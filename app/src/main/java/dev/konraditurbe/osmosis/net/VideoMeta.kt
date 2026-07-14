package dev.konraditurbe.osmosis.net

import dev.konraditurbe.osmosis.core.CameraFile

/**
 * Reads an MP4/MOV clip's duration by parsing the `mvhd` movie-header atom, fetched over HTTP
 * with Range requests (so it respects the process network binding — unlike MediaMetadataRetriever).
 * DJI writes `moov` at the end of the file, so we try the head region first then a tail window.
 */
object VideoMeta {
    private val MVHD = byteArrayOf('m'.code.toByte(), 'v'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte())
    private val TKHD = byteArrayOf('t'.code.toByte(), 'k'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte())

    /**
     * Video-track pixel size from the moov's `tkhd` box (last 8 bytes = width,height as 16.16 fixed).
     * The audio track's tkhd is 0×0, so we return the first track with non-zero dimensions.
     */
    fun resolution(http: HttpClient, file: CameraFile): Pair<Int, Int>? {
        val url = file.urlPath()
        val size = http.head(url)
        var buf = http.getRange(url, 0, 65_535) ?: return null
        if (indexOf(buf, TKHD) < 0 && size > 65_536) {
            buf = http.getRange(url, maxOf(0L, size - 1_048_576), size - 1) ?: return null
        }
        var from = 0
        while (true) {
            val t = indexOf(buf, TKHD, from)
            if (t < 4) return null
            from = t + 4
            val boxLen = be32(buf, t - 4).toInt()
            val boxEnd = t - 4 + boxLen
            if (boxLen in 32..8192 && boxEnd <= buf.size) {
                val w = (be32(buf, boxEnd - 8) shr 16).toInt()
                val h = (be32(buf, boxEnd - 4) shr 16).toInt()
                if (w in 1..12000 && h in 1..12000) return w to h
            }
        }
    }

    /** Duration in milliseconds, or -1 if it can't be determined. */
    fun durationMs(http: HttpClient, file: CameraFile): Long {
        val url = file.urlPath()
        val size = http.head(url)

        var buf = http.getRange(url, 0, 65_535) ?: return -1
        var idx = indexOf(buf, MVHD)
        if (idx < 0 && size > 65_536) {
            val start = maxOf(0L, size - 1_048_576) // last ~1 MB, where moov usually lives
            buf = http.getRange(url, start, size - 1) ?: return -1
            idx = indexOf(buf, MVHD)
        }
        if (idx < 0 || idx + 24 > buf.size) return -1

        // After the 'mvhd' tag: version(1) flags(3) then time fields (widths depend on version).
        val version = buf[idx + 4].toInt() and 0xFF
        val tsOff: Int
        val durOff: Int
        val durIs64: Boolean
        if (version == 1) {
            tsOff = idx + 4 + 4 + 8 + 8; durOff = tsOff + 4; durIs64 = true
        } else {
            tsOff = idx + 4 + 4 + 4 + 4; durOff = tsOff + 4; durIs64 = false
        }
        if (durOff + (if (durIs64) 8 else 4) > buf.size) return -1

        val timescale = be32(buf, tsOff)
        val duration = if (durIs64) be64(buf, durOff) else be32(buf, durOff).toLong()
        if (timescale <= 0 || duration <= 0) return -1
        return duration * 1000 / timescale
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun be64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }
}
