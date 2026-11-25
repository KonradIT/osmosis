package dev.konraditurbe.osmosis.core

import dev.konraditurbe.osmosis.camera.PathAddressing
import dev.konraditurbe.osmosis.dcf.DcfAddressing

/** Maps an album item to its original, thumbnail and preview URLs on the camera. */
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
        fun of(f: CameraFile): MediaAddressing = if (f.isIndexed) DcfAddressing else PathAddressing
    }
}

// The call-site front door. Kept as extensions rather than members so [CameraFile] stays pure data
// with no idea that HTTP, `/v1` or `/v2` exist.

fun CameraFile.urlPath(): String = MediaAddressing.of(this).original(this)

fun CameraFile.thumbUrlPath(): String = MediaAddressing.of(this).thumbnail(this)

fun CameraFile.previewCandidates(): List<String> = MediaAddressing.of(this).previewChain(this)
