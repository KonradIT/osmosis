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
}
