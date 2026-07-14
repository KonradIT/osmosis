package dev.konraditurbe.osmosis.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * Minimal async thumbnail loader (no third-party image lib). Fetches a `.scr` thumbnail over
 * HTTP, decodes it, caches it, and sets it on the target ImageView (recycling-safe via tag).
 * On the first decode failure it logs the leading bytes so we can identify the thumbnail format.
 */
class ImageLoader(private val http: HttpClient, private val log: (String) -> Unit) {
    private val exec = Executors.newFixedThreadPool(4)
    private val cache = LruCache<String, Bitmap>(96)
    @Volatile private var loggedFailure = false

    fun load(thumbUrlPath: String, view: ImageView) {
        view.tag = thumbUrlPath
        cache.get(thumbUrlPath)?.let { view.setImageBitmap(it); return }
        view.setImageBitmap(null)
        exec.submit {
            val bytes = http.getBytes(thumbUrlPath)
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

    fun shutdown() = exec.shutdownNow()
}
