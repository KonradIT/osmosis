package dev.konraditurbe.osmosis.dcf

/**
 * The **DCF** addressing scheme — how DJI hardware that has no filename in its manifest names and
 * locates media instead.
 *
 * DCF (Design rule for Camera File system) is the industry convention behind `DCIM/100MEDIA/DJI_0554.JPG`:
 * a numbered directory (100–999) holding numbered files (1–9999). DJI packs that pair, plus which store
 * it lives on, into the single 32-bit `file_index` its `/v1` endpoint takes.
 *
 * This is **not drone-specific**. Two very different devices address media this way:
 *  - **drones** (Mavic 3 family) — 94-byte manifest records, see [DcfRecords.decodeDrone];
 *  - the **Osmo Action 1** — 65-byte index records over the same `/v1` endpoint, see
 *    [DcfRecords.ACTION1_STRIDE] and MEDIA_PROTOCOL.md §1 ("Parsed — index-based").
 *
 * Everything else in the app addresses media by *path* over `/v2?storage=N&path=…` instead. Keeping the
 * two schemes in separate packages is the point: a path-based camera never touches this file.
 *
 * ### Packed `file_index`
 * ```
 * bits 31:30  storage id   0 SD · 1 internal eMMC · 2 NVMe SSD · 3 reserved
 * bits 29:16  DCF directory (14 bits)   100 -> 100MEDIA
 * bits 15:0   DCF file number           554 -> DJI_0554
 * ```
 * The directory field is **14 bits, not 16**. Masking it as 16 folds the storage bits into it, so every
 * file on internal storage reads as directory 16484, fails [isPlausible] and silently vanishes from the
 * grid — which is exactly how it failed the first time.
 */
object DcfIndex {

    const val STORAGE_SD = 0
    const val STORAGE_INTERNAL = 1
    const val STORAGE_SSD = 2

    fun pack(storage: Int, dir: Int, file: Int): Long =
        ((storage.toLong() and 0x3L) shl 30) or ((dir.toLong() and 0x3FFFL) shl 16) or (file.toLong() and 0xFFFFL)

    fun storage(index: Long): Int = ((index shr 30) and 0x3L).toInt()
    fun dir(index: Long): Int = ((index shr 16) and 0x3FFFL).toInt()
    fun file(index: Long): Int = (index and 0xFFFFL).toInt()

    /**
     * Whether [index] decodes to a directory/file pair DCF could actually have produced.
     *
     * The guard matters because manifest records arrive chunked: a dropped middle chunk shifts the byte
     * stream out of phase and every record after it decodes to garbage. Rejecting implausible indices is
     * what stops that garbage reaching the grid as files with 1970s dates and absurd sizes.
     */
    fun isPlausible(index: Long): Boolean =
        index != 0L && dir(index) in 100..999 && file(index) in 1..9999

    /** `100MEDIA` — the on-card directory name for [index]'s directory number. */
    fun dirName(index: Long): String = "%dMEDIA".format(dir(index))

    /** `DJI_0554.MP4` — DJI's on-card naming convention, rebuilt from the index (no name is transmitted). */
    fun fileName(index: Long, ext: String): String = "DJI_%04d.%s".format(file(index), ext)

    /** `DCIM/100MEDIA/DJI_0554.MP4`. Display and download naming only — it is **not** a URL these
     *  devices answer; see [DcfUrls]. */
    fun path(index: Long, ext: String): String = "DCIM/${dirName(index)}/${fileName(index, ext)}"

    /**
     * Convert a **FAT/DOS packed date+time** to unix seconds (0 if absurd).
     *
     * Read as a unix timestamp it lands in 2019 and every file looks seven years old. It's the classic
     * on-disk FAT encoding instead — which makes sense for something read straight off the card:
     * ```
     * high 16 bits (date): year-1980 << 9 | month << 5 | day
     * low  16 bits (time): hour << 11 | minute << 5 | seconds/2   (2-second resolution)
     * ```
     * Ground truth: `0x5CECB9FB` → 2026-07-12 23:15:54, matching the date DJI Fly shows for DJI_0555.
     * FAT stores wall-clock with no zone, so it's interpreted in the phone's local time — the same
     * assumption the camera path makes for its filename timestamps.
     *
     * Not universal across DCF devices: the Osmo Action 1's records carry plain unix seconds in the same
     * position, so callers pick the encoding per record layout rather than assuming FAT.
     */
    fun fatToEpoch(v: Long): Long {
        if (v == 0L) return 0L
        val date = ((v shr 16) and 0xFFFF).toInt()
        val time = (v and 0xFFFF).toInt()
        val year = 1980 + (date shr 9)
        val month = (date shr 5) and 0x0F
        val day = date and 0x1F
        if (month !in 1..12 || day !in 1..31 || year !in 1980..2200) return 0L
        val cal = java.util.Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, day, time shr 11, (time shr 5) and 0x3F, (time and 0x1F) * 2)
        return cal.timeInMillis / 1000L
    }
}
