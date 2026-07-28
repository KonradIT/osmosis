package dev.konraditurbe.osmosis.ui

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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.konraditurbe.osmosis.R
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.TrimRange
import dev.konraditurbe.osmosis.net.HttpClient
import dev.konraditurbe.osmosis.net.ImageLoader
import dev.konraditurbe.osmosis.net.VideoMeta

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
    private lateinit var btnStar: Button       // favorite toggle (video: next to ]; photo: above Queue)
    private lateinit var btnStarPhoto: Button
    private lateinit var trimRow: View
    private lateinit var controls: View
    private lateinit var seekBar: SeekBar
    private lateinit var txtCur: TextView
    private lateinit var txtTotal: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnRew: ImageButton
    private lateinit var btnFf: ImageButton
    private var scrubbing = false

    private lateinit var file: CameraFile
    private var ip = "192.168.2.1"
    private var position = -1
    private var queued = false
    private var starred = false
    private var resTag: String? = null // resolution, filled in async (moov for video, bounds for photo)
    private var streamCandidates: List<String> = emptyList() // preview URLs, cheapest first
    private var streamIdx = 0          // which candidate we're currently trying
    private var trimStartMs = -1L      // trim in/out points (ms), -1 = unset
    private var trimEndMs = -1L

    // Burst / interval group: the frames' media + thumb paths (sub-index order) and which one is shown.
    // Enumerated up-front by MainActivity via the DUML group-expand query and passed in through the intent.
    private var groupPaths: List<String> = emptyList()
    private var groupThumbs: List<String> = emptyList()
    private var selectedFrame = 0
    private lateinit var burstRow: LinearLayout
    private lateinit var burstStrip: View
    private val imageLoader by lazy { ImageLoader(http) { Log.i("Osmosis", it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        ip = intent.getStringExtra(EXTRA_IP) ?: "192.168.2.1"
        position = intent.getIntExtra(EXTRA_POSITION, -1)
        queued = intent.getBooleanExtra(EXTRA_QUEUED, false)
        starred = intent.getBooleanExtra(EXTRA_STARRED, false)
        trimStartMs = intent.getLongExtra(EXTRA_TRIM_START, -1L)
        trimEndMs = intent.getLongExtra(EXTRA_TRIM_END, -1L)
        groupPaths = intent.getStringArrayListExtra(EXTRA_GROUP_PATHS) ?: emptyList()
        groupThumbs = intent.getStringArrayListExtra(EXTRA_GROUP_THUMBS) ?: emptyList()
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
        btnStar = findViewById(R.id.btnStar)
        btnStarPhoto = findViewById(R.id.btnStarPhoto)
        trimRow = findViewById(R.id.trimRow)
        controls = findViewById(R.id.controls)
        seekBar = findViewById(R.id.seekBar)
        txtCur = findViewById(R.id.txtCur)
        txtTotal = findViewById(R.id.txtTotal)
        btnPlay = findViewById(R.id.btnPlay)
        btnRew = findViewById(R.id.btnRew)
        btnFf = findViewById(R.id.btnFf)
        burstRow = findViewById(R.id.burstRow)
        burstStrip = findViewById(R.id.burstStrip)

        // Tap the media to hide/show the overlays (full-frame view).
        videoView.setOnClickListener { toggleControls() }
        photoView.setOnClickListener { toggleControls() }

        renderTop()
        btnQueue.text = queueLabel()
        btnQueue.setOnClickListener {
            queued = !queued
            btnQueue.text = queueLabel()
            publishResult()
        }

        val onStar = View.OnClickListener { toggleStar() }
        btnStar.setOnClickListener(onStar)
        btnStarPhoto.setOnClickListener(onStar)
        renderStar()

        when {
            file.isVideo -> { setupTrim(); loadVideo() }
            file.isImage -> {
                btnStarPhoto.visibility = View.VISIBLE
                if (groupPaths.size > 1) setupBurstStrip()   // frames came from the DUML group-expand
                loadPhoto()
            }
            else -> showStatus("No preview for .${file.ext}")
        }
    }

    /** Build the 1×n burst/interval frame strip: a thumbnail per frame, tap to view it, accent border on
     *  the selected one. Frame 0 (`_001`) starts selected; the shown frame is what Add-to-Queue queues. */
    private fun setupBurstStrip() {
        burstStrip.visibility = View.VISIBLE
        val d = resources.displayMetrics.density
        val size = (54 * d).toInt(); val gap = (3 * d).toInt(); val pad = (2 * d).toInt()
        groupThumbs.forEachIndexed { i, thumbPath ->
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(gap, 0, gap, 0) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(pad, pad, pad, pad)   // background shows through the padding as the border
                setOnClickListener { selectFrame(i) }
            }
            burstRow.addView(iv)
            imageLoader.load("/v2?storage=${file.storage}&path=$thumbPath", iv)
        }
        updateBurstSelection()
    }

    private fun selectFrame(i: Int) {
        if (i == selectedFrame || i !in groupPaths.indices) return
        selectedFrame = i
        updateBurstSelection()
        loadPhoto()          // re-fetch the chosen frame (spinner shows while it loads)
        publishResult()      // the "currently viewed" frame changed — keep the pending result current
    }

    private fun updateBurstSelection() {
        val accent = ContextCompat.getColor(this, R.color.osmo_accent)
        for (i in 0 until burstRow.childCount) {
            burstRow.getChildAt(i).setBackgroundColor(if (i == selectedFrame) accent else 0x00000000)
        }
    }

    /** URL of the frame currently shown — a group's selected frame, or the single file. */
    private fun currentUrl(): String =
        if (groupPaths.isNotEmpty()) "/v2?storage=${file.storage}&path=${groupPaths[selectedFrame]}" else file.urlPath()

    /** Toggle favorite locally (optimistic) and publish it — MainActivity fires the 0x02/0xbf write. */
    private fun toggleStar() {
        starred = !starred
        renderStar()
        publishResult()
    }

    private fun renderStar() {
        val glyph = if (starred) "★" else "☆"
        val color = if (starred) 0xFFFFC107.toInt() else 0xFFFFFFFF.toInt()
        btnStar.text = glyph; btnStar.setTextColor(color)
        btnStarPhoto.text = glyph; btnStarPhoto.setTextColor(color)
    }

    private fun hasTrim() = trimStartMs >= 0 && trimEndMs > trimStartMs

    private fun queueLabel() = when {
        queued -> "Remove from Queue"
        hasTrim() -> "Add to Queue (trimmed)"
        else -> "Add to Queue"
    }

    /** Wire the custom player (transport + scrubber) and trim. Trim points come from the paused scrubber. */
    private fun setupTrim() {
        trimRow.visibility = View.VISIBLE

        btnPlay.setOnClickListener { togglePlay() }
        btnRew.setOnClickListener { seekBy(-5000) }
        btnFf.setOnClickListener { seekBy(5000) }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) { videoView.seekTo(progress); txtCur.text = mmss(progress.toLong()) }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { scrubbing = true }
            override fun onStopTrackingTouch(sb: SeekBar) { scrubbing = false }
        })

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

    private fun togglePlay() {
        if (videoView.isPlaying) videoView.pause() else videoView.start()
        updatePlayIcon()
    }

    private fun seekBy(deltaMs: Int) {
        val dur = videoView.duration
        val target = (videoView.currentPosition + deltaMs).coerceIn(0, if (dur > 0) dur else Int.MAX_VALUE)
        videoView.seekTo(target)
        seekBar.progress = target
        txtCur.text = mmss(target.toLong())
    }

    private fun updatePlayIcon() = btnPlay.setImageResource(
        if (videoView.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
    )

    /** Tap the media to hide the title + controls for a full-frame view; tap again to bring them back. */
    private fun toggleControls() {
        val show = controls.visibility != View.VISIBLE
        controls.visibility = if (show) View.VISIBLE else View.GONE
        topInfo.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** Keep the scrubber + current-time in sync while playing (skipped while the user is dragging). */
    private val tick = object : Runnable {
        override fun run() {
            if (!scrubbing && !isFinishing) {
                seekBar.progress = videoView.currentPosition
                txtCur.text = mmss(videoView.currentPosition.toLong())
            }
            updatePlayIcon()
            main.postDelayed(this, 250)
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    /** filename · date · <resolution>·<fps> */
    private fun renderTop() {
        val resFps = listOfNotNull(resTag, file.resLabel).joinToString("·").ifBlank { "—" }
        val name = if (groupPaths.isNotEmpty())
            groupPaths[selectedFrame].substringAfterLast('/') + "  (${selectedFrame + 1}/${groupPaths.size})"
        else file.name
        topInfo.text = "$name   ·   ${file.dateTaken}   ·   $resFps"
    }

    private fun loadVideo() {
        // Prefer the resolution decoded straight from the manifest (marker-1 index); only fall back to
        // the MP4 moov when the camera used a resolution code we haven't mapped yet.
        val manifestRes = file.resolution?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
        if (manifestRes != null) {
            resTag = coarseRes(manifestRes[0], manifestRes[1])
        } else {
            Thread {
                val wh = runCatching { VideoMeta.resolution(http, file) }.getOrNull()
                if (wh != null) {
                    resTag = coarseRes(wh.first, wh.second)
                    main.post { if (!isFinishing) renderTop() }
                }
            }.start()
        }
        // Try the low-res proxy first (listed .LRF/.LRV, or a derived .XRF sidecar the Xtra/Action 5
        // Pro doesn't list), falling back through to the full-res file. See CameraFile.previewCandidates.
        streamCandidates = file.previewCandidates()
        streamIdx = 0
        startStream(streamCandidates[streamIdx])
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
        videoView.setOnPreparedListener { mp ->
            Log.i("Osmosis", "preview PREPARED ${mp.videoWidth}x${mp.videoHeight}")
            mp.isLooping = true
            spinner.visibility = ProgressBar.GONE
            videoView.start()
            seekBar.max = videoView.duration.coerceAtLeast(1)
            txtTotal.text = mmss(videoView.duration.toLong())
            updatePlayIcon()
            main.removeCallbacks(tick)
            main.post(tick)
        }
        videoView.setOnErrorListener { _, what, extra ->
            Log.i("Osmosis", "preview ERROR what=$what extra=$extra (candidate ${streamIdx + 1}/${streamCandidates.size}: $path)")
            if (streamIdx < streamCandidates.size - 1) {
                streamIdx++
                Log.i("Osmosis", "preview falling back to ${streamCandidates[streamIdx]}")
                startStream(streamCandidates[streamIdx])
                return@setOnErrorListener true
            }
            showStatus("Can't play this clip ($what/$extra)")
            true
        }
    }

    private fun loadPhoto() {
        val dm = resources.displayMetrics
        val frame = selectedFrame            // guard against a fast frame switch racing the fetch
        val url = currentUrl()
        spinner.visibility = ProgressBar.VISIBLE
        statusText.visibility = TextView.GONE
        Thread {
            val bytes = runCatching { http.getBytes(url) }.getOrNull()
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
                if (isFinishing || frame != selectedFrame) return@post   // a newer frame is loading
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
        // For a burst, hand back the *viewed* frame's own path/thumb so the queue grabs that frame — the
        // grid never probed the group, so it can't resolve an index. Frame 0 (or non-burst) → no override.
        val selPath = groupPaths.getOrNull(selectedFrame).takeIf { selectedFrame > 0 }
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_POSITION, position)
            putExtra(EXTRA_QUEUED, queued)
            putExtra(EXTRA_STARRED, starred)
            putExtra(EXTRA_GROUP_SEL_PATH, selPath)
            putExtra(EXTRA_GROUP_SEL_THUMB, selPath?.let { groupThumbs.getOrNull(selectedFrame) })
            putExtra(EXTRA_TRIM_START, if (hasTrim()) trimStartMs else -1L)
            putExtra(EXTRA_TRIM_END, if (hasTrim()) trimEndMs else -1L)
        })
    }

    override fun onPause() {
        super.onPause()
        if (videoView.isPlaying) { videoView.pause(); updatePlayIcon() }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(tick)
        runCatching { videoView.stopPlayback() }
        imageLoader.shutdown()
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_STORAGE = "storage"
        private const val EXTRA_RES = "res"
        private const val EXTRA_PROXY = "proxy"
        private const val EXTRA_IP = "ip"
        const val EXTRA_POSITION = "position"
        const val EXTRA_QUEUED = "queued"
        const val EXTRA_STARRED = "starred"
        const val EXTRA_GROUP_SEL_PATH = "group_sel_path"    // out: viewed burst frame's path (queue this)
        const val EXTRA_GROUP_SEL_THUMB = "group_sel_thumb"  // out: …and its thumb path
        private const val EXTRA_GROUP_PATHS = "group_paths"
        private const val EXTRA_GROUP_THUMBS = "group_thumbs"
        const val EXTRA_TRIM_START = "trim_start"
        const val EXTRA_TRIM_END = "trim_end"

        /** [group] = a burst/interval group's frames (from DatalinkClient.expandBurstGroup), sub-index
         *  order, [file] being the lead; empty/size-1 for a normal file → no strip. */
        fun intent(ctx: Context, ip: String, file: CameraFile, position: Int, queued: Boolean,
                   trim: TrimRange?, group: List<CameraFile> = emptyList()) =
            Intent(ctx, MediaPreviewActivity::class.java).apply {
                putExtra(EXTRA_PATH, file.path)
                putExtra(EXTRA_STORAGE, file.storage)
                putExtra(EXTRA_RES, file.resLabel)
                putExtra(EXTRA_PROXY, file.proxyPath)
                putExtra(EXTRA_IP, ip)
                putExtra(EXTRA_POSITION, position)
                putExtra(EXTRA_QUEUED, queued)
                putExtra(EXTRA_STARRED, file.starred)
                putExtra(EXTRA_TRIM_START, trim?.startMs ?: -1L)
                putExtra(EXTRA_TRIM_END, trim?.endMs ?: -1L)
                if (group.size > 1) {
                    putStringArrayListExtra(EXTRA_GROUP_PATHS, ArrayList(group.map { it.path }))
                    putStringArrayListExtra(EXTRA_GROUP_THUMBS, ArrayList(group.map { it.thumbPath }))
                }
            }
    }
}
