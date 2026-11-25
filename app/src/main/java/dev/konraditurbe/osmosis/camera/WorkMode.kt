package dev.konraditurbe.osmosis.camera

/**
 * The camera work mode: DUML `0x02/0x10` **SetMode** and `0x02/0x11` **GetMode**.
 *
 * This is how the older Osmo generation switches what the camera is doing, and DJI Mimo sends this
 * switch before it reads files off an Osmo Action 1. Its album calls
 * `DJICameraAC101ModeInfo.H2(CameraWorkMode.TRANSCODE)`, not the `0x02/0x0c` playback entry that the
 * newer cameras use. Testers saw the camera refuse HTTP in each tested mode.
 * SetMode uses the one-byte wire values from DJI Fly's DataCameraGetMode.MODE.
 *
 * The camera also reports its mode unprompted, at byte 4 of the `0x02/0x80` status push (Fly's
 * `DataCameraGetPushStateInfo.getMode` reads `get(4, 1)`), so a switch can be confirmed without polling.
 */
object WorkMode {
    const val TAKE_PHOTO = 0
    const val RECORD = 1
    const val PLAYBACK = 2
    const val TRANSCODE = 3
    const val TUNING = 4
    const val SAVE_POWER = 5
    const val DOWNLOAD = 6
    const val NEW_PLAYBACK = 7
    const val BROADCAST = 8

    fun name(mode: Int?): String = when (mode) {
        null -> "unknown"
        TAKE_PHOTO -> "TAKE_PHOTO"
        RECORD -> "RECORD"
        PLAYBACK -> "PLAYBACK"
        TRANSCODE -> "TRANSCODE"
        TUNING -> "TUNING"
        SAVE_POWER -> "SAVE_POWER"
        DOWNLOAD -> "DOWNLOAD"
        NEW_PLAYBACK -> "NEW_PLAYBACK"
        BROADCAST -> "BROADCAST"
        else -> "0x%02x".format(mode)
    }

    /** `0x02/0x10` body: the mode, one byte. */
    fun setPayload(mode: Int): ByteArray = byteArrayOf(mode.toByte())

    /**
     * The mode in a `0x02/0x11` reply, or null when the reply is not a success.
     *
     * The reply is `[status][mode]`. Fly's `DataCameraGetMode.getMode` reads index 0 of its received
     * data, but Fly's `DataBase` has already split the status byte off by then. Our `findReply` hands
     * back the whole payload, status included, the same way `0x02/0x0c`'s reply is read.
     */
    fun parseGetReply(reply: ByteArray?): Int? {
        if (reply == null || reply.size < 2 || reply[0].toInt() != 0) return null
        return reply[1].toInt() and 0xFF
    }
}
