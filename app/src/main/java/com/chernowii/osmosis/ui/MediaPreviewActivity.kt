package com.chernowii.osmosis.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.chernowii.osmosis.R
import com.chernowii.osmosis.core.CameraFile
import com.chernowii.osmosis.core.TrimRange
import com.chernowii.osmosis.net.HttpClient
import com.chernowii.osmosis.net.VideoMeta

/**
 * Full-screen media preview. Videos stream DJI's low-res .LRF proxy straight off the camera —
 * native MediaPlayer honours the process network binding, so it reaches the internet-less AP and
 * range-fetches the moov + samples on demand (any clip length, full scrub, no download). Photos
 * show the JPEG. Top bar = filename · date · resolution·fps (fps from the DUML manifest; resolution
 * from the MP4 moov, since it isn't in the manifest). The bottom button toggles this item in the
 * download queue and returns the decision. The high-res file is never fetched here.
 */
class MediaPreviewActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val http by lazy { HttpClient(ip) { Log.i("Osmosis", it) } }

    private lateinit var videoView: VideoView
    private lateinit var photoView: ImageView
    private lateinit var spinner: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var topInfo: TextView
    private lateinit var btnQueue: Button
    private lateinit var btnMarkIn: Button
    private lateinit var btnMarkOut: Button
    private lateinit var trimRow: View

    private lateinit var file: CameraFile
    private var ip = "192.168.2.1"
    private var position = -1
    private var queued = false
    private var resTag: String? = null // resolution, filled in async (moov for video, bounds for photo)
    private var triedFull = false      // whether we've already fallen back from proxy to full-res
    private var trimStartMs = -1L      // trim in/out points (ms), -1 = unset
    private var trimEndMs = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        ip = intent.getStringExtra(EXTRA_IP) ?: "192.168.2.1"
        position = intent.getIntExtra(EXTRA_POSITION, -1)
        queued = intent.getBooleanExtra(EXTRA_QUEUED, false)
        trimStartMs = intent.getLongExtra(EXTRA_TRIM_START, -1L)
        trimEndMs = intent.getLongExtra(EXTRA_TRIM_END, -1L)
        file = CameraFile(path, "", intent.getIntExtra(EXTRA_STORAGE, 0),
            intent.getStringExtra(EXTRA_RES), intent.getStringExtra(EXTRA_PROXY))

        videoView = findViewById(R.id.videoView)
        photoView = findViewById(R.id.photoView)
        spinner = findViewById(R.id.spinner)
        statusText = findViewById(R.id.statusText)
        topInfo = findViewById(R.id.topInfo)
        btnQueue = findViewById(R.id.btnQueue)
        btnMarkIn = findViewById(R.id.btnMarkIn)
        btnMarkOut = findViewById(R.id.btnMarkOut)
        trimRow = findViewById(R.id.trimRow)

        renderTop()
        btnQueue.text = queueLabel()
        btnQueue.setOnClickListener {
            queued = !queued
            btnQueue.text = queueLabel()
            publishResult()
        }

        when {
            file.isVideo -> { setupTrim(); loadVideo() }
            file.isImage -> loadPhoto()
            else -> showStatus("No preview for .${file.ext}")
        }
    }

    private fun hasTrim() = trimStartMs >= 0 && trimEndMs > trimStartMs

    private fun queueLabel() = when {
        queued -> "Remove from Queue"
        hasTrim() -> "Add to Queue (trimmed)"
        else -> "Add to Queue"
    }

    /** Grab in/out points from the paused scrubber. Selection requires the video to be paused. */
    private fun setupTrim() {
        trimRow.visibility = View.VISIBLE
        btnMarkIn.setOnClickListener {
            if (videoView.isPlaying) { toast("Pause, then set the start point"); return@setOnClickListener }
            trimStartMs = videoView.currentPosition.toLong()
            if (trimEndMs in 0..trimStartMs) trimEndMs = -1L // stale end now before start
            updateTrimUi()
        }
        btnMarkOut.setOnClickListener {
            if (videoView.isPlaying) { toast("Pause, then set the end point"); return@setOnClickListener }
            if (trimStartMs < 0) { toast("Set the start point [ first"); return@setOnClickListener }
            val pos = videoView.currentPosition.toLong()
            if (pos <= trimStartMs) { toast("End must be after the start point"); return@setOnClickListener }
            trimEndMs = pos
            updateTrimUi()
        }
        updateTrimUi()
    }

    private fun updateTrimUi() {
        btnMarkIn.text = if (trimStartMs >= 0) "[ ${mmss(trimStartMs)}" else "["
        btnMarkOut.text = if (trimEndMs >= 0) "] ${mmss(trimEndMs)}" else "]"
        btnQueue.text = queueLabel()
    }

    private fun mmss(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    /** filename · date · <resolution>·<fps> */
    private fun renderTop() {
        val resFps = listOfNotNull(resTag, file.resLabel).joinToString("·").ifBlank { "—" }
        topInfo.text = "${file.name}   ·   ${file.dateTaken}   ·   $resFps"
    }

    private fun loadVideo() {
        // Resolution isn't in the manifest — read it from the MP4 moov, independent of playback.
        Thread {
            val wh = runCatching { VideoMeta.resolution(http, file) }.getOrNull()
            if (wh != null) {
                resTag = coarseRes(wh.first, wh.second)
                main.post { if (!isFinishing) renderTop() }
            }
        }.start()
        // Prefer the low-res proxy the manifest actually lists (.LRF/.LRV); if the camera provides
        // none, stream the full-res file — streaming handles any size either way.
        startStream(file.proxyUrlPath() ?: file.urlPath())
    }

    /**
     * Stream a clip off the camera (see class doc). The VideoView must be visible before setVideoURI
     * — a GONE view has no Surface, so MediaPlayer would never prepare. If a listed proxy fails to
     * decode (missing/foreign container), fall back once to the full-res file.
     */
    private fun startStream(path: String) {
        val uri = Uri.parse("http://$ip$path")
        Log.i("Osmosis", "preview stream $uri")
        videoView.visibility = VideoView.VISIBLE
        videoView.setVideoURI(uri)
        videoView.setMediaController(MediaController(this).apply { setAnchorView(videoView) })
        videoView.setOnPreparedListener { mp ->
            Log.i("Osmosis", "preview PREPARED ${mp.videoWidth}x${mp.videoHeight}")
            mp.isLooping = true
            spinner.visibility = ProgressBar.GONE
            videoView.start()
        }
        videoView.setOnErrorListener { _, what, extra ->
            Log.i("Osmosis", "preview video ERROR what=$what extra=$extra")
            if (!triedFull && path != file.urlPath()) {
                triedFull = true
                Log.i("Osmosis", "preview falling back to full-res stream")
                startStream(file.urlPath())
                return@setOnErrorListener true
            }
            showStatus("Can't play this clip ($what/$extra)")
            true
        }
    }

    private fun loadPhoto() {
        val dm = resources.displayMetrics
        Thread {
            val bytes = runCatching { http.getBytes(file.urlPath()) }.getOrNull()
            var bmp: Bitmap? = null
            if (bytes != null) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth > 0) resTag = "${bounds.outWidth}×${bounds.outHeight}"
                var sample = 1
                while (bounds.outWidth / sample > dm.widthPixels || bounds.outHeight / sample > dm.heightPixels) sample *= 2
                bmp = runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull()
            }
            main.post {
                if (isFinishing) return@post
                renderTop()
                if (bmp != null) {
                    photoView.setImageBitmap(bmp)
                    spinner.visibility = ProgressBar.GONE
                    photoView.visibility = ImageView.VISIBLE
                } else showStatus("Preview unavailable")
            }
        }.start()
    }

    private fun coarseRes(w: Int, h: Int): String {
        val big = maxOf(w, h)
        return when {
            big >= 7000 -> "8K"
            big >= 3600 -> "4K"
            big >= 2560 -> "2.7K"
            big >= 1900 -> "1080p"
            big >= 1200 -> "720p"
            else -> "${w}×${h}"
        }
    }

    private fun showStatus(msg: String) {
        spinner.visibility = ProgressBar.GONE
        statusText.text = msg
        statusText.visibility = TextView.VISIBLE
    }

    private fun publishResult() {
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_POSITION, position)
            putExtra(EXTRA_QUEUED, queued)
            putExtra(EXTRA_TRIM_START, if (hasTrim()) trimStartMs else -1L)
            putExtra(EXTRA_TRIM_END, if (hasTrim()) trimEndMs else -1L)
        })
    }

    override fun onPause() {
        super.onPause()
        if (videoView.isPlaying) videoView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { videoView.stopPlayback() }
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_STORAGE = "storage"
        private const val EXTRA_RES = "res"
        private const val EXTRA_PROXY = "proxy"
        private const val EXTRA_IP = "ip"
        const val EXTRA_POSITION = "position"
        const val EXTRA_QUEUED = "queued"
        const val EXTRA_TRIM_START = "trim_start"
        const val EXTRA_TRIM_END = "trim_end"

        fun intent(ctx: Context, ip: String, file: CameraFile, position: Int, queued: Boolean, trim: TrimRange?) =
            Intent(ctx, MediaPreviewActivity::class.java).apply {
                putExtra(EXTRA_PATH, file.path)
                putExtra(EXTRA_STORAGE, file.storage)
                putExtra(EXTRA_RES, file.resLabel)
                putExtra(EXTRA_PROXY, file.proxyPath)
                putExtra(EXTRA_IP, ip)
                putExtra(EXTRA_POSITION, position)
                putExtra(EXTRA_QUEUED, queued)
                putExtra(EXTRA_TRIM_START, trim?.startMs ?: -1L)
                putExtra(EXTRA_TRIM_END, trim?.endMs ?: -1L)
            }
    }
}
