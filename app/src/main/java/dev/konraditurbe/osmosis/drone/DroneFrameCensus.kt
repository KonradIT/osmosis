package dev.konraditurbe.osmosis.drone

import dev.konraditurbe.osmosis.net.DumlTransport

/**
 * What an aircraft is *actually* saying, for a model whose session we can't open.
 *
 * The existing diagnostics answer one question — "did a `0x51/0x13` beacon arrive?" — and on a Mavic 3
 * that was enough, because everything it pushes is tunnelled inside `0x51/0x01`. On an unknown model
 * `0x51 inner cmds seen: NONE` is a dead end: it says the aircraft doesn't talk like a Mavic without
 * saying what it *does* do, and those need completely different fixes.
 *
 * So census everything until we have a serial: every CRC-valid frame by cmdset/cmd (nested ones
 * included), the raw head of each transport packet type, and — the useful part — **any payload
 * containing a serial-shaped run**. A DJI serial is 12–24 uppercase alphanumerics, distinctive enough
 * that finding one tells us which frame carries it on this airframe even when it isn't a `0x51/0x13`.
 *
 * Diagnostic only: nothing here latches a serial or changes what we send. A run that merely *looks*
 * like a serial isn't one, and picking the wrong field would be worse than saying we don't know.
 */
class DroneFrameCensus(private val serialFinder: (ByteArray) -> Pair<ByteArray, Int>?) {

    private val frames = HashMap<Int, Int>()            // set<<8|cmd -> count
    private val samples = LinkedHashMap<Int, ByteArray>()   // set<<8|cmd -> first payload seen
    private val pktCounts = LinkedHashMap<Int, Int>()       // transport pktType -> count
    private val pktHeads = LinkedHashMap<Int, ByteArray>()  // transport pktType -> first raw head
    private val serialHits = LinkedHashMap<Int, String>()   // set<<8|cmd -> the serial-shaped run
    private var total = 0

    fun record(pktType: Int, datagram: ByteArray) {
        pktCounts.merge(pktType, 1, Int::plus)
        pktHeads.putIfAbsent(pktType, datagram.copyOfRange(0, minOf(RAW_HEAD, datagram.size)))
        for ((set, cmd, payload) in DumlTransport.scanFrames(datagram)) {
            val key = (set shl 8) or cmd
            total++
            frames.merge(key, 1, Int::plus)
            if (samples.size < MAX_SAMPLES) samples.putIfAbsent(key, payload.copyOfRange(0, minOf(SAMPLE, payload.size)))
            if (serialHits.size < MAX_SERIAL_HITS && key !in serialHits) {
                serialFinder(payload)?.let { (serial, tag) ->
                    serialHits[key] = "%s (%d chars, preceded by 0x%02x)"
                        .format(String(serial, Charsets.US_ASCII), serial.size, tag)
                }
            }
        }
    }

    fun isEmpty() = total == 0

    /** Lines for the log, in the order they're most useful to read. */
    fun report(): List<String> {
        if (isEmpty() && pktCounts.isEmpty()) return listOf("drone census: nothing received at all")
        val out = ArrayList<String>()
        val pkts = pktCounts.entries.sortedBy { it.key }.joinToString(", ") { "pkt%02x×%d".format(it.key, it.value) }
        out += "drone census: $total DUML frames of ${frames.size} kinds, in [$pkts]"
        if (frames.isNotEmpty()) {
            out += "drone census: " + frames.entries.sortedByDescending { it.value }.take(MAX_KINDS)
                .joinToString("  ") { "%02x/%02x×%d".format(it.key shr 8, it.key and 0xFF, it.value) }
        }
        // The one that most likely answers "where does THIS model put its serial?".
        for ((key, hit) in serialHits) out += "drone census: serial-shaped run in %02x/%02x — %s".format(key shr 8, key and 0xFF, hit)
        if (serialHits.isEmpty() && total > 0) out += "drone census: no serial-shaped run in any frame payload"
        for ((key, s) in samples) out += "drone census: sample %02x/%02x = %s".format(key shr 8, key and 0xFF, s.hex())
        // Raw heads matter when frames DON'T parse: they show the transport/routing bytes verbatim.
        for ((t, head) in pktHeads) out += "drone census: raw pkt%02x head = %s".format(t, head.hex())
        return out
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_KINDS = 20        // histogram entries in the one-line summary
        const val MAX_SAMPLES = 12      // distinct frame kinds we dump a payload for
        const val MAX_SERIAL_HITS = 6
        const val SAMPLE = 48           // payload bytes per sample
        const val RAW_HEAD = 48         // raw datagram bytes per packet type
    }
}
