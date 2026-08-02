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

    /**
     * Osmo Action 1 record stride — its list is `[u32 count][u32 totalBytes]` then fixed 65-byte records
     * carrying **unix** seconds at `+0` (not FAT) and the packed index at `+8`. Decoder lands with the
     * `add-osmo-action-support` branch, which has the fixture to test it against; see ROADMAP #6.
     */
    const val ACTION1_STRIDE = 65

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

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or
            (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)
}
