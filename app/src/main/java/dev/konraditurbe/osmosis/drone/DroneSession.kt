package dev.konraditurbe.osmosis.drone

import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.duml.DjiCrc
import dev.konraditurbe.osmosis.duml.DjiMessage
import dev.konraditurbe.osmosis.net.DumlSession

/**
 * A media session with a DJI **drone** (Mavic 3 family) over QuickTransfer WiFi. See ROADMAP #14 and
 * MEDIA_PROTOCOL.md § "DJI Drone QuickTransfer media offload".
 *
 * Nothing below the [DumlSession] handshake is shared with a camera:
 *
 * | | Osmo camera | drone |
 * |---|---|---|
 * | after handshake | registration (`0x00/0x81`, `0x00/0x88`, param subs) | **nothing** — DJI Fly sends none of it |
 * | gate | none | **`0x51` session-open** — answers no command at all until it completes |
 * | media list | CompositePack TLV of paths | flat 94-byte DCF records ([DroneManifest]) |
 * | paging | fresh playback session per page | back to back on the live session |
 * | thumbnails | HTTP | HTTP — a video's THM, a still's from inside the original (see `DcfAddressing`) |
 * | keep-alive | ACK + status polling | an ~860/s uplink stream it expects from a controller |
 *
 * Blocking; call on a background thread. The process must already be bound to the drone's AP.
 */
class DroneSession(
    log: (String) -> Unit,
    port: Int = 9003,
    /**
     * Test hook (`--ez nobeacon true`): ignore the serial the beacon offers, forcing it to come from
     * the `0x51/0x08` challenge instead.
     *
     * Without this the challenge path is **unreachable on the one aircraft that can verify it**: a
     * Mavic 3 beacons first, [latchDroneSerial] latches and returns early, and the challenge is never
     * parsed. So a green Mavic run would prove only "not broken", saying nothing about whether the
     * reactive path works — and that is exactly the path we're about to hand a tester as the fix.
     */
    private val ignoreBeaconSerial: Boolean = false,
    /**
     * The serial + tag already read off the aircraft's BLE identity beacon, if one arrived while
     * pairing. Verified on a Mavic 3: the same `0x51/0x13` beacon lands as a GATT notification ~30 s
     * before the AP exists, so the serial can be in hand before the datalink even opens — no beacon
     * parsing on the datalink and no dependence on the challenge. See [DroneSerial.inTunnelFrame].
     */
    knownSerial: Pair<ByteArray, Int>? = null,
) : DumlSession(log, port, tcpPoke = false, isDrone = true) {

    /** The BLE-supplied serial, unless `nobeacon` is forcing it to come off the wire. */
    private val bleSerial = knownSerial?.takeIf { !ignoreBeaconSerial }

    private var droneCursor = 0L
    private val droneSeen = HashSet<Long>()
    private var querySeq = 0x0C

    // ---- paging diagnostics (ROADMAP #14, "paging stops after page 2") -----------------------------
    // Every datagram seen during a query, counted by transport pktType. This is the field the old
    // failure log could NOT show: its sink keeps only pktType 0x03 (the data stream), so "no valid DUML
    // at all" was equally consistent with a silent drone and a chatty one that simply ignored the query.
    // Those need completely different fixes, so count the raw packets instead of inferring.
    private val rxByType = LinkedHashMap<Int, Int>()
    private var pageNo = 0
    private var sessionStartMs = 0L

    override fun onHandshakeReply(reply: ByteArray) {
        // Log what the drone actually said, not just that *something* typed 0x00 came back: if it answers
        // with a session id other than the one we chose, we would keep stamping ours on every packet and
        // it would drop them all — which looks exactly like the observed failure (handshake fine, beacons
        // flowing, no command accepted).
        log("datalink: handshake reply ${reply.copyOfRange(0, minOf(16, reply.size))
            .joinToString("") { "%02x".format(it) }} (we sent session=0x%04x)".format(tx.sessionId))
    }

    /**
     * Bring the link up and unlock the drone: handshake, then the identity beacon reply, then the `0x51`
     * session-open. Without the last step the drone never opens a data session and ignores every command.
     */
    private fun openSession(ip: String): Boolean {
        log("datalink: local port ${tx.localPort} (drone expects $port)")
        if (!openDatalink(ip) { first ->
                // Show what the drone's own packets look like in OUR session, to compare against the
                // capture (f388: `2280ea9d000001d4 40ef40ef00000000 …` — session echoed, channel in r0-1).
                first.take(3).forEach {
                    log("datalink: rx ${it.copyOfRange(0, minOf(24, it.size)).joinToString("") { b -> "%02x".format(b) }}")
                }
            }
        ) return false

        // Bring the uplink up FIRST and let the drone start tracking us, then hand back.
        recvAll(300); sendAck()
        uplinkRunning = true
        dronePump(500)
        // Order matters: DJI Fly answers the identity beacon first (0x51 msg id 1), then opens (id 2+).
        // Matching that keeps our id sequence identical to its.
        sendDroneIdentity()
        dronePump(400)
        droneSessionOpen()
        // From here the beacon must keep being answered or the data session lapses — see [beaconOn].
        // Started only now so the 0x51 message ids during the open still run 1,2,3… as DJI Fly's do.
        beaconOn = true
        dronePump(1200)
        log("datalink: drone session-open sent — drone frames/s now ${droneRxFrameRate()}")
        return true
    }

    // ---- media list --------------------------------------------------------------------------------

    override fun fetchFileList(ip: String): List<CameraFile> {
        sessionStartMs = System.currentTimeMillis(); pageNo = 0
        if (!openSession(ip)) return emptyList()
        runDronePrelude()
        onFetchProgress?.invoke(50)
        droneSeen.clear(); droneCursor = 0L; moreAvailable = false
        val files = listPage(cursor = 1L)              // cursor 1 = newest page
        droneSeen.addAll(files.map { it.fileIndex })
        droneCursor = files.minOfOrNull { it.fileIndex } ?: 0L
        moreAvailable = files.isNotEmpty() && droneCursor > 0L
        log("datalink: drone manifest → ${files.size} files (newest page; more=$moreAvailable)")
        onFetchProgress?.invoke(100)
        return files
    }

    /**
     * The next OLDER page for the grid's infinite scroll, or empty once exhausted. Unlike the camera
     * path this needs no fresh session and no playback mode — the drone answers pages back to back on
     * the live session. The cursor is the oldest index of the page just shown and the drone *repeats*
     * that file, so the replay is filtered out by [droneSeen].
     */
    override fun fetchNextPage(): List<CameraFile> {
        if (!moreAvailable || droneCursor <= 0L) return emptyList()
        // Must run on the session thread — see [onDroneThread]. Called directly it raced the keep-alive
        // and received nothing at all. Before the keep-alive exists (the very first page) run it inline.
        var page: List<CameraFile> = emptyList()
        if (!onDroneThread(30_000) { page = listPage(droneCursor) }) page = listPage(droneCursor)
        val fresh = page.filter { droneSeen.add(it.fileIndex) }
        val oldest = page.filter { it.fileIndex > 0L }.minOfOrNull { it.fileIndex } ?: 0L
        // Stop when the page brought nothing new or the cursor failed to move older — either means we
        // reached the oldest file on the card (the drone answers the last page with just that file).
        val advanced = oldest in 1 until droneCursor
        if (advanced) droneCursor = oldest
        moreAvailable = advanced && fresh.isNotEmpty()
        // Which store each record came from — the top two bits of its index. A Mavic has both a card
        // and internal memory mounted at separate roots, so footage present on both enumerates twice
        // under different storage ids, which would inflate the grid without any record being wrong.
        val byStore = fresh.groupingBy { it.storage }.eachCount().toSortedMap()
            .entries.joinToString(",") { "s${it.key}=${it.value}" }
        log("datalink: drone page → ${fresh.size} new of ${page.size} [$byStore] (more=$moreAvailable)")
        return fresh
    }

    /** One page: send the query, collect the chunked `0x00/0x27` reply, reassemble, decode. */
    private fun listPage(cursor: Long): List<CameraFile> {
        val seq = querySeq.also { querySeq = (it + 1) and 0xFFFF }
        val chunks = LinkedHashMap<Int, DroneManifest.Chunk>()
        val heard = LinkedHashMap<Int, Int>()     // set<<8|cmd -> count, so a dead query is diagnosable
        val blob = java.io.ByteArrayOutputStream()
        var acked = false
        var wentAhead = false
        rxByType.clear()
        pageNo++
        val tState = "page=$pageNo seq=0x%02x cursor=%d(file %d) udpSeq=0x%04x chan=0x%04x t=%.1fs"
            .format(seq, cursor, cursor and 0xFFFF, tx.seq, tx.cameraChannel,
                (System.currentTimeMillis() - sessionStartMs) / 1000.0)
        sendDuml(0x00, 0x26, DroneManifest.listQuery(seq, cursor), receiverType = 0x01, receiverId = 0)
        log("datalink: drone list QUERY $tState")
        // Verbatim, so it can be diffed against DJI Fly's working query (capture f2229):
        //   4280ea9d701505d5 4815701500000000c601604d 552e04a7020177c94000264a0021…
        log("datalink: drone query pkt ${tx.lastSentPacket?.joinToString("") { "%02x".format(it) }}")
        for (batch in 0 until 14) {
            dronePump(600, blob, manifestStream = true)  // uplink alive + reassembled data stream
            sendAck()
            // A manifest chunk is ~1 kB and overruns the datagram (measured: a 1012-byte frame inside a
            // 1472-byte packet, with the next chunk's head trailing it), so frames routinely straddle a
            // UDP boundary. Rescan the whole accumulated stream, not each datagram on its own.
            chunks.clear(); heard.clear()
            var sawState = false
            for ((set, cmd, pl) in scanFrames(blob.toByteArray())) {
                heard.merge((set shl 8) or cmd, 1, Int::plus)
                if (set != 0x00 || cmd != 0x27) continue
                if (isState(pl, DroneManifest.SUB_LIST_STATE, seq)) sawState = true
                val c = DroneManifest.parseChunk(pl) ?: continue
                if (c.seq == seq) chunks.putIfAbsent(c.index, c)
            }
            if (chunksComplete(chunks)) break
            // The drone raises a state frame before it starts sending and waits to be told to proceed.
            // Ignoring it means sitting out the whole timeout and calling the page empty.
            if (sawState && chunks.isEmpty() && !wentAhead) {
                sendDuml(0x00, 0x26, DroneManifest.transferGo(seq), receiverType = 0x01, receiverId = 0)
                wentAhead = true
            }
            if (!acked && chunks.isNotEmpty()) {
                sendDuml(0x00, 0x26, DroneManifest.listAck(seq), receiverType = 0x01, receiverId = 0)
                acked = true
            }
            // Still silent a couple of rounds in — re-issue once, in case the drone dropped the first
            // while it was still settling after registration.
            if (batch == 3 && chunks.isEmpty())
                sendDuml(0x00, 0x26, DroneManifest.listQuery(seq, cursor), receiverType = 0x01, receiverId = 0)
        }
        // Whether the drone was talking to us AT ALL decides which bug this is: packets arriving but no
        // 0x00/0x27 means the query was received and refused (or dropped by its receive window); no
        // packets at all means the link itself stalled.
        val rx = rxByType.entries.sortedBy { it.key }
            .joinToString(", ") { "pkt%02x×%d".format(it.key, it.value) }
            .ifEmpty { "NOTHING — link silent" }
        if (chunks.isEmpty()) {
            val what = heard.entries.joinToString(", ") {
                "%02x/%02x×%d".format(it.key shr 8, it.key and 0xFF, it.value)
            }.ifEmpty { "(no 0x00/0x27 frames)" }
            log("datalink: drone list FAILED $tState after ${blob.size()}B data; rx [$rx]; frames $what")
            return emptyList()
        }
        log("datalink: drone list OK $tState — ${chunks.size} chunks, rx [$rx]")
        // A gap mid-stream shifts the record phase, so say so rather than emitting silent garbage —
        // [DroneManifest.decode] drops whatever no longer parses as a real (folder, number).
        if (!chunksComplete(chunks))
            log("datalink: drone list INCOMPLETE (chunks ${chunks.keys.sorted()}) — decoding what landed")
        return DroneManifest.decode(DroneManifest.assemble(chunks.values.toList()))
    }

    /** True if [pl] is a `0x4a` state frame of [subtype] carrying [seq] — the drone's transfer signal. */
    private fun isState(pl: ByteArray, subtype: Int, seq: Int): Boolean =
        pl.size >= 6 && (pl[0].toInt() and 0xFF) == 0x4A && (pl[1].toInt() and 0xFF) == subtype &&
            ((pl[4].toInt() and 0xFF) or ((pl[5].toInt() and 0xFF) shl 8)) == seq

    private fun chunksComplete(chunks: Map<Int, DroneManifest.Chunk>): Boolean {
        if (0 !in chunks) return false
        val last = chunks.keys.max()
        return (0..last).all { it in chunks } && chunks[last]!!.isFinal
    }

    // ---- thumbnails over the datalink --------------------------------------------------------------

    private class DroneJob(val work: () -> Unit) { @Volatile var done = false }

    private val droneJobs = java.util.concurrent.ConcurrentLinkedQueue<DroneJob>()

    /**
     * Run [work] on the drone keep-alive thread and wait for it.
     *
     * **Everything that touches the socket must go through here.** The keep-alive loop is permanently
     * in `recvAll`, so any other thread calling it gets starved — that is exactly what broke pagination
     * (`nothing for cursor=…  after 0B; heard (no valid DUML at all)`: the page fetch received literally
     * zero bytes because the keep-alive had already drained them) and what stalled thumbnails part-way
     * down the grid. One thread owns the socket; callers queue.
     */
    private fun onDroneThread(timeoutMs: Long, work: () -> Unit): Boolean {
        if (!keepAliveOn) return false
        val j = DroneJob(work)
        droneJobs.add(j)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!j.done && System.currentTimeMillis() < deadline) runCatching { Thread.sleep(25) }
        return j.done
    }

    /**
     * A drone needs the uplink stream kept running or it stops talking to us, and it wants none of the
     * camera's playback/status polling — so it gets its own loop, which doubles as the server for
     * [fetchNextPage].
     */
    override fun startKeepAlive() {
        keepAliveOn = true
        Thread {
            while (keepAliveOn) {
                runCatching {
                    val j = droneJobs.poll()
                    if (j != null) { runCatching { j.work() }; j.done = true }
                    else { dronePump(250); sendAck() }
                }
            }
        }.apply { isDaemon = true; name = "datalink-drone-keepalive" }.start()
    }

    // ---- telemetry ---------------------------------------------------------------------------------

    /**
     * Decode the drone's telemetry for the status pill.
     *
     * A drone wraps its pushes inside `0x51/0x01` tunnel frames, so a top-level frame scan steps over
     * them. [scanFrames] finds nested frames, so the battery push comes through.
     *
     * Battery percent is the same field as a camera's: `0x0D/0x02` byte 20. Ground-truthed across three
     * captures of this Mavic — 62% → 34% → 26%, rock steady within each session and matching the 20–30%
     * DJI Fly showed at the time.
     */
    private fun parseDroneStatus(raw: ByteArray) {
        if (raw.isEmpty()) return
        for ((set, cmd, p) in scanFrames(raw)) {
            when {
                // Storage uses the SAME field layout as a camera, so reuse that decode verbatim.
                // Read off a Mavic 3: 0x02/0xDC @6/@10 = 243702/152763 MiB (a 238 GB card, 149 GB free)
                // and @24/@28 = 8091/8090 MiB (its 8 GB internal). 0x02/0x80 @5/@9 is the active store.
                set == 0x02 && (cmd == 0x80 || cmd == 0xDC) -> applyStatusFrame(set, cmd, p)
                set == 0x0D && cmd == 0x02 && p.size >= 21 -> {
                    // Percent @20, pack mV @1, signed mA @5 — all as on a camera. NOT delegated to
                    // applyStatusFrame, because its 0x0D/0x02 branch also reads @27/@32 as dock-attached
                    // and charging: Osmo dock semantics that would show a dock indicator for an aircraft.
                    val pct = p[20].toInt() and 0xFF
                    val mv = (p[1].toInt() and 0xFF) or ((p[2].toInt() and 0xFF) shl 8)
                    val ma = (p[5].toInt() and 0xFF) or ((p[6].toInt() and 0xFF) shl 8) or
                        ((p[7].toInt() and 0xFF) shl 16) or ((p[8].toInt() and 0xFF) shl 24)
                    status = status.copy(
                        batteryPercent = if (pct in 0..100) pct else status.batteryPercent,
                        batteryMilliVolts = if (mv in 1..30_000) mv else status.batteryMilliVolts,
                        batteryMilliAmps = ma,
                    )
                }
            }
        }
        emitStatusIfChanged()
    }

    // ---- the 0x51 session-open ---------------------------------------------------------------------

    /**
     * DJI Fly's `0x51/0x13` identity frame, replayed verbatim (outer `0x51/0x01`, target `0xe93b`,
     * wrapping the inner `0x51/0x13` addressed `0xee`→`0xe9` — addresses outside the camera's
     * `(id shl 5) or type` scheme, hence `sendDumlRaw`). Two fields are refreshed per send: a `u32`
     * frame counter and a `u32` ms uptime, at fixed offsets in the inner payload, then the inner CRC16
     * is recomputed.
     *
     * **NOT established to be a gate.** The capture's ordering (beacon → app reply → first command) first
     * read as proof the reply unlocks the drone. It isn't: the session only came up moments earlier, so
     * that ordering is just "traffic starts once the session exists". Sending this made no difference on
     * hardware. Kept because DJI Fly does send it and it is harmless, not because it is known to be
     * required.
     *
     * TODO: the UUID is the one DJI Fly minted on this phone. If this turns out to matter, test whether
     * an arbitrary UUID is accepted rather than shipping a captured identity.
     */
    private val droneIdentityFrame = hex(
        "5544041aeee97c000051130004020037386565383937622d643231392d343964642d" +
            "000401040000000000000100000101018d01000084dc22000000e00c00000000" +
            "f5e439fdb2ae020a000000ffffffff010000000000000000"
    )
    private var droneIdentityCounter = 0x018D
    private val droneStartMs = System.currentTimeMillis()

    /**
     * The 19-byte app identity in `0x51/0x06`. This is DJI Fly's, taken from the capture — a value the
     * drone is known to accept. Substituting our own is untested and would be its own experiment: the
     * drone already discriminates on the pairing token, so it may well check this too.
     */
    private val appId51 = "78ee897b-d219-49dd-".toByteArray(Charsets.US_ASCII)

    /** Common 22-byte wrapper tail; byte 5 is the counter `sendDumlRaw` re-stamps per frame. */
    private val trailing51 = hex("39fdb2ae020100000079102e9b010000000000000000")

    /** The drone's serial, read from its own `0x51/0x13` beacon. Length varies by model. */
    @Volatile private var droneSerial: ByteArray? = bleSerial?.first

    /** The byte this aircraft puts in front of its serial: `0x11` on a Mavic 3, `0x24` on a Neo 2. */
    @Volatile private var serialTag = bleSerial?.second ?: 0x11

    /** Every `0x51` sub-command seen, so a drone that never beacons is distinguishable from one whose
     *  beacon we failed to parse. Those need completely different fixes. */
    private val seen51 = LinkedHashMap<Int, Int>()
    /** First `0x51/0x13` payload seen, kept verbatim for the log when the serial can't be read out. */
    @Volatile private var beacon13: ByteArray? = null

    /** Everything the aircraft sends before we have a serial — see [DroneFrameCensus]. Collected on
     *  every session (it stops the moment a serial latches, so a Mavic pays almost nothing) and only
     *  printed when the open fails, which is the one time anybody needs it. */
    private val census = DroneFrameCensus { parseDroneSerial(it) }

    /** The serial-shaped run in a beacon payload — see [DroneSerial] for why it's found by shape. */
    internal fun parseDroneSerial(payload: ByteArray): Pair<ByteArray, Int>? = DroneSerial.inPayload(payload)

    /** Watch received frames for the beacon and latch the serial. */
    private fun latchDroneSerial(raw: ByteArray) {
        if (droneSerial != null) return
        for ((set, cmd, pl) in scanFrames(raw)) {
            if (set != 0x51 || cmd != 0x01 || pl.size < 13 || (pl[0].toInt() and 0xFF) != 0x55) continue
            val ln = (pl[1].toInt() and 0xFF) or ((pl[2].toInt() and 0x03) shl 8)
            if (ln > pl.size) continue
            val inner = pl[10].toInt() and 0xFF
            seen51[inner] = (seen51[inner] ?: 0) + 1
            // 0x13 is the unprompted identity beacon; 0x08 is the session-open challenge, which names
            // the serial too — and unlike the beacon, it arrives on *every* airframe that answers the
            // open at all, whatever shape its beacon takes.
            if (inner != 0x13 && inner != 0x08) continue
            val body = pl.copyOfRange(11, ln - 2)
            if (inner == 0x13 && beacon13 == null) beacon13 = body
            if (inner == 0x13 && ignoreBeaconSerial) continue   // --ez nobeacon: force the challenge path
            parseDroneSerial(body)?.let { (serial, tag) ->
                droneSerial = serial
                serialTag = tag
                log("datalink: drone serial ${String(serial, Charsets.US_ASCII)} " +
                    "(${serial.size} chars, tag 0x%02x, from 0x51/0x%02x)".format(tag, inner))
            }
        }
    }

    /** What the drone was actually saying when we failed to find a serial — the difference between
     *  "never beaconed" and "beaconed in a shape we don't parse". */
    private fun logBeaconDiagnostics() {
        val inner = seen51.entries.joinToString(", ") { "0x%02x×%d".format(it.key, it.value) }
        log("datalink: 0x51 inner cmds seen: ${if (inner.isEmpty()) "NONE" else inner}")
        beacon13?.let {
            // Don't claim it was unreadable when we're the ones who ignored it — that reads as a
            // parser bug and sends whoever's debugging this straight down the wrong hole.
            val why = if (ignoreBeaconSerial) "was ignored on purpose (nobeacon)"
                      else "carried no readable serial"
            log("datalink: a 0x51/0x13 beacon DID arrive but $why — payload " +
                it.copyOfRange(0, minOf(64, it.size)).joinToString("") { b -> "%02x".format(b) })
        }
        // "NONE" only says this airframe isn't a Mavic. The census says what it IS doing.
        census.report().forEach { log("datalink: $it") }
    }

    /** One `0x51`-channel frame: inner DUML (target 0xe9ee) + the shared 22-byte tail. */
    private fun frame51(cmd: Int, flags: Int, innerId: Int, payload: ByteArray): ByteArray =
        DjiMessage(0xE9EE, innerId, flags or (0x51 shl 8) or (cmd shl 16), payload).encode() + trailing51

    /**
     * `00 00 <tag> <serial> 00` — the body of both `0x51/0x08` and `0x51/0x06` responses.
     *
     * The tag is echoed from the aircraft's own beacon rather than fixed at a Mavic's `0x11`, since a
     * Neo 2 uses `0x24` and a response carrying the wrong one is unlikely to be accepted.
     */
    private fun serialBody(serial: ByteArray) =
        byteArrayOf(0, 0, serialTag.toByte()) + serial + byteArrayOf(0)

    /** Step 1. **Carries no serial** — which is what makes it safe to send before we know one. */
    private fun droneOpenRequest() = frame51(0x02, 0x40, 0x007C, hex("0501040100"))

    /** Steps 3–5, all of which do need the serial the drone named in its step-2 challenge. */
    private fun droneOpenResponses(serial: ByteArray) = listOf(
        frame51(0x08, 0xC0, 0x0001, serialBody(serial)),                      // 3. answer the challenge
        // 4. our id + the serial — one extra 00 ahead of the shared `00 00 <tag> <serial> 00` body
        frame51(0x06, 0x40, 0x007D,
            byteArrayOf(0x04, 0x02, 0x00) + appId51 + byteArrayOf(0) + serialBody(serial)),
        frame51(0x06, 0xC0, 0x0000, serialBody(serial)),                      // 5. answer its 0x51/0x06
    )

    /**
     * **The session-open handshake — this is what actually unlocks the drone.**
     *
     * Found by capturing DJI Fly connecting to a *cold* Mavic (drone power-cycled, app force-stopped).
     * For the first 8 s the drone sends DJI Fly the same near-empty keepalives it sends us — ~2 DUML
     * frames/second. Then the app sends `0x51/0x02`, a short mutual handshake runs on the `0x51` channel,
     * and one second later the drone is streaming ~1200 frames/second and answers everything:
     *
     * 1. app → `0x51/0x02` fl=0x40, payload `05 01 04 01 00`  (open request)
     * 2. drone → `0x51/0x08` fl=0x40  challenge: its serial + the app's UUID
     * 3. app → `0x51/0x08` fl=0xC0  response echoing the serial
     * 4. app → `0x51/0x06` fl=0x40  UUID + serial
     * 5. drone → `0x51/0x06` fl=0xC0, then its own `0x51/0x06` fl=0x40, which the app answers fl=0xC0
     *
     * We had only ever answered the `0x51/0x13` identity beacon, so step 1 never happened and the drone
     * never challenged us. That is why every command was ignored: there was no session to serve them on.
     *
     * **Step 2 is the serial's real source.** Reading it out of the beacon instead is a per-airframe
     * guess — the beacon's shape differs by model (a Mavic 3 tags it `0x11`, a Neo 2 `0x24`, a Mini 3
     * apparently doesn't send one we recognise at all), while the challenge names it the same way on
     * anything that answers step 1. So the beacon is now only a *fast path*: step 1 goes out either
     * way, and the challenge supplies the serial when the beacon didn't.
     */
    private fun droneSessionOpen() {
        val sink = java.io.ByteArrayOutputStream()
        // Fast path: a Mavic 3 beacons its serial unprompted, so give it a moment to. [dronePump]
        // latches per-datagram, which also fixes a straddle bug in the old probe loop — it rescanned
        // whole datagrams concatenated *with their headers*, so a beacon spanning a packet boundary
        // failed CRC and vanished, exactly as documented for manifest chunks.
        bleSerial?.let { (s, tag) ->
            log("datalink: drone serial ${String(s, Charsets.US_ASCII)} (${s.size} chars, " +
                "tag 0x%02x) — already known from BLE, no datalink beacon needed".format(tag))
        }
        val waitUntil = System.currentTimeMillis() + if (ignoreBeaconSerial) 0 else 3000
        while (droneSerial == null && System.currentTimeMillis() < waitUntil) dronePump(200)
        if (ignoreBeaconSerial) log("datalink: nobeacon — skipping the beacon fast path on purpose")

        // Send the open request whether or not we know a serial. It carries none — and the drone's
        // reply to it is the step-2 challenge, which *names the serial*. Bailing here was circular:
        // no serial meant no open, and no open meant no challenge to learn the serial from. Every
        // airframe whose beacon we can't parse died on that loop without us ever asking it anything.
        sendDumlRaw(0xE93B, 0x51, 0x01, droneOpenRequest())
        log("datalink: 51/02 open sent" + when {
            droneSerial == null -> " with no serial known — listening for the challenge to name one"
            bleSerial != null -> " (serial from BLE)"
            else -> " (serial from the datalink beacon)"
        })
        // Give the challenge real time when it's our only remaining source. Measured on a Mavic 3, no
        // 0x51/0x08 arrived within 400 ms of the open — the old window was simply too short to tell a
        // slow challenge apart from an aircraft that never sends one, and those need different fixes.
        if (droneSerial == null) {
            val deadline = System.currentTimeMillis() + 2000
            while (droneSerial == null && System.currentTimeMillis() < deadline) dronePump(150, sink)
        } else dronePump(400, sink)

        val serial = droneSerial ?: run {
            log("datalink: no serial from the beacon OR the 0x51/0x08 challenge — cannot open the session")
            logBeaconDiagnostics()
            return
        }
        for (frame in droneOpenResponses(serial)) {
            sendDumlRaw(0xE93B, 0x51, 0x01, frame)
            // DJI Fly paces these ~230 ms, ~2 ms, ~100 ms apart; a uniform gap is close enough.
            dronePump(200, sink)
        }
        sendAck()
        // Did the drone challenge us back? It answers a good 0x51/0x02 with 0x51/0x08 carrying its
        // serial; anything other than the 0x13 beacon here means the open was at least understood.
        val inner = HashMap<Int, Int>()
        for ((set, cmd, pl) in scanFrames(sink.toByteArray())) {
            if (set != 0x51 || cmd != 0x01 || pl.size < 13 || pl[0].toInt() != 0x55) continue
            inner.merge(pl[10].toInt() and 0xFF, 1, Int::plus)
        }
        log("datalink: 51-channel replies: " +
            (inner.entries.joinToString(", ") { "51/%02x×%d".format(it.key, it.value) }
                .ifEmpty { "NONE — drone ignored the open" }))
    }

    /**
     * Once the session is open the identity beacon must keep being answered, ~2×/s, for as long as we
     * want it to stay open. The drone beacons its serial at that rate and the reference app answers
     * every one.
     *
     * Answering it only at session-open is what capped media browsing at ~30 seconds: measured across
     * four instrumented sessions, every media query at t ≤ 28.5 s succeeded and every one at t ≥ 28.9 s
     * came back empty — while the drone was still pushing ~850 telemetry packets per query. The uplink
     * stream keeps the *link* alive, so nothing looks wrong; it is the `0x51` data session underneath
     * that lapses. See ROADMAP #14.
     */
    @Volatile private var beaconOn = false
    private var lastBeaconMs = 0L

    /** Answer the drone's identity beacon. Cheap — call it once per receive round. */
    private fun sendDroneIdentity() {
        val p = droneIdentityFrame.copyOf()
        val innerLen = (p[1].toInt() and 0xFF) or ((p[2].toInt() and 0x03) shl 8)
        if (innerLen < 15 || innerLen > p.size) return
        putU32(p, 11 + 39, (++droneIdentityCounter).toLong())          // frame counter, steps by 1
        putU32(p, 11 + 43, System.currentTimeMillis() - droneStartMs)  // ms uptime, stepped ~200/frame
        val crc = DjiCrc.computeCrc16(p.copyOfRange(0, innerLen - 2))
        p[innerLen - 2] = (crc and 0xFF).toByte()
        p[innerLen - 1] = ((crc shr 8) and 0xFF).toByte()
        sendDumlRaw(0xE93B, 0x51, 0x01, p)
    }

    private fun putU32(b: ByteArray, i: Int, v: Long) {
        b[i] = (v and 0xFF).toByte()
        b[i + 1] = ((v shr 8) and 0xFF).toByte()
        b[i + 2] = ((v shr 16) and 0xFF).toByte()
        b[i + 3] = ((v shr 24) and 0xFF).toByte()
    }

    // ---- the prelude -------------------------------------------------------------------------------

    private class DroneCmd(val set: Int, val cmd: Int, val rType: Int, val rId: Int, val payload: String)

    /**
     * Everything DJI Fly sends the Mavic between the session coming up and its media query, captured
     * verbatim and replayed in wire order.
     *
     * We had matched the query itself byte-for-byte — routing header, sequence window, symmetric
     * 9003→9003 port, identical `0x4a` body — and the drone still answered nothing, so what was left was
     * *state*. Replaying the lot was the cheap way to find out; it has never been bisected down to the
     * command that actually matters (`0x07/0x44`, sent 640 ms before the query, is the obvious suspect;
     * `0x02/0xeb` and `0x02/0x4d` look like mode switches too).
     *
     * Receiver addressing is recovered from each frame's DUML target (`(id shl 5) or type`). Note
     * `0x07/0x45` carries DJI Fly's own install UUID as the pairing token — the datalink equivalent of
     * the "DJI FLY" BLE token that already proved to be the gate for WiFi credentials.
     */
    private val dronePrelude = listOf(
        DroneCmd(0x0D, 0x01, 0x0B, 0, "000000000000000000"),
        DroneCmd(0x00, 0x51, 0x08, 1, "06"),
        DroneCmd(0x00, 0x4F, 0x0F, 2, "0100000000e8030000"),
        DroneCmd(0x03, 0xF8, 0x03, 0, "0b163bde0b163bdf0b163be0713d65cbef979092ef3963f5"),
        DroneCmd(0x00, 0xE5, 0x0F, 3, "323201"),
        DroneCmd(0x00, 0x99, 0x08, 1, "0100060063616d657261"),
        DroneCmd(0x03, 0x20, 0x03, 0, "0360df6802d468c7ffb4f86d6a"),
        DroneCmd(0x00, 0x01, 0x0E, 0, ""),
        DroneCmd(0x04, 0x10, 0x04, 0, "0a"),
        DroneCmd(0x04, 0x12, 0x04, 0, "664900000000000000000001"),
        DroneCmd(0x01, 0x01, 0x09, 0, "00000000009100000091"),
        DroneCmd(0x07, 0x45, 0x07, 0, "2062626639393934662d613164612d343464622d623165302d396438383963356200"),
        DroneCmd(0x00, 0x32, 0x0F, 3, "3131000000"),
        DroneCmd(0x08, 0x42, 0x0F, 0, "3c000000"),
        DroneCmd(0x08, 0x41, 0x0F, 0, "02"),
        DroneCmd(0x03, 0xDA, 0x03, 0, "0a01"),
        DroneCmd(0x02, 0xEB, 0x01, 0, "00ff87112700000a0001000800"),
        DroneCmd(0x00, 0x4A, 0x08, 1, "ea0708010f2e1e"),
        DroneCmd(0x02, 0x4D, 0x0F, 0, "80"),
        DroneCmd(0x02, 0xB5, 0x01, 0, "00000000"),
        DroneCmd(0x03, 0xF9, 0x03, 0, "5e25cba201"),
        DroneCmd(0x04, 0x67, 0x04, 0, "02460101"),
        DroneCmd(0x0D, 0x03, 0x0B, 0, "00000000"),
        DroneCmd(0x0D, 0x04, 0x0B, 0, "000000000000000000"),
        DroneCmd(0x03, 0x34, 0x03, 0, ""),
        DroneCmd(0x03, 0xAF, 0x03, 0, "04689ced7c000000b8"),
        DroneCmd(0x07, 0x30, 0x09, 0, "45530000455300000100"),
        DroneCmd(0x07, 0x18, 0x07, 0, "ff455300"),
        DroneCmd(0x07, 0x44, 0x07, 0, ""),
    )

    /**
     * Replay [dronePrelude]. Also the cleanest probe we have of whether the drone accepts our commands
     * at all: it only advances the seq it echoes in r0-1 once it has taken a packet, so an echo that
     * moves means we're being heard, and one that sits still means every command is being dropped.
     */
    private fun runDronePrelude() {
        val before = tx.cameraChannel
        for (c in dronePrelude) {
            sendDuml(c.set, c.cmd, hex(c.payload), receiverType = c.rType, receiverId = c.rId)
            dronePump(110)   // the uplink must not stall while we walk the prelude
        }
        sendAck()
        log("datalink: drone prelude — %d cmds, r0-1 0x%04x → 0x%04x".format(
            dronePrelude.size, before, tx.cameraChannel))
    }

    // ---- the uplink stream -------------------------------------------------------------------------

    @Volatile private var uplinkRunning = false

    /**
     * DJI Fly's continuous uplink — and, measured across two captures, **95% of everything it ever sends
     * the drone** (80307 × `0x02/0x82` + 19863 × `0x02/0xdc` + 15621 × `0x04/0x1c` out of 122159 packets,
     * ~860/s sustained for the whole session).
     *
     * This is almost certainly the "app is attached and alive" stream a DJI airframe expects from its
     * controller. It also explains the echo: the drone only advances the seq it mirrors back once it
     * takes a packet, so DJI Fly's echo tracks *because* of this stream, while ours sat frozen at the
     * handshake channel through 29 assorted commands.
     *
     * Note the sender byte is `0x01`, not the `0x02` `sendDuml` hardcodes, hence `sendDumlRaw`.
     */
    private fun droneUplinkTick() {
        sendDumlRaw(0x1C01, 0x02, 0x82, uplink82)
        sendDumlRaw(0x1C01, 0x02, 0xDC, uplinkDc)
        sendDumlRaw(0x1C04, 0x04, 0x1C, uplink1c)
    }

    private val uplink82 =
        hex("000200000000000000000000000000000000000000000000000000000000000000000000000000000000")
    private val uplinkDc = hex("001202000001f6b70300bb540200000000000000000001019b1f00009a1f00000000000000000000")
    private val uplink1c = hex("38")

    /**
     * DUML frames per second the drone is currently sending us — the single clearest signal of whether
     * the session is open. Measured on the wire: a drone that has NOT accepted the client sends ~2
     * frames/s of near-empty keepalive (ours did, for 43 s); one second after the `0x51` handshake it
     * sends DJI Fly 600–1200 frames/s.
     */
    private fun droneRxFrameRate(): Int {
        var frames = 0
        val t0 = System.nanoTime()
        while (System.nanoTime() - t0 < 1_000_000_000L) {
            for (dg in recvAll(40)) frames += scanFrames(dg).size
            droneUplinkTick()
        }
        return frames
    }

    /**
     * Pump the uplink for [ms], draining anything that arrives into [sink].
     *
     * With [manifestStream] the sink instead receives a **reassembled data stream**: only `pktType 0x03`
     * packets, each stripped of its 8-byte transport + 12-byte routing header.
     *
     * This matters more than it looks. A manifest reply is ~4 kB and arrives as several back-to-back
     * 1472-byte packets, with individual DUML frames spanning packet boundaries — and every packet
     * carries its own 20-byte header. Concatenating whole datagrams injects those headers mid-frame, so
     * every chunk that straddles a boundary fails CRC and silently disappears: on hardware we recovered
     * chunks [0, 3, 4] and 11 of 45 files. Stripping the headers first yields all 5 chunks and 45/45
     * records, verified against both DJI Fly captures.
     */
    private fun dronePump(
        ms: Long,
        sink: java.io.ByteArrayOutputStream? = null,
        manifestStream: Boolean = false,
    ) {
        val deadline = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < deadline) {
            for (dg in recvAll(20)) {
                if (dg.size > 6) rxByType.merge(dg[6].toInt() and 0xFF, 1, Int::plus)
                // Telemetry rides pktType 0x01 (small keepalive-sized packets). Decode it HERE rather
                // than only when idle: the keep-alive is busy serving thumbnails for minutes on end, and
                // status parsed only between jobs meant battery and storage never appeared at all.
                val pktType = if (dg.size > 6) dg[6].toInt() and 0xFF else -1
                if (dg.size > 8 && pktType == 0x01) parseDroneStatus(dg)
                // The beacon has only ever been *seen* on pktType 0x01 — but "only ever" is one
                // airframe. Until we have a serial, look on every packet type and census what does
                // arrive; a model that beacons elsewhere was previously invisible, indistinguishable
                // in the log from one that never beacons at all.
                if (dg.size > 8 && droneSerial == null) {
                    latchDroneSerial(dg)
                    census.record(pktType, dg)
                }
                if (sink == null) continue
                if (!manifestStream) sink.write(dg)
                else if (dg.size > 20 && (dg[6].toInt() and 0xFF) == 0x03) sink.write(dg, 20, dg.size - 20)
            }
            droneUplinkTick()
            // Keep the 0x51 data session alive. Rides the pump rather than the keep-alive loop, so it
            // also fires during a long list or thumbnail transfer — which is exactly when the session
            // used to lapse.
            val now = System.currentTimeMillis()
            if (beaconOn && now - lastBeaconMs >= 500) { lastBeaconMs = now; sendDroneIdentity() }
        }
    }
}
