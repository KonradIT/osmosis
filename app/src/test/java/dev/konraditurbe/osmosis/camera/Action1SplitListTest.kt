package dev.konraditurbe.osmosis.camera

import dev.konraditurbe.osmosis.ble.CameraModel.LegacyActionAlbum
import dev.konraditurbe.osmosis.duml.DjiCrc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 16-file Action 1 list, issue #32. The camera declared `[16][1048]`, the first chunk carried 918
 * bytes (14 records) and the second, 130 bytes, never decoded, so the grid came up empty.
 *
 * Retail AC101 firmware sends both chunks back to back without waiting for an ACK. These tests pin the
 * three ways back from a lost chunk: rebuilding a frame split across two SW UDP envelopes, completing
 * the list with a resent chunk however it arrives, and, failing both, showing the records that came.
 */
class Action1SplitListTest {

    private val session = 0xf7ed

    /** The tester's first chunk: 14 whole records under a header that declares 16 and 1048 bytes. */
    private val firstChunk = javaClass.classLoader!!.getResourceAsStream("manifests/action1_16_first_chunk.hex")!!
        .readBytes().toString(Charsets.US_ASCII).trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** The two oldest records, made from two of the listed photos with the indices that come next. */
    private val secondChunk = listOf(0x6401beL, 0x6401bdL).mapIndexed { n, index ->
        firstChunk.copyOfRange(8 + 65 * (n + 1), 8 + 65 * (n + 2)).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(8, index.toInt())
        }
    }.reduce(ByteArray::plus)

    private fun session(legacy: LegacyActionAlbum? = LegacyActionAlbum.ACTION_1) =
        CameraSession(log = {}, legacyAlbum = legacy)

    /**
     * One SD (`ctr` 1) list data frame: Wi-Fi DUML header with its 10-bit length (the BLE
     * [dev.konraditurbe.osmosis.duml.DjiMessage] has an 8-bit one and cannot carry a 941-byte chunk),
     * the `4A 01` sub-header with [seq], the chunk, CRC.
     */
    private fun chunk(seq: Int, data: ByteArray, final: Boolean = false): ByteArray {
        val length = 13 + 10 + data.size
        val b = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0x55).putShort((0x400 or length).toShort())
        b.put(DjiCrc.computeCrc8(b.array().copyOf(3)).toByte()).putShort(0x0201).putShort(0x0403)
        b.put(0).put(0).put(0x27)
        b.put(0x4A).put(0x01).putShort(((10 + data.size) or if (final) 0x1000 else 0).toShort())
        b.putShort(1).putInt(seq).put(data)
        b.putShort(DjiCrc.computeCrc16(b.array().copyOf(length - 2)).toShort())
        return b.array()
    }

    private val chunk0 = chunk(0, firstChunk)
    private val chunk1 = chunk(1, secondChunk, final = true)

    /** A download-channel (type 3) SW UDP envelope carrying [body] at [seq]. */
    private fun envelope(seq: Int, body: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(20 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort((0x8000 or b.capacity()).toShort()).putShort(session.toShort()).putShort(seq.toShort()).put(3)
        b.put(b.array().take(7).fold(0) { a, v -> a xor v.toInt() }.toByte())
        b.position(20)
        b.put(body)
        return b.array()
    }

    // Both chunks in one stream, cut 50 bytes into the second: its head ends one envelope, its tail opens the next.
    private val stream = chunk0 + chunk1
    private val datagrams = listOf(
        envelope(0x73d8, stream.copyOf(chunk0.size + 50)),
        envelope(0x73e0, stream.copyOfRange(chunk0.size + 50, stream.size)),
    )

    @Test
    fun `whole datagrams lose a chunk split across envelopes, and the rebuilt blob keeps all sixteen files`() {
        val s = session()
        val raw = datagrams.reduce(ByteArray::plus)
        assertEquals("the hole is chunk 1", 1L, s.action1ListGap(raw, ctr = 1))

        val blob = s.listBlobForTest(datagrams, session)
        assertNull("nothing missing once the split frame is rebuilt", s.action1ListGap(blob, ctr = 1))
        val files = s.collectStoresForTest(blob)
        assertEquals(16, files.size)
        assertEquals(0x6401cfL, files.first().fileIndex)
        assertEquals(0x6401bdL, files.last().fileIndex)
        assertEquals(false, s.lastSlicesForTest()[0]!!.incomplete)
    }

    @Test
    fun `a resent chunk completes the list wherever it lands, and a second copy is ignored`() {
        val s = session()
        for (blob in listOf(chunk0 + chunk1, chunk1 + chunk0, chunk0 + chunk1 + chunk1)) {
            assertNull(s.action1ListGap(blob, ctr = 1))
            assertEquals(16, s.collectStoresForTest(blob).size)
        }
    }

    @Test
    fun `a list still short of its header shows the records that came and reads as truncated`() {
        val s = session()
        val files = s.collectStoresForTest(chunk0)
        assertEquals(14, files.size)
        assertEquals(0x6401bfL, files.last().fileIndex)
        val sd = s.lastSlicesForTest()[0]!!
        assertTrue(sd.incomplete)
        assertEquals(14, sd.records)
    }

    @Test
    fun `only an Action 1 list is held for a resend`() {
        assertEquals(1L, session().action1ListGap(chunk0, ctr = 1))
        assertNull("not an Action 1", session(legacy = null).action1ListGap(chunk0, ctr = 1))
        assertNull("another counter", session().action1ListGap(chunk0, ctr = 2))
    }

    @Test
    fun `the resend request confirms the chunks before the hole and lists the hole`() {
        assertEquals("4a02171001000000000001000000010100000001000000",
            session().listResend(1, 1).joinToString("") { "%02x".format(it) })
    }
}
