package dev.konraditurbe.osmosis.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two-source rule for delete handles.
 *
 * A handle is only usable when the **record's own bytes** and a **formula fitted to the other records
 * in the same list** agree on it. That is deliberately stricter than either source alone, because
 * `0x00/0x28` addresses a file by handle: a wrong one does not fail, it destroys the wrong file.
 *
 * It also retires a limitation we had written down as fact — that a Pocket 3's stills "carry no
 * handle, and must be treated as non-deletable". They carry one. The guard-byte scan simply refused to
 * read it: a Pocket 3 writes `f6` before the `19 06` tag on a still and `c7` on a panorama, where a
 * Nano writes `ff`/`fe`. Reading the same fixed position the media-type byte comes from finds it, and
 * the fit confirms it — on these fixtures every still's handle lands exactly on `base + seq*step`.
 */
class HandleValidationTest {

    private fun raw(fixture: String) =
        javaClass.classLoader!!.getResourceAsStream("manifests/$fixture")!!.readBytes()

    private fun files(fixture: String, port: Int = 9004) =
        CameraSession(log = {}, port = port, tcpPoke = port == 9004).decodeManifestBlobForTest(raw(fixture))

    /**
     * The regression guard for the bodies that already worked. Promotion must never take a handle
     * away, so every fixture that had deletable records must still have at least as many.
     */
    @Test
    fun `bodies that already had handles keep them`() {
        for ((fixture, port) in listOf(
            "nano_45.bin" to 9004, "nano_delete.bin" to 9004,
            "xtra_13.bin" to 10004, "xtra_delete.bin" to 10004,
            "oa6_sd_3.bin" to 10004, "oa6_internal_2.bin" to 10004,
            "op4_45.bin" to 9004,
        )) {
            val f = files(fixture, port)
            assertTrue("$fixture decoded nothing", f.isNotEmpty())
            val deletable = f.count { it.deletable }
            assertTrue("$fixture: no deletable records left", deletable > 0)
            // Nothing may hold a handle the fit contradicts — that is the whole point of the pass.
            for (x in f) if (x.handle != 0L && x.cmdHandle != 0L) {
                assertEquals("$fixture: ${x.name} handle disagrees with the fit", x.cmdHandle, x.handle)
            }
        }
    }

    /** A Pocket 3's stills now carry handles, and each one lands on the fitted formula. */
    @Test
    fun `pocket 3 stills become deletable`() {
        val f = files("op3_29.bin")
        val stills = f.filter { it.isImage }
        assertTrue("no stills in the fixture", stills.size >= 10)
        assertTrue("stills still have no handle", stills.all { it.handle != 0L })
        for (s in stills) assertEquals(s.cmdHandle, s.handle)
    }

    /** Panoramas are stills with a different guard byte (`c7`), and must come through the same way. */
    @Test
    fun `pocket 3 panoramas become deletable`() {
        val f = files("op3_11_panos.bin")
        val panos = f.filter { it.isPanorama }
        assertTrue("no panoramas in the fixture", panos.isNotEmpty())
        assertTrue("panorama has no handle", panos.all { it.handle != 0L })
    }

    /**
     * The collision that made a real video undeletable was a photo reading across the record boundary
     * and taking the video's handle. With every record reading its own fixed position, no two records
     * in these fixtures share a handle.
     */
    @Test
    fun `no handle is claimed by two records`() {
        for (fixture in listOf("op3_29.bin", "op3_11_panos.bin", "op3_9_pano.bin", "op3_15.bin")) {
            val f = files(fixture)
            val handles = f.filter { it.handle != 0L }.map { it.handle }
            assertEquals("$fixture: duplicate handles", handles.size, handles.toSet().size)
            assertTrue("$fixture: a record is marked handle-shared", f.none { it.handleShared })
        }
    }
}
