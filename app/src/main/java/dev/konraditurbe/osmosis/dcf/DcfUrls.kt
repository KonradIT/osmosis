package dev.konraditurbe.osmosis.dcf

/**
 * The `/v1` HTTP media API — how DCF-indexed devices serve media, in place of the path-based
 * `/v2?storage=N&path=…` every other camera in this app uses.
 *
 * ```
 * GET /v1?file_index=<packed u32>&file_subtype=<S>&file_seg_subindex=<G>
 * ```
 *
 * All three parameters are expected: some models close the connection without a response when one is
 * missing, even though the Mavic 3 tolerates two. The URL carries **no extension** — the server probes
 * them itself (`.JPG .jpg .MP4 .mp4 .MOV .mov .DNG .dng` for [ORG]; `.LRF/.THM/.SCR` and lowercase for
 * the rest), which is why [DcfIndex.path] is display naming only and never a request.
 *
 * `file_seg_subindex` selects a part of a segmented recording; `0` means the whole file.
 *
 * See MEDIA_PROTOCOL.md § "HTTP media API (`/v1`) — DCF indexed".
 */
object DcfUrls {

    /** Original full-res media — the download. Lives at `DCIM/<dir>MEDIA/`. */
    const val ORG = 0

    /** Thumbnail. Lives at `MISC/THM/<dir>/`. */
    const val THM = 1

    /** Screen-res render — a still's cheap preview (~1 MB vs ~14 MB for the original). */
    const val SCR = 2

    /** Sensor/AI data sidecar. Not consumed yet. */
    const val AIS = 17

    /**
     * Low-res proxy video (`.LRF`/`.LRV`) — measured ~7× smaller than the original (38.8 MB vs 273 MB on
     * a 30 s 5.1K clip) and decoding at 1280×720. It's what the reference app range-requests for playback
     * and scrubbing; [ORG] is fetched only for a download.
     */
    const val LRF = 18

    fun of(fileIndex: Long, subtype: Int = ORG, seg: Int = 0): String =
        "/v1?file_index=$fileIndex&file_subtype=$subtype&file_seg_subindex=$seg"
}
