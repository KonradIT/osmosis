package dev.konraditurbe.osmosis.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Osmo Action 1's raw list capture through the **whole** per-store pipeline, not just the record
 * decoder. [dev.konraditurbe.osmosis.dcf.Action1RecordsTest] pins the 65-byte layout; this pins that
 * the session's collect path still hands those bytes to it intact.
 *
 * The decoder was written before the per-store split existed. What makes the two compatible is in
 * `action1_7.bin`: the camera echoes the request counter, answers SD (ctr 1) with one 463-byte chunk
 * carrying the final-chunk flag, and answers internal (ctr 2) with a bare start frame. So the SD slice
 * is one whole `[count][total]` list, and the "both stores sent the same list" fallback, which would
 * concatenate two headers into something `decodeAction1` must reject, never fires.
 */
class Action1ListPipelineTest {

    private val raw = javaClass.classLoader!!.getResourceAsStream("manifests/action1_7.bin")!!.readBytes()

    @Test
    fun `the raw capture splits into seven SD files and an empty internal store`() {
        val files = CameraSession(log = {}).collectStoresForTest(raw)
        assertEquals(7, files.size)
        assertTrue("every file came from the SD query", files.all { it.storage == 0 && it.storageKnown })
        assertEquals("DCIM/100MEDIA/DJI_0593.MP4", files.first().path)
    }

    @Test
    fun `the SD transfer reads as finished, so it is released rather than waited out`() {
        val s = CameraSession(log = {})
        assertEquals(CameraSession.Answer.DONE, s.answerState(raw, ctr = 1))
        assertEquals(CameraSession.Answer.EMPTY, s.answerState(raw, ctr = 2))
    }
}
