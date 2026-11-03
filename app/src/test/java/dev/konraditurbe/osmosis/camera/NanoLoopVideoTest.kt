package dev.konraditurbe.osmosis.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Osmo Nano, a looping-video recording (loop length 5 min, ~31 min recorded, 1 min chapters), captured
 * 2026-11-03 off "Save logs" and recovered with `tools/hexdump_to_bin.py`.
 *
 * The camera keeps only the newest chapters, so the card holds `…_0037_D_026.MP4` to `_031.MP4` and
 * no `_001`. The grid page lists just `_026` — the same shape as a burst lead — and a group-expand on
 * the group handle returns all six.
 *
 *  - `nano_loop_internal_37.bin`: the internal store's newest page, 37 records.
 *  - `nano_loop_expand.bin`: the group-expand reply. The camera fills the 120-record limit by cycling
 *    the six chapters 20 times; decode dedups by path.
 *
 * Ground truth is the card listing taken over USB the same day (sizes below, byte-exact).
 */
class NanoLoopVideoTest {

    private fun decode(f: String) = CameraSession(log = {}, port = 9004, tcpPoke = true)
        .decodeManifestForTest(javaClass.classLoader!!.getResourceAsStream("manifests/$f")!!.readBytes())

    private val cardSizes = linkedMapOf(
        "DJI_20261103163418_0037_D_026.MP4" to 237_379_333L,
        "DJI_20261103163418_0037_D_027.MP4" to 236_419_361L,
        "DJI_20261103163418_0037_D_028.MP4" to 236_846_589L,
        "DJI_20261103163418_0037_D_029.MP4" to 237_157_474L,
        "DJI_20261103163418_0037_D_030.MP4" to 236_228_582L,
        "DJI_20261103163418_0037_D_031.MP4" to 147_819_754L,
    )

    @Test
    fun `grid lists only the oldest kept chapter, as a group lead`() {
        val lead = decode("nano_loop_internal_37.bin").filter { it.name.contains("_0037_") }
        assertEquals(1, lead.size)
        val f = lead.single()
        assertEquals("DJI_20261103163418_0037_D_026.MP4", f.name)
        assertTrue("named like a burst lead, so the grid expands it", f.isBurst)
        assertEquals(237_379_333L, f.sizeBytes)
        // The lead's duration is the whole kept recording (5:37), not its own one-minute chapter.
        assertEquals(337, f.durationSec)
        assertEquals("group-expand handle", 0x40100940L, f.cmdHandle)
    }

    @Test
    fun `group-expand returns every kept chapter once`() {
        val chapters = decode("nano_loop_expand.bin").sortedBy { it.subIndex }
        assertEquals(cardSizes.keys.toList(), chapters.map { it.name })
        assertEquals(listOf(26, 27, 28, 29, 30, 31), chapters.map { it.subIndex })
    }

    /**
     * The chapter records carry `03 f2 19 06` where every grid record carries `03 ff 19 06`. Before the
     * fixed-position anchor, the guard-byte scan missed all six and each decoded with size 0 and no
     * duration — the download queue would have had no size to resume or verify against.
     */
    @Test
    fun `chapters decode their own size duration and format`() {
        val chapters = decode("nano_loop_expand.bin").sortedBy { it.subIndex }
        assertEquals(cardSizes.values.toList(), chapters.map { it.sizeBytes })
        assertEquals(listOf(60, 59, 60, 60, 59, 37), chapters.map { it.durationSec })
        assertTrue(chapters.all { it.resolution == "1920x1440" })
        assertTrue(chapters.all { it.mediaType == 3 })
        assertTrue(chapters.all { it.isVideo })
        assertFalse(chapters.any { it.starred })
    }

    /** All six carry the group's handle. Deleting through it from one chapter is not a per-file delete. */
    @Test
    fun `chapters share the group handle and are not deletable one by one`() {
        val chapters = decode("nano_loop_expand.bin")
        assertTrue(chapters.all { it.handle == 0x40100940L })
        assertFalse(chapters.any { it.deletable })
    }
}
