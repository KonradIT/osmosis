package dev.konraditurbe.osmosis.core

/**
 * The DJI **drone** media manifest — a different payload inside the *same* `0x00/0x26` → `0x00/0x27`
 * DUML exchange the Osmo cameras use. Reverse-engineered from a PCAPdroid capture of **DJI Fly ↔ a
 * real Mavic 3** browsing its gallery over QuickTransfer WiFi (2026-08-01); see ROADMAP #14.
 *
 * **What's the same as a camera:** the DUML command pair (`0x00/0x26` query, `0x00/0x27` reply,
 * `receiverType = 0x01`), the datalink transport, and the `0x4a` sub-protocol envelope — the drone
 * even accepts the byte-identical `4a04…` trigger frame the camera path already sends.
 *
 * **What differs — and it's the whole reason this file exists:** a camera answers with DJI's
 * *CompositePack* TLV carrying real **paths** (`DCIM/DJI_001/DJI_…_0211_D.MP4`). A drone answers with
 * a **flat array of fixed 94-byte records that contain no filename at all** — just a numeric
 * `file_index`. Media is then fetched by that number over HTTP (`/v1?file_index=…`), not by path
 * (`/v2?…&path=…`). So [decode] *synthesises* the DJI-convention name from the index.
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
 *
 * ### Record (94 bytes, newest first)
 * ```
 * +0  u32  mtime, unix seconds
 * +4  u32  file size in bytes   — verified byte-exact against two HTTP Content-Lengths
 * +8  u32  file_index = (folder shl 16) or number  — the /v1?file_index= parameter
 * +12 u16  duration in seconds; 0 => still photo
 * ```
 * Fields past +14 are not yet mapped (resolution/fps live in there somewhere) and are left null
 * rather than guessed — see ROADMAP #14.
 */
object DroneManifest {

    const val RECORD_STRIDE = 94
    private const val HEADER = 10          // 4a + subtype + len + seq + chunk
    private const val CHUNK0_EXTRA = 8     // + count + totalBytes
    private const val FINAL_FLAG = 0x1000

    /** Subtype 0x01 — a file-list reply. */
    private const val SUB_LIST_REPLY = 0x01

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

    /** Parse one `0x00/0x27` DUML payload, or null if it isn't a drone file-list reply. */
    fun parseChunk(payload: ByteArray): Chunk? {
        if (payload.size < HEADER) return null
        if (u8(payload, 0) != 0x4A || u8(payload, 1) != SUB_LIST_REPLY) return null
        val raw = u16(payload, 2)
        // Trust the frame only when its declared length matches what we actually hold; a short read
        // here would silently truncate records and invent files out of the tail bytes.
        if ((raw and 0x0FFF) != payload.size) return null
        val seq = u16(payload, 4)
        val index = u32(payload, 6).toInt()
        val isFinal = (raw and FINAL_FLAG) != 0
        return if (index == 0) {
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
     * Decode reassembled record bytes into files, dropping any trailing partial record and any record
     * whose index doesn't look like a real `(folder, number)` pair — a dropped middle chunk shifts the
     * stream out of phase, and it's far better to return the records we can trust than to emit
     * plausible-looking garbage with 1970s dates and nonsense sizes.
     */
    fun decode(blob: ByteArray): List<CameraFile> {
        val out = ArrayList<CameraFile>()
        for (off in 0..blob.size - RECORD_STRIDE step RECORD_STRIDE) {
            val mtime = u32(blob, off)
            val size = u32(blob, off + 4)
            val index = u32(blob, off + 8)
            val duration = u16(blob, off + 12)
            val folder = ((index shr 16) and 0xFFFF).toInt()
            val number = (index and 0xFFFF).toInt()
            // DJI writes DCIM/100MEDIA, 101MEDIA, … — folders start at 100 and files at 1.
            if (folder !in 100..999 || number !in 1..9999) continue
            if (index == 0L || size == 0L) continue
            out.add(toFile(index, folder, number, size, duration, mtime))
        }
        return out
    }

    private fun toFile(index: Long, folder: Int, number: Int, size: Long, duration: Int, mtime: Long): CameraFile {
        // No name is transmitted, so rebuild DJI's on-card convention: DCIM/100MEDIA/DJI_0554.MP4.
        // duration == 0 is the only still-vs-video signal in the record; it held for every file in the
        // capture (the two we can cross-check came back from HTTP as image/jpeg).
        val ext = if (duration > 0) "MP4" else "JPG"
        val name = "DJI_%04d.%s".format(number, ext)
        val path = "DCIM/%dMEDIA/%s".format(folder, name)
        return CameraFile(
            path = path,
            thumbPath = path,          // drones serve thumbs over DUML, not by a derived HTTP path
            fileIndex = index,
            sizeBytes = size,
            durationSec = duration,
            mtimeEpoch = mtime,
        )
    }

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
