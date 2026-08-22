package dev.konraditurbe.osmosis.dcf

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Resolution and fps from a Mavic 3 record, pinned to ground truth.
 *
 * Twelve real 94-byte records (2026-08-22), each a clip whose format was read off the aircraft's own
 * screen. `+14` is the fps code and `+15` the resolution code — their own set, distinct from the
 * camera manifest's format byte. See [DcfRecords.decodeDrone]. ROADMAP §17.
 */
class DroneResFpsTest {

    // file -> (record hex, expected "WxH", expected fps), from the RESPROBE capture.
    private val cases = listOf(
        Triple("0db0165daea79b045d02640004000310034c030001782ab6030400000001000700100004000304215f620000000000000000000f00000000000000000b000000000000000000000000000000000000000000000000000000000000000000", "3840x2160" to 30, 605),
        Triple("21b0165dc7bf4c045e0264000700030a034c03000152d8957d0400000001000700100007000304e053a00000000000000000000f00000000000000000b000000000000000000000000000000000000000000000000000000000000000000", "1920x1080" to 30, 606),
        Triple("2eb0165de41f1e035f02640003000110034c030001901903d704000001010007001000030001044d23570000000000000000000f00000000000000000b000000000000000000000000000000000000000000000000000000000000000000", "3840x2160" to 24, 607),
        Triple("37b0165d4a2555046002640005000210034c030001a7f1c9440400000101000700100005000204776c750000000000000000000f00000000000000000b000000000000000000000000000000000000000000000000000000000000000000", "3840x2160" to 25, 608),
        Triple("40b0165ded1a40076102640007000410034c030001dc75f9a00400000101000700100007000404ca4a160100000000000000000f00000000000000000b000000000000000000000000000000000000000000000000000000000000000000", "3840x2160" to 48, 609),
        Triple("4cb0165df6cd64046202640004000510034c030001bfee8bdf040000010100070010000400050476faaa0000000000000000000f00000000000000000b000000000000000000000000000000000000000000000000000000000000000000", "3840x2160" to 50, 610),
        Triple("52b0165d6c85cb056302640006000610034c0300012e0e336604000001010007001000060006046ba6de0000000000000000000f00000000000000000b000000000000000000000000000000000000000000000000000000000000000000", "3840x2160" to 60, 611),
        Triple("64b0165dc32d0105640264000a00010a034c030001b103b12e040000010100070010000a000104287bd70000000000000000000f00000000000000000b000000000000000000000000000000000000000000000000000000000000000000", "1920x1080" to 24, 612),
        Triple("6ab0165df0bf1a04650264000600060a034c030001e473572c0400000101000700100006000604c594e70000000000000000000f00000000000000000b000000000000000000000000000000000000000000000000000000000000000000", "1920x1080" to 60, 613),
        Triple("80b0165d551e1d046602640004000316034c03000156791a33040000010100070010000400036635bd660000000000000000000f00000000000000000b000000000000000000000000000000000000000000000000000000000000000000", "4096x2160" to 30, 614),
        Triple("88b0165dbc7bd8076702640007000161034c030001a4bc45130400000101000700100007000166a1ab960000000000000000000f00000000000000000b000000000000000000000000000000000000000000000000000000000000000000", "5120x2700" to 24, 615),
        Triple("95b0165d28135f076802640005000561034c03000113dc87e80400000101000700100005000566cbb9d40000000000000000000f00000000000000000b000000000000000000000000000000000000000000000000000000000000000000", "5120x2700" to 50, 616),
    )

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun `every clip decodes its resolution and fps`() {
        for ((hex, expected, file) in cases) {
            val (wh, fps) = expected
            val rec = DcfRecords.decodeDrone(bytes(hex), stride = 94).single()
            assertEquals("file $file resolution", wh, rec.resolution)
            assertEquals("file $file fps", fps, rec.fps)
        }
    }

    @Test fun `it surfaces on the CameraFile as resLabel`() {
        val rec = DcfRecords.decodeDrone(bytes(cases[0].first), stride = 94).single().toCameraFile()
        assertEquals("3840x2160", rec.resolution)
        assertEquals("30fps", rec.resLabel)
    }

    @Test fun `an unmapped code is left null, not guessed`() {
        assertEquals(null, DcfRecords.decodeDrone(bytes(cases.first().first), stride = 14).firstOrNull()?.resolution)
        // A real record whose resolution byte is a value the enum doesn't define.
        assertEquals(null, patched(fpsByte = 3, resByte = 0xEE).resolution)
    }

    // A valid Mavic 3 record (605) with the fps byte (+14) and resolution byte (+15) overwritten, so
    // the mined enum can be exercised at values this aircraft never produced.
    private fun patched(fpsByte: Int, resByte: Int): DcfRecord {
        val b = bytes(cases.first().first)
        b[14] = fpsByte.toByte(); b[15] = resByte.toByte()
        return DcfRecords.decodeDrone(b, stride = 94).single()
    }

    /** Values a Mavic 3 can't shoot, mined from DJI Fly's enum and pinned so the table can't drift. */
    @Test fun `mined resolutions decode`() {
        assertEquals("2704x1520", patched(3, 0x18).resolution)  // 2.7K
        assertEquals("2560x1440", patched(3, 0x3F).resolution)  // 1440p
        assertEquals("7680x4320", patched(3, 0x37).resolution)  // 8K
        assertEquals("1080x1920", patched(3, 0x42).resolution)  // vertical
    }

    @Test fun `mined and precise frame rates decode`() {
        assertEquals(120, patched(7, 0x10).fps)   // slow motion
        assertEquals(240, patched(8, 0x10).fps)
        assertEquals(24, patched(13, 0x10).fps)   // PRECISE_24 folds to 24
        assertEquals(0, patched(23, 0x10).fps)    // 8.7fps omitted, not rounded
    }
}
