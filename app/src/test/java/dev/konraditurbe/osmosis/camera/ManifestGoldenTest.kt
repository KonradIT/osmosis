package dev.konraditurbe.osmosis.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A golden-master snapshot of the manifest decoder across **every** capture we hold.
 *
 * The CompositePack decoder is the most fragile surface in the app: it reads a dozen fields off
 * length-delimited records whose byte layout differs per camera family, and its history is a string of
 * one-body-at-a-time regressions — an fps read that overran into the next clip, a photo that borrowed
 * a neighbour's delete handle, a path length mistaken for a star flag. Each was invisible on the body
 * it was tuned against and wrong on another.
 *
 * This test decodes each fixture through the real end-to-end path ([CameraSession.decodeManifestBlobForTest],
 * which reassembles `0x00/0x27` frames when the fixture is a raw datagram blob and passes an assembled
 * manifest straight through) and pins **every decoded field of every record** against a committed
 * snapshot in `manifests/golden/`. Any change to any field on any body fails here, with the exact
 * lines that moved — so a fix for one camera can no longer silently shift another.
 *
 * ## Regenerating
 *
 * When a decode change is *intended*, the snapshot is expected to move. Regenerate it deliberately:
 * make [dev.konraditurbe.osmosis.camera.ManifestGoldenTest.CANON] emit the new lines (it already
 * does), run this test, and copy the "actual" block it prints on failure into the matching
 * `manifests/golden/<fixture>.golden.txt`. Review that diff as carefully as the code change — a
 * snapshot updated without reading it defeats the point.
 */
class ManifestGoldenTest {

    private fun raw(f: String) =
        javaClass.classLoader!!.getResourceAsStream("manifests/$f")!!.readBytes()

    private fun golden(f: String) =
        javaClass.classLoader!!.getResourceAsStream("manifests/golden/$f.golden.txt")!!
            .readBytes().decodeToString().trimEnd('\n').split("\n")

    /** One canonical, stable line per record — every field the UI reads, sorted by name. */
    private fun canon(files: List<dev.konraditurbe.osmosis.core.CameraFile>) = files.map {
        "%s|%s|%08x|%08x|%d|%s|%s|%d|%b|%d|%d|%b".format(
            it.name, it.ext, it.handle, it.cmdHandle, it.sizeBytes, it.resolution ?: "",
            it.resLabel ?: "", it.durationSec, it.starred, it.mediaType, it.group, it.deletable)
    }.sorted()

    private fun check(fixture: String, port: Int) {
        val got = canon(CameraSession(log = {}, port = port, tcpPoke = port == 9004)
            .decodeManifestBlobForTest(raw(fixture)))
        val want = golden(fixture)
        if (got != want) {
            println("=== $fixture actual (copy into manifests/golden/$fixture.golden.txt if intended) ===")
            got.forEach(::println)
        }
        assertEquals("$fixture: record count moved", want.size, got.size)
        assertEquals("$fixture: a decoded field changed", want, got)
    }

    @Test fun `nano newest page`() = check("nano_45.bin", 9004)
    @Test fun `nano with favourites and bursts`() = check("nano_delete.bin", 9004)
    @Test fun `xtra newest page`() = check("xtra_13.bin", 10004)
    @Test fun `xtra full card`() = check("xtra_delete.bin", 10004)
    @Test fun `action 6 sd`() = check("oa6_sd_3.bin", 10004)
    @Test fun `action 6 internal`() = check("oa6_internal_2.bin", 10004)
    @Test fun `pocket 3 five with a star`() = check("op3_5_starred.bin", 9004)
    @Test fun `pocket 3 nine with a panorama`() = check("op3_9_pano.bin", 9004)
    @Test fun `pocket 3 nine stars moved`() = check("op3_9_stars_moved.bin", 9004)
    @Test fun `pocket 3 eleven panos`() = check("op3_11_panos.bin", 9004)
    @Test fun `pocket 3 fifteen`() = check("op3_15.bin", 9004)
    @Test fun `pocket 3 twentynine`() = check("op3_29.bin", 9004)
    @Test fun `pocket 4`() = check("op4_45.bin", 9004)
}
