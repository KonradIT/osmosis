package com.chernowii.osmosis.net

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import com.chernowii.osmosis.core.CameraFile
import com.chernowii.osmosis.core.TrimRange
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Downloads camera files into shared media collections (Movies/Pictures/Download → /Osmosis).
 *
 * Whole files stream to a pending MediaStore item with progress + resume (interrupted items reopen,
 * seek to their current length, and Range-request the remainder). Trimmed jobs instead re-mux a time
 * window off the full-res clip with MediaExtractor → MediaMuxer: a keyframe-aligned stream copy (no
 * re-encode) that, because MediaExtractor range-fetches, pulls only the window's bytes off the camera.
 */
class MediaDownloader(
    private val context: Context,
    private val http: HttpClient,
    private val log: (String) -> Unit,
) {
    /** One queued download: a whole file, or a trimmed time window of it. */
    data class Job(val file: CameraFile, val trim: TrimRange? = null)

    interface Progress {
        fun onStart(totalFiles: Int, totalBytes: Long)
        fun onFileStart(index: Int, name: String, fileBytes: Long)
        fun onTick(fileDone: Long, overallDone: Long)
        fun onComplete(saved: Int, skipped: Int, failed: Int)
    }

    private enum class Result { SAVED, SKIPPED, FAILED }

    private val prefs get() = context.getSharedPreferences("osmosis_dl", Context.MODE_PRIVATE)

    fun run(jobs: List<Job>, p: Progress) {
        val sizes = jobs.map { estimateBytes(it) }
        p.onStart(jobs.size, sizes.sum())
        var overallBase = 0L
        var saved = 0; var skipped = 0; var failed = 0
        for ((i, job) in jobs.withIndex()) {
            val sz = sizes[i]
            p.onFileStart(i, displayName(job), sz)
            val tick = { done: Long -> p.onTick(done, overallBase + done) }
            val r = if (job.trim != null) downloadTrimmed(job.file, job.trim, tick)
            else downloadOne(job.file, sz, tick)
            when (r) {
                Result.SAVED -> saved++
                Result.SKIPPED -> skipped++
                Result.FAILED -> failed++
            }
            overallBase += sz
        }
        p.onComplete(saved, skipped, failed)
    }

    /** Full remote size for whole jobs; a duration-proportional estimate for trims (for the bars). */
    private fun estimateBytes(job: Job): Long {
        val full = http.head(job.file.urlPath()).coerceAtLeast(0L)
        val trim = job.trim ?: return full
        if (full <= 0) return 0L
        val dur = VideoMeta.durationMs(http, job.file)
        return if (dur > 0) (full * trim.durationMs / dur).coerceAtLeast(1L) else full
    }

    private fun displayName(job: Job): String =
        if (job.trim == null) job.file.name else trimmedName(job.file, job.trim)

    private fun trimmedName(f: CameraFile, trim: TrimRange): String =
        "${f.name.substringBeforeLast('.')}_${trim.startMs / 1000}-${trim.endMs / 1000}s.${f.ext}"

    // ---- whole-file download (resumable) ------------------------------------

    private fun downloadOne(f: CameraFile, remote: Long, tick: (Long) -> Unit): Result {
        val resolver = context.contentResolver
        val key = "dl_" + f.path

        var u = prefs.getString(key, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val tracked = u
        if (tracked != null && runCatching { resolver.openFileDescriptor(tracked, "rw")?.close() }.isFailure) u = null
        if (u == null) {
            if (isAlreadyDownloaded(f, remote)) { log("skip ${f.name} (already saved)"); return Result.SKIPPED }
            u = createPending(f, f.name) ?: run { log("insert failed: ${f.name}"); return Result.FAILED }
            prefs.edit().putString(key, u.toString()).apply()
        }
        val uri: Uri = u!!

        val pfd = runCatching { resolver.openFileDescriptor(uri, "rw") }.getOrNull()
            ?: run { log("open failed: ${f.name}"); prefs.edit().remove(key).apply(); return Result.FAILED }
        val startOffset = pfd.statSize.coerceAtLeast(0L)

        if (remote > 0 && startOffset >= remote) {
            pfd.close(); markComplete(uri); prefs.edit().remove(key).apply(); return Result.SAVED
        }
        if (startOffset > 0) log("resuming ${f.name} at ${startOffset / 1_000_000} MB")

        val fos = FileOutputStream(pfd.fileDescriptor)
        fos.channel.position(startOffset)
        val ok = http.download(f.urlPath(), fos, startOffset) { total -> tick(total) }
        runCatching { fos.flush(); fos.close() }
        pfd.close()

        return if (ok) {
            markComplete(uri); prefs.edit().remove(key).apply(); Result.SAVED
        } else {
            log("paused ${f.name} (will resume on next Download)"); Result.FAILED
        }
    }

    // ---- trimmed download (MediaExtractor → MediaMuxer stream copy) ----------

    private fun downloadTrimmed(f: CameraFile, trim: TrimRange, tick: (Long) -> Unit): Result {
        if (!trim.isValid) { log("bad trim range: ${f.name}"); return Result.FAILED }
        val resolver = context.contentResolver
        val name = trimmedName(f, trim)
        val uri = createPending(f, name) ?: run { log("insert failed: $name"); return Result.FAILED }
        val pfd = runCatching { resolver.openFileDescriptor(uri, "rw") }.getOrNull()
            ?: run { log("open failed: $name"); runCatching { resolver.delete(uri, null, null) }; return Result.FAILED }

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(http.url(f.urlPath()))
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = HashMap<Int, Int>()
            var bufCap = 4 shl 20 // 4 MB floor — a 4K keyframe can be several MB
            for (t in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(t)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                extractor.selectTrack(t)
                trackMap[t] = muxer.addTrack(fmt)
                if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    bufCap = maxOf(bufCap, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                if (mime.startsWith("video/") && fmt.containsKey(MediaFormat.KEY_ROTATION))
                    muxer.setOrientationHint(fmt.getInteger(MediaFormat.KEY_ROTATION))
            }
            if (trackMap.isEmpty()) { log("no A/V tracks: $name"); resolver.delete(uri, null, null); return Result.FAILED }

            val endUs = trim.endMs * 1000
            muxer.start()
            extractor.seekTo(trim.startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buf = ByteBuffer.allocate(bufCap)
            val info = MediaCodec.BufferInfo()
            var firstPts = -1L
            var written = 0L
            while (true) {
                val tIdx = extractor.sampleTrackIndex
                if (tIdx < 0) break
                val pts = extractor.sampleTime
                if (pts > endUs) break
                val outTrack = trackMap[tIdx]
                if (outTrack == null) { extractor.advance(); continue }
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                if (firstPts < 0) firstPts = pts
                info.offset = 0
                info.size = size
                info.presentationTimeUs = (pts - firstPts).coerceAtLeast(0)
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(outTrack, buf, info)
                written += size
                tick(written)
                extractor.advance()
            }
            muxer.stop()
            markComplete(uri)
            log("trimmed ${f.name} → $name (${written / 1_000_000} MB of ${trim.durationMs} ms)")
            return Result.SAVED
        } catch (e: Exception) {
            log("trim FAILED ${f.name}: ${e.javaClass.simpleName} ${e.message}")
            runCatching { resolver.delete(uri, null, null) }
            return Result.FAILED
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            runCatching { pfd.close() }
        }
    }

    // ---- MediaStore plumbing ------------------------------------------------

    private fun collectionFor(f: CameraFile): Triple<Uri, String, String> = when {
        f.isVideo -> Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/Osmosis", "video/mp4")
        f.isImage -> Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/Osmosis", "image/jpeg")
        else -> Triple(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Download/Osmosis", "application/octet-stream")
    }

    private fun createPending(f: CameraFile, displayName: String): Uri? {
        val (collection, relPath, mime) = collectionFor(f)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(collection, values)
    }

    private fun markComplete(uri: Uri) {
        context.contentResolver.update(
            uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
        )
    }

    private fun isAlreadyDownloaded(f: CameraFile, remote: Long): Boolean {
        val (collection, _, _) = collectionFor(f)
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
        val sel = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0"
        return runCatching {
            context.contentResolver.query(collection, proj, sel, arrayOf(f.name), null)?.use { c ->
                while (c.moveToNext()) {
                    val sz = c.getLong(1)
                    if (remote <= 0 || sz == remote) return true
                }
            }
            false
        }.getOrDefault(false)
    }
}
