package dev.konraditurbe.osmosis.dcf

import dev.konraditurbe.osmosis.net.DumlTransport

/** AC101's reliable video channel. Sequence numbers advance by eight and can wrap mid-record. */
internal class ActionVideoStream(
    private val session: Int,
    private val video: (ByteArray) -> Unit,
    private val audio: (ByteArray) -> Unit,
    private val discontinuity: () -> Unit,
) {
    private val pending = HashMap<Int, ByteArray>()
    private var next = -1
    val acknowledgedSequence: Int? get() = if (next < 0) null else ((next - 1) and 8191) shl 3
    private var missingSince = 0L

    private val header = ByteArray(12)
    private var headerBytes = 0
    private var remaining = 0
    private var record: ByteArray? = null
    private var consumer: ((ByteArray) -> Unit)? = null

    fun discardPartialRecord() {
        headerBytes = 0
        remaining = 0
        record = null
        consumer = null
    }

    fun accept(bytes: ByteArray, at: Int, length: Int, now: Long) {
        if (length <= 20 || DumlTransport.u16(bytes, at + 2) != session || bytes[at + 6].toInt() != 2) return
        var xor = 0
        for (i in at until at + 8) xor = xor xor bytes[i].toInt()
        if (xor != 0) return
        val seq = DumlTransport.u16(bytes, at + 4) ushr 3
        if (next < 0) next = seq
        val ahead = (seq - next) and 8191
        if (ahead >= 4096) return // duplicate or late retransmission
        if (ahead == 0) {
            read(bytes, at + 20, at + length)
            next = (next + 1) and 8191
            drain(now)
        } else {
            pending.putIfAbsent(seq, bytes.copyOfRange(at + 20, at + length))
            if (pending.size == 1 && missingSince == 0L) missingSince = now
            if (pending.size >= 128) skipGap(now)
        }
    }

    fun expireGap(now: Long) {
        if (pending.isNotEmpty() && now - missingSince >= 250) skipGap(now)
    }

    private fun skipGap(now: Long) {
        next = pending.keys.minBy { (it - next) and 8191 }
        discardPartialRecord()
        discontinuity()
        drain(now)
    }

    private fun drain(now: Long) {
        while (true) {
            val part = pending.remove(next) ?: break
            read(part, 0, part.size)
            next = (next + 1) and 8191
        }
        missingSince = if (pending.isEmpty()) 0 else now
    }

    private fun read(data: ByteArray, from: Int, end: Int) {
        var i = from
        while (i < end) {
            if (remaining > 0) {
                val count = minOf(remaining, end - i)
                record?.let { data.copyInto(it, it.size - remaining, i, i + count) }
                remaining -= count
                i += count
                if (remaining == 0) {
                    record?.let { consumer?.invoke(it) }
                    record = null
                }
            } else {
                header[headerBytes++] = data[i++]
                if (headerBytes < header.size) continue
                val size = DumlTransport.u16(header, 4) or ((header[7].toInt() and 255) shl 16)
                if (header[0].toInt() == 0 && header[1].toInt() == 0 && header[2].toInt() == 1 &&
                    header[3].toInt() == -1 && header[6].toInt() == -1 && header[8].toInt() == 0x4c &&
                    header[11].toInt() == 0 && size in 1..4_194_304 &&
                    header.fold(0) { sum, b -> sum xor b.toInt() } == 0) {
                    remaining = size
                    consumer = when (header[9].toInt()) {
                        0x11 -> video
                        0x24 -> if (size <= ActionAdts.MAX_FRAME_BYTES) audio else null
                        else -> null
                    }
                    record = if (consumer != null) ByteArray(size) else null
                    headerBytes = 0
                } else {
                    header.copyInto(header, 0, 1)
                    headerBytes--
                }
            }
        }
    }
}
