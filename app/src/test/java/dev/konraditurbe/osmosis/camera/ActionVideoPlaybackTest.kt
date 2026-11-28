package dev.konraditurbe.osmosis.camera

import dev.konraditurbe.osmosis.core.AlbumVideo
import dev.konraditurbe.osmosis.duml.DjiMessage
import dev.konraditurbe.osmosis.net.DumlTransport
import org.junit.Assert.*
import org.junit.Test

class ActionVideoPlaybackTest {
    private val index = 0x640088
    private val session = 321

    private fun envelope(type: Int, seq: Int, payload: ByteArray): ByteArray =
        DumlTransport.udpHeader(type, payload.size + 12, session, seq) + ByteArray(12) + payload

    private fun message(cmd: Int, body: ByteArray): ByteArray {
        val msg = DjiMessage(0x0201, 12, (cmd shl 16) or 0x02c0, body)
        return envelope(5, 0, msg.encode())
    }

    private fun state(value: Int, position: Int, file: Int = index): ByteArray {
        val body = ByteArray(41)
        body[0] = value.toByte()
        DumlTransport.le32(file).copyInto(body, 4)
        DumlTransport.le32(20000).copyInto(body, 9)
        DumlTransport.le32(position).copyInto(body, 13)
        return message(0x82, body)
    }

    private fun record(seq: Int, value: Byte, kind: Byte = 0x11): ByteArray {
        val header = byteArrayOf(0, 0, 1, -1, 1, 0, -1, 0, 0x4c, kind, 0, 0)
        header[10] = header.fold(0) { sum, b -> sum xor b.toInt() }.toByte()
        return envelope(2, seq, header + value)
    }

    private open class Listener : AlbumVideo.Listener {
        override fun video(accessUnit: ByteArray) {}
        override fun audio(adts: ByteArray) {}
        override fun discontinuity(seeking: Boolean) {}
        override fun position(playing: Boolean, positionMs: Int, durationMs: Int) {}
        override fun ended(error: String?) {}
    }

    @Test fun playbackCorrelatesTheSelectedRecordingAndSeekPosition() {
        val pictures = mutableListOf<Byte>()
        val audio = mutableListOf<Byte>()
        val positions = mutableListOf<Int>()
        val sent = mutableListOf<Pair<Int, List<Byte>>>()
        val acks = mutableListOf<Int?>()
        var resets = 0
        lateinit var playback: ActionVideoPlayback
        playback = ActionVideoPlayback(index.toLong(), object : Listener() {
            override fun video(accessUnit: ByteArray) { pictures.add(accessUnit.single()) }
            override fun audio(adts: ByteArray) { audio.add(adts.single()) }
            override fun discontinuity(seeking: Boolean) { assertTrue(seeking); resets++ }
            override fun position(playing: Boolean, positionMs: Int, durationMs: Int) {
                assertEquals(20000, durationMs)
                positions.add(positionMs)
                when (positionMs) {
                    100 -> { assertTrue(playing); playback.pause() }
                    125 -> { assertFalse(playing); playback.seek(7000) }
                    7000 -> { assertFalse(playing); playback.resume() }
                }
            }
        })
        val batches = ArrayDeque(listOf(
            message(0x7b, byteArrayOf(0)) + record(0, 9) + state(2, 4000, index - 1),
            state(8, -1) + record(8, 1) + record(16, 9, 0x24),
            state(2, 100),
            state(3, 125) + record(24, 9, 0x24),
            record(32, 9) + message(0x7a, byteArrayOf(0)) + state(3, 126) + record(40, 2),
            state(3, 7000) + record(48, 9, 0x24),
            state(2, 7040) + record(56, 3, 0x24) + state(7, 20000),
            message(0x7a, byteArrayOf(0))
        ))
        playback.play(session, { true }, { cmd, body -> sent.add(cmd to body.toList()) },
            { listOf(batches.removeFirst()) }, acks::add)
        fun control(op: Int, value: Int = 0) = 0x7a to (byteArrayOf(op.toByte()) + DumlTransport.le32(value)).toList()
        assertEquals(listOf(0x7b to (DumlTransport.le32(index) + DumlTransport.le32(0)).toList(),
            control(5), control(4, 7000), control(1), control(0)), sent)
        assertEquals(listOf(100, 125, 7000, 7040), positions)
        assertEquals(listOf<Byte>(1, 2), pictures)
        assertEquals(1, resets)
        assertEquals(listOf<Byte>(3), audio)
        assertEquals(56, acks.last())
        assertTrue(batches.isEmpty())
    }

    @Test fun rejectedSelectionStillStopsAndReleasesTheCamera() {
        val playback = ActionVideoPlayback(index.toLong(), Listener())
        val replies = ArrayDeque(listOf(message(0x7b, byteArrayOf(0xe4.toByte())), state(7, 0)))
        val sent = mutableListOf<Int>()
        val error = runCatching {
            playback.play(session, { true }, { cmd, _ -> sent.add(cmd) },
                { listOf(replies.removeFirst()) }, {})
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("e4"))
        assertEquals(listOf(0x7b, 0x7a), sent)
        assertTrue(replies.isEmpty())
    }
}
