package dev.konraditurbe.osmosis.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Per-file command payloads, pinned to bytes captured off DJI Mimo talking to a Nano.
 *
 * Both commands carry a per-request counter that has to advance. With a single sample it is
 * indistinguishable from the handle count — which is exactly how the delete one came to be written as
 * the count, making the first delete of a session correct and every one after it a repeat.
 */
class FileOpPayloadTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val client = DatalinkClient({}, 9004, true)

    @Test
    fun `delete matches Mimo byte for byte, and the counter advances`() {
        // Two consecutive deletes in one Mimo session. The count stays 01; the field after the handle
        // goes 01 then 02.
        assertEquals("010052104001000000000100000001010000",
            hex(client.deletePayload(listOf(0x40105200L), counter = 1)))
        assertEquals("014052104002000000000100000001010000",
            hex(client.deletePayload(listOf(0x40105240L), counter = 2)))
    }

    @Test
    fun `the counter is not the handle count`() {
        // The regression this guards: sending the count in the counter's place. Identical at counter=1,
        // which is why it survived so long.
        val first = hex(client.deletePayload(listOf(0x40105200L), counter = 1))
        val second = hex(client.deletePayload(listOf(0x40105200L), counter = 2))
        assertEquals("same handle, different request", false, first == second)
    }
}
