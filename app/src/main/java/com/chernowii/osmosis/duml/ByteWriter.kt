package com.chernowii.osmosis.duml

// Vendored from reference/dji-remote (com.dimadesu.djiremote.dji.ByteWriter), MIT.
class ByteWriter {
    private val buffer = ArrayList<Byte>()

    val data: ByteArray
        get() = buffer.toByteArray()

    fun writeUInt8(value: Int) {
        buffer.add((value and 0xFF).toByte())
    }

    fun writeUInt16Le(value: Int) {
        buffer.add((value and 0xFF).toByte())
        buffer.add(((value shr 8) and 0xFF).toByte())
    }

    fun writeUInt16Be(value: Int) {
        buffer.add(((value shr 8) and 0xFF).toByte())
        buffer.add((value and 0xFF).toByte())
    }

    fun writeUInt24Le(value: Int) {
        buffer.add((value and 0xFF).toByte())
        buffer.add(((value shr 8) and 0xFF).toByte())
        buffer.add(((value shr 16) and 0xFF).toByte())
    }

    fun writeBytes(bytes: ByteArray) {
        for (b in bytes) buffer.add(b)
    }

    fun writeBytes(bytes: List<Byte>) {
        buffer.addAll(bytes)
    }
}
