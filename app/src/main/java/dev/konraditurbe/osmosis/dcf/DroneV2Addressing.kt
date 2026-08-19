package dev.konraditurbe.osmosis.dcf

import dev.konraditurbe.osmosis.camera.PathAddressing
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.MediaAddressing

/**
 * An aircraft that indexes media by DCF number but serves the bytes **by path over `/v2`** — the
 * majority of current DJI drones ([DroneProducts][dev.konraditurbe.osmosis.drone.DroneProducts]).
 *
 * It is neither of the other two schemes, which is why it needs its own object:
 *
 * - [DcfAddressing] addresses renditions as *subtypes of one index* over `/v1`. These aircraft install
 *   no `/v1` at all, so every one of those URLs is unserved.
 * - [PathAddressing] takes its thumbnail and proxy paths from a manifest that listed them. A drone
 *   catalogue lists neither, and `thumbPath` is just a copy of the media path — so using it directly
 *   would fetch the **full original for every grid cell**.
 *
 * So the original is exact and the renditions are derived, in the same way the Xtra's unlisted `.XRF`
 * proxy is derived from its media path.
 *
 * ⚠️ Untested: no `/v2` aircraft has yet returned a catalogue. The original is the confident part; the
 * two derived extensions are read from how the reference app builds non-original renditions (same
 * record path, extension swapped for the type it wants).
 */
object DroneV2Addressing : MediaAddressing {

    private const val THUMB_EXT = "THM"
    private const val PROXY_EXT = "LRF"
    private const val SCREEN_EXT = "SCR"

    /**
     * An aircraft wants an **absolute** path — `/DCIM/…`, leading slash — where a camera wants a
     * relative one. The reference implementation rejects a `/v2` request outright if the path does not
     * start with `/`, and its own paths come from the aircraft's catalogue, which evidently stores them
     * that way. Our DCF paths are synthesised relative, so add it here rather than change a shape the
     * cameras already work with.
     */
    private fun absolute(path: String) = if (path.startsWith("/")) path else "/$path"

    override fun original(f: CameraFile): String = PathAddressing.byPath(f.storage, absolute(f.path))

    private fun sibling(f: CameraFile, ext: String): String =
        PathAddressing.byPath(f.storage, absolute("${f.path.substringBeforeLast('.', f.path)}.$ext"))

    /**
     * A video's `.THM` sidecar; a still's own embedded EXIF thumbnail.
     *
     * **Never the original.** A wrong sidecar URL costs one failed request and an empty cell, which is
     * recoverable; falling back to the original would pull the whole clip for every cell in the grid.
     * The EXIF route needs no sidecar to exist at all — it is one ranged request for the first 64 kB of
     * the original — so a still's thumbnail works whatever this firmware does or doesn't store.
     */
    override fun thumbnail(f: CameraFile): String =
        if (f.isVideo) sibling(f, THUMB_EXT)
        else CameraFile.EXIF_THUMB + original(f)

    /**
     * Cheapest first: a video's low-res `.LRF` proxy, a still's screen-res `.SCR` render, then the
     * original. Here the original *is* a sane last resort — a preview is one file the user asked to
     * open, not a gridful.
     */
    override fun previewChain(f: CameraFile): List<String> =
        listOf(sibling(f, if (f.isVideo) PROXY_EXT else SCREEN_EXT), original(f))
}
