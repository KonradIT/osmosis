package dev.konraditurbe.osmosis.dcf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A **second, independent Osmo Action 1** — a tester's camera, 2026-08-05, not the unit the layout was
 * derived from. Its whole manifest is 73 bytes, hex-dumped verbatim by the shipped build when the
 * CompositePack decoder found nothing:
 *
 * ```
 * 0000  01000000 49000000 2984e65c c4909e05 78006400 07000610 032f018a a2d2eb03
 * 0020  00040000 00010005 0000000a 0000ce1c 00000000 …
 * ```
 *
 * This is the confirmation the layout was missing. [Action1RecordsTest] pins it against the camera it
 * came from — a layout can always be fitted to the data that produced it. Holding on a different unit,
 * with a different file count and a different clip, is what makes it a format rather than a fit.
 */
class Action1SecondCameraTest {

    /** The 73 bytes exactly as the tester's log dumped them. */
    private val manifest = ("01000000490000002984e65cc4909e05" +
        "7800640007000610032f018aa2d2eb03" +
        "00040000000100050000000a0000ce1c" +
        "00000000000000000000000000000000" +
        "000000000000000000").let { hex ->
        ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }
    }

    @Test fun `the dump is a whole manifest — one 65-byte record behind the 8-byte header`() {
        assertEquals(73, manifest.size)
        // The header is self-describing, and decodeAction1 rejects anything that doesn't add up:
        // count=1, total=73, and 8 + 1*65 == 73 exactly.
        assertEquals(1, DcfRecords.decodeAction1(manifest).size)
    }

    @Test fun `the single clip decodes to a real on-card file`() {
        val r = DcfRecords.decodeAction1(manifest).single()
        // index 0x00640078 -> SD, 100MEDIA, file 120. The old decoder read the file number from +12
        // (which is duration) and would have called this DJI_0007.
        assertEquals(0, DcfIndex.storage(r.fileIndex))
        assertEquals(100, DcfIndex.dir(r.fileIndex))
        assertEquals(120, DcfIndex.file(r.fileIndex))
        assertEquals("DCIM/100MEDIA/DJI_0120.MP4", DcfIndex.path(r.fileIndex, "MP4"))
    }

    @Test fun `size and duration agree with each other and with a real bitrate`() {
        val r = DcfRecords.decodeAction1(manifest).single()
        assertEquals(94_277_828L, r.sizeBytes)   // +4
        assertEquals(7, r.durationSec)           // +38 = 7374 ms, rounded down
        // 94.2 MB over 7.4 s = ~12.7 MB/s — the top of the three bitrates seen on the other camera,
        // i.e. 4K. A field mis-mapping shows up here immediately as an absurd rate.
        val mbPerSec = r.sizeBytes / 1e6 / r.durationSec
        assertTrue("implausible $mbPerSec MB/s", mbPerSec > 1.0 && mbPerSec < 30.0)
    }

    @Test fun `the timestamp is a real date, not a mis-read field`() {
        val r = DcfRecords.decodeAction1(manifest).single()
        assertEquals(1_558_610_985L, r.mtimeEpoch)   // 2019-05-23 — an Action 1 whose clock was never set
    }
}
