package dev.konraditurbe.osmosis.camera

import dev.konraditurbe.osmosis.core.StorageRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Osmo Action 4 — the microSD list from a tester's session on 2026-08-24, recovered from the log's
 * hex dump with `tools/hexdump_to_bin.py`.
 *
 * The first body to reach the grid whose model entry said it could not: `0x0014` was annotated
 * "pairs + BLE creds, but its AP never comes up (open)". This session pairs, brings up a **WPA2** AP,
 * handshakes on 9004, lists six files and downloads one. So the fixture is not only a decoder pin,
 * it is the evidence that retired that note.
 *
 * Two things about this camera are worth stating up front, because both would be guessed wrong from
 * the product name alone:
 *
 *  - **Playback entry is the Pocket 3's `0x01/0x01`, not `0x02/0x0c`.** The Action 4 answers `0x02/0x0c`
 *    with `0xe0` on every attempt; the control burst set its playback bit after 8 frames. An Action
 *    body on the Pocket route — the family split is not by product line.
 *  - **Handle geometry is the Xtra's SD geometry** (`0x00040000` + `seq * 0x10`), not the Nano/Pocket 4
 *    `0x40100000` + `seq * 0x40`. See [Op4ManifestTest] for the same surprise in the other direction.
 */
class Oa4ManifestTest {

    private fun decode() = CameraSession(log = {}, port = 9004, tcpPoke = true)
        .decodeManifestForTest(
            javaClass.classLoader!!.getResourceAsStream("manifests/oa4_6.bin")!!.readBytes()
        )

    private fun byId(n: String) = decode().first { it.name.contains("_${n}_") }

    @Test
    fun `all six records struct-decode`() {
        val files = decode()
        assertEquals(6, files.size)
        assertEquals("every path distinct", 6, files.map { it.path }.toHashSet().size)
        assertEquals(4, files.count { it.ext == "MP4" })
        assertEquals(2, files.count { it.ext == "JPG" })
        assertTrue("stock DJI_ naming, no model suffix", files.all { it.name.startsWith("DJI_") })
        assertTrue("every record is sized", files.all { it.sizeBytes > 0 })
    }

    /**
     * Newest first, and the sequence numbers are the camera's own file numbers.
     *
     * Pinned because the grid's ordering is this list's ordering — a decoder that returned the
     * manifest in storage order would put the oldest still at the top of the screen.
     */
    @Test
    fun `records come back newest first`() {
        assertEquals(
            listOf(
                "DJI_20260812131032_0006_D.MP4",
                "DJI_20260812130449_0005_D.MP4",
                "DJI_20260812125237_0004_D.MP4",
                "DJI_20260811205805_0003_D.MP4",
                "DJI_20260811205751_0002_D.JPG",
                "DJI_20260810094824_0001_D.JPG",
            ),
            decode().map { it.name },
        )
        assertEquals(listOf(6, 5, 4, 3, 2, 1), decode().map { it.seq })
    }

    /** Xtra SD geometry on an Action body: base `0x00040000`, step `0x10`, every handle its own. */
    @Test
    fun `handles are unique and stepped by 0x10 from 0x00040000`() {
        val files = decode()
        assertEquals("no two records may share a delete handle", 6, files.map { it.handle }.toHashSet().size)
        for (f in files) assertEquals(
            "handle should be base + seq*step for ${f.name}",
            0x00040000L + f.seq * 0x10L, f.handle,
        )
    }

    /**
     * **Both stills are deletable**, and that is the regression this fixture exists to hold.
     *
     * The tester ran a build from before `ea4890f`, whose log reads `4 deletable` of six: the two JPGs
     * came back `handle=0x00000000` because the guard-byte scan refused to read a still's handle, so
     * long-press-delete did nothing on half this camera's library. The same bytes through the current
     * decoder promote both — the marker's handle and the fitted `base + seq*step` agree, which is the
     * two-independent-sources bar `withCmdHandles` sets before it will arm a destructive command.
     *
     * Asserting the *count* as well as the flags: a promotion that fired for the wrong reason (say, the
     * fit filling in every record blindly) would still leave six deletable, but it would also leave the
     * videos' handles disagreeing with their markers, which the geometry test above would catch.
     */
    @Test
    fun `stills take their delete handle from the marker once the fit agrees`() {
        val files = decode()
        assertEquals("all six deletable, stills included", 6, files.count { it.deletable })
        for (f in files.filter { it.ext == "JPG" }) {
            assertTrue("${f.name} must be deletable", f.deletable)
            assertEquals("and its handle must equal the fit", f.cmdHandle, f.handle)
        }
        assertEquals(0x00040020L, byId("0002").handle)
        assertEquals(0x00040010L, byId("0001").handle)
    }

    /**
     * A 2.27 GB clip — the largest single file in any fixture, and the reason sizes are `Long`.
     *
     * `DJI_…_0003_D.MP4` is 2,269,994,910 B, which is 122 MB past `Int.MAX_VALUE`. Read into an `Int`
     * anywhere on the path from manifest to Content-Range it wraps negative, and the download either
     * refuses to start or truncates. Nothing else captured so far comes close, so without this record
     * the overflow is unguarded.
     */
    @Test
    fun `a clip past Int MAX_VALUE keeps its full byte size`() {
        val big = byId("0003")
        assertEquals(2_269_994_910L, big.sizeBytes)
        assertTrue("past Int.MAX_VALUE, so it must never round-trip through an Int",
            big.sizeBytes > Int.MAX_VALUE.toLong())
        assertEquals(921, big.durationSec)
    }

    /** Sizes and durations are per record — a read across a boundary would be plain here. */
    @Test
    fun `every record reports its own size and duration`() {
        assertEquals(276_560_514L, byId("0006").sizeBytes)
        assertEquals(381_422_703L, byId("0005").sizeBytes)
        assertEquals(160_212_194L, byId("0004").sizeBytes)
        assertEquals(4_784_128L, byId("0002").sizeBytes)
        assertEquals(5_259_264L, byId("0001").sizeBytes)

        assertEquals(111, byId("0006").durationSec)
        assertEquals(148, byId("0005").durationSec)
        assertEquals(52, byId("0004").durationSec)
        assertTrue("stills have no duration", decode().filter { it.ext == "JPG" }.all { it.durationSec == 0 })
    }

    /** Four 1080p30 clips; the stills carry no frame size in this manifest and must not invent one. */
    @Test
    fun `videos report resolution and frame rate, stills report neither`() {
        for (f in decode().filter { it.isVideo }) {
            assertEquals("1920x1080", f.resolution)
            assertEquals("30fps", f.resLabel)
        }
        for (f in decode().filter { !it.isVideo }) {
            assertNull(f.resolution)
            assertNull(f.resLabel)
        }
    }

    /** Each record's thumbnail is its own — matched by trailing base, not by position. */
    @Test
    fun `every thumbnail belongs to its own record`() {
        for (f in decode()) {
            val base = f.name.substringBeforeLast('.')
            assertEquals("MISC/THM/DJI_001/$base.scr", f.thumbPath)
            assertTrue("media path under DCIM", f.path.startsWith("DCIM/DJI_001/"))
        }
    }

    /**
     * Single-store body: every file is served from `/v2?storage=0`, which is what the download proved.
     *
     * The Action 4 is not pinned via `singleSdStorage` — it resolves like any two-store body, and the
     * answer comes out right anyway because the internal bit is clear on all six handles. Worth pinning
     * because the family is split on this: a single-store Nano and Action 6 serve at `1`, this and the
     * Pocket 3 at `0`, so "one store" alone never determined the mount.
     */
    @Test
    fun `every file mounts at storage 0`() {
        for (f in decode()) {
            assertTrue("SD handle clears the internal bit", f.handle and StorageRules.INTERNAL_BIT == 0L)
            assertEquals("${f.name} -> storage 0", 0,
                StorageRules.mountGuess(singleSdStorage = false, handle = f.handle, cmdHandle = f.cmdHandle))
        }
    }

    /**
     * The blob opens **mid-record** — and the half record must be dropped, not guessed at.
     *
     * These bytes start at `5f 30 30 30 37 5f 44 00` (`_0007_D\0`), the tail of a seventh file's
     * filename field: `DJI_20260818075758_0007_D.JPG`, newer than everything listed. Whether the camera
     * served a partial first chunk or one was lost in flight, the decoder's only correct move is to find
     * no media-path field for it and skip it — the alternative is a record built from another file's
     * bytes, carrying another file's delete handle.
     *
     * So this asserts the *skip*, not the loss. That a file on the card can be absent from the grid is a
     * separate question this capture cannot answer, and the assertion here would hold either way.
     */
    @Test
    fun `a truncated leading record is skipped rather than half-parsed`() {
        val files = decode()
        assertTrue("the partial record must not appear", files.none { it.name.contains("_0007_") })
        assertTrue("nor may its bytes leak into a neighbour's path", files.none { it.path.contains("_0007_") })
        assertFalse("and no record may be built from a truncated name",
            files.any { it.name.startsWith("_") || it.path.endsWith("/") })
    }

    /** Nothing was favourited on this camera, so the star column proves nothing — pinned, not evidence. */
    @Test
    fun `nothing is starred, because nothing was favourited`() {
        assertTrue(decode().none { it.starred })
    }

    /** No proxy is listed, as on every other body — the `.LRF` preview URL is derived, not read. */
    @Test
    fun `the proxy is not listed in the manifest`() {
        assertTrue(decode().all { it.proxyPath == null })
    }
}
