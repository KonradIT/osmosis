package dev.konraditurbe.osmosis.ui

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import androidx.media3.container.NalUnitUtil
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Decodes AC101 HPI video records off the UDP owner. The worker owns the codec and its Surface. */
internal class ActionVideoDecoder(
    private val surface: Surface,
    private val sizeChanged: (Int, Int) -> Unit,
    private val frameRendered: (Long) -> Unit,
    private val failed: (String) -> Unit,
) : AutoCloseable {
    private data class Record(val data: ByteArray, val generation: Int, val continuity: Int, val receivedUs: Long)
    private val queue = ArrayBlockingQueue<Record>(12)
    @Volatile private var generation = 0
    private var continuity = 0
    @Volatile private var offered = 0
    @Volatile private var dropped = 0
    @Volatile private var submitted = 0
    @Volatile private var rendered = 0

    fun statistics() = "records=$offered, dropped=$dropped, submitted=$submitted, rendered=$rendered"
    @Volatile private var closed = false
    private var codec: MediaCodec? = null
    private var sps = ByteArray(0)
    private var pps = ByteArray(0)
    private val info = MediaCodec.BufferInfo()
    private val worker = Thread(::decode, "action-video-decoder").apply { start() }

    fun offer(bytes: ByteArray) {
        if (closed) return
        offered++
        if (!queue.offer(Record(bytes, generation, continuity, System.nanoTime() / 1000))) {
            // Let the worker finish its buffered pictures, even if codec startup takes a while.
            // The first accepted record after the gap will require a new keyframe.
            continuity++
            dropped++
        }
    }

    fun reset() {
        generation++
        queue.clear()
    }

    override fun close() {
        closed = true
        worker.interrupt()
    }

    private fun releaseCodec() {
        codec?.let {
            runCatching { it.stop() }
            it.release()
        }
        codec = null
    }

    private fun decode() {
        var active = generation
        var continuous = 0
        try {
            while (!closed) {
                val record = queue.poll(15, TimeUnit.MILLISECONDS)
                if (active != generation) {
                    releaseCodec()
                    active = generation
                }
                if (record != null && record.generation == active) {
                    if (record.continuity != continuous) {
                        releaseCodec()
                        continuous = record.continuity
                    }
                    accept(record)
                }
                drain(active)
            }
        } catch (_: InterruptedException) {
            // Viewer closed.
        } catch (e: Exception) {
            if (!closed) failed(e.message ?: e.javaClass.simpleName)
        } finally {
            try { releaseCodec() } finally { surface.release() }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun accept(record: Record) {
        val data = record.data
        var keyframe = false
        var hasSlices = false
        var configChanged = false
        forEachNal(data) { type, start, end ->
            when (type) {
                7, 8 -> {
                    val param = byteArrayOf(0, 0, 0, 1) + data.copyOfRange(start, end)
                    if (type == 7 && !sps.contentEquals(param)) { sps = param; configChanged = true }
                    if (type == 8 && !pps.contentEquals(param)) { pps = param; configChanged = true }
                }
                5 -> { keyframe = true; hasSlices = true }
                1 -> hasSlices = true
            }
        }
        if (configChanged) releaseCodec()
        if (!hasSlices) return
        if (codec == null) {
            if (!keyframe || sps.isEmpty() || pps.isEmpty()) return
            val format = NalUnitUtil.parseSpsNalUnit(sps, 4, sps.size)
            val media = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, format.width, format.height)
            media.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            media.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            media.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec!!.configure(media, surface, null, 0)
            codec!!.start()
            sizeChanged(format.width, format.height)
        }
        val decoder = codec ?: return
        while (!closed && generation == record.generation) {
            val slot = decoder.dequeueInputBuffer(5000)
            if (slot >= 0) {
                val input = checkNotNull(decoder.getInputBuffer(slot))
                input.clear()
                input.order(java.nio.ByteOrder.BIG_ENDIAN)
                // HPI video records can include separate parameter sets, SEI and a trailing delimiter.
                // Feed the picture's slices together, including every slice of a multi-slice picture.
                forEachNal(data) { type, start, end ->
                    if (type == 1 || type == 5) {
                        input.putInt(1)
                        input.put(data, start, end - start)
                    }
                }
                decoder.queueInputBuffer(slot, 0, input.position(), record.receivedUs, 0)
                submitted++
                return
            }
            drain(record.generation)
        }
    }

    private fun drain(active: Int) {
        val decoder = codec ?: return
        while (!closed) {
            val slot = decoder.dequeueOutputBuffer(info, 0)
            if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (slot < 0) return
            val render = active == generation && info.size > 0
            decoder.releaseOutputBuffer(slot, render)
            if (render) {
                rendered++
                frameRendered(info.presentationTimeUs)
            }
        }
    }

    private inline fun forEachNal(data: ByteArray, visit: (Int, Int, Int) -> Unit) {
        var start = -1
        var zeroes = 0
        for (i in data.indices) {
            val b = data[i].toInt()
            if (b == 1 && zeroes >= 2) {
                if (start >= 0 && start < i - zeroes) visit(data[start].toInt() and 31, start, i - zeroes)
                start = i + 1
            }
            zeroes = if (b == 0) zeroes + 1 else 0
        }
        if (start in data.indices) visit(data[start].toInt() and 31, start, data.size)
    }
}
