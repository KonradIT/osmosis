package dev.konraditurbe.osmosis.core

import dev.konraditurbe.osmosis.dcf.DcfIndex

/**
 * One media item, as the app models it regardless of which device listed it.
 *
 * Pure data: how it is actually *fetched* — `/v2` by path or `/v1` by DCF index — lives behind
 * [MediaAddressing], not here.
 */

/**
 * DJI's `MediaFileType`, the enum a record's type byte carries.
 *
 * Only the values the manifest has actually produced are named; the rest are in MEDIA_PROTOCOL.md
 * (`5` TIFF, `10` AUDIO, `19` LRF, `20` THM, `21` SCR, `44` OSV, `65535` UNKNOWN) and can be added
 * here when something decodes one.
 */
object MediaFileType {
    const val JPEG = 0
    const val DNG = 1
    const val MOV = 2
    const val MP4 = 3
    const val PANORAMA = 4
}

data class CameraFile(
    val path: String,        // e.g. DCIM/DJI_001/DJI_20260329115359_0211_D.MP4
    val thumbPath: String,   // e.g. MISC/THM/DJI_001/DJI_20260329115359_0211_D.scr
    val storage: Int = 0,    // the camera mount index that serves this path (resolved by probing —
                             // NOT a fixed SD/internal mapping: an Xtra served its SD at 0, internal at 1)
    val resLabel: String? = null, // e.g. "25fps" — fps from the DUML manifest record
    val proxyPath: String? = null, // low-res proxy clip (.LRF/.LRV) if the camera lists one
    val handle: Long = 0L,   // camera-assigned delete handle (DUML 0x00/0x28); 0 = unknown → not deletable
    val sizeBytes: Long = 0L, // full media byte size from the DUML manifest (record marker-12); 0 = unknown (probe HTTP)
    val starred: Boolean = false, // ⭐ favourite flag from the manifest (marker+10, video records)
    val resolution: String? = null, // "3840x2160": video from the res-index enum (marker-1); photo from
                                    // its direct pixel W×H (marker+58/+62); null = unknown
    val durationSec: Int = 0, // video length in whole seconds, from the DUML manifest (marker-4); 0 = unknown
    // Handle for the *non-destructive* per-file commands — favorite (0x02/0xbf) and burst group-expand
    // (0x00/0x26 group mode) — which photos need too, but photo records carry no [handle]. Derived by
    // fitting `base + seq*step` to the handles the manifest DOES expose, per storage list, so it works on
    // any camera (Nano `0x40100000`/`0x40` vs Xtra `0x00040000`/`0x10`) with nothing hardcoded. Deliberately
    // separate from [handle]: delete stays manifest-only, never a fitted guess. 0 = couldn't derive.
    val cmdHandle: Long = 0L,
    // Which per-storage list of the manifest this record came from (0 = first, 1 = second). A camera
    // with a card returns TWO lists back to back — SD first, then internal — and every file in a list
    // lives on the same store, so the caller resolves [storage] once per group instead of once per
    // manifest (which used to stamp one store on everything and 404 the other half).
    val group: Int = 0,
    // DCF-INDEXED DEVICES ONLY (0 on a path-based camera). Drones — and the Osmo Action 1 — address
    // media by a packed numeric index instead of a path, served at `/v1?file_index=N` rather than
    // `/v2?…&path=…`. The [path] above is synthesised from this index for display/naming only — it is
    // NOT a URL these devices answer. See [DcfIndex], [DcfUrls] and MEDIA_PROTOCOL.md §29.
    val fileIndex: Long = 0L,
    // Modification time, unix seconds — drones put it straight in the manifest record, where cameras
    // encode it in the filename ([timestamp]). 0 = unknown.
    val mtimeEpoch: Long = 0L,
    // True when [storage] came from the store-specific query that returned this record, rather than
    // from a handle-bit guess confirmed by a HEAD. Set by CameraSession.collectStores; when it is set
    // there is nothing left to resolve and no probe to run. Deliberately last in the parameter list —
    // several call sites still construct a CameraFile positionally, so inserting anywhere else
    // silently rebinds their arguments.
    val storageKnown: Boolean = false,
    /**
     * Another record in the same manifest carries this file's [handle].
     *
     * Delete addresses a file **by handle**, so a shared one does not fail — it destroys whichever
     * file the camera has under that handle and the grid then drops the cell that was asked for,
     * which reads as success. Silently deleting the wrong file is the worst outcome this app has, so
     * a shared handle disables delete for every record holding it. Seen on a Pocket 3: of 43 records
     * only 11 carried handles, and two of those pairs collided (`0x00042ca0`, `0x00042d80`), each
     * time a JPG sharing with the video shot seconds earlier.
     *
     * Last in the parameter list on purpose — several call sites construct a CameraFile positionally.
     */
    val handleShared: Boolean = false,

    /**
     * DJI's `MediaFileType` for this record, or -1 where the record carries no type tag.
     *
     * `0` JPEG · `1` DNG · `2` MOV · `3` MP4 · `4` PANORAMA · `5` TIFF · `10` AUDIO · `19` LRF ·
     * `20` THM · `21` SCR · `44` OSV · `65535` UNKNOWN — see MEDIA_PROTOCOL.md §"What a record means".
     * Observed so far: JPEG, MP4 and PANORAMA.
     *
     * It sits two bytes before the constant `19 06` tag and is the same byte the delete-handle marker
     * reads as its "kind" — so it is present on every record, including the stills that carry no
     * marker. Kept raw rather than as a set of booleans: the enum is longer than the values we have
     * seen, and an unmapped one should stay visible instead of decoding as "not a panorama".
     */
    val mediaType: Int = -1,
) {
    /** DCF-index-addressed media, fetched over `/v1` rather than by path over `/v2`. */
    val isIndexed: Boolean get() = fileIndex != 0L

    /** True once the manifest yielded a delete handle for this file (see DatalinkClient.deleteFiles). */
    val deletable: Boolean get() = handle != 0L && !handleShared

    val name: String get() = path.substringAfterLast('/')
    val ext: String get() = name.substringAfterLast('.', "").uppercase()
    val timestamp: String get() = Regex("""_(\d{14})_""").find(name)?.groupValues?.get(1) ?: ""
    /**
     * The short per-file number shown on a grid cell. Cameras carry it in the name (`…_0211_D.MP4`);
     * a drone's name has no such field (`DJI_0554.MP4`), so take the DCF file number straight out of
     * the packed index — the low 16 bits — instead of showing every cell as `0000`.
     */
    val seq: Int get() = if (isIndexed) DcfIndex.file(fileIndex)
    else Regex("""_(\d{4})_D""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /**
     * `YYYYMMDD` for date grouping, or empty if genuinely unknown.
     *
     * Cameras encode it in the filename; a drone doesn't, so fall back to the manifest mtime — the same
     * value [dateTaken] shows. Without this the grid keys grouping off an empty string and files up
     * under a single unknown-date header even though the preview screen shows the right date.
     */
    val ymd: String get() = timestamp.takeIf { it.length >= 8 }?.substring(0, 8)
        ?: mtimeEpoch.takeIf { it > 0 }?.let {
            java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(it * 1000L))
        } ?: ""

    /** 3-digit burst/interval sub-index (`…_0286_D_001.JPG` → 1), or 0 if this isn't a group frame. */
    val subIndex: Int get() = Regex("""_(\d{3})\.\w+$""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    /** The shared name-prefix that groups burst/interval frames (everything before `_NNN.ext`), or null. */
    val groupKey: String? get() = Regex("""^(.+)_\d{3}\.\w+$""").find(name)?.groupValues?.get(1)
    /** A burst/interval frame — its name carries a `_NNN` sub-index. The manifest lists ONLY the group's
     *  first frame (`…_0286_D_001.JPG`) and standalone photos have no suffix, so this is the reliable
     *  burst signal (→ the 🎞️ badge). The other frames aren't in the manifest — the viewer enumerates
     *  them by probing `_002…` on open (see MediaPreviewActivity.probeBurstFrames). */
    val isBurst: Boolean get() = groupKey != null

    /** Human date: from the 14-digit filename timestamp (cameras), else the manifest mtime (drones). */
    val dateTaken: String get() = timestamp.takeIf { it.length == 14 }?.let {
        "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6, 8)} ${it.substring(8, 10)}:${it.substring(10, 12)}"
    } ?: mtimeEpoch.takeIf { it > 0 }?.let {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(it * 1000L))
    } ?: ""

    val isVideo: Boolean get() = ext in setOf("MP4", "MOV", "OSV", "INSV", "LRF", "LRV", "XRF")

    /**
     * An in-camera-stitched panorama — `MediaFileType.PANORAMA`.
     *
     * Written as an ordinary `.JPG`, so nothing in the name or extension tells it apart and only
     * [mediaType] can. Verified on an Osmo Pocket 3: two panoramas read `4` where six ordinary stills
     * on the same card read `0` (JPEG) and three videos read `3` (MP4).
     */
    val isPanorama: Boolean get() = mediaType == MediaFileType.PANORAMA
    val isImage: Boolean get() = ext in setOf("JPG", "JPEG", "DNG", "HEIC", "RAW")

    companion object {
        /**
         * Marks a thumbnail that has to be lifted out of the original's EXIF block rather than fetched
         * as its own file — the suffix is the original's URL. Used for stills on a DCF device, which
         * carry no thumbnail rendition at all. `ImageLoader` ranges the head and calls [EmbeddedJpeg].
         */
        const val EXIF_THUMB = "exif:"
    }
}
