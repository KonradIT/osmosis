package dev.konraditurbe.osmosis.duml

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows

// Vendored from https://github.com/dimadesu/dji-remote
// MIT License.
class DjiMessageTest {
    @Test
    fun encodeDecodeRoundtrip() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val msg = DjiMessage(target = 0x0702, id = 0x1234, type = 0x112233, payload = payload)
        val decoded = DjiMessage.fromBytes(msg.encode())
        assertEquals(msg.target, decoded.target)
        assertEquals(msg.id, decoded.id)
        assertEquals(msg.type, decoded.type)
        assertEquals(msg.payload.toList(), decoded.payload.toList())
    }

    @Test
    fun typeAccessorsDecomposeCorrectly() {
        // type 0x450740 (LE bytes 40 07 45) => flags=0x40, cmdSet=0x07, cmdId=0x45
        val msg = DjiMessage(target = 0x0702, id = 1, type = 0x450740, payload = ByteArray(0))
        assertEquals(0x40, msg.flags)
        assertEquals(0x07, msg.cmdSet)
        assertEquals(0x45, msg.cmdId)
    }

    @Test
    fun headerCrcMismatchThrows() {
        val msg = DjiMessage(target = 1, id = 2, type = 3, payload = byteArrayOf(0x01))
        val encoded = msg.encode()
        encoded[2] = 0x05 // corrupt version/len-hi byte
        assertThrows(IllegalArgumentException::class.java) { DjiMessage.fromBytes(encoded) }
    }
}
