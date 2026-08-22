package dev.konraditurbe.osmosis.dcf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The favourite flag in a Mavic 3 record, pinned to ground truth.
 *
 * Six real 94-byte records straight off the aircraft (2026-08-22), three of files favourited in DJI
 * Fly (580, 585, 590) and three not (581, 584, 589) — a video and a still on each side. The flag is
 * the byte at `+19`, the one right after the constant `4c 03` pair: `01` on every favourite, `00` on
 * every other, for both media kinds. See [DcfRecords.decodeDrone].
 */
class DroneStarFlagTest {

    // file number (index & 0xFFFF) -> full record hex, from the STARPROBE capture.
    private val records = linkedMapOf(
        580 to "93100d5d3b59870e440264000e000310034c030101d22be18e040000000100070010000e000304db2a270100000000000000000f00000000000000000b000000000000000000000000000000000000000000000000000000000000000000",
        581 to "a2100d5dce5af3114502640012000310034c030001d6c376a80400000001000700100012000304e74d660100000000000000000f00000000000000000b000000000000000000000000000000000000000000000000000000000000000000",
        584 to "d8100d5d9932dd0a480264000a000310034c03000137fcad81040000000100070010000a000304e96ed90000000000000000000f00000000000000000b000000000000000000000000000000000000000000000000000000000000000000",
        585 to "ef100d5d3138b2184902640019000310034c030101743e26ee0400000001000700100019000304766fea0100000000000000000f00000000000000000b000000000000000000000000000000000000000000000000000000000000000000",
        589 to "fc100d5d0060a4004d02640000000000004c03000201000008ff01000000f0000000180100006400000002006400f9ffffff0a00000001000000180016000000000b00000000000000000000000000000000000000000000000000000000",
        590 to "0e110d5d0070f8004e02640000000000004c03010201000008ff010000001e000000180100006400000002006400f9ffffff0a00000001000a00180016000000000b00000000000000000000000000000000000000000000000000000000",
    )

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun blob() = records.values.joinToString("").let(::bytes)

    @Test fun `every record is 94 bytes`() {
        for ((n, h) in records) assertEquals("file $n", 94, h.length / 2)
    }

    @Test fun `favourited files read starred, the rest do not`() {
        val fav = setOf(580, 585, 590)
        val decoded = DcfRecords.decodeDrone(blob(), stride = 94).associateBy { (it.fileIndex and 0xFFFF).toInt() }
        assertEquals("all six records decode", 6, decoded.size)
        for (n in records.keys) {
            val starred = decoded.getValue(n).starred
            if (n in fav) assertTrue("file $n should be starred", starred)
            else assertFalse("file $n should not be starred", starred)
        }
    }

    @Test fun `the flag survives projection to CameraFile`() {
        val byNum = DcfRecords.decodeDrone(blob(), stride = 94)
            .associateBy { (it.fileIndex and 0xFFFF).toInt() }
        assertTrue(byNum.getValue(590).toCameraFile().starred)   // a favourited still
        assertFalse(byNum.getValue(589).toCameraFile().starred)  // the still next to it
    }
}
