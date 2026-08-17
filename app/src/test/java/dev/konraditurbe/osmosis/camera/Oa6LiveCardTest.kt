package dev.konraditurbe.osmosis.camera

import dev.konraditurbe.osmosis.core.StorageRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Osmo Action 6, camera in hand (2026-08-17), with **both stores populated** and every file's
 * provenance known from the camera's own screen.
 *
 * `oa6_sd_3.bin` — the microSD list: `0001` 4K 16:9 @ 25, `0002` 4K OpenGate @ 30, `0003` a 40 MP still.
 * `oa6_internal_2.bin` — the built-in list: `0001` 4K 16:9 @ 25, `0002` a 40 MP still.
 *
 * This is the first two-store body with media on *both* sides, which is what makes the pair worth
 * keeping: the `0x40000000` handle bit that picks a file's `/v2?storage=` mount was fitted on bodies
 * where one store was empty, so nothing until now could tell a correct rule from a lucky one.
 */
class Oa6LiveCardTest {

    private fun decode(name: String) = CameraSession(log = {}, port = 9004, tcpPoke = true)
        .decodeManifestForTest(
            javaClass.classLoader!!.getResourceAsStream("manifests/$name")!!.readBytes()
        )

    private val sd get() = decode("oa6_sd_3.bin")
    private val internal get() = decode("oa6_internal_2.bin")

    @Test
    fun `both stores decode whole`() {
        assertEquals(3, sd.size)
        assertEquals(2, internal.size)
        for (f in sd + internal) assertTrue("every record is sized", f.sizeBytes > 0)
    }

    /**
     * Two stores, two handle bases, one step — and the same file number appears in both.
     *
     * `0001` exists on the card *and* on the built-in store, so a client that keyed anything off the
     * name would collapse them; only the handle's `0x40000000` bit separates them.
     */
    @Test
    fun `each store has its own handle base and the internal bit separates them`() {
        assertEquals(listOf(0x00100040L, 0x00100080L, 0x001000C0L), sd.map { it.handle }.sorted())
        assertEquals(listOf(0x40100040L, 0x40100080L), internal.map { it.handle }.sorted())
        assertTrue("card handles clear the internal bit", sd.all { it.handle and 0x40000000L == 0L })
        assertTrue("built-in handles set it", internal.all { it.handle and 0x40000000L != 0L })
    }

    /** The mount each file is actually served from: card → `storage=0`, built-in → `storage=1`. */
    @Test
    fun `the handle bit picks the right storage mount on a body with both stores full`() {
        for (f in sd) assertEquals("SD ${f.name} -> storage 0", 0,
            StorageRules.mountGuess(singleSdStorage = false, handle = f.handle, cmdHandle = f.cmdHandle))
        for (f in internal) assertEquals("internal ${f.name} -> storage 1", 1,
            StorageRules.mountGuess(singleSdStorage = false, handle = f.handle, cmdHandle = f.cmdHandle))
    }

    /**
     * The favourite flag, against what the camera's own gallery showed.
     *
     * Exactly two files were favourited before this pair was dumped — the **still** `0003` on the card
     * and the **video** `0001` on the built-in store — and those are the two that read back. It is the
     * Nano's offset (a `0`/`1` byte 9 past the record marker), not the Pocket 3's signature, so this
     * body is a third answer to "where is the star": a real flag, at the original place, on both media
     * kinds. One favourite per store also rules out a decoder that simply stars the first record of a
     * list, which a single-store dump could not.
     */
    @Test
    fun `the favourite flag matches the camera on a still and a video`() {
        assertTrue("only the card's still", sd.single { it.starred }.name.contains("_0003_"))
        assertEquals("and it is a still", "JPG", sd.single { it.starred }.ext)

        assertTrue("only the built-in's video", internal.single { it.starred }.name.contains("_0001_"))
        assertTrue("and it is a video", internal.single { it.starred }.isVideo)
        assertEquals("two favourites in all", 2, (sd + internal).count { it.starred })
    }

    /**
     * Frame rate is per record, and a clip must not inherit its neighbour's.
     *
     * The card holds a 25 fps clip next to a 29.97 fps one, and `0002` used to report 25: the rational
     * is kept as the *last* match in a window that reaches into the next record, so the scan ran past
     * `0002`'s own `30000/1001` and took `0001`'s `25000/1000`. Bounded at the next record's head now.
     *
     * It has to be the next head rather than this record's path, because the field orders disagree —
     * this body writes `head · enum · filename · path`, the CAM_ family `head · path · enum · filename`.
     * Bounding at the path silently blanked every Xtra clip's frame rate.
     */
    @Test
    fun `a clip reports its own frame rate, not the neighbouring clip's`() {
        val byId = { n: String -> sd.first { it.name.contains("_${n}_") } }
        assertEquals("30fps", byId("0002").resLabel)   // 30000/1001, was reporting 25fps
        assertEquals("25fps", byId("0001").resLabel)   // 25000/1000
        assertEquals("25fps", internal.first { it.isVideo }.resLabel)
    }

    /**
     * Format index **125** is the 1:1 full-sensor mode the camera's UI calls "4K OpenGate", and it
     * carries pixels like every other index: 3840×3840, ffprobed off the downloaded file.
     *
     * Kept as a `W x H` string with an ASCII `x`, because that is what the preview parses to coarsen a
     * clip to "4K" — a `×` here reads as one token and blanks the label instead.
     */
    @Test
    fun `the OpenGate index decodes to a square 4K frame`() {
        assertEquals("3840x3840", sd.first { it.name.contains("_0002_") }.resolution)
        assertEquals("3840x2160", sd.first { it.name.contains("_0001_") }.resolution)
        assertEquals("3840x2160", internal.first { it.isVideo }.resolution)
    }

    /** Stills carry their pixel size directly — 40 MP 4:3, the size DJI's own UI calls "L". */
    @Test
    fun `a still reports its own pixel size`() {
        for (f in (sd + internal).filter { !it.isVideo }) assertEquals("7296x5472", f.resolution)
    }

    /**
     * Byte sizes are per record, videos and stills alike — the OpenGate clip is 127 MB for ten seconds
     * (~101 Mbit/s), three times the 4K 16:9 clip beside it, so a size read across a record boundary
     * would be obvious here.
     */
    @Test
    fun `every file reports its own byte size`() {
        assertEquals(127_391_571L, sd.first { it.name.contains("_0002_") }.sizeBytes)
        assertEquals(42_051_246L, sd.first { it.name.contains("_0001_") }.sizeBytes)
        assertEquals(2_408_448L, sd.first { it.ext == "JPG" }.sizeBytes)
        assertEquals(39_513_171L, internal.first { it.isVideo }.sizeBytes)
        assertEquals(2_834_432L, internal.first { it.ext == "JPG" }.sizeBytes)
    }

    /** Every record is deletable and no handle is shared — the invariant `0x00/0x28` depends on. */
    @Test
    fun `every record has a delete handle of its own`() {
        val all = sd + internal
        assertTrue("all deletable", all.all { it.deletable })
        assertEquals("no handle is shared", all.size, all.map { it.handle }.toHashSet().size)
    }

    /**
     * The media-type byte is **not** where a Pocket 3 keeps it, and nothing may assume it is.
     *
     * That byte (still\video\panorama) is read from a fixed `19 06` tag seven bytes before the record's
     * media path. This body writes its filename field in between, so the tag isn't there and the type
     * comes back unknown — correctly, rather than as whatever byte happens to sit at the offset.
     * Everything that matters still works: kind comes from the extension, not from this.
     */
    @Test
    fun `the Pocket 3 type byte is absent here and reads as unknown rather than as garbage`() {
        assertTrue((sd + internal).all { it.mediaType == -1 })
        assertTrue("no record is mistaken for a panorama", (sd + internal).none { it.isPanorama })
    }

    /** No proxy is listed, exactly as on every other body — the `.LRF` preview URL is derived. */
    @Test
    fun `the proxy is not listed in the manifest`() {
        assertTrue((sd + internal).all { it.proxyPath == null })
        assertNull(sd.first { it.isVideo }.proxyPath)
    }
}
