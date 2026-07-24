package dev.konraditurbe.osmosis.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the CompositePack (`0x00/0x27`) structural decoder against **real bytes** captured off an
 * Osmo Nano datalink (three consecutive video records, reassembled). These are the actual
 * marker-delimited records the parser was reverse-engineered from; every filename, delete handle,
 * byte size, and fps below was cross-checked against the capture, so a regression here means the
 * wire format moved, not the test.
 */
class CompositeManifestTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Three consecutive Osmo Nano CompositePack records (heads DJI_…0266 / …0265 / …0264).
    private val threeRecords = hex(
        "804210409800025f03ff19060000005701030001c45a108f0400000101000700100098000263a5693d090000000000000000" +
        "17001833000000a8610000e8030000a8610000e8030000000000000000000000650000000018011000000000003500000000" +
        "00010000210500000000220500000000260500000000280600000001001c05000000012b0500000000310500000000360500" +
        "0000063705000000022c0500000010200a0000000100000001000b444a49000000000000000000008a010a0100000a410000" +
        "12010013000d1d444a495f32303236303731323137343234335f303236365f442e4d50341a2c000000014443494d2f444a49" +
        "5f3030312f444a495f32303236303731323137343234335f303236365f44001b0a0000000201000312131a30000000024d49" +
        "53432f54484d2f444a495f3030312f444a495f32303236303731323137343234335f303236365f44001b0a00000002020114" +
        "021500ea8cec5c17ae7c4340421040c400025f03ff190600000057010300018786908504000001010007001000c4000263ef" +
        "59800b000000000000000017001833000000a8610000e8030000a8610000e803000000000000000000000065000000001801" +
        "100000000000350000000000010000210500000000220500000000260500000000280600000001001c05000000012b050000" +
        "00003105000000003605000000063705000000022c0500000010200a0000000100000001000b444a49000000000000000000" +
        "008a01090100000941000012010013000d1d444a495f32303236303731323137333932315f303236355f442e4d50341a2c00" +
        "0000014443494d2f444a495f3030312f444a495f32303236303731323137333932315f303236355f44001b0a000000020100" +
        "0312131a30000000024d4953432f54484d2f444a495f3030312f444a495f32303236303731323137333932315f303236355f" +
        "44001b0a00000002020114021500db8bec5cad15a527004210407300025f03ff190600000057010300018b3ff04304000001" +
        "010007001000730002639341c306000000000000000017001833000000a8610000e8030000a8610000e80300000000000000" +
        "0000000065000000001801100000000000350000000000010000210500000000220500000000260500000000280600000001" +
        "001c05000000012b05000000003105000000003605000000063705000000022c0500000010200a0000000100000001000b44" +
        "4a49000000000000000000008a01080100000841000012010013000d1d444a495f32303236303731323137333035355f3032" +
        "36345f442e4d50341a2c000000014443494d2f444a495f3030312f444a495f32303236303731323137333035355f30323634" +
        "5f44001b0a0000000201000312131a30000000024d4953432f54484d2f444a495f3030312f444a495f323032363037313231" +
        "37333035355f303236345f44001b0a00000002020114021500138bec5c8649b04e"
    )

    private fun decode() = DatalinkClient({}, 9004, true).decodeCompositeForTest(threeRecords)

    @Test
    fun `decodes every record with the exact captured fields`() {
        val files = decode().sortedBy { it.path }
        assertEquals(3, files.size)

        // (name, DCIM path, delete handle, byte size, fps) — all read off the real capture.
        val expected = listOf(
            Triple("DCIM/DJI_001/DJI_20260712173055_0264_D.MP4", 0x40104200L, 113459603L),
            Triple("DCIM/DJI_001/DJI_20260712173921_0265_D.MP4", 0x40104240L, 192961007L),
            Triple("DCIM/DJI_001/DJI_20260712174243_0266_D.MP4", 0x40104280L, 155019685L),
        )
        for ((f, e) in files.zip(expected)) {
            assertEquals(e.first, f.path)
            assertEquals("handle for ${f.name}", e.second, f.handle)
            assertEquals("size for ${f.name}", e.third, f.sizeBytes)
            assertEquals("fps for ${f.name}", "25fps", f.resLabel)
            assertTrue("deletable", f.deletable)
            assertEquals("thumb", "MISC/THM/DJI_001/${f.name.substringBeforeLast('.')}.scr", f.thumbPath)
        }
    }

    @Test
    fun `a blob with no CompositePack marker decodes to nothing (falls back to scrape)`() {
        // No 03 ff 19 06 marker anywhere → the structural decoder must yield empty, not crash.
        val garbage = hex("00010203040506070809" + "444a495f".repeat(8))
        assertEquals(0, DatalinkClient({}, 9004, true).decodeCompositeForTest(garbage).size)
    }

    @Test
    fun `handle and size come from fixed record offsets, not the filename text`() {
        // Sanity: the first (0264) record's handle is the head u32-LE and its size is head+38 —
        // proving we read structure, so a future extension-less name can't break handle/size.
        val f = decode().first { it.name.contains("_0264_") }
        assertNotNull(f)
        assertEquals(0x40104200L, f.handle)
        assertEquals(113459603L, f.sizeBytes)
    }
}
