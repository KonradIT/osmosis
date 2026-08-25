package dev.konraditurbe.osmosis.camera

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
 *    pages feed the oldest handle of the previous page. The cursor hex below are the *exact* values
 *    Mimo sent while scrolling this Nano's library, so a regression means the format moved.
 *  - [CameraSession.stepPagination]: the pure per-page advance (dedup + pick the next cursors + decide
 *    whether more remain), which is what the grid's infinite scroll drives.
 *
 * A page request is a **pair** of queries — counter 1 selects the SD store, counter 2 the internal one
 * (the cursor's `0x40000000` bit is the selector). So there is a cursor per store, and the tests below
 * cover each store advancing, running out, and being absent entirely.
 */
class PaginationTest {

    private val dl = CameraSession({}, 9004, true)

    /** The count in every list request; not readable from here, so the literal is repeated. */
    private val pageSize = 45

    private val newestSd = 0x00000001L
    private val newestInternal = 0x40000001L

    /**
     * A record. [storage] null leaves `storageKnown` clear, so the store is inferred from the handle
     * bit — the no-counter-echo camera's path. Pass it to model a store-specific query's answer.
     */
    private fun file(seq: Int, handle: Long, storage: Int? = null) = CameraFile(
        path = "DCIM/DJI_001/DJI_20260101120000_%04d_D.MP4".format(seq),
        thumbPath = "MISC/THM/DJI_001/DJI_20260101120000_%04d_D.scr".format(seq),
        handle = handle,
        storage = storage ?: 0,
        storageKnown = storage != null,
    )

    /** [n] records newest-first, handles descending from [base] by [step]. */
    private fun page(n: Int, base: Long, step: Long = 0x40, firstSeq: Int = 500, storage: Int? = null) =
        (0 until n).map { file(firstSeq - it, base - it * step, storage) }

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

    /**
     * An SD page cursor rides counter 1 exactly as an internal one rides counter 2.
     *
     * This is the one step of the two-cursor fix the captures do not already prove: Mimo was only ever
     * observed paging an internal library, so every captured cursor has the `0x40000000` bit set. The
     * request encoding is handle-agnostic — same field, same width — and the bit is the store selector,
     * so an SD handle selects SD. Confirmed on hardware is what settles it; this pins the encoding.
     */
    @Test
    fun `an SD page cursor encodes the same way as an internal one`() {
        val cmd = dl.buildListCmdForTest(1, 0x00040060L)
        assertEquals(0x01.toByte(), cmd[4])                              // SD query counter
        assertEquals(0x60.toByte(), cmd[10]); assertEquals(0x00.toByte(), cmd[11])
        assertEquals(0x04.toByte(), cmd[12]); assertEquals(0x00.toByte(), cmd[13])
        assertEquals(0x2d.toByte(), cmd[14])
    }

    // ---- storeOf: which cursor a record advances --------------------------------

    @Test
    fun `a store-specific query's answer is taken at its word`() {
        assertEquals(0, dl.storeOf(file(1, 0x40101280L, storage = 0)))   // handle bit says internal…
        assertEquals(1, dl.storeOf(file(1, 0x00040060L, storage = 1)))   // …storageKnown wins
    }

    /** Without the counter echo, the handle's store bit decides — the same rule as the HTTP mount. */
    @Test
    fun `a merged list falls back to the handle store bit`() {
        assertEquals(1, dl.storeOf(file(1, 0x40101280L)))
        assertEquals(0, dl.storeOf(file(1, 0x00040060L)))
        assertEquals(0, dl.storeOf(file(1, 0x00100580L)))
    }

    // ---- stepPagination: the per-page advance -----------------------------------

    @Test
    fun `each store advances on its own cursor`() {
        val seen = mutableSetOf<String>()
        val sd = page(pageSize, 0x000406C0L, step = 0x10, firstSeq = 500, storage = 0)
        val internal = page(pageSize, 0x40101400L, step = 0x40, firstSeq = 300, storage = 1)
        val step = dl.stepPagination(0x00040700L, 0x40101500L, sd + internal, seen)

        assertEquals(90, step.fresh.size)
        assertEquals("oldest SD handle on the page", 0x00040400L, step.sdCursor)
        assertEquals("oldest internal handle on the page", 0x40100900L, step.internalCursor)
        assertTrue(step.moreAvailable)
    }

    /**
     * The Action 4 / Pocket 3 case: an SD-only library pages.
     *
     * Every handle clears `0x40000000`, so under the single-cursor design the cursor seeded to 0 and
     * `moreAvailable` was false however full the card — the grid stopped dead at 45 with no error. Here
     * the SD cursor advances and the internal one stays parked at its newest-page selector.
     */
    @Test
    fun `an SD-only library pages`() {
        val seen = mutableSetOf<String>()
        val sd = page(pageSize, 0x000406C0L, step = 0x10, storage = 0)
        val step = dl.stepPagination(0x00040700L, newestInternal, sd, seen)

        assertEquals(0x00040400L, step.sdCursor)
        assertEquals("no internal records, so that cursor must not move", newestInternal, step.internalCursor)
        assertTrue(step.moreAvailable)
    }

    /** The Nano/Xtra regression: an internal-only library pages exactly as it did before. */
    @Test
    fun `an internal-only library pages and leaves the SD cursor alone`() {
        val seen = mutableSetOf<String>()
        val internal = page(pageSize, 0x40101400L, step = 0x40, storage = 1)
        val step = dl.stepPagination(newestSd, 0x40101500L, internal, seen)

        assertEquals(0x40100900L, step.internalCursor)
        assertEquals("no SD records, so that cursor must not move", newestSd, step.sdCursor)
        assertTrue(step.moreAvailable)
    }

    /**
     * A cursor may only take a handle from its **own** store.
     *
     * This replaces a test that called an SD handle "a stray low-namespace handle (e.g. a 0x0010xxxx
     * photo)" and asserted it was discarded. It was never stray — it was an Action 6 SD record, and
     * discarding it is what made SD content unreachable past the first page. Now it belongs to the SD
     * cursor, and the guarantee that matters is that the two namespaces cannot cross.
     */
    @Test
    fun `a cursor never takes a handle from the other store`() {
        val seen = mutableSetOf<String>()
        val mixed = listOf(
            file(48, 0x40101280L, storage = 1),
            file(22, 0x00100580L, storage = 0),
            file(47, 0x401011C0L, storage = 1),
        )
        val step = dl.stepPagination(0x00100600L, 0x40101300L, mixed, seen)
        assertEquals("the internal cursor takes the oldest INTERNAL handle", 0x401011C0L, step.internalCursor)
        assertEquals("the SD cursor takes the SD one", 0x00100580L, step.sdCursor)
    }

    /**
     * One store exhausted, the other still going — the mixed-library end game.
     *
     * SD came back short, so it is done; internal came back full, so the scroll continues. A single
     * `moreAvailable` for both stores has to be an OR, or the shorter store ends the whole scroll.
     */
    @Test
    fun `a short store does not end pagination while the other is still full`() {
        val seen = mutableSetOf<String>()
        val sd = page(5, 0x00040050L, step = 0x10, firstSeq = 5, storage = 0)
        val internal = page(pageSize, 0x40101400L, step = 0x40, firstSeq = 300, storage = 1)
        val step = dl.stepPagination(0x00040100L, 0x40101500L, sd + internal, seen)

        assertTrue("internal came back full, so there is more", step.moreAvailable)
        assertEquals(0x40100900L, step.internalCursor)
    }

    /** Both stores short: the library is exhausted and the pull-up spinner must not arm. */
    @Test
    fun `a short page in every store ends pagination`() {
        val seen = mutableSetOf<String>()
        val sd = page(5, 0x00040050L, step = 0x10, firstSeq = 5, storage = 0)
        val internal = page(3, 0x40101400L, step = 0x40, firstSeq = 300, storage = 1)
        val step = dl.stepPagination(0x00040100L, 0x40101500L, sd + internal, seen)

        assertEquals("still returns the files", 8, step.fresh.size)
        assertFalse(step.moreAvailable)
    }

    @Test
    fun `dedups the one-file boundary overlap between pages`() {
        val seen = mutableSetOf<String>()
        val first = page(pageSize, 0x40101400L, step = 0x40, storage = 1)
        dl.stepPagination(newestSd, 0x40101500L, first, seen)
        // The next page repeats the cursor file itself, as the camera does.
        val second = listOf(first.last()) + page(pageSize - 1, 0x401002C0L, step = 0x40, firstSeq = 455, storage = 1)
        val step = dl.stepPagination(newestSd, first.last().handle, second, seen)
        assertEquals("the repeat is dropped", pageSize - 1, step.fresh.size)
    }

    /**
     * The same filename on both stores must survive as two cells.
     *
     * `Oa6LiveCardTest` proves a camera can hold `…_0001_…` on the card *and* on the built-in store.
     * Dedup keyed on path alone would drop one of them — invisible, and only once both stores page.
     */
    @Test
    fun `dedup is keyed by store and path, not path alone`() {
        val seen = mutableSetOf<String>()
        val both = listOf(
            file(1, 0x00040010L, storage = 0),
            file(1, 0x40040010L, storage = 1),   // same path, other store
        )
        val step = dl.stepPagination(0x00040100L, 0x40040100L, both, seen)
        assertEquals("both copies are new", 2, step.fresh.size)
    }

    @Test
    fun `stops when no handle is older than the current cursor`() {
        val seen = mutableSetOf<String>()
        val internal = page(pageSize, 0x40101400L, step = 0x40, storage = 1)
        // A cursor already older than everything on the page: nothing qualifies.
        val step = dl.stepPagination(newestSd, 0x40100000L, internal, seen)
        assertEquals("cursor unchanged", 0x40100000L, step.internalCursor)
        assertFalse(step.moreAvailable)
    }

    /**
     * Handle 0 means "no handle" — a record whose scan disagreed with the fitted geometry, so it was
     * revoked rather than trusted. Taking it as a cursor would park paging at the bottom permanently.
     */
    @Test
    fun `a zero handle never becomes a cursor`() {
        val seen = mutableSetOf<String>()
        val withHole = page(pageSize - 1, 0x40101400L, step = 0x40, storage = 1) +
            file(1, 0L, storage = 1)
        val step = dl.stepPagination(newestSd, 0x40101500L, withHole, seen)
        assertEquals(0x40101400L - (pageSize - 2) * 0x40L, step.internalCursor)
        assertTrue(step.internalCursor > 0L)
    }

    @Test
    fun `a page with nothing new ends pagination`() {
        val seen = mutableSetOf<String>()
        val internal = page(pageSize, 0x40101400L, step = 0x40, storage = 1)
        dl.stepPagination(newestSd, 0x40101500L, internal, seen)          // seed → all seen
        val step = dl.stepPagination(newestSd, 0x40100900L, internal, seen)  // same page again
        assertTrue(step.fresh.isEmpty())
        assertFalse(step.moreAvailable)
    }
}
