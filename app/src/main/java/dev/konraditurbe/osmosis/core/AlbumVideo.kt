package dev.konraditurbe.osmosis.core

/** Controls for one selected remote recording. Closing this handle cannot stop a newer selection. */
interface AlbumVideo : AutoCloseable {
    fun pause()
    fun resume()
    fun seek(positionMs: Int)

    /** Called on the camera socket owner. Consumers must not block it. */
    interface Listener {
        fun video(accessUnit: ByteArray)
        fun audio(adts: ByteArray)
        /** A seek changes the recording position. A packet gap only damages the current access unit. */
        fun discontinuity(seeking: Boolean = false)
        fun position(playing: Boolean, positionMs: Int, durationMs: Int)
        fun ended(error: String?)
    }
}
