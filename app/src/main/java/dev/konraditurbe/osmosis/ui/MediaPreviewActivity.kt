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
import dev.konraditurbe.osmosis.net.Highlights
import dev.konraditurbe.osmosis.net.HttpClient
import dev.konraditurbe.osmosis.net.ImageLoader

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
    private var resTag: String? = null // resolution label, from the manifest (video enum / photo W×H)
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
    private lateinit var highlightRow: LinearLayout
    private lateinit var highlightStrip: View
    private val imageLoader by lazy { ImageLoader(http) { Log.i("Osmosis", it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        // Full-screen media viewer: black system bars with light icons (the app's cream theme sets the
        // opposite), so the status/nav bars blend into the dark preview instead of the cream chrome.
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        ip = intent.getStringExtra(EXTRA_IP) ?: "192.168.2.1"
        position = intent.getIntExtra(EXTRA_POSITION, -1)
        queued = intent.getBooleanExtra(EXTRA_QUEUED, false)
        trimStartMs = intent.getLongExtra(EXTRA_TRIM_START, -1L)
        trimEndMs = intent.getLongExtra(EXTRA_TRIM_END, -1L)
        groupPaths = intent.getStringArrayListExtra(EXTRA_GROUP_PATHS) ?: emptyList()
        groupThumbs = intent.getStringArrayListExtra(EXTRA_GROUP_THUMBS) ?: emptyList()
        file = CameraFile(path, "", intent.getIntExtra(EXTRA_STORAGE, 0),
            intent.getStringExtra(EXTRA_RES), intent.getStringExtra(EXTRA_PROXY),
            handle = intent.getLongExtra(EXTRA_HANDLE, 0L),
            sizeBytes = intent.getLongExtra(EXTRA_SIZE, 0L),
            resolution = intent.getStringExtra(EXTRA_RESOLUTION))

        videoView = findViewById(R.id.videoView)
        photoView = findViewById(R.id.photoView)
        spinner = findViewById(R.id.spinner)
        statusText = findViewById(R.id.statusText)
        topInfo = findViewById(R.id.topInfo)
        btnQueue = findViewById(R.id.btnQueue)
        btnMarkIn = findViewById(R.id.btnMarkIn)
        btnMarkOut = findViewById(R.id.btnMarkOut)
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
        highlightRow = findViewById(R.id.highlightRow)
        highlightStrip = findViewById(R.id.highlightStrip)

        // Tap the media to hide/show the overlays (full-frame view).
        videoView.setOnClickListener { toggleControls() }
        photoView.setOnClickListener { toggleControls() }

        renderTop()
        refreshQueueButton()
        btnQueue.setOnClickListener {
            queued = !queued
            refreshQueueButton()
            publishResult()
        }

        when {
            file.isVideo -> { setupTrim(); loadVideo(); loadHighlights() }
            file.isImage -> {
                resTag = file.resolution?.replace("x", "×")   // pixel W×H from the manifest, no JPEG decode
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
        refreshQueueButton() // a different frame may already be saved (or not)
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

    private fun hasTrim() = trimStartMs >= 0 && trimEndMs > trimStartMs

    private fun queueLabel() = when {
        queued -> "Remove from Queue"
        hasTrim() -> "Add to Queue (trimmed)"
        else -> "Add to Queue"
    }

    // Name -> "already fully saved" — checked off the UI thread, cached (per burst frame / the single file).
    private val downloadedCache = HashMap<String, Boolean>()

    /** The item the download button acts on: the selected burst frame, or the single file. Per-frame size
     *  is only known for the lead (frame 0), so other frames fall back to a name-only match. */
    private fun currentCheckFile(): CameraFile =
        if (groupPaths.isNotEmpty())
            file.copy(path = groupPaths[selectedFrame], sizeBytes = if (selectedFrame == 0) file.sizeBytes else 0L)
        else file

    /**
     * Gray out the download button when this exact file is already fully saved in its Osmosis collection —
     * but only for a **whole** download (no trim): a trimmed export is a new output, so it stays enabled.
     */
    private fun refreshQueueButton() {
        val f = currentCheckFile()
        val name = f.name
        if (!hasTrim() && !downloadedCache.containsKey(name)) {
            Thread {
                val done = dev.konraditurbe.osmosis.net.MediaDownloader.isDownloaded(this, f)
                main.post { downloadedCache[name] = done; refreshQueueButton() }
            }.start()
        }
        val alreadySaved = !hasTrim() && downloadedCache[name] == true
        btnQueue.isEnabled = !alreadySaved
        btnQueue.alpha = if (alreadySaved) 0.5f else 1f
        btnQueue.text = if (alreadySaved) "Already downloaded" else queueLabel()
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
        refreshQueueButton()   // adding a trim re-enables the button even if the whole file is saved
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

    /** id · date · <resolution>·<fps> — the 4-digit media ID (seq), not the raw filename. */
    private fun renderTop() {
        val resFps = listOfNotNull(resTag, file.resLabel).joinToString("·").ifBlank { "—" }
        val id = "%04d".format(file.seq)
        val name = if (groupPaths.isNotEmpty()) "$id  (${selectedFrame + 1}/${groupPaths.size})" else id
        topInfo.text = "$name   ·   ${file.dateTaken}   ·   $resFps"
    }

    /** Pull this video's highlight marks off-UI (DUML 0x02/0xff via the datalink bridge — inline on the
     *  live session now) and, if any, show a row of tappable ⚑ m:ss chips that seek the player. The video
     *  plays immediately; marks appear when the (fast) query returns. Best-effort — none / no session → nothing. */
    private fun loadHighlights() {
        val handle = file.handle
        if (handle == 0L) return
        Thread {
            val marks = runCatching { Highlights.provider?.invoke(handle) }.getOrNull().orEmpty()
            if (marks.isNotEmpty()) main.post { if (!isFinishing) showHighlights(marks) }
        }.start()
    }

    private fun showHighlights(marks: List<Int>) {
        highlightStrip.visibility = View.VISIBLE
        val accent = ContextCompat.getColor(this, R.color.osmo_accent)
        val d = resources.displayMetrics.density
        for (ms in marks) {
            val chip = TextView(this).apply {
                text = "⚑ ${mmss(ms.toLong())}"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
                setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins((4 * d).toInt(), 0, (4 * d).toInt(), 0) }
                setBackgroundColor(accent)
                setOnClickListener {
                    videoView.seekTo(ms)
                    seekBar.progress = ms
                    txtCur.text = mmss(ms.toLong())
                    if (controls.visibility != View.VISIBLE) toggleControls()
                }
            }
            highlightRow.addView(chip)
        }
    }

    private fun loadVideo() {
        // Resolution comes straight from the manifest (res-index enum, marker-1) — no moov. An unmapped
        // code just leaves it blank (the clip still plays); add the code to resolutionForIndex when seen.
        file.resolution?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
            ?.let { resTag = coarseRes(it[0], it[1]); renderTop() }   // onCreate already drew the top bar
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
                // Bounds are decoded to pick a safe downsample factor; they also backfill the resolution
                // label for the CAM_ family (Xtra/Action), whose manifest photo dims we don't decode.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (resTag == null && bounds.outWidth > 0) resTag = "${bounds.outWidth}×${bounds.outHeight}"
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
            putExtra(EXTRA_PATH, intent.getStringExtra(EXTRA_PATH))   // lead path = the grid cell's identity
            putExtra(EXTRA_POSITION, position)
            putExtra(EXTRA_QUEUED, queued)
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
        const val EXTRA_PATH = "path"
        private const val EXTRA_STORAGE = "storage"
        private const val EXTRA_SIZE = "size"    // full manifest byte size → already-downloaded check
        private const val EXTRA_HANDLE = "handle"    // video handle → highlight pull (0x02/0xff)
        private const val EXTRA_RES = "res"          // fps label ("25fps")
        private const val EXTRA_RESOLUTION = "resolution"  // pixel W×H ("3840x2160") from the manifest
        private const val EXTRA_PROXY = "proxy"
        private const val EXTRA_IP = "ip"
        const val EXTRA_POSITION = "position"
        const val EXTRA_QUEUED = "queued"
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
                putExtra(EXTRA_SIZE, file.sizeBytes)
                putExtra(EXTRA_HANDLE, file.handle)
                putExtra(EXTRA_RES, file.resLabel)
                putExtra(EXTRA_RESOLUTION, file.resolution)
                putExtra(EXTRA_PROXY, file.proxyPath)
                putExtra(EXTRA_IP, ip)
                putExtra(EXTRA_POSITION, position)
                putExtra(EXTRA_QUEUED, queued)
                putExtra(EXTRA_TRIM_START, trim?.startMs ?: -1L)
                putExtra(EXTRA_TRIM_END, trim?.endMs ?: -1L)
                if (group.size > 1) {
                    putStringArrayListExtra(EXTRA_GROUP_PATHS, ArrayList(group.map { it.path }))
                    putStringArrayListExtra(EXTRA_GROUP_THUMBS, ArrayList(group.map { it.thumbPath }))
                }
            }
    }
}
