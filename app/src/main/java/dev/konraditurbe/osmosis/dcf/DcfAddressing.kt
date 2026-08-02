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
     * A video's thumbnail is its [THM][DcfUrls.THM] over HTTP. **A still has no rendition on the card
     * at all** — no THM, no SCR, no AIS, no LRF — so it can only come over the datalink, and is
     * addressed with the [DUML_THUMB][CameraFile.DUML_THUMB] pseudo-URL that `ImageLoader` routes there.
     *
     * Probed against a Mavic 3: for a photo index, only `file_subtype=0` answers; every other subtype
     * makes the server close the connection with no response, which is how this firmware reports a
     * missing file (there is no 404 — a failed lookup returns HANDLER_ERROR and the connection dies).
     * The reference app fetches *every* thumbnail, video and still alike, over the datalink and never
     * requests subtype 1 or 2 over HTTP at all; keeping HTTP for videos is a deliberate deviation,
     * because it parallelises where the datalink is one-at-a-time.
     */
    override fun thumbnail(f: CameraFile): String =
        if (f.isVideo) DcfUrls.of(f.fileIndex, DcfUrls.THM)
        else CameraFile.DUML_THUMB + f.fileIndex

    /**
     * Videos take the low-res [LRF][DcfUrls.LRF] proxy — measured ~7× smaller than the original and the
     * only reason scrubbing is usable. Stills take the screen-res [SCR][DcfUrls.SCR] render, which on a
     * 14 MP frame is the difference between a snappy preview and decoding ~14 MB. Both fall back to the
     * original.
     */
    override fun previewChain(f: CameraFile): List<String> =
        listOf(DcfUrls.of(f.fileIndex, if (f.isVideo) DcfUrls.LRF else DcfUrls.SCR), original(f))
}
