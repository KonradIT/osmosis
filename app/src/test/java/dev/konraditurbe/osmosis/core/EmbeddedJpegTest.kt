package dev.konraditurbe.osmosis.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The only source of a photo thumbnail on a drone, so it is worth pinning: a still has no THM, SCR or
 * any other rendition on the card, and `/v1` serves nothing but the original.
 */
class EmbeddedJpegTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** A JPEG with an APP1 (EXIF) segment wrapping [thumb], then a segment of image data. */
    private fun jpegWithExif(thumb: ByteArray, trailing: ByteArray = ByteArray(0)): ByteArray {
        val exifHeader = "Exif".toByteArray() + bytes(0x00, 0x00)
        val body = exifHeader + thumb
        val len = body.size + 2
        return bytes(0xFF, 0xD8) +                                  // SOI
            bytes(0xFF, 0xE1, (len shr 8) and 0xFF, len and 0xFF) + // APP1 + length
            body + trailing
    }

    private val thumb = bytes(0xFF, 0xD8, 0x11, 0x22, 0x33, 0xFF, 0xD9)

    @Test
    fun `lifts the thumbnail out of the APP1 segment`() {
        assertArrayEquals(thumb, EmbeddedJpeg.fromHeader(jpegWithExif(thumb)))
    }

    @Test
    fun `ignores JPEG markers that appear in image data rather than in APP1`() {
        // A 14 MP frame's entropy-coded data contains 0xFFD8 byte pairs by chance. Scanning for "the
        // second SOI" would return garbage; confining the search to APP1 and stopping at SOS does not.
        val sos = bytes(0xFF, 0xDA, 0x00, 0x02) + bytes(0xFF, 0xD8, 0x77, 0xFF, 0xD9)
        val noExif = bytes(0xFF, 0xD8) + bytes(0xFF, 0xDB, 0x00, 0x04, 0x01, 0x02) + sos
        assertNull(EmbeddedJpeg.fromHeader(noExif))
    }

    @Test
    fun `walks past other segments to reach APP1`() {
        val app0 = bytes(0xFF, 0xE0, 0x00, 0x04, 0x0A, 0x0B)       // JFIF
        val withApp0 = bytes(0xFF, 0xD8) + app0 +
            jpegWithExif(thumb).copyOfRange(2, jpegWithExif(thumb).size)
        assertArrayEquals(thumb, EmbeddedJpeg.fromHeader(withApp0))
    }

    @Test
    fun `a truncated head yields nothing rather than a broken image`() {
        val full = jpegWithExif(thumb)
        assertNull("cut before the thumbnail's EOI", EmbeddedJpeg.fromHeader(full.copyOfRange(0, full.size - 2)))
        assertNull(EmbeddedJpeg.fromHeader(ByteArray(0)))
        assertNull("not a JPEG at all", EmbeddedJpeg.fromHeader(bytes(0x00, 0x01, 0x02, 0x03)))
    }

    @Test
    fun `the head request stays inside EXIF's own size limit`() {
        // APP1 carries a u16 length, so it cannot exceed 64 kB — one ranged request always suffices,
        // against ~14 MB for the full frame.
        assertEquals(65_536, EmbeddedJpeg.HEAD_BYTES)
    }
}
