package dev.konraditurbe.osmosis.dcf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DCF addressing scheme on its own, independent of any device's wire format — the layer shared by
 * the drones and the Osmo Action 1 (MEDIA_PROTOCOL.md §1, "Parsed — index-based").
 *
 * These assertions were previously reachable only through a Mavic 3 manifest fixture. Pinning them
 * directly is what lets a second record layout be added without re-deriving the packing rules.
 */
class DcfIndexTest {

    @Test
    fun `pack and unpack round-trip across all three fields`() {
        val i = DcfIndex.pack(storage = 2, dir = 137, file = 4211)
        assertEquals(2, DcfIndex.storage(i))
        assertEquals(137, DcfIndex.dir(i))
        assertEquals(4211, DcfIndex.file(i))
    }

    @Test
    fun `the directory field is 14 bits, so storage does not bleed into it`() {
        // Masking the directory as 16 bits folds the storage bits in: an internal-storage file then
        // reads as directory 16484, fails isPlausible and silently vanishes from the grid.
        val emmc = DcfIndex.pack(DcfIndex.STORAGE_INTERNAL, 100, 1)
        assertEquals(100, DcfIndex.dir(emmc))
        assertEquals(DcfIndex.STORAGE_INTERNAL, DcfIndex.storage(emmc))
        assertTrue("an internal-storage index must survive the sanity check", DcfIndex.isPlausible(emmc))
        assertEquals(16484, ((emmc shr 16) and 0xFFFFL).toInt())   // what the 16-bit mask would give
    }

    @Test
    fun `real Mavic 3 indices decode to the values DJI Fly shows`() {
        // 6554154 is the file DJI Fly downloaded in the reference capture: DCIM/100MEDIA/DJI_0554.
        assertEquals(0, DcfIndex.storage(6554154L))
        assertEquals(100, DcfIndex.dir(6554154L))
        assertEquals(554, DcfIndex.file(6554154L))
        assertEquals(6554154L, DcfIndex.pack(0, 100, 554))
    }

    @Test
    fun `rejects indices DCF could not have produced`() {
        assertFalse(DcfIndex.isPlausible(0L))
        assertFalse("directories start at 100", DcfIndex.isPlausible(DcfIndex.pack(0, 99, 1)))
        assertFalse("directories stop at 999", DcfIndex.isPlausible(DcfIndex.pack(0, 1000, 1)))
        assertFalse("file numbers start at 1", DcfIndex.isPlausible(DcfIndex.pack(0, 100, 0)))
        assertTrue(DcfIndex.isPlausible(DcfIndex.pack(0, 999, 9999)))
    }

    @Test
    fun `rebuilds DJI's on-card naming from the index alone`() {
        val i = DcfIndex.pack(0, 100, 554)
        assertEquals("100MEDIA", DcfIndex.dirName(i))
        assertEquals("DJI_0554.MP4", DcfIndex.fileName(i, "MP4"))
        assertEquals("DCIM/100MEDIA/DJI_0554.JPG", DcfIndex.path(i, "JPG"))
    }

    @Test
    fun `mtime is a FAT date-time, not unix seconds`() {
        // Ground truth: DJI_0555 was recorded 2026-07-12 and its raw field is 0x5CECB9FB. Read as a
        // unix timestamp that lands in 2019 and every file looks seven years old.
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = DcfIndex.fatToEpoch(0x5CECB9FBL) * 1000L
        assertEquals(2026, cal.get(java.util.Calendar.YEAR))
        assertEquals(7, cal.get(java.util.Calendar.MONTH) + 1)
        assertEquals(12, cal.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(23, cal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(15, cal.get(java.util.Calendar.MINUTE))
        assertEquals(54, cal.get(java.util.Calendar.SECOND))   // FAT stores seconds/2
    }

    @Test
    fun `an unreadable FAT value yields zero rather than a bogus date`() {
        assertEquals(0L, DcfIndex.fatToEpoch(0L))
        assertEquals("month 0 is not a date", 0L, DcfIndex.fatToEpoch(0x00000000L or (1L shl 16)))
        assertEquals("month 13 is not a date", 0L, DcfIndex.fatToEpoch((13L shl 5 or 1L) shl 16))
    }

    @Test
    fun `v1 urls carry all three parameters the server expects`() {
        assertEquals(
            "/v1?file_index=6554154&file_subtype=0&file_seg_subindex=0",
            DcfUrls.of(6554154L),
        )
        assertEquals(
            "/v1?file_index=6554154&file_subtype=18&file_seg_subindex=0",
            DcfUrls.of(6554154L, DcfUrls.LRF),
        )
        // A segmented recording selects its part; 0 is the whole file.
        assertEquals(
            "/v1?file_index=6554154&file_subtype=0&file_seg_subindex=3",
            DcfUrls.of(6554154L, DcfUrls.ORG, seg = 3),
        )
    }

    @Test
    fun `a record projects into CameraFile with a synthesised name and the right store`() {
        val rec = DcfRecord(
            fileIndex = DcfIndex.pack(DcfIndex.STORAGE_INTERNAL, 101, 7),
            sizeBytes = 1234L,
            durationSec = 17,
            mtimeEpoch = 1_770_000_000L,
        )
        val f = rec.toCameraFile()
        assertEquals("DCIM/101MEDIA/DJI_0007.MP4", f.path)
        assertEquals(DcfIndex.STORAGE_INTERNAL, f.storage)
        assertTrue(f.isVideo)
        assertTrue(f.isIndexed)
        // duration 0 is the only still-vs-video signal a record carries.
        assertTrue(rec.copy(durationSec = 0).toCameraFile().isImage)
    }
}
