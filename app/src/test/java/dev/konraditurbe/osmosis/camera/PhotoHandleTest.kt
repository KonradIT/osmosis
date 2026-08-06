package dev.konraditurbe.osmosis.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A photo must never be given another file's delete handle.
 *
 * `0x00/0x28` is irreversible and takes a handle, not a path — so a handle read out of the wrong
 * record doesn't fail, it destroys the wrong file. Manifests captured off DJI Mimo talking to a real
 * Nano and a real Xtra Edge Pro; the expected handle for each record is the `u32-LE` eight bytes ahead
 * of that record's own `19 06` marker.
 */
class PhotoHandleTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("manifests/$name.bin")!!.readBytes()

    /** Every `[03|00] [ff|fe] 19 06` marker in the blob, with the handle eight bytes before it. */
    private fun markerHandles(b: ByteArray): List<Pair<Int, Long>> {
        val out = ArrayList<Pair<Int, Long>>()
        for (i in 8 until b.size - 3) {
            val kind = b[i].toInt() and 0xFF
            val star = b[i + 1].toInt() and 0xFF
            if ((kind == 0x03 || kind == 0x00) && (star == 0xFF || star == 0xFE) &&
                (b[i + 2].toInt() and 0xFF) == 0x19 && (b[i + 3].toInt() and 0xFF) == 0x06
            ) {
                var h = 0L
                for (k in 0 until 4) h = h or ((b[i - 8 + k].toLong() and 0xFF) shl (8 * k))
                out.add(i to h)
            }
        }
        return out
    }

    private fun check(camera: String, port: Int, poke: Boolean) {
        val blob = fixture(camera)
        val files = CameraSession({}, port, poke).decodeCompositeForTest(blob)
        val valid = markerHandles(blob).map { it.second }.toSet()
        val photos = files.filter { it.isImage }
        assertEquals("fixture should hold photos", true, photos.isNotEmpty())
        assertEquals("fixture should hold videos too", true, files.any { it.isVideo })

        // A handle of 0 means "not deletable", which is safe. Anything else must be a real handle
        // belonging to some record — and specifically to THIS one.
        val wrong = photos.filter { it.handle != 0L && it.handle !in valid }
        assertEquals("$camera: photos given a handle that is in no record at all: " +
            wrong.joinToString { "${it.name}=0x%08x".format(it.handle) }, 0, wrong.size)

        // The real guarantee: no two files may ever share a handle. A duplicate means one of them is
        // pointing at the other's record, and deleting it destroys the wrong file.
        val shared = files.filter { p ->
            p.handle != 0L && files.any { it !== p && it.handle == p.handle }
        }
        assertEquals("$camera: files sharing a handle — deleting one would destroy the other: " +
            shared.joinToString { "${it.name}=0x%08x".format(it.handle) }, 0, shared.size)

        // And a photo must actually get one now; they were assumed to have none.
        assertEquals("$camera: photos should carry a delete handle", 0,
            photos.count { it.handle == 0L })
    }

    @Test fun `nano photos never borrow another file's handle`() = check("nano_delete", 9004, true)

    @Test fun `xtra photos never borrow another file's handle`() = check("xtra_delete", 10004, false)
}
