package dev.konraditurbe.osmosis.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Osmo Pocket 3, 15 records — the only OP3 manifest we hold.
 *
 * Recovered from the hex dump in a tester's log of 2026-07-24, where the decoder of the day gave up:
 * `manifest struct-decode skipped (declared=15, records=0) — flat scrape`. It struct-decodes in full
 * now, so this pins that back.
 *
 * It matters beyond regression because the OP3 names files differently — a `_OP3` suffix the other
 * cameras don't emit (`DJI_20260721121344_0015_D_OP3.MP4`) — which makes its records a different
 * length, and record length is what every offset in the decoder is measured from.
 */
class Op3ManifestTest {

    private fun decode() = CameraSession(log = {}, port = 9004, tcpPoke = true)
        .decodeCompositeForTest(
            javaClass.classLoader!!.getResourceAsStream("manifests/op3_15.bin")!!.readBytes()
        )

    @Test
    fun `all 15 records struct-decode`() {
        val files = decode()
        assertEquals("the July build got 0 of these", 15, files.size)
        assertEquals(15, files.map { it.path }.toHashSet().size)
        assertTrue("the _OP3 suffix must survive the name parse",
            files.all { it.name.contains("_D_OP3.") })
        assertEquals(15, files.count { it.ext == "MP4" })
    }

    /**
     * Every handle distinct, and the sequence perfectly linear: `0x00040010` + `seq * 0x10`.
     *
     * This is the counter-example to the collisions seen on a *different* OP3 card, where two JPGs
     * each shared a handle with the video shot seconds earlier. This card holds only videos and is
     * clean, which is what points at photo records rather than the camera reusing handles — the
     * failure mode CameraFile.cmdHandle already documents ("a photo inheriting the next video's
     * handle"). Unproven until a mixed OP3 card is dumped; see handleShared for the guard meanwhile.
     */
    @Test
    fun `handles are unique and evenly stepped`() {
        val files = decode()
        val handles = files.map { it.handle }
        assertEquals("no two records may share a delete handle", 15, handles.toHashSet().size)
        assertTrue(files.all { it.deletable })
        for (f in files) assertEquals(
            "handle should be base + seq*step for ${f.name}",
            0x00040000L + f.seq * 0x10L, f.handle,
        )
    }
}
