package com.chernowii.osmosis.core

/** One media item on the camera, with its media path and derived thumbnail path. */
data class CameraFile(
    val path: String,       // e.g. DCIM/DJI_001/DJI_20260329115359_0211_D.MP4
    val thumbPath: String,  // e.g. MISC/THM/DJI_001/DJI_20260329115359_0211_D.scr
    val storage: Int = 0,   // 0 = internal, 1 = SD
) {
    val name: String get() = path.substringAfterLast('/')
    val ext: String get() = name.substringAfterLast('.', "").uppercase()
    val timestamp: String get() = Regex("""_(\d{14})_""").find(name)?.groupValues?.get(1) ?: ""
    val seq: Int get() = Regex("""_(\d{4})_D""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    fun urlPath(): String = "/v2?storage=$storage&path=$path"
    fun thumbUrlPath(): String = "/v2?storage=$storage&path=$thumbPath"

    val isVideo: Boolean get() = ext in setOf("MP4", "MOV", "OSV", "INSV", "LRF")
    val isImage: Boolean get() = ext in setOf("JPG", "JPEG", "DNG", "HEIC", "RAW")
}
