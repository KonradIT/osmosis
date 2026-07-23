package dev.konraditurbe.osmosis.rsdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the camera `device_id` read against DJI's own R-SDK docs
 * (`Osmo-GPS-Controller-Demo/docs/protocol_data_segment.md`).
 *
 * The id sits at offset 0 of the Connection Request payload as a little-endian `uint32_t`, so a
 * documented `0xFF44` is `44 ff 00 00` on the wire — get the order backwards and every camera reads
 * as an unknown id, which is exactly the value we'd be watching for on an untested unit.
 */
class RsdkDeviceIdTest {

    /** connection_request_command_frame, 33 B: device_id @0, verify_mode @26, verify_data @27. */
    private fun connectionPayload(deviceId: Int, verifyMode: Int = 2, verifyData: Int = 0) =
        ByteArray(33).apply {
            this[0] = (deviceId and 0xFF).toByte()
            this[1] = ((deviceId ushr 8) and 0xFF).toByte()
            this[2] = ((deviceId ushr 16) and 0xFF).toByte()
            this[3] = ((deviceId ushr 24) and 0xFF).toByte()
            this[26] = verifyMode.toByte()
            this[27] = (verifyData and 0xFF).toByte()
            this[28] = ((verifyData ushr 8) and 0xFF).toByte()
        }

    @Test
    fun `device id is read little-endian from offset 0`() {
        assertEquals(0xFF44, RsdkProtocol.cameraDeviceId(connectionPayload(0xFF44)))
        // The literal wire bytes for an Action 5 Pro, spelled out rather than round-tripped.
        val wire = byteArrayOf(0x44, 0xFF.toByte(), 0, 0) + ByteArray(29)
        assertEquals(0xFF44, RsdkProtocol.cameraDeviceId(wire))
    }

    @Test
    fun `every id in DJI's published table resolves to a name`() {
        val documented = mapOf(
            0xFF33 to "Osmo Action 4",
            0xFF44 to "Osmo Action 5 Pro",
            0xFF55 to "Osmo Action 6",
            0xFF66 to "Osmo 360",
        )
        assertEquals(documented, RsdkProtocol.DEVICE_IDS)
        for ((id, name) in documented) {
            assertEquals("0x%04X ($name)".format(id), RsdkProtocol.deviceIdLabel(id))
        }
    }

    @Test
    fun `an id outside the table is called out, not silently accepted`() {
        // The Xtra rebrand may well answer with an id of its own; that's a finding, not an error.
        val label = RsdkProtocol.deviceIdLabel(0xFF99)
        assertTrue("must flag the unknown id: $label", label.contains("UNKNOWN"))
        assertTrue("must show the raw id: $label", label.contains("0x0000FF99"))
    }

    @Test
    fun `a payload too short to hold an id yields null instead of garbage`() {
        assertNull(RsdkProtocol.cameraDeviceId(ByteArray(3)))
        assertNull(RsdkProtocol.cameraDeviceId(ByteArray(0)))
    }
}
