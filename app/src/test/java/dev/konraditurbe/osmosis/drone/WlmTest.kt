package dev.konraditurbe.osmosis.drone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WLM entry decision and its two request bodies.
 *
 * Worth pinning offline because the failure it prevents is silent: send the wrong entry command and
 * the aircraft simply carries on beaconing, which is indistinguishable from an aircraft that does not
 * support QuickTransfer at all. That mistake cost the Neo 2 several hardware runs.
 */
class WlmTest {

    /** A 27-byte `0x51/0x04` body with the three fields this decision reads. */
    private fun osd(messageVersion: Int, localLiveview: Int = 4, peerLiveview: Int = 4) =
        ByteArray(Wlm.DEVICE_OSD_LEN).also {
            it[13] = localLiveview.toByte()
            it[14] = peerLiveview.toByte()
            it[20] = messageVersion.toByte()
        }

    @Test
    fun `service mode is selected only above message version 1`() {
        // The exact handler condition: > 1, so version 1 itself takes the fallback.
        assertFalse(Wlm.parseDeviceOsd(osd(0))!!.serviceModeSupported)
        assertFalse(Wlm.parseDeviceOsd(osd(1))!!.serviceModeSupported)
        assertTrue(Wlm.parseDeviceOsd(osd(2))!!.serviceModeSupported)
        assertTrue(Wlm.parseDeviceOsd(osd(255))!!.serviceModeSupported)
    }

    @Test
    fun `a short OSD body decodes to nothing rather than a wrong branch`() {
        assertNull(Wlm.parseDeviceOsd(ByteArray(Wlm.DEVICE_OSD_LEN - 1)))
        assertNull(Wlm.parseDeviceOsd(ByteArray(0)))
    }

    @Test
    fun `the fallback refuses to guess when local and peer live-view modes disagree`() {
        assertEquals(4, Wlm.parseDeviceOsd(osd(1, localLiveview = 4, peerLiveview = 4))!!
            .liveviewLinkModeForFallback)
        assertNull(
            "a request built on the wrong live-view mode is worse than no request",
            Wlm.parseDeviceOsd(osd(1, localLiveview = 4, peerLiveview = 1))!!.liveviewLinkModeForFallback,
        )
    }

    @Test
    fun `the service-mode request is 32 bytes, download, and carries the serial`() {
        val serial = "1581FA6QC25BS01CHVJQ".toByteArray(Charsets.US_ASCII)
        val enter = Wlm.serviceModeRequest(enter = true, serial = serial)
        assertEquals(32, enter.size)
        assertEquals("version", 0, enter[0].toInt())
        assertEquals("service = download", 1, enter[1].toInt())
        assertEquals("mode = WIFI_HIGHSPEED", 1, enter[2].toInt())
        assertArrayEquals(serial, enter.copyOfRange(3, 3 + serial.size))
        assertTrue("padded with zeros", enter.copyOfRange(3 + serial.size, 32).all { it == 0.toByte() })

        // Exit differs in exactly one byte — the mode.
        val exit = Wlm.serviceModeRequest(enter = false, serial = serial)
        assertEquals("mode = COMMON", 0, exit[2].toInt())
        assertArrayEquals(enter.copyOfRange(3, 32), exit.copyOfRange(3, 32))
    }

    @Test
    fun `an unknown serial leaves the field zeroed rather than omitted`() {
        val body = Wlm.serviceModeRequest(enter = true, serial = null)
        assertEquals(32, body.size)
        assertTrue(body.copyOfRange(3, 23).all { it == 0.toByte() })
    }

    @Test
    fun `an over-long serial is truncated to the 20-byte field`() {
        val body = Wlm.serviceModeRequest(enter = true, serial = ByteArray(40) { 'A'.code.toByte() })
        assertEquals(32, body.size)
        assertTrue("must not run past the serial field", body.copyOfRange(23, 32).all { it == 0.toByte() })
    }

    /**
     * Three bytes — and emphatically not the Mavic 3's five-byte `05 01 04 01 00` session-open, which
     * shares the command id. Confusing the two is the whole reason this class exists.
     */
    @Test
    fun `the link-mode fallback is requested-liveview-requested`() {
        assertArrayEquals(
            byteArrayOf(4, 2, 4),
            Wlm.linkModeRequest(Wlm.LINK_MODE_WIFI_ONLY, liveviewLinkMode = 2),
        )
        assertArrayEquals(
            byteArrayOf(1, 2, 1),
            Wlm.linkModeRequest(Wlm.LINK_MODE_COMMON, liveviewLinkMode = 2),
        )
        assertEquals(3, Wlm.linkModeRequest(Wlm.LINK_MODE_WIFI_ONLY, 4).size)
    }
}
