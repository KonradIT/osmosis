package com.chernowii.osmosis.net

import android.widget.TextView
import com.chernowii.osmosis.core.CameraFile
import java.util.concurrent.Executors

/**
 * Lazily fetches a file's size (Content-Length) and, for videos, duration (mvhd), then appends
 * them to a grid cell's label. Recycling-safe via the label tag; results are cached.
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
            val size = http.head(file.urlPath())
            val dur = if (file.isVideo) VideoMeta.durationMs(http, file) else -1
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
