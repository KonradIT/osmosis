package dev.konraditurbe.osmosis.core

/** Live camera status decoded off the DUML datalink (battery, mode, recording, storage, firmware). */
data class CameraStatus(
    val batteryPercent: Int = -1,     // -1 = unknown
    val mode: String? = null,         // "Video", "Photo", "Timelapse", …
    val recording: Boolean = false,
    val recordSeconds: Int = 0,
    val sdInserted: Boolean = false,
    val storageFreeMb: Int = -1,      // free space of the active store (MiB), -1 = unknown
    val storageTotalMb: Int = -1,     // total capacity of the active store (MiB), -1 = unknown
    val firmware: String? = null,
)
