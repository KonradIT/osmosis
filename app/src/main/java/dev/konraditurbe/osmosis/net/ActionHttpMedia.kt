package dev.konraditurbe.osmosis.net

import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.matchesOriginalPrefix
import dev.konraditurbe.osmosis.core.urlPath
import dev.konraditurbe.osmosis.dcf.DcfAddressing.Route
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.FutureTask
import kotlin.concurrent.thread

/** Test the original a download would actually fetch. Some web servers answer unknown URLs with HTML. */
internal class ActionHttpMedia(private val authority: String, private val log: (String) -> Unit) {
    fun findRoute(samples: List<CameraFile>, routes: List<Route>): Route? {
        try {
            for (route in routes) for (file in samples) {
                if (Thread.currentThread().isInterrupted) return null
                if (serves(file.copy(dcfRoute = route))) return route
            }
        } catch (e: ConnectException) {
            // The routes share a server. A refused TCP connection applies to every path for this pass.
            log("HTTP connect $authority: ${e.message}")
        }
        return null
    }

    /** Only the caller touches UDP. A slow HTTP read must leave that thread free to acknowledge it. */
    fun checkWhileConnected(samples: List<CameraFile>, routes: List<Route>, retry: Boolean,
                            serviceDatalink: () -> Boolean): Route? {
        val check = FutureTask<Route?> {
            var route = findRoute(samples, routes)
            while (retry && route == null && !Thread.currentThread().isInterrupted) {
                Thread.sleep(2000)
                route = findRoute(samples, routes)
            }
            route
        }
        thread(name = "Action album HTTP", isDaemon = true) { check.run() }
        return try {
            while (!check.isDone) {
                if (!serviceDatalink()) return null
            }
            check.get()
        } finally {
            check.cancel(true)
        }
    }

    private fun serves(file: CameraFile): Boolean {
        val path = file.urlPath()
        val conn = URL("http://$authority$path").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 1000
            conn.readTimeout = 1500
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Range", "bytes=0-63")
            val code = conn.responseCode
            val range = conn.getHeaderField("Content-Range")
            val partial = conn.contentRange()
            val responseLength = conn.getHeaderFieldLong("Content-Length", -1)
            val length = if (code == 206) partial?.total else responseLength
            val span = if (partial?.start == 0L && partial.end < 64) partial.end.toInt() + 1 else 0
            val valid = code == 200 || (code == 206 && span > 0 &&
                (responseLength == -1L || responseLength == span.toLong()))
            val matched = valid && (length == -1L || file.sizeBytes == 0L || length == file.sizeBytes) &&
                conn.inputStream.use {
                    val prefix = it.readPrefix(64)
                    (code != 206 || prefix.size == span) && file.matchesOriginalPrefix(prefix)
                }
            log("HTTP GET $path → $code, range=$range, length=$length, media signature matched=$matched")
            matched
        } catch (e: ConnectException) {
            throw e
        } catch (e: java.io.IOException) {
            log("HTTP GET $path → ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            conn.disconnect()
        }
    }
}
