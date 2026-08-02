package dev.konraditurbe.osmosis.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Per-file command payloads, pinned to bytes captured off DJI Mimo talking to a real Nano.
 *
 * Both commands carry a **per-request counter** that has to advance. From a single sample it is
 * indistinguishable from the handle count — which is exactly how the delete one came to be written as
 * the count, leaving the first delete of a session correct and every one after it a repeat.
 */
class FileOpPayloadTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val session = CameraSession({}, 9004, true)

    // ---- delete (0x00/0x28) ------------------------------------------------------------------------

    @Test
    fun `delete matches Mimo byte for byte, and the counter advances`() {
        // Two consecutive deletes in one Mimo session. The count stays 01; the field after the handle
        // goes 01 then 02.
        assertEquals("010052104001000000000100000001010000",
            hex(session.deletePayload(listOf(0x40105200L), counter = 1)))
        assertEquals("014052104002000000000100000001010000",
            hex(session.deletePayload(listOf(0x40105240L), counter = 2)))
    }

    @Test
    fun `the delete counter is not the handle count`() {
        // The regression this guards: sending the count where the counter belongs. Identical at
        // counter = 1, which is why it survived so long.
        assertNotEquals(
            hex(session.deletePayload(listOf(0x40105200L), counter = 1)),
            hex(session.deletePayload(listOf(0x40105200L), counter = 2)),
        )
    }

    @Test
    fun `the trailing selector is identical on a one-store and a two-store camera`() {
        // Long assumed to be a storage selector, copied from a single Nano capture. An Xtra with both
        // an SD card and internal memory sends byte-identical bytes, so it selects nothing.
        assertEquals("01010000", hex(session.deletePayload(listOf(0x400403C0L), counter = 1)).takeLast(8))
        assertEquals("01010000", hex(session.deletePayload(listOf(0x40105200L), counter = 9)).takeLast(8))
    }

    // ---- favourite (0x02/0xbf) ---------------------------------------------------------------------

    @Test
    fun `favourite matches Mimo byte for byte, counter and all`() {
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
