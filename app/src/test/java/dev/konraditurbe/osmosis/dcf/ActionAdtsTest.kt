package dev.konraditurbe.osmosis.dcf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionAdtsTest {
    @Test fun acceptsTheHeaderEmittedByRetailFirmware() {
        // V01.10.00.40, 0x79ac88, executed with a 2,400-byte AAC sample.
        val header = byteArrayOf(-1, -15, 0x4c, 0x81.toByte(), 0x2c, -1, -4)
        val record = header + ByteArray(2400)
        assertTrue(ActionAdts.isFrame(record))
        assertFalse(ActionAdts.isFrame(record.copyOf(record.size - 1)))
        assertFalse(ActionAdts.isFrame(record + byteArrayOf(0)))
        assertFalse(ActionAdts.isFrame(record.copyOf().also { it[2] = 0x50 }))
        assertFalse(ActionAdts.isFrame(record.copyOf().also { it[6] = -1 }))
        assertFalse(ActionAdts.isFrame(header))
    }
}
