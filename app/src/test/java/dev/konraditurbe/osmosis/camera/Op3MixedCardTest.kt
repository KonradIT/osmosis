package dev.konraditurbe.osmosis.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Osmo Pocket 3, 29 records — a **second** OP3, and a mixed card of videos and stills.
 *
 * From a tester's log of 2026-08-10. This is the card [Op3ManifestTest] said was needed: that fixture
 * holds videos only, every handle distinct and evenly stepped, and its comment leaves the explanation
 * for the collisions seen elsewhere as a theory — that *photo* records inherit a neighbouring video's
 * handle rather than the camera reusing handles. This card has both kinds on it, and it settles the
 * question: see [stills either have no handle at all or inherit a video's].
 *
 * It also disproves a naming assumption by counter-example. The other OP3 suffixes every file `_OP3`;
 * this one suffixes none. Same model, different *user* setting — so nothing may key off the suffix.
 * See MEDIA_PROTOCOL.md §1 on Naming Management.
 */
class Op3MixedCardTest {

    /**
     * The FULL decode, not [CameraSession.decodeCompositeForTest] — the collision guard runs after the
     * struct decode, and it is half of what this fixture is for. Note the log's own
     * `11 deletable` is the count *before* that guard; seven survive it.
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
     * The point of this fixture: a still never carries a delete handle of its own.
     *
     * Eighteen of the stills decode with handle `0`, and the two that do carry one carry a **video's**
     * — `_0729_D.JPG` shares `0x00042d80` with `_0728_D.MP4`, shot two minutes earlier. Both routes
     * end at "not deletable", by different rules: `handle == 0` and [CameraFile.handleShared].
     */
    @Test
    fun `stills either have no handle at all or inherit a video's`() {
        val files = decode()
        val stillsWithHandle = files.filter { it.ext == "JPG" && it.handle != 0L }
        assertEquals("only the collided pair's JPG carries a handle", 1, stillsWithHandle.size)
        assertTrue("and that handle belongs to a video shot alongside it",
            files.any { it.ext == "MP4" && it.handle == stillsWithHandle[0].handle })
        assertTrue("no still may ever be deletable on this body",
            files.filter { it.ext == "JPG" }.none { it.deletable })
    }

    /**
     * Two real handles are each shared by two files, and every file involved is refused for delete.
     *
     * Deleting by a shared handle takes the *other* file — `0x00/0x28` addresses by handle, not path —
     * and the grid then drops the cell that was asked for, so it reads as success. Unrecoverable, and
     * silent. Handle `0` is excluded here for the same reason the guard excludes it: eighteen stills
     * share it and none of them is a collision.
     */
    @Test
    fun `a shared handle disables delete on every file holding it`() {
        val files = decode()
        val shared = files.filter { it.handle != 0L }.groupBy { it.handle }.filterValues { it.size > 1 }
        assertEquals("two collisions, exactly as the log reported", 2, shared.size)
        assertEquals(
            "at 0x00042ca0 and 0x00042d80", listOf(0x00042ca0L, 0x00042d80L), shared.keys.sorted(),
        )
        for ((handle, group) in shared) {
            assertEquals("each collision is a pair", 2, group.size)
            for (f in group) assertFalse(
                "delete must stay disabled for ${f.name} (handle 0x%08x)".format(handle), f.deletable,
            )
        }
    }

    /** 29 records, 18 without a handle and 4 sharing one — leaving seven the app may delete. */
    @Test
    fun `only uncollided files with a handle of their own are deletable`() {
        val files = decode()
        assertEquals("stills carry no handle", 18, files.count { it.handle == 0L })
        assertEquals(7, files.count { it.deletable })
        assertTrue("every deletable file is a video", files.filter { it.deletable }.all { it.ext == "MP4" })
    }
}
