package dev.konraditurbe.osmosis.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Per-file command payloads, pinned to bytes captured off DJI Mimo talking to a Nano.
 *
 * These were built by inspection and had never been checked against the reference app; a wrong byte
 * here fails silently on hardware (the camera answers, just not the way you wanted) which is the worst
 * kind of protocol bug to chase.
 */
class FileOpPayloadTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val session = CameraSession({}, 9004, true)

    @Test
    fun `favourite matches Mimo byte for byte, counter and all`() {
        // Two consecutive favourites in one Mimo session, on two different files. Note the counter:
        // it advances per request rather than being a constant, which is only visible with two samples.
        assertEquals("010140401040010000000001000000",
            hex(session.favoritePayload(0x40104040L, counter = 1, on = true)))
        assertEquals("0101c03f1040020000000001000000",
            hex(session.favoritePayload(0x40103fc0L, counter = 2, on = true)))
    }

    @Test
    fun `un-favouriting flips exactly one byte`() {
        val on = hex(session.favoritePayload(0x40104040L, 1, on = true))
        val off = hex(session.favoritePayload(0x40104040L, 1, on = false))
        assertEquals(on.length, off.length)
        assertEquals("only the flag differs", 1, on.zip(off).count { it.first != it.second })
    }
}
