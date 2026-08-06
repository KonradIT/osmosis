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
     * The app identity presented alongside the PIN, and **the string a device keys its remembered
     * approval on** — proven on a Mavic 3 by sending a rotated one: an identity the aircraft had
     * never seen turned a silent re-pair (`0x45` → `0x01`) into a full approval round
     * (`0x45` → `0x02`, LEDs chasing, power-button hold, `0x07/0x46`). Present a known identity and
     * the aircraft skips straight past all of it.
     *
     * Both values are 32 characters, so the frame length is unchanged either way.
     */
    private val identifier: String = DEFAULT_IDENTIFIER,
) {
    companion object {
        /** What moblin/dji-remote use; accepted by every Osmo camera. */
        const val DEFAULT_IDENTIFIER = "284ae5b8d76b3375a04a6417ad71bea3"

        /**
         * A DJI Fly install UUID, read off a btsnoop of it pairing a Mavic 3 (2026-08-01). Its shape is
         * the thing worth keeping: a standard UUID with the last group truncated to 8 hex, giving 32
         * characters — which is what [newInstallIdentity] mints.
         *
         * **Not used as an identity any more.** It only ever suppressed the approval prompt on the one
         * aircraft that had approved *that DJI Fly install*; on anybody else's drone it is an unknown
         * string like any other. Shipping it would also have every user of this app presenting one
         * person's DJI Fly identity, and contending for its slot on the aircraft.
         */
        const val DJI_FLY_IDENTIFIER = "bbf9994f-a1da-44db-b1e0-9d889c5b"

        /**
         * A fresh per-install identity, in the 32-char shape above. `randomUUID()` renders 8-4-4-4-12;
         * taking 32 chars keeps 8-4-4-4-8.
         *
         * Must be generated **once and persisted** — a new identity per launch would ask the user to
         * hold the power button on every single connect, and would burn a remembered-pairing slot each
         * time.
         */
        fun newInstallIdentity(): String = java.util.UUID.randomUUID().toString().take(32)

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
