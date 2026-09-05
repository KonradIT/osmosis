package dev.konraditurbe.osmosis.camera

import dev.konraditurbe.osmosis.core.CameraFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Osmo Action 4 — the newest page of a 2026-09-05 session, recovered from the log's hex dump with
 * `tools/hexdump_to_bin.py`. The camera held more (`more=true`); this is the first 45.
 *
 * The complement to [Oa4ManifestTest]'s six-file card: a mixed library — 40 stills, 5 clips, two of the
 * stills favourited — on the same body. Every record is sized and deletable, so it is also the fixture
 * for "stills carry a size and a handle" on an Action body, which the six-file card only half showed.
 *
 * Two things this page pins that no other fixture does:
 *
 *  - **Stars on stills.** Both favourites are JPGs; no clip is starred. The flag is read by signature
 *    ([CameraSession.starFlagBySignature]), and this is the first Action-family capture where the
 *    signature has to match a still's layout rather than a clip's.
 *  - **A clock that moved mid-card.** `_0011_` is stamped `22:45:57`, six hours past `_0012_` at
 *    `16:50:23` although it was shot first (lower sequence number). Six hours is UTC+8, DJI's factory
 *    zone, against Europe/Berlin summer time — the body was on the default zone until the app's
 *    connect-time sync moved it. The grid sorts by that stamp, so 0011 lands between 0046 and 0045.
 *    Pinned as fact, not as desired behaviour: any change to how the grid orders (by name stamp, by
 *    sequence, or a blend) has its worked example here.
 */
class Oa4StarredManifestTest {

    private fun decode() = CameraSession(log = {}, port = 9004, tcpPoke = true)
        .decodeManifestForTest(
            javaClass.classLoader!!.getResourceAsStream("manifests/oa4_45.bin")!!.readBytes()
        )

    private fun byId(n: String) = decode().first { it.name.contains("_${n}_") }

    @Test
    fun `forty-five records, forty stills and five clips`() {
        val files = decode()
        assertEquals(45, files.size)
        assertEquals("every path distinct", 45, files.map { it.path }.toHashSet().size)
        assertEquals(40, files.count { it.ext == "JPG" })
        assertEquals(5, files.count { it.ext == "MP4" })
        assertTrue("stock DJI_ naming", files.all { it.name.startsWith("DJI_") })
        assertTrue("single-store page", files.all { it.group == 0 })
    }

    /** The camera lists newest first by its own file number: 0055 down to 0011, no gaps. */
    @Test
    fun `manifest order is sequence order, newest first`() {
        assertEquals((55 downTo 11).toList(), decode().map { it.seq })
    }

    /** Exactly two favourites, both stills — the star signature matched on a JPG record, twice. */
    @Test
    fun `two stills are starred and nothing else is`() {
        val files = decode()
        assertEquals(
            listOf("DJI_20260903235322_0047_D.JPG", "DJI_20260903165611_0016_D.JPG"),
            files.filter { it.starred }.map { it.name },
        )
        assertTrue("no clip is starred", files.filter { it.isVideo }.none { it.starred })
        assertTrue("a star on a still must not disturb its neighbours",
            listOf("0046", "0048", "0015", "0017").none { byId(it).starred })
    }

    /** Xtra SD geometry, as on the six-file card: base `0x00040000`, step `0x10`, every handle its own. */
    @Test
    fun `handles are unique and stepped by 0x10 from 0x00040000`() {
        val files = decode()
        assertEquals(45, files.map { it.handle }.toHashSet().size)
        for (f in files) {
            assertEquals("handle should be base + seq*step for ${f.name}", 0x00040000L + f.seq * 0x10L, f.handle)
            assertEquals("and equal the fit", f.cmdHandle, f.handle)
        }
    }

    /** All 45 deletable, stills included — the promotion through the fit holds at scale, not just on six. */
    @Test
    fun `every record is deletable and sized`() {
        val files = decode()
        assertEquals(45, files.count { it.deletable })
        assertTrue("every record carries a byte size", files.all { it.sizeBytes > 0 })
        assertEquals(4_628_480L, byId("0016").sizeBytes)
        assertEquals(5_787_648L, byId("0047").sizeBytes)
        assertEquals(122_112_115L, byId("0026").sizeBytes)
    }

    /** Four 1080p30 clips and one 1080p60; durations are per record, down to a 0 s and a 1 s clip. */
    @Test
    fun `clips report resolution, frame rate and duration, stills report none`() {
        for (id in listOf("0024", "0025", "0026", "0027")) {
            assertEquals("1920x1080", byId(id).resolution)
            assertEquals("30fps", byId(id).resLabel)
        }
        assertEquals("1920x1080", byId("0055").resolution)
        assertEquals("60fps", byId("0055").resLabel)
        assertEquals(listOf(10, 1, 30, 0, 3), listOf("0024", "0025", "0026", "0027", "0055").map { byId(it).durationSec })
        for (f in decode().filter { !it.isVideo }) {
            assertNull(f.resolution); assertNull(f.resLabel); assertEquals(0, f.durationSec)
        }
    }

    /** Each record's thumbnail is its own — matched by trailing base, not by position. */
    @Test
    fun `every thumbnail belongs to its own record`() {
        for (f in decode()) {
            assertEquals("MISC/THM/DJI_001/${f.name.substringBeforeLast('.')}.scr", f.thumbPath)
            assertTrue(f.path.startsWith("DCIM/DJI_001/"))
        }
    }

    /**
     * The name stamp on `_0011_` is six hours ahead of the file shot four minutes after it. The grid
     * keys on that stamp, so this is where the one file on the page changes places. See the class doc.
     */
    @Test
    fun `one still carries a stamp from before the clock was synced`() {
        val early = byId("0011"); val next = byId("0012")
        assertEquals("20260903224557", early.timestamp)
        assertEquals("20260903165023", next.timestamp)
        assertTrue("shot first, stamped later", early.seq < next.seq && early.timestamp > next.timestamp)
        assertEquals("same calendar day either way", early.ymd, next.ymd)

        val gridOrder = decode()
            .sortedWith(compareByDescending<CameraFile> { it.timestamp }.thenByDescending { it.seq })
            .map { it.seq }
        assertEquals("0011 sits between 0046 and 0045 under the grid's sort",
            listOf(46, 11, 45), gridOrder.slice(gridOrder.indexOf(46)..gridOrder.indexOf(45)))
    }
}
