package dev.konraditurbe.osmosis.net

import dev.konraditurbe.osmosis.duml.DjiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The datalink's wire framing.
 *
 * Worth having specifically because the two worst outages in this code's history were both **wrong
 * values in the 12-byte routing header** — leaking the peer's telemetry seq into our ack (which forced a
 * fresh registered session for every delete/favourite/page/burst), and then freezing the
 * drone's ack at the handshake channel (a healthy-looking session that answered no command at all).
 * Both were silent in the same asymmetric way: reads and keepalive kept flowing while writes were
 * dropped by the receive window. Neither was catchable by a test while these builders were private and
 * tangled up with the socket.
 */
class DumlTransportTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ---- transport header --------------------------------------------------------------------------

    @Test
    fun `transport header carries session and seq little-endian with an xor trailer`() {
        val h = DumlTransport.udpHeader(pktType = 0x05, payloadLen = 40, sessionId = 0x2965, seq = 0x87C0)
        assertEquals(8, h.size)
        assertEquals(0x65, h[2].toInt() and 0xFF)   // session LE
        assertEquals(0x29, h[3].toInt() and 0xFF)
        assertEquals(0xC0, h[4].toInt() and 0xFF)   // seq LE
        assertEquals(0x87, h[5].toInt() and 0xFF)
        assertEquals(0x05, h[6].toInt() and 0xFF)   // pktType

        var xor = 0
        for (i in 0 until 7) xor = xor xor (h[i].toInt() and 0xFF)
        assertEquals("byte 7 is the XOR of the preceding seven", xor, h[7].toInt() and 0xFF)
    }

    @Test
    fun `total length is payload plus the eight header bytes, with the high flag bit set`() {
        val h = DumlTransport.udpHeader(0x05, payloadLen = 40, sessionId = 0x2965, seq = 0)
        val w0 = (h[0].toInt() and 0xFF) or ((h[1].toInt() and 0xFF) shl 8)
        assertEquals(48, w0 and 0x3FFF)
        assertTrue("bit 15 is always set", (w0 and 0x8000) != 0)
    }

    // ---- routing header ----------------------------------------------------------------------------

    @Test
    fun `the ack is our own previous seq, never the peer's channel`() {
        // r0-1 once held the camera's telemetry seq, which runs ~10x faster than our
        // commands and wraps to a different phase, so it drifted out of the receive window.
        val rt = DumlTransport.routingHeader(seq = 0x1570, cmdCounter = 3, drone = false)
        assertEquals(12, rt.size)
        val ack = (rt[0].toInt() and 0xFF) or ((rt[1].toInt() and 0xFF) shl 8)
        val seq = (rt[2].toInt() and 0xFF) or ((rt[3].toInt() and 0xFF) shl 8)
        assertEquals(0x1570, seq)
        assertEquals("ack trails our own seq by exactly one 8-step", 0x1568, ack)
    }

    @Test
    fun `the ack wraps with the sequence rather than going negative`() {
        val rt = DumlTransport.routingHeader(seq = 0x0004, cmdCounter = 0, drone = false)
        val ack = (rt[0].toInt() and 0xFF) or ((rt[1].toInt() and 0xFF) shl 8)
        assertEquals(0xFFFC, ack)
    }

    @Test
    fun `byte 10 distinguishes a drone command from a camera one`() {
        assertEquals(0x60, DumlTransport.routingHeader(0x100, 1, drone = true)[10].toInt() and 0xFF)
        assertEquals(0x00, DumlTransport.routingHeader(0x100, 1, drone = false)[10].toInt() and 0xFF)
        // Everything else is identical — the drone/camera split must not leak further into the header.
        val d = DumlTransport.routingHeader(0x100, 7, drone = true)
        val c = DumlTransport.routingHeader(0x100, 7, drone = false)
        assertEquals(hex(d.copyOfRange(0, 10)), hex(c.copyOfRange(0, 10)))
        assertEquals(7, d[8].toInt() and 0xFF)   // command counter
    }

    // ---- frame scanning ----------------------------------------------------------------------------

    private fun frame(set: Int, cmd: Int, payload: ByteArray, target: Int = 0x0702, id: Int = 0x000A) =
        DjiMessage(target, id, (2 shl 5) or (set shl 8) or (cmd shl 16), payload).encode()

    @Test
    fun `scanFrames finds a frame and verifies both CRCs`() {
        val f = frame(0x00, 0x27, byteArrayOf(1, 2, 3, 4))
        val got = DumlTransport.scanFrames(f)
        assertEquals(1, got.size)
        assertEquals(0x00, got[0].first)
        assertEquals(0x27, got[0].second)
        assertEquals("01020304", hex(got[0].third))

        // A single flipped byte inside the frame must make it disappear, not decode to garbage.
        val corrupt = f.copyOf().also { it[12] = (it[12] + 1).toByte() }
        assertTrue(DumlTransport.scanFrames(corrupt).isEmpty())
    }

    /**
     * A frame with the length encoded across the full **10 bits** of bytes 1–2.
     *
     * Hand-built because [DjiMessage.encode] writes the length as a `u8` and byte 2 as a constant
     * `0x04`, so it cannot express one — fine in practice, since nothing this app *sends* comes close
     * to 242 bytes of payload, but it means long frames only ever arrive, never depart.
     */
    private fun longFrame(set: Int, cmd: Int, payload: ByteArray): ByteArray {
        val len = 13 + payload.size
        val head = byteArrayOf(0x55, (len and 0xFF).toByte(), (((len shr 8) and 0x03) or (1 shl 2)).toByte())
        val body = head + byteArrayOf(dev.konraditurbe.osmosis.duml.DjiCrc.computeCrc8(head).toByte()) +
            byteArrayOf(0x02, 0x07, 0x0A, 0x00) +                                  // target, id
            byteArrayOf(0x40, set.toByte(), cmd.toByte()) + payload                // type (flags|set|cmd)
        val crc = dev.konraditurbe.osmosis.duml.DjiCrc.computeCrc16(body)
        return body + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    @Test
    fun `a frame over 255 bytes still parses`() {
        // Length is 10 bits + a 6-bit version in bytes 1-2, so a long frame's byte 2 is 0x05/0x06/0x07,
        // not 0x04. Matching only 0x04 hid every long frame — which is exactly where a drone manifest
        // lives, and why the whole Mavic manifest was invisible at first.
        val f = longFrame(0x00, 0x27, ByteArray(400) { it.toByte() })
        assertTrue("this fixture must actually exceed the single-byte length", f.size > 255)
        assertEquals("byte 2 must carry the length's high bits", 0x05, f[2].toInt() and 0xFF)
        val got = DumlTransport.scanFrames(f)
        assertEquals(1, got.size)
        assertEquals(0x00 to 0x27, got[0].first to got[0].second)
        assertEquals(400, got[0].third.size)
    }

    @Test
    fun `scanning steps one byte at a time so a tunnelled frame is not skipped`() {
        // A drone wraps some replies inside a 0x51/0x01 tunnel frame. Advancing by the outer frame's
        // length skips the payload with it, so the inner frame must be found too.
        val inner = frame(0x00, 0x27, byteArrayOf(0x4A, 0x01))
        val outer = frame(0x51, 0x01, inner, target = 0xE93B)
        val sets = DumlTransport.scanFrames(outer).map { it.first to it.second }
        assertTrue("the outer tunnel frame", sets.contains(0x51 to 0x01))
        assertTrue("the frame nested inside it", sets.contains(0x00 to 0x27))
    }

    @Test
    fun `frames are found inside surrounding datagram noise`() {
        val f = frame(0x02, 0xDC, byteArrayOf(9, 9))
        val padded = ByteArray(20) { 0x55 } + f + ByteArray(11) { 0x55 }
        assertEquals(1, DumlTransport.scanFrames(padded).count { it.first == 0x02 && it.second == 0xDC })
    }

    // ---- reply matching ----------------------------------------------------------------------------

    @Test
    fun `findReply skips the empty transport ACK that precedes the real reply`() {
        // That ACK is what a naive "first frame with this cmdset" match grabs, which makes a command
        // that actually landed look like a failure.
        val ack = frame(0x00, 0x28, ByteArray(0))
        val real = frame(0x00, 0x28, byteArrayOf(0x00, 0x00))
        assertEquals("0000", hex(DumlTransport.findReply(listOf(ack + real), 0x00, 0x28)!!))
        assertNull(DumlTransport.findReply(listOf(ack), 0x00, 0x28))
        assertNull("a different command must not match", DumlTransport.findReply(listOf(real), 0x00, 0x26))
    }

    @Test
    fun `findRespStatus reads the leading status word`() {
        val ok = frame(0x02, 0xBF, byteArrayOf(0x00, 0x00, 0x77))
        assertEquals(0, DumlTransport.findRespStatus(ok, 0x02, 0xBF))
        val err = frame(0x02, 0xBF, byteArrayOf(0xC8.toByte(), 0x00))
        assertEquals(0xC8, DumlTransport.findRespStatus(err, 0x02, 0xBF))
        assertNull(DumlTransport.findRespStatus(ok, 0x02, 0xC0))
    }

    // ---- helpers -----------------------------------------------------------------------------------

    @Test
    fun `hex ignores whitespace and le32 is little-endian`() {
        assertEquals("4a002110", hex(DumlTransport.hex("4a 00 21 10")))
        assertEquals("df690000", hex(DumlTransport.le32(0x69DF)))
        assertNotNull(DumlTransport.hex(""))
    }
}
