package dev.konraditurbe.osmosis.camera

import dev.konraditurbe.osmosis.camera.CameraSession.Answer
import dev.konraditurbe.osmosis.camera.CameraSession.ListTransfer
import dev.konraditurbe.osmosis.camera.CameraSession.ListTransfer.Action
import dev.konraditurbe.osmosis.drone.DroneManifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The SD list query's transfer is released when its answer is in, not on a timer (issue #53).
 *
 * An Action 6 with a populated card answered the SD query with `4A 03` state frames and no data, twice,
 * while the same pass listed 45 internal files: `ctr1={end=15} ctr2={data=14,end=4}`. The release went
 * out one batch after the query regardless, so whatever the card was about to send never had the
 * chance. These tests pin how an answer is read off the wire and what the transfer does about it.
 */
class SdListTransferTest {

    private val s = CameraSession(log = {}, port = 9004, tcpPoke = true)

    private fun raw(fixture: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("manifests/$fixture")!!.readBytes()

    /** One `0x00/0x27` frame: 11-byte DUML header, the 10-byte `4A` sub-header, the chunk, 2 CRC bytes. */
    private fun frame(sub: Int, ctr: Int, chunk: ByteArray = ByteArray(0), final: Boolean = false): ByteArray {
        val lenFlags = (10 + chunk.size) or (if (final) 0x1000 else 0)
        val payload = byteArrayOf(
            0x4A, sub.toByte(), (lenFlags and 0xFF).toByte(), (lenFlags shr 8).toByte(),
            ctr.toByte(), 0, 0, 0, 0, 0,
        ) + chunk
        return dev.konraditurbe.osmosis.duml.DjiMessage(0x0201, 0x0403, 0x270000, payload).encode()
    }

    private fun state(ctr: Int) = frame(0x03, ctr)
    private fun data(ctr: Int, final: Boolean = false) = frame(0x01, ctr, ByteArray(40) { 0x20 }, final)

    /** A batch-paced transfer: release no earlier than step 1. */
    private fun transfer(hold: Boolean = true) =
        ListTransfer(ctr = 1, minStep = 1, quietSteps = 1, proceedWait = 2, maxSteps = 4, hold = hold)

    // ---- reading the answer ------------------------------------------------------------------------

    /** Both raw captures: no card, so the SD query gets a lone `4A 04`; the internal one streams and ends. */
    @Test
    fun `the raw captures read as an empty card and a finished internal list`() {
        for (fixture in listOf("nano_45.bin", "xtra_13.bin")) {
            val blob = raw(fixture)
            assertEquals("$fixture SD", Answer.EMPTY, s.answerState(blob, 1))
            assertEquals("$fixture internal", Answer.DONE, s.answerState(blob, 2))
            assertEquals("$fixture, a counter never asked", Answer.SILENT, s.answerState(blob, 3))
        }
    }

    /** The Action 6's card in issue #53: state frames under counter 1, never a chunk. */
    @Test
    fun `state frames with no data are a camera that is waiting, not one that has finished`() {
        val blob = state(1) + state(1) + state(1) + data(2) + data(2, final = true) + state(2)
        assertEquals(Answer.WAITING, s.answerState(blob, 1))
        assertEquals(Answer.DONE, s.answerState(blob, 2))
    }

    @Test
    fun `an answer is done at its final chunk, and a state frame never closes it`() {
        assertEquals("mid-stream", Answer.STREAMING, s.answerState(data(1) + data(1), 1))
        assertEquals("final chunk", Answer.DONE, s.answerState(data(1) + data(1, final = true), 1))
        assertEquals("final chunk, then the state frames that follow it", Answer.DONE,
            s.answerState(data(1) + data(1, final = true) + state(1) + state(1), 1))
        // The waiting camera that then starts sending is streaming, not done: its state frames came first.
        assertEquals("state before data", Answer.STREAMING, s.answerState(state(1) + state(1) + data(1), 1))
    }

    /**
     * Issue #53, second log. With the card listing at last, the internal store came back with 31 of 45
     * records on one page and none on the last, each time "ended": a state frame under its counter had
     * been read as the end of an answer that was still arriving, or had not begun.
     */
    @Test
    fun `a state frame in the middle of a stream is not its end`() {
        assertEquals(Answer.STREAMING, s.answerState(data(2) + data(2) + state(2), 2))
        assertEquals("and the rest still counts", Answer.DONE,
            s.answerState(data(2) + state(2) + data(2, final = true), 2))
    }

    /** The internal query follows the SD one on the same blob, where an earlier transfer may still be talking. */
    @Test
    fun `the internal answer is judged from where its query went out`() {
        val before = data(1) + data(1, final = true) + state(2)      // a state frame left over under counter 2
        assertEquals("nothing of the new answer yet", Answer.SILENT, s.answerState(before, 2, from = before.size))
        assertEquals(Answer.WAITING, s.answerState(before + state(2), 2, from = before.size))
        assertEquals(Answer.DONE, s.answerState(before + state(2) + data(2, final = true), 2, from = before.size))
    }

    /** The SD retry appends to a blob that already holds the first, empty answer under the same counter. */
    @Test
    fun `a re-asked query is judged by its own frames`() {
        val first = frame(0x04, 1)                           // not mounted yet: start frame, no data
        assertEquals(Answer.EMPTY, s.answerState(first, 1))
        assertEquals("nothing yet for the retry", Answer.SILENT, s.answerState(first, 1, from = first.size))
        val retried = first + data(1) + data(1, final = true)
        assertEquals(Answer.DONE, s.answerState(retried, 1, from = first.size))
    }

    // ---- releasing the transfer --------------------------------------------------------------------

    /** A card that answers at once keeps the timing it always had: release on step 1. */
    @Test
    fun `an answer that is already in is released on step one, as before`() {
        for (answer in listOf(Answer.DONE, Answer.EMPTY)) {
            val t = transfer()
            assertEquals("$answer, step 0", Action.WAIT, t.next(0, answer, 0))
            assertEquals("$answer, step 1", Action.RELEASE, t.next(1, answer, 0))
            assertEquals("$answer, released once", Action.WAIT, t.next(2, answer, 0))
        }
    }

    /**
     * Issue #53. The camera raises state frames and sends nothing. It is told to proceed, once, and the
     * transfer stays open until the list that follows has finished — the old timer released it on step 1.
     */
    @Test
    fun `a waiting camera is told to proceed and its transfer is held until the list is in`() {
        val t = transfer()
        assertEquals(Action.PROCEED, t.next(0, Answer.WAITING, 0))
        assertEquals("not released on the old timer", Action.WAIT, t.next(1, Answer.WAITING, 0))
        assertEquals("still arriving", Action.WAIT, t.next(2, Answer.STREAMING, 20))
        assertEquals(Action.RELEASE, t.next(3, Answer.DONE, 45))
    }

    /** A proceed that brings nothing costs two steps, then the transfer is released and the load goes on. */
    @Test
    fun `a proceed that goes unanswered does not hold the load up`() {
        val t = transfer()
        assertEquals(Action.PROCEED, t.next(0, Answer.WAITING, 0))
        assertEquals(Action.WAIT, t.next(1, Answer.WAITING, 0))
        assertEquals(Action.RELEASE, t.next(2, Answer.WAITING, 0))
    }

    @Test
    fun `a stream is not released while records are still arriving`() {
        val t = transfer()
        assertEquals(Action.WAIT, t.next(0, Answer.STREAMING, 10))
        assertEquals(Action.WAIT, t.next(1, Answer.STREAMING, 30))
        assertEquals("grew again", Action.WAIT, t.next(2, Answer.STREAMING, 45))
        assertEquals("a batch with nothing new, final chunk lost", Action.RELEASE, t.next(3, Answer.STREAMING, 45))
    }

    @Test
    fun `a camera that says nothing at all is released at the limit`() {
        val t = transfer()
        for (step in 0..3) assertEquals("step $step", Action.WAIT, t.next(step, Answer.SILENT, 0))
        assertEquals(Action.RELEASE, t.next(4, Answer.SILENT, 0))
    }

    /** The limit is for silence only. An Action 6 took 3.6 s over one card page; a cap would have cut it. */
    @Test
    fun `a stream that keeps growing is never cut by the silence limit`() {
        val t = transfer()
        for (step in 0..9) assertEquals("step $step", Action.WAIT, t.next(step, Answer.STREAMING, step * 5))
        assertEquals(Action.RELEASE, t.next(10, Answer.DONE, 45))
    }

    /** A store past its end marker, or never populated, keeps the old timing: step 1, no proceed, no waiting. */
    @Test
    fun `a store with nothing older to give is released on step one whatever it says`() {
        for (answer in Answer.values()) {
            val t = transfer(hold = false)
            assertEquals("$answer, step 0", Action.WAIT, t.next(0, answer, 0))
            assertEquals("$answer, step 1", Action.RELEASE, t.next(1, answer, 0))
        }
    }

    // ---- the frames ----------------------------------------------------------------------------------

    /** The release is the frame this file always sent; the proceed is the drone's, for counter 1. */
    @Test
    fun `the control frames are the transfer family's, for the SD counter`() {
        assertEquals("4a040e1001000000000001000000", s.SD_RELEASE.joinToString("") { "%02x".format(it) })
        assertArrayEquals(DroneManifest.listAck(1), s.SD_RELEASE)
        assertArrayEquals(DroneManifest.transferGo(1), s.SD_PROCEED)
    }

    /** The internal store's transfer is released and proceeded with its own counter, 2. */
    @Test
    fun `each store's control frames carry its own counter`() {
        assertArrayEquals(s.SD_RELEASE, s.listRelease(1))
        assertArrayEquals(DroneManifest.listAck(2), s.listRelease(2))
        assertArrayEquals(DroneManifest.transferGo(2), s.listProceed(2))
        assertEquals("the SD frame itself is left alone", "4a040e1001000000000001000000",
            s.SD_RELEASE.joinToString("") { "%02x".format(it) })
    }
}
