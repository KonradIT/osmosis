package dev.konraditurbe.osmosis.core

import dev.konraditurbe.osmosis.camera.PathAddressing
import dev.konraditurbe.osmosis.dcf.DcfAddressing
import dev.konraditurbe.osmosis.dcf.DroneV2Addressing

/**
 * How a device's media is located over HTTP — the one seam between the app's two, mutually exclusive
 * addressing schemes.
 *
 * | | path-based camera | DCF-indexed device |
 * |---|---|---|
 * | who | Nano, Osmo Action 5 Pro / Xtra, the 360 | Mavic 3 family; Osmo Action 1 ([DcfRecords]) |
 * | endpoint | `/v2?storage=N&path=…` | `/v1?file_index=…&file_subtype=…` |
 * | proxy | a sidecar file (`.LRF`/`.XRF`) at its own path | a **subtype** of the same index |
 * | impl | [PathAddressing] | [DcfAddressing] |
 *
 * The two implementations share no code — that is the point of the interface. [of] is the single place
 * in the app that decides which one a file uses, and it decides from the file's own origin: a record
 * that came out of a DCF manifest carries an index, one that came out of a CompositePack carries a path.
 */
interface MediaAddressing {

    /** The full-resolution original — what a download fetches. */
    fun original(f: CameraFile): String

    /** This file's thumbnail for the grid. */
    fun thumbnail(f: CameraFile): String

    /**
     * Ordered URLs to try when streaming a *preview*, cheapest (smallest) first, ending at the
     * original. Streaming a small proxy is also what dodges hardware-decoder limits — full-res 4:3 4K
     * HEVC won't decode on weaker devices.
     */
    fun previewChain(f: CameraFile): List<String>

    companion object {
        /**
         * A DCF-indexed device is not automatically a `/v1` device.
         *
         * Most DJI aircraft index media by DCF number *and* serve it by physical path over `/v2` —
         * the same surface as an Osmo camera. Only three current aircraft install the `/v1` packed-index
         * download, the Mavic 3 among them, which is why `/v1` looked like the drone scheme: it is the
         * scheme of the one aircraft this app was built against.
         */
        fun of(f: CameraFile): MediaAddressing = when {
            f.isIndexed && f.dcfHttpV2 -> DroneV2Addressing
            f.isIndexed -> DcfAddressing
            else -> PathAddressing
        }
    }
}

// The call-site front door. Kept as extensions rather than members so [CameraFile] stays pure data
// with no idea that HTTP, `/v1` or `/v2` exist.

fun CameraFile.urlPath(): String = MediaAddressing.of(this).original(this)

fun CameraFile.thumbUrlPath(): String = MediaAddressing.of(this).thumbnail(this)

fun CameraFile.previewCandidates(): List<String> = MediaAddressing.of(this).previewChain(this)
