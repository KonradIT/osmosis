package dev.konraditurbe.osmosis.drone

/**
 * The WLM (wireless-link-manager) commands a drone uses to be *put into* QuickTransfer mode.
 *
 * Distinct from the Mavic 3's `0x51/0x02` session-open, and the distinction matters: that five-byte
 * open is one aircraft's observed trace, while this is the path the current DJI Fly handler
 * (`UAV77WiFiModeHandler`) takes on every other supported airframe. Sending the Mavic's open to a
 * Neo 2 gets silence — not because the aircraft is broken, but because it was never the command that
 * unlocks it.
 *
 * The choice is made at runtime from the aircraft's own `0x51/0x04` push, not from a model table:
 *
 * ```
 * wait for 0x51/0x04 (wlm_dev_osd_push, 27 bytes)
 *     byte 20 (message version) > 1  ->  0x51/0x1a  service mode: download + WIFI_HIGHSPEED
 *     otherwise                      ->  0x51/0x02  [04, live-view link mode, 04]
 * exit mirrors it: 0x51/0x1a download+COMMON, or 0x51/0x02 [01, live-view link mode, 01]
 * ```
 */
internal object Wlm {

    const val CMD_LINK_MODE_SWITCH = 0x02
    const val CMD_DEVICE_OSD_PUSH = 0x04
    const val CMD_SERVICE_MODE_SWITCH = 0x1A

    /** `0x51/0x02` fallback link modes. `WIFI_ONLY` enters QuickTransfer, `COMMON` leaves it. */
    const val LINK_MODE_COMMON = 1
    const val LINK_MODE_WIFI_ONLY = 4

    private const val SERVICE_MODE_VERSION = 0
    private const val SERVICE_TYPE_DOWNLOAD = 1
    private const val DOWNLOAD_MODE_COMMON = 0
    private const val DOWNLOAD_MODE_WIFI_HIGHSPEED = 1
    private const val SERVICE_MODE_REQUEST_LEN = 32
    private const val SERIAL_FIELD_LEN = 20

    /** Shortest `0x51/0x04` body the layout below is defined for. */
    const val DEVICE_OSD_LEN = 27

    /**
     * The fields of a `0x51/0x04` push this decision needs. The frame carries link quality, signal
     * bars and service modes too; only the three that steer the branch are named here, because
     * decoding bytes nothing reads invites treating guesses as facts.
     */
    data class DeviceOsd(
        /** Byte 20. Above 1 means the aircraft understands the `0x51/0x1a` service-mode switch. */
        val messageVersion: Int,
        /** Byte 13 — the value the three-byte fallback has to preserve. */
        val localLiveviewLinkMode: Int,
        /** Byte 14. The fallback is only safe to build when this agrees with byte 13. */
        val peerLiveviewLinkMode: Int,
    ) {
        /** The exact branch condition in the current handler: service-mode switching iff version > 1. */
        val serviceModeSupported: Boolean get() = messageVersion > 1

        /**
         * The live-view mode to echo back in the fallback request, or null when the aircraft reports
         * two different ones.
         *
         * The app keeps a single live-view mode in handler state; the wire exposes the local and peer
         * views separately. When they disagree there is no way to know which the aircraft expects, and
         * guessing would send it a mode it is not in — so this fails closed and the caller skips the
         * fallback rather than issue a request built on a coin flip.
         */
        val liveviewLinkModeForFallback: Int?
            get() = localLiveviewLinkMode.takeIf { it == peerLiveviewLinkMode }
    }

    /** Decode a `0x51/0x04` body, or null if it is shorter than the known layout. */
    fun parseDeviceOsd(payload: ByteArray): DeviceOsd? {
        if (payload.size < DEVICE_OSD_LEN) return null
        fun at(i: Int) = payload[i].toInt() and 0xFF
        return DeviceOsd(messageVersion = at(20), localLiveviewLinkMode = at(13), peerLiveviewLinkMode = at(14))
    }

    /**
     * `0x51/0x1a` body — `uav_wlm_service_mode_switch_req`: `[version][service][mode]`, then a
     * 20-byte ASCII serial field (all-zero when unknown), zero-padded to 32 bytes.
     */
    fun serviceModeRequest(enter: Boolean, serial: ByteArray?): ByteArray {
        val body = ByteArray(SERVICE_MODE_REQUEST_LEN)
        body[0] = SERVICE_MODE_VERSION.toByte()
        body[1] = SERVICE_TYPE_DOWNLOAD.toByte()
        body[2] = (if (enter) DOWNLOAD_MODE_WIFI_HIGHSPEED else DOWNLOAD_MODE_COMMON).toByte()
        serial?.let { it.copyInto(body, 3, 0, minOf(it.size, SERIAL_FIELD_LEN)) }
        return body
    }

    /**
     * `0x51/0x02` fallback body — `[requested, current live-view mode, requested]`.
     *
     * Three bytes, and **not** the Mavic 3's five-byte session-open despite sharing the command id.
     */
    fun linkModeRequest(mode: Int, liveviewLinkMode: Int): ByteArray =
        byteArrayOf(mode.toByte(), liveviewLinkMode.toByte(), mode.toByte())
}
