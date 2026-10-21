package dev.konraditurbe.osmosis.duml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the RTMP livestream frames against github.com/xaionaro-go/djictl (CC0). The reference is
 * djictl's own packet comment in GetMessagePayloadConfigureLiveStream:
 *
 *   hdr:     55 42 04 b0 0208 b3bb 400878
 *   payload: 00 32 00 0a 7017 0200 03 000000 2700 + "rtmp://192.168.0.131:1946/test/stream0/"
 *
 * Two things to check there every time: the message id is written **big-endian** (b3 bb), unlike
 * DjiMessage's little-endian write, and the device byte is `0x32` in the capture while djictl's
 * code sends `0x2A` (Action 5 Pro: `0x2E`). We ship the code value.
 */
class OsmoCommandsRtmpTest {

    private fun target(f: ByteArray) = (f[4].toInt() and 0xFF) or ((f[5].toInt() and 0xFF) shl 8)
    // Read big-endian, i.e. as djictl writes it — DjiMessage's LE write already swapped the bytes.
    private fun id(f: ByteArray) = ((f[6].toInt() and 0xFF) shl 8) or (f[7].toInt() and 0xFF)
    private fun flags(f: ByteArray) = f[8].toInt() and 0xFF
    private fun cmdSet(f: ByteArray) = f[9].toInt() and 0xFF
    private fun cmdId(f: ByteArray) = f[10].toInt() and 0xFF
    private fun payload(f: ByteArray) = f.copyOfRange(11, f.size - 2)

    private fun bytes(f: ByteArray) = f.map { it.toInt() and 0xFF }

    /** The URL from djictl's capture, so the whole frame can be compared byte for byte. */
    private val goldenUrl = "rtmp://192.168.0.131:1946/test/stream0/"

    @Test
    fun `configure stream reproduces djictl's captured frame`() {
        val f = OsmoCommands.configureStream(
            resolution = 0x0A, bitrateKbps = 6000, fps = 0x03, url = goldenUrl,
        )
        assertEquals(0x42, f[1].toInt() and 0xFF)          // length byte: 66 total
        assertEquals(0x0802, target(f))                    // App -> VideoTransmission (DM368)
        assertEquals("id is big-endian on the wire, not DjiMessage's LE", 0xB3BB, id(f))
        assertEquals(0x40, flags(f)); assertEquals(0x08, cmdSet(f)); assertEquals(0x78, cmdId(f))

        // djictl's payload with its capture's `0x32` device byte replaced by the code's `0x2A`.
        val expected = mutableListOf(0x00, 0x2A, 0x00, 0x0A, 0x70, 0x17, 0x02, 0x00, 0x03,
            0x00, 0x00, 0x00, 0x27, 0x00) + goldenUrl.map { it.code }
        assertEquals(expected, bytes(payload(f)))
    }

    @Test
    fun `configure stream uses the Action 5 Pro device byte`() {
        val f = OsmoCommands.configureStream(0x0A, 6000, 0x03, goldenUrl, action5Pro = true)
        assertEquals(0x2E, bytes(payload(f))[1])
    }

    @Test
    fun `resolution and fps tables match djictl's byte tables`() {
        assertEquals(mapOf("480p" to 0x47, "720p" to 0x04, "1080p" to 0x0A),
            OsmoCommands.STREAM_RESOLUTIONS)
        assertEquals(0x03, OsmoCommands.STREAM_FPS_30)
        assertEquals(mapOf("Off" to 0x00, "RockSteady" to 0x01, "HorizonSteady" to 0x02,
            "RockSteady+" to 0x03, "HorizonBalancing" to 0x04), OsmoCommands.STREAM_EIS)
    }

    @Test
    fun `bitrate is little-endian 16 bit`() {
        val p = bytes(payload(OsmoCommands.configureStream(0x04, 4000, 0x02, "rtmp://h/l")))
        assertEquals(listOf(0xA0, 0x0F), p.subList(4, 6))  // 4000 = 0x0FA0, little-endian
        assertEquals(0x04, p[3])                            // 720p
        assertEquals(0x02, p[6])                            // djictl's fixed 0x02 marker
        assertEquals(0x02, p[8])                            // 25fps
    }

    @Test
    fun `url longer than the frame length field is rejected`() {
        val max = OsmoCommands.MAX_STREAM_URL_BYTES
        val p = payload(OsmoCommands.configureStream(0x0A, 6000, 0x03, "x".repeat(max)))
        assertEquals(max + 14, p.size)                     // 12 fixed + 2 url length + url
        assertEquals(max and 0xFF, p[12].toInt() and 0xFF) // url length, little-endian
        assertEquals(max shr 8, p[13].toInt() and 0xFF)
        assertThrows(IllegalArgumentException::class.java) {
            OsmoCommands.configureStream(0x0A, 6000, 0x03, "x".repeat(max + 1))
        }
    }

    @Test
    fun `prepare to live stream stages carry djictl's payloads and ids`() {
        val s1 = OsmoCommands.prepareLiveStreamStage1()
        assertEquals(0x0802, target(s1)); assertEquals(0xFEAB, id(s1))
        assertEquals(0x02, cmdSet(s1)); assertEquals(0xE1, cmdId(s1))
        assertEquals(listOf(0x1A), bytes(payload(s1)))

        val s2 = OsmoCommands.prepareLiveStreamStage2()
        assertEquals(0x0802, target(s2)); assertEquals(0xFFAB, id(s2))
        assertEquals(0x02, cmdSet(s2)); assertEquals(0x8E, cmdId(s2))
        assertEquals(listOf(0x00, 0x01, 0x1C, 0x00), bytes(payload(s2)))
    }

    @Test
    fun `start and stop differ only in the trailing byte`() {
        val start = OsmoCommands.startStream(); val stop = OsmoCommands.stopStream()
        assertEquals(listOf(0x01, 0x01, 0x1A, 0x00, 0x01, 0x01), bytes(payload(start)))
        assertEquals(listOf(0x01, 0x01, 0x1A, 0x00, 0x01, 0x02), bytes(payload(stop)))
        assertEquals(0x0802, target(start)); assertEquals(0xB4BB, id(start))
        assertEquals(0xB5BB, id(stop))
        assertEquals(0x02, cmdSet(start)); assertEquals(0x8E, cmdId(start))
    }

    @Test
    fun `image stabilization targets the camera and is model specific`() {
        val other = OsmoCommands.setImageStabilization(0x03)
        assertEquals(0x0102, target(other))
        assertEquals(listOf(0x01, 0x01, 0x08, 0x00, 0x01, 0x03), bytes(payload(other)))

        val a5p = OsmoCommands.setImageStabilization(0x00, action5Pro = true)
        assertEquals(listOf(0x01, 0x01, 0x1A, 0x00, 0x01, 0x00), bytes(payload(a5p)))
        assertEquals(0x02, cmdSet(a5p)); assertEquals(0x8E, cmdId(a5p))
    }
}
