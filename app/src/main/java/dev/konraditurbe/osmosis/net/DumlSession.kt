package dev.konraditurbe.osmosis.net

import dev.konraditurbe.osmosis.core.CameraStatus
import dev.konraditurbe.osmosis.core.MediaSession
import dev.konraditurbe.osmosis.duml.OsmoCommands
import java.net.InetSocketAddress
import java.net.Socket

/**
 * What an Osmo camera session and a DJI drone session genuinely share: bringing the datalink up, and
 * decoding the status pushes that feed the battery/storage pill.
 *
 * Everything past the handshake diverges completely — a camera registers (`0x00/0x81`, `0x00/0x88`,
 * param subscriptions) and then speaks CompositePack; a drone is registered by nothing at all and must
 * be unlocked with the `0x51` session-open before it answers a single command. So this class
 * deliberately stops at the point they part company, and does not try to model both.
 *
 * Layering: [DumlTransport] owns the wire, this owns bringing a session up, and the subclasses own the
 * device's protocol.
 */
abstract class DumlSession(
    protected val log: (String) -> Unit,
    protected val port: Int,
    /** TCP-7001 poke to arm the datalink — the Osmo 360 / Nano / Pocket 3 need it, nothing else does. */
    private val tcpPoke: Boolean,
    isDrone: Boolean,
) : MediaSession {

    protected val tx = DumlTransport(log, port, bindLocalPort = isDrone, droneRouting = isDrone)

    /** Kept because [sequenceSpaceMismatched] has never been checked on a drone. */
    private val isDroneLink = isDrone

    @Volatile protected var keepAliveOn = false

    /**
     * What the camera itself says about playback mode, from bit 30 of the `0x02/0x80` flags word.
     * Null until it has pushed one. Independent of whether our own mode-change was answered.
     */
    @Volatile
    var playbackReported: Boolean? = null
        private set

    /**
     * When the current session last completed its handshake+registration, or 0 if never.
     *
     * Measured on a Nano: inline command **writes** stop being answered roughly 70 s after this, while
     * reads (pagination, manifest queries) keep working all session. Same session, deletes at 45/57/66 s
     * returned `status=0x0000` in ~1 s; at 74/94/124/142 s they got no reply at all. Callers use the age
     * to refresh before a write rather than discover it by timing out. See CameraSession.refreshSession.
     */
    @Volatile protected var registeredAtMs = 0L

    /** Milliseconds since this session registered; [Long.MAX_VALUE] if it never did. */
    protected fun sessionAgeMs(): Long =
        if (registeredAtMs == 0L) Long.MAX_VALUE else System.currentTimeMillis() - registeredAtMs

    @Volatile override var onStatus: ((CameraStatus) -> Unit)? = null
    @Volatile override var onFetchProgress: ((Int) -> Unit)? = null

    @Volatile final override var handshakeOk = false
        protected set

    @Volatile final override var moreAvailable = false
        protected set

    protected var status = CameraStatus()
    private var lastSig = ""          // last display signature fired to onStatus (throttles UI updates)
    private var lastBattSig = ""      // dock-relevant bytes of 0x0d/02; log only on change (#5)
    private var lastStorageSig = ""   // the 0x02/0xdc body; log only when the numbers move

    /**
     * The handshake (SYN) payload: our proposed **base sequence** followed by the window/MTU parameters
     * the official app offers — window 100, MTU 1472 (`c0 05`), and the rest replayed verbatim.
     *
     * Only the first two bytes vary. They used to be a hardcoded `b8 87`, i.e. every session this app
     * ever opened proposed the same base; it is now drawn fresh per connect by the transport, because a
     * reused base can leave the peer's session state wedged. See [DumlTransport.baseSeq].
     */
    private fun handshakeFrame(): ByteArray = hex(
        "000064006400c005140000640000019001c005140000640014006400c00514000064000101040102"
    ).also {
        it[0] = (tx.baseSeq and 0xFF).toByte()
        it[1] = ((tx.baseSeq shr 8) and 0xFF).toByte()
    }

    /**
     * Bring the datalink up to the point the device is talking to us: optional TCP poke, handshake
     * (retried until it answers), then drain its heartbeats to learn its channel and start our own
     * sequence there. What comes next — camera registration or the drone's `0x51` open — is the
     * subclass's business.
     */
    protected fun openDatalink(ip: String, onFirstPackets: ((List<ByteArray>) -> Unit)? = null): Boolean {
        handshakeOk = false
        tx.open(ip)

        if (tcpPoke) {
            runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(tx.peerAddress, 7001), 1200)
                    s.getOutputStream().write(OsmoCommands.setPairingPin("osmo"))
                    s.getOutputStream().flush()
                    Thread.sleep(400)
                }
            }
        }

        val reply = tx.handshake(handshakeFrame())
        if (reply == null) { log("datalink: handshake FAILED on udp/$port"); tx.close(); return false }
        onHandshakeReply(reply)
        handshakeOk = true
        log("datalink: handshake OK on udp/$port")
        onFetchProgress?.invoke(8)

        // Drain heartbeats, learn the peer's channel, set our seq start.
        repeat(5) { batch ->
            val got = recvAll(400)
            if (batch == 0) onFirstPackets?.invoke(got)
            sendAck()
        }
        tx.syncSeqToPeerChannel()
        onFetchProgress?.invoke(16)
        // baseSeq is logged alongside the channel because the peer echoes it: seeing them equal is
        // expected, and seeing the channel differ from a base we now randomise is the first real
        // evidence that the peer has a sequence space of its own.
        log("datalink: session=0x%04x base=0x%04x channel=0x%04x"
            .format(tx.sessionId, tx.baseSeq, tx.cameraChannel))
        if (sequenceSpaceMismatched()) {
            log("datalink: peer answered on its own sequence channel, not the base we proposed — " +
                "it may be holding a session from a previous connection")
        }
        registeredAtMs = System.currentTimeMillis()
        return true
    }

    /**
     * Has the peer declined the sequence base we proposed?
     *
     * Every packet carries the sender's sequence position at bytes 8-9, and [DumlTransport.open] seeds
     * `camChannel` with our own freshly-randomised base — so after draining, the two are equal exactly
     * when the peer echoed our proposal, and differ when it is counting from somewhere of its own.
     *
     * **Reported, not acted on.** A peer counting from its own space has answered nothing in both
     * sessions where it appeared, so this is real evidence and worth logging — but it is not the whole
     * story: of three sessions across our logs that returned no media, only two had the mismatch, and
     * the third recovered on a plain reconnect with base and channel equal throughout. Recovery
     * therefore keys on the empty result itself (see `CameraSession.fetchFileList`), which covers every
     * case observed rather than the two this signature explains.
     *
     * Drones are excluded: they bind a fixed local port and the comparison has never been checked there.
     */
    protected fun sequenceSpaceMismatched(): Boolean = !isDroneLink && tx.cameraChannel != tx.baseSeq

    /** Hook for a subclass to inspect the handshake reply (the drone logs it). */
    protected open fun onHandshakeReply(reply: ByteArray) = Unit

    override fun close() {
        keepAliveOn = false
        tx.close()
    }

    // ---- transport delegates -----------------------------------------------------------------------
    // Thin pass-throughs to [tx], so the protocol call sites in the subclasses read as protocol rather
    // than as plumbing. A rename here would be a wire change.

    protected fun sendRaw(pktType: Int, payload: ByteArray) = tx.sendRaw(pktType, payload)

    protected fun sendAck() = tx.sendAck()

    protected fun sendDuml(
        set: Int, cmd: Int, payload: ByteArray,
        receiverType: Int, receiverId: Int, cmdType: Int = 2,
    ) = tx.sendDuml(set, cmd, payload, receiverType, receiverId, cmdType)

    protected fun sendDumlRaw(target: Int, set: Int, cmd: Int, payload: ByteArray, cmdType: Int = 0) =
        tx.sendDumlRaw(target, set, cmd, payload, cmdType)

    protected fun recvAll(durationMs: Long): List<ByteArray> = tx.recvAll(durationMs)

    protected fun scanFrames(raw: ByteArray) = DumlTransport.scanFrames(raw)

    protected fun findReply(datagrams: List<ByteArray>, set: Int, cmd: Int) =
        DumlTransport.findReply(datagrams, set, cmd)

    protected fun findRespStatus(d: ByteArray, set: Int, id: Int) = DumlTransport.findRespStatus(d, set, id)

    protected fun hex(s: String) = DumlTransport.hex(s)

    protected fun le32(v: Int) = DumlTransport.le32(v)

    protected fun u32le(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    // ---- status ------------------------------------------------------------------------------------

    /** Clear the accumulated status so a new session starts from a blank pill. */
    protected fun resetStatus() {
        status = CameraStatus()
        lastSig = ""
        lastBattSig = ""
        lastStorageSig = ""
        // A fresh session must re-learn this from the camera, not inherit the last one's answer —
        // the whole value of the bit is that it is the camera's word and not ours.
        playbackReported = null
    }

    /** Fire [onStatus] if anything the UI displays has actually changed. */
    protected fun emitStatusIfChanged() {
        val sig = displaySig(status)
        if (sig != lastSig) { lastSig = sig; onStatus?.invoke(status) }
    }

    // Includes the power state so docking/undocking refreshes the pill even when the percentage
    // hasn't moved; mV is bucketed to 0.05 V so normal ripple doesn't spam the UI.
    private fun displaySig(s: CameraStatus) =
        "${s.batteryPercent}|${s.sdTotalMb / 1024}|${s.sdFreeMb / 1024}|${s.internalFreeMb / 1024}" +
            "|${s.storageFreeMb / 1024}|${s.storageTotalMb / 1024}" +
            "|${s.docked}|${s.charging}|${s.batteryMilliVolts / 50}"

    /**
     * Decode one status push into [status]. Identical on both device families — a drone's pushes carry
     * the same field layouts, they just arrive wrapped in `0x51/0x01` tunnel frames.
     */
    protected fun applyStatusFrame(set: Int, id: Int, p: ByteArray): Boolean {
        when {
            set == 0x02 && id == 0x80 && p.size >= 13 -> {
                // Byte 0-3 is a flags word, and bit 30 is the camera's OWN answer to "am I in
                // playback". Worth more than it looks: entering playback is a command we send and
                // never verify, so a body that answers the mode change and then stays in capture is
                // indistinguishable from one that complied — which is exactly the open Pocket 3
                // report. This frame arrives unprompted on every session, so the answer was already
                // on the wire. Logged on change only.
                val inPlayback = (u32le(p, 0).toInt() and 0x40000000) != 0
                if (playbackReported != inPlayback) {
                    playbackReported = inPlayback
                    log("camera reports playback mode: ${if (inPlayback) "YES" else "no"}")
                }
                // Storage of the active store: total = u32-LE MiB @ byte 5, free = u32-LE MiB @ byte 9.
                val total = u32le(p, 5).toInt()
                val free = u32le(p, 9).toInt()
                status = status.copy(
                    storageTotalMb = if (total in 1..50_000_000) total else status.storageTotalMb,
                    storageFreeMb = if (free in 0..50_000_000) free else status.storageFreeMb,
                ); return true
            }
            set == 0x02 && id == 0xDC && p.size >= 22 -> {
                // One [total][free] u32-LE MiB block PER STORE (byte 2 = store count, mirrored at
                // byte 5): the first at @6/@10, the built-in at @24/@28. Measured payload lengths are
                // 22 B single-store and 40 B two-store (xtra_13.bin) — @32-39 of the 40 B body is
                // unmapped. The >= 32 test below is a "is the second block present" gate, NOT the
                // frame length; a **single-store body (Pocket 3 = microSD only) sends 22 B** with just
                // the first block, so read the built-in only when it's actually there. Ground-truthed: an
                // Action 6's @6/@10 (121785/109748 MiB) matched its on-screen 118.9/107.2 GB; the
                // Action 5 Pro + Xtra rebadge report an identical 48980 MiB built-in; a card-less
                // Xtra reports @6 = 0. Byte 0 is *not* an inserted flag (0x11 no card / 0x00 with) —
                // presence is capacity > 0.
                val sdTotal = u32le(p, 6).toInt(); val sdFree = u32le(p, 10).toInt()
                val hasInternal = p.size >= 32
                val inTotal = if (hasInternal) u32le(p, 24).toInt() else 0
                val inFree = if (hasInternal) u32le(p, 28).toInt() else 0
                fun sane(v: Int) = v in 0..50_000_000
                // The pill's whole input, logged once per change. Without this a one-line pill is
                // indistinguishable from three different faults: the camera never sent the frame, it
                // sent a single-store body, or it sent two stores we mis-parsed. A Nano browse showed
                // exactly that ambiguity — one line on screen and nothing in the log to explain it.
                val dcSig = "${p.size}|$sdTotal|$sdFree|$inTotal|$inFree"
                if (dcSig != lastStorageSig) {
                    lastStorageSig = dcSig
                    log("storage: 0x02/0xdc ${p.size}B stores=${p[2].toInt() and 0xFF} " +
                        "first=$sdTotal/$sdFree MB" +
                        (if (hasInternal) " built-in=$inTotal/$inFree MB" else " (no built-in block)"))
                }
                status = status.copy(
                    sdTotalMb = if (sane(sdTotal)) sdTotal else status.sdTotalMb,
                    sdFreeMb = if (sane(sdFree)) sdFree else status.sdFreeMb,
                    // A single-store frame confirms there is no built-in (0), rather than "unknown" (-1).
                    internalTotalMb = if (!hasInternal) 0 else if (sane(inTotal)) inTotal else status.internalTotalMb,
                    internalFreeMb = if (!hasInternal) 0 else if (sane(inFree)) inFree else status.internalFreeMb,
                ); return true
            }
            // Gimbal position telemetry — swallowed, not measured.
            //
            // Its ARRIVAL RATE is not a read on whether the motors are turning: an Osmo Pocket 4 that
            // had physically folded its gimbal on entering playback (tester-observed, 2026-08-08) went
            // on pushing this at 9.3/s, and a camera that had refused playback pushed it at 10.0/s.
            // The frame is a fixed-rate heartbeat, so a "gimbal is running" probe built on it reported
            // RUNNING in every state and sent an OP3 investigation down the wrong path entirely.
            set == 0x04 && id == 0x05 -> return true
            set == 0x00 && id == 0x00 && p.size >= 6 -> {
                // GetVersion reply: NUL-separated ASCII (sdk\0name\0firmware); grab the version string.
                val text = String(p, Charsets.US_ASCII)
                val fw = Regex("""\d{2}\.\d{2}\.\d{2}\.\d{2}""").find(text)?.value
                    ?: text.split('\u0000').map { it.trim() }.lastOrNull { it.length in 4..24 && it.any(Char::isDigit) }
                if (!fw.isNullOrBlank()) { status = status.copy(firmware = fw); return true }
            }
            set == 0x0D && id == 0x02 && p.size >= 21 -> {
                // The dock is NOT its own DUML device (docked vs undocked sessions only ever showed
                // type 0x05 id 0), so the dock signal lives in this frame: u16@1 = pack mV,
                // i32@5 = current mA (signed, -ve = discharging), @20 = percent, @27 = dock
                // attached, @32 = taking charge. Confirmed over six live dock/undock transitions
                // (MEDIA_PROTOCOL.md §20). Logged on change only — a state line, not a 1 Hz stream.
                if (p.size >= 34) {
                    val mv = (p[1].toInt() and 0xFF) or ((p[2].toInt() and 0xFF) shl 8)
                    val cur = ((p[5].toInt() and 0xFF) or ((p[6].toInt() and 0xFF) shl 8) or
                        ((p[7].toInt() and 0xFF) shl 16) or ((p[8].toInt() and 0xFF) shl 24))
                    val docked = (p[27].toInt() and 0xFF) != 0
                    val charging = (p[32].toInt() and 0xFF) == 1
                    status = status.copy(
                        batteryMilliVolts = if (mv in 2000..5000) mv else status.batteryMilliVolts,
                        batteryMilliAmps = cur, docked = docked, charging = charging,
                    )
                    val sig = "$docked|$charging|${if (cur == 0) "0" else if (cur < 0) "-" else "+"}"
                    if (sig != lastBattSig) {
                        lastBattSig = sig
                        log("battery: %d%% %dmV %+dmA docked=%s charging=%s"
                            .format(p[20].toInt() and 0xFF, mv, cur, docked, charging))
                    }
                }
                val bp = p[20].toInt() and 0xFF
                if (bp in 0..100) { status = status.copy(batteryPercent = bp); return true }
                if (p.size >= 34) return true   // power fields decoded even if the % looked bogus
            }
        }
        return false
    }

    /** Test seam: feed one status frame and read back the accumulated [CameraStatus]. */
    internal fun applyStatusFrameForTest(set: Int, id: Int, payload: ByteArray): CameraStatus {
        applyStatusFrame(set, id, payload)
        return status
    }

}
