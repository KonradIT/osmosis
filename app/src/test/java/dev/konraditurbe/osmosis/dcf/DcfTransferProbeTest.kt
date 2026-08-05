package dev.konraditurbe.osmosis.dcf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The probe frames, pinned. These go to a tester's camera unattended, so the two properties that
 * matter are that a query names the right file and that a release is addressed to the transfer we
 * actually opened — a release aimed at the wrong kind or seq leaks the slot it was meant to free.
 */
class DcfTransferProbeTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun `a query names its transfer kind, its seq and its file`() {
        // 0x00640078 = the index from the tester's Action 1 (100MEDIA / DJI_0120).
        val q = DcfTransferProbe.query(base = 0x20, seq = 0x61, fileIndex = 0x00640078L)
        assertEquals("4a20141061000000000078006400000000000000", hex(q))
        assertEquals(0x20, q[1].toInt())            // base + QUERY
    }

    @Test fun `a release is addressed to the kind and seq it frees`() {
        val r = DcfTransferProbe.release(base = 0x20, seq = 0x61)
        assertEquals(0x24, r[1].toInt())            // base + RELEASE
        assertEquals(0x61, r[4].toInt())
    }

    @Test fun `the list kind is never probed — it is known to answer and would prove nothing`() {
        assert(0x00 !in DcfTransferProbe.BASES)
        assert(0x20 in DcfTransferProbe.BASES)      // the drone's thumbnail kind, the attested guess
    }

    @Test fun `family matching accepts our own kind and rejects everything else`() {
        val state = byteArrayOf(0x4A, 0x23, 0x0E, 0x10, 0x61, 0x00, 0, 0, 0, 0)
        assertEquals(DcfTransferProbe.STATE, DcfTransferProbe.familyMember(state, 0x20, 0x61))
        assertEquals("STATE", DcfTransferProbe.kindName(DcfTransferProbe.familyMember(state, 0x20, 0x61)!!))
        // Another transfer's seq, another kind's base, and a non-0x4a frame all have to miss — the
        // list is streaming on this same session, and counting its frames as a hit would be a lie.
        assertNull(DcfTransferProbe.familyMember(state, 0x20, 0x62))
        assertNull(DcfTransferProbe.familyMember(state, 0x40, 0x61))
        assertNull(DcfTransferProbe.familyMember(byteArrayOf(0x55, 0x23, 0, 0, 0x61, 0), 0x20, 0x61))
    }
}
