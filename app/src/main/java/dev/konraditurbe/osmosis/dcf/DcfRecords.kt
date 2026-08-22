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
    val starred: Boolean = false,
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
            starred = starred,
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

    /**
     * The Mavic 3's record size, and the fallback when a reply does not declare its own.
     *
     * **Not "the drone stride".** A Mini 3 uses **67**, with byte-for-byte the same fields in the same
     * places — only the trailing unmapped bytes differ in length. Prefer [strideFrom]; this is what to
     * use when a reply arrives with no count/total to derive it from.
     */
    const val DRONE_STRIDE = 94

    /** Bytes of `[u32 count][u32 totalBytes]` that a list reply's `total` counts but records do not. */
    private const val LIST_HEADER = 8

    /**
     * The record size this reply actually uses, from the count and total it declares in chunk 0.
     *
     * `total = 8 + stride * count`, exact on every capture held: a Mavic 3 at 45/4238 gives 94, a
     * Mini 3 at 21/1415 and 1/75 both give 67. The aircraft has been telling us its record size all
     * along and we hardcoded one aircraft's answer, which is why a Mini 3 decoded to nothing.
     *
     * Null when the reply declares nothing usable, or when the arithmetic does not come out whole —
     * a non-integer stride means the assumption is wrong, and guessing would invent files.
     */
    fun strideFrom(count: Int, totalBytes: Int): Int? {
        if (count <= 0 || totalBytes <= LIST_HEADER) return null
        val body = totalBytes - LIST_HEADER
        if (body % count != 0) return null
        return (body / count).takeIf { it in 16..1024 }
    }

    /**
     * Osmo Action 1 record stride — its list is `[u32 count][u32 totalBytes]` then fixed 65-byte records
     * carrying **unix** seconds at `+0` (not FAT) and the packed index at `+8`. Decoder lands with the
     * `support-osmo-action-1` branch, which has the fixture to test it against. Layout:
     * MEDIA_PROTOCOL.md §1 ("Parsed — index-based").
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
     * +19 u8   favourite flag: 1 = starred — the byte right after the constant `4c 03` pair
     * ```
     * The favourite flag is **hardware-verified on the Mavic 3**: three files favourited in DJI Fly
     * (580, 585, 590) read `01` here and the seven around them `00`, videos and stills alike. Other
     * fields past `+14` (resolution/fps) are not yet mapped and are left null rather than guessed. The
     * flag is read wherever the stride reaches it; on a body that puts it elsewhere the worst case is a
     * cosmetic wrong heart, never a wrong file — so it is not gated to the Mavic.
     *
     * Trailing partial records are dropped, as is any record whose index fails [DcfIndex.isPlausible] —
     * a missing middle chunk shifts the stream out of phase, and returning the records we can trust
     * beats emitting plausible-looking garbage.
     */
    fun decodeDrone(blob: ByteArray, stride: Int = DRONE_STRIDE): List<DcfRecord> {
        val out = ArrayList<DcfRecord>()
        if (stride < 16) return out
        for (off in 0..blob.size - stride step stride) {
            val mtime = u32(blob, off)
            val size = u32(blob, off + 4)
            val index = u32(blob, off + 8)
            val duration = u16(blob, off + 12)
            if (!DcfIndex.isPlausible(index) || size == 0L) continue
            val starred = stride > 19 && u8(blob, off + 19) == 1
            out.add(DcfRecord(index, size, duration, DcfIndex.fatToEpoch(mtime), starred))
        }
        return out
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or
            (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)
}
