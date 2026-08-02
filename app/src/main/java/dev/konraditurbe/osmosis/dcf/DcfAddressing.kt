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

    override fun thumbnail(f: CameraFile): String = DcfUrls.of(f.fileIndex, DcfUrls.THM)

    /**
     * Videos take the low-res [LRF][DcfUrls.LRF] proxy — measured ~7× smaller than the original and the
     * only reason scrubbing is usable. Stills take the screen-res [SCR][DcfUrls.SCR] render, which on a
     * 14 MP frame is the difference between a snappy preview and decoding ~14 MB. Both fall back to the
     * original.
     */
    override fun previewChain(f: CameraFile): List<String> =
        listOf(DcfUrls.of(f.fileIndex, if (f.isVideo) DcfUrls.LRF else DcfUrls.SCR), original(f))
}
