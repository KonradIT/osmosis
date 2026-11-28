package dev.konraditurbe.osmosis.core

/** JPEG renditions selected by the Action album file request. Size limits bound in-memory transfers. */
enum class AlbumJpeg(val subtype: Int, val maxBytes: Long) {
    THUMBNAIL(1, 2L * 1024 * 1024),
    SCREEN(2, 8L * 1024 * 1024),
}

/**
 * A live connection to a device's media library — what the UI talks to, so it never has to ask whether
 * it is holding a camera or a drone.
 *
 * The two implementations have almost nothing in common below this line: an Osmo camera answers a
 * CompositePack manifest of paths and pages through a fresh playback session, while a drone must be
 * unlocked with a `0x51` handshake and then answers flat DCF records on the live session. Both are
 * "give me the newest page, then older ones", which is all the grid ever needed.
 *
 * Device-specific capabilities default to a no-op rather than throwing: a drone has no burst groups or
 * highlights, each camera selects its supported album transport. Callers can invoke
 * them unconditionally.
 */
interface MediaSession {

    /** Live battery/storage/firmware, as status pushes are decoded. */
    var onStatus: ((CameraStatus) -> Unit)?

    /** Progress 0..100 through [fetchFileList], for the connect progress bar. */
    var onFetchProgress: ((Int) -> Unit)?

    /**
     * False when the datalink handshake never landed — i.e. the wrong UDP port for this device. Lets
     * the caller tell "no media" apart from "nothing answered" and retry the alternate port.
     */
    val handshakeOk: Boolean

    /** Another, older page is likely to exist — drives the grid's infinite scroll. */
    val moreAvailable: Boolean

    /** Whether a listed original passed the session's HTTP check. Null if no check was needed. */
    val httpServing: Boolean? get() = null

    /** Available for listed Action 1 originals when the camera does not serve them over HTTP. */
    val datalinkOriginals: Boolean get() = false

    val datalinkJpegs: Boolean get() = false

    /** Camera-generated movie preview over the paired album session. */
    val datalinkVideo: Boolean get() = false

    fun openAlbumVideo(file: CameraFile, listener: AlbumVideo.Listener): AlbumVideo? = null

    fun fetchAlbumJpeg(file: CameraFile, rendition: AlbumJpeg): ByteArray? = null

    fun downloadOriginal(file: CameraFile, offset: Long, output: java.io.OutputStream,
                         progress: (Long) -> Unit): Boolean = false

    /** Connect and return the newest page. Empty on failure. Blocking. */
    fun fetchFileList(ip: String): List<CameraFile>

    /** The next older page, or empty once exhausted. Returns only newly-seen files. Blocking. */
    fun fetchNextPage(): List<CameraFile>

    /** Hold the link up during browse/download — some devices sleep their AP the moment it goes idle. */
    fun startKeepAlive()

    fun close()

    // ---- optional, per device family ---------------------------------------------------------------

    /** Enumerate a burst/interval group's frames. Path-based cameras only. */
    fun expandBurstGroup(lead: CameraFile): List<CameraFile> = listOf(lead)

    /** Highlight marker positions, in **milliseconds**. Path-based cameras only. */
    fun getHighlights(handle: Long): List<Int> = emptyList()

    /** Delete by manifest handle; the reply status word (0 = OK), or null. Path-based cameras only. */
    fun deleteFiles(handles: List<Long>): Int? = null

    /** Toggle the ⭐ flag. Path-based cameras only. */
    fun setFavorite(handle: Long, on: Boolean): Boolean = false
}
