package com.chernowii.osmosis.net

import com.chernowii.osmosis.core.CameraFile
import com.chernowii.osmosis.duml.DjiMessage
import com.chernowii.osmosis.duml.OsmoCommands
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

    /** Handshake + register + list. Socket stays open on success. Empty list on failure. */
    fun fetchFileList(ip: String): List<CameraFile> {
        cam = InetAddress.getByName(ip)
        sock = DatagramSocket().apply { soTimeout = 200 }
        sessionId = Random.nextInt(0x1000, 0xFFFE)

        if (tcpPoke) {
            runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(cam, 7001), 1200)
                    s.getOutputStream().write(OsmoCommands.setPairingPin("mbln"))
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
        if (!ok) { log("datalink: handshake FAILED on udp/$port"); sock.close(); return emptyList() }
        log("datalink: handshake OK on udp/$port")

        // Drain heartbeats, learn camera channel, set our seq start.
        repeat(5) { recvAll(400); sendAck() }
        udpSeq = (lastCamSeq + 8) and 0xFFFF

        // Registration.
        sendDuml(0x00, 0x81, appDeviceInfo(), receiverType = 0x08, receiverId = 2, cmdType = 4)
        recvAll(400); sendAck()
        sendDuml(0x00, 0x88, hex("170008237b41505000000000000002"), receiverType = 0x08, receiverId = 1)
        recvAll(400); sendAck()
        sendDuml(0x03, 0xDA, hex("05ffffffff"), receiverType = 0x03, receiverId = 0)
        recvAll(400); sendAck()
        var subId = 0x69DF
        for (p in paramSubs) {
            sendDuml(0x00, 0x99, subscription(p, subId), receiverType = 0x08, receiverId = 1)
            subId++; recvAll(300); sendAck()
        }
        repeat(4) { recvAll(400); sendAck() }

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
            if (batch >= 4 && count > 0 && count == lastCount) { if (++stable >= 2) break } else stable = 0
            lastCount = count
        }

        val bytes = blob.toByteArray()
        val files = parse(bytes)
        log("datalink: parsed ${files.size} media files (${bytes.size}B)")
        return files
    }

    /** Keep the datalink session active (ACK the camera ~2×/s) so the AP doesn't sleep. */
    fun startKeepAlive() {
        if (keepAliveOn) return
        keepAliveOn = true
        Thread {
            while (keepAliveOn) {
                runCatching { recvAll(200); sendAck() }
                runCatching { Thread.sleep(300) }
            }
        }.apply { isDaemon = true; name = "datalink-keepalive" }.start()
    }

    fun close() {
        keepAliveOn = false
        runCatching { sock.close() }
    }

    private val pathCountRe = Regex("""DCIM/(?:DJI|CAM)_\d{3}/(?:DJI|CAM)_\d{14}_\d{4}_D""")

    /** Distinct media paths seen so far — lets the collect loop stop once the list stops growing. */
    private fun distinctPaths(blob: java.io.ByteArrayOutputStream): Int =
        pathCountRe.findAll(String(blob.toByteArray(), Charsets.ISO_8859_1)).map { it.value }.toHashSet().size

    /** Media naming: Osmo Nano uses DCIM/DJI_001/DJI_…; 360 & Action 5 use CAM_. Match either. */
    private fun parse(bytes: ByteArray): List<CameraFile> {
        val text = String(bytes, Charsets.ISO_8859_1)
        val pathRe = Regex("""DCIM/(?:DJI|CAM)_\d{3}/(?:DJI|CAM)_\d{14}_\d{4}_D""")
        val nameRe = Regex("""(?:DJI|CAM)_\d{14}_\d{4}_D\.[A-Za-z0-9]{2,4}""")
        val bestExt = HashMap<String, String>()
        val proxyByBase = HashMap<String, String>() // base -> LRF/LRV proxy extension, if listed
        val primary = setOf("MP4", "MOV", "JPG", "JPEG", "DNG", "OSV", "INSV", "HEIC")
        val proxyExts = setOf("LRF", "LRV")
        for (m in nameRe.findAll(text)) {
            val base = m.value.substringBeforeLast('.')
            val ext = m.value.substringAfterLast('.').uppercase()
            val cur = bestExt[base]
            if (cur == null || (ext in primary && cur !in primary)) bestExt[base] = ext
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
            CameraFile(path = mediaPath, thumbPath = thumb, storage = 0,
                resLabel = fps?.let { "${it}fps" }, proxyPath = proxy)
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
        var fps: Int? = null
        var i = maxOf(0, idx - 220)
        while (i <= idx - 8) {
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
