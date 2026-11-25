package dev.konraditurbe.osmosis.net

import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.matchesOriginalPrefix
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

internal fun InputStream.readPrefix(limit: Int): ByteArray {
    val buf = ByteArray(limit)
    var count = 0
    while (count < limit) {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
        val n = read(buf, count, limit - count)
        if (n <= 0) break
        count += n
    }
    return if (count == limit) buf else buf.copyOf(count)
}

internal data class ContentRange(val start: Long, val end: Long, val total: Long)
private val rangePattern = Regex("""bytes (\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

internal fun HttpURLConnection.contentRange(): ContentRange? {
    val value = getHeaderField("Content-Range") ?: return null
    val parts = rangePattern.matchEntire(value.trim())?.groupValues ?: return null
    val start = parts[1].toLongOrNull() ?: return null
    val end = parts[2].toLongOrNull() ?: return null
    val total = parts[3].toLongOrNull() ?: return null
    return if (start <= end && end < total) ContentRange(start, end, total) else null
}

/** Thin client for the camera's lighttpd /v2 file API. Process must be bound to the camera net. */
class HttpClient(
    private val ip: String = "192.168.2.1",
    private val log: (String) -> Unit = {},
) {
    /** Absolute URL for a camera path, e.g. for MediaExtractor/MediaPlayer data sources. */
    fun url(path: String): String = "http://$ip$path"

    private fun open(path: String, method: String, rangeStart: Long = -1): HttpURLConnection =
        (URL("http://$ip$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 20000
            requestMethod = method
            setRequestProperty("Accept", "*/*")
            if (rangeStart > 0) setRequestProperty("Range", "bytes=$rangeStart-")
        }

    /**
     * HTTP status code for a HEAD, or -1 on error.
     *
     * Note that on a drone **-1 is the normal answer for a file that isn't there**: that firmware
     * reports a missing file by closing the connection rather than sending a 404, so the request throws
     * instead of returning a status. Callers must not read -1 as "network problem".
     */
    fun headCode(path: String): Int {
        val c = open(path, "HEAD")
        return try {
            c.responseCode
        } catch (e: Exception) {
            log("HEAD $path ERR ${e.javaClass.simpleName}: ${e.message}"); -1
        } finally {
            c.disconnect()
        }
    }

    /** Content-Length of a file, or -1 if missing/not found. */
    fun head(path: String): Long {
        val c = open(path, "HEAD")
        return try {
            if (c.responseCode in 200..299) c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1
            else -1
        } catch (_: Exception) {
            -1
        } finally {
            c.disconnect()
        }
    }

    /** Fetch an inclusive byte range from start to end. Null on error. */
    fun getRange(path: String, start: Long, end: Long): ByteArray? {
        val c = (URL("http://$ip$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 20000
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=$start-$end")
        }
        return try {
            val length = end - start + 1
            if (start < 0 || length !in 1..Int.MAX_VALUE.toLong()) return null
            when (c.responseCode) {
                200 -> if (start != 0L) return null
                206 -> if (c.contentRange()?.start != start) return null
                else -> return null
            }
            c.inputStream.use { it.readPrefix(length.toInt()) }
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    /** Fetch a whole (small) file, e.g. a thumbnail. Null on error. */
    fun getBytes(path: String): ByteArray? {
        val c = open(path, "GET")
        return try {
            val code = c.responseCode
            if (code !in 200..299) null else c.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    /** Outcome of a streamed download — see [download]. */
    enum class Fetch {
        /** The server sent the whole (remaining) body and closed cleanly. */
        DONE,
        /** The transfer started but ended early. Whatever arrived is valid; resume from the new length. */
        INTERRUPTED,
        /** A ranged request was answered `200` (whole file) instead of `206`. The body starts at byte 0,
         *  so appending it at the resume offset would corrupt the file — restart from zero instead. */
        RANGE_IGNORED,
        /** No usable response at all (bad status, connect failure). Nothing was written. */
        FAILED,
    }

    /**
     * Stream a file to [out], starting at [rangeStart] (0 = whole file). [onProgress] gets the
     * running total-bytes-written.
     *
     * `Connection: close` is deliberate: a long transfer the camera cuts leaves a socket that must not
     * be reused, and Android's connection pool will otherwise hand the same dead socket to the very next
     * request — which then fails instantly with "unexpected end of stream" having moved no bytes.
     */
    fun download(path: String, out: OutputStream, rangeStart: Long, checkedOriginal: CameraFile? = null, onProgress: (Long) -> Unit): Fetch {
        val c = open(path, "GET", rangeStart).apply { setRequestProperty("Connection", "close") }
        if (checkedOriginal != null) c.instanceFollowRedirects = false
        return try {
            val code = c.responseCode
            if (code !in 200..299) {
                log("download $path -> HTTP $code")
                return Fetch.FAILED
            }
            // A range was asked for and the server answered with the whole file. Writing this body at
            // the resume offset would splice the file's opening bytes into its middle, producing a
            // plausible-sized but corrupt video — so report it and let the caller start over.
            if (rangeStart > 0 && code != 206) {
                log("download $path -> HTTP $code for a ranged request (Range ignored) — restarting at 0")
                return Fetch.RANGE_IGNORED
            }
            val expected = checkedOriginal?.sizeBytes ?: 0L
            if (checkedOriginal != null) {
                val range = c.contentRange()
                val length = c.getHeaderFieldLong("Content-Length", -1)
                val valid = when (code) {
                    200 -> expected == 0L || length == -1L || length == expected
                    206 -> range != null && range.start == rangeStart &&
                        (expected == 0L || range.total == expected) && range.end == range.total - 1 &&
                        (length == -1L || length == range.end - range.start + 1)
                    else -> false
                }
                if (!valid) return Fetch.FAILED
            }
            val buf = ByteArray(65536)
            var total = rangeStart.coerceAtLeast(0)
            c.inputStream.use { ins ->
                if (rangeStart == 0L && checkedOriginal != null) {
                    val head = ins.readPrefix(64)
                    if (!checkedOriginal.matchesOriginalPrefix(head) ||
                        (expected > 0 && head.size > expected)) return Fetch.FAILED
                    out.write(head)
                    total += head.size
                    onProgress(total)
                }
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (expected > 0 && n > expected - total) return Fetch.FAILED
                    out.write(buf, 0, n)
                    total += n
                    onProgress(total)
                }
            }
            out.flush()
            if (expected > 0 && total != expected) Fetch.INTERRUPTED else Fetch.DONE
        } catch (e: Exception) {
            runCatching { out.flush() }   // keep whatever arrived, so the resume starts from it
            log("download $path ERR ${e.javaClass.simpleName}: ${e.message}")
            Fetch.INTERRUPTED
        } finally {
            c.disconnect()
        }
    }
}
