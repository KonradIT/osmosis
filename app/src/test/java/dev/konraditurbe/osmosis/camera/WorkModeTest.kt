package dev.konraditurbe.osmosis.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkModeTest {

    @Test
    fun `SetMode body is the wire mode as one byte`() {
        assertArrayEquals(byteArrayOf(0x03), WorkMode.setPayload(WorkMode.TRANSCODE))
        assertArrayEquals(byteArrayOf(0x07), WorkMode.setPayload(WorkMode.NEW_PLAYBACK))
    }

    @Test
    fun `GetMode reply is status then mode`() {
        assertEquals(WorkMode.RECORD, WorkMode.parseGetReply(byteArrayOf(0x00, 0x01)))
        assertEquals(WorkMode.TRANSCODE, WorkMode.parseGetReply(byteArrayOf(0x00, 0x03, 0x00)))
    }

    @Test
    fun `a refused or short GetMode reply is no answer, not mode 0`() {
        // Reading byte 0 of a refusal as the mode would report TAKE_PHOTO for an 0xe0.
        assertNull(WorkMode.parseGetReply(byteArrayOf(0xE0.toByte(), 0x01)))
        assertNull(WorkMode.parseGetReply(byteArrayOf(0x00)))
        assertNull(WorkMode.parseGetReply(null))
    }

    @Test
    fun `status push byte 4 is the work mode`() {
        val s = CameraSession({}, 9004, true)
        // 13-byte 0x02/0x80 body: flags word, mode at byte 4, then total/free MiB at 5 and 9.
        val push = ByteArray(13).also { it[4] = WorkMode.TRANSCODE.toByte() }
        s.applyStatusFrameForTest(0x02, 0x80, push)
        assertEquals(WorkMode.TRANSCODE, s.workModeReported)
    }
}
