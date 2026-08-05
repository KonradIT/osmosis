package dev.konraditurbe.osmosis.drone

import dev.konraditurbe.osmosis.duml.DjiMessage
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The census exists for one job: on an airframe that never sends a `0x51/0x13` beacon, say where the
 * serial *is*. These pin that it finds one outside the Mavic's frame shape.
 */
class DroneFrameCensusTest {

    /** Same rule as DroneSession.parseDroneSerial: longest 12–24 run of [0-9A-Z], plus the byte before. */
    private val finder: (ByteArray) -> Pair<ByteArray, Int>? = { payload ->
        var best: Pair<ByteArray, Int>? = null
        var i = 0
        while (i < payload.size) {
            val c = payload[i].toInt() and 0xFF
            if (!((c in 0x30..0x39) || (c in 0x41..0x5A))) { i++; continue }
            var end = i
            while (end < payload.size) {
                val e = payload[end].toInt() and 0xFF
                if ((e in 0x30..0x39) || (e in 0x41..0x5A)) end++ else break
            }
            val len = end - i
            if (len in 12..24 && len > (best?.first?.size ?: 0)) {
                best = payload.copyOfRange(i, end) to (if (i > 0) payload[i - 1].toInt() and 0xFF else 0x11)
            }
            i = end
        }
        best
    }

    /** A CRC-valid DUML frame the real scanner will accept, wrapped in a plausible 20-byte header. */
    private fun datagram(set: Int, cmd: Int, payload: ByteArray): ByteArray =
        ByteArray(20) + DjiMessage(0xE9EE, 1, 0x40 or (set shl 8) or (cmd shl 16), payload).encode()

    @Test fun `finds a serial carried by a frame that is not a 0x51 beacon`() {
        val census = DroneFrameCensus(finder)
        // A serial-shaped run behind a 0x24 tag, in an 0x0C/0x22 frame — nothing like a Mavic's beacon.
        val body = byteArrayOf(0x00, 0x24) + "1581F5FKD24A00ABCDEF".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00, 0x00)
        census.record(0x02, datagram(0x0C, 0x22, body))

        val report = census.report().joinToString("\n")
        assertTrue(report, report.contains("serial-shaped run in 0c/22"))
        assertTrue(report, report.contains("1581F5FKD24A00ABCDEF"))
        assertTrue(report, report.contains("preceded by 0x24"))
    }

    @Test fun `says so plainly when no payload holds a serial`() {
        val census = DroneFrameCensus(finder)
        census.record(0x01, datagram(0x02, 0x82, ByteArray(24)))
        val report = census.report().joinToString("\n")
        assertTrue(report, report.contains("no serial-shaped run in any frame payload"))
        assertTrue(report, report.contains("02/82×1"))
        assertTrue(report, report.contains("pkt01×1"))
    }

    @Test fun `an aircraft that sends nothing reads as nothing, not as an empty histogram`() {
        assertTrue(DroneFrameCensus(finder).report().single().contains("nothing received at all"))
    }
}
