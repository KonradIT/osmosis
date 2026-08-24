package dev.konraditurbe.osmosis.camera

import dev.konraditurbe.osmosis.dcf.DcfRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The camera manifest's video format enum (`marker−1`), pinned per code.
 *
 * Worth a test of its own because the table is a **decimal transcription of an enum quoted in hex**,
 * and the two spellings collide silently: `10` is 1080p, hex `10` is 4K, and both are plausible rows.
 * A row entered in the wrong base doesn't fail loudly — it labels a clip with another format's frame
 * size, which is the sort of thing that survives a long time. Every assertion below therefore states
 * the hex the code came from alongside the decimal the table is keyed on.
 *
 * See MEDIA_PROTOCOL.md § Resolution.
 */
class VideoFormatIndexTest {

    private val dl = CameraSession({}, 9004, true)

    private fun res(hex: String) = dl.resolutionForIndex(hex.toInt(16))

    @Test
    fun `the landscape formats decode`() {
        assertEquals("1920x1080", res("0A"))   // 10  1080p 16:9
        assertEquals("1920x1440", res("0C"))   // 12  1080p 4:3
        assertEquals("3840x2160", res("10"))   // 16  4K 16:9
        assertEquals("2688x1512", res("2D"))   // 45  2.7K 16:9
        assertEquals("2688x2016", res("5F"))   // 95  2.7K 4:3
        assertEquals("3840x2880", res("67"))   // 103 4K 4:3
    }

    /** Vertical formats are taller than they are wide — a transposed row would read as landscape. */
    @Test
    fun `the vertical formats decode and stay portrait`() {
        assertEquals("1080x1920", res("42"))   // 66  1080p 9:16
        assertEquals("1512x2688", res("43"))   // 67  2.7K 9:16
        assertEquals("1728x3072", res("6C"))   // 108 3K 9:16

        for (h in listOf("42", "43", "6C")) {
            val (w, tall) = res(h)!!.split('x').map { it.toInt() }
            org.junit.Assert.assertTrue("$h must be portrait", tall > w)
        }
    }

    /** Square formats: width equals height, including the OpenGate entry. */
    @Test
    fun `the square formats decode`() {
        assertEquals("1080x1080", res("69"))   // 105 1080p 1:1
        assertEquals("2160x2160", res("6A"))   // 106 2160p 1:1
        assertEquals("3072x3072", res("6B"))   // 107 3K 1:1
        assertEquals("3840x3840", res("7D"))   // 125 4K OpenGate

        for (h in listOf("69", "6A", "6B", "7D")) {
            val (w, tall) = res(h)!!.split('x').map { it.toInt() }
            assertEquals("$h must be square", w, tall)
        }
    }

    /**
     * The trap, stated as an assertion: **hex `10` and decimal `10` are different formats.**
     *
     * `0x43` = 67 and decimal `67` = 0x43 happen to be the same row here, but `0x67` = 103 is a
     * different one — so a code read as hex where the table wants decimal lands on a real, wrong entry
     * rather than falling through to null.
     */
    @Test
    fun `decimal and hex spellings of the same digits are different formats`() {
        assertEquals("3840x2160", dl.resolutionForIndex(0x10))   // hex 10 → 4K
        assertEquals("1920x1080", dl.resolutionForIndex(10))     // decimal 10 → 1080p

        assertEquals("1512x2688", dl.resolutionForIndex(0x43))   // hex 43 = 67 → 2.7K vertical
        assertEquals("3840x2880", dl.resolutionForIndex(0x67))   // hex 67 = 103 → 4K 4:3
    }

    /**
     * The camera and drone enums are the same namespace sampled separately, and the codes they share
     * must keep agreeing — the six rows added from the hex table were taken from the drone side on that
     * basis. The tables stay separate (a drone's set is much larger), so nothing but this test would
     * notice them drifting apart.
     */
    @Test
    fun `codes present in both enums agree on pixels`() {
        for (code in 0..0xFF) {
            val camera = dl.resolutionForIndex(code) ?: continue
            val drone = DcfRecords.droneResolution(code) ?: continue
            assertEquals("code 0x%02x disagrees between the camera and drone tables".format(code),
                drone, camera)
        }
    }

    /** An unmapped code yields null so the caller can log it by name, rather than a fabricated size. */
    @Test
    fun `an unmapped code is null, not a guess`() {
        assertNull(dl.resolutionForIndex(0))
        assertNull(dl.resolutionForIndex(0xFE))
        assertNull(dl.resolutionForIndex(255))
    }
}
