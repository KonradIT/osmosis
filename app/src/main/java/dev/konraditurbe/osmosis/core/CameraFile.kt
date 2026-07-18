package dev.konraditurbe.osmosis.core

/** One media item on the camera, with its media path and derived thumbnail path. */
data class CameraFile(
    val path: String,        // e.g. DCIM/DJI_001/DJI_20260329115359_0211_D.MP4
    val thumbPath: String,   // e.g. MISC/THM/DJI_001/DJI_20260329115359_0211_D.scr
    val storage: Int = 0,    // 0 = internal, 1 = SD
    val resLabel: String? = null, // e.g. "25fps" — fps from the DUML manifest record
    val proxyPath: String? = null, // low-res proxy clip (.LRF/.LRV) if the camera lists one
) {
    val name: String get() = path.substringAfterLast('/')
    val ext: String get() = name.substringAfterLast('.', "").uppercase()
    val timestamp: String get() = Regex("""_(\d{14})_""").find(name)?.groupValues?.get(1) ?: ""
    val seq: Int get() = Regex("""_(\d{4})_D""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

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
