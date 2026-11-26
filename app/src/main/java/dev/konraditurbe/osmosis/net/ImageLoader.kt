package dev.konraditurbe.osmosis.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import dev.konraditurbe.osmosis.core.AlbumJpeg
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.MediaSession
import dev.konraditurbe.osmosis.core.thumbUrlPath
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Loads grid thumbnails and album JPEGs on the camera transport that supplies them. */
class ImageLoader(private val http: HttpClient, private val album: MediaSession? = null,
                  private val log: (String) -> Unit) {
    private val exec = Executors.newFixedThreadPool(4)
    private val albumWorker = Executors.newSingleThreadExecutor()
    private class AlbumBinding(val key: String)
    private val pending = WeakHashMap<ImageView, Future<*>>()
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    @Volatile private var loggedFailure = false

    fun clear(view: ImageView) {
        pending.remove(view)?.cancel(true)
        view.tag = null
        view.setImageBitmap(null)
    }

    fun loadThumbnail(file: CameraFile, httpAvailable: Boolean, view: ImageView) {
        val path = if (httpAvailable) file.thumbUrlPath() else ""
        if (album?.datalinkJpegs == true && file.isActionDcf) {
            if ((view.tag as? AlbumBinding)?.key == albumKey(file, AlbumJpeg.THUMBNAIL)) return
            loadAlbum(file, AlbumJpeg.THUMBNAIL, view) { ok -> if (!ok && path.isNotEmpty()) load(path, view) }
        } else load(path, view)
    }

    private fun albumKey(file: CameraFile, rendition: AlbumJpeg) =
        "album/${file.albumKey}/${file.mtimeEpoch}/$rendition"

    fun loadAlbum(file: CameraFile, rendition: AlbumJpeg, view: ImageView, complete: (Boolean) -> Unit) {
        clear(view)
        val session = album
        if (session == null || !session.datalinkJpegs) { complete(false); return }
        val key = albumKey(file, rendition)
        val binding = AlbumBinding(key)
        view.tag = binding
        cache.get(key)?.let { view.setImageBitmap(it); complete(true); return }
        pending[view] = albumWorker.submit {
            fun fetch(kind: AlbumJpeg): Bitmap? {
                val bytes = session.fetchAlbumJpeg(file, kind) ?: return null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                val limit = if (rendition == AlbumJpeg.THUMBNAIL) 384 else 2048
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > limit) sample *= 2
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample })
            }
            val fallback = if (rendition == AlbumJpeg.SCREEN) AlbumJpeg.THUMBNAIL else AlbumJpeg.SCREEN
            val bitmap = fetch(rendition) ?: if (!Thread.currentThread().isInterrupted && session.datalinkJpegs)
                fetch(fallback) else null
            view.post {
                if (view.tag !== binding || albumWorker.isShutdown) return@post
                pending.remove(view)
                if (bitmap != null) {
                    cache.put(key, bitmap)
                    view.setImageBitmap(bitmap)
                } else view.tag = null
                complete(bitmap != null)
            }
        }
    }

    fun load(thumbUrlPath: String, view: ImageView) {
        pending.remove(view)?.cancel(true)
        view.tag = thumbUrlPath
        if (thumbUrlPath.isEmpty()) { view.setImageBitmap(null); return }
        cache.get(thumbUrlPath)?.let { view.setImageBitmap(it); return }
        view.setImageBitmap(null)
        exec.submit {
            // Derived path is a `.scr` screennail (videos). Photos have a `.thm` instead, so if the
            // `.scr` isn't there, fall back to `.thm` before giving up — the camera lists one or the
            // other, never both. Only the first view of such a file pays the extra request; the decoded
            // bitmap caches under the original key.
            val exif = dev.konraditurbe.osmosis.core.CameraFile.EXIF_THUMB
            var bytes = if (thumbUrlPath.startsWith(exif)) {
                http.getRange(thumbUrlPath.removePrefix(exif), 0,
                    (dev.konraditurbe.osmosis.core.EmbeddedJpeg.HEAD_BYTES - 1).toLong())
                    ?.let { dev.konraditurbe.osmosis.core.EmbeddedJpeg.fromHeader(it) }
            } else {
                http.getBytes(thumbUrlPath)
            }
            if (bytes == null && thumbUrlPath.endsWith(".scr")) {
                bytes = http.getBytes(thumbUrlPath.removeSuffix(".scr") + ".thm")
            }
            val bmp = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            if (bmp == null && bytes != null && !loggedFailure) {
                loggedFailure = true
                val head = bytes.take(16).joinToString("") { "%02x".format(it) }
                log("thumb: ${bytes.size}B did not decode; head=$head (path=$thumbUrlPath)")
            }
            if (bmp != null) {
                cache.put(thumbUrlPath, bmp)
                view.post { if (view.tag == thumbUrlPath) view.setImageBitmap(bmp) }
            }
        }
    }

    fun shutdown() {
        pending.values.forEach { it.cancel(true) }
        pending.clear()
        albumWorker.shutdownNow()
        exec.shutdownNow()
    }
}
