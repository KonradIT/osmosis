package dev.konraditurbe.osmosis.duml

// Vendored from https://github.com/dimadesu/dji-remote (com.dimadesu.djiremote.dji.ByteReader).
// MIT License.
class ByteReader(private val data: ByteArray) {
    private var pos = 0

    val bytesAvailable: Int
        get() = data.size - pos

    fun readUInt8(): Int {
        checkRemaining(1)
        return data[pos++].toInt() and 0xFF
    }

    fun readUInt16Le(): Int {
        checkRemaining(2)
        val lo = data[pos++].toInt() and 0xFF
        val hi = data[pos++].toInt() and 0xFF
        return lo or (hi shl 8)
    }

    fun readUInt16Be(): Int {
        checkRemaining(2)
        val hi = data[pos++].toInt() and 0xFF
        val lo = data[pos++].toInt() and 0xFF
        return (hi shl 8) or lo
    }

    fun readUInt24Le(): Int {
        checkRemaining(3)
        val b0 = data[pos++].toInt() and 0xFF
        val b1 = data[pos++].toInt() and 0xFF
        val b2 = data[pos++].toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16)
    }

    fun readBytes(count: Int): ByteArray {
        checkRemaining(count)
        val out = data.copyOfRange(pos, pos + count)
        pos += count
        return out
    }

    private fun checkRemaining(n: Int) {
        if (pos + n > data.size) throw IllegalArgumentException("not enough bytes")
    }
}
