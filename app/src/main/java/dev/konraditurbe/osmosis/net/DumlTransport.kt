package dev.konraditurbe.osmosis.net

import dev.konraditurbe.osmosis.duml.DjiCrc
import dev.konraditurbe.osmosis.duml.DjiMessage
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

/**
 * The DUML-over-UDP datalink itself: socket, session, sequencing and framing. Everything a camera and
 * a drone do **identically**, with no idea what a media manifest is.
 *
 * Wire layers per packet: `[8B transport hdr][12B routing hdr][DUML frame]`.
 *
 * The datalink is a **sliding-window transport**, and that is the part worth being careful with — every
 * packet carries `[r0-1 = ack][r2-3 = my own seq]`, and both live in *our own* command-seq space. Two
 * separate outages traced back to getting that wrong (see [routingHeader]), in the same asymmetric way
 * each time: reads and keepalive keep flowing while WRITES are silently dropped by the receive window,
 * so the session looks perfectly healthy and answers nothing.
 *
 * Blocking; call on a background thread. The process must already be bound to the device's network.
 */
class DumlTransport(
    private val log: (String) -> Unit,
    val port: Int,
    /**
     * Bind the local socket to [port] as well. DJI Fly talks to a drone **symmetrically** — every one of
     * the 138k packets in the Mavic capture is 9003→9003. A camera answers an ephemeral source port
     * happily, so this was never needed for one; a drone that routes command replies by port would send
     * ours nowhere, which matches the symptom exactly (session up, beacons streaming, no command answered).
     */
    private val bindLocalPort: Boolean = false,
    /** Byte 10 of the routing header is `0x60` on the commands DJI Fly sends a Mavic, `0x00` for a camera. */
    private val droneRouting: Boolean = false,
) {

    var sessionId = 0
        private set

    private var udpSeq = 0
    private var dumlSeq = 0xA000
    private var cmdCounter = 0
    private var camChannel = 0xB887

    /**
     * The base sequence proposed in the handshake, and the start of our own send-seq space.
     *
     * **Randomised per session, and it matters.** This was a constant (`0x87b8`) baked into the
     * handshake payload, so every session on every device opened with the same base — and an
     * independent reverse-engineering of this transport reports that reusing a fixed base across
     * connects can leave the peer's session state wedged, which looks exactly like a link that
     * handshakes and then answers nothing. The official app draws a fresh one each connect.
     *
     * Kept 8-aligned because the send seq advances in steps of 8.
     *
     * It also explains a red herring: the peer echoes this value back, so the "channel" we appeared
     * to learn from it was only ever our own constant coming home. With a random base, a log showing
     * the same number for both really does mean the echo, not a coincidence.
     */
    var baseSeq = 0xB887
        private set

    /**
     * The peer's reliable-downlink cursor, from `[10:12]` of its 34-byte pktType-`0x01` status frames.
     * Echoed back in [sendAck]; the peer holds off streaming until its window is being acknowledged.
     */
    private var peerCursor = 0
    private var msgId51 = 0
    private var seq51 = 0

    private lateinit var sock: DatagramSocket
    private lateinit var peer: InetAddress

    /** The last packet handed to the socket — kept so a query can be logged verbatim and diffed. */
    var lastSentPacket: ByteArray? = null
        private set

    val peerAddress: InetAddress? get() = if (::peer.isInitialized) peer else null
    val peerIp: String? get() = peerAddress?.hostAddress

    /** The peer's own sequence channel, learned from bytes 8–9 of everything it sends. */
    val cameraChannel: Int get() = camChannel

    /** Our current send sequence — for diagnosing window drift. See [routingHeader]. */
    val seq: Int get() = udpSeq

    val isOpen: Boolean get() = ::sock.isInitialized && !sock.isClosed

    val localPort: Int get() = if (::sock.isInitialized) sock.localPort else -1

    // ---- lifecycle ---------------------------------------------------------------------------------

    /** Create the socket and pick a session id. Does not talk to the peer yet. */
    fun open(ip: String) {
        peer = InetAddress.getByName(ip)
        sock = if (bindLocalPort) {
            runCatching { DatagramSocket(port) }
                .onFailure { log("datalink: local udp/$port unavailable (${it.message}) — falling back") }
                .getOrElse { DatagramSocket() }
        } else DatagramSocket()
        sock.soTimeout = 200
        sessionId = Random.nextInt(0x1000, 0xFFFE)
        // Fresh base per connect, 8-aligned — see [baseSeq]. camChannel starts here because until the
        // peer says otherwise, the only sequence space either side knows about is the one we proposed.
        baseSeq = Random.nextInt(0x1000, 0xF000) and 0xFFF8
        camChannel = baseSeq
        peerCursor = 0
    }

    /**
     * Send [payload] as a pktType-`0x00` frame until the peer answers with one, up to [attempts] times.
     * Returns the accepted reply datagram, or null if nothing ever came back — which is how the caller
     * tells "no media" apart from "wrong UDP port for this device" and retries the alternate.
     */
    fun handshake(payload: ByteArray, attempts: Int = 20): ByteArray? {
        repeat(attempts) {
            sendRaw(0x00, payload)
            for (r in recvAll(350)) if (r.size >= 8 && (r[6].toInt() and 0xFF) == 0x00) return r
        }
        return null
    }

    /**
     * Start our send sequence at the peer's channel + 8, once heartbeats have taught us the channel.
     * Called after draining the peer's first packets.
     */
    fun syncSeqToPeerChannel() {
        udpSeq = (camChannel + 8) and 0xFFFF
    }

    fun close() {
        runCatching { if (::sock.isInitialized) sock.close() }
    }

    // ---- send --------------------------------------------------------------------------------------

    /** Send a UDP packet, swallowing a send failure. When the AP drops or the network is unbound
     *  mid-session `sock.send` throws ENETUNREACH — expected on a dying link, and it must NEVER crash
     *  the keep-alive thread (it once did). */
    private fun sendPacket(pkt: ByteArray): Boolean {
        lastSentPacket = pkt
        return runCatching { sock.send(DatagramPacket(pkt, pkt.size, peer, port)) }.isSuccess
    }

    fun sendRaw(pktType: Int, payload: ByteArray) {
        val pkt = udpHeader(pktType, payload.size) + payload
        if (sendPacket(pkt)) advance()
    }

    /**
     * The pktType-`0x04` window acknowledgement: a 34-byte frame of three `[u16][u16][u32 zero]` groups
     * carrying, in order, **the peer's downlink cursor**, **our base seq** and **our current send seq**.
     *
     * All three used to be [camChannel] — right for the middle one only by accident, since camChannel
     * *was* the constant base, while the first never reflected what the peer had actually sent us.
     *
     * **The third stays at the base, deliberately.** The reference implementation puts its current send
     * seq there, and copying that broke drone pagination outright: page 1 fine, page 2 dead with the
     * drone streaming status and no data. The two quantities are not the same. That implementation
     * advances its sequence in exactly one place — per data frame — whereas ours advances on *every*
     * packet, including the ~860/s drone uplink, so within a minute it had raced ahead and wrapped past
     * `0xFFFF` (`0xdd88` → `0x8e70` between the two pages) and the peer's window stalled. Sending our
     * seq here would only be equivalent if our sequence meant what theirs does.
     *
     * Sent with seq 0, like every other type-`0x04`.
     */
    fun sendAck() {
        fun grp(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            0, 0, 0, 0,
        )
        val payload = grp(peerCursor) + grp(baseSeq) + grp(baseSeq) + byteArrayOf(0, 0)
        val old = udpSeq; udpSeq = 0
        val hdr = udpHeader(0x04, payload.size)
        udpSeq = old
        sendPacket(hdr + payload)
    }

    fun sendDuml(
        set: Int, cmd: Int, payload: ByteArray,
        receiverType: Int, receiverId: Int, cmdType: Int = 2,
    ) {
        cmdCounter++
        val rt = routingHeader()
        val target = 0x02 or (((receiverId shl 5) or receiverType) shl 8)
        val type = (cmdType shl 5) or (set shl 8) or (cmd shl 16)
        val duml = DjiMessage(target, dumlSeq, type, payload).encode()
        dumlSeq = (dumlSeq + 1) and 0xFFFF
        val pkt = udpHeader(0x05, rt.size + duml.size) + rt + duml
        if (sendPacket(pkt)) advance()
    }

    /**
     * Send a frame at a raw [target] with a caller-chosen sender byte — the drone uplink uses sender
     * `0x01`, not the `0x02` [sendDuml] hardcodes.
     *
     * For a `0x51` frame both of its sequence fields are stamped fresh. The outer wrapper carries 22
     * trailing bytes after the inner frame and `trailing[5]` is a counter (`…39fdb2ae 02 NN …`). Captured
     * frames are replayed verbatim, so those counters arrive frozen at whatever DJI Fly used — the
     * identity beacon at `0x0a`, the session-open at `0x01`. Sent in our order that counter goes
     * BACKWARDS, exactly the shape a replay check rejects. It sits outside the inner frame, so
     * re-stamping it monotonically leaves the inner CRC untouched.
     */
    fun sendDumlRaw(target: Int, set: Int, cmd: Int, payload: ByteArray, cmdType: Int = 0) {
        cmdCounter++
        var pl = payload
        if (set == 0x51 && pl.size >= 13 && (pl[0].toInt() and 0xFF) == 0x55) {
            val innerLen = (pl[1].toInt() and 0xFF) or ((pl[2].toInt() and 0x03) shl 8)
            if (innerLen in 13..pl.size && pl.size >= innerLen + 6) {
                pl = pl.copyOf()
                pl[innerLen + 5] = (++seq51 and 0xFF).toByte()
            }
        }
        val rt = routingHeader()
        val type = (cmdType shl 5) or (set shl 8) or (cmd shl 16)
        val id = if (set == 0x51) (++msgId51 and 0xFFFF) else 0x000A
        val duml = DjiMessage(target, id, type, pl).encode()
        val pkt = udpHeader(0x05, rt.size + duml.size) + rt + duml
        if (sendPacket(pkt)) advance()
    }

    // ---- receive -----------------------------------------------------------------------------------

    fun recvAll(durationMs: Long): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val deadline = System.nanoTime() + durationMs * 1_000_000
        val buf = ByteArray(65536)
        while (System.nanoTime() < deadline) {
            try {
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                val data = p.data.copyOf(p.length)
                out.add(data)
                if (data.size >= 10) {
                    val ch = (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8)
                    if (ch != 0) camChannel = ch
                }
                // The peer's reliable-downlink cursor rides in its 34-byte pktType-0x01 status frames;
                // [sendAck] echoes it back, and the peer will not open its downlink until we do.
                if (data.size == 34 && (data[6].toInt() and 0xFF) == 0x01) {
                    peerCursor = (data[10].toInt() and 0xFF) or ((data[11].toInt() and 0xFF) shl 8)
                }
            } catch (_: java.net.SocketTimeoutException) {
                // keep polling until the deadline
            } catch (_: Exception) {
                break
            }
        }
        return out
    }

    // ---- headers -----------------------------------------------------------------------------------

    private fun udpHeader(pktType: Int, payloadLen: Int): ByteArray =
        udpHeader(pktType, payloadLen, sessionId, udpSeq)

    /**
     * Command routing header (pkt `0x05`): `[r0-1 = ack of my previous seq][r2-3 = my own seq]`.
     *
     * Both fields live in OUR OWN command-seq space — r2-3 is this command's seq, r0-1 the previous one.
     * Ground-truthed from Mimo/Xtra pcaps, and the subject of two separate regressions:
     *
     * 1. r0-1 once held `camChannel` — the *camera's* telemetry seq, refreshed by every received packet.
     *    Telemetry runs ~10× faster than our commands and wraps to a different phase, so r0-1 drifted far
     *    from r2-3 and the receive window dropped our writes. That single divergence is what forced a
     *    fresh registered session for every delete/favorite/page/burst. See MEDIA_PROTOCOL.md
     *    § "Datalink transport / sequencing".
     * 2. A later revision set the *drone* ack to `camChannel`, on the theory that a drone echoes our seq
     *    back in r0-1. It does not — it repeats the handshake channel forever, so the ack froze at
     *    `0x87b8` while our seq ran away with the uplink stream: measured 564 packets stale against DJI
     *    Fly's 2.
     *
     * Both camera and drone want our own previous seq. The window is not enforced regardless — DJI Fly
     * runs ~1600 packets ahead of it.
     */
    private fun routingHeader(): ByteArray = routingHeader(udpSeq, cmdCounter, droneRouting)

    private fun advance() { udpSeq = (udpSeq + 8) and 0xFFFF }

    // ---- frame scanning (pure) ---------------------------------------------------------------------

    companion object {

        /**
         * The 8-byte transport header: `[u16 flag|total][u16 session][u16 seq][u8 pktType][u8 xor]`,
         * where the trailing byte is the XOR of the other seven.
         *
         * Pure, so the layout is assertable without a socket — see `DumlTransportTest`.
         */
        fun udpHeader(pktType: Int, payloadLen: Int, sessionId: Int, seq: Int): ByteArray {
            val total = 8 + payloadLen
            val w0 = (1 shl 15) or (total and 0x3FFF)
            val b = byteArrayOf(
                (w0 and 0xFF).toByte(), ((w0 shr 8) and 0xFF).toByte(),
                (sessionId and 0xFF).toByte(), ((sessionId shr 8) and 0xFF).toByte(),
                (seq and 0xFF).toByte(), ((seq shr 8) and 0xFF).toByte(),
                pktType.toByte(),
            )
            var xor = 0
            for (x in b) xor = xor xor (x.toInt() and 0xFF)
            return b + xor.toByte()
        }

        /**
         * The 12-byte routing header. Pure for the same reason: **both** regressions described on the
         * instance [routingHeader] were wrong values in these twelve bytes, and neither was catchable by
         * a test while this was tangled up with the socket.
         */
        fun routingHeader(seq: Int, cmdCounter: Int, drone: Boolean): ByteArray {
            val ack = (seq - 8) and 0xFFFF
            return byteArrayOf(
                (ack and 0xFF).toByte(), ((ack shr 8) and 0xFF).toByte(),
                (seq and 0xFF).toByte(), ((seq shr 8) and 0xFF).toByte(),
                0, 0, 0, 0, (cmdCounter and 0xFF).toByte(), 0x01,
                (if (drone) 0x60 else 0x00).toByte(), 0x00,
            )
        }

        /**
         * Every DUML frame in [raw] as `(cmdSet, cmdId, payload)`, both CRCs verified.
         *
         * Two things this must get right that a naive scan doesn't. **Length is 10 bits + a 6-bit
         * version** packed LE into bytes 1–2, so a frame over 255 bytes has byte2 `0x05`/`0x06`/`0x07`,
         * not `0x04` — matching only `0x04` hides every long frame, which is precisely where the drone
         * manifest lives. And it advances **one byte at a time, not by the frame length**: a drone wraps
         * some replies inside a `0x51/0x01` tunnel frame, and skipping the outer frame skips the payload
         * with it. Verifying both CRCs is what makes byte-at-a-time scanning safe from false positives.
         */
        fun scanFrames(raw: ByteArray): List<Triple<Int, Int, ByteArray>> {
            val out = ArrayList<Triple<Int, Int, ByteArray>>()
            var i = 0
            while (i + 13 <= raw.size) {
                if ((raw[i].toInt() and 0xFF) != 0x55) { i++; continue }
                val len = ((raw[i + 1].toInt() and 0xFF) or ((raw[i + 2].toInt() and 0xFF) shl 8)) and 0x3FF
                val ver = (raw[i + 2].toInt() and 0xFF) shr 2
                if (ver != 1 || len < 13 || i + len > raw.size) { i++; continue }
                if (DjiCrc.computeCrc8(raw.copyOfRange(i, i + 3)) != (raw[i + 3].toInt() and 0xFF)) { i++; continue }
                val body = raw.copyOfRange(i, i + len - 2)
                val want = (raw[i + len - 2].toInt() and 0xFF) or ((raw[i + len - 1].toInt() and 0xFF) shl 8)
                if (DjiCrc.computeCrc16(body) != want) { i++; continue }
                out.add(Triple(
                    raw[i + 9].toInt() and 0xFF, raw[i + 10].toInt() and 0xFF,
                    raw.copyOfRange(i + 11, i + len - 2),
                ))
                i++
            }
            return out
        }

        /**
         * First CRC-valid DUML **reply** frame with the given cmdset/cmd across [datagrams] → its
         * payload, else null. Skips the empty-payload transport ACK the camera sends before the real
         * reply (that ACK is what a naive "first frame" match grabs, making a landed command look like
         * a failure).
         */
        fun findReply(datagrams: List<ByteArray>, set: Int, cmd: Int): ByteArray? {
            for (d in datagrams) {
                var i = 0
                while (i + 11 <= d.size) {
                    if ((d[i].toInt() and 0xFF) != 0x55) { i++; continue }
                    val len = ((d[i + 1].toInt() and 0xFF) or ((d[i + 2].toInt() and 0xFF) shl 8)) and 0x3FF
                    if (len < 13 || i + len > d.size) { i++; continue }
                    if (DjiCrc.computeCrc8(d.copyOfRange(i, i + 3)) != (d[i + 3].toInt() and 0xFF)) { i++; continue }
                    if ((d[i + 9].toInt() and 0xFF) == set && (d[i + 10].toInt() and 0xFF) == cmd) {
                        val payload = d.copyOfRange(i + 11, i + len - 2)
                        if (payload.isNotEmpty()) return payload   // skip the empty transport ACK
                    }
                    i += len
                }
            }
            return null
        }

        /** Scan a datagram for a DUML response frame with [set]/[id] and return its leading status word. */
        fun findRespStatus(d: ByteArray, set: Int, id: Int): Int? {
            var i = 0
            while (i + 13 <= d.size) {
                if ((d[i].toInt() and 0xFF) != 0x55) { i++; continue }
                val len = ((d[i + 1].toInt() and 0xFF) or ((d[i + 2].toInt() and 0xFF) shl 8)) and 0x3FF
                if (len < 13 || i + len > d.size) { i++; continue }
                if ((d[i + 9].toInt() and 0xFF) == set && (d[i + 10].toInt() and 0xFF) == id) {
                    val ps = i + 11; val pe = i + len - 2
                    return when {
                        pe - ps >= 2 -> (d[ps].toInt() and 0xFF) or ((d[ps + 1].toInt() and 0xFF) shl 8)
                        pe - ps >= 1 -> d[ps].toInt() and 0xFF
                        else -> 0
                    }
                }
                i += len
            }
            return null
        }

        fun hex(s: String): ByteArray {
            val clean = s.filter { !it.isWhitespace() }
            return ByteArray(clean.length / 2) {
                ((clean[it * 2].digitToInt(16) shl 4) or clean[it * 2 + 1].digitToInt(16)).toByte()
            }
        }

        fun le32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
        )
    }
}
