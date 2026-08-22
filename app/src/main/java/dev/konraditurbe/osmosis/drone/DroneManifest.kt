package dev.konraditurbe.osmosis.drone

import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.dcf.DcfRecords

/**
 * The DJI **drone** wire format for media listing — a different payload inside the *same*
 * `0x00/0x26` → `0x00/0x27` DUML exchange the Osmo cameras use. Reverse-engineered from a PCAPdroid
 * capture of **DJI Fly ↔ a real Mavic 3** browsing its gallery over QuickTransfer WiFi (2026-08-01);
 * see MEDIA_PROTOCOL.md §28.
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
 * +1  u8   subtype — 0x00 query, 0x01 reply, 0x02 proceed, 0x03 state, 0x04 release
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
    fun decode(blob: ByteArray, stride: Int = DcfRecords.DRONE_STRIDE): List<CameraFile> =
        DcfRecords.decodeDrone(blob, stride).map { it.toCameraFile() }

    /**
     * The record size this reply declares, or null if it declared nothing usable.
     *
     * Chunk 0 carries the file count and the total record bytes, and `total = 8 + stride * count` —
     * so the aircraft states its own record size and there is no need to know the model. See
     * [DcfRecords.strideFrom].
     */
    fun strideOf(chunks: List<Chunk>): Int? = chunks
        .firstOrNull { it.index == 0 && it.count >= 0 && it.totalBytes >= 0 }
        ?.let { DcfRecords.strideFrom(it.count, it.totalBytes) }

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

    fun listAck(seq: Int, subtype: Int = SUB_LIST_ACK): ByteArray {
        val p = byteArrayOf(0x4A, 0x04, 0x0E, 0x10, 0x00, 0x00, 0, 0, 0, 0, 0x01, 0, 0, 0)
        p[1] = subtype.toByte()
        p[4] = (seq and 0xFF).toByte(); p[5] = ((seq shr 8) and 0xFF).toByte()
        return p
    }

    /**
     * The `0x4a` subtypes run as a family per transfer kind: `+0` query, `+1` reply, `+2` proceed,
     * `+3` state, `+4` release. A media list is `0x00`–`0x04`; a thumbnail would be `0x20`–`0x24`, but
     * this app fetches thumbnails over HTTP instead — see `DcfAddressing`.
     *
     * **A transfer holds a slot on the drone until it is released, and there are few of them.** Leak
     * them and it stops answering media queries while telemetry streams on, so the link looks healthy
     * and serves nothing. Captured from the reference app:
     * ```
     * -> 4a sub=00 seq=0005   query
     * <- 4a sub=03 seq=0005   drone: state (arrives before the data, and again after it)
     * -> 4a sub=02 seq=0005   app: proceed
     * <- 4a sub=01 seq=0005   data
     * -> 4a sub=04 seq=0005   app: release
     * ```
     */
    const val SUB_LIST_GO = 0x02
    const val SUB_LIST_STATE = 0x03
    const val SUB_LIST_ACK = 0x04

    /**
     * The "proceed" frame answering a [SUB_LIST_STATE] the drone raises before it starts sending.
     * Byte-identical to the reference app's: `4a020f10 <seq:u16> 00000000 0000000000`.
     */
    fun transferGo(seq: Int, subtype: Int = SUB_LIST_GO): ByteArray {
        val p = ByteArray(15)
        p[0] = 0x4A; p[1] = subtype.toByte()
        p[2] = 0x0F; p[3] = 0x10                       // len 15 | FINAL
        p[4] = (seq and 0xFF).toByte(); p[5] = ((seq shr 8) and 0xFF).toByte()
        return p
    }

    private fun le32(out: java.io.ByteArrayOutputStream, v: Long) {
        out.write((v and 0xFF).toInt()); out.write(((v shr 8) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt()); out.write(((v shr 24) and 0xFF).toInt())
    }

    /**
     * Favourite / unfavourite one file — DUML **`0x02/0xbf`**, addressed by packed **`file_index`**.
     *
     * Byte-identical to the camera's favourite payload ([CameraSession.favoritePayload]) with the index
     * in the handle slot: `01 01 [index:u32-LE][counter:u32-LE] 00 [on] 00 00 00`. Verified byte-exact
     * against a Mavic 3 capture (files 603 and 604 favourited in DJI Fly, 2026-08-22).
     */
    fun favouriteCmd(fileIndex: Long, counter: Int, on: Boolean): ByteArray =
        java.io.ByteArrayOutputStream().apply {
            write(0x01); write(0x01); le32(this, fileIndex); le32(this, counter.toLong())
            write(0x00); write(if (on) 0x01 else 0x00); write(0x00); write(0x00); write(0x00)
        }.toByteArray()

    /**
     * Delete files by packed **`file_index`** — DUML **`0x00/0x28`**, batch-capable.
     *
     * The camera's delete payload ([CameraSession.deletePayload]) **minus the trailing `01 01 00 00`**
     * storage selector, which the aircraft does not send: `[count:u8][index:u32-LE × count]
     * [counter:u32-LE] 00 [count:u32-LE]`. Verified byte-exact against a Mavic 3 capture that deleted
     * three files (600, 601, 602) in one command. **Irreversible.**
     */
    fun deleteCmd(indices: List<Long>, counter: Int): ByteArray =
        java.io.ByteArrayOutputStream().apply {
            write(indices.size and 0xFF)
            for (idx in indices) le32(this, idx)
            le32(this, counter.toLong()); write(0x00); le32(this, indices.size.toLong())
        }.toByteArray()
}
