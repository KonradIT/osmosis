package dev.konraditurbe.osmosis.core

import dev.konraditurbe.osmosis.camera.PathAddressing
import dev.konraditurbe.osmosis.dcf.DcfAddressing
import dev.konraditurbe.osmosis.dcf.DcfIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two addressing schemes side by side — the seam that keeps `/v2`-by-path and `/v1`-by-index out
 * of each other's code.
 *
 * The camera half was previously unasserted anywhere: the `/v2` URL shape and the derived-sidecar proxy
 * rules were only exercised by running against real hardware.
 */
class MediaAddressingTest {

    private fun cameraFile(
        path: String = "DCIM/DJI_001/DJI_20260329115359_0211_D.MP4",
        storage: Int = 1,
        proxyPath: String? = null,
    ) = CameraFile(
        path = path,
        thumbPath = path.replace("DCIM/", "MISC/THM/").replace(".MP4", ".scr"),
        storage = storage,
        proxyPath = proxyPath,
    )

    private fun dcfFile(dir: Int = 100, file: Int = 554, video: Boolean = true) = CameraFile(
        path = DcfIndex.path(DcfIndex.pack(0, dir, file), if (video) "MP4" else "JPG"),
        thumbPath = "",
        fileIndex = DcfIndex.pack(0, dir, file),
        durationSec = if (video) 17 else 0,
    )

    @Test
    fun `a file's origin picks its scheme, and the two never overlap`() {
        assertSame(PathAddressing, MediaAddressing.of(cameraFile()))
        assertSame(DcfAddressing, MediaAddressing.of(dcfFile()))
    }

    // ---- path-based cameras (/v2) ------------------------------------------------------------------

    @Test
    fun `camera media is addressed by path on its resolved mount index`() {
        val f = cameraFile(storage = 1)
        assertEquals("/v2?storage=1&path=DCIM/DJI_001/DJI_20260329115359_0211_D.MP4", f.urlPath())
        assertEquals(
            "/v2?storage=1&path=MISC/THM/DJI_001/DJI_20260329115359_0211_D.scr",
            f.thumbUrlPath(),
        )
        // storage is a probed mount index, not a fixed SD/internal mapping — an Xtra served SD at 0.
        assertEquals("/v2?storage=0&path=DCIM/DJI_001/DJI_20260329115359_0211_D.MP4",
            cameraFile(storage = 0).urlPath())
    }

    @Test
    fun `a listed proxy wins, then the derived sidecar, then the original`() {
        val listed = cameraFile(proxyPath = "DCIM/DJI_001/DJI_20260329115359_0211_D.LRV")
        assertEquals(
            listOf(
                "/v2?storage=1&path=DCIM/DJI_001/DJI_20260329115359_0211_D.LRV",
                "/v2?storage=1&path=DCIM/DJI_001/DJI_20260329115359_0211_D.LRF",
                "/v2?storage=1&path=DCIM/DJI_001/DJI_20260329115359_0211_D.MP4",
            ),
            listed.previewCandidates(),
        )
        assertEquals("the chain must end at the original", listed.urlPath(), listed.previewCandidates().last())
    }

    @Test
    fun `the derived proxy extension follows the camera family's naming prefix`() {
        // The Xtra / Action 5 Pro (CAM_) writes an unlisted .XRF sidecar; DJI-proper (DJI_) uses .LRF.
        assertTrue(cameraFile(path = "DCIM/CAM_001/CAM_20260329115359_0211_D.MP4")
            .previewCandidates().any { it.endsWith(".XRF") })
        assertTrue(cameraFile().previewCandidates().any { it.endsWith(".LRF") })
        // An unknown convention derives nothing — just stream the full-res file.
        assertEquals(
            listOf("/v2?storage=1&path=DCIM/XX_001/FOO_0001.MP4"),
            cameraFile(path = "DCIM/XX_001/FOO_0001.MP4").previewCandidates(),
        )
    }

    @Test
    fun `burst frames enumerated after the manifest are addressed by their own path`() {
        assertEquals(
            "/v2?storage=1&path=DCIM/DJI_001/DJI_20260329115359_0286_D_002.JPG",
            PathAddressing.byPath(1, "DCIM/DJI_001/DJI_20260329115359_0286_D_002.JPG"),
        )
    }

    // ---- DCF-indexed devices (/v1) -----------------------------------------------------------------

    @Test
    fun `indexed media is addressed by number, never by path`() {
        val f = dcfFile()
        assertEquals("/v1?file_index=6554154&file_subtype=0&file_seg_subindex=0", f.urlPath())
        assertTrue("no /v2 may ever be built for an indexed file",
            f.previewCandidates().none { it.startsWith("/v2") })
    }

    @Test
    fun `a video's thumbnail is its THM, a still's can only come over the datalink`() {
        // Probed on a Mavic 3: for a photo index only subtype 0 answers — every other subtype makes
        // the server close the connection, which is how this firmware reports a missing file. So a
        // still has no HTTP thumbnail to ask for, and asking anyway left every photo cell blank.
        assertEquals("/v1?file_index=6554154&file_subtype=1&file_seg_subindex=0",
            dcfFile(video = true).thumbUrlPath())
        assertEquals("${CameraFile.DUML_THUMB}6554154", dcfFile(video = false).thumbUrlPath())
    }

    @Test
    fun `video preview prefers the subtype-18 proxy, stills the screen-res render`() {
        // Pinned to a capture of DJI Fly playing + scrubbing videos: every playback fetch was
        // `file_subtype=18` with Range requests, and the single `file_subtype=0` fetch was the download.
        assertEquals(
            listOf(
                "/v1?file_index=6554154&file_subtype=18&file_seg_subindex=0",
                "/v1?file_index=6554154&file_subtype=0&file_seg_subindex=0",
            ),
            dcfFile(video = true).previewCandidates(),
        )
        assertEquals(
            listOf(
                "/v1?file_index=6554154&file_subtype=2&file_seg_subindex=0",
                "/v1?file_index=6554154&file_subtype=0&file_seg_subindex=0",
            ),
            dcfFile(video = false).previewCandidates(),
        )
    }

    @Test
    fun `a proxy is a subtype of the same index, not a sidecar path`() {
        // The camera scheme's proxyPath is meaningless here and must not leak into the chain even if a
        // record somehow carried one.
        val f = dcfFile().copy(proxyPath = "DCIM/100MEDIA/DJI_0554.LRV")
        assertTrue(f.previewCandidates().all { it.startsWith("/v1?file_index=") })
    }
}
