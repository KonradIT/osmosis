package dev.konraditurbe.osmosis.drone

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The drone favourite and delete payloads, pinned byte-for-byte to a Mavic 3 capture
 * (`PCAPdroid_22_Aug_14_38_28`, 2026-08-22): files 603 and 604 favourited and 600/601/602 deleted in
 * DJI Fly, addressed by packed `file_index`. The commands are the camera's own — `0x02/0xbf` and
 * `0x00/0x28` — so the only thing that can drift is the byte layout, which is what this locks.
 */
class DroneWriteCmdTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // Packed indices from the capture: dir 100, files 604/603 and 602/601/600.
    private val f604 = 0x0064025cL
    private val f603 = 0x0064025bL
    private val f602 = 0x0064025aL
    private val f601 = 0x00640259L
    private val f600 = 0x00640258L

    @Test fun `favourite payload matches the wire, counters 1 and 2`() {
        assertEquals("01015c026400010000000001000000", hex(DroneManifest.favouriteCmd(f604, 1, on = true)))
        assertEquals("01015b026400020000000001000000", hex(DroneManifest.favouriteCmd(f603, 2, on = true)))
    }

    @Test fun `unfavourite flips only the on byte`() {
        assertEquals("01015c026400010000000000000000", hex(DroneManifest.favouriteCmd(f604, 1, on = false)))
    }

    @Test fun `three-file delete matches the wire, counter 3`() {
        assertEquals(
            "035a0264005902640058026400030000000003000000",
            hex(DroneManifest.deleteCmd(listOf(f602, f601, f600), 3)),
        )
    }

    /** The drone delete is the camera's minus the trailing `01 01 00 00` storage selector — a
     *  single-file delete must therefore be four bytes shorter and not end in that trailer. */
    @Test fun `single delete has no storage-selector trailer`() {
        val p = hex(DroneManifest.deleteCmd(listOf(f604), 1))
        assertEquals("015c026400010000000001000000", p)
        assert(!p.endsWith("01010000")) { "drone delete must not carry the camera's storage trailer" }
    }
}
