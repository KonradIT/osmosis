package dev.konraditurbe.osmosis.dcf

import dev.konraditurbe.osmosis.camera.PathAddressing
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.MediaAddressing

/** Addresses indexed album records using the HTTP route selected for the camera. */
object DcfAddressing : MediaAddressing {

    enum class Route { V1, V2, STATIC }

    override fun original(f: CameraFile): String = when (f.dcfRoute) {
        Route.V1 -> DcfUrls.of(f.fileIndex, DcfUrls.ORG, f.segmentIndex)
        Route.V2 -> PathAddressing.byPath(f.storage, f.path)
        Route.STATIC -> "/${f.path}"
    }

    /**
     * A video's thumbnail is its [THM][DcfUrls.THM]. **A still has no rendition on the card at all** —
     * probed on a Mavic 3, only `file_subtype=0` answers for a photo index; every other subtype makes
     * the server close the connection, which is how this firmware reports a missing file (there is no
     * 404 — a failed lookup returns HANDLER_ERROR and the connection dies). So a still's thumbnail is
     * taken from the EXIF block inside the original, via one ranged request for its first 64 kB.
     *
     * Both are plain HTTP and parallelise across the loader's pool. The reference app instead pulls
     * every thumbnail over the datalink, which is strictly one-at-a-time and leases a transfer slot per
     * image — the source of a long-running stall in the grid. This is a deliberate deviation.
     */
    override fun thumbnail(f: CameraFile): String = when {
        f.dcfRoute == Route.V1 && f.isVideo -> DcfUrls.of(f.fileIndex, DcfUrls.THM, f.segmentIndex)
        f.ext == "JPG" -> CameraFile.EXIF_THUMB + original(f)
        else -> ""
    }

    /**
     * Videos take the low-res [LRF][DcfUrls.LRF] proxy — measured ~7× smaller than the original and the
     * only reason scrubbing is usable. Stills take the screen-res [SCR][DcfUrls.SCR] render, which on a
     * 14 MP frame is the difference between a snappy preview and decoding ~14 MB. Both fall back to the
     * original.
     */
    override fun previewChain(f: CameraFile): List<String> =
        if (f.dcfRoute == Route.V1)
            listOf(DcfUrls.of(f.fileIndex, if (f.isVideo) DcfUrls.LRF else DcfUrls.SCR, f.segmentIndex), original(f))
        else listOf(original(f))
}
