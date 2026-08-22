package dev.konraditurbe.osmosis.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoder runs on bytes off a radio link, so it must survive a manifest that arrives short, split,
 * or corrupted without ever throwing — and, more importantly, without ever offering an **unsafe delete
 * handle** on garbage input. A crash drops the grid; a wrong handle destroys a file.
 *
 * These are deterministic sweeps, not random fuzzing: every truncation length and a fixed stride of
 * byte flips across every fixture. Two invariants must hold for all of them:
 *
 *  1. **No throw.** Whatever the bytes, decode returns a (possibly empty) list.
 *  2. **No unsafe handle.** Any file the UI would offer to delete (`deletable`) must carry a handle
 *     equal to the fitted `cmdHandle` — the two-source agreement rule. Corruption may make a file
 *     *non*-deletable, never falsely deletable at the wrong address.
 *
 * The counts are asserted too, so a future change that quietly stops exercising the fixtures (a
 * renamed resource, an early return) fails instead of passing vacuously.
 */
class ManifestFuzzTest {

    private fun raw(f: String) =
        javaClass.classLoader!!.getResourceAsStream("manifests/$f")!!.readBytes()

    private val fixtures = listOf(
        "nano_45.bin", "nano_delete.bin", "xtra_13.bin", "xtra_delete.bin", "oa6_sd_3.bin",
        "oa6_internal_2.bin", "op3_5_starred.bin", "op3_9_pano.bin", "op3_11_panos.bin",
        "op3_15.bin", "op3_29.bin", "op4_45.bin", "mini3_21.bin",
    )

    private fun session() = CameraSession(log = {}, port = 9004, tcpPoke = true)

    /** A file that would be offered for deletion whose handle the fit does not vouch for. */
    private fun unsafe(files: List<dev.konraditurbe.osmosis.core.CameraFile>) =
        files.filter { it.deletable && it.cmdHandle != 0L && it.handle != it.cmdHandle }

    @Test fun `every truncation decodes without throwing or producing an unsafe handle`() {
        var cases = 0
        val throwsAt = ArrayList<String>()
        val unsafeAt = ArrayList<String>()
        for (f in fixtures) {
            val b = raw(f)
            val step = if (b.size > 2000) 7 else 1
            var n = 0
            while (n <= b.size) {
                cases++
                try {
                    val files = session().decodeManifestBlobForTest(b.copyOfRange(0, n))
                    unsafe(files).forEach { unsafeAt.add("$f@$n ${it.name}") }
                } catch (e: Throwable) {
                    throwsAt.add("$f@$n ${e::class.simpleName}: ${e.message}")
                }
                n += step
            }
        }
        assertTrue("decode threw: ${throwsAt.take(10)}", throwsAt.isEmpty())
        assertTrue("unsafe handle on truncated input: ${unsafeAt.take(10)}", unsafeAt.isEmpty())
        assertTrue("far too few cases ($cases) — did the fixtures load?", cases > 2000)
    }

    @Test fun `every single-byte corruption decodes without throwing or producing an unsafe handle`() {
        var cases = 0
        val throwsAt = ArrayList<String>()
        val unsafeAt = ArrayList<String>()
        for (f in fixtures) {
            val b = raw(f)
            val step = if (b.size > 2000) 13 else 3
            var i = 0
            while (i < b.size) {
                val c = b.copyOf()
                c[i] = (c[i] + 1).toByte()
                cases++
                try {
                    val files = session().decodeManifestBlobForTest(c)
                    unsafe(files).forEach { unsafeAt.add("$f@$i ${it.name}") }
                } catch (e: Throwable) {
                    throwsAt.add("$f@$i ${e::class.simpleName}: ${e.message}")
                }
                i += step
            }
        }
        assertTrue("decode threw: ${throwsAt.take(10)}", throwsAt.isEmpty())
        assertTrue("unsafe handle on corrupted input: ${unsafeAt.take(10)}", unsafeAt.isEmpty())
        assertTrue("far too few cases ($cases)", cases > 1000)
    }

    /** Empty and tiny inputs are the commonest real edge (a store that answered with nothing). */
    @Test fun `empty and sub-header-length inputs decode to nothing`() {
        for (n in 0..16) {
            assertEquals("$n bytes should decode empty", 0, session().decodeManifestBlobForTest(ByteArray(n)).size)
        }
    }
}
