package dev.konraditurbe.osmosis.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards for three places the decoder used to guess, all pinned to the real captures.
 *
 * Every claim here was measured off the fixtures before the code changed — see the individual tests
 * for the numbers. Together they cover: which DUML frames carry the manifest, whether the favourite
 * flag is readable, and whether the library has more pages.
 */
class ManifestRobustnessTest {

    private fun raw(fixture: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("manifests/$fixture")!!.readBytes()

    private fun session(port: Int) = CameraSession(log = {}, port = port, tcpPoke = port == 9004)

    /**
     * The manifest is reassembled from DUML `0x00/0x27` frames only.
     *
     * Measured across both raw captures: every frame whose payload begins `4A 01` is on `0x00/0x27`
     * (16,154 chunk bytes on the Nano, 3,600 on the Xtra) and no other command carries one. Injecting
     * a decoy frame that has the old `4A 01` prefix on a *different* command must therefore change
     * nothing — under the previous prefix-only match its body was spliced straight into the manifest,
     * which is how raising the subscription list to Mimo's 54 keys drowned the media stream.
     */
    @Test
    fun `a 4A 01 frame on another command is not treated as manifest`() {
        val clean = raw("nano_45.bin")
        val expected = session(9004).decodeManifestBlobForTest(clean)
        assertEquals(45, expected.size)

        // 0x02/0x23 carrying the same prefix — shaped like a subscription push, not a media chunk.
        val body = "camcap_photo_time_limited_burst_param".toByteArray()
        val payload = byteArrayOf(0x4A, 0x01) + ByteArray(8) + body
        val len = payload.size + 13
        val decoy = byteArrayOf(
            0x55, (len and 0xFF).toByte(), ((len shr 8) and 0x03).toByte(), 0x00,
            0x01, 0x02, 0x03, 0x04, 0x00, 0x02, 0x23,
        ) + payload + byteArrayOf(0, 0)

        val polluted = session(9004).decodeManifestBlobForTest(clean + decoy)
        assertEquals("a non-0x00/0x27 frame must not reach the manifest", expected.size, polluted.size)
        assertEquals(expected.map { it.path }, polluted.map { it.path })
    }

    /**
     * The favourite flag is read only where it actually reads as a boolean.
     *
     * At marker+9 the Nano fixtures split 0/1 cleanly — `nano_delete.bin`, captured while favourites
     * were being tested, is 19 zeros to 26 ones. The Xtra's records put a *path length* at that offset
     * (`1a <len> 00 00 00 01 DCIM/…`), reading 44 or 48, so treating "non-zero" as starred would badge
     * every Xtra file at once.
     */
    @Test
    fun `xtra path-length byte is never read as a star`() {
        val files = session(10004).decodeManifestBlobForTest(raw("xtra_13.bin"))
        assertEquals(13, files.size)
        assertTrue("44/48 at marker+9 is a length, not a flag", files.none { it.starred })
    }

    /** The Nano decode still works end to end; nano_45.bin predates favouriting, so none are starred. */
    @Test
    fun `nano decode is unaffected`() {
        val files = session(9004).decodeManifestBlobForTest(raw("nano_45.bin"))
        assertEquals(45, files.size)
        assertTrue(files.none { it.starred })
    }

    /**
     * The per-store split agrees with the handle-bit rule it replaces, on every record we have.
     *
     * Two independent derivations of the same fact: the counter echoed in the response sub-header
     * (which query returned this record) versus bit `0x40000000` of the record's own handle. Both
     * fixtures were captured from the internal-store query — counter 2, 16154 and 3600 chunk bytes,
     * no counter-1 chunks at all — so every record must come back stamped internal, and every handle
     * must have the bit set. 58 records across two camera families.
     *
     * What this does *not* cover: a blob containing both stores, because neither fixture has one. The
     * SD side is evidenced from the Mimo captures instead — on a Nano `0x00000001` returns the single
     * dock-SD clip against 45 internal, on an Xtra 35 against 45.
     */
    @Test
    fun `store split agrees with the handle bit it replaces`() {
        for ((fixture, port) in listOf("nano_45.bin" to 9004, "xtra_13.bin" to 10004)) {
            val files = session(port).collectStoresForTest(raw(fixture))
            assertTrue("$fixture decoded nothing", files.isNotEmpty())
            for (f in files) {
                assertTrue("$fixture/${f.name}: store should be known, not guessed", f.storageKnown)
                assertEquals("$fixture/${f.name}: captured from the internal query", 1, f.storage)
                if (f.handle != 0L) assertTrue(
                    "$fixture/${f.name}: handle 0x%08x should carry the internal bit".format(f.handle),
                    f.handle >= 0x40000000L,
                )
            }
        }
    }

    /**
     * A blob the split can't attribute falls back to the merged parse, unstamped.
     *
     * Two ways that happens: a camera that doesn't echo the request counter, and one that answers
     * both store queries with the same list. Either way the files must still decode — an empty grid
     * would be far worse than a HEAD probe — and must be left for the old per-file resolution.
     */
    @Test
    fun `an unattributable blob still decodes and stays unstamped`() {
        val clean = raw("nano_45.bin")
        // Rewrite every response sub-header counter to 7, a value neither query uses.
        val odd = clean.copyOf()
        var i = 0
        while (i + 13 <= odd.size) {
            if (odd[i] != 0x55.toByte()) { i++; continue }
            val len = ((odd[i + 1].toInt() and 0xFF) or ((odd[i + 2].toInt() and 0xFF) shl 8)) and 0x3FF
            if (len < 13 || i + len > odd.size) { i++; continue }
            val p = i + 11
            if (len - 13 > 10 && odd[p] == 0x4A.toByte() && odd[p + 1] == 0x01.toByte()) odd[p + 4] = 7
            i += len
        }
        val files = session(9004).collectStoresForTest(odd)
        assertEquals("must still decode, not blank the grid", 45, files.size)
        assertTrue("nothing to attribute, so nothing claimed", files.none { it.storageKnown })
    }

    /**
     * A short page ends the library, a full one does not.
     *
     * `xtra_13.bin` returned 13 of the 45 we asked for, so there is nothing older and the pull-up
     * spinner must not arm. `nano_45.bin` returned a full 45 out of a 195-file library, so it must.
     * This replaces `pageCursor > 0L`, which was true for any camera holding at least one video.
     */
    @Test
    fun `more pages only when the page came back full`() {
        val s = session(9004)
        val cursor = 0x40101780L

        // The two real cases, using each fixture's actual record count.
        val xtra = s.decodeManifestBlobForTest(raw("xtra_13.bin"))
        assertEquals(13, xtra.size)
        assertTrue("13 of 45 is the end of the library", !s.hasOlderPage(xtra.size, cursor))

        val nano = s.decodeManifestBlobForTest(raw("nano_45.bin"))
        assertEquals(45, nano.size)
        assertTrue("a full 45 means more to come", s.hasOlderPage(nano.size, cursor))

        // No cursor is still the end, however full the page.
        assertTrue(!s.hasOlderPage(45, 0L))
        // An empty camera must not arm the spinner — the old cursor-only test could.
        assertTrue(!s.hasOlderPage(0, cursor))
    }
}
