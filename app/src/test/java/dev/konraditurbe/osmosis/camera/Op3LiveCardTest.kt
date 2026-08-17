package dev.konraditurbe.osmosis.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two dumps of the **same** Osmo Pocket 3 card, two minutes apart, taken while the camera was in hand
 * (2026-08-17) with every file's provenance known.
 *
 * `op3_5_starred.bin` — 5 files, and `0001_D.MP4` is **favourited** (the heart shows in the camera's own
 * gallery). The first OP3 fixture with a star on it; the star is not decoded yet, so what this pins is
 * that the record survives it.
 *
 * `op3_9_pano.bin` — the same card plus four stills shot back to back, the last of which is a
 * **panorama** assembled in-camera and written as an ordinary `.JPG`. Four adjacent stills is the case
 * [Op3MixedCardTest]'s fix could not cover: with a video between them, a photo's neighbour always had a
 * marker to stop the scan running on.
 *
 * Together they are the regression test for that fix on a body whose stills carry no marker at all.
 */
class Op3LiveCardTest {

    private fun decode(name: String) = CameraSession(log = {}, port = 9004, tcpPoke = true)
        .decodeManifestForTest(
            javaClass.classLoader!!.getResourceAsStream("manifests/$name")!!.readBytes()
        )

    @Test
    fun `the five-file card decodes whole`() {
        val files = decode("op3_5_starred.bin")
        assertEquals(5, files.size)
        assertEquals(3, files.count { it.ext == "MP4" })
        assertEquals(2, files.count { it.ext == "JPG" })
    }

    /**
     * Four stills in a row, and not one of them borrows a handle.
     *
     * This is the shape that would break if the handle scan ran past a record again: each of these
     * photos has another photo on both sides, so an overrun has nothing to stop it until it reaches a
     * video several records away. Every still must read 0, and the three videos must keep their own.
     */
    @Test
    fun `adjacent stills never borrow a handle`() {
        val files = decode("op3_9_pano.bin")
        assertEquals(9, files.size)
        assertTrue("no still may carry a handle",
            files.filter { it.ext == "JPG" }.all { it.handle == 0L })
        assertEquals("the three videos, and only those", 3, files.count { it.handle != 0L })
        val handles = files.filter { it.handle != 0L }.map { it.handle }
        assertEquals("no video may share a handle", handles.size, handles.toHashSet().size)
    }

    /** Only videos are deletable on this body, for the same reason: a still has no handle to delete by. */
    @Test
    fun `only videos are deletable`() {
        for (f in listOf("op3_5_starred.bin", "op3_9_pano.bin")) {
            val files = decode(f)
            assertTrue(f, files.filter { it.deletable }.all { it.ext == "MP4" })
            assertEquals(f, 3, files.count { it.deletable })
        }
    }

    /** The panorama is written as a plain `.JPG` and carries no handle, like any other still. */
    @Test
    fun `a panorama is still a handleless JPG`() {
        val pano = decode("op3_9_pano.bin").first { it.name.contains("_0010_D") }
        assertEquals("JPG", pano.ext)
        assertEquals(0L, pano.handle)
        assertTrue("and is recognised as a panorama", pano.isPanorama)
    }

    /**
     * The favourite flag, established by a controlled A/B with the camera in hand.
     *
     * `op3_9_pano.bin` and `op3_9_stars_moved.bin` are the same nine files minutes apart, with only the
     * favourites changed in between — `0001` cleared, `0005` set, and `0002` (a **still**) set. The two
     * blobs differ in exactly three bytes, and those three are these flags.
     *
     * It is read off a fixed signature rather than the `[ff|fe] 19 06` marker because a Pocket 3 still
     * has no marker at all: before this, a favourited photo could not show a heart at any offset.
     */
    @Test
    fun `the favourite flag tracks the camera on both videos and stills`() {
        val before = decode("op3_9_pano.bin").associateBy { it.name }
        val after = decode("op3_9_stars_moved.bin").associateBy { it.name }
        fun starOf(m: Map<String, dev.konraditurbe.osmosis.core.CameraFile>, n: String) =
            m.entries.first { it.key.contains(n) }.value.starred

        assertTrue("0001 was favourited", starOf(before, "_0001_D"))
        assertTrue("and was cleared on the camera", !starOf(after, "_0001_D"))

        assertTrue("0005 was not favourited", !starOf(before, "_0005_D"))
        assertTrue("and was set on the camera", starOf(after, "_0005_D"))

        // The one that the marker-based read could never have seen.
        assertTrue("0002 is a still", after.entries.first { it.key.contains("_0002_D") }.value.ext == "JPG")
        assertTrue("0002 was not favourited", !starOf(before, "_0002_D"))
        assertTrue("and a favourited STILL reads as starred", starOf(after, "_0002_D"))

        assertEquals("exactly one star before", 1, before.values.count { it.starred })
        assertEquals("exactly two after", 2, after.values.count { it.starred })
    }

    /**
     * The media-type byte, and with it the panorama.
     *
     * `op3_11_panos.bin` is the same card again with an ordinary photo and a second panorama shot back
     * to back, so both kinds appear twice or more on one card: two panoramas read `0x04`, six stills
     * read `0x00`, three videos read `0x03`. The byte sits at `mediaPath - 15`, immediately before the
     * constant `19 06` tag, and is the same one the delete-handle marker reads as its "kind".
     *
     * A panorama is written as a plain `.JPG` with nothing else to distinguish it, so this byte is the
     * only thing that can. Superseding [the panorama is indistinguishable from a photo so far].
     */
    @Test
    fun `the type byte separates panoramas from photos and videos`() {
        val files = decode("op3_11_panos.bin")
        assertEquals(11, files.size)

        val panos = files.filter { it.isPanorama }
        assertEquals("two panoramas on this card", 2, panos.size)
        assertTrue("both are written as ordinary JPGs", panos.all { it.ext == "JPG" })
        assertTrue("0010 and 0012",
            panos.map { it.name.substringAfter("_0").take(3) }.toSet() == setOf("010", "012"))

        assertEquals("stills", 0x00, files.first { it.name.contains("_0011_D") }.mediaType)
        assertEquals("videos", 0x03, files.first { it.name.contains("_0001_D") }.mediaType)
        assertTrue("no video is ever a panorama", files.none { it.isVideo && it.isPanorama })
        assertEquals("six ordinary stills", 6, files.count { it.mediaType == 0x00 })
    }
}
