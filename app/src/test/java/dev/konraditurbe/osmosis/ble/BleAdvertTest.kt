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

    /**
     * A plain Osmo Pocket 4, from a tester's scan on 2026-08-08. Same payload *length* as its Pro
     * sibling above, but the classic format: model id populated, new-format flag clear.
     *
     *     OP4   21 00 00 be 00 00 ee 8d d9 a0 00 00 00 00   classic 0x0021, flag clear
     *     OP4P  00 00 00 ee 00 04 bd 6e 56 20 da 00 00 10   classic 0,      flag set -> 218
     *
     * So the two formats are chosen per model, not per generation or payload size — which is exactly
     * why 1.3.0 handled the Pocket 4 and left the Pocket 4 Pro as unknown(0x0000).
     */
    private val pocket4 = hex("210000be0000ee8dd9a000000000")

    /** Nano shape: model 0x0019 at [0:2], then MAC, then a trailing byte. */
    private val nano = hex("190000c25a8abdc2d803")

    /**
     * An Osmo Action 4 advert, from a tester's scan on 2026-08-24 — the session that first carried an
     * Action 4 all the way to a downloaded file.
     *
     *     14 00 fa aa bb cc dd ee ff     classic 0x0014, then the MAC in advertised order
     *
     * Byte 2 is `0xfa` where the Nano's is `0x00`, which is the point of keeping it: the classic model
     * id is a u16 LE at `[0:2]`, so anything that widened the read past two bytes to accommodate a new
     * body would resolve this advert as garbage rather than as an Action 4.
     */
    private val action4 = hex("1400faaabbccddeeff")

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

    /**
     * The Pocket 4 resolves by id even though the tester had **renamed** the camera to `MEGG-OP4`.
     * Nothing in that name matches the model table, so the name fallback would have failed outright;
     * only the advert's classic id identifies it. Real devices in the wild are renamed.
     */
    @Test
    fun `pocket 4 resolves by id on a renamed camera`() {
        val d = BleAdvert.decode(pocket4)
        assertFalse("classic format: the flag bit at payload[5] is clear", d.newFormat)
        assertEquals(0x0021, d.modelId)
        assertEquals("OsmoPocket4", BleConstants.MODEL_NAMES[d.modelId])
        val m = CameraModel.resolve(d.modelId, "MEGG-OP4")
        assertEquals("Osmo Pocket 4", m.name)
        assertEquals(9004, m.datalinkPort)
    }

    /** The pair differ only in which field carries the model — length alone can't tell them apart. */
    @Test
    fun `pocket 4 and pocket 4 pro are the same length but different formats`() {
        assertEquals(pocket4.size, pocket4Pro.size)
        assertEquals(0x0021, BleAdvert.modelId(pocket4))
        assertEquals(0x0022, BleAdvert.modelId(pocket4Pro))
    }

    /**
     * The Action 4 resolves through the classic path to a camera profile that reaches the grid.
     *
     * `datalinkPort` 9004 with the TCP poke is exactly what the working session used, and
     * `singleSdStorage` must stay clear: the mount is decided by the handle's internal bit here, not
     * pinned the way the Pocket 3's is. See [dev.konraditurbe.osmosis.camera.Oa4ManifestTest].
     */
    @Test
    fun `action 4 resolves through the classic format to a 9004 profile`() {
        val d = BleAdvert.decode(action4)
        assertFalse("classic format: the flag bit at payload[5] is clear", d.newFormat)
        assertEquals(0x0014, d.modelId)
        assertEquals("OsmoAction4", BleConstants.MODEL_NAMES[d.modelId])

        val m = CameraModel.resolve(d.modelId, "OsmoAction4-ABCD")
        assertEquals("Osmo Action 4", m.name)
        assertEquals(9004, m.datalinkPort)
        assertTrue(m.tcpPoke)
        assertFalse("its AP is WPA2, not WPA3", m.wpa3)
        assertFalse("the handle bit picks the mount; nothing is pinned", m.singleSdStorage)
        assertFalse("an Action 4 is not a drone", m.isDrone)
    }

    @Test
    fun `mimo's third company id is accepted`() {
        assertTrue(BleConstants.isDjiCompanyId(BleConstants.DJI_COMPANY_ID))
        assertTrue(BleConstants.isDjiCompanyId(BleConstants.DJI_COMPANY_ID_ALT))
        assertTrue(BleConstants.isDjiCompanyId(0xE5C0))
        assertFalse(BleConstants.isDjiCompanyId(0x004C))  // Apple
    }
}
