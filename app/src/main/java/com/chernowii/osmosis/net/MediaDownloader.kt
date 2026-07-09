package com.chernowii.osmosis.net

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.chernowii.osmosis.core.CameraFile
import java.io.FileOutputStream

/**
 * Downloads camera files into shared media collections (Movies/Pictures/Download → /Osmosis) with
 * progress + resume. Each in-progress file is a pending MediaStore item whose URI we remember in
 * prefs; if interrupted, the next run reopens it, seeks to its current length, and Range-requests
 * the remainder. Already-completed files are skipped.
 */
class MediaDownloader(
    private val context: Context,
    private val http: HttpClient,
    private val log: (String) -> Unit,
) {
    interface Progress {
        fun onStart(totalFiles: Int, totalBytes: Long)
        fun onFileStart(index: Int, name: String, fileBytes: Long)
        fun onTick(fileDone: Long, overallDone: Long)
        fun onComplete(saved: Int, skipped: Int, failed: Int)
    }

    private enum class Result { SAVED, SKIPPED, FAILED }

    private val prefs get() = context.getSharedPreferences("osmosis_dl", Context.MODE_PRIVATE)

    fun run(files: List<CameraFile>, p: Progress) {
        val sizes = files.map { http.head(it.urlPath()).coerceAtLeast(0L) }
        p.onStart(files.size, sizes.sum())
        var overallBase = 0L
        var saved = 0; var skipped = 0; var failed = 0
        for ((i, f) in files.withIndex()) {
            val remote = sizes[i]
            p.onFileStart(i, f.name, remote)
            when (downloadOne(f, remote) { fileDone -> p.onTick(fileDone, overallBase + fileDone) }) {
                Result.SAVED -> saved++
                Result.SKIPPED -> skipped++
                Result.FAILED -> failed++
            }
            overallBase += remote
        }
        p.onComplete(saved, skipped, failed)
    }

    private fun downloadOne(f: CameraFile, remote: Long, tick: (Long) -> Unit): Result {
        val resolver = context.contentResolver
        val key = "dl_" + f.path

        var u = prefs.getString(key, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        // Drop a stale tracked URI that no longer opens.
        val tracked = u
        if (tracked != null && runCatching { resolver.openFileDescriptor(tracked, "rw")?.close() }.isFailure) u = null
        if (u == null) {
            if (isAlreadyDownloaded(f, remote)) { log("skip ${f.name} (already saved)"); return Result.SKIPPED }
            u = createPending(f) ?: run { log("insert failed: ${f.name}"); return Result.FAILED }
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
        fos.channel.position(startOffset) // append (server Range-response continues from here)
        val ok = http.download(f.urlPath(), fos, startOffset) { total -> tick(total) }
        runCatching { fos.flush(); fos.close() }
        pfd.close()

        return if (ok) {
            markComplete(uri); prefs.edit().remove(key).apply(); Result.SAVED
        } else {
            log("paused ${f.name} (will resume on next Download)"); Result.FAILED
        }
    }

    private fun collectionFor(f: CameraFile): Triple<Uri, String, String> = when {
        f.isVideo -> Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/Osmosis", "video/mp4")
        f.isImage -> Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/Osmosis", "image/jpeg")
        else -> Triple(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Download/Osmosis", "application/octet-stream")
    }

    private fun createPending(f: CameraFile): Uri? {
        val (collection, relPath, mime) = collectionFor(f)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
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
