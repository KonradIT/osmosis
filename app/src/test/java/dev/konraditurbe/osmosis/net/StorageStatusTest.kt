package dev.konraditurbe.osmosis.net

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
        DatalinkClient({}, 9004, true).applyStatusFrameForTest(0x02, 0xDC, hex(payload))

    // Action 6 with a card. Its own screen read "107.2 / 118.9 GB" for the card.
    private val action6 = "001202000001b9db0100b4ac0100000000002a2b000001011dc8000072c50000"
    // Action 5 Pro with a card.
    private val action5 = "00120200000166e80000647f000000000000de0a0000010154bf000075b20000"
    // Xtra Edge Pro (an Action 5 Pro rebadge) with NO card in.
    private val xtraNoCard = "11120200000200000000000000000000000000000000010154bf000003b50000"

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
}
