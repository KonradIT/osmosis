package dev.konraditurbe.osmosis.rsdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `0x1D/0x06` — DJI's `new_camera_status_push`, the frame an Osmo Action 6 reports its mode in.
 *
 * It reports **only** this one: the numeric `0x1D/0x02` struct the older parser expects never arrives,
 * which is why the mode read as unknown on that body until this was wired up. The layout is fixed at
 * 46 bytes, `[01][nameLen][name:21][02][paramLen][param:21]`, and the first bytes here — `01 05` then
 * `VIDEO` — are verbatim what the camera sent.
 */
class NewCameraStatusTest {

    /** Build a frame the way the camera does: fixed type bytes, length-prefixed, zero-padded fields. */
    private fun frame(name: String, param: String): ByteArray {
        val p = ByteArray(46)
        p[0] = 0x01; p[1] = name.length.toByte()
        name.toByteArray(Charsets.US_ASCII).copyInto(p, 2)
        p[23] = 0x02; p[24] = param.length.toByte()
        param.toByteArray(Charsets.US_ASCII).copyInto(p, 25)
        return p
    }

    @Test
    fun `the mode name and its parameter line decode`() {
        val m = RsdkProtocol.parseNewCameraStatus(frame("VIDEO", "4K25 RS"))!!
        assertEquals("VIDEO", m.name)
        assertEquals("4K25 RS", m.param)
        assertEquals("VIDEO · 4K25 RS", m.label)
    }

    /** The exact opening bytes the Action 6 sent, so a change in how the prefix is read breaks here. */
    @Test
    fun `the captured VIDEO frame decodes from its real leading bytes`() {
        val p = ByteArray(46)
        "0105564944454f".chunked(2).forEachIndexed { i, b -> p[i] = b.toInt(16).toByte() }
        p[23] = 0x02
        assertEquals("VIDEO", RsdkProtocol.parseNewCameraStatus(p)!!.name)
    }

    /** A camera in a mode with no parameters shows the name alone, not a dangling separator. */
    @Test
    fun `an empty parameter line leaves the label as just the name`() {
        assertEquals("PHOTO", RsdkProtocol.parseNewCameraStatus(frame("PHOTO", ""))!!.label)
    }

    /**
     * The two type bytes are fixed by the spec, so they are verified rather than skipped: a frame that
     * doesn't carry them is some other layout, and reading strings out of it at these offsets would
     * yield plausible-looking garbage rather than an obvious failure.
     */
    @Test
    fun `a frame without the fixed type bytes is refused`() {
        assertNull(RsdkProtocol.parseNewCameraStatus(frame("VIDEO", "4K25").also { it[0] = 0x03 }))
        assertNull(RsdkProtocol.parseNewCameraStatus(frame("VIDEO", "4K25").also { it[23] = 0x00 }))
        assertNull("a truncated frame must not decode", RsdkProtocol.parseNewCameraStatus(ByteArray(20)))
    }

    /** A length byte longer than its field must clamp, not read into the next one (or off the end). */
    @Test
    fun `an over-long length is clamped to the field`() {
        val p = frame("VIDEO", "4K25").also { it[1] = 99 }
        val m = RsdkProtocol.parseNewCameraStatus(p)!!
        assertEquals("VIDEO", m.name)   // the field is 21 B; the trailing zeros are trimmed
    }
}
