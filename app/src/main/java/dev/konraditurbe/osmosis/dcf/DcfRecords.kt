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
    /** "3840x2160" etc. from the record's resolution code, or null for an unmapped code / a still. */
    val resolution: String? = null,
    /** Frames per second from the record's fps code, or 0 when unmapped / a still. */
    val fps: Int = 0,
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
            resolution = resolution,
            resLabel = if (fps > 0) "${fps}fps" else null,
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
     * +14 u8   fps code  — see [droneFps]  (a still reads 0 here → no rate)
     * +15 u8   resolution code — see [droneResolution]
     * +19 u8   favourite flag: 1 = starred — the byte right after the constant `4c 03` pair
     * ```
     * The res/fps codes are their own set, distinct from the Osmo cameras' CompositePack format byte —
     * a code meaning 4K in one does not in the other. Hardware-verified on a Mavic 3 across twelve clips
     * spanning 1080p/4K/C4K/5.1K at 24–60 fps; an unmapped code is left null rather than guessed.
     *
     * The favourite flag is **hardware-verified on the Mavic 3** too: three files favourited (580, 585,
     * 590) read `01` here and the seven around them `00`, videos and stills alike. It is read wherever
     * the stride reaches it; on a body that puts it elsewhere the worst case is a cosmetic wrong heart,
     * never a wrong file. Fields past `+19` are still unmapped.
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
            val fps = if (stride > 14) droneFps(u8(blob, off + 14)) else 0
            val resolution = if (stride > 15) droneResolution(u8(blob, off + 15)) else null
            out.add(DcfRecord(index, size, duration, DcfIndex.fatToEpoch(mtime), starred, resolution, fps))
        }
        return out
    }

    /**
     * Drone record fps code (`+14`) → whole frames per second. Full table: MEDIA_PROTOCOL.md § drone
     * record layout.
     *
     * **Anchored to hardware**: a Mavic 3 produced codes 1–6 for 24/25/30/48/50/60, which is what lets
     * the rest — the slow-motion rates a Mavic 3 can't shoot but other aircraft can — be trusted from
     * the official app's own value codes rather than guessed. Codes 0x0D–0x10 / 0x18–0x1B are
     * decimal-corrected rates (23.976/29.97/…) folded to their base integer; the one genuinely
     * fractional rate (8.7 fps, code 0x17) is omitted rather than rounded.
     */
    private fun droneFps(code: Int): Int = when (code) {
        1 -> 24; 2 -> 25; 3 -> 30; 4 -> 48; 5 -> 50; 6 -> 60
        7 -> 120; 8 -> 240; 9 -> 480; 10 -> 100; 11 -> 96; 12 -> 180
        13 -> 24; 14 -> 30; 15 -> 48; 16 -> 60          // PRECISE_ variants
        17 -> 90; 18 -> 192; 19 -> 200; 20 -> 400; 21 -> 8; 22 -> 20
        24 -> 120; 25 -> 96; 26 -> 72; 27 -> 72; 28 -> 75; 29 -> 15
        else -> 0
    }

    /**
     * Drone record resolution code (`+15`) → "WIDTHxHEIGHT". Full table: MEDIA_PROTOCOL.md § drone
     * record layout.
     *
     * **Anchored to hardware**: a Mavic 3 produced `0x0A`/`0x10`/`0x16`/`0x61` for 1080p / 4K / C4K /
     * 5.1K, which is what lets the rest — 2.7K, 1440p, the larger cine and 360 modes other aircraft
     * shoot but a Mavic 3 does not — be trusted from the official app's own value codes rather than
     * guessed. Only codes that name a concrete pixel size are mapped; interlaced, RAW and
     * aspect-ratio-only codes are left null → the app falls back to the moov.
     */
    internal fun droneResolution(code: Int): String? = when (code) {
        0x00 -> "640x480"; 0x02 -> "1280x640"; 0x04 -> "1280x720"; 0x06 -> "1280x960"
        0x08 -> "1920x960"; 0x0A -> "1920x1080"; 0x0C -> "1920x1440"; 0x0E -> "3840x1920"
        0x10 -> "3840x2160"; 0x12 -> "3840x2880"; 0x14 -> "4096x2048"; 0x16 -> "4096x2160"
        0x18 -> "2704x1520"; 0x1A -> "640x512"; 0x1B -> "4608x2160"; 0x1C -> "4608x2592"
        0x1F -> "2720x1530"; 0x20 -> "5280x2160"; 0x21 -> "5280x2972"; 0x22 -> "3840x1572"
        0x23 -> "5760x3240"; 0x24 -> "6016x3200"; 0x25 -> "2048x1080"; 0x26 -> "336x256"
        0x27 -> "5120x2880"; 0x2C -> "5440x2880"; 0x2D -> "2688x1512"; 0x2E -> "640x360"
        0x30 -> "4000x3000"; 0x32 -> "2880x1620"; 0x34 -> "2720x2040"; 0x36 -> "720x576"
        0x37 -> "7680x4320"; 0x38 -> "5472x3078"; 0x39 -> "8192x4320"; 0x3A -> "8192x3456"
        0x3B -> "4096x1712"; 0x3C -> "8192x5456"; 0x3D -> "5576x2952"; 0x3E -> "5248x2952"
        0x3F -> "2560x1440"; 0x40 -> "2560x1920"; 0x41 -> "4096x3072"; 0x42 -> "1080x1920"
        0x43 -> "1512x2688"; 0x44 -> "5472x3648"; 0x45 -> "864x480"; 0x46 -> "720x1280"
        0x5F -> "2688x2016"; 0x60 -> "8192x3424"; 0x61 -> "5120x2700"; 0x62 -> "1440x1080"
        else -> null
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or
            (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)
}
