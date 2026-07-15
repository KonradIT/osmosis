package dev.konraditurbe.osmosis.core

/** One media item on the camera, with its media path and derived thumbnail path. */
data class CameraFile(
    val path: String,        // e.g. DCIM/DJI_001/DJI_20260329115359_0211_D.MP4
    val thumbPath: String,   // e.g. MISC/THM/DJI_001/DJI_20260329115359_0211_D.scr
    val storage: Int = 0,    // 0 = internal, 1 = SD
    val resLabel: String? = null, // e.g. "25fps" — fps from the DUML manifest record
    val proxyPath: String? = null, // low-res proxy clip (.LRF/.LRV) if the camera lists one
    val fileIndex: Int? = null, // older index-based cameras (e.g. Osmo Action 1): download by index
) {
    val name: String get() = path.substringAfterLast('/')
    val ext: String get() = name.substringAfterLast('.', "").uppercase()
    val timestamp: String get() = Regex("""_(\d{14})_""").find(name)?.groupValues?.get(1) ?: ""
    val seq: Int get() = Regex("""_(\d{4})_D""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** Human date from the 14-digit timestamp: "2026-07-09 19:56". */
    val dateTaken: String get() = timestamp.takeIf { it.length == 14 }?.let {
        "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6, 8)} ${it.substring(8, 10)}:${it.substring(10, 12)}"
    } ?: ""

    // Older cameras (Osmo Action 1) address files by numeric index over the legacy /v1 API; newer
    // ones use path + storage over /v2.
    fun urlPath(): String =
        fileIndex?.let { "/v1?file_index=$it" } ?: "/v2?storage=$storage&path=$path"

    /** Thumbnail URL, or "" if none is known (index-based cameras don't expose one to us yet). */
    fun thumbUrlPath(): String =
        if (fileIndex != null || thumbPath.isEmpty()) "" else "/v2?storage=$storage&path=$thumbPath"

    /** URL of the low-res proxy for preview, or null if the camera doesn't provide one. */
    fun proxyUrlPath(): String? = proxyPath?.let { "/v2?storage=$storage&path=$it" }

    val isVideo: Boolean get() = ext in setOf("MP4", "MOV", "OSV", "INSV", "LRF", "LRV")
    val isImage: Boolean get() = ext in setOf("JPG", "JPEG", "DNG", "HEIC", "RAW")
}
