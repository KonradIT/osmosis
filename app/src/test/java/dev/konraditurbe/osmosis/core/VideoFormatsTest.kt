package dev.konraditurbe.osmosis.core

import dev.konraditurbe.osmosis.camera.CameraSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame-size → name lookup that replaced `coarseRes`.
 *
 * The tests that matter here are the three shapes the old threshold arithmetic got wrong, pinned as
 * regressions so no one is tempted to reintroduce a bucketing function.
 */
class VideoFormatsTest {

    @Test
    fun `the square formats are named for what they are, not bucketed by their long edge`() {
        // coarseRes bucketed on maxOf(w, h): 2160 cleared its 1900 threshold but not its 3600 one, so a
        // 2160x2160 clip was labelled "1080p" — the single worst output the old function produced.
        assertEquals("2160p 1:1", VideoFormats.label("2160x2160"))
        assertEquals("1080p 1:1", VideoFormats.label("1080x1080"))
        assertEquals("3K 1:1", VideoFormats.label("3072x3072"))
        assertEquals("4K OpenGate", VideoFormats.label("3840x3840"))
    }

    @Test
    fun `vertical and landscape sizes with the same numbers get different names`() {
        assertEquals("1080p", VideoFormats.label("1920x1080"))
        assertEquals("1080p 9:16", VideoFormats.label("1080x1920"))
        assertEquals("2.7K", VideoFormats.label("2688x1512"))
        assertEquals("2.7K 9:16", VideoFormats.label("1512x2688"))
        assertEquals("3K 9:16", VideoFormats.label("1728x3072"))
    }

    /** Aspect variants are distinct names — the long edge alone cannot tell 4K 16:9 from 4K 4:3. */
    @Test
    fun `aspect variants are named apart`() {
        assertEquals("4K", VideoFormats.label("3840x2160"))
        assertEquals("4K 4:3", VideoFormats.label("3840x2880"))
        assertEquals("1080p 4:3", VideoFormats.label("1920x1440"))
        assertEquals("2.7K 4:3", VideoFormats.label("2688x2016"))
    }

    /**
     * A size with no name falls back to the pixels, not to a shrug.
     *
     * The 36 drone sizes with no agreed name land here — `5472×3078` is always true, and it beats "?"
     * for anyone who recognises the format. Note the separator becomes a real `×`.
     */
    @Test
    fun `an unnamed size shows its pixels`() {
        assertEquals("1234×5678", VideoFormats.label("1234x5678"))
        assertEquals("5472×3078", VideoFormats.label("5472x3078"))   // a real, unnamed drone size
        assertEquals("2880×1620", VideoFormats.label("2880x1620"))   // the drone's 0x32
        assertFalse("falling back is not the same as being named", VideoFormats.isNamed("5472x3078"))
        assertTrue(VideoFormats.isNamed("3840x2160"))
    }

    /** "?" is reserved for having no size at all — an absent or unreadable field, never a real frame. */
    @Test
    fun `only a missing or unreadable size is a question mark`() {
        assertEquals("?", VideoFormats.label(null))
        assertEquals("?", VideoFormats.label(""))
        assertEquals("?", VideoFormats.label("   "))
        assertEquals("?", VideoFormats.label("garbage"))
        assertEquals("?", VideoFormats.label("1920x"))
        assertEquals("?", VideoFormats.label("0x0"))
        assertEquals("?", VideoFormats.label("1920x1080x720"))
    }

    /**
     * Every size the camera format enum can produce must have a name.
     *
     * This is the coupling that matters: a new code added to `resolutionForIndex` without a matching
     * name here shows "?" in the preview, which is safe but silently unfinished. Fails the moment the
     * two drift, naming the size that needs one.
     */
    @Test
    fun `every camera format code has a name`() {
        val dl = CameraSession({}, 9004, true)
        for (code in 0..0xFF) {
            val px = dl.resolutionForIndex(code) ?: continue
            assertTrue(
                "format code $code (0x%02x) decodes to $px, which has no name in VideoFormats".format(code),
                VideoFormats.isNamed(px),
            )
        }
    }

    /**
     * No camera format may reach the UI as bare pixels.
     *
     * Compared against the **pretty** form, since that is what the fallback emits — testing against the
     * raw `"3840x2160"` would pass for a code that fell through, which is exactly the case this guards.
     */
    @Test
    fun `no camera format falls back to its pixel size`() {
        val dl = CameraSession({}, 9004, true)
        for (code in 0..0xFF) {
            val px = dl.resolutionForIndex(code) ?: continue
            assertFalse(
                "code $code fell through to its pixels — name $px in VideoFormats",
                VideoFormats.label(px) == px.replace("x", "×"),
            )
        }
    }
}
