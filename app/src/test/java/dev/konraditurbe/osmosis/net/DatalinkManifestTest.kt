package dev.konraditurbe.osmosis.net

import org.junit.Test
import org.junit.Assert.*

/**
 * Decoder tests over real captured file-list blobs (raw DUML datagrams, pre-reassembly):
 *  - manifests/nano_45.bin — Osmo Nano, 45 records (44 video + 1 JPG). Includes the record whose
 *    DCIM path straddled a 0x00/0x27 frame boundary (0264_D) — the case that used to silently drop.
 *  - manifests/xtra_13.bin — Xtra Edge Pro / Action 5 Pro, 13 records (1 MP4 + 12 JPG).
 *  - manifests/action1_7.bin — Osmo Action 1, 7 records in the older index-based format (no path
 *    strings; files addressed by FileIndex, downloaded via /v1).
 * These lock in both the frame reassembly (10-byte 0x00/0x27 sub-header stripping) and the
 * count-validated record decode. Regenerate a fixture by dumping DatalinkClient's raw file-list blob.
 */
class DatalinkManifestTest {

    private fun decode(fixture: String, port: Int): List<dev.konraditurbe.osmosis.core.CameraFile> {
        val bytes = javaClass.classLoader!!.getResourceAsStream("manifests/$fixture")!!.readBytes()
        return DatalinkClient(log = {}, port = port, tcpPoke = port == 9004).decodeManifestBlobForTest(bytes)
    }

    @Test
    fun nanoDecodesAll45() {
        val files = decode("nano_45.bin", 9004)
        assertEquals("record count must match the manifest's declared u32 count", 45, files.size)
        // No duplicates — every record maps to a distinct path.
        assertEquals(45, files.map { it.path }.toHashSet().size)
        // The regression case: the boundary-straddling record must be present and well-formed.
        val f0264 = files.single { it.name == "DJI_20260712173055_0264_D.MP4" }
        assertEquals("DCIM/DJI_001/DJI_20260712173055_0264_D.MP4", f0264.path)
        assertEquals("MISC/THM/DJI_001/DJI_20260712173055_0264_D.scr", f0264.thumbPath)
        assertEquals("25fps", f0264.resLabel)
        // fps only on videos: 44 videos carry it, the lone JPG does not.
        assertEquals(44, files.count { it.resLabel != null })
        assertEquals(1, files.count { it.ext == "JPG" })
        assertTrue(files.none { it.ext == "JPG" && it.resLabel != null })
    }

    @Test
    fun xtraDecodesAll13() {
        val files = decode("xtra_13.bin", 10004)
        assertEquals(13, files.size)
        assertEquals(13, files.map { it.path }.toHashSet().size)
        // Xtra uses the CAM_ naming under DCIM/CAM_001/.
        assertTrue(files.all { it.path.startsWith("DCIM/CAM_001/CAM_") })
        // Exactly one MP4 (with fps), the rest JPGs (without).
        val videos = files.filter { it.ext == "MP4" }
        assertEquals(1, videos.size)
        assertEquals("25fps", videos.single().resLabel)
        assertEquals(12, files.count { it.ext == "JPG" })
        assertTrue(files.filter { it.ext == "JPG" }.all { it.resLabel == null })
    }

    @Test
    fun osmoAction1IndexListDecodesAll7() {
        val files = decode("action1_7.bin", 9004)
        assertEquals(7, files.size)
        // Every record is index-based → carries a fileIndex and downloads via /v1?file_index=.
        assertTrue(files.all { it.fileIndex != null })
        assertTrue(files.all { it.urlPath().startsWith("/v1?file_index=") })
        // FileIndex + descending order cross-checked against the camera's RTOS transfer log.
        assertEquals(0x640251, files.first().fileIndex)
        assertEquals(0x640241, files.last().fileIndex)
        // Synthesized names carry the real date so sorting/date display work; no thumbnail URL yet.
        assertTrue(files.first().path.startsWith("DCIM/100MEDIA/DJI_2019"))
        assertTrue(files.all { it.thumbUrlPath().isEmpty() })
    }
}
