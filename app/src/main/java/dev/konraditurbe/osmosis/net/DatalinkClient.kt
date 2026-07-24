package dev.konraditurbe.osmosis.net

import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.CameraStatus
import dev.konraditurbe.osmosis.duml.DjiCrc
import dev.konraditurbe.osmosis.duml.DjiMessage
import dev.konraditurbe.osmosis.duml.OsmoCommands
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

/**
 * DUML-over-UDP datalink client. Handshakes, registers, requests the media file list (0x00/0x26),
 * and parses the DJI_/CAM_ paths out of the 0x00/0x27 response (ported from osmo-download's
 * file_list.py). Wire layers per packet: [8B udp hdr][12B routing hdr][DUML frame]; the app's UDP
 * sequence must start at (camera_channel + 8), learned from the camera's heartbeat routing header.
 *
 * The datalink UDP [port] differs by camera family:
 *  - Osmo 360 / Nano / Pocket 3 = 9004, and need a TCP-7001 poke to arm it ([tcpPoke] = true).
 *  - Xtra Edge Pro (= DJI Action 5 Pro) = 10004, no poke (discovered via pcap; undocumented).
 *
 * After [fetchFileList] the socket stays OPEN: the Action 5 tears down its WiFi AP the instant the
 * datalink goes idle, so call [startKeepAlive] to hold it up during browse/download, then [close].
 * Blocking; call on a background thread. The process must already be bound to the camera network.
 */
class DatalinkClient(
    private val log: (String) -> Unit,
    private val port: Int = 9004,
    private val tcpPoke: Boolean = true,
) {
    private val handshake = hex(
        "b88764006400c005140000640000019001c005140000640014006400c00514000064000101040102"
    )
    private val paramSubs = listOf(
        "camcap_mode_profile", "camcap_video_format", "camcap_fov", "camcap_iso",
        "camcap_photo_storage_format", "camcap_color_mode", "cam_storage", "cam_status",
    )

    private val VIDEO_EXTS = setOf("MP4", "MOV", "OSV", "INSV")

    private var sessionId = 0
    private var udpSeq = 0
    private var dumlSeq = 0xA000
    private var cmdCounter = 0
    private var lastCamSeq = 0xB887

    private lateinit var sock: DatagramSocket
    private lateinit var cam: InetAddress
    @Volatile private var keepAliveOn = false

    /** Set to receive live camera status (battery/storage/firmware) parsed from status pushes. */
    @Volatile var onStatus: ((CameraStatus) -> Unit)? = null

    /** Progress 0..100 through fetchFileList (handshake → registration → manifest), for a UI bar. */
    @Volatile var onFetchProgress: ((Int) -> Unit)? = null
    private var status = CameraStatus()
    private var lastSig = ""        // last display signature fired to onStatus (throttles UI updates)
    private var lastBattSig = ""    // dock-relevant bytes of 0x0d/02; log only on change (#5)

    /** False when the datalink handshake never landed — i.e. wrong UDP port for this camera. Lets
     *  the caller distinguish "no media" from "nothing answered" and retry the alternate port. */
    @Volatile var handshakeOk = false
        private set

    /**
     * Open the udp/[port] datalink and bring the session up to the point commands are accepted: TCP
     * poke, handshake (retry until the camera answers), drain heartbeats to learn its channel + start
     * our sequence, then register (device-info, register, gimbal-init, param subscriptions). Leaves
     * [sock] open and the send sequence synced on success. Shared by [fetchFileList] and the delete
     * flow — the delete re-runs this so it rides the same fresh, in-window session the list does.
     */
    private fun openAndRegister(ip: String, subscribe: Boolean = true): Boolean {
        handshakeOk = false
        cam = InetAddress.getByName(ip)
        sock = DatagramSocket().apply { soTimeout = 200 }
        sessionId = Random.nextInt(0x1000, 0xFFFE)

        if (tcpPoke) {
            runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(cam, 7001), 1200)
                    s.getOutputStream().write(OsmoCommands.setPairingPin("osmo"))
                    s.getOutputStream().flush()
                    Thread.sleep(400)
                }
            }
        }

        var ok = false
        for (attempt in 0 until 20) {
            sendRaw(0x00, handshake)
            for (r in recvAll(350)) if (r.size >= 8 && (r[6].toInt() and 0xFF) == 0x00) { ok = true; break }
            if (ok) break
        }
        if (!ok) { log("datalink: handshake FAILED on udp/$port"); runCatching { sock.close() }; return false }
        handshakeOk = true
        log("datalink: handshake OK on udp/$port")
        onFetchProgress?.invoke(8)

        // Drain heartbeats, learn camera channel, set our seq start.
        repeat(5) { recvAll(400); sendAck() }
        udpSeq = (lastCamSeq + 8) and 0xFFFF
        onFetchProgress?.invoke(16)

        // Registration.
        sendDuml(0x00, 0x81, appDeviceInfo(), receiverType = 0x08, receiverId = 2, cmdType = 4)
        recvAll(400); sendAck()
        sendDuml(0x00, 0x88, hex("170008237b41505000000000000002"), receiverType = 0x08, receiverId = 1)
        recvAll(400); sendAck()
        sendDuml(0x03, 0xDA, hex("05ffffffff"), receiverType = 0x03, receiverId = 0)
        recvAll(400); sendAck()
        // Status subscriptions only feed the live pill; the delete session skips them to save ~4 s.
        if (subscribe) {
            var subId = 0x69DF
            for ((i, p) in paramSubs.withIndex()) {
                sendDuml(0x00, 0x99, subscription(p, subId), receiverType = 0x08, receiverId = 1)
                subId++; recvAll(300); sendAck()
                onFetchProgress?.invoke(22 + i * 3) // ramp through the 8 subscriptions
            }
            repeat(4) { recvAll(400); sendAck() }
        }
        return true
    }

    /** Handshake + register + list. Socket stays open on success. Empty list on failure. */
    fun fetchFileList(ip: String): List<CameraFile> {
        if (!openAndRegister(ip)) return emptyList()
        onFetchProgress?.invoke(50)

        // File-list request (0x00/0x26). Records stream back over several packets. Page the list up
        // front (indices 0.., 42.., 64..), then collect only until the parsed record count stops
        // growing — instead of always waiting a fixed 15 s.
        sendDuml(0x00, 0x26, hex(
            "4a002a10010000000000010000002d000d0100ffffffffffffffff000100000000000000000000000000"
        ), receiverType = 0x01, receiverId = 0)
        val blob = java.io.ByteArrayOutputStream()
        var lastCount = -1
        var stable = 0
        for (batch in 0 until 15) {
            val resps = recvAll(800); sendAck()
            for (r in resps) blob.write(r)
            if (batch == 1) sendDuml(0x00, 0x26, hex("4a040e1001000000000001000000"),
                receiverType = 0x01, receiverId = 0)
            if (batch == 2) sendDuml(0x00, 0x26, hex(
                "4a002a10020000000000010000402d000d0100ffffffffffffffff000100000000000000000000000000"
            ), receiverType = 0x01, receiverId = 0)
            val count = distinctPaths(blob)
            if (count != lastCount) log("datalink: $count files (batch $batch)")
            onFetchProgress?.invoke((55 + batch * 6).coerceAtMost(95))
            if (batch >= 4 && count > 0 && count == lastCount) { if (++stable >= 2) break } else stable = 0
            lastCount = count
        }

        val raw = blob.toByteArray()
        val bytes = manifestBytes(raw) // reassemble the fragmented file-list frames (see manifestBytes)
        val files = decodeManifest(bytes)
        log("datalink: parsed ${files.size} media files (${bytes.size}B)")
        onFetchProgress?.invoke(100)
        return files
    }

    /**
     * Keep the datalink alive (ACK ~2×/s so the AP doesn't sleep) and, Mimo-style, poll the camera
     * for status — heartbeat 0x02/0x8E, state query 0x02/0xA0, status poll 0x02/0x61 — decoding the
     * pushed status frames (battery/storage/firmware) as they arrive.
     */
    fun startKeepAlive() {
        if (keepAliveOn) return
        keepAliveOn = true
        status = CameraStatus()
        lastSig = ""
        lastBattSig = ""
        Thread {
            var tick = 0
            while (keepAliveOn) {
                runCatching {
                    parseStatus(recvAll(200))
                    sendAck()
                    sendDuml(0x02, 0x8E, hex("00011400"), receiverType = 0x01, receiverId = 0)
                    if (tick % 3 == 0) sendDuml(0x02, 0xA0, ByteArray(0), receiverType = 0x01, receiverId = 0)
                    if (tick % 6 == 0) sendDuml(0x02, 0x61, ByteArray(0), receiverType = 0x01, receiverId = 0)
                }
                runCatching { Thread.sleep(300) }
                tick++
            }
        }.apply { isDaemon = true; name = "datalink-keepalive" }.start()
    }

    /** Scan datagrams for DUML status frames (SOF 0x55; cmd set/id at header offsets 9/10) and decode. */
    private fun parseStatus(datagrams: List<ByteArray>) {
        for (d in datagrams) {
            var i = 0
            while (i + 11 <= d.size) {
                if ((d[i].toInt() and 0xFF) != 0x55) { i++; continue }
                val len = ((d[i + 1].toInt() and 0xFF) or ((d[i + 2].toInt() and 0xFF) shl 8)) and 0x3FF
                if (len < 13 || i + len > d.size) { i++; continue }
                // Verify the header CRC8 before believing this is a frame: a bare 0x55 scan happily
                // matches raw bytes inside the handshake/routing headers (e.g. the 0xB887 channel
                // constant), which invented a phantom "device 0x07/4 via 0xb8/87" in the log.
                if (DjiCrc.computeCrc8(d.copyOfRange(i, i + 3)) != (d[i + 3].toInt() and 0xFF)) { i++; continue }
                val set = d[i + 9].toInt() and 0xFF
                val id = d[i + 10].toInt() and 0xFF
                applyStatusFrame(set, id, d.copyOfRange(i + 11, i + len - 2))
                i += len
            }
        }
        val sig = displaySig(status)
        if (sig != lastSig) { lastSig = sig; onStatus?.invoke(status) }
    }

    // Includes the power state so docking/undocking refreshes the pill even when the percentage
    // hasn't moved; mV is bucketed to 0.05 V so normal ripple doesn't spam the UI.
    private fun displaySig(s: CameraStatus) =
        "${s.batteryPercent}|${s.sdInserted}|${s.storageFreeMb / 1024}|${s.storageTotalMb / 1024}" +
            "|${s.docked}|${s.charging}|${s.batteryMilliVolts / 50}"

    private fun applyStatusFrame(set: Int, id: Int, p: ByteArray): Boolean {
        when {
            set == 0x02 && id == 0x80 && p.size >= 13 -> {
                // Storage of the active store: total = u32-LE MiB @ byte 5, free = u32-LE MiB @ byte 9.
                val total = u32le(p, 5).toInt()
                val free = u32le(p, 9).toInt()
                status = status.copy(
                    storageTotalMb = if (total in 1..50_000_000) total else status.storageTotalMb,
                    storageFreeMb = if (free in 0..50_000_000) free else status.storageFreeMb,
                ); return true
            }
            set == 0x02 && id == 0xDC && p.isNotEmpty() -> {
                status = status.copy(sdInserted = (p[0].toInt() and 0x01) != 0); return true
            }
            set == 0x00 && id == 0x00 && p.size >= 6 -> {
                // GetVersion reply: NUL-separated ASCII (sdk\0name\0firmware); grab the version string.
                val text = String(p, Charsets.US_ASCII)
                val fw = Regex("""\d{2}\.\d{2}\.\d{2}\.\d{2}""").find(text)?.value
                    ?: text.split(' ').map { it.trim() }.lastOrNull { it.length in 4..24 && it.any(Char::isDigit) }
                if (!fw.isNullOrBlank()) { status = status.copy(firmware = fw); return true }
            }
            set == 0x0D && id == 0x02 && p.size >= 21 -> {
                // The dock is NOT its own DUML device (docked vs undocked sessions only ever showed
                // type 0x05 id 0), so the dock signal lives in this frame: u16@1 = pack mV,
                // i32@5 = current mA (signed, -ve = discharging), @20 = percent, @27 = dock
                // attached, @32 = taking charge. Confirmed over six live dock/undock transitions
                // (ROADMAP #5). Logged on change only — a state line, not a 1 Hz stream.
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

    /**
     * Delete files by their manifest [handles] (DUML **0x00/0x28** — the file-management cmdset, same
     * one the list `0x00/0x26` lives in; reverse-engineered from a Mimo↔Nano pcap). Returns the reply
     * status word (**0x0000 = OK**), or null on failure/timeout. **Irreversible on the SD card** — the
     * caller must confirm intent and pass only handles it means to destroy. See ROADMAP #4.
     *
     * Runs in a **fresh registered session**: the browse keep-alive loop advances our `udpSeq` past
     * the window the camera will accept, and while reads (the list) still get answered, writes get
     * silently dropped. So we tear the keep-alive session down and re-open exactly like the list fetch
     * ([openAndRegister]) — the one path the camera reliably accepts — issue the delete there, then
     * keep that session for browse. Call on a background thread (blocks ~9 s, the re-handshake cost).
     */
    fun deleteFiles(handles: List<Long>): Int? {
        if (handles.isEmpty()) return null
        val ip = (if (::cam.isInitialized) cam.hostAddress else null) ?: return null
        keepAliveOn = false
        Thread.sleep(600)                // let the keep-alive loop finish its current recv and exit
        runCatching { sock.close() }     // free udp/$port for the fresh session
        val status = runCatching { freshSessionDelete(ip, handles) }
            .getOrElse { log("datalink: delete session error: ${it.message}"); null }
        // Reuse the (already open + registered) delete session for browse — no second re-open. It has
        // no status subscriptions, so the pill freezes at its last values until the next reconnect.
        if (::sock.isInitialized && !sock.isClosed) runCatching { startKeepAlive() }
        return status
    }

    private fun freshSessionDelete(ip: String, handles: List<Long>): Int? {
        if (!openAndRegister(ip, subscribe = false)) { log("datalink: delete — fresh session open FAILED"); return null }
        // Payload mirrors the captured frame: [count:u8][handle:u32-LE …][count:u32] 00 [count:u32]
        // 01 01 00 00. The trailing 00 / 01 01 00 00 (storage selector) are verbatim from the capture.
        val b = java.io.ByteArrayOutputStream()
        b.write(handles.size and 0xFF)
        for (h in handles) b.write(le32(h.toInt()))
        b.write(le32(handles.size))
        b.write(0x00)
        b.write(le32(handles.size))
        b.write(byteArrayOf(0x01, 0x01, 0x00, 0x00))
        log("datalink: DELETE 0x00/0x28 n=${handles.size}")
        sendDuml(0x00, 0x28, b.toByteArray(), receiverType = 0x01, receiverId = 0)
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            for (d in recvAll(200)) findRespStatus(d, 0x00, 0x28)?.let {
                log("datalink: DELETE reply status=0x%04x".format(it)); return it
            }
            sendAck()
        }
        log("datalink: DELETE — no reply")
        return null
    }

    /** Scan a datagram for a DUML response frame with [set]/[id] and return its leading status word. */
    private fun findRespStatus(d: ByteArray, set: Int, id: Int): Int? {
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

    fun close() {
        keepAliveOn = false
        runCatching { sock.close() }
    }

    /**
     * Count distinct media-path fields in a byte range by structure — the same `1a … 00 00 00 01`
     * "DCIM/" TLV [decodeComposite] anchors on, read by length. Used to gauge list growth while paging
     * and to vet reassembly, so it must not depend on any naming pattern: a custom Folder/File prefix
     * still counts because the whole path string is read, not matched.
     */
    private fun countMediaPaths(bytes: ByteArray): Int {
        val seen = HashSet<String>()
        var i = 0
        while (i < bytes.size) {
            val f = readPathField(bytes, i, sub = 1, prefix = "DCIM/")
            if (f != null) { seen.add(f.value); i = f.end } else i++
        }
        return seen.size
    }

    /**
     * The file list streams back as many DUML frames (cmdset 0x00 / cmd 0x26). Concatenating the raw
     * datagrams leaves each frame's 11-byte header + 2-byte CRC spliced into the byte stream, so any
     * DCIM path that straddles a frame boundary gets those bytes injected mid-string and the file
     * silently drops out of the grid — non-deterministically, depending on which record lands on a
     * boundary. Re-stitch just the 0x00/0x26 payloads, in order, so the manifest is contiguous before
     * we decode it. Walks the whole blob (not per-datagram) so a frame split across two UDP packets is
     * still reassembled.
     */
    private fun manifestBytes(raw: ByteArray): ByteArray {
        // The file list streams back as DUML data frames (cmd 0x00/0x27) whose payload is
        //   [10-byte sub-header][chunk]:  4A 01 .. .. <seq:u16le @6> 00 00, then the chunk bytes.
        // byte1==0x01 marks a data chunk; the control frames (4A 04.. start / 4A 03.. end) are 10
        // bytes of sub-header with no chunk. The chunks reassemble into the real manifest, which opens
        // with a u32-LE file count. We must strip the sub-header before concatenating — otherwise a
        // record whose path straddles a frame boundary gets those 10 bytes injected mid-path
        // (e.g. "DCIM/DJI_" + "J….001/…") and silently drops from the grid. Concatenate in arrival
        // order (not seq-sorted): on multi-page lists the seq counter restarts per page.
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i + 13 <= raw.size) {
            if ((raw[i].toInt() and 0xFF) != 0x55) { i++; continue }
            val len = ((raw[i + 1].toInt() and 0xFF) or ((raw[i + 2].toInt() and 0xFF) shl 8)) and 0x3FF
            if (len < 13 || i + len > raw.size) { i++; continue }
            val plStart = i + 11; val plLen = len - 13
            if (plLen > 10 && (raw[plStart].toInt() and 0xFF) == 0x4A && (raw[plStart + 1].toInt() and 0xFF) == 0x01)
                out.write(raw, plStart + 10, plLen - 10) // drop the 10-byte sub-header, keep the chunk
            i += len
        }
        val bytes = out.toByteArray()
        // Guard: only use the reassembled stream if it carries at least as many intact media-path
        // fields as the raw blob, so a model that streams the list some other way falls back to the
        // old whole-blob parse. (A path split across a frame boundary won't parse as a field, which is
        // exactly the straddling case reassembly fixes.)
        return if (countMediaPaths(bytes) >= countMediaPaths(raw) && bytes.isNotEmpty()) bytes else raw
    }

    /** Distinct media paths seen so far — lets the collect loop stop once the list stops growing. */
    private fun distinctPaths(blob: java.io.ByteArrayOutputStream): Int =
        countMediaPaths(manifestBytes(blob.toByteArray()))

    private val primaryExts = setOf("MP4", "MOV", "JPG", "JPEG", "DNG", "OSV", "INSV", "HEIC")
    private val proxyExts = setOf("LRF", "LRV")

    /** Test seam: run the full raw-blob → frame-reassemble → decode pipeline on a captured manifest. */
    internal fun decodeManifestBlobForTest(rawBlob: ByteArray): List<CameraFile> =
        decodeManifest(manifestBytes(rawBlob))

    /** Test seam: decode an already-reassembled CompositePack manifest (post frame-reassembly). */
    internal fun decodeCompositeForTest(manifest: ByteArray): List<CameraFile> = decodeComposite(manifest)

    /**
     * Decode the reassembled manifest. [decodeComposite] structurally handles every CompositePack
     * layout on the Osmo line (Nano, Xtra, Action 5/6, Pocket 3 — photos and videos, stock or
     * custom-prefixed). Only a blob with *no* CompositePack media-path field at all — a genuinely
     * different list format — reaches [parseFlat]'s loose scrape, and we dump the bytes so that
     * unknown layout can be cracked (paths/filenames only — no credentials or coordinates).
     */
    private fun decodeManifest(bytes: ByteArray): List<CameraFile> {
        val comp = decodeComposite(bytes)
        if (comp.isNotEmpty()) {
            log("datalink: decoded ${comp.size} CompositePack records " +
                "(${comp.count { it.resLabel != null }} fps, ${comp.count { it.proxyPath != null }} proxies, " +
                "${comp.count { it.deletable }} deletable, ${comp.count { it.sizeBytes > 0 }} sized)")
            return comp
        }
        log("datalink: no CompositePack records — dumping manifest, falling back to flat scrape")
        dumpManifest(bytes)
        return parseFlat(bytes)
    }

    /** A length-delimited CompositePack field: its ASCII value and the offset just past it. */
    private class TlvField(val value: String, val end: Int)

    /**
     * Read a **path** field at [i] if one starts there: `1a [total:u8] 00 00 00 [sub:u8] <ascii>`,
     * where the ASCII value is `total-6` bytes (total counts the 6-byte header). Returns the value +
     * end offset only when the sub-type matches and the value is printable and carries [prefix]
     * (`DCIM/` for media, `MISC/` for the thumb). Pure tag→length→value; no content pattern.
     */
    private fun readPathField(bytes: ByteArray, i: Int, sub: Int, prefix: String): TlvField? {
        if (i + 6 > bytes.size || bytes[i] != 0x1A.toByte()) return null
        if (bytes[i + 2] != 0.toByte() || bytes[i + 3] != 0.toByte() || bytes[i + 4] != 0.toByte()) return null
        if ((bytes[i + 5].toInt() and 0xFF) != sub) return null
        val slen = (bytes[i + 1].toInt() and 0xFF) - 6
        if (slen < prefix.length || i + 6 + slen > bytes.size) return null
        val s = String(bytes, i + 6, slen, Charsets.ISO_8859_1)
        return if (s.startsWith(prefix) && s.all { it.code in 0x20..0x7E }) TlvField(s, i + 6 + slen) else null
    }

    /**
     * Structural decoder for DJI's **CompositePack** media manifest (the record format the delete path
     * `0x00/0x28` already trusts). Every field is length-delimited, so it's read tag → length → value
     * — never grepped, and never validated against a filename pattern. That's the whole point: the
     * decoder doesn't know or care what a DJI filename looks like, so custom Folder/File name prefixes
     * (the camera's *Naming Management* feature — `_A01`, `_DOA5`, `_OP3`) and any future convention
     * decode identically to stock, because the path and name arrive as raw length-delimited strings.
     *
     * Anchor: the **media-path field** — the most self-identifying thing in a record:
     * ```
     *   1a [total:u8] 00 00 00 01  <ascii "DCIM/…", total-6 bytes>     media path (this record)
     *   1a [total:u8] 00 00 00 02  <ascii "MISC/…">                    thumbnail path
     *   0d [len:u8]                <ascii "<name>.<ext>">              filename — read only for its ext
     * ```
     * The delete **handle** (`u32-LE @ head`) and video **byte size** (`u32-LE @ head+38`) hang off a
     * marker `03 ff 19 06` at `head+8` present on **video records only**; it's read opportunistically
     * (photos → handle 0 / size 0). Reproduces all 45 Nano and 13 Xtra files, photos included, and the
     * Pocket 3 / OA5 / OA6 lists. Empty when no media-path field is present → [decodeManifest] scrapes.
     */
    private fun decodeComposite(bytes: ByteArray): List<CameraFile> {
        // Locate every media-path field (one per record). Read by length; the 6-byte `1a … 00 00 00 01`
        // signature plus the `DCIM/` prefix makes a false hit in binary essentially impossible.
        data class Media(val pos: Int, val end: Int, val path: String)
        val medias = ArrayList<Media>()
        var i = 0
        while (i < bytes.size) {
            val f = readPathField(bytes, i, sub = 1, prefix = "DCIM/")
            if (f != null) { medias.add(Media(i, f.end, f.value)); i = f.end } else i++
        }
        if (medias.isEmpty()) return emptyList()

        val byPath = LinkedHashMap<String, CameraFile>() // dedup by media path (the list can page-repeat)
        for (k in medias.indices) {
            val m = medias[k]
            val lo = if (k > 0) medias[k - 1].end else 0
            val hi = if (k + 1 < medias.size) medias[k + 1].pos else bytes.size
            byPath.putIfAbsent(m.path, resolveRecord(bytes, m.path, lo, hi))
        }
        return byPath.values.toList()
    }

    /**
     * Build one [CameraFile] from its media-path field. The base name is the last path component; the
     * thumbnail (`1a…02`) and the extension (`0d`) are found by walking the record window `[lo,hi)`
     * and matching *this* base — field order varies across the line (name before path on the Nano,
     * after it on the Xtra), so association is by trailing base, not position.
     */
    private fun resolveRecord(bytes: ByteArray, mediaDir: String, lo: Int, hi: Int): CameraFile {
        val base = mediaDir.substringAfterLast('/')      // e.g. DJI_20260721121344_0015_D_OP3

        // Thumbnail path: the 1a…02 field whose value ends with this base.
        var thumb: String? = null
        var t = lo
        while (t < hi) {
            val f = readPathField(bytes, t, sub = 2, prefix = "MISC/")
            if (f != null && f.value.endsWith(base)) { thumb = f.value; break }
            t++
        }

        // Extension: the 0d filename field `<base>.<ext>` — read by its length, matched by base (no
        // pattern). A camera that omits the extension here simply yields "" (a visible gap, not a 404).
        var ext = ""; var proxyExt: String? = null
        var n = lo
        while (n < hi - 2) {
            if (bytes[n] == 0x0D.toByte()) {
                val len = bytes[n + 1].toInt() and 0xFF
                if (len > base.length && n + 2 + len <= bytes.size) {
                    val v = String(bytes, n + 2, len, Charsets.ISO_8859_1)
                    if (v.length > base.length + 1 && v.startsWith(base) && v[base.length] == '.') {
                        val e = v.substring(base.length + 1).uppercase()
                        if (e in VIDEO_EXTS || e in setOf("JPG", "JPEG", "DNG", "HEIC")) ext = e
                        else if (e in setOf("LRF", "LRV", "XRF")) proxyExt = e
                    }
                }
            }
            n++
        }

        // Video handle/size hang off the marker `03 ff 19 06` (at head+8) within the record; photos
        // have none → handle 0 / size 0, matching the legacy decoder.
        var head = -1
        var m = lo
        while (m < hi - 4) {
            if (bytes[m] == 0x03.toByte() && bytes[m + 1] == 0xFF.toByte() &&
                bytes[m + 2] == 0x19.toByte() && bytes[m + 3] == 0x06.toByte()
            ) { head = m - 8; break }
            m++
        }
        val hasMarker = head >= 0
        val isVideo = ext in VIDEO_EXTS

        val path = if (ext.isNotEmpty()) "$mediaDir.$ext" else mediaDir
        val thumbPath = (thumb ?: mediaDir.replaceFirst("DCIM/", "MISC/THM/")) + ".scr"
        val handle = if (hasMarker) u32le(bytes, head) else 0L
        // Media byte size = u32-LE at head-4 (marker-12). Ground-truth-verified against the camera's
        // own SD card (85/85 Nano files, exact) and confirmed varying on the Action family. NB: the old
        // head+38 read was the *proxy* (`.LRF`) size — right-looking on the Nano, a constant elsewhere.
        val size = if (isVideo && hasMarker && head >= 4) u32le(bytes, head - 4) else 0L
        val fps = if (isVideo && hasMarker) fpsInRange(bytes, head, hi) else null
        // ⭐ starTag and the resolution index, both u8, ground-truth-verified against the SD card.
        // The star byte sits 9 bytes past the record's `[ff|fe] 19 06` marker (videos use `03 ff 19 06`,
        // photos a `fe 19 06` variant); the resolution index is at marker-1 (video header only).
        val starred = starFlag(bytes, lo, hi)
        val resolution = if (isVideo && hasMarker && head + 7 < bytes.size)
            resolutionForIndex(bytes[head + 7].toInt() and 0xFF) else null
        return CameraFile(
            path = path, thumbPath = thumbPath, storage = 0,
            resLabel = fps?.let { "${it}fps" }, proxyPath = proxyExt?.let { "$mediaDir.$it" },
            handle = handle, sizeBytes = size, starred = starred, resolution = resolution,
        )
    }

    /**
     * The manifest resolution byte (`marker-1`) is a **camera-specific index**, not the SDK's
     * `VideoResolution` enum (whose codes don't match), so the map is built empirically from clips
     * cross-referenced against the SD card. Unknown → null → the app falls back to the MP4 `moov`.
     */
    /** ⭐ favourite flag: the byte 9 past the record's `[ff|fe] 19 06` marker is 1 when starred, 0
     *  otherwise. Unified across videos (`03 ff 19 06`) and photos (`fe 19 06`). */
    private fun starFlag(bytes: ByteArray, lo: Int, hi: Int): Boolean {
        var q = lo
        while (q < hi - 9) {
            if ((bytes[q] == 0xFF.toByte() || bytes[q] == 0xFE.toByte()) &&
                bytes[q + 1] == 0x19.toByte() && bytes[q + 2] == 0x06.toByte()
            ) return (bytes[q + 9].toInt() and 0xFF) == 1
            q++
        }
        return false
    }

    private fun resolutionForIndex(code: Int): String? = when (code) {
        16 -> "3840x2160"  // 4K 16:9
        45 -> "2688x1512"  // 2.7K 16:9
        95 -> "2688x2016"  // 2.7K 4:3
        103 -> "3840x2880" // 4K 4:3
        else -> null
    }

    /**
     * Dump the reassembled manifest as hex when a model's layout doesn't decode, so a single test run
     * gives us the actual bytes to reverse. Emitted in fixed-width rows with the byte offset, the way
     * we hand-decoded the Nano/Xtra records — enough to see the count header and where the filename /
     * extension / handle fields sit. Contents are paths and filenames only; the passphrase and GPS
     * never travel on this datalink, so this is safe for the shared "Save logs" file.
     */
    private fun dumpManifest(bytes: ByteArray) {
        log("datalink: --- manifest hex (${bytes.size}B), report this to crack the layout ---")
        var off = 0
        while (off < bytes.size) {
            val end = minOf(off + 32, bytes.size)
            val hex = StringBuilder(); val asc = StringBuilder()
            for (i in off until end) {
                val b = bytes[i].toInt() and 0xFF
                hex.append("%02x".format(b))
                if (i - off == 15) hex.append(' ')
                asc.append(if (b in 0x20..0x7E) b.toChar() else '.')
            }
            log("  %04x  %-65s %s".format(off, hex.toString(), asc.toString()))
            off = end
        }
        log("datalink: --- end manifest hex ---")
    }

    /** Fallback scrape: whole-blob regex, joining fields by filename base. No per-record structure or
     *  count check — used when [decodeManifest] can't validate the layout. Osmo Nano uses
     *  DCIM/DJI_001/DJI_…; 360 & Action 5 use CAM_. */
    private fun parseFlat(bytes: ByteArray): List<CameraFile> {
        val text = String(bytes, Charsets.ISO_8859_1)
        val pathRe = Regex("""DCIM/(?:DJI|CAM)_\d{3}/(?:DJI|CAM)_\d{14}_\d{4}_D""")
        val nameRe = Regex("""(?:DJI|CAM)_\d{14}_\d{4}_D\.[A-Za-z0-9]{2,4}""")
        val bestExt = HashMap<String, String>()
        val proxyByBase = HashMap<String, String>() // base -> LRF/LRV proxy extension, if listed
        for (m in nameRe.findAll(text)) {
            val base = m.value.substringBeforeLast('.')
            val ext = m.value.substringAfterLast('.').uppercase()
            val cur = bestExt[base]
            if (cur == null || (ext in primaryExts && cur !in primaryExts)) bestExt[base] = ext
            if (ext in proxyExts) proxyByBase[base] = ext
        }
        val thumbRe = Regex("""MISC/THM/(?:DJI|CAM)_\d{3}/(?:DJI|CAM)_\d{14}_\d{4}_D(?:\.\w{2,4})?""")
        val thumbByBase = HashMap<String, String>()
        for (m in thumbRe.findAll(text)) {
            val v = m.value
            thumbByBase[v.substringAfterLast('/').substringBeforeLast('.')] =
                if (v.contains('.')) v else "$v.scr"
        }
        log("datalink: media exts=${bestExt.values.toSortedSet()} proxies=${proxyByBase.values.toSortedSet()}")
        return pathRe.findAll(text).map { it.value }.toSortedSet().map { p ->
            val base = p.substringAfterLast('/')
            val ext = bestExt[base]
            val mediaPath = ext?.let { "$p.$it" } ?: p
            val thumb = thumbByBase[base] ?: (p.replaceFirst("DCIM/", "MISC/THM/") + ".scr")
            val fps = if (ext in VIDEO_EXTS) fpsFor(bytes, "$base.$ext") else null
            val proxy = proxyByBase[base]?.let { "$p.$it" }
            val namePos = ext?.let { indexOf(bytes, "$base.$it".toByteArray(Charsets.ISO_8859_1)) } ?: -1
            val handle = if (namePos >= 0) handleForName(bytes, namePos) else 0L
            val sizeBytes = if (namePos >= 0) sizeForName(bytes, namePos, ext in VIDEO_EXTS) else 0L
            CameraFile(path = mediaPath, thumbPath = thumb, storage = 0,
                resLabel = fps?.let { "${it}fps" }, proxyPath = proxy, handle = handle, sizeBytes = sizeBytes)
        }
    }

    /**
     * The record encodes fps as a rational num/den (den ∈ {1000,1001}) shortly before the filename
     * field — 25000/1000 = 25, 30000/1001 = 29.97. That's the only reliable per-file metadata here:
     * pixel dimensions aren't stored, and the enum block can't separate 4K from 2.7K (both same
     * enums), so resolution is read from the MP4 moov instead.
     */
    private fun fpsFor(bytes: ByteArray, fileName: String): Int? {
        val idx = indexOf(bytes, fileName.toByteArray(Charsets.ISO_8859_1))
        if (idx < 0) return null
        return fpsInRange(bytes, idx - 220, idx)
    }

    /** Scan [start,end) for the fps rational (num/den, den ∈ {1000,1001}); takes the last match, which
     *  is the one nearest the filename — i.e. this record's own enum block. See [fpsFor] for the format. */
    private fun fpsInRange(bytes: ByteArray, start: Int, end: Int): Int? {
        var fps: Int? = null
        var i = maxOf(0, start)
        val stop = minOf(end, bytes.size) - 8
        while (i <= stop) {
            val den = u32le(bytes, i + 4)
            if (den == 1000L || den == 1001L) {
                val num = u32le(bytes, i)
                if (num in 20_000L..250_000L) fps = Math.round(num.toDouble() / den).toInt()
            }
            i++
        }
        return fps
    }

    private fun u32le(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    /**
     * Position of a manifest record's **head** for the file whose name starts at [namePos], or -1.
     * Every record carries a constant marker `03 ff 19 06` at `head + 8`; the head holds the delete
     * **handle** (u32-LE at +0) and, for videos, the byte **size** (+38). We anchor on the marker
     * nearest *before* the filename — robust across the **Nano** (`DJI_`, 361-byte records, handles at
     * `0x40104000`+ stepping `0x40`) and the **Xtra / Action family** (`CAM_`, 272-byte records,
     * handles at `0x40040000`+ stepping `0x10`); the earlier `0x40`-aligned scan mis-read the Xtra
     * (whose handles aren't `0x40`-aligned) and grabbed a stray dword → the camera rejected it (0xd6).
     * Verified against a pcap: the three handles Mimo sent in a delete mapped to the three files that
     * then vanished, and on the Xtra the marker-derived handle deletes where the aligned one did not.
     */
    private fun recordStart(bytes: ByteArray, namePos: Int): Int {
        var i = minOf(namePos, bytes.size) - 4
        val lo = maxOf(0, namePos - 400)
        while (i >= lo) {
            if (bytes[i] == 0x03.toByte() && bytes[i + 1] == 0xFF.toByte() &&
                bytes[i + 2] == 0x19.toByte() && bytes[i + 3] == 0x06.toByte()) {
                return if (i - 8 >= 0) i - 8 else -1
            }
            i--
        }
        return -1
    }

    /** The delete handle (record head) for the file at [namePos], or 0 → non-deletable. */
    private fun handleForName(bytes: ByteArray, namePos: Int): Long {
        val p = recordStart(bytes, namePos)
        return if (p >= 0) u32le(bytes, p) else 0L
    }

    /** Full media byte size from the record (+38, u32-LE) — **video records only** (photos lay out
     *  differently); 0 when unavailable. Replaces an HTTP HEAD for the common case. */
    private fun sizeForName(bytes: ByteArray, namePos: Int, isVideo: Boolean): Long {
        if (!isVideo) return 0L
        val p = recordStart(bytes, namePos)
        return if (p >= 0 && p + 42 <= bytes.size) u32le(bytes, p + 38) else 0L
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // ---- packet builders (mirror file_list.py) ------------------------------

    private fun udpHeader(pktType: Int, payloadLen: Int): ByteArray {
        val total = 8 + payloadLen
        val w0 = (1 shl 15) or (total and 0x3FFF)
        val b = byteArrayOf(
            (w0 and 0xFF).toByte(), ((w0 shr 8) and 0xFF).toByte(),
            (sessionId and 0xFF).toByte(), ((sessionId shr 8) and 0xFF).toByte(),
            (udpSeq and 0xFF).toByte(), ((udpSeq shr 8) and 0xFF).toByte(),
            pktType.toByte(),
        )
        var xor = 0
        for (x in b) xor = xor xor (x.toInt() and 0xFF)
        return b + xor.toByte()
    }

    private fun routingHeader(): ByteArray = byteArrayOf(
        (lastCamSeq and 0xFF).toByte(), ((lastCamSeq shr 8) and 0xFF).toByte(),
        (udpSeq and 0xFF).toByte(), ((udpSeq shr 8) and 0xFF).toByte(),
        0, 0, 0, 0, (cmdCounter and 0xFF).toByte(), 0x01, 0x00, 0x00,
    )

    private fun advance() { udpSeq = (udpSeq + 8) and 0xFFFF }

    private fun sendRaw(pktType: Int, payload: ByteArray) {
        val pkt = udpHeader(pktType, payload.size) + payload
        sock.send(DatagramPacket(pkt, pkt.size, cam, port))
        advance()
    }

    private fun sendAck() {
        val grp = byteArrayOf(
            (lastCamSeq and 0xFF).toByte(), ((lastCamSeq shr 8) and 0xFF).toByte(),
            (lastCamSeq and 0xFF).toByte(), ((lastCamSeq shr 8) and 0xFF).toByte(),
            0, 0, 0, 0,
        )
        val payload = grp + grp + grp + byteArrayOf(0, 0)
        val old = udpSeq; udpSeq = 0
        val hdr = udpHeader(0x04, payload.size)
        udpSeq = old
        val pkt = hdr + payload
        sock.send(DatagramPacket(pkt, pkt.size, cam, port))
    }

    private fun sendDuml(
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
        sock.send(DatagramPacket(pkt, pkt.size, cam, port))
        advance()
    }

    private fun recvAll(durationMs: Long): List<ByteArray> {
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
                    val camCh = (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8)
                    if (camCh != 0) lastCamSeq = camCh
                }
            } catch (_: java.net.SocketTimeoutException) {
                // keep polling until the deadline
            } catch (_: Exception) {
                break
            }
        }
        return out
    }

    private fun appDeviceInfo(): ByteArray {
        // "\x00APP" + 37*00 + 02 + 8*00 + 02 08 + 10*00  (62 bytes) — mirrors file_list.py.
        val b = ByteArray(62)
        b[1] = 'A'.code.toByte(); b[2] = 'P'.code.toByte(); b[3] = 'P'.code.toByte()
        b[41] = 0x02; b[50] = 0x02; b[51] = 0x08
        return b
    }

    private fun subscription(name: String, subId: Int): ByteArray {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val padded = nameBytes + ByteArray(maxOf(0, 20 - nameBytes.size))
        val innerLen = padded.size + 6
        return byteArrayOf(0x02, 0x02, 0x00, 0x00) +
            le32(subId) + byteArrayOf(0, 0, 0, 0) +
            byteArrayOf((innerLen and 0xFF).toByte(), ((innerLen shr 8) and 0xFF).toByte()) +
            byteArrayOf(0x00) + byteArrayOf(nameBytes.size.toByte()) + byteArrayOf(0x00) +
            padded + byteArrayOf(0, 0, 0, 0)
    }

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    private fun hex(s: String): ByteArray {
        val clean = s.filter { !it.isWhitespace() }
        return ByteArray(clean.length / 2) {
            ((clean[it * 2].digitToInt(16) shl 4) or clean[it * 2 + 1].digitToInt(16)).toByte()
        }
    }
}
