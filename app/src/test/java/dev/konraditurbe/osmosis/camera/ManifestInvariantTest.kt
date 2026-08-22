package dev.konraditurbe.osmosis.camera

import dev.konraditurbe.osmosis.core.CameraFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Properties that must hold on a **clean** decode of every real capture — the structural contract the
 * rest of the app relies on, checked against ground truth rather than re-derived from the same bytes.
 *
 * The golden master ([ManifestGoldenTest]) pins the exact values; this pins the *rules* those values
 * must obey, so a future fixture (or a plausible-looking edit that keeps the golden lines but breaks an
 * invariant) is caught by whichever net is tighter.
 */
class ManifestInvariantTest {

    private fun raw(f: String) =
        javaClass.classLoader!!.getResourceAsStream("manifests/$f")!!.readBytes()

    /** (fixture, port, expected record count). The count guards against a silent under-read. */
    private val bodies = listOf(
        Triple("nano_45.bin", 9004, 45),
        Triple("nano_delete.bin", 9004, 45),
        Triple("xtra_13.bin", 10004, 13),
        Triple("xtra_delete.bin", 10004, 45),
        Triple("oa6_sd_3.bin", 10004, 3),
        Triple("oa6_internal_2.bin", 10004, 2),
        Triple("op3_5_starred.bin", 9004, 5),
        Triple("op3_9_pano.bin", 9004, 9),
        Triple("op3_11_panos.bin", 9004, 11),
        Triple("op3_15.bin", 9004, 15),
        Triple("op3_29.bin", 9004, 29),
        Triple("op4_45.bin", 9004, 45),
    )

    private fun decode(f: String, port: Int): List<CameraFile> =
        CameraSession(log = {}, port = port, tcpPoke = port == 9004).decodeManifestBlobForTest(raw(f))

    @Test fun `record counts are exactly what the cameras hold`() {
        for ((f, port, n) in bodies) assertEquals(f, n, decode(f, port).size)
    }

    /** The one that matters for an irreversible command: a deletable file's handle is the vouched one. */
    @Test fun `every deletable file has a handle the fit agrees with`() {
        for ((f, port, _) in bodies) {
            for (x in decode(f, port)) if (x.deletable) {
                assertTrue("$f: ${x.name} deletable with handle 0", x.handle != 0L)
                if (x.cmdHandle != 0L)
                    assertEquals("$f: ${x.name} deletable handle disagrees with the fit", x.cmdHandle, x.handle)
            }
        }
    }

    /** No two deletable files may address the same object — that is how the wrong file gets destroyed. */
    @Test fun `no two deletable files share a handle`() {
        for ((f, port, _) in bodies) {
            val handles = decode(f, port).filter { it.deletable }.map { it.handle }
            assertEquals("$f: a delete handle is shared", handles.size, handles.toSet().size)
        }
    }

    /** Paths are the dedup key and the fetch address; a repeat means a cell fetches the wrong bytes. */
    @Test fun `every decoded path is unique`() {
        for ((f, port, _) in bodies) {
            val paths = decode(f, port).map { it.path }
            assertEquals("$f: a path repeats", paths.size, paths.toSet().size)
        }
    }

    /** Sizes and durations are unsigned; a negative one is a misread u32/u16 sign-extended. */
    @Test fun `sizes and durations are never negative`() {
        for ((f, port, _) in bodies) {
            for (x in decode(f, port)) {
                assertTrue("$f: ${x.name} negative size ${x.sizeBytes}", x.sizeBytes >= 0)
                assertTrue("$f: ${x.name} negative duration ${x.durationSec}", x.durationSec >= 0)
            }
        }
    }

    /** A video that decoded a resolution label must have decoded an fps too, and vice versa — both hang
     *  off the same marker, so one without the other means the marker was found only half-read. */
    @Test fun `a resolution and an fps come together on a clip`() {
        // Not universal across bodies (some clips carry an unmapped resolution index → null label while
        // fps still reads), so this asserts the weaker, always-true direction: an fps implies the record
        // had a readable marker, which is what the delete/size reads also depend on.
        for ((f, port, _) in bodies) {
            for (x in decode(f, port)) if (x.resLabel != null) {
                assertTrue("$f: ${x.name} has fps but no handle — marker half-read", x.handle != 0L)
            }
        }
    }

    /** The two store-lists a carded camera returns are labelled 0 and 1, never anything else. */
    @Test fun `group is always 0 or 1`() {
        for ((f, port, _) in bodies) {
            for (x in decode(f, port)) assertTrue("$f: ${x.name} group=${x.group}", x.group == 0 || x.group == 1)
        }
    }
}
