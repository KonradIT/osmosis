package dev.konraditurbe.osmosis.drone

import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.dcf.DcfRecords

/**
 * The DJI **drone** wire format for media listing — a different payload inside the *same*
 * `0x00/0x26` → `0x00/0x27` DUML exchange the Osmo cameras use. Reverse-engineered from a PCAPdroid
 * capture of **DJI Fly ↔ a real Mavic 3** browsing its gallery over QuickTransfer WiFi (2026-08-01);
 * see ROADMAP #14.
 *
 * **What's the same as a camera:** the DUML command pair (`0x00/0x26` query, `0x00/0x27` reply,
 * `receiverType = 0x01`), the datalink transport, and the `0x4a` sub-protocol envelope — the drone
 * even accepts the byte-identical `4a04…` trigger frame the camera path already sends.
 *
 * **What differs:** a camera answers with DJI's *CompositePack* TLV carrying real **paths**
 * (`DCIM/DJI_001/DJI_…_0211_D.MP4`). A drone answers with a flat array of fixed 94-byte records that
 * contain no filename at all — just a numeric `file_index`, addressed over `/v1` instead of `/v2`.
 * That addressing scheme is **not drone-specific** (the Osmo Action 1 uses it too), so it lives in
 * `dcf/`; this file holds only what is specific to a drone's wire protocol.
 *
 * ### `0x4a` envelope (both directions)
 * ```
 * +0  u8   0x4a
 * +1  u8   subtype — 0x00 list query, 0x01 list reply, 0x20 thumb query, 0x21 thumb reply
 * +2  u16  low 12 bits = this frame's payload length; bit 0x1000 = FINAL chunk
 * +4  u16  seq — the reply echoes the query's
 * +6  u32  chunk index (a reply over ~1 kB is split; DUML frames cap at 1023 bytes)
 * chunk 0 of a reply only:
 * +10 u32  total file count
 * +14 u32  total manifest byte length
 * ```
 * The `0x1000` flag is why a naive `u8` length read appears to work on short frames and then silently
 * mis-parses long ones — a 26-byte reply reads `1a 10`, a 999-byte one reads `e7 03`.
 */
object DroneManifest {

    /** Record stride of a drone's manifest, re-exported from the DCF decoders for callers and tests. */
    const val RECORD_STRIDE = DcfRecords.DRONE_STRIDE

    private const val HEADER = 10          // 4a + subtype + len + seq + chunk
    private const val CHUNK0_EXTRA = 8     // + count + totalBytes
    private const val FINAL_FLAG = 0x1000

    /** Subtype 0x01 — a file-list reply. */
    const val SUB_LIST_REPLY = 0x01

    /** Subtype 0x21 — a thumbnail reply: the same chunked envelope, carrying JPEG bytes. */
    const val SUB_THUMB_REPLY = 0x21

    /** One `0x00/0x27` reply frame. [data] is the record bytes only, envelope stripped. */
    data class Chunk(
        val seq: Int,
        val index: Int,
        val isFinal: Boolean,
        val count: Int,        // total files in the whole reply (chunk 0 only, else -1)
        val totalBytes: Int,   // total record bytes across all chunks (chunk 0 only, else -1)
        val data: ByteArray,
    ) {
        // data class + ByteArray: identity equals would be surprising, so compare by content.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Chunk && seq == other.seq && index == other.index &&
                isFinal == other.isFinal && count == other.count &&
                totalBytes == other.totalBytes && data.contentEquals(other.data))

        override fun hashCode(): Int =
            (((seq * 31 + index) * 31 + count) * 31 + totalBytes) * 31 + data.contentHashCode()
    }

    /** Parse one `0x00/0x27` DUML payload of the given [subtype], or null if it isn't one. */
    fun parseChunk(payload: ByteArray, subtype: Int = SUB_LIST_REPLY): Chunk? {
        if (payload.size < HEADER) return null
        if (u8(payload, 0) != 0x4A || u8(payload, 1) != subtype) return null
        val raw = u16(payload, 2)
        // Trust the frame only when its declared length matches what we actually hold; a short read
        // here would silently truncate records and invent files out of the tail bytes.
        if ((raw and 0x0FFF) != payload.size) return null
        val seq = u16(payload, 4)
        val index = u32(payload, 6).toInt()
        val isFinal = (raw and FINAL_FLAG) != 0
        // Only a FILE-LIST reply puts count + totalBytes in chunk 0. A thumbnail reply's chunk 0 is
        // plain data from +10 (its own 13-byte prefix then the JPEG), so reading them there would eat
        // the first 8 bytes of the image.
        return if (index == 0 && subtype == SUB_LIST_REPLY) {
            if (payload.size < HEADER + CHUNK0_EXTRA) return null
            Chunk(
                seq, 0, isFinal,
                count = u32(payload, 10).toInt(),
                totalBytes = u32(payload, 14).toInt(),
                data = payload.copyOfRange(HEADER + CHUNK0_EXTRA, payload.size),
            )
        } else {
            Chunk(seq, index, isFinal, -1, -1, payload.copyOfRange(HEADER, payload.size))
        }
    }

    /** Concatenate one reply's chunks in index order. Chunks may arrive duplicated and out of order. */
    fun assemble(chunks: List<Chunk>): ByteArray {
        val byIndex = sortedMapOf<Int, ByteArray>()
        for (c in chunks) byIndex.putIfAbsent(c.index, c.data)
        val out = java.io.ByteArrayOutputStream()
        for ((_, d) in byIndex) out.write(d)
        return out.toByteArray()
    }

    /**
     * Decode reassembled record bytes into files. The record layout, the packed index, the FAT
     * timestamp and the name synthesis are all DCF concerns — see [DcfRecords.decodeDrone].
     */
    fun decode(blob: ByteArray): List<CameraFile> =
        DcfRecords.decodeDrone(blob).map { it.toCameraFile() }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or
            (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)

    // ---- request builders -------------------------------------------------------------------------

    /**
     * The file-list query, byte-identical to DJI Fly's apart from [seq] and [cursor].
     *
     * Note it is **9 bytes shorter than the camera's** (33 vs 42) — the camera path's longer form is
     * what elicits a CompositePack reply, so drones keep their own builder rather than sharing one.
     * Byte 14 is the page size (0x2d = 45), matching the count the drone reports back.
     *
     * **Paging:** `cursor = 1` asks for the newest page. An older page passes the **oldest
     * `file_index` of the page just received**, and the drone replays that file as the first record of
     * the next page — so callers dedup by index. (The camera's `0x40000001` video-handle cursor is
     * meaningless here: DJI Fly issues it after every page and the Mavic answers `count = 0`.)
     */
    fun listQuery(seq: Int, cursor: Long = 1L): ByteArray {
        val p = byteArrayOf(
            0x4A, 0x00, 0x21, 0x10, 0x0C, 0x00, 0, 0, 0, 0,
            0x01, 0, 0, 0,                                     // cursor (u32 LE) @10
            0x2D, 0x00, 0x0D, 0x01, 0x00,                      // page size 45 + filter
            -1, -1, -1, -1, -1, -1, -1, -1,                    // ff*8 = all media types
            0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
        )
        p[2] = 0x21; p[3] = 0x10                                // len 33 | FINAL
        p[4] = (seq and 0xFF).toByte(); p[5] = ((seq shr 8) and 0xFF).toByte()
        p[10] = (cursor and 0xFF).toByte()
        p[11] = ((cursor shr 8) and 0xFF).toByte()
        p[12] = ((cursor shr 16) and 0xFF).toByte()
        p[13] = ((cursor shr 24) and 0xFF).toByte()
        return p
    }

    /**
     * Thumbnail request for one [fileIndex] — subtype `0x20`, replied to as chunked subtype `0x21`.
     * Captured verbatim from DJI Fly (48 bytes); only [seq] and the index vary.
     *
     * Kept as a fallback: `/v1?file_subtype=1` serves the same thumbnail over plain HTTP and
     * parallelises, whereas this path is one request at a time on the session thread.
     */
    fun thumbQuery(seq: Int, fileIndex: Long): ByteArray {
        val p = ByteArray(48)
        p[0] = 0x4A; p[1] = 0x20
        p[2] = 0x30; p[3] = 0x10                       // len 48 | FINAL
        p[4] = (seq and 0xFF).toByte(); p[5] = ((seq shr 8) and 0xFF).toByte()
        // +6 chunk = 0 (already zero)
        p[10] = (fileIndex and 0xFF).toByte()
        p[11] = ((fileIndex shr 8) and 0xFF).toByte()
        p[12] = ((fileIndex shr 16) and 0xFF).toByte()
        p[13] = ((fileIndex shr 24) and 0xFF).toByte()
        p[14] = 0x01; p[16] = 0x01                     // request params
        p[22] = -1; p[23] = -1; p[24] = -1; p[25] = -1 // ffffffff
        return p
    }

    /**
     * Pull the JPEG out of a reassembled thumbnail stream, or null if there isn't one.
     *
     * The payload opens with a 13-byte prefix (`00000000`, `u32` total length, `u32` file index, `00`)
     * before the image, so rather than trust that offset we just locate the JPEG markers — robust if a
     * model ever prefixes differently.
     */
    fun extractJpeg(data: ByteArray): ByteArray? {
        var soi = -1
        for (i in 0 until data.size - 1) {
            if (u8(data, i) == 0xFF && u8(data, i + 1) == 0xD8) { soi = i; break }
        }
        if (soi < 0) return null
        var eoi = -1
        for (i in data.size - 2 downTo soi) {
            if (u8(data, i) == 0xFF && u8(data, i + 1) == 0xD9) { eoi = i + 2; break }
        }
        return if (eoi > soi) data.copyOfRange(soi, eoi) else null
    }

    /**
     * The follow-up frame that closes out a list reply. DJI Fly sends it *after* the chunks land (not
     * before — it does not trigger the transfer), and it is byte-identical to the one the camera path
     * already emits mid-stream, so the same frame serves both.
     */
    fun listAck(seq: Int): ByteArray {
        val p = byteArrayOf(0x4A, 0x04, 0x0E, 0x10, 0x00, 0x00, 0, 0, 0, 0, 0x01, 0, 0, 0)
        p[4] = (seq and 0xFF).toByte(); p[5] = ((seq shr 8) and 0xFF).toByte()
        return p
    }
}
