package dev.konraditurbe.osmosis.stream

import dev.konraditurbe.osmosis.duml.OsmoCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera's BLE notifications are not one-frame-each: a frame can be split across two, or two
 * can share one. Reading payloads out of a frame the reassembler had to stitch is the only way
 * to tell this works without hardware.
 */
class DumlReassemblerTest {

    private val frame = OsmoCommands.startStream()

    @Test
    fun `one complete frame passes through untouched`() {
        val out = DumlReassembler().feed(frame)
        assertEquals(1, out.size)
        assertEquals(frame.toList(), (out[0].encode()).toList())
    }

    @Test
    fun `a frame split across two notifications is stitched back together`() {
        val r = DumlReassembler()
        val cut = frame.size / 2
        assertTrue(r.feed(frame.copyOfRange(0, cut)).isEmpty())
        val out = r.feed(frame.copyOfRange(cut, frame.size))
        assertEquals(1, out.size)
        assertEquals(frame.toList(), out[0].encode().toList())
    }

    @Test
    fun `two frames in one notification both come out`() {
        val r = DumlReassembler()
        val out = r.feed(OsmoCommands.prepareLiveStreamStage1() + OsmoCommands.stopStream())
        assertEquals(2, out.size)
        assertEquals(0xE1, out[0].cmdId)
        assertEquals(listOf(0x01, 0x01, 0x1A, 0x00, 0x01, 0x02), out[1].payload.map { it.toInt() and 0xFF })
    }

    @Test
    fun `leading garbage is skipped up to the next start-of-frame byte`() {
        val out = DumlReassembler().feed(byteArrayOf(0x00, 0x11, 0x22) + frame)
        assertEquals(1, out.size)
        assertEquals(frame.toList(), out[0].encode().toList())
    }

    @Test
    fun `noise alone yields nothing and does not grow without bound`() {
        val r = DumlReassembler()
        repeat(10) { assertTrue(r.feed(ByteArray(100) { 0x11.toByte() }).isEmpty()) }
        assertTrue(r.feed(frame).isNotEmpty())
    }
}
