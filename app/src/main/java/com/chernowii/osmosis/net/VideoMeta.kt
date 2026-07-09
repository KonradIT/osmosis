package com.chernowii.osmosis.net

import com.chernowii.osmosis.core.CameraFile

/**
 * Reads an MP4/MOV clip's duration by parsing the `mvhd` movie-header atom, fetched over HTTP
 * with Range requests (so it respects the process network binding — unlike MediaMetadataRetriever).
 * DJI writes `moov` at the end of the file, so we try the head region first then a tail window.
 */
object VideoMeta {
    private val MVHD = byteArrayOf('m'.code.toByte(), 'v'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte())

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

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
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
