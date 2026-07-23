package dev.konraditurbe.osmosis.core

/**
 * Live camera status decoded off the DUML datalink (battery, power, storage, firmware).
 *
 * The power fields come from the `0x0d/0x02` battery push, mapped by docking/undocking a Nano
 * mid-session and watching which bytes moved (ROADMAP #5). Note the dock's *own* charge level is not
 * reported anywhere in the protocol — only the camera's, plus whether the dock is attached/charging.
 */
data class CameraStatus(
    val batteryPercent: Int = -1,     // -1 = unknown
    val sdInserted: Boolean = false,
    val storageFreeMb: Int = -1,      // free space of the active store (MiB), -1 = unknown
    val storageTotalMb: Int = -1,     // total capacity of the active store (MiB), -1 = unknown
    val firmware: String? = null,
    val batteryMilliVolts: Int = -1,  // pack voltage (u16 @1), -1 = unknown
    val batteryMilliAmps: Int = 0,    // signed current (i32 @5): +charging / -discharging
    val docked: Boolean = false,      // dock physically attached (@27 != 0)
    val charging: Boolean = false,    // actually taking charge (@32 == 1)
) {
    /** True once we've decoded a battery frame with the power fields. */
    val hasPowerInfo: Boolean get() = batteryMilliVolts > 0
}
