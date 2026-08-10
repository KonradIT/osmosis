package dev.konraditurbe.osmosis.drone

import dev.konraditurbe.osmosis.dcf.DcfRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A DJI Mini 3 catalogue, from a PCAPdroid capture of DJI Fly (2026-08-09).
 *
 * The first real drone catalogue from anything but a Mavic 3, and it shows a second aircraft using
 * **the same field layout at a different stride** — 67 bytes, not 94. Same FAT mtime at `+0`, size at
 * `+4`, packed index at `+8`, duration at `+12`; only the trailing unmapped bytes are shorter.
 *
 * Truncated at chunk 0 (981 of a declared 1407 bytes), so 14 of its 21 records are present. That is
 * itself worth keeping: it exercises the trailing-partial-record path.
 */
class Mini3ManifestTest {

    private val blob =
        javaClass.classLoader!!.getResourceAsStream("manifests/mini3_21.bin")!!.readBytes()

    /** `total = 8 + stride * count`, and the aircraft declares both. Mavic 3 and Mini 3 disagree. */
    @Test
    fun `the stride is derived from the declared count and total`() {
        assertEquals("Mini 3, 21 files in 1415 bytes", 67, DcfRecords.strideFrom(21, 1415))
        assertEquals("the same aircraft, a one-file reply", 67, DcfRecords.strideFrom(1, 75))
        assertEquals("Mavic 3, 45 files in 4238 bytes", 94, DcfRecords.strideFrom(45, 4238))
    }

    /** A stride that does not divide the body means the assumption is wrong; inventing one invents files. */
    @Test
    fun `an implausible declaration yields no stride rather than a guess`() {
        assertNull(DcfRecords.strideFrom(21, 1416))   // not a whole number of records
        assertNull(DcfRecords.strideFrom(0, 1415))    // no files
        assertNull(DcfRecords.strideFrom(-1, -1))     // a chunk that declared nothing
        assertNull(DcfRecords.strideFrom(4000, 4008)) // 2 bytes/record — not a record
    }

    @Test
    fun `all 14 complete records decode with real values`() {
        val recs = DcfRecords.decodeDrone(blob, stride = 67)
        assertEquals(14, recs.size)
        assertTrue("every index must be plausible", recs.all { it.storage == 1 })
        // Newest first, file numbers descending from DJI_0155.
        val numbers = recs.map { (it.fileIndex and 0xFFFF).toInt() }
        assertEquals(155, numbers.first())
        assertEquals(141, numbers.last())
        assertEquals("strictly descending", numbers.sortedDescending(), numbers)
        // Six stills then eight clips, exactly as the aircraft was used.
        assertEquals(6, recs.count { it.durationSec == 0 })
        assertEquals(8, recs.count { it.durationSec > 0 })
    }

    /**
     * Sizes and durations have to agree with each other, not merely be non-zero.
     *
     * A wrong stride still yields "records" — it reads whatever lands at those offsets — so the check
     * that actually distinguishes a correct layout is whether the numbers make physical sense together.
     */
    @Test
    fun `clip sizes match their durations at a plausible bitrate`() {
        val clips = DcfRecords.decodeDrone(blob, stride = 67).filter { it.durationSec > 0 }
        for (c in clips) {
            val mbPerSec = c.sizeBytes.toDouble() / c.durationSec / 1_000_000
            assertTrue("${c.sizeBytes}B over ${c.durationSec}s = %.1f MB/s".format(mbPerSec),
                mbPerSec in 5.0..20.0)
        }
        // Stills are megabytes, not hundreds of them.
        for (s in DcfRecords.decodeDrone(blob, stride = 67).filter { it.durationSec == 0 }) {
            assertTrue("still of ${s.sizeBytes}B", s.sizeBytes in 1_000_000..8_000_000)
        }
    }

    /** The Mavic's stride on Mini 3 bytes must not quietly produce plausible-looking files. */
    @Test
    fun `the wrong stride does not silently yield a believable list`() {
        val wrong = DcfRecords.decodeDrone(blob, stride = 94)
        assertTrue("94 on a 67-byte aircraft should find almost nothing, got ${wrong.size}",
            wrong.size <= 2)
    }
}
