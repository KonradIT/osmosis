package dev.konraditurbe.osmosis.duml

// Vendored/adapted from reference/dji-remote (com.dimadesu.djiremote.dji.DjiPayloads), MIT.

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
class DjiPairMessagePayload(private val pairPinCode: String) {
    companion object {
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
        writer.writeBytes(identifierBlob)
        writer.writeBytes(djiPackString(pairPinCode))
        return writer.data
    }
}
