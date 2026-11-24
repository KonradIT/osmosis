package dev.konraditurbe.osmosis.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `0x02/0x10` / `0x02/0x11` and the mode byte in the `0x02/0x80` push. The wire values are DJI Fly's
 * `DataCameraGetMode.MODE`; nothing here has been checked against an Action 1 yet. These pin the shapes,
 * so a tester log can be read against what we know we sent.
 */
class WorkModeTest {

    @Test
    fun `SetMode body is the wire mode as one byte`() {
        assertArrayEquals(byteArrayOf(0x03), WorkMode.setPayload(WorkMode.TRANSCODE))
        assertArrayEquals(byteArrayOf(0x07), WorkMode.setPayload(WorkMode.NEW_PLAYBACK))
    }

    @Test
    fun `TRANSCODE by its wire value is tried first, and no mode that could cut the Wi-Fi is tried`() {
        assertEquals(WorkMode.TRANSCODE, WorkMode.HTTP_CANDIDATES.first())
        for (unsafe in listOf(WorkMode.TUNING, WorkMode.SAVE_POWER, WorkMode.BROADCAST))
            assert(unsafe !in WorkMode.HTTP_CANDIDATES) { "${WorkMode.name(unsafe)} must not be swept" }
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
