package dev.konraditurbe.osmosis.dcf

/** Retail AC101 playback emits one MPEG-4 AAC-LC, 48 kHz stereo ADTS frame per audio record. */
internal object ActionAdts {
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 2
    const val HEADER_BYTES = 7
    const val MAX_FRAME_BYTES = 8191

    fun isFrame(data: ByteArray): Boolean {
        if (data.size <= HEADER_BYTES || data.size > MAX_FRAME_BYTES) return false
        fun u(i: Int) = data[i].toInt() and 255
        if (u(0) != 255 || u(1) != 0xf1 || u(2) != 0x4c || u(3) and 0xfc != 0x80) return false
        val length = ((u(3) and 3) shl 11) or (u(4) shl 3) or (u(5) ushr 5)
        return length == data.size && u(6) and 3 == 0
    }
}
