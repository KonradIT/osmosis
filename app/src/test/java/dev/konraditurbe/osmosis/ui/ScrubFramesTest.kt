package dev.konraditurbe.osmosis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grid placement for the scrub preview — the part that's easy to get off by one. */
class ScrubFramesTest {

    @Test fun `cells sit in the middle of their slice`() {
        // 60 s over 6 cells = 10 s slices, sampled at their midpoints.
        assertEquals(listOf(5_000L, 15_000L, 25_000L, 35_000L, 45_000L, 55_000L),
            ScrubFrames.gridTimes(60_000, 6))
    }

    @Test fun `never samples the first or last frame`() {
        // Clip start is usually black and the last frame can be past the final keyframe.
        val times = ScrubFrames.gridTimes(12_345, 10)
        assertTrue(times.first() > 0)
        assertTrue(times.last() < 12_345)
    }

    @Test fun `times are strictly increasing and in range`() {
        for (count in 4..16) {
            val times = ScrubFrames.gridTimes(7_000, count)
            assertEquals(count, times.size)
            assertEquals(times.sorted().distinct(), times)
            assertTrue(times.all { it in 0..7_000 })
        }
    }
}
