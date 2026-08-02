package dev.konraditurbe.osmosis.net

import dev.konraditurbe.osmosis.camera.CameraSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `0x02/0xDC` push carries **both** stores, as two `[total][free]` u32-LE MiB blocks: the card at
 * `@6/@10` and the built-in at `@24/@28`. These are verbatim frames off three cameras, and they pin
 * the layout three independent ways — see the assertions below.
 *
 * They also prove byte 0 is *not* an "SD inserted" flag: it reads `0x11` on the camera with **no**
 * card and `0x00` on the two **with** one. Reading it as a flag (as we first did) reported presence
 * exactly backwards, which is why a card's capacity got rendered under an "Internal" label.
 */
class StorageStatusTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun status(payload: String) =
        CameraSession({}, 9004, true).applyStatusFrameForTest(0x02, 0xDC, hex(payload))

    // Action 6 with a card. Its own screen read "107.2 / 118.9 GB" for the card.
    private val action6 = "001202000001b9db0100b4ac0100000000002a2b000001011dc8000072c50000"
    // Action 5 Pro with a card.
    private val action5 = "00120200000166e80000647f000000000000de0a0000010154bf000075b20000"
    // Xtra Edge Pro (an Action 5 Pro rebadge) with NO card in.
    private val xtraNoCard = "11120200000200000000000000000000000000000000010154bf000003b50000"
    // Osmo 360 with a 512 GB card — a distinct two-store body (large card + ~105 GB built-in).
    private val osmo360 = "0012020000011f7207008c71070000000000c66b000001015fa60100e2a50100"
    // Action 5 Pro again but with a fresh 128 GB card — same 48980 MiB built-in as above.
    private val action5BigCard = "001202000001b9db01003ddb010000000000ed270000010154bf000024bf0000"
    // Pocket 3 — SINGLE store (its microSD), so a shorter 22-byte frame (byte 2 = 01, one block, no
    // built-in). A real capture with a 128 GB card in.
    private val pocket3 = "001201000001b9db0100230a0100000000004e180000"

    @Test
    fun `card block matches the capacity the camera shows on its own screen`() {
        val s = status(action6)
        assertEquals(121_785, s.sdTotalMb)   // 118.9 GB
        assertEquals(109_748, s.sdFreeMb)    // 107.2 GB
        assertTrue(s.sdInserted)
    }

    @Test
    fun `a camera with no card reports zero capacity for it, not a flag`() {
        val s = status(xtraNoCard)
        assertEquals(0, s.sdTotalMb)
        assertFalse("byte 0 is 0x11 here — presence must come from capacity, not that byte", s.sdInserted)
        assertEquals(48_980, s.internalTotalMb) // built-in still reported
    }

    /** Same silicon (the Xtra is a rebadged Action 5 Pro) must report the same built-in capacity. */
    @Test
    fun `built-in block agrees across a camera and its rebadge`() {
        assertEquals(48_980, status(action5).internalTotalMb)
        assertEquals(48_980, status(xtraNoCard).internalTotalMb)
    }

    @Test
    fun `both stores decode independently when a card is in`() {
        val s = status(action5)
        assertEquals(59_494, s.sdTotalMb)
        assertEquals(48_980, s.internalTotalMb)
        assertTrue(s.sdInserted)
    }

    /** A single-store body (Pocket 3 = microSD only) sends a shorter **22-byte** frame with just the
     *  first block. It must still decode the card — the old `p.size >= 32` gate dropped it entirely,
     *  so the Pocket 3 never reported storage — and report NO built-in (0), not a garbage/OOB value. */
    @Test
    fun `single-store 22-byte frame decodes the card and reports no built-in`() {
        val s = status(pocket3)
        assertEquals(121_785, s.sdTotalMb)   // 128 GB card
        assertEquals(68_131, s.sdFreeMb)
        assertTrue(s.sdInserted)
        assertEquals("single-store frame = no built-in", 0, s.internalTotalMb)
    }

    @Test
    fun `Osmo 360 two-store frame decodes a large card and built-in`() {
        val s = status(osmo360)
        assertEquals(487_967, s.sdTotalMb)     // ~512 GB card
        assertEquals(108_127, s.internalTotalMb) // ~105 GB built-in
        assertTrue(s.sdInserted)
    }

    /** The 48980 MiB Action-5-Pro built-in is a per-body constant — it reads the same whether the card
     *  is 64 GB (action5) or 128 GB (action5BigCard). */
    @Test
    fun `built-in capacity is independent of the card size`() {
        assertEquals(48_980, status(action5).internalTotalMb)
        val big = status(action5BigCard)
        assertEquals(48_980, big.internalTotalMb)
        assertEquals(121_785, big.sdTotalMb)
    }
}
