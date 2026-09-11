package dev.konraditurbe.osmosis.net

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.urlPath
import java.io.ByteArrayOutputStream

/**
 * Pulls one full-resolution frame out of a clip that is still **on the camera** and saves it as a JPEG
 * next to the photos (`Pictures/Osmosis`). Nothing else of the clip is downloaded.
 *
 * Same trick as the trimmed download: [MediaExtractor] speaks HTTP and range-requests, so pointed at
 * the full-res `.MP4` it fetches the `moov` and then only the samples it is asked for. We seek to the
 * keyframe before the wanted offset and feed the decoder from there up to the target, so the cost is
 * one GOP's worth of bytes (a few tens of MB on 4K), and the result is the frame the user paused on
 * rather than the nearest keyframe.
 *
 * Decoding runs **in-process on [MediaCodec]**, not via `MediaMetadataRetriever`: the retriever lives in
 * mediaserver, prefers software codecs, and on a weaker device just returned null for a 4:3 4K HEVC
 * clip with nothing in our log to say why. Here every decoder that claims the MIME is tried in
 * `MediaCodecList` order (hardware first), and the track format + the codec that failed are logged.
 *
 * The offset is taken from the preview player, which streams the low-res proxy; both files start at
 * the same instant, so a proxy timestamp addresses the same moment in the full-res clip.
 */
class FrameCapture(
    private val context: Context,
    private val http: HttpClient,
    private val log: (String) -> Unit,
) {
    /**
     * Extract the frame at [offsetMs] of [f] (a full-res video) and save it as
     * `<name without extension>_capture_<offsetMs>.JPG`. Returns the saved item's Uri, or null when the
     * frame couldn't be decoded or written. Blocking — call off the main thread.
     */
    fun capture(f: CameraFile, offsetMs: Long): Uri? {
        val name = captureName(f, offsetMs)
        val started = System.currentTimeMillis()
        val jpeg = decodeFrame(http.url(f.urlPath()), offsetMs) ?: run {
            log("capture FAILED ${f.name} @${offsetMs} ms: no frame decoded")
            return null
        }
        log("capture ${f.name} @${offsetMs} ms → ${jpeg.size / 1000} kB JPEG in ${System.currentTimeMillis() - started} ms")
        val uri = save(jpeg, name)
        if (uri == null) log("capture FAILED $name: MediaStore write failed") else log("capture saved $name")
        return uri
    }

    // ---- decode ---------------------------------------------------------------

    /** JPEG bytes of the frame at [offsetMs], or null. Tries each decoder for the track's MIME in turn. */
    private fun decodeFrame(url: String, offsetMs: Long): ByteArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(url)
            var track = -1
            var fmt: MediaFormat? = null
            for (t in 0 until extractor.trackCount) {
                val tf = extractor.getTrackFormat(t)
                if (tf.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = t; fmt = tf; break }
            }
            if (track < 0 || fmt == null) { log("capture: no video track in $url"); return null }
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            log("capture: track $mime ${fmt.getInteger(MediaFormat.KEY_WIDTH)}x${fmt.getInteger(MediaFormat.KEY_HEIGHT)}" +
                " profile=${fmt.intOrNull(MediaFormat.KEY_PROFILE)} level=${fmt.intOrNull(MediaFormat.KEY_LEVEL)}" +
                " fps=${fmt.intOrNull(MediaFormat.KEY_FRAME_RATE)} rot=${fmt.intOrNull(MediaFormat.KEY_ROTATION)}" +
                " transfer=${fmt.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
            extractor.selectTrack(track)

            val rotation = fmt.intOrNull(MediaFormat.KEY_ROTATION) ?: 0
            val targetUs = offsetMs * 1000
            val fps = fmt.intOrNull(MediaFormat.KEY_FRAME_RATE) ?: 25
            val halfFrameUs = 500_000L / fps.coerceAtLeast(1)

            val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            if (decoders.isEmpty()) { log("capture: no decoder for $mime"); return null }
            for (info in decoders) {
                val started = System.currentTimeMillis()
                extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val img = runCatching { decodeWith(info, fmt, extractor, targetUs, halfFrameUs) }
                    .onFailure { log("capture: ${info.name} threw ${it.javaClass.simpleName} ${it.message}") }
                    .getOrNull()
                if (img != null) {
                    log("capture: ${info.name} decoded frame @${img.ptsUs / 1000} ms" +
                        " (${img.width}x${img.height}) in ${System.currentTimeMillis() - started} ms")
                    return toJpeg(img, rotation)
                }
                log("capture: ${info.name} produced no frame, trying next decoder")
            }
            return null
        } catch (e: Exception) {
            log("capture: can't open $url — ${e.javaClass.simpleName} ${e.message}")
            return null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** A decoded frame as NV21 (what [YuvImage] eats), plus its geometry and timestamp. */
    private class Frame(val nv21: ByteArray, val width: Int, val height: Int, val ptsUs: Long)

    /**
     * Feed [extractor] (already seeked to the keyframe before [targetUs]) through [info] until the first
     * output whose timestamp reaches the target, and return that frame — or null if the decoder gave
     * nothing usable (couldn't configure, opaque output format, EOS/timeout before the target).
     */
    private fun decodeWith(info: MediaCodecInfo, fmt: MediaFormat, extractor: MediaExtractor,
                           targetUs: Long, halfFrameUs: Long): Frame? {
        val codec = MediaCodec.createByCodecName(info.name)
        try {
            // Flexible YUV so getOutputImage can hand us the planes (a surface would be opaque).
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val bufInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var fed = 0
            var lastProgress = System.currentTimeMillis()
            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        val pts = extractor.sampleTime
                        // Stop feeding a little past the target: a reordered (B) frame at the target
                        // may need the sample after it, and EOS flushes whatever is still inside.
                        if (size < 0 || pts > targetUs + FEED_PAST_TARGET_US) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, pts, 0)
                            fed++
                            extractor.advance()
                        }
                        lastProgress = System.currentTimeMillis()
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(bufInfo, 10_000)
                when {
                    outIdx >= 0 -> {
                        lastProgress = System.currentTimeMillis()
                        val eos = bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val hit = bufInfo.size > 0 && bufInfo.presentationTimeUs + halfFrameUs >= targetUs
                        if (hit || (eos && bufInfo.size > 0)) {
                            val image = codec.getOutputImage(outIdx)
                            val frame = image?.let { toNv21(it, bufInfo.presentationTimeUs) }
                            if (image == null) log("capture: ${info.name} output is not a flexible YUV image" +
                                " (format ${codec.outputFormat.intOrNull(MediaFormat.KEY_COLOR_FORMAT)})")
                            image?.close()
                            codec.releaseOutputBuffer(outIdx, false)
                            return frame
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (eos) { log("capture: ${info.name} hit EOS before the target after $fed samples"); return null }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        log("capture: ${info.name} output format ${codec.outputFormat}")
                }
                if (System.currentTimeMillis() - lastProgress > STALL_MS) {
                    log("capture: ${info.name} stalled after $fed samples"); return null
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /**
     * Repack a decoded [Image] (any pixel/row stride, cropped) as NV21: Y plane, then interleaved VU.
     * Handles 8-bit YUV_420_888 and 10-bit P010 — a 10-bit hardware decoder may ignore the flexible
     * 8-bit request and return 16-bit little-endian samples, MSB-aligned, so the top 8 bits are the
     * high byte of each pair.
     */
    private fun toNv21(img: Image, ptsUs: Long): Frame {
        val crop = img.cropRect ?: Rect(0, 0, img.width, img.height)
        val w = crop.width()
        val h = crop.height()
        val out = ByteArray(w * h * 3 / 2)
        val planes = img.planes
        val p010 = img.format == ImageFormat.YCBCR_P010
        val hi = if (p010) 1 else 0     // byte within a sample that carries the top 8 bits
        if (p010) log("capture: 10-bit P010 output, taking the high byte of each sample")
        // Y
        var pos = 0
        run {
            val p = planes[0]; val b = p.buffer; val rs = p.rowStride; val ps = p.pixelStride
            val row = ByteArray(rs)
            for (y in 0 until h) {
                b.position((crop.top + y) * rs + crop.left * ps)
                if (ps == 1) { b.get(out, pos, w); pos += w }
                else { b.get(row, 0, minOf(row.size, b.remaining())); for (x in 0 until w) out[pos++] = row[x * ps + hi] }
            }
        }
        // VU interleaved (NV21 = V first)
        val cw = w / 2; val ch = h / 2
        val u = planes[1]; val v = planes[2]
        val ub = u.buffer; val vb = v.buffer
        val uRow = ByteArray(u.rowStride); val vRow = ByteArray(v.rowStride)
        for (y in 0 until ch) {
            val uOff = (crop.top / 2 + y) * u.rowStride + (crop.left / 2) * u.pixelStride
            val vOff = (crop.top / 2 + y) * v.rowStride + (crop.left / 2) * v.pixelStride
            ub.position(uOff); ub.get(uRow, 0, minOf(uRow.size, ub.remaining()))
            vb.position(vOff); vb.get(vRow, 0, minOf(vRow.size, vb.remaining()))
            for (x in 0 until cw) {
                out[pos++] = vRow[x * v.pixelStride + hi]
                out[pos++] = uRow[x * u.pixelStride + hi]
            }
        }
        return Frame(out, w, h, ptsUs)
    }

    /** NV21 → JPEG; a rotated clip (portrait Nano) is turned upright rather than tagged. */
    private fun toJpeg(f: Frame, rotation: Int): ByteArray {
        val raw = ByteArrayOutputStream().also {
            YuvImage(f.nv21, ImageFormat.NV21, f.width, f.height, null)
                .compressToJpeg(Rect(0, 0, f.width, f.height), JPEG_QUALITY, it)
        }.toByteArray()
        if (rotation % 360 == 0) return raw
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true)
        bmp.recycle()
        return ByteArrayOutputStream().also { rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            .toByteArray().also { rotated.recycle() }
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

    // ---- save -----------------------------------------------------------------

    /** Write [jpeg] into Pictures/Osmosis as a pending item, then publish it. */
    private fun save(jpeg: ByteArray, displayName: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, PICTURES_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { it.write(jpeg) } != null
        }.getOrDefault(false)
        if (!ok) { runCatching { resolver.delete(uri, null, null) }; return null }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    companion object {
        /** Same folder the downloader writes photos to (see MediaDownloader.collectionFor). */
        private const val PICTURES_DIR = "Pictures/Osmosis"
        private const val JPEG_QUALITY = 95
        /** Keep feeding samples this far past the target before EOS, for reordered frames. */
        private const val FEED_PAST_TARGET_US = 500_000L
        /** No input accepted and no output produced for this long = the decoder is wedged. */
        private const val STALL_MS = 30_000L

        /** `DJI_20260329115359_0211_D.MP4` @ 12345 ms → `DJI_20260329115359_0211_D_capture_12345.JPG`. */
        fun captureName(f: CameraFile, offsetMs: Long): String =
            "${f.name.substringBeforeLast('.')}_capture_$offsetMs.JPG"
    }
}
