package dev.konraditurbe.osmosis.dcf

import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.MediaAddressing

/**
 * DCF-index addressing — drones and the Osmo Action 1, over `/v1` ([DcfUrls]).
 *
 * The distinguishing move is that a cheaper rendition is a **subtype of the same index**, not a
 * separate file at a separate path: one number addresses the original, its thumbnail, its screen-res
 * render and its low-res proxy clip. So unlike the camera scheme there is nothing to derive, probe or
 * guess — the chain is exact.
 *
 * Nothing here knows about paths, and [PathAddressing][dev.konraditurbe.osmosis.camera.PathAddressing]
 * knows nothing about indices.
 */
object DcfAddressing : MediaAddressing {

    override fun original(f: CameraFile): String = DcfUrls.of(f.fileIndex, DcfUrls.ORG)

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
    override fun thumbnail(f: CameraFile): String =
        if (f.isVideo) DcfUrls.of(f.fileIndex, DcfUrls.THM)
        else CameraFile.EXIF_THUMB + DcfUrls.of(f.fileIndex, DcfUrls.ORG)

    /**
     * Videos take the low-res [LRF][DcfUrls.LRF] proxy — measured ~7× smaller than the original and the
     * only reason scrubbing is usable. Stills take the screen-res [SCR][DcfUrls.SCR] render, which on a
     * 14 MP frame is the difference between a snappy preview and decoding ~14 MB. Both fall back to the
     * original.
     */
    override fun previewChain(f: CameraFile): List<String> =
        listOf(DcfUrls.of(f.fileIndex, if (f.isVideo) DcfUrls.LRF else DcfUrls.SCR), original(f))
}
