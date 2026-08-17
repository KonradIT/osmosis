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

    /**
     * The panorama is written as a plain `.JPG` and decodes as one.
     *
     * Telling it apart is not done yet: two bytes in its record differ from every normal still on the
     * same card — `path-15` reads `0x04` where a normal photo reads `0x00`, and `path-14` reads `0xc7`
     * where they read a uniform `0xf6`. With a single panorama either could be the type and the other a
     * coincidence, so nothing keys off them. This pins today's behaviour, which is that it looks like an
     * ordinary photo.
     */
    @Test
    fun `the panorama is indistinguishable from a photo so far`() {
        val pano = decode("op3_9_pano.bin").first { it.name.contains("_0010_D") }
        assertEquals("JPG", pano.ext)
        assertEquals(0L, pano.handle)
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
}
