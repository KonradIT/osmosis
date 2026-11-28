package dev.konraditurbe.osmosis.ui

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import dev.konraditurbe.osmosis.core.FileLog
import dev.konraditurbe.osmosis.dcf.ActionAdts
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Plays the camera-paced AAC stream. All codec and AudioTrack calls run off the camera socket owner. */
internal class ActionPreviewAudio(manager: AudioManager, focusLost: () -> Unit) : AutoCloseable {
    private data class Frame(val data: ByteArray, val receivedUs: Long, val generation: Int)
    private data class VideoClock(val sourceUs: Long, val renderUs: Long)
    // Up to 5.46 s of compressed AAC (256 frames of 1,024 samples) covers a slow first IDR.
    private val frames = ArrayBlockingQueue<Frame>(256)
    @Volatile private var clock: VideoClock? = null
    @Volatile private var generation = 0
    @Volatile private var playing = true
    @Volatile private var closed = false

    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
    private val audioManager = manager
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener { change ->
            if (!closed && playing && change < 0) {
                setPlaying(false)
                focusLost()
            }
        }.build()
    private var hasFocus = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private val info = MediaCodec.BufferInfo()
    private val worker = Thread(::run, "action-preview-audio").apply { start() }

    private fun nowUs() = System.nanoTime() / 1000

    fun videoRendered(sourceUs: Long) { clock = VideoClock(sourceUs, nowUs()) }

    fun offer(data: ByteArray) {
        if (closed || !playing || !ActionAdts.isFrame(data)) return
        val frame = Frame(data, nowUs(), generation)
        if (!frames.offer(frame)) {
            frames.poll()
            frames.offer(frame)
        }
    }

    @Synchronized fun reset() {
        clock = null
        generation++
        frames.clear()
    }

    @Synchronized fun setPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        if (!value) reset()
    }

    override fun close() {
        closed = true
        worker.interrupt()
    }

    private fun open(): Boolean {
        hasFocus = audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) return false
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, ActionAdts.SAMPLE_RATE, ActionAdts.CHANNELS)
        // MPEG-4 AudioSpecificConfig for AAC-LC, sample-rate index 3, stereo.
        format.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x11, 0x90.toByte())))
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec = decoder
        decoder.configure(format, null, null, 0)
        decoder.start()
        return true
    }

    private fun release() {
        track?.let { output ->
            runCatching { output.pause(); output.flush() }
            runCatching { output.release() }
        }
        track = null
        codec?.let { decoder ->
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
        codec = null
        if (hasFocus) runCatching { audioManager.abandonAudioFocusRequest(focus) }
        hasFocus = false
    }

    private fun active(epoch: Int) = !closed && playing && generation == epoch

    private fun run() {
        var epoch = generation
        var disabled = false
        try {
            while (!closed) {
                if (epoch != generation) {
                    if (playing && track != null && !disabled) {
                        // Seeking invalidates queued samples. Keep a running decoder warm so
                        // the first audio frame from the new position can play promptly.
                        try {
                            track?.let { it.pause(); it.flush(); it.play() }
                            codec?.flush()
                        } catch (_: IllegalStateException) { release() }
                    } else release()
                    epoch = generation
                    disabled = false
                }
                val frame = frames.poll(8, TimeUnit.MILLISECONDS)
                if (disabled || !active(epoch)) continue
                try {
                    if (frame != null && frame.generation == epoch) {
                        if (codec == null && !open()) { disabled = true; continue }
                        if (inTime(frame)) submit(frame)
                    }
                    drain(epoch)
                } catch (e: RuntimeException) {
                    // A sound-device or AAC failure must leave the video viewer usable.
                    val message = "Action 1 preview audio: ${e.message}"
                    Log.w("Osmosis", message, e)
                    FileLog.write(message)
                    release()
                    disabled = true
                }
            }
        } catch (_: InterruptedException) {
            // The view was detached.
        } finally { release() }
    }

    /** HPI carries no PTS. The firmware paces audio against video before sending both on one channel. */
    private fun inTime(frame: Frame): Boolean {
        while (active(frame.generation)) {
            val video = clock
            val now = nowUs()
            if (video == null || now - video.renderUs > 500_000) {
                Thread.sleep(8)
                continue
            }
            val untilUs = frame.receivedUs - video.sourceUs - (now - video.renderUs)
            if (untilUs < -200_000) return false
            if (untilUs <= 40_000) return true // Give the output device a small buffer.
            Thread.sleep(4)
        }
        return false
    }

    private fun submit(frame: Frame) {
        val decoder = codec ?: return
        while (active(frame.generation)) {
            val index = decoder.dequeueInputBuffer(2000)
            if (index >= 0) {
                val size = frame.data.size - ActionAdts.HEADER_BYTES
                val buffer = checkNotNull(decoder.getInputBuffer(index))
                buffer.clear()
                buffer.put(frame.data, ActionAdts.HEADER_BYTES, size)
                decoder.queueInputBuffer(index, 0, size, frame.receivedUs, 0)
                return
            }
            drain(frame.generation)
        }
    }

    private fun drain(epoch: Int) {
        val decoder = codec ?: return
        while (active(epoch)) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = decoder.outputFormat
                check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == ActionAdts.SAMPLE_RATE &&
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == ActionAdts.CHANNELS &&
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_16BIT)
                continue
            }
            if (index < 0) return
            try {
                if (info.size == 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) continue
                val pcm = checkNotNull(decoder.getOutputBuffer(index))
                pcm.position(info.offset)
                pcm.limit(info.offset + info.size)
                val output = track ?: createTrack().also {
                    track = it
                    it.play()
                    FileLog.write("Action 1 preview audio started: 48000 Hz stereo AAC")
                }
                while (pcm.hasRemaining() && active(epoch)) {
                    val n = output.write(pcm, pcm.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                    check(n >= 0) { "AudioTrack write failed ($n)" }
                    if (n == 0) {
                        if (nowUs() - (clock?.renderUs ?: 0) > 500_000) break
                        Thread.sleep(4)
                    }
                }
            } finally { decoder.releaseOutputBuffer(index, false) }
        }
    }

    private fun createTrack(): AudioTrack {
        val channels = AudioFormat.CHANNEL_OUT_STEREO
        val sampleRate = ActionAdts.SAMPLE_RATE
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val format = AudioFormat.Builder().setEncoding(encoding).setSampleRate(sampleRate).setChannelMask(channels).build()
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, channels, encoding)
        check(minBytes > 0) { "AudioTrack has no stereo output at $sampleRate Hz" }
        return AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(minBytes)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
    }
}
