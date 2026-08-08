package dev.konraditurbe.osmosis.drone

import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.previewCandidates
import dev.konraditurbe.osmosis.core.thumbUrlPath
import dev.konraditurbe.osmosis.core.urlPath
import dev.konraditurbe.osmosis.dcf.DcfIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which HTTP surface an aircraft's media is fetched from.
 *
 * The mistake this guards against is treating `/v1` as "the drone scheme". It is the *Mavic 3's*
 * scheme, and the Mavic 3 is one of only three current aircraft that install it — so a Neo 2 addressed
 * that way asks for a URL its firmware never serves.
 */
class DroneAddressingTest {

    private fun droneFile(modelId: Int?): CameraFile {
        val index = DcfIndex.pack(storage = 1, dir = 100, file = 554)
        return CameraFile(
            path = DcfIndex.path(index, "MP4"),
            thumbPath = DcfIndex.path(index, "MP4"),
            fileIndex = index,
            storage = 1,
            dcfHttpV2 = DroneProducts.usesHttpV2(modelId),
        )
    }

    @Test
    fun `the Mavic 3 keeps the packed-index v1 URL it was verified on`() {
        assertFalse(DroneProducts.usesHttpV2(0x70))
        val url = droneFile(0x70).urlPath()
        assertTrue("expected /v1, got $url", url.startsWith("/v1?file_index="))
    }

    @Test
    fun `a Neo 2 is addressed by path over v2`() {
        assertTrue(DroneProducts.usesHttpV2(0x7E))
        assertEquals("/v2?storage=1&path=DCIM/100MEDIA/DJI_0554.MP4", droneFile(0x7E).urlPath())
    }

    /**
     * The grid must never fetch originals.
     *
     * A drone record has no thumbnail path of its own — the decoder copies the media path into
     * `thumbPath` — so any scheme that trusts that field turns every cell into a full-clip download.
     * On a 45-file page of 1.4 GB clips that is ~60 GB to draw one screen.
     */
    @Test
    fun `a v2 drone thumbnail is a sidecar or EXIF, never the original`() {
        val video = droneFile(0x7E)
        assertEquals("/v2?storage=1&path=DCIM/100MEDIA/DJI_0554.THM", video.thumbUrlPath())

        val still = video.copy(path = "DCIM/100MEDIA/DJI_0554.JPG", durationSec = 0)
        val thumb = still.thumbUrlPath()
        assertTrue("a still should come from its own EXIF", thumb.startsWith(CameraFile.EXIF_THUMB))

        for (f in listOf(video, still)) {
            assertNotEquals("a thumbnail must never be the original", f.urlPath(), f.thumbUrlPath())
        }
    }

    /** Preview may end at the original — that is one file the user opened, not a gridful. */
    @Test
    fun `a v2 drone preview tries the cheap rendition first`() {
        val chain = droneFile(0x7E).previewCandidates()
        assertEquals("/v2?storage=1&path=DCIM/100MEDIA/DJI_0554.LRF", chain.first())
        assertEquals("/v2?storage=1&path=DCIM/100MEDIA/DJI_0554.MP4", chain.last())
    }

    @Test
    fun `an unknown aircraft stays on the scheme we have made work`() {
        // Not a claim that /v1 is right for it — a preference for the failure we can recognise.
        assertFalse(DroneProducts.usesHttpV2(null))
        assertFalse(DroneProducts.usesHttpV2(0x99))
        assertTrue(droneFile(null).urlPath().startsWith("/v1?"))
    }

    @Test
    fun `the three v1 aircraft are exactly the ones the app installs it on`() {
        for (id in listOf(0x70, 0x71, 0x74)) {
            assertFalse("0x%02x should be v1".format(id), DroneProducts.usesHttpV2(id))
        }
        // A sample of the v2 majority, including two that share a product family with the v1 rows —
        // the split is per-airframe, not per-family.
        for (id in listOf(0x73, 0x72, 0x76, 0x7B, 0x7C, 0x7E, 0xD0)) {
            assertTrue("0x%02x should be v2".format(id), DroneProducts.usesHttpV2(id))
        }
    }

    @Test
    fun `Mini 2 and Mini 3 serve no HTTP download at all`() {
        // They support the native catalogue but install no HTTP surface, so neither URL scheme
        // applies and a download needs the native file-body path nobody has reversed.
        assertEquals(DroneProducts.Http.NATIVE_ONLY, DroneProducts.of(0x8D)!!.http)
        assertEquals(DroneProducts.Http.NATIVE_ONLY, DroneProducts.of(0x75)!!.http)
        assertFalse(DroneProducts.usesHttpV2(0x75))
    }
}
