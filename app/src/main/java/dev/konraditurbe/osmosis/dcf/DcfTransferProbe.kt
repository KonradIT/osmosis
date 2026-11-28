package dev.konraditurbe.osmosis.dcf

/**
 * Does an older Osmo Action serve **file bytes over the datalink**, the way it already serves its
 * media list?
 *
 * This is ROADMAP #6's one open question. The Action 1 refuses HTTP on `:80` while WiFi and the
 * datalink are both up, so the whole download path is blocked on a transport we haven't identified —
 * static lighttpd once the camera is coaxed into playback, or DUML via `DjiTransSrv`, the service that
 * already sent us the list.
 *
 * The drone work makes the second one cheap to test. `0x4a` transfers run as a **family per transfer
 * kind**: `+0` query, `+1` reply, `+2` proceed, `+3` state, `+4` release. A media list is `0x00`–`0x04`
 * and a thumbnail is `0x20`–`0x24` on a Mavic 3 — the same machinery, a different base. The Action 1
 * demonstrably speaks the list half of that family, so if its file transfer is DUML at all, it is
 * likely another base in the same family.
 *
 * So: send a query at each candidate base and see whether anything answers. **A `+3` state frame is
 * the signal** — on the drone that is what the camera raises before it starts sending, and getting one
 * back for a file index means the family is generic on this firmware and the download is a port rather
 * than a fresh reverse-engineering job.
 *
 * ### What a silent result does and doesn't prove
 * A base that answers nothing is **weak evidence**, not a refutation. The query payload here mirrors
 * the list query's header with the file index where the list puts its cursor — a guess, because no
 * capture of this camera transferring a file exists. A wrong payload shape would also produce silence.
 * Only a positive is strong. The log says so, so nobody reads an empty result as "ruled out".
 *
 * ### Slots
 * **A transfer holds a slot on the device until released, and there are few of them.** Leaked slots on
 * a Mavic 3 made it stop answering media queries while telemetry streamed on — the link looks healthy
 * and serves nothing, which is indistinguishable from "unsupported" and would have us abandon a route
 * that works. So every probe releases, including the ones that get no reply.
 *
 * Read-only throughout: query and release, nothing that writes to the card.
 */
object DcfTransferProbe {

    /**
     * Transfer-kind bases to try. `0x00` (the list) is deliberately excluded — we know it answers, and
     * re-querying it would prove nothing. `0x20` is the drone's thumbnail kind and so the best-attested
     * guess; the rest walk the same `0x20` spacing on the theory that kinds are laid out on a grid.
     */
    val BASES = listOf(0x20, 0x40, 0x60, 0x10, 0x30)

    const val QUERY = 0
    const val REPLY = 1
    const val PROCEED = 2
    const val STATE = 3
    const val RELEASE = 4

    /**
     * A transfer query for [fileIndex] at transfer-kind [base], shaped like the list query's header:
     * `4a <sub> <len|FINAL> <seq:u16> 00000000 <index:u32> …`. The list puts its paging cursor at byte
     * 10; a file transfer has to name its file somewhere, and that slot is the obvious candidate.
     */
    fun query(base: Int, seq: Int, fileIndex: Long): ByteArray {
        val p = ByteArray(20)
        p[0] = 0x4A
        p[1] = (base + QUERY).toByte()
        p[2] = 0x14; p[3] = 0x10                       // len 20 | FINAL
        p[4] = (seq and 0xFF).toByte(); p[5] = ((seq shr 8) and 0xFF).toByte()
        for (i in 0 until 4) p[10 + i] = ((fileIndex shr (8 * i)) and 0xFF).toByte()
        return p
    }

    /** The release for a transfer we opened — sent whatever happened, so we never leak a slot. */
    fun release(base: Int, seq: Int): ByteArray {
        val p = ByteArray(14)
        p[0] = 0x4A
        p[1] = (base + RELEASE).toByte()
        p[2] = 0x0E; p[3] = 0x10                       // len 14 | FINAL
        p[4] = (seq and 0xFF).toByte(); p[5] = ((seq shr 8) and 0xFF).toByte()
        p[10] = 0x01
        return p
    }

    /** Is [payload] a `0x4a` frame belonging to transfer-kind [base] carrying [seq]? Returns its
     *  sub-offset within the family (`+1` reply, `+3` state, …), or null. */
    fun familyMember(payload: ByteArray, base: Int, seq: Int): Int? {
        if (payload.size < 6 || (payload[0].toInt() and 0xFF) != 0x4A) return null
        val sub = payload[1].toInt() and 0xFF
        if (sub < base || sub > base + RELEASE) return null
        val s = (payload[4].toInt() and 0xFF) or ((payload[5].toInt() and 0xFF) shl 8)
        return if (s == seq) sub - base else null
    }

    fun kindName(offset: Int): String = when (offset) {
        QUERY -> "query"; REPLY -> "reply"; PROCEED -> "proceed"
        STATE -> "STATE"; RELEASE -> "release"; else -> "sub+$offset"
    }
}
