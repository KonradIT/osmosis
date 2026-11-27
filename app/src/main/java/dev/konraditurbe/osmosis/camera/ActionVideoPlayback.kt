package dev.konraditurbe.osmosis.camera

import dev.konraditurbe.osmosis.core.AlbumVideo
import dev.konraditurbe.osmosis.dcf.ActionVideoStream
import dev.konraditurbe.osmosis.net.DumlTransport
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** One indexed recording. The UI posts intents and the session's socket owner executes them. */
internal class ActionVideoPlayback(val index: Long, private val sink: AlbumVideo.Listener) : AlbumVideo {
    @Volatile var closed = false
        private set
    private val seekTo = AtomicReference<Int?>(null)
    @Volatile private var wantPlaying = true

    override fun pause() { wantPlaying = false }
    override fun resume() { wantPlaying = true }
    override fun seek(positionMs: Int) { seekTo.set(positionMs.coerceAtLeast(0)) }
    override fun close() { closed = true }

    fun finish(error: String?) = sink.ended(error)

    fun play(
        session: Int, connected: () -> Boolean, send: (Int, ByteArray) -> Unit,
        receive: () -> List<ByteArray>, ack: (Int?) -> Unit,
    ) {
        fun now() = System.nanoTime() / 1_000_000
        var matched = false
        var playing = true
        var seekTarget: Int? = null
        var pendingControl: Int? = null
        var commandAt = now()
        var lastStatus = commandAt
        var lastPicture = commandAt
        var lastPacket = commandAt
        var collecting = false
        val preroll = ArrayDeque<ByteArray>()
        var prerollBytes = 0
        fun discardPreroll() {
            preroll.clear()
            prerollBytes = 0
        }
        val stream = ActionVideoStream(session, { picture ->
            if (collecting) {
                lastPicture = now()
                if (matched && seekTarget == null) sink.video(picture)
                else if (preroll.size < 48 && prerollBytes + picture.size <= 4 * 1024 * 1024) {
                    preroll.addLast(picture)
                    prerollBytes += picture.size
                } else {
                    // Wait for the next intact GOP if the state push arrives unusually late.
                    discardPreroll()
                    sink.discontinuity()
                }
            }
        }, { audio ->
            if (collecting && matched && playing && wantPlaying && seekTarget == null) sink.audio(audio)
        }, {
            discardPreroll()
            sink.discontinuity()
        })
        fun control(op: Int, arg: Int = 0) = send(0x7a, byteArrayOf(op.toByte()) + DumlTransport.le32(arg))
        send(0x7b, DumlTransport.le32(index.toInt()) + DumlTransport.le32(0))
        try {
            var ended = false
            while (!closed && connected() && !ended) {
                if (matched && pendingControl == null) {
                    val seek = seekTo.getAndSet(null)
                    val op = when {
                        seek != null -> 4
                        playing != wantPlaying -> if (wantPlaying) 1 else 5
                        else -> null
                    }
                    if (op != null) {
                        if (op == 4) {
                            seekTarget = seek
                            collecting = false
                            discardPreroll()
                            stream.discardPartialRecord()
                            sink.discontinuity(seeking = true)
                        }
                        pendingControl = op
                        commandAt = now()
                        control(op, seek ?: 0)
                    }
                }
                val packets = receive()
                val time = now()
                for (packet in packets) DumlTransport.forEachEnvelope(packet) { at, length ->
                    if (DumlTransport.u16(packet, at + 2) != session) return@forEachEnvelope
                    lastPacket = time
                    when (packet[at + 6].toInt()) {
                        2 -> stream.accept(packet, at, length, time)
                        5 -> for ((set, cmd, body) in DumlTransport.scanFrames(packet.copyOfRange(at + 8, at + length))) {
                            if (set != 2) continue
                            if ((cmd == 0x7b || cmd == 0x7a) && body.isNotEmpty()) {
                                check(body[0].toInt() == 0) {
                                    "camera rejected 02/%02x (status %02x)".format(cmd, body[0])
                                }
                                if (cmd == 0x7b || pendingControl == 4) collecting = true
                            }
                            if (cmd != 0x82 || body.size < 17) continue
                            if (u32(body, 4) != index) {
                                if (!matched) {
                                    collecting = false
                                    discardPreroll()
                                    stream.discardPartialRecord()
                                }
                                continue
                            }
                            lastStatus = time
                            val state = body[0].toInt()
                            val position = u32(body, 13)
                            if (position == 0xffff_ffffL || state == 8) {
                                if (!matched) collecting = true
                                continue
                            }
                            if (state == 7) {
                                if (matched && seekTarget == null) ended = true
                                continue
                            }
                            if (state != 2 && state != 3) continue
                            matched = true
                            val wasPlaying = playing
                            playing = state == 2
                            if (!wasPlaying && playing) lastPicture = time
                            val target = seekTarget
                            if (target != null && abs(position - target) > 1500) continue
                            if (target != null) lastPicture = time
                            seekTarget = null
                            collecting = true
                            while (preroll.isNotEmpty()) sink.video(preroll.removeFirst())
                            prerollBytes = 0
                            if (pendingControl == 4 || (pendingControl == 1 && playing) ||
                                (pendingControl == 5 && !playing)) pendingControl = null
                            sink.position(playing, position.toIntBounded(), u32(body, 9).toIntBounded())
                        }
                    }
                }
                stream.expireGap(time)
                ack(stream.acknowledgedSequence)
                if (pendingControl != null && time - commandAt > 5000) error("playback control timed out")
                if ((playing && time - lastStatus > 10000) || time - lastPacket > 10000 ||
                    (!matched && time - commandAt > 10000))
                    error("camera playback status timed out")
                if (matched && playing && time - lastPicture > 10000) error("camera video stream timed out")
            }
            if (!closed && !ended && !connected()) error("camera disconnected")
        } finally {
            control(0)
            // The camera can still have video and state queued when the viewer changes clips.
            // Give it a chance to acknowledge Stop before the owner restores the mode.
            val until = now() + 300
            while (connected() && now() < until) {
                val packets = receive()
                var stopped = false
                for (packet in packets) DumlTransport.forEachEnvelope(packet) { at, length ->
                    if (packet[at + 6].toInt() == 5 && DumlTransport.u16(packet, at + 2) == session) {
                        val frames = DumlTransport.scanFrames(packet.copyOfRange(at + 8, at + length))
                        stopped = stopped || frames.any { (set, cmd, body) ->
                            set == 2 && ((cmd == 0x7a && body.firstOrNull() == 0.toByte()) ||
                                (cmd == 0x82 && body.size >= 8 && body[0].toInt() == 7 && u32(body, 4) == index))
                        }
                    }
                }
                ack(stream.acknowledgedSequence)
                if (stopped) break
            }
        }
    }

    private fun u32(b: ByteArray, at: Int) =
        DumlTransport.u16(b, at).toLong() or (DumlTransport.u16(b, at + 2).toLong() shl 16)
    private fun Long.toIntBounded() = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
