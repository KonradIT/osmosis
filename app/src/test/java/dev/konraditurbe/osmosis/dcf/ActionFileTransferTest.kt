package dev.konraditurbe.osmosis.dcf

import dev.konraditurbe.osmosis.core.AlbumJpeg
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.duml.DjiCrc
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ActionFileTransferTest {
    private val session = 0x9dea
    private val transferId = 0x12
    private val original = ByteArray(1200) { it.toByte() }.also { "ftyp".toByteArray().copyInto(it, 4) }
    private val file = CameraFile("DCIM/100MEDIA/DJI_0540.MP4", "", fileIndex = 0x64021c,
        sizeBytes = original.size.toLong(), mediaType = 3)
    private val out = ByteArrayOutputStream()
    private val sent = mutableListOf<ByteArray>()

    private fun little(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun frame(kind: Int, seq: Int, body: ByteArray, last: Boolean = false,
                      id: Int = transferId): ByteArray {
        val length = body.size + 23
        val b = little(length)
        b.put(0x55).putShort((0x400 or length).toShort())
        b.put(DjiCrc.computeCrc8(b.array().copyOf(3)).toByte()).putShort(0x0201).putShort(1)
        b.put(0).put(0).put(0x27).put(0x4a).put(kind.toByte())
        b.putShort((body.size + 10 or if (last) 0x1000 else 0).toShort())
        b.putShort(id.toShort()).putInt(seq).put(body)
        b.putShort(DjiCrc.computeCrc16(b.array().copyOf(length - 2)).toShort())
        return b.array()
    }

    private fun packet(seq: Int, body: ByteArray, sid: Int = session): ByteArray {
        val b = little(20 + body.size)
        b.putShort((0x8000 or b.capacity()).toShort()).putShort(sid.toShort()).putShort(seq.toShort()).put(3)
        b.put(b.array().take(7).fold(0) { a, v -> a xor v.toInt() }.toByte())
        b.position(20)
        b.put(body)
        return b.array()
    }

    private fun first(data: ByteArray, index: Long = file.fileIndex, size: Int = original.size) =
        little(13 + data.size).putInt(0).putInt(size + 13).putInt(index.toInt()).put(0).put(data).array()

    private fun run(vararg packets: ByteArray, offset: Long = 0, jpeg: AlbumJpeg? = null): Boolean {
        var received = false
        return ActionFileTransfer(file, offset, transferId, out, jpeg) {}.run(session, { sent.add(it) },
            receive = { received = true; packets.toList() }, ackWindow = {}, connected = { !received }, log = {})
    }

    @Test fun `original query matches the captured shape and addresses the listed segment and resume offset`() {
        val request = ActionFileTransfer(file.copy(segmentIndex = 3, sizeBytes = 0xf0001111L), 0x81234567L, transferId, out) {}.request()
        assertArrayEquals(bytes("4a2030101200000000001c0264000100000067452381ffffffff000000000300" +
            "00000000000000000000000000000000"), request)
    }

    @Test fun `JPEG requests keep the segment and use the returned rendition length for a video`() {
        val jpeg = bytes("ffd8ffe000104a4649460001ffd9")
        for (kind in AlbumJpeg.values()) {
            out.reset()
            val request = ActionFileTransfer(file.copy(segmentIndex = 3), 0, transferId, out, kind) {}.request()
            assertEquals(kind.subtype, request[16].toInt())
            assertEquals(3, request[30].toInt())
            assertEquals(-1, ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN).getInt(22))
            assertTrue(run(packet(8, frame(0x21, 0, first(jpeg.copyOf(6), size = jpeg.size))),
                packet(16, frame(0x21, 1, jpeg.copyOfRange(6, jpeg.size), last = true)), jpeg = kind))
            assertArrayEquals(jpeg, out.toByteArray())
        }
    }

    @Test fun `a JPEG request rejects a returned MP4 or excessive or incomplete JPEG`() {
        val kind = AlbumJpeg.SCREEN
        val jpeg = bytes("ffd8ffe0ffd9")
        assertFalse(run(packet(8, frame(0x21, 0, first(original.take(64).toByteArray()))), jpeg = kind))
        assertFalse(run(packet(8, frame(0x21, 0, first(jpeg, size = kind.maxBytes.toInt() + 1))), jpeg = kind))
        assertFalse(run(packet(8, frame(0x21, 0, first(jpeg, size = 7), last = true)), jpeg = kind))
        assertEquals(0, out.size())
    }

    @Test fun `the thirteen-byte reply header cannot overflow the camera's range length`() {
        val huge = file.copy(sizeBytes = 0xffffffffL)
        val first = ActionFileTransfer(huge, 0, transferId, out) {}
        assertEquals(0xfffffff2L, first.endOffset)
        assertEquals(-14, ByteBuffer.wrap(first.request()).order(ByteOrder.LITTLE_ENDIAN).getInt(22))
        val tail = ActionFileTransfer(huge, first.endOffset, transferId + 1, out) {}
        assertEquals(huge.sizeBytes, tail.endOffset)
        assertEquals(-1, ByteBuffer.wrap(tail.request()).order(ByteOrder.LITTLE_ENDIAN).getInt(22))
    }

    @Test fun `a state frame requests only the missing chunk before releasing the transfer`() {
        val opening = frame(0x21, 0, first(original.copyOf(600)))
        val middle = frame(0x21, 1, original.copyOfRange(600, 900))
        val end = frame(0x21, 2, original.copyOfRange(900, 1200), last = true)
        var receives = 0
        val done = ActionFileTransfer(file, 0, transferId, out) {}.run(session, { sent.add(it) },
            receive = {
                if (receives++ == 0) {
                    Thread.sleep(275) // the camera's state arrives after the ACK rate limit
                    listOf(packet(8, opening + end + frame(0x23, 0, byteArrayOf())))
                } else listOf(packet(16, middle))
            }, ackWindow = {}, connected = { receives < 2 }, log = {})
        assertTrue(done)
        assertArrayEquals(bytes("4a02171012000000000001000000010100000001000000"), sent[1])
        assertArrayEquals(original, out.toByteArray())
    }

    @Test fun `split and reordered frames are written exactly once and released`() {
        val opening = frame(0x21, 0, first(original.copyOf(600)))
        val middle = frame(0x21, 1, original.copyOfRange(600, 900))
        val end = frame(0x21, 2, original.copyOfRange(900, 1200), last = true)
        val corrupt = middle.copyOf().apply { this[40] = 99 }
        // A frame crosses an envelope boundary at the sequence-number wrap. Two envelopes share UDP.
        assertTrue(run(packet(0xfff8, opening.copyOf(56)) + packet(0, opening.copyOfRange(56, opening.size)),
            packet(8, end + corrupt), packet(16, end), packet(24, middle)))
        assertArrayEquals(original, out.toByteArray())
        assertArrayEquals(bytes("4a240e1012000000000001000000"), sent.last())
    }

    @Test fun `a resumed response must describe the remaining range`() {
        val tail = original.copyOfRange(900, 1200)
        assertFalse(run(packet(8, frame(0x21, 0, first(tail), last = true)), offset = 900))
        assertEquals(0, out.size())
        assertTrue(run(packet(8, frame(0x21, 0, first(tail, size = 300), last = true)), offset = 900))
        assertArrayEquals(tail, out.toByteArray())
    }

    @Test fun `wrong session transfer id index or signature cannot publish bytes`() {
        val data = original.copyOf(100)
        val valid = frame(0x21, 0, first(data))
        assertFalse(run(packet(8, valid, sid = session - 1), packet(16, frame(0x21, 0, first(data), id = 9)),
            packet(24, frame(0x21, 0, first(data, index = file.fileIndex + 1)))))
        assertFalse(run(packet(8, frame(0x21, 0, first(ByteArray(100))))))
        assertEquals(0, out.size())
        assertEquals(2, sent.count { it[1] == 0x24.toByte() })
    }

    @Test fun `a premature final fragment leaves only the validated contiguous prefix for resume`() {
        assertFalse(run(packet(8, frame(0x21, 0, first(original.copyOf(100)))),
            packet(16, frame(0x21, 1, original.copyOfRange(100, 150), last = true))))
        assertArrayEquals(original.copyOf(100), out.toByteArray())
    }
}
