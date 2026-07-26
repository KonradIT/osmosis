package dev.konraditurbe.osmosis.net

import dev.konraditurbe.osmosis.core.CameraFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the media-list **pagination** reverse-engineered from a DJI-Mimo capture (UDP 9004, the same
 * datalink we use). Two moving parts:
 *  - the request wire format: a 4-byte **little-endian file handle** cursor at nested-payload bytes
 *    10-13, count `0x2d`=45 at byte 14, counter at byte 4. `0x00000001` selects the newest page; older
 *    pages feed the oldest video handle of the previous page. The cursor hex below are the *exact*
 *    values Mimo sent while scrolling this Nano's library, so a regression means the format moved.
 *  - [DatalinkClient.stepPagination]: the pure per-page advance (dedup + pick the next cursor + decide
 *    whether more remain), which is what the grid's infinite scroll drives.
 */
class PaginationTest {

    private val dl = DatalinkClient({}, 9004, true)

    private fun file(seq: Int, handle: Long) = CameraFile(
        path = "DCIM/DJI_001/DJI_20260101120000_%04d_D.MP4".format(seq),
        thumbPath = "MISC/THM/DJI_001/DJI_20260101120000_%04d_D.scr".format(seq),
        handle = handle,
    )

    // ---- request wire format ----------------------------------------------------

    @Test
    fun `newest-page request equals the proven osmo-download blob`() {
        // cursor 0x00000001 (+ counter 1) must reproduce the exact newest-list request byte for byte.
        val cmd = dl.buildListCmdForTest(1, 0x00000001L)
        assertEquals(
            "4a002a10010000000000010000002d000d0100ffffffffffffffff000100000000000000000000000000",
            cmd.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `cursor is a 4-byte little-endian handle at bytes 10-13, count stays 45`() {
        // 0x401036c0 → bytes 10..13 = c0 36 10 40 (exactly Mimo's page-2 cursor).
        val cmd = dl.buildListCmdForTest(2, 0x401036c0L)
        assertEquals(0x02.toByte(), cmd[4])                              // command counter
        assertEquals(0xc0.toByte(), cmd[10]); assertEquals(0x36.toByte(), cmd[11])
        assertEquals(0x10.toByte(), cmd[12]); assertEquals(0x40.toByte(), cmd[13])
        assertEquals(0x2d.toByte(), cmd[14])                            // page size = 45, never changes
    }

    @Test
    fun `every real Mimo page cursor round-trips through the request`() {
        for (h in longArrayOf(0x40102b80L, 0x40101780L, 0x40100cc0L, 0x40100680L)) {
            val cmd = dl.buildListCmdForTest(3, h)
            val readBack = (cmd[10].toLong() and 0xFF) or ((cmd[11].toLong() and 0xFF) shl 8) or
                ((cmd[12].toLong() and 0xFF) shl 16) or ((cmd[13].toLong() and 0xFF) shl 24)
            assertEquals(h, readBack)
        }
    }

    // ---- stepPagination: the per-page advance -----------------------------------

    @Test
    fun `advances to the oldest video handle and signals more`() {
        val seen = mutableSetOf<String>()
        val page = listOf(file(50, 0x40101400L), file(49, 0x40101340L), file(48, 0x40101280L))
        val step = dl.stepPagination(0x40101500L, page, seen)
        assertEquals(3, step.fresh.size)
        assertEquals(0x40101280L, step.nextCursor)      // smallest handle strictly below the cursor
        assertTrue(step.moreAvailable)
    }

    @Test
    fun `dedups the one-file boundary overlap between pages`() {
        val seen = mutableSetOf<String>()
        dl.stepPagination(0x40101500L, listOf(file(50, 0x40101400L), file(49, 0x40101340L)), seen)
        // The next page repeats seq 49 (the boundary) and brings 48, 47 — only 48/47 are new.
        val step = dl.stepPagination(
            0x40101340L,
            listOf(file(49, 0x40101340L), file(48, 0x40101280L), file(47, 0x401011c0L)),
            seen,
        )
        assertEquals(listOf(48, 47), step.fresh.map { it.seq })
        assertEquals(0x401011c0L, step.nextCursor)
    }

    @Test
    fun `stops when no handle is older than the current cursor`() {
        val seen = mutableSetOf<String>()
        val page = listOf(file(50, 0x40101500L), file(51, 0x40101540L))   // all >= cursor
        val step = dl.stepPagination(0x40101500L, page, seen)
        assertEquals(0x40101500L, step.nextCursor)      // cursor unchanged
        assertFalse(step.moreAvailable)
    }

    @Test
    fun `a stray low-namespace handle cannot drag the cursor to the bottom`() {
        val seen = mutableSetOf<String>()
        // A 0x0010xxxx photo (like seq 0022) mixed with real videos: the cursor must take a VIDEO handle.
        val page = listOf(file(48, 0x40101280L), file(22, 0x00100580L), file(47, 0x401011c0L))
        val step = dl.stepPagination(0x40101300L, page, seen)
        assertEquals(0x401011c0L, step.nextCursor)      // oldest video handle, NOT 0x100580
        assertTrue(step.moreAvailable)
    }

    @Test
    fun `a page with nothing new ends pagination`() {
        val seen = mutableSetOf<String>()
        val page = listOf(file(50, 0x40101400L))
        dl.stepPagination(0x40101500L, page, seen)                       // seed → seq 50 seen
        val step = dl.stepPagination(0x40101400L, page, seen)            // same file again
        assertTrue(step.fresh.isEmpty())
        assertFalse(step.moreAvailable)
    }
}
