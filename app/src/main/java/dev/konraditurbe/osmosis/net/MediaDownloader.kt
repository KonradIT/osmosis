package dev.konraditurbe.osmosis.net

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.TrimRange
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
        /** One job finished. [done] is true when it's now on the device (saved OR already-present/skipped) —
         *  i.e. safe to drop from the queue; false only when it failed/paused and should be retried. */
        fun onFileDone(index: Int, done: Boolean)
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
            p.onFileDone(i, r != Result.FAILED)
            overallBase += sz
        }
        p.onComplete(saved, skipped, failed)
    }

    /** Full remote size for whole jobs; a duration-proportional estimate for trims (for the bars). Both
     *  size and duration come from the DUML manifest; HTTP HEAD only guards a record we couldn't size. */
    private fun estimateBytes(job: Job): Long {
        val full = (if (job.file.sizeBytes > 0) job.file.sizeBytes else http.head(job.file.urlPath())).coerceAtLeast(0L)
        val trim = job.trim ?: return full
        if (full <= 0) return 0L
        val durMs = job.file.durationSec * 1000L
        return if (durMs > 0) (full * trim.durationMs / durMs).coerceAtLeast(1L) else full
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
            if (isAlreadyDownloaded(f)) { log("skip ${f.name} (already saved)"); return Result.SKIPPED }
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

    companion object {
        /**
         * True if [file] is already fully saved **in its Osmosis folder**. Matched by display name,
         * relative path and **completed** state (`IS_PENDING=0`) — NOT by exact byte size. A completed
         * row is only ever written after a full transfer, so name + folder is sufficient proof. (An
         * exact-size match false-negatived files whose manifest size disagreed slightly with the stored
         * bytes, re-saving them as duplicates every run.)
         *
         * The relative-path clause matters: this used to match on name alone, across the whole
         * MediaStore, on the assumption that a capture name is globally unique. A camera name
         * (`DJI_20260329115359_0211_D.MP4`) very nearly is — but a **drone**'s DCF name is just
         * `DJI_0554.JPG`, one of the most common filenames there is, so any unrelated DJI photo already
         * on the device made us declare the file downloaded and gray out the button for something never
         * saved. Scoping the query to the folder we write to costs nothing and can't be wrong.
         */
        fun isDownloaded(context: Context, file: CameraFile): Boolean = downloadedUri(context, file) != null

        /**
         * The saved copy's `content://` Uri, or null if it isn't in the gallery yet.
         *
         * The match [isDownloaded] documents — completed row, matched on name **and** our own relative
         * path — but keeping the row id instead of discarding it, so the file can be handed to another
         * app (share / edit). A MediaStore Uri is directly grantable, which a raw path would not be.
         */
        fun downloadedUri(context: Context, file: CameraFile): Uri? {
            val collection = when {
                file.isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                file.isImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            // Must agree with createPending's RELATIVE_PATH, or nothing we save is ever found again.
            val relPath = when {
                file.isVideo -> "Movies/Osmosis"
                file.isImage -> "Pictures/Osmosis"
                else -> "Download/Osmosis"
            }
            val proj = arrayOf(MediaStore.MediaColumns._ID)
            val sel = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0" +
                " AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            return runCatching {
                context.contentResolver
                    .query(collection, proj, sel, arrayOf(file.name, "$relPath%"), null)
                    ?.use { c ->
                        if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
                    }
            }.getOrNull()
        }

        /** MIME for a saved copy, matching what the download wrote. */
        fun mimeOf(file: CameraFile): String = when {
            file.isVideo -> "video/mp4"
            file.isImage -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }

    /** See [isDownloaded]: a completed row with our unique capture-name = already saved (no size match). */
    private fun isAlreadyDownloaded(f: CameraFile): Boolean = isDownloaded(context, f)
}
