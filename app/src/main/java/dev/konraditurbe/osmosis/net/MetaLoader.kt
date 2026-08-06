package dev.konraditurbe.osmosis.net

import android.widget.TextView
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.urlPath
import java.util.concurrent.Executors

/**
 * Fills in a grid cell's size + (for videos) duration label. **Size** comes straight from the DUML
 * manifest ([CameraFile.sizeBytes], record +38) with no network round-trip; only files the manifest
 * didn't size (photos, unknown layouts) fall back to an HTTP HEAD. Duration still needs the MP4
 * `mvhd`. Recycling-safe via the label tag; results are cached.
 */
class MetaLoader(private val http: HttpClient) {
    private val exec = Executors.newFixedThreadPool(3)
    private val cache = HashMap<String, String>()

    fun load(file: CameraFile, label: TextView, prefix: String) {
        synchronized(cache) { cache[file.path] }?.let {
            label.text = if (it.isEmpty()) prefix else "$prefix  $it"
            return
        }
        label.text = prefix
        label.tag = file.path
        exec.submit {
            // Everything from the DUML manifest — no network. Size (record marker-14) and, for videos,
            // duration in seconds (marker+26) are both decoded up front. The HTTP HEAD only survives as a
            // guard for the rare record the manifest didn't size (unknown layout).
            val size = if (file.sizeBytes > 0) file.sizeBytes else http.head(file.urlPath())
            val dur = if (file.isVideo && file.durationSec > 0) file.durationSec * 1000L else -1L
            val meta = buildString {
                if (dur > 0) append(fmtDur(dur))
                if (size > 0) { if (isNotEmpty()) append(" · "); append(fmtSize(size)) }
            }
            synchronized(cache) { cache[file.path] = meta }
            label.post {
                if (label.tag == file.path) label.text = if (meta.isEmpty()) prefix else "$prefix  $meta"
            }
        }
    }

    fun shutdown() = exec.shutdownNow()

    private fun fmtDur(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun fmtSize(b: Long): String = when {
        b >= 1_000_000_000 -> "%.1fGB".format(b / 1e9)
        b >= 1_000_000 -> "%.0fMB".format(b / 1e6)
        else -> "%dKB".format(b / 1000)
    }
}
