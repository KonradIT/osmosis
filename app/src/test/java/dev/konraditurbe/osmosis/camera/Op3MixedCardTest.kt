package dev.konraditurbe.osmosis.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Osmo Pocket 3, 29 records — a **second** OP3, and a mixed card of videos and stills.
 *
 * From a tester's log of 2026-08-10. This is the card [Op3ManifestTest] said was needed: that fixture
 * holds videos only, so it could not settle why mixed cards showed handle collisions. This one has both
 * kinds on it, and the answer was neither theory on offer — not the camera reusing handles, and not
 * photos inheriting them, but our own scan reading across a record boundary. See
 * [no still carries a delete handle].
 *
 * It also disproves a naming assumption by counter-example. The other OP3 suffixes every file `_OP3`;
 * this one suffixes none. Same model, different *user* setting — so nothing may key off the suffix.
 * See MEDIA_PROTOCOL.md §1 on Naming Management.
 */
class Op3MixedCardTest {

    /**
     * The FULL decode, not [CameraSession.decodeCompositeForTest] — the collision guard runs after the
     * struct decode, and it is half of what this fixture is for. Note the log's own `11 deletable` is
     * the count *before* that guard.
     *
     * The blob starts mid-record (`…749_D`), exactly as the tester's log carried it. Left that way on
     * purpose: the decoder anchors on the record marker rather than counting from byte 0, so a
     * real-world dump beginning in the middle of a filename is a free test of that.
     */
    private fun decode() = CameraSession(log = {}, port = 9004, tcpPoke = true)
        .decodeManifestForTest(
            javaClass.classLoader!!.getResourceAsStream("manifests/op3_29.bin")!!.readBytes()
        )

    @Test
    fun `all 29 records struct-decode`() {
        val files = decode()
        assertEquals(29, files.size)
        assertEquals("no path may be decoded twice", 29, files.map { it.path }.toHashSet().size)
        assertTrue("stills and videos on one card",
            files.any { it.ext == "JPG" } && files.any { it.ext == "MP4" })
    }

    /** The suffix is a user setting, not a model trait — this OP3 has none where the other has `_OP3`. */
    @Test
    fun `naming carries no OP3 suffix on this body`() {
        assertTrue(decode().none { it.name.contains("_OP3") })
    }

    /**
     * The point of this fixture: on this body a still carries **no delete handle at all**.
     *
     * It used to decode with two "collisions", each a still sharing a video's handle, and that was read
     * as camera behaviour — a photo inheriting the handle of the video shot beside it. It was ours. The
     * marker a handle hangs off sits before the path it belongs to, and the per-record window
     * deliberately reaches into the next record so the other fields can be found; a Pocket 3 photo has
     * no marker, so the scan walked past its path and matched the NEXT record's. Bounding the scan at
     * the record's own path leaves every still at handle 0 and every video with its own.
     *
     * It cost more than tidiness: the collision guard refuses BOTH sides, so two perfectly good videos
     * were undeletable because a photo had borrowed their handle. Nine files are deletable here, not
     * seven.
     */
    @Test
    fun `no still carries a delete handle`() {
        val files = decode()
        assertTrue("a still must never carry a handle on this body",
            files.filter { it.ext == "JPG" }.all { it.handle == 0L })
        assertEquals("and therefore none is deletable", 0, files.count { it.ext == "JPG" && it.deletable })
        assertEquals(20, files.count { it.handle == 0L })
    }

    /**
     * No two records may share a handle — the invariant `0x00/0x28` makes load-bearing.
     *
     * It addresses a file by handle, not by path, so a duplicate does not fail: it deletes the *other*
     * file and the grid drops the cell that was asked for, which reads as success. The guard that
     * refuses shared handles still exists and is still correct; there is simply nothing left for it to
     * catch on this fixture.
     */
    @Test
    fun `no handle is shared`() {
        val files = decode()
        val shared = files.filter { it.handle != 0L }.groupBy { it.handle }.filterValues { it.size > 1 }
        assertTrue("expected no collisions, got ${shared.keys.map { "0x%08x".format(it) }}", shared.isEmpty())
    }

    /** Every video has a handle of its own, and only videos are deletable. */
    @Test
    fun `every deletable file is a video with its own handle`() {
        val files = decode()
        assertEquals(9, files.count { it.deletable })
        assertTrue(files.filter { it.deletable }.all { it.ext == "MP4" })
    }
}
