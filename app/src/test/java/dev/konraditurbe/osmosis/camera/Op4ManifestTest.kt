package dev.konraditurbe.osmosis.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Osmo Pocket 4, 45 records — recovered from a tester's log of 2026-08-08.
 *
 * The Pocket 4 is the newest body that reaches the grid, and until this fixture nothing pinned it:
 * the model was marked verified purely on a log line saying 45 records decoded. Here are the bytes.
 *
 * Worth having because the OP4 shares the **Nano's** handle geometry (`0x40100000` + `seq * 0x40`)
 * rather than the Action family's `0x10` step, despite being a Pocket — so the "Pocket 3 and Pocket 4
 * are the same family" assumption a reader might make from the names is wrong at the byte level.
 */
class Op4ManifestTest {

    private fun decode() = CameraSession(log = {}, port = 9004, tcpPoke = true)
        .decodeCompositeForTest(
            javaClass.classLoader!!.getResourceAsStream("manifests/op4_45.bin")!!.readBytes()
        )

    @Test
    fun `all 45 records struct-decode`() {
        val files = decode()
        assertEquals(45, files.size)
        assertEquals("every path distinct", 45, files.map { it.path }.toHashSet().size)
        assertEquals(45, files.count { it.ext == "MP4" })
        assertTrue("stock DJI_ naming, no model suffix", files.all { it.name.startsWith("DJI_") })
    }

    /** Nano geometry on a Pocket body: base `0x40100000`, step `0x40`, every handle its own. */
    @Test
    fun `handles are unique and stepped by 0x40`() {
        val files = decode()
        assertEquals("no two records may share a delete handle", 45, files.map { it.handle }.toHashSet().size)
        assertTrue(files.all { it.deletable })
        for (f in files) assertEquals(
            "handle should be base + seq*step for ${f.name}",
            0x40100000L + f.seq * 0x40L, f.handle,
        )
    }

    /**
     * The star column reads 0 across the board here — which proves nothing about the flag.
     *
     * Nothing had been favourited on this camera when the manifest was captured, exactly as with
     * `nano_45.bin`. Asserted so the fixture is not later mistaken for evidence that the Pocket 4
     * lacks a star flag; only a capture with a known favourite can settle that.
     */
    @Test
    fun `nothing is starred, because nothing was favourited`() {
        assertTrue(decode().none { it.starred })
    }
}
