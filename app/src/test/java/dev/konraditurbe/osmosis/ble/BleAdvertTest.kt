package dev.konraditurbe.osmosis.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two DJI advertisement formats, pinned to real captured bytes.
 *
 * The Pocket 4 Pro payload is verbatim from a tester's scan on 2026-08-07
 * (`OsmoPocket4P-6E55`, `9C:5A:8A:BD:6E:56`, company id `0x08AA`), which is the advert that scanned
 * as `model=unknown(0x0000)` and started all this.
 */
class BleAdvertTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** OsmoPocket4P-6E55, as logged. Classic bytes are zero; the id lives at [10:12]. */
    private val pocket4Pro = hex("000000ee0004bd6e5620da000010")

    /** Nano shape: model 0x0019 at [0:2], then MAC, then a trailing byte. */
    private val nano = hex("190000c25a8abdc2d803")

    @Test
    fun `pocket 4 pro resolves through the new format`() {
        val d = BleAdvert.decode(pocket4Pro)
        assertTrue("flag bit at payload[5] should select the new format", d.newFormat)
        assertEquals("product type is the LE u16 at payload[10:12]", 218, d.rawProductType)
        assertEquals("HG224 maps to classic id 0x22", 0x0022, d.modelId)
    }

    @Test
    fun `pocket 4 pro is named and gets a camera profile`() {
        val id = BleAdvert.modelId(pocket4Pro)!!
        assertEquals("OsmoPocket4Pro", BleConstants.MODEL_NAMES[id])
        val m = CameraModel.resolve(id, "OsmoPocket4P-6E55")
        assertEquals("Osmo Pocket 4 Pro", m.name)
        assertEquals(9004, m.datalinkPort)
        assertTrue(m.tcpPoke)
        assertFalse("two stores were listed, so storage must stay resolved per file", m.singleSdStorage)
    }

    /** The classic path must not regress: byte 1 is zero on every camera seen, so the u16 read holds. */
    @Test
    fun `nano still resolves through the classic format`() {
        val d = BleAdvert.decode(nano)
        assertFalse(d.newFormat)
        assertEquals(0x0019, d.modelId)
        assertEquals("OsmoNano", BleConstants.MODEL_NAMES[d.modelId])
    }

    /**
     * The classic id sits where the new format's MAC does, so a legacy advert can trip the flag bit
     * by accident. It is length that saves us — [BleAdvert] only reads a product type when the
     * payload is long enough to actually hold one, and otherwise falls back.
     */
    @Test
    fun `a short payload with the flag bit set falls back to the classic id`() {
        val d = BleAdvert.decode(hex("1900000405"))   // payload[5] absent entirely
        assertFalse(d.newFormat)
        assertEquals(0x0019, d.modelId)

        val d2 = BleAdvert.decode(hex("190000040504"))  // flag set at [5], but no room for [10:12]
        assertFalse(d2.newFormat)
        assertNull(d2.rawProductType)
        assertEquals(0x0019, d2.modelId)
    }

    /** An unmapped product type still reports its number — that is how the next model gets named. */
    @Test
    fun `unknown product type is reported rather than swallowed`() {
        val d = BleAdvert.decode(hex("000000ee0004bd6e562099990010"))
        assertTrue(d.newFormat)
        assertEquals(0x9999, d.rawProductType)
        assertNull(d.modelId)
    }

    @Test
    fun `all zeroes is not a model`() {
        assertNull(BleAdvert.modelId(hex("0000")))
        assertNull(BleAdvert.modelId(ByteArray(0)))
    }

    @Test
    fun `mimo's third company id is accepted`() {
        assertTrue(BleConstants.isDjiCompanyId(BleConstants.DJI_COMPANY_ID))
        assertTrue(BleConstants.isDjiCompanyId(BleConstants.DJI_COMPANY_ID_ALT))
        assertTrue(BleConstants.isDjiCompanyId(0xE5C0))
        assertFalse(BleConstants.isDjiCompanyId(0x004C))  // Apple
    }
}
