package dev.konraditurbe.osmosis.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the wake advertisement to DJI's documented bytes:
 * `{ 10, 0xff, 'W','K','P','1','2','3','4','5','6' }` (protocol_data_segment.md, 001A), where 1..6 is
 * the target MAC reversed. The company-id smuggling in [CameraWaker] is easy to get subtly wrong —
 * one swapped byte and the camera silently ignores the packet — so assert the whole frame.
 */
class CameraWakerTest {

    @Test
    fun `packet matches DJI's documented layout byte for byte`() {
        val pkt = CameraWaker.wakePacket("AA:BB:CC:DD:EE:FF")!!
        assertArrayEquals(
            byteArrayOf(
                10,                       // AD length
                0xFF.toByte(),            // AD type: manufacturer specific
                'W'.code.toByte(), 'K'.code.toByte(), 'P'.code.toByte(),
                0xFF.toByte(), 0xEE.toByte(), 0xDD.toByte(),  // MAC reversed
                0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte(),
            ),
            pkt,
        )
        assertEquals("DJI's sample is 11 bytes incl. the length byte", 11, pkt.size)
    }

    @Test
    fun `company id serialises little-endian to the W K magic`() {
        // Android writes the id LE, so 0x4B57 must land as 'W'(0x57) then 'K'(0x4B).
        assertEquals(0x57, CameraWaker.WKP_MAGIC_AS_COMPANY_ID and 0xFF)
        assertEquals(0x4B, (CameraWaker.WKP_MAGIC_AS_COMPANY_ID shr 8) and 0xFF)
        assertEquals('W'.code, CameraWaker.WKP_MAGIC_AS_COMPANY_ID and 0xFF)
        assertEquals('K'.code, (CameraWaker.WKP_MAGIC_AS_COMPANY_ID shr 8) and 0xFF)
    }

    @Test
    fun `payload is P plus the reversed mac`() {
        val p = CameraWaker.wakePayload("12:34:56:78:9A:BC")!!
        assertArrayEquals(
            byteArrayOf('P'.code.toByte(), 0xBC.toByte(), 0x9A.toByte(), 0x78, 0x56, 0x34, 0x12),
            p,
        )
        assertEquals(7, p.size) // 'P' + 6 MAC bytes; +2 company id = 9 after the AD type
    }

    @Test
    fun `malformed macs are rejected rather than broadcast as garbage`() {
        assertNull(CameraWaker.wakePayload("AA:BB:CC"))
        assertNull(CameraWaker.wakePayload(""))
        assertNull(CameraWaker.wakePayload("ZZ:BB:CC:DD:EE:FF"))
    }
}
