package dev.konraditurbe.osmosis.drone

/**
 * Finding an aircraft's serial number, which the `0x51` session-open has to hand back before the drone
 * will serve anything.
 *
 * **Found by shape, not by tag.** This used to anchor on a `0x11` byte, which is what a Mavic 3 puts in
 * front of its serial — a Neo 2 uses `0x24`, so the search found nothing and the session could never
 * open, indistinguishable in the log from an aircraft that never beacons. Both serials are the same
 * length and the same alphabet; only the tag differs, so the tag is the one thing not to key on.
 *
 * A DJI serial is a run of uppercase alphanumerics ([MIN]–[MAX] of them) — long enough that nothing
 * else in a beacon looks like it. The longest such run wins, and the byte in front comes back with it,
 * because a response should echo whatever tag this aircraft used rather than a Mavic's.
 *
 * Lives outside `DroneSession` because the same beacon arrives on **two transports**: the datalink over
 * WiFi, and — verified on a Mavic 3 — GATT notifications over BLE, some 30 seconds earlier, before the
 * drone's AP even exists. See [inTunnelFrame].
 */
object DroneSerial {

    /** Plausible serial lengths — both aircraft seen so far emit exactly 20; the range is slack. */
    const val MIN = 12
    const val MAX = 24

    /** The longest serial-shaped run in [payload], with the byte preceding it, or null. */
    fun inPayload(payload: ByteArray): Pair<ByteArray, Int>? {
        fun isSerialChar(b: Byte): Boolean {
            val c = b.toInt() and 0xFF
            return (c in 0x30..0x39) || (c in 0x41..0x5A)   // 0-9 A-Z
        }
        var best: Pair<ByteArray, Int>? = null
        var i = 0
        while (i < payload.size) {
            if (!isSerialChar(payload[i])) { i++; continue }
            var end = i
            while (end < payload.size && isSerialChar(payload[end])) end++
            val len = end - i
            if (len in MIN..MAX && len > (best?.first?.size ?: 0)) {
                val tag = if (i > 0) payload[i - 1].toInt() and 0xFF else 0x11
                best = payload.copyOfRange(i, end) to tag
            }
            i = end
        }
        return best
    }

    /**
     * The serial inside a `0x51/0x01` tunnel frame's payload — the wrapper a drone puts around its own
     * pushes. [pl] is that payload, which begins with a nested `0x55` DUML frame.
     *
     * Accepts inner `0x13` (the unprompted identity beacon) and `0x08` (the session-open challenge).
     *
     * **This is what makes the BLE route work.** Measured on a Mavic 3, the identity beacon arrives as a
     * GATT notification on the pairing characteristic while we're still reading WiFi credentials —
     * `55 44 04 1a e9ee … 00 51 13 | 00 00 00 11 "1581F45T…"` — byte-identical to the body the datalink
     * carries half a minute later. Reading it there means the serial is already in hand before the
     * session opens, on any airframe that beacons at all, whatever its beacon's internal layout.
     */
    fun inTunnelFrame(pl: ByteArray): Pair<ByteArray, Int>? {
        if (pl.size < 13 || (pl[0].toInt() and 0xFF) != 0x55) return null
        val len = (pl[1].toInt() and 0xFF) or ((pl[2].toInt() and 0x03) shl 8)
        if (len > pl.size || len < 15) return null
        val inner = pl[10].toInt() and 0xFF
        if (inner != 0x13 && inner != 0x08) return null
        return inPayload(pl.copyOfRange(11, len - 2))
    }
}
