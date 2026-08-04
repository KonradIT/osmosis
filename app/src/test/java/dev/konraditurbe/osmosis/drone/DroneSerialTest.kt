package dev.konraditurbe.osmosis.drone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The serial has to come out of a `0x51/0x13` beacon on **any** aircraft, because the session-open
 * echoes it back and nothing is served until that completes.
 *
 * Both payloads here are real, taken from tester logs. They are the reason this is found by shape
 * rather than by tag: the serials are the same length and the same alphabet, and only the byte in
 * front of them differs — which is exactly what a tag-anchored search keys on and gets wrong.
 */
class DroneSerialTest {

    private val session = DroneSession({})

    private fun bytes(hex: String) = ByteArray(hex.length / 2) {
        ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte()
    }

    /** Neo 2, 2026-08-04. Note the `24` ahead of the ASCII. */
    private val neo2 = bytes(
        "050000243135383146413651433235425330314348564a51" +
            "0101010000000000040101010000016800000049b400000000b0e344000000"
    )

    /** Mavic 3 — same shape, `11` as the tag. */
    private val mavic3 = bytes("050000113135383146343554383234313230304541315441" + "010101000000")

    @Test
    fun `reads a Neo 2 serial, whose tag is 0x24`() {
        val (serial, tag) = session.parseDroneSerial(neo2)!!
        assertEquals("1581FA6QC25BS01CHVJQ", String(serial, Charsets.US_ASCII))
        assertEquals(0x24, tag)
    }

    @Test
    fun `reads a Mavic 3 serial, whose tag is 0x11`() {
        val (serial, tag) = session.parseDroneSerial(mavic3)!!
        assertEquals("1581F45T8241200EA1TA", String(serial, Charsets.US_ASCII))
        assertEquals(0x11, tag)
    }

    @Test
    fun `the tag is never what identifies the serial`() {
        // The regression this guards: keying on 0x11 silently rejected the Neo 2 and reported it as an
        // aircraft that never beacons. Any tag byte must work.
        for (tag in listOf(0x00, 0x11, 0x24, 0x7F, 0xFF)) {
            val p = bytes("0500%02x".format(tag)) + "1581ABCDEFGH01234567".toByteArray(Charsets.US_ASCII)
            val found = session.parseDroneSerial(p)
            assertEquals("tag 0x%02x".format(tag), "1581ABCDEFGH01234567", String(found!!.first, Charsets.US_ASCII))
        }
    }

    @Test
    fun `short alphanumeric runs are not mistaken for a serial`() {
        // Beacons are full of small ASCII-looking values; only a long run is a serial.
        assertNull(session.parseDroneSerial(bytes("0500241234010203040506")))
        assertNull(session.parseDroneSerial(ByteArray(40)))
    }
}
