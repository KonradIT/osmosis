package dev.konraditurbe.osmosis.drone

import dev.konraditurbe.osmosis.net.DumlTransport
import org.junit.Test
import java.io.File

/**
 * An analysis harness, not a unit test — it reads a PCAPdroid capture of DJI Fly talking to a real
 * drone and prints what the reference app actually does on the datalink, decoded with *our own*
 * parser. Any disagreement between this output and the app's behaviour is a bug in the app.
 *
 * Skipped unless `-Dosmosis.pcap=<file>` is passed, so it never runs in a normal build:
 * ```
 * ./gradlew testDebugUnitTest --tests '*PcapAnalysis*' -Dosmosis.pcap=reference/captures/wifi/x.pcap
 * ```
 * Captures live in `reference/` and are gitignored; this file is only useful with one to hand.
 */
class PcapAnalysis {

    private fun u16be(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun u16le(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32le(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)

    /** One UDP datagram: relative time, whether we sent it, and its payload. */
    private class Datagram(val tMs: Long, val fromApp: Boolean, val payload: ByteArray)

    /** Walk a LINKTYPE_RAW pcap, yielding UDP datagrams on [port]. */
    private fun readUdp(file: File, port: Int): List<Datagram> {
        val b = file.readBytes()
        require(u32le(b, 0) == 0xA1B2C3D4L) { "not a little-endian pcap: ${"%08x".format(u32le(b, 0))}" }
        require(u32le(b, 20) == 101L) { "expected LINKTYPE_RAW(101), got ${u32le(b, 20)}" }
        val out = ArrayList<Datagram>()
        var off = 24
        var t0 = -1L
        while (off + 16 <= b.size) {
            val ts = u32le(b, off) * 1000 + u32le(b, off + 4) / 1000
            val caplen = u32le(b, off + 8).toInt()
            val p = off + 16
            off = p + caplen
            if (off > b.size || caplen < 28) continue
            if ((b[p].toInt() and 0xF0) != 0x40) continue          // IPv4 only
            if ((b[p + 9].toInt() and 0xFF) != 17) continue        // UDP only
            val ihl = (b[p].toInt() and 0x0F) * 4
            val udp = p + ihl
            if (udp + 8 > b.size) continue
            val sport = u16be(b, udp); val dport = u16be(b, udp + 2)
            if (sport != port && dport != port) continue
            val len = u16be(b, udp + 4)
            val payload = b.copyOfRange(udp + 8, minOf(udp + len, b.size))
            if (t0 < 0) t0 = ts
            // The app is 192.168.2.x; the drone is 192.168.2.1.
            val fromApp = (b[p + 16].toInt() and 0xFF) == 192 && (b[p + 19].toInt() and 0xFF) == 1
            out.add(Datagram(ts - t0, fromApp, payload))
        }
        return out
    }

    /** The `0x4a` envelope header of a media payload, if it is one. */
    private fun envelope(pl: ByteArray): Triple<Int, Int, Int>? {
        if (pl.size < 10 || (pl[0].toInt() and 0xFF) != 0x4A) return null
        return Triple(pl[1].toInt() and 0xFF, u16le(pl, 4), u16le(pl, 2) and 0x0FFF)  // subtype, seq, len
    }

    @Test
    fun `what does DJI Fly actually do on the datalink`() {
        val path = System.getenv("OSMOSIS_PCAP") ?: System.getProperty("osmosis.pcap") ?: run {
            println("PcapAnalysis: skipped (set OSMOSIS_PCAP=<file>)"); return
        }
        val f = File(path)
        if (!f.isFile) { println("PcapAnalysis: no such file $path"); return }

        // udp/9003 drone, 9004 Osmo 360 / Nano / Pocket 3, 10004 Xtra Edge Pro / Action 5 Pro.
        val port = (System.getenv("OSMOSIS_PORT") ?: "9003").toInt()
        val grams = readUdp(f, port)
        println("=== ${f.name}: ${grams.size} datagrams on udp/$port ===")

        val pktTypes = sortedMapOf<Int, Int>()
        val cmdCounts = sortedMapOf<String, Int>()
        val mediaEvents = ArrayList<String>()
        val subtypeSeqs = HashMap<Int, MutableSet<Int>>()

        // Every file-management frame, verbatim. 0x00/0x28 is delete: the request carries the handles
        // and the trailing selector bytes, the reply its status word. 0x02/0xbf is favourite, which
        // shares the handle namespace and is the cheapest cross-check that a handle is still valid.
        val fileOps = ArrayList<String>()

        for (g in grams) {
            if (g.payload.size > 6) pktTypes.merge(g.payload[6].toInt() and 0xFF, 1, Int::plus)
            // Frames may sit behind the 8-byte transport + 12-byte routing header; scanFrames is
            // CRC-verified and byte-at-a-time, so feeding the whole datagram is safe.
            for ((set, cmd, pl) in DumlTransport.scanFrames(g.payload)) {
                val dir = if (g.fromApp) "->" else "<-"
                cmdCounts.merge("$dir %02x/%02x".format(set, cmd), 1, Int::plus)
                if ((set == 0x00 && cmd == 0x28) || (set == 0x02 && cmd == 0xBF)) {
                    fileOps.add("%7.2fs %s %02x/%02x  %s"
                        .format(g.tMs / 1000.0, dir, set, cmd, pl.joinToString("") { "%02x".format(it) }))
                }
                if (set != 0x00 || (cmd != 0x26 && cmd != 0x27)) continue
                val env = envelope(pl) ?: continue
                val (sub, seq, len) = env
                subtypeSeqs.getOrPut(sub) { sortedSetOf() }.add(seq)
                // Only log queries and the head of each reply — a reply is many chunks.
                val isQuery = g.fromApp
                val chunk = if (pl.size >= 10) u32le(pl, 6).toInt() else -1
                if (isQuery || chunk == 0) {
                    val extra = when (sub) {
                        0x20 -> " file_index=" + u32le(pl, 10)
                        0x00 -> " cursor=" + u32le(pl, 10)
                        0x01 -> " count=" + u32le(pl, 10) + " bytes=" + u32le(pl, 14)
                        else -> ""
                    }
                    // Small frames are control, not data — dump them verbatim so our builders can be
                    // diffed against the reference byte for byte.
                    val hex = if (pl.size <= 24) "  [" + pl.joinToString("") { "%02x".format(it) } + "]" else ""
                    mediaEvents.add("%7.2fs %s 4a sub=%02x seq=%04x len=%d%s%s"
                        .format(g.tMs / 1000.0, dir, sub, seq, len, extra, hex))
                }
            }
        }

        println("\n-- transport pktTypes --"); pktTypes.forEach { (k, v) -> println("  pkt%02x  %d".format(k, v)) }
        println("\n-- DUML commands (top 25) --")
        cmdCounts.entries.sortedByDescending { it.value }.take(25).forEach { println("  ${it.key}  ${it.value}") }
        println("\n-- 0x4a subtypes seen, and how many distinct seqs each used --")
        subtypeSeqs.toSortedMap().forEach { (sub, seqs) ->
            println("  sub=%02x  %d distinct seqs, range %04x..%04x"
                .format(sub, seqs.size, seqs.min(), seqs.max()))
        }
        println("\n-- file management: 0x00/0x28 delete, 0x02/0xbf favourite --")
        if (fileOps.isEmpty()) println("  (none)") else fileOps.forEach { println("  $it") }

        println("\n-- media timeline (queries + reply heads) --")
        mediaEvents.take(400).forEach { println("  $it") }
        println("\n  … ${mediaEvents.size} media events total")
    }
}
