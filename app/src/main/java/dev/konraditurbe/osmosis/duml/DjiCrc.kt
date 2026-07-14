package dev.konraditurbe.osmosis.duml

// Vendored from https://github.com/dimadesu/dji-remote (com.dimadesu.djiremote.dji.DjiCrc).
// MIT License.
// DJI DUML checksums. Same algorithm as drone-side DUML.
object DjiCrc {
    // CRC-8: spec init=0xEE, poly=0x31, refIn=true, refOut=true.
    // Reflected (LSB-first) form uses reflected init/poly: init=reflect8(0xEE)=0x77, poly=reflect8(0x31)=0x8C.
    fun computeCrc8(data: ByteArray, initial: Int = 0x77, poly: Int = 0x8C): Int {
        var crc = initial and 0xFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            for (i in 0 until 8) {
                crc = if ((crc and 0x01) != 0) (crc ushr 1) xor poly else crc ushr 1
            }
        }
        return crc and 0xFF
    }

    // CRC-16: spec init=0x496C, poly=0x1021, refIn=true, refOut=true.
    // Reflected form: init=reflect16(0x496C)=0x3692, poly=reflect16(0x1021)=0x8408.
    fun computeCrc16(data: ByteArray, initial: Int = 0x3692, poly: Int = 0x8408): Int {
        var crc = initial and 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            for (i in 0 until 8) {
                crc = if ((crc and 0x01) != 0) (crc ushr 1) xor poly else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }
}
