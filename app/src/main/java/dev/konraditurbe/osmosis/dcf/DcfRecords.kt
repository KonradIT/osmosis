package dev.konraditurbe.osmosis.dcf

import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.MediaFileType

/** DCF album descriptor. A name extension takes precedence over the conventional indexed filename. */
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
    val mediaType: Int = -1,
    val segmentIndex: Int = 0,
    val listedName: String? = null,
) {
    val storage: Int get() = DcfIndex.storage(fileIndex)

    fun toCameraFile(route: DcfAddressing.Route = DcfAddressing.Route.V1): CameraFile {
        val extension = when (mediaType) {
            MediaFileType.JPEG -> "JPG"
            MediaFileType.DNG -> "DNG"
            MediaFileType.MOV -> "MOV"
            MediaFileType.MP4 -> "MP4"
            else -> if (durationSec > 0) "MP4" else "JPG" // drone records
        }
        val standardPath = DcfIndex.path(fileIndex, extension)
        val name = listedName?.takeIf { it.substringAfterLast('.').equals(extension, ignoreCase = true) }
        val basePath = when {
            name == null -> standardPath
            name.startsWith("DCIM/") -> name
            else -> "${standardPath.substringBeforeLast('/')}/$name"
        }
        val suffix = "_%03d".format(java.util.Locale.ROOT, segmentIndex)
        val stem = basePath.substringBeforeLast('.')
        val path = if (segmentIndex == 0 || stem.endsWith(suffix)) basePath
            else "$stem$suffix.${basePath.substringAfterLast('.')}"
        return CameraFile(
            path = path,
            thumbPath = path,
            fileIndex = fileIndex,
            dcfRoute = route,
            mediaType = mediaType,
            segmentIndex = segmentIndex,
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

/** Decodes drone record arrays and the length-prefixed photo and video descriptors used by Actions. */
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

    /** Record stride in the captured Action 1 albums. See [decodeAction1]. */
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

    /**
     * DJI's dji_list_info_descriptor has an 18-byte prefix and an extension length at +17.
     * The captured Action 1 firmware pads its extension area to 47 bytes. A newer layout can
     * vary the length by item. Validate every boundary before returning any files.
     *
     * AC101 DjiTransSrv_SendListFrag packs FAT wall time at +0 and writes the media type at +16.
     * DjiTransSrv_VideoMiscInfoGet writes tag 10, the segment sub-index and duration in ms.
     *
     * [allowShort] accepts a list that stops before the length its header declares and returns the
     * whole records it holds. A missing chunk is a missing run of records, not a shifted one, because
     * the camera packs whole records into each chunk. Every record that is present is still checked.
     */
    fun decodeAction1(blob: ByteArray, variableRecords: Boolean = false, allowShort: Boolean = false): List<DcfRecord> {
        if (blob.size < LIST_HEADER) return emptyList()
        val count = u32(blob, 0)
        val declared = u32(blob, 4)
        val short = allowShort && declared in (blob.size + 1L)..MAX_ACTION1_LIST
        if (declared != blob.size.toLong() && !short) return emptyList()
        if (count !in 1..((declared - LIST_HEADER) / 18)) return emptyList()
        if (!variableRecords && LIST_HEADER + count * ACTION1_STRIDE != declared) return emptyList()

        val out = ArrayList<DcfRecord>()
        var off = LIST_HEADER
        repeat(count.toInt()) {
            if (blob.size - off < 18) return if (short) out else emptyList()
            val next = off + 18 + u8(blob, off + 17)
            if (next > blob.size) return if (short) out else emptyList()
            if (!variableRecords && next - off != ACTION1_STRIDE) return emptyList()
            val index = u32(blob, off + 8)
            if (!DcfIndex.isPlausible(index)) return emptyList()
            val type = u8(blob, off + 16)
            val video = type == MediaFileType.MOV || type == MediaFileType.MP4
            if (video || type == MediaFileType.JPEG || type == MediaFileType.DNG) {
                var seconds = if (video) u16(blob, off + 12) else 0
                var segment = 0
                var listedName: String? = null
                var starred = false
                var pos = off + 18
                while (pos < next) {
                    val tag = u8(blob, pos++)
                    // DJI_FILE_LIST_EXT_TYPE. Unknown tags have no length field, so skip to
                    // the next record rather than interpreting their payload as another tag.
                    val length = when (tag) {
                        1 -> 4  // video UUID
                        2 -> 3  // photo type and group
                        3 -> 1  // star
                        4 -> 5  // video attributes
                        5 -> 3  // group
                        6, 7 -> 1
                        8 -> 35 // packed EXIF
                        10 -> 10 // misc video
                        11 -> 23 // custom DCF metadata
                        13 -> if (pos < next) 1 + u8(blob, pos) else return emptyList()
                        14 -> 16 // file MD5
                        else -> break
                    }
                    if (pos + length > next) return emptyList()
                    if (tag == 3) starred = u8(blob, pos) == 1
                    if (tag == 13) {
                        val name = String(blob, pos + 1, length - 1, Charsets.UTF_8).trimEnd('\u0000')
                            .removePrefix("/")
                        if (listedFileName.matches(name)) listedName = name
                    }
                    if (tag == 10 && video) {
                        segment = u16(blob, pos)
                        val ms = u32(blob, pos + 2)
                        if (ms > 0) seconds = (ms / 1000).toInt().coerceAtLeast(1)
                    }
                    pos += length
                }
                out += DcfRecord(
                    fileIndex = index, sizeBytes = u32(blob, off + 4), durationSec = seconds,
                    mtimeEpoch = DcfIndex.fatToEpoch(u32(blob, off)), starred = starred,
                    resolution = if (video) droneResolution(u8(blob, off + 15)) else null,
                    fps = if (video) droneFps(u8(blob, off + 14) and 0x3f) else 0,
                    mediaType = type, segmentIndex = segment, listedName = listedName,
                )
            }
            off = next
        }
        return if (off == blob.size) out else emptyList()
    }

    /** Largest declared length a short Action 1 list is taken at its word for: 255 records of 1 KiB. */
    private const val MAX_ACTION1_LIST = 255L * 1024

    private val listedFileName = Regex("""(?:DCIM/(?:[A-Za-z0-9_-]+/)*)?[A-Za-z0-9_-][A-Za-z0-9_.-]*\.[A-Za-z0-9]+""")

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or
            (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)
}
