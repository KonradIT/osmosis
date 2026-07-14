package dev.konraditurbe.osmosis.net

import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

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

    /** HTTP status code for a HEAD, or -1 on error. */
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
            if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() } else null
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

    /**
     * Stream a file to [out], starting at [rangeStart] (0 = whole file). [onProgress] gets the
     * running total-bytes-written. Returns true on clean completion.
     */
    fun download(path: String, out: OutputStream, rangeStart: Long, onProgress: (Long) -> Unit): Boolean {
        val c = open(path, "GET", rangeStart)
        return try {
            val code = c.responseCode
            if (code !in 200..299) {
                log("download $path -> HTTP $code")
                return false
            }
            val buf = ByteArray(65536)
            var total = rangeStart.coerceAtLeast(0)
            c.inputStream.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    onProgress(total)
                }
            }
            out.flush()
            true
        } catch (e: Exception) {
            log("download $path ERR ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            c.disconnect()
        }
    }
}
