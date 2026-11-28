package dev.konraditurbe.osmosis.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import dev.konraditurbe.osmosis.core.AlbumVideo
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.MediaSession

internal class ActionVideoView(
    context: Context,
    private val session: MediaSession,
    private val file: CameraFile,
    private val firstFrame: (Int, Int) -> Unit,
    private val progress: (Boolean, Int, Int) -> Unit,
    private val finished: (String?) -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener, AlbumVideo.Listener {
    private val main = Handler(Looper.getMainLooper())
    private var control: AlbumVideo? = null
    @Volatile private var decoder: ActionVideoDecoder? = null
    @Volatile private var audioPlayer: ActionPreviewAudio? = null
    @Volatile private var localPlaying = true
    @Volatile private var cameraPlaying = true
    private var closed = false
    private var displayed = false
    private var videoWidth = 0
    private var videoHeight = 0
    private val timeout = Runnable { end("no decodable camera frame (${decoder?.statistics()})") }

    init {
        isOpaque = false
        surfaceTextureListener = this
        main.postDelayed(timeout, 18000)
    }

    fun setPlaying(playing: Boolean) {
        localPlaying = playing
        audioPlayer?.setPlaying(localPlaying && cameraPlaying)
        if (playing) control?.resume() else control?.pause()
    }

    fun seek(ms: Int) {
        audioPlayer?.reset()
        control?.seek(ms)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
        if (closed) return
        val audio = ActionPreviewAudio(context.getSystemService(AudioManager::class.java)) {
            main.post { if (!closed) setPlaying(false) }
        }
        audioPlayer = audio
        decoder = ActionVideoDecoder(Surface(texture), { width, height ->
            main.post { videoWidth = width; videoHeight = height; fit() }
        }, audio::videoRendered, { error -> main.post { end(error) } })
        control = session.openAlbumVideo(file, this)
        if (control == null) end("camera session unavailable")
    }

    private fun fit() {
        if (closed || videoWidth == 0 || videoHeight == 0 || width == 0 || height == 0) return
        val scale = minOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        setTransform(Matrix().apply {
            setScale(videoWidth * scale / width, videoHeight * scale / height, width / 2f, height / 2f)
        })
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) = fit()
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (!closed && !displayed && videoWidth > 0) {
            displayed = true
            main.removeCallbacks(timeout)
            firstFrame(videoWidth, videoHeight)
        }
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        end(null)
        return true
    }

    override fun video(accessUnit: ByteArray) { decoder?.offer(accessUnit) }
    override fun audio(adts: ByteArray) { audioPlayer?.offer(adts) }
    override fun discontinuity(seeking: Boolean) {
        // Intact AAC frames on either side of a lost datagram still belong to the same timeline.
        if (seeking) audioPlayer?.reset()
        decoder?.reset()
    }
    override fun position(playing: Boolean, positionMs: Int, durationMs: Int) {
        cameraPlaying = playing
        audioPlayer?.setPlaying(localPlaying && cameraPlaying)
        main.post { if (!closed) progress(playing, positionMs, durationMs) }
    }
    override fun ended(error: String?) { main.post { end(error) } }
    private fun end(error: String?) {
        if (closed) return
        close()
        finished(error)
    }

    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        audioPlayer?.close()
        audioPlayer = null
        control?.close()
        control = null
        decoder?.close()
        decoder = null
    }
}
