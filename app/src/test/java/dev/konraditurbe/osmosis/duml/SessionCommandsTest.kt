package dev.konraditurbe.osmosis.duml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the receiver addressing of Mimo's session commands against frames captured from a real
 * Mimo→Nano HCI snoop. These are easy to get wrong and fail *silently*: addressed to the camera
 * (0x01) instead of their own subsystem, the Nano just answers `e0` (reject) and never wakes.
 *
 * Reference frames (Mimo, 2026-07-23 22:35:43):
 *   00/2b  55 0f 04 a2 02 **f0** 1b cb 40 00 2b 04 00 9a b9
 *   53/10  55 11 04 92 02 **1c** 1d cb 40 53 10 00 00 00 00 89 4a
 * Receiver byte is `(id << 5) | type`.
 */
class SessionCommandsTest {

    private fun sender(f: ByteArray) = f[4].toInt() and 0xFF
    private fun receiver(f: ByteArray) = f[5].toInt() and 0xFF
    private fun cmdSet(f: ByteArray) = f[9].toInt() and 0xFF
    private fun cmdId(f: ByteArray) = f[10].toInt() and 0xFF
    private fun payload(f: ByteArray) = f.copyOfRange(11, f.size - 2)

    @Test
    fun `session ping 0x00-0x2b targets type 0x10 id 7 (receiver 0xF0) not the camera`() {
        val f = OsmoCommands.sessionPing(OsmoCommands.SESSION_WAKE)
        assertEquals(0x02, sender(f))
        assertEquals("must be 0xF0; 0x01 (camera) gets rejected with e0", 0xF0, receiver(f))
        assertEquals(0x00, cmdSet(f)); assertEquals(0x2B, cmdId(f))
        assertEquals(0x10, receiver(f) and 0x1F)   // type 0x10
        assertEquals(7, receiver(f) shr 5)         // id 7
        assertEquals(listOf(0x04, 0x00), payload(f).map { it.toInt() and 0xFF })
    }

    @Test
    fun `keepalive uses the same target with the 01 01 payload`() {
        val f = OsmoCommands.sessionPing(OsmoCommands.SESSION_KEEPALIVE)
        assertEquals(0xF0, receiver(f))
        assertEquals(listOf(0x01, 0x01), payload(f).map { it.toInt() and 0xFF })
    }

    @Test
    fun `0x53-0x10 targets type 0x1C not the camera`() {
        val f = OsmoCommands.session5310()
        assertEquals(0x02, sender(f))
        assertEquals("must be 0x1C; 0x01 (camera) gets rejected with e0", 0x1C, receiver(f))
        assertEquals(0x53, cmdSet(f)); assertEquals(0x10, cmdId(f))
        assertEquals(0x1C, receiver(f) and 0x1F)   // type 0x1C
        assertEquals(0, receiver(f) shr 5)         // id 0
        assertEquals(listOf(0, 0, 0, 0), payload(f).map { it.toInt() and 0xFF })
    }

    @Test
    fun `wifi commands still address the wifi subsystem`() {
        val f = OsmoCommands.wifiQuery(0x07)
        assertEquals(0x07, receiver(f))
        assertEquals(0x07, cmdSet(f)); assertEquals(0x07, cmdId(f))
    }
}
