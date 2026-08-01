package dev.konraditurbe.osmosis.duml

// Vendored/adapted from https://github.com/dimadesu/dji-remote (com.dimadesu.djiremote.dji.DjiPayloads).
// MIT License.

/** DJI string field: [len:u8][utf8 bytes]. */
fun djiPackString(value: String): ByteArray {
    val data = value.toByteArray(Charsets.UTF_8)
    val writer = ByteWriter()
    writer.writeUInt8(data.size)
    writer.writeBytes(data)
    return writer.data
}

/**
 * SetPairingPIN (CmdSet 0x07 / CmdId 0x45) payload.
 *
 * The first field is a stable app identifier (here the 32-hex UUID moblin/dji-remote uses),
 * followed by the PIN packstring. For the Osmo 360 family the identifier can also be a
 * 15-digit string; we keep this known-accepted blob for the initial handshake attempt and
 * will make the identifier configurable once we see how the Nano responds.
 */
class DjiPairMessagePayload(
    private val pairPinCode: String,
    /**
     * The app identity presented alongside the PIN. Both are 32 characters, so the frame length is
     * unchanged. A Mavic already discriminates on the *token* (only "DJI FLY" unlocks WiFi creds), so
     * it may well discriminate on this too for anything beyond credentials — see [DJI_FLY_IDENTIFIER].
     */
    private val identifier: String = DEFAULT_IDENTIFIER,
) {
    companion object {
        /** What moblin/dji-remote use; accepted by every Osmo camera. */
        const val DEFAULT_IDENTIFIER = "284ae5b8d76b3375a04a6417ad71bea3"

        /**
         * DJI Fly's own per-install UUID, read off a btsnoop of it pairing this Mavic 3 (2026-08-01).
         * Tried because the drone hands out WiFi credentials to us yet refuses every datalink command,
         * and this is the last identity field we had never varied.
         */
        const val DJI_FLY_IDENTIFIER = "bbf9994f-a1da-44db-b1e0-9d889c5b"

        // PackString("284ae5b8d76b3375a04a6417ad71bea3") -- 0x20 length prefix + 32 hex chars.
        val identifierBlob = byteArrayOf(
            0x20, 0x32, 0x38, 0x34, 0x61, 0x65, 0x35, 0x62,
            0x38, 0x64, 0x37, 0x36, 0x62, 0x33, 0x33, 0x37,
            0x35, 0x61, 0x30, 0x34, 0x61, 0x36, 0x34, 0x31,
            0x37, 0x61, 0x64, 0x37, 0x31, 0x62, 0x65, 0x61,
            0x33
        )
    }

    fun encode(): ByteArray {
        val writer = ByteWriter()
        writer.writeBytes(djiPackString(identifier))
        writer.writeBytes(djiPackString(pairPinCode))
        return writer.data
    }
}
