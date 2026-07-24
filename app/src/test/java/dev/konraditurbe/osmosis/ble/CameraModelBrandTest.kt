package dev.konraditurbe.osmosis.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Xtra Edge Pro is a rebadged Osmo Action 5 Pro and advertises the **same** model id `0x0015`,
 * but 10004/no-poke was only ever confirmed on the Xtra — a rebrand firmware change, not a DJI one.
 * These pin the split so a genuine DJI unit never silently inherits the rebrand's datalink config.
 */
class CameraModelBrandTest {

    @Test
    fun `xtra oui is detected as the xtra brand`() {
        assertEquals(Brand.XTRA, Brand.of("EC:9E:EA:11:22:33", "XtraEdgePro-C2D8"))
        // Even a DJI-looking name loses to the hardware OUI.
        assertEquals(Brand.XTRA, Brand.of("EC:9E:EA:11:22:33", "OsmoAction5Pro-1234"))
    }

    @Test
    fun `dji unit is detected as dji`() {
        assertEquals(Brand.DJI, Brand.of("34:12:78:AA:BB:CC", "OsmoAction5Pro-1234"))
    }

    @Test
    fun `same model id resolves differently per brand`() {
        val xtra = CameraModel.resolve(0x0015, "XtraEdgePro-C2D8", Brand.XTRA)
        val dji = CameraModel.resolve(0x0015, "OsmoAction5Pro-1234", Brand.DJI)

        // Xtra: the confirmed rebrand quirk.
        assertEquals("Xtra Edge Pro", xtra.name)
        assertEquals(10004, xtra.datalinkPort)
        assertFalse(xtra.tcpPoke)
        assertTrue(xtra.verified)

        // Genuine DJI: DJI-standard config, now tester-confirmed on real hardware.
        assertEquals("Osmo Action 5 Pro", dji.name)
        assertEquals(9004, dji.datalinkPort)
        assertTrue(dji.tcpPoke)
        assertTrue("a real Action 5 Pro browses + downloads on 9004 (tester-confirmed)", dji.verified)
    }

    @Test
    fun `alternate flips the datalink config both ways`() {
        val dji = CameraModel.resolve(0x0015, "OsmoAction5Pro-1234", Brand.DJI)
        val alt = dji.alternate()
        assertEquals(10004, alt.datalinkPort)
        assertFalse(alt.tcpPoke)
        assertFalse(alt.verified)

        val back = alt.alternate()
        assertEquals(9004, back.datalinkPort)
        assertTrue(back.tcpPoke)
    }

    @Test
    fun `unbranded models keep the dji default`() {
        val nano = CameraModel.resolve(0x0019, "OsmoNano-C2D8", Brand.DJI)
        assertEquals(9004, nano.datalinkPort)
        assertTrue(nano.tcpPoke)
        assertTrue(nano.verified)
    }
}
