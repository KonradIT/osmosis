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
import android.view.GestureDetector
import android.view.MotionEvent
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
import dev.konraditurbe.osmosis.camera.PathAddressing
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.TrimRange
import dev.konraditurbe.osmosis.core.previewCandidates
import dev.konraditurbe.osmosis.core.urlPath
import dev.konraditurbe.osmosis.net.HttpClient
import dev.konraditurbe.osmosis.net.ImageLoader

/**
 * Full-screen media preview. Videos stream DJI's low-res .LRF proxy straight off the camera —
 * native MediaPlayer honours the process network binding, so it reaches the internet-less AP and
 * range-fetches the moov + samples on demand (any clip length, full scrub, no download). Photos
 * show the JPEG. Top bar = 4-digit ID · date · resolution·fps (all from the DUML manifest). The bottom
 * button toggles this item in the download queue, written straight to the grid via [PreviewNav].
 *
 * **Swipe to browse:** a horizontal fling moves through the grid's *filtered* list (left = next, right =
 * previous) — for photos always, for videos only while paused. The high-res file is never fetched here.
 */
class MediaPreviewActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val http by lazy { HttpClient(ip) { Log.i("Osmosis", it) } }

    private lateinit var videoView: VideoView
    private lateinit var photoView: ImageView
    private var photoZoom: PhotoZoom? = null
    private lateinit var savedActions: View
    /** Uri of the current item's saved copy, or null when it isn't in the gallery yet. */
    private var savedUri: android.net.Uri? = null
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
    // The grid's filtered list + our index in it, so a swipe (when paused) moves to the prev/next item.
    private var navItems: List<CameraFile> = emptyList()
    private var navIndex = -1
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
    private val imageLoader by lazy { ImageLoader(http) { Log.i("Osmosis", it) } }

    // Scrub preview: the frame under the thumb, floated above the seek bar during a drag.
    private lateinit var previewRoot: View
    private lateinit var scrubPreview: View
    private lateinit var scrubImage: ImageView
    private lateinit var scrubTime: TextView
    private val scrubFrames by lazy { ScrubFrames { Log.i("Osmosis", it) } }

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

        ip = intent.getStringExtra(EXTRA_IP) ?: "192.168.2.1"
        // The initially-tapped burst group (if any) — its frame strip shows only on this first item and is
        // cleared once you swipe away (swiped items show the group lead as a single photo).
        groupPaths = intent.getStringArrayListExtra(EXTRA_GROUP_PATHS) ?: emptyList()
        groupThumbs = intent.getStringArrayListExtra(EXTRA_GROUP_THUMBS) ?: emptyList()

        videoView = findViewById(R.id.videoView)
        photoView = findViewById(R.id.photoView)
        spinner = findViewById(R.id.spinner)
        statusText = findViewById(R.id.statusText)
        topInfo = findViewById(R.id.topInfo)
        savedActions = findViewById(R.id.savedActions)
        findViewById<View>(R.id.btnShare).setOnClickListener { sendSavedCopy(Intent.ACTION_SEND) }
        findViewById<View>(R.id.btnEdit).setOnClickListener { sendSavedCopy(Intent.ACTION_EDIT) }
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
        previewRoot = findViewById(R.id.previewRoot)
        scrubPreview = findViewById(R.id.scrubPreview)
        scrubImage = findViewById(R.id.scrubImage)
        scrubTime = findViewById(R.id.scrubTime)

        // One-time wiring; the media itself is (re)loaded per item in loadCurrent().
        setupTrimListeners()
        installSwipeAndTap()   // tap = toggle overlays; horizontal swipe (when paused) = prev/next item
        btnQueue.setOnClickListener {
            queued = !queued
            commitQueue()
            refreshQueueButton()
            if (queued) offerSidecar() else dropSidecar()
        }

        navItems = dev.konraditurbe.osmosis.net.PreviewNav.items
        navIndex = intent.getIntExtra(EXTRA_POSITION, -1)
        file = navItems.getOrNull(navIndex) ?: fileFromExtras()
        if (file.path.isEmpty()) { finish(); return }
        loadCurrent()
    }

    private fun fileFromExtras(): CameraFile = CameraFile(
        intent.getStringExtra(EXTRA_PATH) ?: "", "", intent.getIntExtra(EXTRA_STORAGE, 0),
        intent.getStringExtra(EXTRA_RES), intent.getStringExtra(EXTRA_PROXY),
        handle = intent.getLongExtra(EXTRA_HANDLE, 0L),
        sizeBytes = intent.getLongExtra(EXTRA_SIZE, 0L),
        resolution = intent.getStringExtra(EXTRA_RESOLUTION))

    /** Tap the media to toggle overlays; a horizontal fling moves to the prev/next item — but only when
     *  paused (photos are always swipeable). Swipe **left = next**, **right = previous**. A photo also
     *  pinch-zooms, and while it is zoomed the fling is suppressed so a drag pans instead of navigating. */
    private fun installSwipeAndTap() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // MUST return true so the detector consumes the DOWN and keeps getting MOVE/UP — otherwise
            // the view never forwards the rest of the gesture and onFling/onSingleTap never fire.
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { toggleControls(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (file.isVideo) return false
                photoZoom?.toggle(e.x, e.y)
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (photoZoom?.isZoomed == true) return false   // panning a magnified photo, not navigating
                if (kotlin.math.abs(vx) < kotlin.math.abs(vy) || kotlin.math.abs(vx) < 800f) return false
                if (navItems.isEmpty() || (file.isVideo && videoView.isPlaying)) return false
                loadItem(navIndex + if (vx < 0) 1 else -1)   // swipe left = next, swipe right = previous
                return true
            }
        })
        @Suppress("ClickableViewAccessibility")
        val touch = View.OnTouchListener { _, ev -> gd.onTouchEvent(ev) }
        videoView.setOnTouchListener(touch)

        val zoom = PhotoZoom(photoView)
        photoZoom = zoom
        @Suppress("ClickableViewAccessibility")
        photoView.setOnTouchListener { _, ev ->
            // Both get every event: zoom claims only pinches and pans, while the detector still needs
            // the full stream for single- and double-tap. Always consume, or the view stops delivering
            // MOVE/UP after the DOWN and every gesture here dies half-way.
            zoom.onTouch(ev)
            gd.onTouchEvent(ev)
            true
        }
    }

    /** Move to a different item in the filtered list: reset per-item state and (re)load the media. */
    private fun loadItem(index: Int) {
        if (index !in navItems.indices || index == navIndex) return
        navIndex = index
        file = navItems[index]
        selectedFrame = 0
        groupPaths = emptyList(); groupThumbs = emptyList()   // strip only for the initially-tapped burst
        burstRow.removeAllViews(); burstStrip.visibility = View.GONE
        scrubFrames.close()
        hideScrubPreview()
        runCatching { videoView.stopPlayback() }
        main.removeCallbacks(tick)
        loadCurrent()
    }

    /** Load whatever [file] currently points at — queue/trim state comes live from the grid (PreviewNav). */
    private fun loadCurrent() {
        queued = dev.konraditurbe.osmosis.net.PreviewNav.isQueued?.invoke(file.path) ?: false
        val t = dev.konraditurbe.osmosis.net.PreviewNav.trimFor?.invoke(file.path)
        trimStartMs = t?.startMs ?: -1L; trimEndMs = t?.endMs ?: -1L
        resTag = null

        videoView.visibility = View.GONE
        photoView.visibility = View.GONE
        statusText.visibility = View.GONE
        spinner.visibility = ProgressBar.VISIBLE
        // `controls` is the whole bottom overlay — it also holds the queue button and the burst strip,
        // so it stays up for a still. Only [trimRow], the scrubber/transport/trim block inside it, is
        // video-only. Hiding the container instead took "Add to Queue" down with it on every photo.
        trimRow.visibility = if (file.isVideo) View.VISIBLE else View.GONE
        controls.visibility = View.VISIBLE
        topInfo.visibility = View.VISIBLE

        renderTop()
        updateTrimUi()
        refreshQueueButton()

        when {
            file.isVideo -> loadVideo()
            file.isImage -> {
                resTag = file.resolution?.replace("x", "×")   // pixel W×H from the manifest, no JPEG decode
                if (groupPaths.size > 1) setupBurstStrip()     // frames came from the DUML group-expand
                loadPhoto()
            }
            else -> showStatus(getString(R.string.no_preview, file.ext))
        }
    }

    /** Write the current item's queue decision straight to the grid via the bridge (no result round-trip). */
    private fun commitQueue() {
        val member = if (groupPaths.isNotEmpty() && selectedFrame > 0)
            file.copy(path = groupPaths[selectedFrame], thumbPath = groupThumbs.getOrNull(selectedFrame) ?: file.thumbPath)
        else null
        val trim = if (hasTrim()) TrimRange(trimStartMs, trimEndMs) else null
        dev.konraditurbe.osmosis.net.PreviewNav.setQueued?.invoke(file.path, queued, trim, member)
    }

    /** The file the queue button actually acts on: the shown burst frame, else the current item. */
    private fun queueTarget(): CameraFile =
        if (groupPaths.isNotEmpty() && selectedFrame > 0)
            file.copy(path = groupPaths[selectedFrame], thumbPath = groupThumbs.getOrNull(selectedFrame) ?: file.thumbPath)
        else file

    /**
     * ROADMAP #19: when a queued still has an unlisted RAW (`.DNG`) beside it, or a queued clip an
     * audio-backup (`.WAV`), offer to queue that too. The manifest carries no reliable flag for it, so
     * confirm the companion exists with one HEAD (off the main thread) before prompting. It is queued as
     * its own entry, carrying its own path through the queue's `member` slot so it downloads with no grid
     * cell of its own; the HEAD's Content-Length becomes its size for the progress/duplicate checks.
     */
    private fun offerSidecar() {
        val target = queueTarget()
        val sc = target.sidecarCandidate() ?: return
        val url = PathAddressing.byPath(target.storage, sc.path)
        Thread {
            val len = http.head(url)
            if (len < 0) return@Thread                    // no companion file on the card
            main.post {
                if (!queued) return@post                  // user de-queued while the HEAD was in flight
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.sidecar_title)
                    .setMessage(getString(R.string.sidecar_msg, sc.ext, humanSize(len)))
                    .setPositiveButton(android.R.string.yes) { _, _ ->
                        dev.konraditurbe.osmosis.net.PreviewNav.setQueued
                            ?.invoke(sc.path, true, null, sc.copy(sizeBytes = len))
                        Toast.makeText(this, getString(R.string.sidecar_added, sc.ext), Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(android.R.string.no, null)
                    .show()
            }
        }.start()
    }

    /** Drop a companion when its parent is de-queued — silent, and a no-op if it was never added. */
    private fun dropSidecar() {
        val sc = queueTarget().sidecarCandidate() ?: return
        dev.konraditurbe.osmosis.net.PreviewNav.setQueued?.invoke(sc.path, false, null, null)
    }

    private fun humanSize(b: Long): String = when {
        b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
        b >= 1_000_000 -> "%.0f MB".format(b / 1e6)
        else -> "%d KB".format(b / 1000)
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
            imageLoader.load(PathAddressing.byPath(file.storage, thumbPath), iv)
        }
        updateBurstSelection()
    }

    private fun selectFrame(i: Int) {
        if (i == selectedFrame || i !in groupPaths.indices) return
        selectedFrame = i
        updateBurstSelection()
        loadPhoto()          // re-fetch the chosen frame (spinner shows while it loads)
        refreshQueueButton() // a different frame may already be saved (or not)
        if (queued) commitQueue()   // the queued frame changed — keep the grid's queue in sync
    }

    private fun updateBurstSelection() {
        val accent = ContextCompat.getColor(this, R.color.osmo_accent)
        for (i in 0 until burstRow.childCount) {
            burstRow.getChildAt(i).setBackgroundColor(if (i == selectedFrame) accent else 0x00000000)
        }
    }

    /**
     * URL of the frame currently shown — a group's selected frame, or the single file. Burst frames are
     * addressed by path because they are enumerated after the manifest, and burst groups only exist on
     * the path-based cameras.
     */
    private fun currentUrl(): String =
        if (groupPaths.isNotEmpty()) PathAddressing.byPath(file.storage, groupPaths[selectedFrame])
        else file.urlPath()

    private fun hasTrim() = trimStartMs >= 0 && trimEndMs > trimStartMs

    private fun queueLabel() = when {
        queued -> getString(R.string.remove_from_queue)
        hasTrim() -> getString(R.string.add_to_queue_trimmed)
        else -> getString(R.string.add_to_queue)
    }

    // Name -> "already fully saved" — checked off the UI thread, cached (per burst frame / the single file).
    private val downloadedCache = HashMap<String, Boolean>()
    private val savedUriCache = HashMap<String, android.net.Uri?>()

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
                // One query answers both questions — the row's existence grays out Download, and its id
                // is the Uri that Share/Edit hand to the other app.
                val uri = dev.konraditurbe.osmosis.net.MediaDownloader.downloadedUri(this, f)
                main.post {
                    downloadedCache[name] = uri != null
                    savedUriCache[name] = uri
                    refreshQueueButton()
                }
            }.start()
        }
        val alreadySaved = !hasTrim() && downloadedCache[name] == true
        btnQueue.isEnabled = !alreadySaved
        btnQueue.alpha = if (alreadySaved) 0.5f else 1f
        btnQueue.text = if (alreadySaved) getString(R.string.already_downloaded) else queueLabel()

        // Share/Edit act on the saved copy, so they only exist once there is one — and they follow the
        // title overlay, since a tap-to-hide should clear the frame completely.
        savedUri = savedUriCache[name]
        savedActions.visibility =
            if (savedUri != null && topInfo.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    /**
     * Hand the saved copy to another app: [Intent.ACTION_SEND] for the share sheet, [Intent.ACTION_EDIT]
     * for editors (Snapseed and friends register for it).
     *
     * Both go through a chooser rather than a default, and both grant read — plus write for EDIT, since
     * an editor saving in place needs it. Only ever a MediaStore Uri, which is grantable as-is.
     */
    private fun sendSavedCopy(action: String) {
        val uri = savedUri ?: return
        val mime = dev.konraditurbe.osmosis.net.MediaDownloader.mimeOf(currentCheckFile())
        val intent = Intent(action).apply {
            if (action == Intent.ACTION_SEND) {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        val title = if (action == Intent.ACTION_SEND) getString(R.string.share) else getString(R.string.edit_with)
        val noAppRes = if (action == Intent.ACTION_SEND) R.string.no_app_available_share else R.string.no_app_available_edit
        runCatching { startActivity(Intent.createChooser(intent, title)) }
            .onFailure { toast(getString(noAppRes)) }
    }

    /** Wire the custom player (transport + scrubber) and trim buttons — once. Trim *values* are (re)set
     *  per item in loadCurrent(); a mark change on an already-queued clip updates its stored trim live. */
    private fun setupTrimListeners() {
        btnPlay.setOnClickListener { togglePlay() }
        btnRew.setOnClickListener { seekBy(-5000) }
        btnFf.setOnClickListener { seekBy(5000) }
        // Dragging moves the *preview*, not the player: the frame under the thumb shows in a bubble
        // over the bar and the clip jumps once, on release. Seeking live used to fire a seek per
        // pixel of travel, each one a fresh range fetch over the camera's AP.
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                txtCur.text = mmss(progress.toLong())
                showScrubPreview(progress)
                main.removeCallbacks(scrubRefine)
                main.postDelayed(scrubRefine, 140)   // sharpen only once the thumb settles
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                scrubbing = true
                showScrubPreview(sb.progress)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                scrubbing = false
                hideScrubPreview()
                seekToMs(sb.progress.toLong())
            }
        })

        btnMarkIn.setOnClickListener {
            if (videoView.isPlaying) { toast(getString(R.string.pause_then_set_start)); return@setOnClickListener }
            trimStartMs = videoView.currentPosition.toLong()
            if (trimEndMs in 0..trimStartMs) trimEndMs = -1L // stale end now before start
            updateTrimUi()
            if (queued) commitQueue()
        }
        btnMarkOut.setOnClickListener {
            if (videoView.isPlaying) { toast(getString(R.string.pause_then_set_end)); return@setOnClickListener }
            if (trimStartMs < 0) { toast(getString(R.string.set_start_first)); return@setOnClickListener }
            val pos = videoView.currentPosition.toLong()
            if (pos <= trimStartMs) { toast(getString(R.string.end_after_start)); return@setOnClickListener }
            trimEndMs = pos
            updateTrimUi()
            if (queued) commitQueue()
        }
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

    /** Tap the media to hide the title + controls for a full-frame view; tap again to bring them back.
     *  The scrubber/transport/trim block is video-only, but the bar itself carries the queue button,
     *  so a photo gets it back on the second tap like everything else does. */
    private fun toggleControls() {
        val show = topInfo.visibility != View.VISIBLE
        topInfo.visibility = if (show) View.VISIBLE else View.GONE
        savedActions.visibility = if (show && savedUri != null) View.VISIBLE else View.GONE
        controls.visibility = if (show) View.VISIBLE else View.GONE
        trimRow.visibility = if (show && file.isVideo) View.VISIBLE else View.GONE
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

    /**
     * Start decoding preview frames for the clip we're actually streaming (see [ScrubFrames]): a
     * coarse grid up front so the bubble is never empty, then sharper frames on demand as the thumb
     * settles. Short clips get none — the whole thing fits in a flick of the bar.
     *
     * Deliberately *after* the player is prepared, and delayed on top of that, so the extra decoder
     * isn't competing with the player's initial buffering for the camera's link.
     */
    private fun startScrubFrames(streamPath: String) {
        scrubFrames.close()
        val durMs = videoView.duration.toLong()
        if (durMs < MIN_SCRUB_MS) return
        val at = navIndex
        val cellW = scrubImage.layoutParams.width
        val cellH = scrubImage.layoutParams.height
        main.postDelayed({
            if (isFinishing || navIndex != at) return@postDelayed
            scrubFrames.open("http://$ip$streamPath", cellW, cellH) { ms, bmp ->
                // Only the frame for where the thumb *is* right now — anything else has been
                // overtaken by the drag, and the grid arriving mid-playback isn't shown at all.
                if (scrubbing && ms == seekBar.progress.toLong()) showScrubFrame(bmp)
            }
            scrubFrames.prefetch(durMs, SCRUB_GRID_CELLS)
        }, 600)
    }

    /** Float the bubble over the seekbar thumb at [posMs] with the closest frame we have so far. */
    private fun showScrubPreview(posMs: Int) {
        scrubTime.text = mmss(posMs.toLong())
        showScrubFrame(scrubFrames.nearest(posMs.toLong()))
        scrubPreview.visibility = View.VISIBLE
        positionScrubPreview(posMs)
    }

    /** Null (nothing decoded yet, or the source never opened) collapses the bubble to a time chip
     *  rather than leaving an empty grey rectangle hanging over the bar. */
    private fun showScrubFrame(bmp: Bitmap?) {
        scrubImage.setImageBitmap(bmp)
        scrubImage.visibility = if (bmp != null) View.VISIBLE else View.GONE
    }

    private fun hideScrubPreview() {
        main.removeCallbacks(scrubRefine)
        scrubPreview.visibility = View.GONE
        scrubImage.setImageBitmap(null)
    }

    /** Centre the bubble on the seekbar thumb, clamped inside the screen, sitting just above the bar.
     *  Measured against the root because the bar is nested a few layouts deep in the bottom overlay. */
    private fun positionScrubPreview(posMs: Int) {
        val bar = IntArray(2).also { seekBar.getLocationInWindow(it) }
        val root = IntArray(2).also { previewRoot.getLocationInWindow(it) }
        val track = seekBar.width - seekBar.paddingLeft - seekBar.paddingRight
        val frac = if (seekBar.max > 0) posMs.toFloat() / seekBar.max else 0f
        val thumbX = bar[0] - root[0] + seekBar.paddingLeft + track * frac

        if (scrubPreview.width == 0) scrubPreview.measure(0, 0)   // first show, never laid out yet
        val w = scrubPreview.width.takeIf { it > 0 } ?: scrubPreview.measuredWidth
        val h = scrubPreview.height.takeIf { it > 0 } ?: scrubPreview.measuredHeight
        val margin = 8 * resources.displayMetrics.density
        val maxX = (previewRoot.width - w - margin).coerceAtLeast(margin)
        scrubPreview.translationX = (thumbX - w / 2f).coerceIn(margin, maxX)
        scrubPreview.translationY = (bar[1] - root[1] - h - margin).coerceAtLeast(0f)
    }

    /** Once the thumb stops moving, pull the keyframe actually under it (debounced from the drag). */
    private val scrubRefine = Runnable { if (scrubbing) scrubFrames.request(seekBar.progress.toLong()) }

    private fun seekToMs(ms: Long) {
        val t = ms.toInt()
        videoView.seekTo(t)
        seekBar.progress = t
        txtCur.text = mmss(ms)
    }

    private fun loadVideo() {
        // Resolution comes straight from the manifest (res-index enum, marker-1) — no moov, and the name
        // is a lookup, never arithmetic (see VideoFormats). A size with no name shows "?"; add the code
        // to resolutionForIndex, and its name to VideoFormats, when a new one is seen.
        resTag = dev.konraditurbe.osmosis.core.VideoFormats.label(file.resolution)
        renderTop()                                       // onCreate already drew the top bar
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
            startScrubFrames(path)   // the candidate that actually opened, so previews match the stream
        }
        videoView.setOnErrorListener { _, what, extra ->
            Log.i("Osmosis", "preview ERROR what=$what extra=$extra (candidate ${streamIdx + 1}/${streamCandidates.size}: $path)")
            if (streamIdx < streamCandidates.size - 1) {
                streamIdx++
                Log.i("Osmosis", "preview falling back to ${streamCandidates[streamIdx]}")
                startStream(streamCandidates[streamIdx])
                return@setOnErrorListener true
            }
            showStatus(getString(R.string.cant_play_clip, what, extra))
            true
        }
    }

    private fun loadPhoto() {
        val dm = resources.displayMetrics
        val frame = selectedFrame            // guard against a fast frame switch racing the fetch
        val at = navIndex                    // …or a swipe to another item
        // A drone serves a screen-res render of a still (`file_subtype=2`), so try that before pulling a
        // full ~14 MB frame just to downsample it for the display. Cameras have no such rendition — they
        // keep the single full-res URL. Burst frames address a specific path, so they bypass this.
        val urls = if (groupPaths.isEmpty() && file.isIndexed) file.previewCandidates() else listOf(currentUrl())
        spinner.visibility = ProgressBar.VISIBLE
        statusText.visibility = TextView.GONE
        Thread {
            val bytes = urls.firstNotNullOfOrNull { runCatching { http.getBytes(it) }.getOrNull() }
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
                if (isFinishing || frame != selectedFrame || navIndex != at) return@post   // superseded
                renderTop()
                if (bmp != null) {
                    photoView.setImageBitmap(bmp)
                    photoZoom?.reset()   // a new bitmap starts fitted, never inheriting the last one's zoom
                    spinner.visibility = ProgressBar.GONE
                    photoView.visibility = ImageView.VISIBLE
                } else showStatus(getString(R.string.preview_unavailable))
            }
        }.start()
    }

    private fun showStatus(msg: String) {
        spinner.visibility = ProgressBar.GONE
        statusText.text = msg
        statusText.visibility = TextView.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        if (videoView.isPlaying) { videoView.pause(); updatePlayIcon() }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(tick)
        runCatching { videoView.stopPlayback() }
        scrubFrames.close()
        imageLoader.shutdown()
    }

    companion object {
        /** Below this the whole clip fits in a flick of the bar, so a scrub preview earns nothing. */
        private const val MIN_SCRUB_MS = 6_000L
        /** Coarse grid decoded up front — enough that any thumb position has a frame within a few
         *  percent of the clip, without a long stall on the camera's link before the first drag. */
        private const val SCRUB_GRID_CELLS = 12

        const val EXTRA_PATH = "path"
        private const val EXTRA_STORAGE = "storage"
        private const val EXTRA_SIZE = "size"    // full manifest byte size → already-downloaded check
        private const val EXTRA_HANDLE = "handle"    // manifest handle → delete / favourite
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
