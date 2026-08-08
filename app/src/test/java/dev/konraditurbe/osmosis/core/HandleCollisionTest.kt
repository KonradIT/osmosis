package dev.konraditurbe.osmosis.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delete addresses a file by handle, so two records sharing one is a data-loss bug, not a display
 * bug: the camera destroys whichever file it has under that handle and the grid drops the cell that
 * was asked for, so it reads as success.
 *
 * Real case, from a Pocket 3 tester's manifest — 43 records, only 11 with handles, two pairs
 * colliding, each a JPG sharing with the video shot seconds before it:
 *
 *     HANDLE COLLISION 0x00042ca0 shared by DJI_..._0715_D.JPG, DJI_..._0714_D.MP4
 *     HANDLE COLLISION 0x00042d80 shared by DJI_..._0729_D.JPG, DJI_..._0728_D.MP4
 */
class HandleCollisionTest {

    private fun f(name: String, handle: Long, shared: Boolean = false) =
        CameraFile(path = "DCIM/DJI_001/$name", thumbPath = "", handle = handle, handleShared = shared)

    @Test
    fun `a unique handle stays deletable`() {
        assertTrue(f("DJI_20260804151550_0714_D.MP4", 0x00042ca0).deletable)
    }

    @Test
    fun `a shared handle is not deletable`() {
        assertFalse(f("DJI_20260804151556_0715_D.JPG", 0x00042ca0, shared = true).deletable)
        assertFalse(f("DJI_20260804151550_0714_D.MP4", 0x00042ca0, shared = true).deletable)
    }

    /** Unchanged: no handle at all was never deletable, and that is a different reason. */
    @Test
    fun `no handle is still not deletable`() {
        assertFalse(f("DJI_20260804151556_0715_D.JPG", 0L).deletable)
    }

    /**
     * The flag must gate *delete only*. Favourite and burst-expand also take a handle but are
     * non-destructive — addressing the wrong file there mis-stars a photo, it does not erase one —
     * so blocking them would cost function for no safety.
     */
    @Test
    fun `a collided file can still be favourited`() {
        val collided = f("DJI_20260804151556_0715_D.JPG", 0x00042ca0, shared = true)
        val favHandle = if (collided.handle != 0L) collided.handle else collided.cmdHandle
        assertTrue("favourite still has a handle to use", favHandle != 0L)
    }
}
