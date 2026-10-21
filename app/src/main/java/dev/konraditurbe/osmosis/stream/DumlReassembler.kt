package dev.konraditurbe.osmosis.stream

import dev.konraditurbe.osmosis.duml.DjiMessage

/**
 * Reassembles DUML frames from raw notification bytes. [dev.konraditurbe.osmosis.ble.GattClient]
 * parses one notification at a time, so a frame split across two (or two frames sharing one) is
 * unreadable without this. Feed it everything; it returns the frames that are complete and holds
 * the remainder until the next notification completes them.
 */
internal class DumlReassembler {
    private var carry = ByteArray(0)

    fun feed(raw: ByteArray): List<DjiMessage> {
        val buf = carry + raw
        val out = mutableListOf<DjiMessage>()
        var off = 0
        while (true) {
            // Resync past any leading byte that is not a start-of-frame.
            while (off < buf.size && (buf[off].toInt() and 0xFF) != 0x55) off++
            if (off + 2 > buf.size) break
            val len = buf[off + 1].toInt() and 0xFF
            if (len < 2 || off + len > buf.size) break
            val frame = try {
                DjiMessage.fromBytes(buf.copyOfRange(off, off + len))
            } catch (_: Exception) {
                break
            }
            out += frame
            off += len
        }
        carry = if (off >= buf.size) ByteArray(0) else buf.copyOfRange(off, buf.size)
        // Never grow without a resync: a stream of noise would otherwise pin the buffer forever.
        if (carry.size > 512) carry = ByteArray(0)
        return out
    }
}
