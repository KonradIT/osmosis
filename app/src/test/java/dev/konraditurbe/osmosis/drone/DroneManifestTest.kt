package dev.konraditurbe.osmosis.drone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes the drone media manifest from bytes captured off a **real Mavic 3** (DJI Fly browsing its
 * gallery over QuickTransfer WiFi, PCAPdroid, 2026-08-01). Fixture: `mavic3_manifest.txt`, one line
 * per `0x00/0x27` reply as `<seq> <chunkIndex> <hex payload>`.
 *
 * Two of these records can be checked against ground truth outside the manifest: DJI Fly downloaded
 * `file_index` 6554154 and 6554148 over HTTP in the same capture, and the server's `Content-Length`
 * came back as 14168064 and 9494528 — exactly the sizes decoded here. That pins the size field and,
 * with it, the 94-byte stride.
 */
class DroneManifestTest {

    private data class Row(val seq: Int, val chunk: Int, val payload: ByteArray)

    private fun fixture(): List<Row> {
        val text = javaClass.classLoader!!.getResourceAsStream("manifests/mavic3_manifest.txt")!!
            .bufferedReader().readText()
        return text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (s, c, hex) = line.trim().split(" ")
                Row(s.toInt(), c.toInt(), hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            }.toList()
    }

    private fun chunksFor(seq: Int) =
        fixture().filter { it.seq == seq }.mapNotNull { DroneManifest.parseChunk(it.payload) }

    @Test
    fun `parses the 0x4a envelope including the 0x1000 final-chunk flag`() {
        // seq 0xdd is a complete single-chunk reply: 10 files, 948 declared bytes, FINAL set.
        val c = chunksFor(0xDD).single()
        assertEquals(0, c.index)
        assertEquals(10, c.count)
        assertEquals(948, c.totalBytes)
        assertTrue("single-chunk reply must carry the final flag", c.isFinal)
    }

    @Test
    fun `decodes a complete reply into all ten files, newest first`() {
        val files = DroneManifest.decode(DroneManifest.assemble(chunksFor(0xDD)))
        assertEquals(10, files.size)

        val newest = files.first()
        assertEquals(6553777L, newest.fileIndex)
        assertEquals("DCIM/100MEDIA/DJI_0177.MP4", newest.path)
        assertEquals(290512504L, newest.sizeBytes)
        assertEquals(17, newest.durationSec)
        assertTrue(newest.isVideo)

        // Records run newest-first, one file index per step down.
        assertEquals(listOf(177, 176, 175, 174, 173, 172, 171, 170, 169, 168),
            files.map { (it.fileIndex and 0xFFFF).toInt() })
    }

    @Test
    fun `duration zero means a still photo and names it JPG`() {
        val files = DroneManifest.decode(DroneManifest.assemble(chunksFor(0xDD)))
        val photo = files.single { it.fileIndex == 6553771L }
        assertEquals(0, photo.durationSec)
        assertEquals("DCIM/100MEDIA/DJI_0171.JPG", photo.path)
        assertTrue(photo.isImage)
        assertEquals(11689984L, photo.sizeBytes)
    }

    @Test
    fun `sizes match the HTTP Content-Length DJI Fly actually received`() {
        // The two files downloaded in the same capture, via /v1?file_index=…&file_subtype=0.
        val all = fixture().mapNotNull { DroneManifest.parseChunk(it.payload) }
            .groupBy { it.seq }
            .flatMap { (_, cs) -> DroneManifest.decode(DroneManifest.assemble(cs)) }
            .associateBy { it.fileIndex }

        assertEquals(14168064L, all[6554154L]!!.sizeBytes)
        assertEquals(9494528L, all[6554148L]!!.sizeBytes)
    }

    @Test
    fun `decoded records are index-addressed, never path-addressed`() {
        val f = DroneManifest.decode(DroneManifest.assemble(chunksFor(0xDD))).first()
        assertTrue(f.isIndexed)
        assertEquals(6553777L, f.fileIndex)
        // Which URLs that produces is MediaAddressingTest's business, not the wire format's.
    }

    @Test
    fun `file_index is packed storage + directory + number, not a flat folder`() {
        // bits 31:30 storage | 29:16 DCF directory (14 bits) | 15:0 file number.
        // Masking the directory as 16 bits folds the storage bits in, so an internal-storage file reads
        // as directory 16484 and gets dropped — every eMMC file silently vanishing from the grid.
        val sd = DroneManifest.decode(record(index = (0L shl 30) or (100L shl 16) or 554L))
        assertEquals(1, sd.size)
        assertEquals("DCIM/100MEDIA/DJI_0554.JPG", sd[0].path)
        assertEquals(0, sd[0].storage)

        val emmc = DroneManifest.decode(record(index = (1L shl 30) or (100L shl 16) or 1L))
        assertEquals("an internal-storage file must survive decoding", 1, emmc.size)
        assertEquals("DCIM/100MEDIA/DJI_0001.JPG", emmc[0].path)
        assertEquals(1, emmc[0].storage)
    }

    /** One synthetic 94-byte record with the given index (photo: duration 0). */
    private fun record(index: Long): ByteArray {
        val r = ByteArray(DroneManifest.RECORD_STRIDE)
        fun put32(off: Int, v: Long) {
            for (i in 0 until 4) r[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }
        put32(0, 0x5CECB9FBL)   // FAT mtime
        put32(4, 1_234_567L)    // size
        put32(8, index)
        return r
    }

    // The FAT-vs-unix mtime decode is a DCF concern shared with the Osmo Action 1 — see DcfIndexTest.

    @Test
    fun `grid id and date grouping work without a camera-style filename`() {
        val files = DroneManifest.decode(DroneManifest.assemble(chunksFor(0xDD)))
        val f = files.single { it.fileIndex == 6553777L }
        // seq feeds the "%04d" badge on a grid cell and the preview screen's ID. A drone name has no
        // _NNNN_D field, so without the packed-index fallback every cell reads 0000.
        assertEquals(177, f.seq)
        // Date grouping keys off ymd. Empty means everything piles under one unknown-date header even
        // though the preview screen shows the right date.
        assertEquals(8, f.ymd.length)
        assertTrue("ymd should be a real YYYYMMDD", f.ymd.startsWith("20"))
        assertEquals(f.ymd, f.dateTaken.take(10).replace("-", ""))
    }

    @Test
    fun `mtime drives the display date when the name carries no timestamp`() {
        val f = DroneManifest.decode(DroneManifest.assemble(chunksFor(0xDD))).first()
        assertEquals("", f.timestamp)                 // no DJI_<14 digits>_ name on a drone
        assertTrue("expected a real date from the manifest mtime", f.dateTaken.startsWith("20"))
        assertTrue("FAT decode should not land decades in the past", f.mtimeEpoch > 1_600_000_000L)
    }

    @Test
    fun `a reply missing middle chunks yields only the records it can trust`() {
        // seq 0x0c declares 45 files / 4238 bytes but the capture only carries chunks 0, 3 and 4 —
        // concatenating those shifts the stream out of phase, so the tail must be rejected rather
        // than emitted as files with 1970s dates and absurd sizes.
        val cs = chunksFor(0x0C)
        assertEquals(listOf(0, 3, 4), cs.map { it.index }.sorted())
        assertEquals(45, cs.first { it.index == 0 }.count)

        val files = DroneManifest.decode(DroneManifest.assemble(cs))
        assertTrue("should still recover the leading intact records", files.size >= 10)
        assertTrue("must not invent 45 files from a gapped stream", files.size < 45)
        assertTrue(files.all { (it.fileIndex shr 16) == 100L })
        assertTrue(files.all { it.sizeBytes > 0 })
    }

    @Test
    fun `rejects payloads whose declared length disagrees with the bytes held`() {
        val good = fixture().first { it.seq == 0xDD }.payload
        assertNotNull(DroneManifest.parseChunk(good))
        assertNull(DroneManifest.parseChunk(good.copyOfRange(0, good.size - 4)))
        assertNull(DroneManifest.parseChunk(byteArrayOf(0x4A, 0x01)))
        // A thumbnail reply (subtype 0x21) shares the command but is not a file list.
        val thumb = good.copyOf().also { it[1] = 0x21 }
        assertNull(DroneManifest.parseChunk(thumb))
    }

    @Test
    fun `the transfer control frames match the reference app byte for byte`() {
        // Lifted from a capture of DJI Fly browsing a Mavic 3's album. A transfer that is never
        // released holds its slot, and the drone serves a bounded number of them per session — which
        // is what stalled paging a few thumbnails in.
        assertEquals("release a media list (sub 04)",
            "4a040e1005000000000001000000",
            DroneManifest.listAck(0x05).joinToString("") { "%02x".format(it) })
        assertEquals("proceed, answering the drone's state frame (sub 02)",
            "4a020f100500000000000000000000",
            DroneManifest.transferGo(0x05).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `list query matches DJI Fly's bytes and patches seq plus cursor`() {
        // Captured verbatim from DJI Fly at t=11.97 (seq 0x0c, cursor 1).
        val expected = "4a0021100c0000000000010000002d000d0100ffffffffffffffff000100000000"
        assertEquals(expected, DroneManifest.listQuery(0x0C).joinToString("") { "%02x".format(it) })
        // The paging variant seen at t=12.41: same frame, seq 0x0d, cursor 0x40000001.
        assertEquals("4a0021100d0000000000010000402d000d0100ffffffffffffffff000100000000",
            DroneManifest.listQuery(0x0D, 0x40000001L).joinToString("") { "%02x".format(it) })
        assertEquals("4a040e100c000000000001000000",
            DroneManifest.listAck(0x0C).joinToString("") { "%02x".format(it) })
    }
}
