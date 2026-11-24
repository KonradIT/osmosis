package dev.konraditurbe.osmosis.camera

/**
 * The camera work mode: DUML `0x02/0x10` **SetMode** and `0x02/0x11` **GetMode**.
 *
 * This is how the older Osmo generation switches what the camera is doing, and DJI Mimo sends this
 * switch before it reads files off an Osmo Action 1. Its album calls
 * `DJICameraAC101ModeInfo.H2(CameraWorkMode.TRANSCODE)`, not the `0x02/0x0c` playback entry that the
 * newer cameras use. An Action 1 answers that playback entry with `0xe0` every time (issue #32), and
 * `:80` refuses connections in ~10 ms. So the camera's TCP stack is up, but nothing listens on the port
 * until something switches the mode.
 *
 * **Wire values are DJI's on-the-wire enum**, taken from DJI Fly 1.21.4: `DataCameraGetMode.MODE`,
 * which `DataCameraSetMode.doPack` sends as a single byte. They are not Mimo's KeyValue
 * `CameraWorkMode`, an SDK-level enum whose TRANSCODE is 7 (`NEW_PLAYBACK` on the wire). Mimo maps that
 * enum to the wire inside its packed native library, where it can't be read. So [HTTP_CANDIDATES] tries
 * both readings.
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

    /**
     * Modes to try, in order, until `:80` opens.
     *
     * - [TRANSCODE] first: it is the mode Mimo names for the Action 1's album, by its wire value.
     * - [NEW_PLAYBACK] second: Mimo's own number for TRANSCODE (7), in case its mapping passes it through.
     * - [DOWNLOAD] and [PLAYBACK] last: the other two modes DJI defines for getting media off a camera.
     *
     * Every value here is a mode DJI's own apps set. [TUNING], [SAVE_POWER] and [BROADCAST] are left out
     * on purpose, because nothing suggests they serve files and SAVE_POWER could take the Wi-Fi down.
     */
    val HTTP_CANDIDATES = listOf(TRANSCODE, NEW_PLAYBACK, DOWNLOAD, PLAYBACK)

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
