package dev.konraditurbe.osmosis.dcf

import dev.konraditurbe.osmosis.core.CameraFile

/**
 * One media item as a DCF-indexed device reports it — the fields that are actually in a manifest
 * record, before any naming or URL is invented on top.
 *
 * There is deliberately **no path** here: these devices transmit no filename at all. The on-card name
 * is reconstructed from [fileIndex] in [toCameraFile], purely for display and for what the file is
 * saved as.
 */
data class DcfRecord(
    val fileIndex: Long,
    val sizeBytes: Long,
    val durationSec: Int,
    val mtimeEpoch: Long,
) {
    val storage: Int get() = DcfIndex.storage(fileIndex)

    /**
     * Project into the app-wide [CameraFile] model.
     *
     * `durationSec == 0` is the only still-vs-video signal a record carries; it held for every file in
     * the Mavic 3 capture, and the two we could cross-check came back from HTTP as `image/jpeg`.
     */
    fun toCameraFile(): CameraFile {
        val path = DcfIndex.path(fileIndex, if (durationSec > 0) "MP4" else "JPG")
        return CameraFile(
            path = path,
            thumbPath = path,
            fileIndex = fileIndex,
            sizeBytes = sizeBytes,
            durationSec = durationSec,
            mtimeEpoch = mtimeEpoch,
            storage = storage,
        )
    }
}

/**
 * Decoders for the fixed-stride record arrays DCF-indexed devices return.
 *
 * Unlike the path-based cameras — which answer with DJI's *CompositePack* TLV — these devices answer
 * with a flat array of fixed-size records. The stride and field offsets differ per device family and
 * the layouts share only [DcfRecord.fileIndex], so each family gets its own explicit decoder rather
 * than one parameterised reader that would obscure both.
 */
object DcfRecords {

    /** Drone (Mavic 3 family) record stride. */
    const val DRONE_STRIDE = 94

    /** Osmo Action 1 record stride. See [decodeAction1]. */
    const val ACTION1_STRIDE = 65

    /** Header on an Action 1 list: `[u32 count][u32 totalBytes]`, where totalBytes covers the header. */
    private const val ACTION1_HEADER = 8

    /**
     * Decode a drone's reassembled record bytes.
     *
     * ```
     * +0  u32  mtime, FAT/DOS packed — NOT unix seconds; see [DcfIndex.fatToEpoch]
     * +4  u32  file size in bytes    — verified byte-exact against two HTTP Content-Lengths
     * +8  u32  file_index, packed    — see [DcfIndex]
     * +12 u16  duration in seconds; 0 => still photo
     * ```
     * Fields past `+14` are not yet mapped (resolution/fps live in there somewhere) and are left null
     * rather than guessed.
     *
     * Trailing partial records are dropped, as is any record whose index fails [DcfIndex.isPlausible] —
     * a missing middle chunk shifts the stream out of phase, and returning the records we can trust
     * beats emitting plausible-looking garbage.
     */
    fun decodeDrone(blob: ByteArray): List<DcfRecord> {
        val out = ArrayList<DcfRecord>()
        for (off in 0..blob.size - DRONE_STRIDE step DRONE_STRIDE) {
            val mtime = u32(blob, off)
            val size = u32(blob, off + 4)
            val index = u32(blob, off + 8)
            val duration = u16(blob, off + 12)
            if (!DcfIndex.isPlausible(index) || size == 0L) continue
            out.add(DcfRecord(index, size, duration, DcfIndex.fatToEpoch(mtime)))
        }
        return out
    }

    /**
     * Decode an Osmo Action 1 list: `[u32 count][u32 totalBytes]` then fixed 65-byte records.
     *
     * **The first 14 bytes are laid out exactly like [decodeDrone]'s** — same fields, same offsets,
     * despite the different stride and a decade between the two firmwares:
     * ```
     * +0  u32  mtime, **unix seconds** — unlike the drone, which packs FAT/DOS here
     * +4  u32  file size in bytes
     * +8  u32  file_index, packed     — see [DcfIndex]
     * +12 u16  duration, whole seconds (truncated)
     * +19 u32  Amba video UUID        — matches `DjiMovDmx` in the camera's own log; not surfaced
     * +38 u32  duration in **milliseconds**
     * ```
     * Fields past `+42` are unmapped.
     *
     * `+12` and `+38` identify each other: across all seven fixture records the millisecond value is
     * exactly the second value × 1000 plus a sub-second remainder (117 ↔ 117550, 174 ↔ 174941,
     * 306 ↔ 306606). That also retires an older guess that `+38` was a file size — the "0.6 MB" once
     * read there is 667 **ms**.
     *
     * **Milliseconds are what decides still-vs-video, not seconds.** The fixture contains a 0.667 s
     * clip whose `+12` is therefore `0`, which the drone's `durationSec == 0` rule would call a photo;
     * the camera's own log lists its UUID among the `DjiMovDmx` videos. Sub-second durations are
     * rounded up to 1 s so that rule still holds downstream.
     *
     * Two fields were previously read wrong, and both mattered. `+12` was taken for the DCF *file
     * number* and used to synthesise filenames, so a clip whose real name is `DJI_0593.MP4` appeared as
     * `…_0117_…`; and `+10` was read as the DCF directory, which is really the **high half of the u32
     * index** — the same mis-slicing that hides internal storage on a drone. The DCF directory and file
     * number come out of [DcfIndex], never off raw offsets.
     *
     * Returns an empty list when the bytes are not this format, so a caller can fall through to the
     * path-based decoder.
     */
    fun decodeAction1(blob: ByteArray): List<DcfRecord> {
        if (blob.size < ACTION1_HEADER + ACTION1_STRIDE) return emptyList()
        val count = u32(blob, 0).toInt()
        val total = u32(blob, 4).toInt()
        // The header is self-describing: reject anything that doesn't account for itself exactly.
        if (count !in 1..100_000 || total != blob.size) return emptyList()
        if (ACTION1_HEADER + count * ACTION1_STRIDE != total) return emptyList()

        val out = ArrayList<DcfRecord>(count)
        for (k in 0 until count) {
            val off = ACTION1_HEADER + k * ACTION1_STRIDE
            val index = u32(blob, off + 8)
            if (!DcfIndex.isPlausible(index)) continue
            val ms = u32(blob, off + 38)
            // Round a sub-second clip up rather than to zero: zero means "still" everywhere downstream.
            val seconds = if (ms in 1..999) 1 else (ms / 1000).toInt()
            out.add(DcfRecord(index, u32(blob, off + 4), seconds, u32(blob, off)))
        }
        return out
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or
            (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)
}
