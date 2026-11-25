package dev.konraditurbe.osmosis.dcf

import dev.konraditurbe.osmosis.core.urlPath
import dev.konraditurbe.osmosis.core.previewCandidates
import dev.konraditurbe.osmosis.ble.CameraModel
import dev.konraditurbe.osmosis.camera.CameraSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Osmo Action 1's 65-byte record layout, pinned to a real list captured off the camera.
 *
 * The fixture is the **raw** datalink blob (all datagrams, sub-headers included); the reassembled list
 * inside it is `[u32 count=7][u32 total=463]` + 7 × 65 B. The camera's own log independently states
 * `FileNumToSend: 7, SizeToSend: 463` and `FileIndexSending: 0x640251` … `0x640241`, so the count, the
 * size and both end indices are externally corroborated rather than self-asserted.
 */
class Action1RecordsTest {

    /** Locate the reassembled list inside the raw capture by its self-describing header. */
    private fun listBytes(): ByteArray {
        val raw = javaClass.classLoader!!.getResourceAsStream("manifests/action1_7.bin")!!.readBytes()
        val head = byteArrayOf(7, 0, 0, 0, 0xCF.toByte(), 1, 0, 0) // count=7, total=463
        val at = (0..raw.size - head.size).first { i -> head.indices.all { raw[i + it] == head[it] } }
        return raw.copyOfRange(at, at + 463)
    }

    @Test
    fun `decodes all seven records, with the indices the camera logged`() {
        val recs = DcfRecords.decodeAction1(listBytes())
        assertEquals(7, recs.size)
        assertEquals(0x640251L, recs.first().fileIndex)
        assertEquals(0x640241L, recs.last().fileIndex)
    }

    @Test
    fun `the packed index yields the on-card name, not the raw halves`() {
        val r = DcfRecords.decodeAction1(listBytes()).first()
        // 0x640251 -> storage 0, dir 100, file 593. The regression this guards: reading +12 (which is
        // duration) as the file number produced DJI_0117 for a file that is really DJI_0593.
        assertEquals(0, DcfIndex.storage(r.fileIndex))
        assertEquals(100, DcfIndex.dir(r.fileIndex))
        assertEquals(593, DcfIndex.file(r.fileIndex))
    }

    @Test
    fun `every record implies a sane video bitrate`() {
        // Weak on its own — but combined with the +12/+38 agreement below it fixes which field is the
        // size. The camera records at several bitrates (~1.6, ~4.4 and ~12.5 MB/s in this capture), so
        // this deliberately does NOT assert a constant rate; an early version did and was wrong.
        val recs = DcfRecords.decodeAction1(listBytes())
        assertEquals(7, recs.size)
        for (r in recs) {
            val rate = r.sizeBytes.toDouble() / r.durationSec
            assertTrue("implausible ${r.sizeBytes} B over ${r.durationSec}s", rate in 100_000.0..20_000_000.0)
        }
    }

    @Test
    fun `a sub-second clip is a video, not a still`() {
        // The trap this guards: one record's whole-second duration is 0, so the drone's
        // `durationSec == 0 => photo` rule would misfile it. Its millisecond duration is 667, and the
        // camera's own log lists its UUID among the DjiMovDmx videos.
        val recs = DcfRecords.decodeAction1(listBytes())
        assertTrue("every file in this capture is a video", recs.all { it.durationSec > 0 })
        assertTrue("expected the 0.667 s clip to round up to 1 s", recs.any { it.durationSec == 1 })
    }

    @Test
    fun `AC101 packs local capture time as FAT`() {
        val r = DcfRecords.decodeAction1(listBytes()).first()
        val expected = java.time.LocalDateTime.of(2026, 5, 2, 19, 2, 10)
        assertEquals(expected.atZone(java.time.ZoneId.systemDefault()).toEpochSecond(), r.mtimeEpoch)
    }

    @Test
    fun `a drone manifest is rejected, so the two decoders cannot be crossed`() {
        // 94-byte records with no [count][total] header must not parse as an Action 1 list.
        assertTrue(DcfRecords.decodeAction1(ByteArray(8 + 94 * 3)).isEmpty())
        assertTrue(DcfRecords.decodeAction1(ByteArray(0)).isEmpty())
    }

    private fun descriptor(type: Int, ext: ByteArray, index: Int = 0x640001): ByteArray =
        ByteBuffer.allocate(18 + ext.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x5ca29845)
            putInt(512)
            putInt(index)
            putInt(0)
            put(type.toByte())
            put(ext.size.toByte())
            put(ext)
        }.array()

    private fun page(vararg records: ByteArray): ByteArray {
        val total = 8 + records.sumOf { it.size }
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(records.size)
            putInt(total)
            records.forEach { put(it) }
        }.array()
    }

    @Test fun `typed photos and numbered video parts survive the camera session decoder`() {
        val raw = page(descriptor(0, byteArrayOf(3, 1)), descriptor(1, byteArrayOf(), 0x640002),
            descriptor(2, byteArrayOf(10, 1, 0, 0x78, 5, 0, 0, 5, 0, 0, 0), 0x640003),
            descriptor(2, byteArrayOf(10, 2, 0, 0x78, 5, 0, 0, 0, 0, 0, 0), 0x640003))
        val profile = CameraModel.resolve(0x82, null).legacyAlbum
        val files = CameraSession({}, 9004, true, legacyAlbum = profile).decodeManifestForTest(raw)
        assertEquals(listOf("DJI_0001.JPG", "DJI_0002.DNG", "DJI_0003_001.MOV", "DJI_0003_002.MOV"),
            files.map { it.name })
        assertTrue(files.first().starred)
        assertEquals(1, files.last().durationSec)
        assertEquals(4, files.distinctBy { it.albumKey }.size)
        assertTrue(files.last().previewCandidates().all { it.endsWith("file_seg_subindex=2") })
        assertTrue(files.none { it.isBurst || it.deletable || it.sidecarCandidate() != null })
    }

    @Test fun `v2 uses the supplied camera path when the descriptor includes one`() {
        val name = "DCIM/DJI_001/DJI_20260706163318_0120_D.MP4".toByteArray()
        val record = descriptor(3, byteArrayOf(13, name.size.toByte()) + name)
        val file = DcfRecords.decodeAction1(page(record), variableRecords = true).single()
            .toCameraFile(DcfAddressing.Route.V2)
        assertEquals("/v2?storage=0&path=${String(name)}", file.urlPath())
    }

    @Test fun `malformed extension lengths reject the entire page`() {
        val first = descriptor(3, byteArrayOf())
        val truncated = descriptor(3, byteArrayOf(10, 1))
        assertTrue(DcfRecords.decodeAction1(page(first, truncated), variableRecords = true).isEmpty())
        truncated[17] = 57
        assertTrue(DcfRecords.decodeAction1(page(first, truncated), variableRecords = true).isEmpty())
    }

    @Test fun `Action 2 and 3 select their own Mimo URL format for indexed media`() {
        val raw = page(descriptor(3, byteArrayOf(0), 0x40640002))
        fun from(id: Int?, name: String?) = CameraSession({}, 9004, true,
            legacyAlbum = CameraModel.resolve(id, name).legacyAlbum).decodeManifestForTest(raw).single()
        assertEquals(CameraModel.LegacyActionAlbum.ACTION_1, CameraModel.resolve(null, "OsmoAction-FB74FD").legacyAlbum)
        assertEquals(CameraModel.LegacyActionAlbum.ACTION_1, CameraModel.resolve(null, "Osmo Action 1").legacyAlbum)
        assertEquals(DcfUrls.of(0x40640002), from(null, "DJI Action 2").urlPath())
        assertEquals("/v2?storage=1&path=DCIM/100MEDIA/DJI_0002.MP4", from(0x12, null).urlPath())
        assertTrue(from(0x12, null).albumKey != from(0x12, null).copy(fileIndex = 0x640002).albumKey)
    }
}
