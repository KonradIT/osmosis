package dev.konraditurbe.osmosis.core

/** One media item on the camera, with its media path and derived thumbnail path. */
data class CameraFile(
    val path: String,        // e.g. DCIM/DJI_001/DJI_20260329115359_0211_D.MP4
    val thumbPath: String,   // e.g. MISC/THM/DJI_001/DJI_20260329115359_0211_D.scr
    val storage: Int = 0,    // the camera mount index that serves this path (resolved by probing —
                             // NOT a fixed SD/internal mapping: an Xtra served its SD at 0, internal at 1)
    val resLabel: String? = null, // e.g. "25fps" — fps from the DUML manifest record
    val proxyPath: String? = null, // low-res proxy clip (.LRF/.LRV) if the camera lists one
    val handle: Long = 0L,   // camera-assigned delete handle (DUML 0x00/0x28); 0 = unknown → not deletable
    val sizeBytes: Long = 0L, // full media byte size from the DUML manifest (record marker-12); 0 = unknown (probe HTTP)
    val starred: Boolean = false, // ⭐ favourite flag from the manifest (marker+10, video records)
    val resolution: String? = null, // "3840x2160": video from the res-index enum (marker-1); photo from
                                    // its direct pixel W×H (marker+58/+62); null = unknown
    val durationSec: Int = 0, // video length in whole seconds, from the DUML manifest (marker+26); 0 = unknown
    // Which per-storage list of the manifest this record came from (0 = first, 1 = second). A camera
    // with a card returns TWO lists back to back — SD first, then internal — and every file in a list
    // lives on the same store, so the caller resolves [storage] once per group instead of once per
    // manifest (which used to stamp one store on everything and 404 the other half).
    val group: Int = 0,
) {
    /** True once the manifest yielded a delete handle for this file (see DatalinkClient.deleteFiles). */
    val deletable: Boolean get() = handle != 0L

    val name: String get() = path.substringAfterLast('/')
    val ext: String get() = name.substringAfterLast('.', "").uppercase()
    val timestamp: String get() = Regex("""_(\d{14})_""").find(name)?.groupValues?.get(1) ?: ""
    val seq: Int get() = Regex("""_(\d{4})_D""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** 3-digit burst/interval sub-index (`…_0286_D_001.JPG` → 1), or 0 if this isn't a group frame. */
    val subIndex: Int get() = Regex("""_(\d{3})\.\w+$""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    /** The shared name-prefix that groups burst/interval frames (everything before `_NNN.ext`), or null. */
    val groupKey: String? get() = Regex("""^(.+)_\d{3}\.\w+$""").find(name)?.groupValues?.get(1)
    /** A burst/interval frame — its name carries a `_NNN` sub-index. The manifest lists ONLY the group's
     *  first frame (`…_0286_D_001.JPG`) and standalone photos have no suffix, so this is the reliable
     *  burst signal (→ the 🎞️ badge). The other frames aren't in the manifest — the viewer enumerates
     *  them by probing `_002…` on open (see MediaPreviewActivity.probeBurstFrames). */
    val isBurst: Boolean get() = groupKey != null

    /** Human date from the 14-digit timestamp: "2026-07-09 19:56". */
    val dateTaken: String get() = timestamp.takeIf { it.length == 14 }?.let {
        "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6, 8)} ${it.substring(8, 10)}:${it.substring(10, 12)}"
    } ?: ""

    fun urlPath(): String = "/v2?storage=$storage&path=$path"
    fun thumbUrlPath(): String = "/v2?storage=$storage&path=$thumbPath"

    /** URL of the low-res proxy for preview, or null if the camera doesn't provide one. */
    fun proxyUrlPath(): String? = proxyPath?.let { "/v2?storage=$storage&path=$it" }

    /**
     * Low-res proxy extension pinned by camera family, read from the file-naming prefix (which is the
     * family tell): the **Action family** (`CAM_` — Xtra / Action 5 Pro, and the 360) writes an
     * **unlisted `.XRF`** sidecar; **DJI-proper** (`DJI_` — Nano) uses `.LRF`. Null for an unknown
     * convention → no derived proxy, just stream the full-res file.
     */
    private fun proxyExt(): String? = when {
        name.startsWith("CAM_") -> "XRF"
        name.startsWith("DJI_") -> "LRF"
        else -> null
    }

    /**
     * Ordered URLs to try when streaming a *preview*, cheapest (smallest) first:
     *  1. the manifest-listed proxy (the Nano/360 list their `.LRF`/`.LRV`);
     *  2. else the **derived** sidecar proxy for this camera family — the Xtra / Action 5 Pro writes a
     *     low-res `.XRF` next to each clip (same base name + folder) but does NOT list it in the
     *     manifest, so we derive the path directly instead of probing several extensions;
     *  3. the full-resolution file, as a last resort.
     * Streaming the small proxy also dodges hardware-decoder limits (full-res 4:3 4K HEVC won't decode
     * on weaker devices). Duplicates collapse, so this is normally just [proxy, full-res].
     */
    fun previewCandidates(): List<String> {
        val urls = LinkedHashSet<String>()
        proxyUrlPath()?.let { urls.add(it) }
        proxyExt()?.let { urls.add("/v2?storage=$storage&path=${path.substringBeforeLast('.', path)}.$it") }
        urls.add(urlPath())
        return urls.toList()
    }

    val isVideo: Boolean get() = ext in setOf("MP4", "MOV", "OSV", "INSV", "LRF", "LRV", "XRF")
    val isImage: Boolean get() = ext in setOf("JPG", "JPEG", "DNG", "HEIC", "RAW")
}
