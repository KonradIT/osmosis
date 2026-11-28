package dev.konraditurbe.osmosis.dcf

import dev.konraditurbe.osmosis.net.DumlTransport
import org.junit.Assert.*
import org.junit.Test

class ActionVideoStreamTest {
    private fun hpi(kind: Int, data: ByteArray): ByteArray {
        val h = byteArrayOf(0, 0, 1, -1, data.size.toByte(), (data.size shr 8).toByte(),
            -1, (data.size shr 16).toByte(), 0x4c, kind.toByte(), 0, 0)
        h[10] = h.fold(0) { x, b -> x xor b.toInt() }.toByte()
        return h + data
    }

    private fun packet(sequence: Int, payload: ByteArray, session: Int = 17): ByteArray =
        DumlTransport.udpHeader(2, payload.size + 12, session, sequence) + ByteArray(12) + payload

    @Test fun splitHeadersRetransmissionAndWrapProduceOnePicture() {
        val picture = ByteArray(70001) { (it and 255).toByte() }
        val records = hpi(0x24, byteArrayOf(1, 2, 3)) + hpi(0x11, picture)
        for (cut in 1..12) {
            val out = mutableListOf<ByteArray>()
            val audio = mutableListOf<ByteArray>()
            val stream = ActionVideoStream(17, out::add, audio::add) { fail("unexpected discontinuity") }
            val pieces = listOf(records.copyOfRange(0, cut), records.copyOfRange(cut, 17),
                records.copyOfRange(17, 20000), records.copyOfRange(20000, 40000),
                records.copyOfRange(40000, records.size))
            fun feed(i: Int, flags: Int = 0) {
                val p = packet((65520 + 8 * i + flags) and 65535, pieces[i])
                stream.accept(p, 0, p.size, 1000)
            }
            feed(0); feed(2)
            assertEquals(65520, stream.acknowledgedSequence)
            feed(1, 2); feed(2); feed(3); feed(4)
            assertArrayEquals(picture, out.single())
            assertArrayEquals(byteArrayOf(1, 2, 3), audio.single())
            assertEquals(16, stream.acknowledgedSequence)
        }
    }

    @Test fun lossAndBadHeadersCannotSplicePictures() {
        val out = mutableListOf<ByteArray>()
        var resets = 0
        val stream = ActionVideoStream(17, out::add, { fail("unexpected audio") }) { resets++ }
        val good = hpi(0x11, byteArrayOf(50, 51))
        val corrupt = good.clone().also { it[10] = 0 }
        fun feed(p: ByteArray) = stream.accept(p, 0, p.size, 1000)
        feed(packet(48, good.copyOf(13)))
        feed(packet(64, byteArrayOf(99) + corrupt + good))
        feed(packet(56, byteArrayOf(100), session = 18))
        stream.expireGap(1260)
        assertEquals(1, resets)
        assertArrayEquals(byteArrayOf(50, 51), out.single())
    }
}
