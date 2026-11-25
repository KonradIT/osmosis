package dev.konraditurbe.osmosis.camera

import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.MediaFileType
import dev.konraditurbe.osmosis.core.urlPath
import dev.konraditurbe.osmosis.dcf.DcfAddressing.Route
import dev.konraditurbe.osmosis.net.HttpClient
import dev.konraditurbe.osmosis.net.ActionHttpMedia
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ActionHttpMediaTest {
    private val clip = ByteArray(512).also { "ftyp".toByteArray().copyInto(it, 4) }
    private val file = CameraFile("DCIM/100MEDIA/DJI_0001_002.MP4", "", fileIndex = 0x640001,
        sizeBytes = clip.size.toLong(), mediaType = MediaFileType.MP4, segmentIndex = 2)

    private class Request(val socket: Socket) {
        private val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val path = reader.readLine().split(' ')[1]
        val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }
            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }

        fun reply(code: Int, body: ByteArray, range: String? = null, sendLength: Boolean = true) {
            val header = buildString {
                append("HTTP/1.1 $code OK\r\nConnection: close\r\n")
                if (sendLength) append("Content-Length: ${body.size}\r\n")
                if (range != null) append("Content-Range: $range\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(header.toByteArray(Charsets.US_ASCII))
                write(body)
                flush()
            }
        }
    }

    private fun withCamera(handler: (Request) -> Unit, check: (String) -> Unit) {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: SocketException) {
                    if (server.isClosed) break else throw e
                }
                socket.use { handler(Request(it)) }
            }
        }
        try {
            check("127.0.0.1:${server.localPort}")
        } finally {
            server.close()
            worker.join(2000)
        }
    }

    @Test fun `an HTML index page falls through to the actual static clip, which downloads intact`() {
        withCamera({ request ->
            if (request.path == "/${file.path}") request.reply(200, clip)
            else request.reply(200, "<html>".padEnd(512).toByteArray())
        }) { host ->
            val route = ActionHttpMedia(host) {}.findRoute(listOf(file), listOf(Route.V1, Route.STATIC))
            assertEquals(Route.STATIC, route)
            val client = HttpClient(host)
            val path = file.copy(dcfRoute = route!!).urlPath()
            assertArrayEquals(clip.copyOf(64), client.getRange(path, 0, 63))
            val out = ByteArrayOutputStream()
            assertEquals(HttpClient.Fetch.DONE, client.download(path, out, 0, file) {})
            assertArrayEquals(clip, out.toByteArray())
        }
    }

    @Test fun `a segmented original can be verified on v1 and resumed at the exact offset`() {
        val requested = java.util.concurrent.ConcurrentLinkedQueue<String>()
        withCamera({ req ->
            requested.add(req.path)
            val start = if (req.headers["range"] == "bytes=90-") 90 else 0
            val end = if (start == 90) clip.lastIndex else 63
            req.reply(206, clip.copyOfRange(start, end + 1), "bytes $start-$end/${clip.size}")
        }) { host ->
            assertEquals(Route.V1, ActionHttpMedia(host) {}.findRoute(listOf(file), listOf(Route.V1)))
            val out = ByteArrayOutputStream()
            assertEquals(HttpClient.Fetch.DONE, HttpClient(host).download(file.urlPath(), out, 90, file) {})
            assertArrayEquals(clip.copyOfRange(90, clip.size), out.toByteArray())
            assertEquals(List(2) { "/v1?file_index=6553601&file_subtype=0&file_seg_subindex=2" }, requested.toList())
        }
    }

    @Test fun `neither a mismatched range nor an HTML original can enter the output`() {
        withCamera({ req ->
            if (req.path == "/html") req.reply(200, "<!doctype html>".padEnd(512).toByteArray())
            else req.reply(206, clip.copyOf(64), "bytes 12-75/512")
        }) { host ->
            assertNull(ActionHttpMedia(host) {}.findRoute(listOf(file), listOf(Route.V1)))
            val client = HttpClient(host)
            val out = ByteArrayOutputStream()
            assertEquals(HttpClient.Fetch.FAILED, client.download("/range", out, 64, file) {})
            assertEquals(HttpClient.Fetch.FAILED, client.download("/html", out, 0, file) {})
            assertEquals(0, out.size())
        }
    }

    @Test fun `the session can acknowledge UDP while the camera is still preparing HTTP`() {
        val waitingForAck = CountDownLatch(1)
        val acknowledged = CountDownLatch(1)
        withCamera({ req ->
            waitingForAck.countDown()
            if (acknowledged.await(1, TimeUnit.SECONDS)) req.reply(200, clip)
            else req.reply(503, byteArrayOf(0))
        }) { host ->
            val sessionThread = Thread.currentThread()
            val probe = ActionHttpMedia(host) {}
            val route = probe.checkWhileConnected(listOf(file), listOf(Route.V1), retry = false) {
                assertSame(sessionThread, Thread.currentThread())
                if (waitingForAck.await(20, TimeUnit.MILLISECONDS)) acknowledged.countDown()
                Thread.sleep(5)
                true
            }
            assertEquals(Route.V1, route)
        }
    }

    @Test fun `link loss stops HTTP retries before another route check`() {
        val requested = CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        withCamera({ req ->
            reads.incrementAndGet()
            req.reply(404, byteArrayOf(0))
            requested.countDown()
        }) { host ->
            val probe = ActionHttpMedia(host) {}
            val route = probe.checkWhileConnected(listOf(file), listOf(Route.V1), retry = true) {
                !requested.await(1, TimeUnit.SECONDS)
            }
            assertNull(route)
            assertEquals(1, reads.get())
        }
    }

    @Test fun `a clean EOF before the manifest length must resume`() {
        withCamera({ req ->
            req.reply(200, clip.copyOf(83), sendLength = false)
        }) { host ->
            val out = ByteArrayOutputStream()
            assertEquals(HttpClient.Fetch.INTERRUPTED, HttpClient(host).download(file.urlPath(), out, 0, file) {})
            assertArrayEquals(clip.copyOf(83), out.toByteArray())
        }
    }
}
