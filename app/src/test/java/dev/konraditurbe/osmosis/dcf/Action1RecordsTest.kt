package dev.konraditurbe.osmosis.dcf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Osmo Action 1's 65-byte record layout, pinned to a real list captured off the camera.
 *
 * The fixture is the **raw** datalink blob (all datagrams, sub-headers included); the reassembled list
 * inside it is `[u32 count=7][u32 total=463]` + 7 × 65 B. The camera's own log independently states
 * `FileNumToSend: 7, SizeToSend: 463` and `FileIndexSending: 0x640251` … `0x640241`, so the count, the
 * size and both end indices are externally corroborated rather than self-asserted.
 */
class Action1RecordsTest {

    /** Locate the reassembled list inside the raw capture by its self-describing header. */
    private fun listBytes(): ByteArray {
        val raw = javaClass.classLoader!!.getResourceAsStream("manifests/action1_7.bin")!!.readBytes()
        val head = byteArrayOf(7, 0, 0, 0, 0xCF.toByte(), 1, 0, 0) // count=7, total=463
        val at = (0..raw.size - head.size).first { i -> head.indices.all { raw[i + it] == head[it] } }
        return raw.copyOfRange(at, at + 463)
    }

    @Test
    fun `decodes all seven records, with the indices the camera logged`() {
        val recs = DcfRecords.decodeAction1(listBytes())
        assertEquals(7, recs.size)
        assertEquals(0x640251L, recs.first().fileIndex)
        assertEquals(0x640241L, recs.last().fileIndex)
    }

    @Test
    fun `the packed index yields the on-card name, not the raw halves`() {
        val r = DcfRecords.decodeAction1(listBytes()).first()
        // 0x640251 -> storage 0, dir 100, file 593. The regression this guards: reading +12 (which is
        // duration) as the file number produced DJI_0117 for a file that is really DJI_0593.
        assertEquals(0, DcfIndex.storage(r.fileIndex))
        assertEquals(100, DcfIndex.dir(r.fileIndex))
        assertEquals(593, DcfIndex.file(r.fileIndex))
    }

    @Test
    fun `every record implies a sane video bitrate`() {
        // Weak on its own — but combined with the +12/+38 agreement below it fixes which field is the
        // size. The camera records at several bitrates (~1.6, ~4.4 and ~12.5 MB/s in this capture), so
        // this deliberately does NOT assert a constant rate; an early version did and was wrong.
        val recs = DcfRecords.decodeAction1(listBytes())
        assertEquals(7, recs.size)
        for (r in recs) {
            val rate = r.sizeBytes.toDouble() / r.durationSec
            assertTrue("implausible ${r.sizeBytes} B over ${r.durationSec}s", rate in 100_000.0..20_000_000.0)
        }
    }

    @Test
    fun `a sub-second clip is a video, not a still`() {
        // The trap this guards: one record's whole-second duration is 0, so the drone's
        // `durationSec == 0 => photo` rule would misfile it. Its millisecond duration is 667, and the
        // camera's own log lists its UUID among the DjiMovDmx videos.
        val recs = DcfRecords.decodeAction1(listBytes())
        assertTrue("every file in this capture is a video", recs.all { it.durationSec > 0 })
        assertTrue("expected the 0.667 s clip to round up to 1 s", recs.any { it.durationSec == 1 })
    }

    @Test
    fun `timestamps are unix seconds, not the drone's FAT packing`() {
        val r = DcfRecords.decodeAction1(listBytes()).first()
        // 1554159685 = 2019-04-01T21:41:25Z. Run through fatToEpoch it would be nonsense — this is the
        // one field where the Action and the drone genuinely differ.
        assertEquals(1554159685L, r.mtimeEpoch)
    }

    @Test
    fun `a drone manifest is rejected, so the two decoders cannot be crossed`() {
        // 94-byte records with no [count][total] header must not parse as an Action 1 list.
        assertTrue(DcfRecords.decodeAction1(ByteArray(8 + 94 * 3)).isEmpty())
        assertTrue(DcfRecords.decodeAction1(ByteArray(0)).isEmpty())
    }
}
