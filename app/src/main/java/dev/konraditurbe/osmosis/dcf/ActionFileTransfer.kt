package dev.konraditurbe.osmosis.dcf

import dev.konraditurbe.osmosis.core.AlbumJpeg
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.matchesOriginalPrefix
import dev.konraditurbe.osmosis.duml.DjiCrc
import dev.konraditurbe.osmosis.net.DumlTransport
import dev.konraditurbe.osmosis.net.DumlTransport.Companion.u16
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap

/** One file or JPEG rendition on the already paired AC101 album session. All socket access stays on its owner thread. */
internal class ActionFileTransfer(
    private val file: CameraFile,
    private val offset: Long,
    private val id: Int,
    private val output: OutputStream,
    private val jpeg: AlbumJpeg? = null,
    private val progress: (Long) -> Unit,
) {
    // The camera adds its 13-byte first-fragment header to the requested byte count in a u32.
    // A FAT file within 13 bytes of 4 GiB needs a second request for its tail.
    private val rangeLength = minOf(file.sizeBytes - offset, 0xffffffffL - 13)
    var endOffset = offset + rangeLength
        private set
    private val label = jpeg?.name?.lowercase() ?: "original"
    private val pending = TreeMap<Long, ByteArray>()
    private var next = 0L
    private var highest = -1L
    private var written = offset
    private var finished = false
    private var sawState = false
    private var stateCount = 0

    /** How long the camera gets to send the first chunk of this transfer. */
    internal var startTimeoutNs = 8_000_000_000L

    /**
     * Once chunks are flowing, how long a missing one may hold up the file. AC101 never resent a lost
     * chunk on hardware (issue #32: 30–57 `4a 02` requests per stall, none honoured), so waiting the
     * full start timeout only delayed the restart that [fetchOriginal] makes from [contiguousEnd].
     */
    internal var stallTimeoutNs = 2_000_000_000L

    /** True when [run] gave up because no new in-order chunk arrived, not because of a bad frame or a disconnect. */
    var stalled = false
        private set

    /** Absolute end of the bytes written in order so far. A new request from here appends cleanly. */
    val contiguousEnd: Long get() = written

    /** Mimo 2.10.9 DataTransferRequest::CreateStartRequestPack, 32-bit file offsets on AC101. */
    internal fun request(): ByteArray = envelope(0x20, 48).apply {
        putInt(file.fileIndex.toInt())
        putShort(1)
        put((jpeg?.subtype ?: 0).toByte())
        put(0) // sub-index (the drive is already encoded in fileIndex)
        putInt(offset.toInt())
        putInt(if (jpeg != null || endOffset == file.sizeBytes) -1 else rangeLength.toInt())
        putInt(0) // extended sub-index
        putShort(file.segmentIndex.toShort())
    }.array()

    private fun envelope(kind: Int, size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x4a)
            put(kind.toByte())
            putShort((0x1000 or size).toShort())
            putShort(id.toShort())
            putInt(0)
        }

    private fun acknowledge(): ByteArray {
        val gaps = mutableListOf<Pair<Long, Long>>()
        var expected = next
        for (received in pending.keys) {
            if (received > expected) {
                gaps += expected to received - expected
                if (gaps.size == 3) break
            }
            expected = received + 1
        }
        if (gaps.size < 3 && expected <= highest) gaps += expected to highest - expected + 1
        return envelope(0x02, 15 + 8 * gaps.size).apply {
            putInt(next.toInt())
            put(gaps.size.toByte())
            for ((start, count) in gaps) {
                putInt(start.toInt())
                putInt(count.toInt())
            }
        }.array()
    }

    fun run(session: Int, send: (ByteArray) -> Unit, receive: () -> List<ByteArray>, ackWindow: () -> Unit,
            connected: () -> Boolean, log: (String) -> Unit): Boolean {
        if (!file.isActionDcf || file.segmentIndex !in 0..65535 || !connected()) return false
        if (jpeg == null && (file.sizeBytes !in 1..0xffffffffL || offset !in 0 until file.sizeBytes)) return false
        if (jpeg != null && offset != 0L) return false
        val frames = AlbumFrames(session)
        var lastProgress = System.nanoTime()
        var lastAck = lastProgress
        var acks = 0
        log("datalink: $label ${file.name} index=${file.fileIndex} segment=${file.segmentIndex} offset=$offset transfer=$id")
        try {
            send(request())
            while (connected() && !finished) {
                val before = next
                for (packet in receive()) {
                    frames.accept(packet) { p -> if (!finished) accept(p) }
                }
                ackWindow()
                val now = System.nanoTime()
                if (next != before) lastProgress = now
                if (now - lastProgress >= if (highest < 0) startTimeoutNs else stallTimeoutNs) {
                    stalled = true
                    throw IOException("waiting for chunk $next (highest=$highest, state frames=$stateCount, transfer ACKs=$acks)")
                }
                if (!finished && (sawState || (highest >= 0 && now - lastProgress > 400_000_000L)) &&
                    now - lastAck >= 250_000_000L) {
                    send(acknowledge())
                    acks++
                    lastAck = now
                    sawState = false
                }
            }
        } catch (e: IOException) {
            log("datalink: $label ${file.name} stopped at $written/$endOffset: ${e.message}")
            return false
        } finally {
            send(DcfTransferProbe.release(0x20, id))
        }
        if (finished) log("datalink: received ${file.name} through byte $written/$endOffset")
        return finished
    }

    private fun accept(p: ByteArray) {
        if (p.size < 10 || p[0] != 0x4a.toByte() || u16(p, 4) != id || u16(p, 2) and 0xfff != p.size) return
        when (p[1].toInt()) {
            0x23, 0x03 -> {
                sawState = true
                stateCount++
            }
            0x24 -> throw IOException("camera released transfer (${p.drop(10).take(4)})")
            0x21 -> {
                val seq = u32(p, 6)
                if (seq < next || seq > (jpeg?.maxBytes ?: rangeLength)) return
                highest = maxOf(highest, seq)
                if (seq - next >= 512) return // bound memory while requesting earlier missing chunks
                pending.putIfAbsent(seq, p)
                while (!finished) {
                    val chunk = pending.remove(next) ?: break
                    writeChunk(chunk)
                    next++
                }
            }
        }
    }

    private fun writeChunk(p: ByteArray) {
        var start = 10
        if (next == 0L) {
            if (p.size < 23 || u32(p, 18) != file.fileIndex || p[22] != 0.toByte())
                throw IOException("file header/index mismatch")
            val length = u32(p, 14) - 13
            start += 13
            if (jpeg != null) {
                if (length !in 1..jpeg.maxBytes) throw IOException("invalid JPEG length $length")
                if (p.size < start + 3 || p[start] != 0xff.toByte() || p[start + 1] != 0xd8.toByte() ||
                    p[start + 2] != 0xff.toByte()) throw IOException("unexpected JPEG signature")
                endOffset = length
            } else {
                if (length != rangeLength) throw IOException("original header/length mismatch")
                if (offset == 0L && !file.matchesOriginalPrefix(p.copyOfRange(start, minOf(start + 64, p.size))))
                    throw IOException("unexpected original media signature")
            }
        }
        val count = p.size - start
        val last = u16(p, 2) and 0x1000 != 0
        if (count <= 0 || written + count > endOffset || last != (written + count == endOffset))
            throw IOException("final chunk/manifest length mismatch")
        output.write(p, start, count)
        written += count
        progress(written)
        finished = last
    }

    companion object {
        /** Restarts in a row that may bring no new bytes before the original is given up on. */
        internal const val MAX_IDLE_RESTARTS = 1

        /**
         * Fetch an original from [offset] to its end, one transfer per range, restarting from the
         * contiguous end whenever a transfer stalls.
         *
         * On hardware a stalled transfer never recovered by itself, while a fresh request from the
         * last in-order byte started streaming every time it was tried by hand (6 of 6 in issue #32).
         * This does that restart automatically. A restart that brings no new bytes counts against
         * [MAX_IDLE_RESTARTS], so a camera that has stopped answering still ends the download. Bad
         * frames, a released transfer and a disconnect are not stalls and end it at once, leaving the
         * contiguous prefix for the next Download.
         */
        internal fun fetchOriginal(file: CameraFile, offset: Long, output: OutputStream, progress: (Long) -> Unit,
                                   nextId: () -> Int, connected: () -> Boolean, log: (String) -> Unit,
                                   run: (ActionFileTransfer) -> Boolean): Boolean {
            var from = offset
            var idle = 0
            var resumes = 0
            do {
                val transfer = ActionFileTransfer(file, from, nextId(), output, null, progress)
                if (run(transfer)) {
                    from = transfer.endOffset
                    idle = 0
                    continue
                }
                if (!transfer.stalled || !connected()) return false
                val reached = transfer.contiguousEnd
                idle = if (reached > from) 0 else idle + 1
                if (idle > MAX_IDLE_RESTARTS) return false
                resumes++
                log("datalink: original ${file.name} stalled at $reached/${file.sizeBytes} — " +
                    "re-requesting from there (auto-resume $resumes)")
                from = reached
            } while (from < file.sizeBytes)
            return true
        }
    }
}

private fun u32(b: ByteArray, at: Int) = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)

/** SW UDP can coalesce envelopes, and a DUML frame can straddle two download-channel envelopes. */
internal class AlbumFrames(private val session: Int) {
    private class Channel {
        var tail = ByteArray(0)
        var seq = -1
    }
    private val download = Channel()
    private val control = Channel()

    fun accept(packet: ByteArray, consume: (ByteArray) -> Unit) {
        DumlTransport.forEachEnvelope(packet) { at, length ->
            val kind = packet[at + 6].toInt()
            if ((kind == 3 || kind == 5) && length > 20 && u16(packet, at + 2) == session) {
                val channel = if (kind == 3) download else control
                val seq = u16(packet, at + 4)
                val carry = if (seq == ((channel.seq + 8) and 0xffff)) channel.tail else ByteArray(0)
                val bytes = carry + packet.copyOfRange(at + 20, at + length)
                channel.seq = seq
                channel.tail = decode(bytes, consume)
            }
        }
    }

    private fun decode(bytes: ByteArray, consume: (ByteArray) -> Unit): ByteArray {
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] != 0x55.toByte()) { i++; continue }
            if (i + 4 > bytes.size) break
            val size = u16(bytes, i + 1) and 0x3ff
            if (bytes[i + 2].toInt() and 0xfc != 4 || size < 13 ||
                DjiCrc.computeCrc8(bytes.copyOfRange(i, i + 3)) != (bytes[i + 3].toInt() and 255)) { i++; continue }
            if (i + size > bytes.size) break
            if (DjiCrc.computeCrc16(bytes, offset = i, length = size - 2) != u16(bytes, i + size - 2)) { i++; continue }
            if (bytes[i + 9] == 0.toByte() && bytes[i + 10] == 0x27.toByte())
                consume(bytes.copyOfRange(i + 11, i + size - 2))
            i += size
        }
        return bytes.copyOfRange(i, bytes.size)
    }
}
