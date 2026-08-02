package dev.konraditurbe.osmosis.core

/**
 * Lift the thumbnail a camera embeds in a JPEG's EXIF block.
 *
 * This exists because a DJI drone stores **no thumbnail rendition for a still** — `/v1` serves only the
 * original — so the only small image available is the one inside the file itself. EXIF caps `APP1` at
 * 64 kB and it sits near the front, so the whole thing arrives in one ranged request of the first 64 kB
 * rather than the ~14 MB of the full frame.
 *
 * Deliberately structural rather than "find the second `FFD8`": the compressed image data of a large
 * photo can contain those bytes, so the search is confined to the `APP1` segment and stops at `SOS`
 * where entropy-coded data begins.
 */
object EmbeddedJpeg {

    /** How much of a file's head to fetch. EXIF's `APP1` is a u16 length, so it cannot exceed this. */
    const val HEAD_BYTES = 65_536

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16be(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

    /**
     * The embedded thumbnail in [head] (the first bytes of a JPEG), or null if there isn't one.
     * [head] may be truncated mid-segment; a thumbnail that runs past the end is simply not found.
     */
    fun fromHeader(head: ByteArray): ByteArray? {
        if (head.size < 4 || u8(head, 0) != 0xFF || u8(head, 1) != 0xD8) return null
        var i = 2
        while (i + 4 <= head.size) {
            if (u8(head, i) != 0xFF) { i++; continue }          // resync on padding
            when (val marker = u8(head, i + 1)) {
                0xD8, 0x01, in 0xD0..0xD7 -> { i += 2 }         // standalone, no length
                0xDA, 0xD9 -> return null                       // image data starts; nothing embedded
                else -> {
                    val len = u16be(head, i + 2)
                    if (len < 2) return null
                    if (marker == 0xE1) {                       // APP1 — where EXIF lives
                        val end = minOf(i + 2 + len, head.size)
                        jpegWithin(head, i + 4, end)?.let { return it }
                    }
                    i += 2 + len
                }
            }
        }
        return null
    }

    /** A complete `FFD8`…`FFD9` JPEG inside `[from, to)`, or null. */
    private fun jpegWithin(b: ByteArray, from: Int, to: Int): ByteArray? {
        var soi = -1
        var i = from
        while (i < to - 1) {
            if (u8(b, i) == 0xFF && u8(b, i + 1) == 0xD8) { soi = i; break }
            i++
        }
        if (soi < 0) return null
        var j = to - 2
        while (j > soi) {
            if (u8(b, j) == 0xFF && u8(b, j + 1) == 0xD9) return b.copyOfRange(soi, j + 2)
            j--
        }
        return null
    }
}
