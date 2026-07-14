package dev.konraditurbe.osmosis.duml

// Vendored from https://github.com/dimadesu/dji-remote (com.dimadesu.djiremote.dji.DjiMessage).
// MIT License.
// BLE-variant DUML frame: SOF, len, ver, crc8, target(2 LE), id(2 -- see note), type(3 LE), payload, crc16(2 LE).
//
// NOTE on `id`: on the BLE channel this 16-bit field is a big-endian message id in the
// wild, but this class treats it as LE for a lossless encode/decode round-trip. For the
// simple request/response matching we do, the camera echoes the same bytes, so LE-in/LE-out
// is correct on the wire regardless of interpretation. Revisit if we ever need to *read*
// the numeric id semantically.
class DjiMessage(var target: Int, var id: Int, var type: Int, var payload: ByteArray) {
    companion object {
        @Throws(IllegalArgumentException::class)
        fun fromBytes(data: ByteArray): DjiMessage {
            val reader = ByteReader(data)
            val first = reader.readUInt8()
            if (first != 0x55) throw IllegalArgumentException("Bad first byte")
            val length = reader.readUInt8()
            if (data.size != length) throw IllegalArgumentException("Bad length")
            val version = reader.readUInt8()
            if (version != 0x04) throw IllegalArgumentException("Bad version")
            val headerCrc = reader.readUInt8()
            val calculatedHeaderCrc = DjiCrc.computeCrc8(data.copyOfRange(0, 3))
            if (headerCrc != calculatedHeaderCrc) throw IllegalArgumentException("Bad header CRC")
            val target = reader.readUInt16Le()
            val id = reader.readUInt16Le()
            val type = reader.readUInt24Le()
            val payload = reader.readBytes(reader.bytesAvailable - 2)
            val crc = reader.readUInt16Le()
            val withoutCrc = data.copyOfRange(0, data.size - 2)
            val calcCrc = DjiCrc.computeCrc16(withoutCrc)
            if (crc != calcCrc) throw IllegalArgumentException("Bad CRC")
            return DjiMessage(target, id, type, payload)
        }
    }

    fun encode(): ByteArray {
        val writer = ByteWriter()
        writer.writeUInt8(0x55)
        writer.writeUInt8(13 + payload.size)
        writer.writeUInt8(0x04)
        val headerCrc = DjiCrc.computeCrc8(writer.data)
        writer.writeUInt8(headerCrc)
        writer.writeUInt16Le(target)
        writer.writeUInt16Le(id)
        writer.writeUInt24Le(type)
        writer.writeBytes(payload)
        val withoutCrc = writer.data
        val crc = DjiCrc.computeCrc16(withoutCrc)
        writer.writeUInt16Le(crc)
        return writer.data
    }

    /** cmdSet is the middle byte of the 24-bit LE type field. */
    val cmdSet: Int get() = (type shr 8) and 0xFF

    /** cmdId is the high byte of the 24-bit LE type field. */
    val cmdId: Int get() = (type shr 16) and 0xFF

    /** flags is the low byte of the 24-bit LE type field (0x40 req, 0xC0 resp, 0x00 notify). */
    val flags: Int get() = type and 0xFF

    fun format(): String =
        "target=0x%04X id=0x%04X flags=0x%02X set=0x%02X cmd=0x%02X payload=%s"
            .format(target, id, flags, cmdSet, cmdId, payload.joinToString("") { "%02x".format(it) })
}
