package dev.konraditurbe.osmosis.core

/**
 * Live camera status decoded off the DUML datalink (battery, power, storage, firmware).
 *
 * The power fields come from the `0x0d/0x02` battery push, mapped by docking/undocking a Nano
 * mid-session and watching which bytes moved (MEDIA_PROTOCOL.md §20). Note the dock's *own* charge is not
 * reported anywhere in the protocol — only the camera's, plus whether the dock is attached/charging.
 */
data class CameraStatus(
    val batteryPercent: Int = -1,     // -1 = unknown
    val storageFreeMb: Int = -1,      // free space of the active store (MiB), -1 = unknown
    val storageTotalMb: Int = -1,     // total capacity of the active store (MiB), -1 = unknown
    // Both stores, from the 0x02/0xDC push, which carries a block per store (see DatalinkClient).
    // The card is absent exactly when its capacity reads 0 — byte 0 of that frame is NOT an
    // inserted flag (it read 0x11 on a camera with no card and 0x00 on two cameras with one).
    val sdTotalMb: Int = -1,          // SD capacity (MiB); 0 = no card, -1 = not reported yet
    val sdFreeMb: Int = -1,
    val internalTotalMb: Int = -1,    // built-in storage (MiB)
    val internalFreeMb: Int = -1,
    val firmware: String? = null,
    val batteryMilliVolts: Int = -1,  // pack voltage (u16 @1), -1 = unknown
    val batteryMilliAmps: Int = 0,    // signed current (i32 @5): +charging / -discharging
    val docked: Boolean = false,      // dock physically attached (@27 != 0)
    val charging: Boolean = false,    // actually taking charge (@32 == 1)
) {
    /** True once we've decoded a battery frame with the power fields. */
    val hasPowerInfo: Boolean get() = batteryMilliVolts > 0

    /** A card is in exactly when the camera reports a non-zero capacity for it. */
    val sdInserted: Boolean get() = sdTotalMb > 0
}
