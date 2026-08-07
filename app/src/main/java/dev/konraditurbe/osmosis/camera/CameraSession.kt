package dev.konraditurbe.osmosis.camera

import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.duml.DjiCrc
import dev.konraditurbe.osmosis.net.DumlSession
import dev.konraditurbe.osmosis.net.DumlTransport

/**
 * A media session with an **Osmo camera** — registration, the CompositePack media list (`0x00/0x26`)
 * and every per-file command (delete, favourite, burst expand).
 *
 * This is the path `main` has always taken and the one every camera in the app uses. A drone shares
 * only the handshake in [DumlSession] and diverges completely after it — see
 * [DroneSession][dev.konraditurbe.osmosis.drone.DroneSession].
 *
 * The datalink UDP [port] differs by camera family:
 *  - Osmo 360 / Nano / Pocket 3 = 9004, and need a TCP-7001 poke to arm it (`tcpPoke` = true).
 *  - Xtra Edge Pro (= DJI Action 5 Pro) = 10004, no poke (discovered via pcap; undocumented).
 *
 * After [fetchFileList] the socket stays OPEN: the Action 5 tears down its WiFi AP the instant the
 * datalink goes idle, so call [startKeepAlive] to hold it up during browse/download, then [close].
 * Blocking; call on a background thread. The process must already be bound to the camera network.
 */
class CameraSession(
    log: (String) -> Unit,
    port: Int = 9004,
    tcpPoke: Boolean = true,
) : DumlSession(log, port, tcpPoke, isDrone = false) {

    /**
     * The status keys to subscribe to (`0x00/0x99`). Eight, deliberately — NOT Mimo's 54.
     *
     * Tried and reverted (2026-08-07). Mimo subscribes to 54 keys, so we matched it. The camera then
     * pushes state for all of them, and that push traffic arrives as `4A 01` data chunks on the SAME
     * stream the media manifest uses — so [manifestBytes] cannot filter it out and [decodeManifest] sees
     * a 35-70 KB blob of parameter names with no `DCIM/` paths in it at all. On an Xtra Edge Pro that
     * broke burst group-expand outright:
     *
     *     group-expand(inline) CAM_20260802162559_0057_D -> 0 frames (of 0)
     *     no CompositePack records - dumping manifest, falling back to flat scrape
     *     ...the dumped "manifest" contains camcap_photo_time_limited_burst_param...
     *
     * and each failure fell through to a fresh session, so it looped. With eight keys the push volume is
     * small enough not to swamp a manifest query. Raising this again needs [manifestBytes] to filter by
     * DUML cmd (0x00/0x27) rather than by the `4A 01` payload prefix alone.
     *
     * The frame FORMAT fix stays — see [subscription]; that one was real and is unrelated.
     */
    private val paramSubs = listOf(
        "camcap_mode_profile", "camcap_video_format", "camcap_fov", "camcap_iso",
        "camcap_photo_storage_format", "camcap_color_mode", "cam_storage", "cam_status",
    )

    private val VIDEO_EXTS = setOf("MP4", "MOV", "OSV", "INSV")

    /**
     * `0x00/0x88` sub-command `0x17` — the app telling the camera it is still here, ASCII `APP` at bytes
     * 5-7. Sent at registration **and every ~1 s for the whole session**.
     *
     * The camera needs a periodic beat to keep serving a browsing client, and playback mode is what it
     * drops without one. We used to send `0x02/0x8E` ~3x/s, which Mimo never sends and which actively
     * knocked the camera OUT of playback; removing it left no beat at all, and the mode then fell over
     * about a second after being set, coming back only on our own 10 s re-assert — observed on hardware
     * as "appears, 1 s, disappears, 10 s later appears again". With this at 1 Hz, playback holds for the
     * whole session.
     *
     * 14 bytes, Mimo's shape. Ours was 15 with an extra `00` before the trailing `02`. Bytes 2-5 vary
     * between Mimo's own sends (46237c41 → a8237c41) so they carry a counter or timestamp we don't
     * model; a fixed value is accepted.
     *
     * Note for whoever picks this up: Mimo's 1 Hz 00/88 is actually a DIFFERENT sub-command —
     * `1a 00 00 00 01` / `1a 00 00 00 00`, 5 bytes — and it sends the `0x17` announce only twice, at
     * t=0.115 s and t=0.595 s. Beating the announce is therefore not what Mimo does; it simply works.
     * The `1a` beat is untried and is the more faithful thing to test.
     */
    private val APP_PRESENCE = hex("170046237c415050000000000002")

    // ---- playback mode -------------------------------------------------------------------------
    // Playback (0x02/0x0c = 01 01 00 01) is a CAMERA-WIDE mode, not a per-command flag, and it is what
    // takes the camera out of capture: on a Pocket 3 the gimbal parks, which is what a user offloading
    // a session actually wants. Mimo treats it as owned for the whole browse — from a Nano capture:
    //
    //     1.10s APP->CAM 01010001      enter
    //     1.33s CAM->APP 00            confirmed
    //     … 48 s of browsing, thumbnails and a DELETE, no further 0x02/0x0c at all …
    //
    // and in a longer one it held for 128 s straight, left only when the user backed out of the album,
    // and on re-entry sent the enter TWICE 0.6 s apart — i.e. Mimo retries until the camera answers.
    //
    // We used to fire the enter once and never look at the reply, then send LEAVE at the end of every
    // fresh-session fallback (favorite). So the mode flapped on/off underneath operations
    // that assume it is held, which is what made favourites and deletes race and let a Pocket 3 sit in
    // capture mode — recording — throughout a transfer. Now: confirm on entry, retry, hold for the
    // whole session, and release only on teardown.
    @Volatile private var playbackHeld = false
    /** Set by [close] so the keep-alive thread sends the leave before the socket goes. */
    @Volatile private var releasePlaybackOnExit = false
    @Volatile private var playbackReleased = false

    // Lazy grid pagination: [pageCursor] = oldest video handle seen (the next page's selector),
    // [seenPaths] dedups across pages, [moreAvailable] = another older page is likely to exist. Video
    // record handles live at/above VIDEO_HANDLE_BASE (0x4010xxxx on the Nano); the paging cursor only
    // steps through those, so stray low-namespace handles don't derail it.
    private val VIDEO_HANDLE_BASE = 0x40000000L

    /**
     * Records requested per file-list query — the `2d` at byte 14 of every `0x00/0x26` we build.
     *
     * Not a number we picked: Mimo's own `BaseFetchData.mCount` is 45, and byte 14 is `0x2d` in all 36
     * list requests across four Mimo captures. Named here so the short-page end-of-list test below is
     * obviously tied to what we actually asked for.
     */
    private val PAGE_SIZE = 45

    // Request counters we put at byte 4 of each 0x00/0x26, echoed back at sub-header byte 4 of every
    // 0x00/0x27 chunk answering it. Counter 1 always carries cursor 0x00000001 (SD), counter 2 either
    // 0x40000001 (internal, initial load) or an internal page handle — both the internal store.
    private val SD_QUERY_CTR = 1
    private val INTERNAL_QUERY_CTR = 2
    // Frame limit for a burst/interval group-expand query (byte 14). Generous — covers long interval
    // timelapses; any spill into older files is filtered out by the group base name. See expandBurstGroup.
    private val GROUP_EXPAND_LIMIT = 120
    private var pageCursor = 0x40000001L
    private val seenPaths = HashSet<String>()


    /**
     * Bring the datalink up ([openDatalink]) and then register: device-info, register, gimbal-init and
     * the param subscriptions. Leaves the socket open and the send sequence synced on success.
     *
     * Shared by [fetchFileList] and the delete flow — the delete re-runs this so it rides the same
     * fresh, in-window session the list does.
     */
    private fun openAndRegister(ip: String, subscribe: Boolean = true): Boolean {
        if (!openDatalink(ip)) return false

        // Registration.
        sendDuml(0x00, 0x81, appDeviceInfo(), receiverType = 0x08, receiverId = 2, cmdType = 4)
        recvAll(400); sendAck()
        sendDuml(0x00, 0x88, APP_PRESENCE, receiverType = 0x08, receiverId = 1)
        recvAll(400); sendAck()
        sendDuml(0x03, 0xDA, hex("05ffffffff"), receiverType = 0x03, receiverId = 0)
        recvAll(400); sendAck()
        // Status subscriptions only feed the live pill; the delete session skips them to save ~4 s.
        if (subscribe) {
            // Back to back, like Mimo (all 80 of its frames inside 258 ms), then drain once. Waiting
            // ~300 ms per key served no purpose — the camera doesn't gate the next subscription on the
            // previous reply — and with 54 keys that pause would have cost 16 s of connect time.
            var subId = 0x69DF
            for ((i, p) in paramSubs.withIndex()) {
                sendDuml(0x00, 0x99, subscription(p, subId), receiverType = 0x08, receiverId = 1)
                subId++
                if (i % 8 == 7) { recvAll(20); sendAck() }   // let the socket breathe, keep the ACKs flowing
                onFetchProgress?.invoke(22 + (i * 20) / paramSubs.size)
            }
            repeat(4) { recvAll(400); sendAck() }
        }
        return true
    }

    /** Handshake + register + list. Socket stays open on success. Empty list on failure. */
    override fun fetchFileList(ip: String): List<CameraFile> {
        if (!openAndRegister(ip)) return emptyList()
        runCatching { syncTime() }   // set the camera clock + timezone to the phone's, on every connect
        onFetchProgress?.invoke(50)

        // Fast initial load: osmo-download's proven 3-command sequence → the newest ~45 files (no
        // playback mode needed, quick, zero issues). OLDER pages are lazy — the grid's infinite scroll
        // calls fetchNextPage(), which enters playback and pages by the 4-byte handle cursor.
        sendDuml(0x00, 0x26, hex(
            "4a002a10010000000000010000002d000d0100ffffffffffffffff000100000000000000000000000000"
        ), receiverType = 0x01, receiverId = 0)
        val blob = java.io.ByteArrayOutputStream()
        var lastCount = -1
        var stable = 0
        for (batch in 0 until 15) {
            for (r in recvAll(800)) blob.write(r); sendAck()
            if (batch == 1) sendDuml(0x00, 0x26, hex("4a040e1001000000000001000000"),
                receiverType = 0x01, receiverId = 0)
            if (batch == 2) sendDuml(0x00, 0x26, hex(
                "4a002a10020000000000010000402d000d0100ffffffffffffffff000100000000000000000000000000"
            ), receiverType = 0x01, receiverId = 0)
            val count = distinctPaths(blob)
            if (count != lastCount) log("datalink: $count files (batch $batch)")
            onFetchProgress?.invoke((55 + batch * 6).coerceAtMost(95))
            if (batch >= 4 && count > 0 && count == lastCount) { if (++stable >= 2) break } else stable = 0
            lastCount = count
        }
        val files = collectStores(blob.toByteArray())
        // Seed lazy-pagination state: dedup set + cursor = oldest video handle on this page.
        seenPaths.clear(); seenPaths.addAll(files.map { it.path })
        pageCursor = files.map { it.handle }.filter { it >= VIDEO_HANDLE_BASE }.minOrNull() ?: 0L
        moreAvailable = hasOlderPage(files.size, pageCursor)
        log("datalink: parsed ${files.size} media files (newest page; more=$moreAvailable)")
        onFetchProgress?.invoke(100)
        return files
    }

    private fun listCmd(ctr: Int, cursor: Long): ByteArray =
        hex("4a002a10010000000000010000002d000d0100ffffffffffffffff000100000000000000000000000000").also {
            it[4] = ctr.toByte()
            it[10] = (cursor and 0xFF).toByte()
            it[11] = ((cursor ushr 8) and 0xFF).toByte()
            it[12] = ((cursor ushr 16) and 0xFF).toByte()
            it[13] = ((cursor ushr 24) and 0xFF).toByte()
        }


    /**
     * Fetch the next OLDER page for the grid's infinite scroll, returning ONLY the newly-seen files
     * (empty when exhausted). Pagination (from a DJI-Mimo pcap): enter PLAYBACK MODE (0x02/0x0c=01 01 00
     * 01), then per page query(cursor=1) → trigger → query(cursor=[oldest video handle], 4-byte LE at
     * bytes 10-13). The camera drops the datalink after ~2 pages and any keepalive drifts udpSeq out of
     * window, so each call runs in a FRESH registered session (like the delete flow), then restores the
     * browse keep-alive. Blocking; call off the UI thread.
     */
    @Synchronized
    override fun fetchNextPage(): List<CameraFile> {
        if (!moreAvailable) return emptyList()

        // Inline on the live session first (playback held) — the win that makes scroll instant (#12).
        // Same frame sequence as the fresh path: query(cursor=1) → trigger → query(pageCursor). Run
        // stepPagination (which advances the cursor) ONLY on a non-empty decode, so a failed inline query
        // falls through to the fresh path without double-advancing.
        if (keepAliveOn) {
            val blob = runManifestQuery(
                listCmd(1, 1L), hex("4a040e1001000000000001000000"), listCmd(2, pageCursor))
            val page = blob?.let { collectStores(it) } ?: emptyList()
            if (page.isNotEmpty()) {
                val step = stepPagination(pageCursor, page, seenPaths)
                pageCursor = step.nextCursor; moreAvailable = step.moreAvailable
                log("datalink: next page(inline) cursor=0x${pageCursor.toString(16)} +${step.fresh.size} new (more=$moreAvailable)")
                return step.fresh
            }
            log("datalink: next page(inline) empty — fresh-session fallback")
        }

        // Fallback: fresh registered session (pre-#12 path).
        val ip = tx.peerIp ?: return emptyList()
        keepAliveOn = false
        Thread.sleep(600)                 // let the keep-alive loop finish its current recv and exit
        tx.close()                        // free udp/$port for the fresh session
        val fresh = runCatching { freshSessionPage(ip) }
            .getOrElse { log("datalink: next-page session error: ${it.message}"); emptyList() }
        if (tx.isOpen) runCatching { startKeepAlive() }
        return fresh
    }

    /** One older page in a fresh playback session; advances [pageCursor]/[moreAvailable]; returns new files. */
    private fun freshSessionPage(ip: String): List<CameraFile> {
        if (!openAndRegister(ip, subscribe = false)) { log("datalink: next-page open FAILED"); return emptyList() }
        sendDuml(0x02, 0x0c, hex("01010001"), receiverType = 0x01, receiverId = 0)     // enter playback
        for (b in 0 until 2) { recvAll(250); sendAck() }
        val blob = java.io.ByteArrayOutputStream()
        sendDuml(0x00, 0x26, listCmd(1, 1L), receiverType = 0x01, receiverId = 0)       // query cursor=1
        var lastCount = -1; var stable = 0
        for (batch in 0 until 12) {
            for (r in recvAll(800)) blob.write(r); sendAck()
            if (batch == 1) sendDuml(0x00, 0x26, hex("4a040e1001000000000001000000"), receiverType = 0x01, receiverId = 0)
            if (batch == 2) sendDuml(0x00, 0x26, listCmd(2, pageCursor), receiverType = 0x01, receiverId = 0)  // page selector
            val c = distinctPaths(blob)
            if (batch >= 4 && c > 0 && c == lastCount) { if (++stable >= 2) break } else stable = 0
            lastCount = c
        }
        val page = collectStores(blob.toByteArray())
        val step = stepPagination(pageCursor, page, seenPaths)
        pageCursor = step.nextCursor
        moreAvailable = step.moreAvailable
        log("datalink: next page cursor=0x${pageCursor.toString(16)} +${step.fresh.size} new (more=$moreAvailable)")
        return step.fresh
    }

    /**
     * Enumerate a burst/interval group's frames WITHOUT probing — the group-expand `0x00/0x26` query the
     * official app uses (RE'd from a Mimo pcap). The grid manifest lists only the group lead (`…_001`);
     * re-querying with the GROUP HANDLE returns a small manifest of every frame with its real path, thumb
     * and size. Handle = `0x40100000 + seq*0x40` (the favorite-handle formula). Runs in a fresh playback
     * session (the live keep-alive would drop the write), like [fetchNextPage]. Blocking; call off-UI.
     * Returns the frames in sub-index order, or just [lead] if it isn't a group / the query came back empty.
     */
    @Synchronized
    override fun expandBurstGroup(lead: CameraFile): List<CameraFile> {
        val base = lead.groupKey ?: return listOf(lead)
        val ip = tx.peerIp ?: return listOf(lead)
        // Handle namespace differs per camera AND per store, so use the manifest-fitted handle (see
        // withCmdHandles) — a hardcoded Nano formula returned 0 frames on the Xtra.
        val handle = lead.cmdHandle.takeIf { it != 0L } ?: return listOf(lead)

        // Inline on the live session first (playback held): query + trigger, collect the stream (#12).
        if (keepAliveOn) {
            val blob = runManifestQuery(groupCmd(1, handle, GROUP_EXPAND_LIMIT), hex("4a040e1001000000000001000000"))
            if (blob != null) {
                val all = decodeManifest(manifestBytes(blob))
                val group = all.filter { it.name.startsWith(base) }.sortedBy { it.subIndex }
                log("datalink: group-expand(inline) $base → ${group.size} frames (of ${all.size})")
                if (group.size > 1) return group
            }
        }

        // Fallback: fresh registered session (pre-#12 path).
        keepAliveOn = false
        Thread.sleep(600)                 // let the keep-alive loop exit before we take the socket
        tx.close()
        val frames = runCatching { freshSessionExpand(ip, handle, base) }
            .getOrElse { log("datalink: group-expand error: ${it.message}"); emptyList() }
        if (tx.isOpen) runCatching { startKeepAlive() }
        return frames.takeIf { it.size > 1 } ?: listOf(lead)
    }

    /** The group-expand request: grid template with the group handle (bytes 10-13), a generous frame
     *  limit (byte 14) and "group mode" (byte 16 = 0x10, vs 0x0d for the full list). */
    private fun groupCmd(ctr: Int, handle: Long, count: Int): ByteArray =
        hex("4a002a10190000000000804710400600100100ffffffffffffffff000100000000000000000000010000").also {
            it[4] = ctr.toByte()
            it[10] = (handle and 0xFF).toByte()
            it[11] = ((handle ushr 8) and 0xFF).toByte()
            it[12] = ((handle ushr 16) and 0xFF).toByte()
            it[13] = ((handle ushr 24) and 0xFF).toByte()
            it[14] = count.toByte()
        }

    /** One group-expand in a fresh playback session; returns the frames whose name shares [base] (the
     *  query can spill into older files, so filter to the group), oldest-first by sub-index. */
    private fun freshSessionExpand(ip: String, handle: Long, base: String): List<CameraFile> {
        if (!openAndRegister(ip, subscribe = false)) { log("datalink: group-expand open FAILED"); return emptyList() }
        sendDuml(0x02, 0x0c, hex("01010001"), receiverType = 0x01, receiverId = 0)     // enter playback
        for (b in 0 until 2) { recvAll(250); sendAck() }
        val blob = java.io.ByteArrayOutputStream()
        sendDuml(0x00, 0x26, groupCmd(1, handle, GROUP_EXPAND_LIMIT), receiverType = 0x01, receiverId = 0)
        var lastCount = -1; var stable = 0
        for (batch in 0 until 10) {
            for (r in recvAll(700)) blob.write(r); sendAck()
            if (batch == 1) sendDuml(0x00, 0x26, hex("4a040e1001000000000001000000"), receiverType = 0x01, receiverId = 0)
            val c = distinctPaths(blob)
            if (batch >= 3 && c > 0 && c == lastCount) { if (++stable >= 2) break } else stable = 0
            lastCount = c
        }
        val all = decodeManifest(manifestBytes(blob.toByteArray()))
        val group = all.filter { it.name.startsWith(base) }.sortedBy { it.subIndex }
        log("datalink: group-expand $base → ${group.size} frames (of ${all.size} returned)")
        return group
    }

    /**
     * Is there an older page to fetch? Needs a usable cursor **and** a page that came back full.
     *
     * A short page is the end of the library: we asked for [PAGE_SIZE] records and the camera gave us
     * fewer, so it had no more. This used to be the cursor test alone, which is true for any camera
     * holding at least one video — so the pull-up spinner armed on libraries that were already
     * complete, and a pull spent a whole page fetch to append nothing.
     *
     * Mimo answers the same question from a per-record `isPageLastFile` flag it gets in the manifest.
     * That flag is not at any fixed offset in our decoded records — searched every marker-relative
     * position against a known-final page (`xtra_13.bin`, 13 records) versus a known-continuing one
     * (`nano_45.bin`, a full 45 of 195) and nothing separates them — so the page-size test stands in
     * for it. Same conclusion, one less unknown, and it needs no new byte to be right.
     */
    internal fun hasOlderPage(pageSize: Int, cursor: Long): Boolean =
        cursor > 0L && pageSize >= PAGE_SIZE

    internal data class PageStep(val fresh: List<CameraFile>, val nextCursor: Long, val moreAvailable: Boolean)

    /**
     * Pure pagination step for the grid's infinite scroll. Given the current [cursor] (a 4-byte file
     * handle) and a freshly-decoded [page], returns: the files not already in [seen] (which it updates);
     * the next cursor = the OLDEST video handle (>= [VIDEO_HANDLE_BASE]) on the page that is strictly
     * older (smaller) than [cursor]; and whether another older page is likely (new files AND the cursor
     * actually advanced). Only handles in `[VIDEO_HANDLE_BASE, cursor)` qualify, so a stray low-namespace
     * handle (e.g. a 0x0010xxxx photo like seq 0022) can't drag the cursor to the bottom and stall paging.
     */
    internal fun stepPagination(cursor: Long, page: List<CameraFile>, seen: MutableSet<String>): PageStep {
        val fresh = page.filter { seen.add(it.path) }
        val oldestVideo = page.map { it.handle }.filter { it in VIDEO_HANDLE_BASE until cursor }.minOrNull()
        return if (oldestVideo != null && fresh.isNotEmpty()) PageStep(fresh, oldestVideo, true)
        else PageStep(fresh, cursor, false)
    }

    // ---- inline commands on the live session (#12) --------------------------------------------------
    // The keep-alive thread owns the socket, so a command that wants a reply can't just send + recv on
    // another thread (it would race the keep-alive's recv). Instead the caller QUEUES the command and the
    // keep-alive loop sends it, then captures its reply from the same recv loop — one long-lived, in-
    // sequence session, no fresh re-registration. Works only because the r8-9 seq fix keeps writes in-window.
    private class PendingCmd(
        val set: Int, val cmd: Int, val payload: ByteArray,
        val rType: Int, val rId: Int, val cType: Int,
        val replySet: Int, val replyCmd: Int, val deadlineMs: Long,
        val primeFrames: List<ByteArray> = emptyList(),  // extra frames, one per tick after the query (stream priming)
        val stream: Boolean = false,        // true = collect the 0x00/0x27 manifest stream until it settles
    ) {
        val blob = java.io.ByteArrayOutputStream()   // raw datagrams for a stream query
        @Volatile var reply: ByteArray? = null
        @Volatile var done = false
        var ticks = 0; var lastCount = -1; var stable = 0
    }
    private val cmdQueue = java.util.concurrent.ConcurrentLinkedQueue<PendingCmd>()
    @Volatile private var awaitingCmd: PendingCmd? = null   // in flight, session-thread owned

    /**
     * Run a command INLINE on the live keep-alive session and return its reply payload (bytes after the
     * DUML header, before the CRC), or null on timeout. Blocking; call off the UI thread. Returns null
     * immediately if the keep-alive isn't running, so callers can fall back to a fresh session.
     */
    fun runCommand(
        set: Int, cmd: Int, payload: ByteArray, receiverType: Int, receiverId: Int,
        cmdType: Int = 2, replySet: Int = set, replyCmd: Int = cmd, timeoutMs: Long = 2500,
    ): ByteArray? {
        if (!keepAliveOn) return null
        val c = PendingCmd(set, cmd, payload, receiverType, receiverId, cmdType, replySet, replyCmd,
            System.currentTimeMillis() + timeoutMs)
        cmdQueue.add(c)
        while (!c.done && System.currentTimeMillis() < c.deadlineMs + 600) runCatching { Thread.sleep(30) }
        return c.reply
    }

    /**
     * Run a manifest-stream query (group-expand / pagination) INLINE on the live session: send [payload],
     * then [trigger] one tick later, collect the `0x00/0x27` stream until the file count settles, and return
     * the raw blob (feed to `manifestBytes` + `decodeManifest`). Null if the keep-alive isn't running.
     */
    fun runManifestQuery(payload: ByteArray, vararg primeFrames: ByteArray, timeoutMs: Long = 4000): ByteArray? {
        if (!keepAliveOn) return null
        val c = PendingCmd(0x00, 0x26, payload, 0x01, 0, 2, 0x00, 0x27,
            System.currentTimeMillis() + timeoutMs, primeFrames = primeFrames.toList(), stream = true)
        cmdQueue.add(c)
        while (!c.done && System.currentTimeMillis() < c.deadlineMs + 600) runCatching { Thread.sleep(30) }
        return c.reply
    }

    /**
     * Keep the datalink alive (ACK ~2×/s so the AP doesn't sleep) and, Mimo-style, poll the camera
     * for status — heartbeat 0x02/0x8E, state query 0x02/0xA0, status poll 0x02/0x61 — decoding the
     * pushed status frames (battery/storage/firmware) as they arrive. Also holds **playback mode** the
     * whole browse session (so inline commands needing it — favorite/group-expand/pagination — work) and
     * services the inline command queue ([runCommand]).
     */

    /**
     * Put the camera into playback and wait for it to say so, retrying like Mimo does.
     *
     * The old code sent this once and never read the reply, so a camera that was busy, still waking, or
     * mid-mode-change simply stayed in capture and nothing noticed. The camera answers `0x02/0x0c` with
     * a status byte; Mimo waits for that and re-sends if it doesn't come (observed: two sends 0.6 s
     * apart on re-entering the album).
     *
     * Runs ON the keep-alive thread, before its loop. That matters: [sendDuml] bumps `cmdCounter` and
     * `dumlSeq` with no lock, so sending playback frames from any other thread would interleave the
     * sequence numbers and get subsequent commands rejected as out-of-window.
     */
    private fun enterPlaybackConfirmed(attempts: Int = 3): Boolean {
        repeat(attempts) { n ->
            sendDuml(0x02, 0x0C, hex("01010001"), receiverType = 0x01, receiverId = 0)
            val deadline = System.nanoTime() + 900_000_000L
            while (System.nanoTime() < deadline) {
                val dg = recvAll(100)
                parseStatus(dg)                       // keep the status pill fed while we wait
                if (findReply(dg, 0x02, 0x0C) != null) {
                    playbackHeld = true
                    log("datalink: playback mode held" + if (n > 0) " (confirmed on attempt ${n + 1})" else "")
                    return true
                }
                sendAck()
            }
        }
        // Not fatal — the browse still works, but the camera stays in capture (a Pocket 3 keeps its
        // gimbal live and can still be recording), so say so rather than pretend we hold it.
        log("datalink: playback mode NOT confirmed after $attempts attempts — camera may still be in capture")
        return false
    }

    /**
     * How long a registered session keeps accepting inline command WRITES.
     *
     * Two cameras, and they do NOT agree — the Xtra's window is the shorter of the two:
     *
     *     Nano       ok at 45 s, 57 s, 66 s   ·  no reply at 74 s, 94 s, 124 s, 142 s
     *     Edge Pro   no reply at 51.6 s
     *
     * Reads are unaffected on both — a pagination query at 82 s in the Nano session returned normally.
     * 40 s sits under the Xtra's only observed failure with room to spare; 55 s did not, and a favourite
     * at 51.6 s slipped through and missed. Erring low is cheap: the refresh costs one handshake and only
     * happens when a write is actually requested on an old session, whereas erring high costs the full
     * dead-wait-plus-verify path the mitigation exists to avoid.
     *
     * This is a MITIGATION, not the fix. Something in the sliding-window sequence handling
     * ([DumlTransport.routingHeader] / advance) drifts out of the camera's write-accept range and we do
     * not yet know what — the header comment there claims the window isn't enforced, which our own
     * measurement contradicts, so a pcap of one of our long sessions against Mimo's needs to settle it.
     * Until then, re-registering on a schedule we choose beats discovering it by timing out: the old
     * path burned 10 s waiting for a reply that was never coming, then re-registered anyway and re-listed
     * the whole manifest to verify — ~28 s in total.
     */
    private val WRITE_WINDOW_MS = 40_000L

    /**
     * Re-register before a write when the session is too old to accept one.
     *
     * Costs a handshake but lands the write inline afterwards, skipping both the doomed attempt and the
     * verify re-list. Briefly drops playback — closing the socket is what takes the camera out of it —
     * which is the same dip the old fallback caused, just sooner and without the wasted wait.
     */
    // NOTE on @Synchronized, here and on fetchNextPage / expandBurstGroup / deleteFiles / setFavorite:
    // every one of these can tear the keep-alive down, close the socket and re-register. Two of them at
    // once is two threads calling openAndRegister on the same transport, and the result is exactly the
    // wreckage this session's fixes were meant to end. Observed on an Xtra Edge Pro while scrolling and
    // favouriting at the same time: a write-refresh registered at 09:49:13, pagination's own fallback
    // opened a second handshake 4 s later, playback then failed all 3 confirm attempts because the socket
    // went out from under it, the favourite got no reply, and the manifest came back as 41 KB of garbage
    // with no CompositePack records. Serialising a favourite against a delete was not enough — pagination
    // runs off the scroll handler and never touched cmdExec. The lock is the CameraSession instance and
    // Java monitors are reentrant, so deleteFiles -> refreshSessionForWrite nests without deadlocking.
    @Synchronized
    private fun refreshSessionForWrite(what: String): Boolean {
        if (!keepAliveOn || sessionAgeMs() <= WRITE_WINDOW_MS) return false
        val ip = tx.peerIp ?: return false
        log("datalink: session is ${sessionAgeMs() / 1000}s old — re-registering before $what " +
            "(writes stop being answered past ~${WRITE_WINDOW_MS / 1000}s)")
        keepAliveOn = false
        runCatching { Thread.sleep(600) }        // let the keep-alive loop finish its recv and exit
        tx.close()
        if (!openAndRegister(ip, subscribe = false)) {
            log("datalink: re-register before $what FAILED — attempting the write anyway")
            return false
        }
        startKeepAlive()
        return true
    }

    override fun startKeepAlive() {
        if (keepAliveOn) return
        keepAliveOn = true
        playbackReleased = false
        resetStatus()
        Thread {
            var tick = 0
            enterPlaybackConfirmed()
            while (keepAliveOn) {
                runCatching {
                    val dg = recvAll(200)
                    parseStatus(dg)
                    awaitingCmd?.let { c ->
                        if (c.stream) {
                            for (r in dg) c.blob.write(r)
                            c.ticks++
                            if (c.ticks in 1..c.primeFrames.size)       // send each prime frame one tick apart
                                sendDuml(c.set, c.cmd, c.primeFrames[c.ticks - 1], receiverType = c.rType, receiverId = c.rId, cmdType = c.cType)
                            val cnt = distinctPaths(c.blob)
                            if (c.ticks >= c.primeFrames.size + 2 && cnt > 0 && cnt == c.lastCount) {
                                if (++c.stable >= 2) { c.reply = c.blob.toByteArray(); c.done = true; awaitingCmd = null }
                            } else c.stable = 0
                            c.lastCount = cnt
                            if (!c.done && System.currentTimeMillis() > c.deadlineMs) {
                                c.reply = c.blob.toByteArray(); c.done = true; awaitingCmd = null
                            }
                        } else {
                            val r = findReply(dg, c.replySet, c.replyCmd)
                            if (r != null) { c.reply = r; c.done = true; awaitingCmd = null }
                            else if (System.currentTimeMillis() > c.deadlineMs) { c.done = true; awaitingCmd = null }
                        }
                    }
                    sendAck()
                    // NO 0x02/0x8E here. It was sent ~3x/s as a "heartbeat" with payload 00011400, and
                    // it is what kept dragging the camera back out of playback about a second after we
                    // put it in — which is why a Pocket 3 stayed in capture through a transfer, and why
                    // delete only ever answered inline when it happened to be issued within a few
                    // seconds of connecting. Later deletes hit a camera no longer in playback, got no
                    // reply, and paid ~28 s for the verify re-list instead.
                    //
                    // Mimo does not send it. In a 49 s Nano session (reference/captures/wifi/
                    // mimo_nano_delete.pcap) Mimo sends 0x02/0x8E, 0x02/0xa0 and 0x02/0x61 exactly ZERO
                    // times, holds playback the whole way, and still gets status — because the camera
                    // PUSHES 0x02/0x80 (493x) and 0x02/0x82 (480x) on its own once registered. The polls
                    // were never needed for the pill; parseStatus reads those pushes either way.
                    // ~1 Hz app-presence beat (tick is 300 ms). Without it the camera drops playback a
                    // second after we set it — see APP_PRESENCE. This is the beat 0x02/0x8E was standing
                    // in for, badly: that one was ours, not Mimo's, and it knocked playback out.
                    if (tick % 3 == 0)
                        sendDuml(0x00, 0x88, APP_PRESENCE, receiverType = 0x08, receiverId = 1)
                    if (tick % 3 == 0) sendDuml(0x02, 0xA0, ByteArray(0), receiverType = 0x01, receiverId = 0)
                    if (tick % 6 == 0) sendDuml(0x02, 0x61, ByteArray(0), receiverType = 0x01, receiverId = 0)
                    // Re-assert playback every ~10 s. Belt and braces: with 0x02/0x8E gone the mode
                    // should simply stay, but the camera can also be knocked out of it by something we
                    // don't control — a button press on the body, a mode change, a firmware quirk — and
                    // silently losing it costs a 28 s delete. Idempotent and one frame per 33 ticks.
                    if (playbackHeld && tick % 33 == 0)
                        sendDuml(0x02, 0x0C, hex("01010001"), receiverType = 0x01, receiverId = 0)
                    if (awaitingCmd == null) cmdQueue.poll()?.let { c ->
                        awaitingCmd = c
                        sendDuml(c.set, c.cmd, c.payload, receiverType = c.rType, receiverId = c.rId, cmdType = c.cType)
                    }
                }
                runCatching { Thread.sleep(300) }
                tick++
            }
            // The loop also stops for a fresh-session fallback, which resumes browsing straight after —
            // only a real teardown releases the mode, the same way Mimo leaves the album rather than
            // toggling per operation.
            if (releasePlaybackOnExit && playbackHeld) {
                runCatching {
                    sendDuml(0x02, 0x0C, hex("01010000"), receiverType = 0x01, receiverId = 0)
                    recvAll(150)
                }
                playbackHeld = false
                log("datalink: playback mode released")
            }
            playbackReleased = true
        }.apply { isDaemon = true; name = "datalink-keepalive" }.start()
    }

    /**
     * Hand the camera back before dropping the link, so it isn't left parked in playback.
     *
     * The leave has to be sent BY the keep-alive thread (unsynchronized sequence counters — see
     * [enterPlaybackConfirmed]), so this asks it to and waits briefly for it to finish. Bounded tight:
     * a caller that closes and immediately re-opens must not be stalled, and a missed leave costs
     * nothing worse than a camera sitting in playback until it sleeps.
     */
    override fun close() {
        if (playbackHeld && keepAliveOn) {
            releasePlaybackOnExit = true
            keepAliveOn = false
            val deadline = System.currentTimeMillis() + 500
            while (!playbackReleased && System.currentTimeMillis() < deadline) runCatching { Thread.sleep(25) }
            releasePlaybackOnExit = false
        }
        super.close()
    }

    /** Scan datagrams for DUML status frames (SOF 0x55; cmd set/id at header offsets 9/10) and decode. */
    private fun parseStatus(datagrams: List<ByteArray>) {
        for (d in datagrams) {
            var i = 0
            while (i + 11 <= d.size) {
                if ((d[i].toInt() and 0xFF) != 0x55) { i++; continue }
                val len = ((d[i + 1].toInt() and 0xFF) or ((d[i + 2].toInt() and 0xFF) shl 8)) and 0x3FF
                if (len < 13 || i + len > d.size) { i++; continue }
                // Verify the header CRC8 before believing this is a frame: a bare 0x55 scan happily
                // matches raw bytes inside the handshake/routing headers (e.g. the 0xB887 channel
                // constant), which invented a phantom "device 0x07/4 via 0xb8/87" in the log.
                if (DjiCrc.computeCrc8(d.copyOfRange(i, i + 3)) != (d[i + 3].toInt() and 0xFF)) { i++; continue }
                val set = d[i + 9].toInt() and 0xFF
                val id = d[i + 10].toInt() and 0xFF
                applyStatusFrame(set, id, d.copyOfRange(i + 11, i + len - 2))
                i += len
            }
        }
        emitStatusIfChanged()
    }


    /**
     * Delete files by their manifest [handles] (DUML **0x00/0x28** — the file-management cmdset, same
     * one the list `0x00/0x26` lives in; reverse-engineered from a Mimo↔Nano pcap). Returns the reply
     * status word (**0x0000 = OK**), or null on failure/timeout. **Irreversible on the SD card** — the
     * caller must confirm intent and pass only handles it means to destroy. See ROADMAP #4.
     *
     * Runs in a **fresh registered session**: the browse keep-alive loop advances our `udpSeq` past
     * the window the camera will accept, and while reads (the list) still get answered, writes get
     * silently dropped. So we tear the keep-alive session down and re-open exactly like the list fetch
     * ([openAndRegister]) — the one path the camera reliably accepts — issue the delete there, then
     * keep that session for browse. Call on a background thread (blocks ~9 s, the re-handshake cost).
     */
    @Synchronized
    override fun deleteFiles(handles: List<Long>): Int? {
        if (handles.isEmpty()) return null
        refreshSessionForWrite("delete")
        val payload = deletePayload(handles, delCounter++)

        // Inline on the live session first (#12). For a DESTRUCTIVE op a landed reply is authoritative —
        // we return it as-is and never re-issue via the fallback (which could delete twice / report a stale
        // "no such handle" on an already-deleted file). Only a genuine no-reply falls through.
        if (keepAliveOn) {
            // Re-assert playback FIRST. Hardware finding: a delete is only answered while the camera is
            // actually in playback. The one delete that ever came back inline was issued 5 s after the
            // mode was set; every one issued minutes later got no reply at all and fell to the verify
            // re-list. Queued via runCommand so it goes out on the keep-alive thread (see
            // enterPlaybackConfirmed for why that matters), and cheap enough to pay before every delete.
            runCommand(0x02, 0x0C, hex("01010001"), receiverType = 0x01, receiverId = 0, timeoutMs = 1500)
            log("datalink: DELETE(inline) 0x00/0x28 n=${handles.size}")
            // 10 s, not the 2500 ms default. An Xtra Edge Pro answered a delete at +6.9 s with status
            // 0x0000 — long after the default gave up — so the reply WAS coming and we threw it away,
            // then paid for a verify re-list on every single delete. Waiting is cheap here; the fallback
            // tears the session down, and a delete is a deliberate, one-at-a-time act anyway.
            val reply = runCommand(0x00, 0x28, payload, receiverType = 0x01, receiverId = 0,
                timeoutMs = 10_000)
            if (reply != null) {
                val status = if (reply.size >= 2) (reply[0].toInt() and 0xFF) or ((reply[1].toInt() and 0xFF) shl 8) else reply[0].toInt() and 0xFF
                log("datalink: DELETE(inline) status=0x%04x".format(status))
                return status
            }
            // A missing reply does NOT mean a missing delete. Observed on an Xtra Edge Pro: the inline
            // request landed and the file was removed from the card, only the reply never matched — and
            // the old fallback then re-sent the same handle to a camera that had already freed it,
            // which answered 0xd6 ("no such handle") and made a completed delete report as a failure.
            //
            // Re-issuing is not merely wrong, it is dangerous: these cameras REUSE file numbers, and a
            // handle is a function of the number, so the same handle can address a *different, newer*
            // file minutes later. Verify by listing instead — reading is safe, deleting twice is not.
            log("datalink: DELETE(inline) no reply — the delete may still have landed; verifying by re-listing")
            return verifyDeleted(handles)
        }

        // No live session to go inline on: a fresh registered session is the only route (pre-#12 path).
        val ip = tx.peerIp ?: return null
        keepAliveOn = false
        Thread.sleep(600)                // let the keep-alive loop finish its current recv and exit
        tx.close()                       // free udp/$port for the fresh session
        val status = runCatching { freshSessionDelete(ip, payload) }
            .getOrElse { log("datalink: delete session error: ${it.message}"); null }
        if (tx.isOpen) runCatching { startKeepAlive() }
        return status
    }

    /**
     * Did [handles] actually go? Re-lists in a fresh session and looks for them.
     *
     * `0` (the camera's own OK status) when none of them come back, `null` — "no confirmation" — when
     * any still does or the listing fails. Deliberately never re-sends the delete; see [deleteFiles].
     *
     * Caveat worth knowing: the list is the newest page, so a handle that was already off it reads as
     * absent. The cost of that is a grid cell disappearing for a file that survived, which a refresh
     * corrects — as against re-issuing a delete, whose cost is a destroyed file.
     */
    private fun verifyDeleted(handles: List<Long>): Int? {
        val ip = tx.peerIp ?: return null
        keepAliveOn = false
        Thread.sleep(600)
        tx.close()
        val files = runCatching { fetchFileList(ip) }.getOrElse {
            log("datalink: delete verify — listing failed: ${it.message}"); emptyList()
        }
        if (tx.isOpen) runCatching { startKeepAlive() }
        if (files.isEmpty()) { log("datalink: delete verify — no list, cannot confirm"); return null }
        val survivors = handles.filter { h -> files.any { it.handle == h } }
        return if (survivors.isEmpty()) {
            log("datalink: delete verify — handle(s) gone from the list; the delete landed")
            0
        } else {
            log("datalink: delete verify — %d handle(s) still listed; NOT re-issuing".format(survivors.size))
            null
        }
    }

    /**
     * Delete request payload: `[count:u8][handle:u32-LE …][counter:u32] 00 [count:u32] 01 01 00 00`.
     *
     * The field after the handles is a **per-request counter**, not a second copy of the count — it
     * advances 1, 2, 3… across a session exactly as the favourite counter does. With a single handle
     * the two are indistinguishable in one sample, which is how it came to be written as the count;
     * two consecutive deletes in a Mimo capture show `01` then `02` while the count stays `01`. So the
     * first delete of a session happened to be right and every one after it repeated a counter — the
     * shape a replay check rejects.
     *
     * Verified against Mimo↔Nano, `n=1` only. Multi-handle deletes are still inferred: the UI deletes
     * one file at a time, so that path has never been exercised on hardware.
     */
    internal fun deletePayload(handles: List<Long>, counter: Int): ByteArray = java.io.ByteArrayOutputStream().apply {
        write(handles.size and 0xFF)
        for (h in handles) write(le32(h.toInt()))
        write(le32(counter)); write(0x00); write(le32(handles.size)); write(byteArrayOf(0x01, 0x01, 0x00, 0x00))
    }.toByteArray()

    private fun freshSessionDelete(ip: String, payload: ByteArray): Int? {
        if (!openAndRegister(ip, subscribe = false)) { log("datalink: delete — fresh session open FAILED"); return null }
        log("datalink: DELETE 0x00/0x28 (fresh)")
        sendDuml(0x00, 0x28, payload, receiverType = 0x01, receiverId = 0)
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            for (d in recvAll(200)) findRespStatus(d, 0x00, 0x28)?.let {
                log("datalink: DELETE reply status=0x%04x".format(it)); return it
            }
            sendAck()
        }
        log("datalink: DELETE — no reply")
        return null
    }

    /** Per-session request counters — both commands carry one that must advance. See [deletePayload]. */
    private var delCounter = 1
    private var favCounter = 1

    /**
     * Star / un-star a file (DUML **0x02/0xbf**, decoded from a Mimo capture). Like delete and paging,
     * this is a **write that the live keep-alive session silently drops**, and the pcap shows Mimo only
     * ever favorites with **playback mode active** — so we run it in a fresh registered session that
     * enters playback first, exactly like [freshSessionPage]. Payload: `01 01` · `[handle:u32-LE]` ·
     * `[counter:u32-LE]` · `00` · `[on:u8]` · `00 00 00`, to receiver `0x01`; the handle is the favorite
     * index `0x40100000 + seq*0x40`. Returns true on the camera's `0x00` ack. Blocks (~re-handshake);
     * call on a background thread, serialized so rapid toggles don't overlap sessions.
     */
    /**
     * Favourite request payload: `01 01 [handle:u32-LE][counter:u32-LE] 00 [on:u8] 00 00 00`.
     *
     * Byte-identical to Mimo's, verified against a Nano capture — including the counter, which
     * increments per request within a session rather than being a constant.
     */
    internal fun favoritePayload(handle: Long, counter: Int, on: Boolean): ByteArray =
        java.io.ByteArrayOutputStream().apply {
            write(byteArrayOf(0x01, 0x01)); write(le32(handle.toInt())); write(le32(counter))
            write(0x00); write(if (on) 0x01 else 0x00); write(byteArrayOf(0x00, 0x00, 0x00))
        }.toByteArray()

    @Synchronized
    override fun setFavorite(handle: Long, on: Boolean): Boolean {
        refreshSessionForWrite("favourite")
        val payload = favoritePayload(handle, favCounter++, on)

        // Try INLINE on the live session first (it holds playback + a faithful seq now — #12). No pause,
        // no re-registration. Fall back to a fresh session only if the camera doesn't answer.
        if (keepAliveOn) {
            // Same as delete: re-assert playback before the write. Mimo only ever favourites with
            // playback active, and a camera that has drifted out of it answers nothing — which then
            // costs a fresh-session fallback.
            runCommand(0x02, 0x0C, hex("01010001"), receiverType = 0x01, receiverId = 0, timeoutMs = 1500)
            log("datalink: FAVORITE(inline) 0x02/0xbf handle=0x%08x on=%b".format(handle, on))
            val reply = runCommand(0x02, 0xbf, payload, receiverType = 0x01, receiverId = 0)
            val status = reply?.let {
                if (it.size >= 2) (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) else it[0].toInt() and 0xFF
            }
            log("datalink: FAVORITE(inline) status=${status?.let { "0x%04x".format(it) } ?: "no reply"}")
            if (status == 0) return true
        }

        // Fallback: fresh registered session (the pre-#12 path), kept until inline is proven everywhere.
        val ip = tx.peerIp ?: return false
        keepAliveOn = false
        Thread.sleep(600)                // let the keep-alive loop finish its recv and exit
        tx.close()                       // free udp/$port for the fresh session
        val ok = runCatching { freshSessionFavorite(ip, handle, on) }
            .getOrElse { log("datalink: favorite session error: ${it.message}"); false }
        if (tx.isOpen) runCatching { startKeepAlive() }
        return ok
    }

    private fun freshSessionFavorite(ip: String, handle: Long, on: Boolean): Boolean {
        if (!openAndRegister(ip, subscribe = false)) { log("datalink: favorite — fresh session open FAILED"); return false }
        sendDuml(0x02, 0x0c, hex("01010001"), receiverType = 0x01, receiverId = 0)   // enter playback mode
        recvAll(200)
        val b = java.io.ByteArrayOutputStream()
        b.write(byteArrayOf(0x01, 0x01))
        b.write(le32(handle.toInt()))
        b.write(le32(favCounter++))
        b.write(0x00)
        b.write(if (on) 0x01 else 0x00)
        b.write(byteArrayOf(0x00, 0x00, 0x00))
        log("datalink: FAVORITE 0x02/0xbf handle=0x%08x on=%b".format(handle, on))
        sendDuml(0x02, 0xbf, b.toByteArray(), receiverType = 0x01, receiverId = 0)
        var status: Int? = null
        val deadline = System.nanoTime() + 2_000_000_000L
        while (status == null && System.nanoTime() < deadline) {
            for (d in recvAll(200)) { status = findRespStatus(d, 0x02, 0xbf); if (status != null) break }
            if (status == null) sendAck()
        }
        // No leave here on purpose: the browse continues and the keep-alive resumes holding playback.
        log("datalink: FAVORITE reply status=${status?.let { "0x%04x".format(it) } ?: "none"}")
        return status == 0
    }

    /**
     * Count distinct media-path fields in a byte range by structure — the same `1a … 00 00 00 01`
     * "DCIM/" TLV [decodeComposite] anchors on, read by length. Used to gauge list growth while paging
     * and to vet reassembly, so it must not depend on any naming pattern: a custom Folder/File prefix
     * still counts because the whole path string is read, not matched.
     */
    private fun countMediaPaths(bytes: ByteArray): Int {
        val seen = HashSet<String>()
        var i = 0
        while (i < bytes.size) {
            val f = readPathField(bytes, i, sub = 1, prefix = "DCIM/")
            if (f != null) { seen.add(f.value); i = f.end } else i++
        }
        return seen.size
    }

    /**
     * The file list streams back as many DUML frames (cmdset 0x00 / cmd 0x26). Concatenating the raw
     * datagrams leaves each frame's 11-byte header + 2-byte CRC spliced into the byte stream, so any
     * DCIM path that straddles a frame boundary gets those bytes injected mid-string and the file
     * silently drops out of the grid — non-deterministically, depending on which record lands on a
     * boundary. Re-stitch just the 0x00/0x26 payloads, in order, so the manifest is contiguous before
     * we decode it. Walks the whole blob (not per-datagram) so a frame split across two UDP packets is
     * still reassembled.
     */
    private fun manifestBytes(raw: ByteArray, requestCtr: Int? = null): ByteArray {
        // The file list streams back as DUML data frames (cmd 0x00/0x27) whose payload is
        //   [10-byte sub-header][chunk]:  4A 01 .. .. <seq:u16le @6> 00 00, then the chunk bytes.
        // byte1==0x01 marks a data chunk; the control frames (4A 04.. start / 4A 03.. end) are 10
        // bytes of sub-header with no chunk. The chunks reassemble into the real manifest, which opens
        // with a u32-LE file count. We must strip the sub-header before concatenating — otherwise a
        // record whose path straddles a frame boundary gets those 10 bytes injected mid-path
        // (e.g. "DCIM/DJI_" + "J….001/…") and silently drops from the grid. Concatenate in arrival
        // order (not seq-sorted): on multi-page lists the seq counter restarts per page.
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i + 13 <= raw.size) {
            if ((raw[i].toInt() and 0xFF) != 0x55) { i++; continue }
            val len = ((raw[i + 1].toInt() and 0xFF) or ((raw[i + 2].toInt() and 0xFF) shl 8)) and 0x3FF
            if (len < 13 || i + len > raw.size) { i++; continue }
            // Match on the frame's DUML command, not on its payload prefix. The 11-byte header is
            // [55][len:2][crc8][target:2][id:2][type][set][cmd], so the command is right here — there
            // was never a reason to guess from the body. `4A 01` alone also matches subscription state,
            // which is why raising paramSubs to Mimo's 54 keys drowned the media stream in parameter
            // names (see paramSubs). Verified against the captures: in nano_45.bin and xtra_13.bin
            // every `4A 01` chunk arrives on 0x00/0x27 and nothing else does.
            val cmdSet = raw[i + 9].toInt() and 0xFF
            val cmdId = raw[i + 10].toInt() and 0xFF
            val plStart = i + 11; val plLen = len - 13
            // Sub-header byte 4 echoes the counter from the request that asked for this chunk (our
            // requests put it at the same offset). That is what lets one blob be split back into the
            // per-store answers it actually contains — see [collectStores].
            val ctrOk = requestCtr == null || (raw[plStart + 4].toInt() and 0xFF) == requestCtr
            if (cmdSet == 0x00 && cmdId == 0x27 && ctrOk &&
                plLen > 10 && (raw[plStart].toInt() and 0xFF) == 0x4A && (raw[plStart + 1].toInt() and 0xFF) == 0x01
            ) out.write(raw, plStart + 10, plLen - 10) // drop the 10-byte sub-header, keep the chunk
            i += len
        }
        val bytes = out.toByteArray()
        // A per-counter slice is a subset by construction, so the whole-blob comparison below would
        // always reject it. Callers handle an empty slice themselves.
        if (requestCtr != null) return bytes
        // Guard: only use the reassembled stream if it carries at least as many intact media-path
        // fields as the raw blob, so a model that streams the list some other way falls back to the
        // old whole-blob parse. (A path split across a frame boundary won't parse as a field, which is
        // exactly the straddling case reassembly fixes.)
        return if (countMediaPaths(bytes) >= countMediaPaths(raw) && bytes.isNotEmpty()) bytes else raw
    }

    /**
     * Split one collected blob into its per-store answers, so each file's `/v2?storage=` mount is
     * known from the query that returned it instead of guessed from its handle.
     *
     * Both queries are already sent — cursor `0x00000001` under counter 1 and `0x40000001` under
     * counter 2 — and the **cursor's top bit is the store selector**, which the Mimo captures settle:
     * on a Nano `0x00000001` returns the single dock-SD clip and `0x40000001` the 45 internal ones; on
     * an Xtra, 35 SD against 45 internal. Those map straight onto DJI's own `FileLocation`
     * (`SD_CARD=0`, `INTERNAL_STORAGE=1`), which is the same integer the HTTP mount wants. So the two
     * halves only ever needed telling apart, and the response counter does that for free — no extra
     * round trip, no HEAD probe, no handle-bit inference.
     *
     * Falls back to the merged parse (and the old per-file resolution) in the two cases where the
     * split can't be trusted: a camera that doesn't echo the counter, and one that answers both
     * queries with the same list — a single-store body, where there is nothing to attribute.
     */
    private fun collectStores(raw: ByteArray): List<CameraFile> {
        // An empty slice means that store answered with nothing — a camera with no card, or a query
        // that went unanswered. Decoding it anyway logs "no CompositePack records — falling back to
        // flat scrape" and runs a scrape over zero bytes, which reads in the log exactly like a decode
        // failure on a real manifest. Seen on a Nano whose SD query came back empty.
        fun sliceOf(ctr: Int): List<CameraFile> =
            manifestBytes(raw, requestCtr = ctr).takeIf { it.isNotEmpty() }?.let { decodeManifest(it) }
                ?: emptyList()
        val sd = sliceOf(SD_QUERY_CTR)
        val internal = sliceOf(INTERNAL_QUERY_CTR)
        val ambiguous = sd.isNotEmpty() && internal.isNotEmpty() &&
            sd.map { it.path }.toSet() == internal.map { it.path }.toSet()
        if ((sd.isEmpty() && internal.isEmpty()) || ambiguous) {
            val merged = decodeManifest(manifestBytes(raw))
            log("datalink: store split unavailable (${if (ambiguous) "both queries same list" else "no counter echo"})" +
                " — ${merged.size} files, storage resolved per file")
            return merged
        }
        log("datalink: per-store lists — SD ${sd.size}, internal ${internal.size} (no probing needed)")
        // Internal first: it is the larger store on every camera we have, so on the rare overlap the
        // kept copy is the one more likely to be right.
        return (internal.map { it.copy(storage = 1, storageKnown = true) } +
            sd.map { it.copy(storage = 0, storageKnown = true) }).distinctBy { it.path }
    }

    /** Distinct media paths seen so far — lets the collect loop stop once the list stops growing. */
    private fun distinctPaths(blob: java.io.ByteArrayOutputStream): Int =
        countMediaPaths(manifestBytes(blob.toByteArray()))

    private val primaryExts = setOf("MP4", "MOV", "JPG", "JPEG", "DNG", "OSV", "INSV", "HEIC")
    private val proxyExts = setOf("LRF", "LRV")

    /** Test seam: run the full raw-blob → frame-reassemble → decode pipeline on a captured manifest. */
    internal fun decodeManifestBlobForTest(rawBlob: ByteArray): List<CameraFile> =
        decodeManifest(manifestBytes(rawBlob))

    /** Test seam: the per-store split, so it can be checked against the handle-bit rule it replaces. */
    internal fun collectStoresForTest(rawBlob: ByteArray): List<CameraFile> = collectStores(rawBlob)

    /** Test seam: decode an already-reassembled CompositePack manifest (post frame-reassembly). */
    internal fun decodeCompositeForTest(manifest: ByteArray): List<CameraFile> = decodeComposite(manifest)

    /** Test seam: build a file-list request for a given counter + 4-byte handle cursor. */
    internal fun buildListCmdForTest(ctr: Int, cursor: Long): ByteArray = listCmd(ctr, cursor)

    /**
     * Decode the reassembled manifest. [decodeComposite] structurally handles every CompositePack
     * layout on the Osmo line (Nano, Xtra, Action 5/6, Pocket 3 — photos and videos, stock or
     * custom-prefixed). Only a blob with *no* CompositePack media-path field at all — a genuinely
     * different list format — reaches [parseFlat]'s loose scrape, and we dump the bytes so that
     * unknown layout can be cracked (paths/filenames only — no credentials or coordinates).
     */
    /**
     * Shout if two files claim the same delete handle.
     *
     * `0x00/0x28` addresses a file by handle, not by path, so a duplicated handle does not fail — it
     * deletes the *other* file and the grid then drops the cell that was asked for, which reads as
     * success. That is precisely what a photo inheriting the next video's handle used to do, so this is
     * the one invariant worth asserting at runtime rather than only in a test.
     *
     * Silent when healthy. Small lists are dumped in full: on a controlled 4-file card the handles are
     * the whole point, and the volume is trivial.
     */
    private fun warnOnHandleCollisions(files: List<CameraFile>) {
        val dupes = files.filter { it.handle != 0L }.groupBy { it.handle }.filter { it.value.size > 1 }
        for ((h, group) in dupes) {
            log("datalink: ⚠ HANDLE COLLISION 0x%08x shared by %s".format(h, group.joinToString { it.name }))
        }
        if (files.size <= 12) {
            for (f in files) log("datalink:   %-44s handle=0x%08x".format(f.name.take(44), f.handle))
        }
    }

    private fun decodeManifest(bytes: ByteArray): List<CameraFile> {
        val comp = decodeComposite(bytes)
        if (comp.isNotEmpty()) {
            log("datalink: decoded ${comp.size} CompositePack records " +
                "(${comp.count { it.resLabel != null }} fps, ${comp.count { it.proxyPath != null }} proxies, " +
                "${comp.count { it.deletable }} deletable, ${comp.count { it.sizeBytes > 0 }} sized)")
            warnOnHandleCollisions(comp)
            return comp
        }
        log("datalink: no CompositePack records — dumping manifest, falling back to flat scrape")
        dumpManifest(bytes)
        return parseFlat(bytes)
    }

    /** A length-delimited CompositePack field: its ASCII value and the offset just past it. */
    private class TlvField(val value: String, val end: Int)

    /**
     * Read a **path** field at [i] if one starts there: `1a [total:u8] 00 00 00 [sub:u8] <ascii>`,
     * where the ASCII value is `total-6` bytes (total counts the 6-byte header). Returns the value +
     * end offset only when the sub-type matches and the value is printable and carries [prefix]
     * (`DCIM/` for media, `MISC/` for the thumb). Pure tag→length→value; no content pattern.
     */
    private fun readPathField(bytes: ByteArray, i: Int, sub: Int, prefix: String): TlvField? {
        if (i + 6 > bytes.size || bytes[i] != 0x1A.toByte()) return null
        if (bytes[i + 2] != 0.toByte() || bytes[i + 3] != 0.toByte() || bytes[i + 4] != 0.toByte()) return null
        if ((bytes[i + 5].toInt() and 0xFF) != sub) return null
        val slen = (bytes[i + 1].toInt() and 0xFF) - 6
        if (slen < prefix.length || i + 6 + slen > bytes.size) return null
        val s = String(bytes, i + 6, slen, Charsets.ISO_8859_1)
        return if (s.startsWith(prefix) && s.all { it.code in 0x20..0x7E }) TlvField(s, i + 6 + slen) else null
    }

    /**
     * Structural decoder for DJI's **CompositePack** media manifest (the record format the delete path
     * `0x00/0x28` already trusts). Every field is length-delimited, so it's read tag → length → value
     * — never grepped, and never validated against a filename pattern. That's the whole point: the
     * decoder doesn't know or care what a DJI filename looks like, so custom Folder/File name prefixes
     * (the camera's *Naming Management* feature — `_A01`, `_DOA5`, `_OP3`) and any future convention
     * decode identically to stock, because the path and name arrive as raw length-delimited strings.
     *
     * Anchor: the **media-path field** — the most self-identifying thing in a record:
     * ```
     *   1a [total:u8] 00 00 00 01  <ascii "DCIM/…", total-6 bytes>     media path (this record)
     *   1a [total:u8] 00 00 00 02  <ascii "MISC/…">                    thumbnail path
     *   0d [len:u8]                <ascii "<name>.<ext>">              filename — read only for its ext
     * ```
     * The delete **handle** (`u32-LE @ head`) and video **byte size** (`u32-LE @ head+38`) hang off a
     * marker `03 ff 19 06` at `head+8` present on **video records only**; it's read opportunistically
     * (photos → handle 0 / size 0). Reproduces all 45 Nano and 13 Xtra files, photos included, and the
     * Pocket 3 / OA5 / OA6 lists. Empty when no media-path field is present → [decodeManifest] scrapes.
     */
    private fun decodeComposite(bytes: ByteArray): List<CameraFile> {
        // Locate every media-path field (one per record). Read by length; the 6-byte `1a … 00 00 00 01`
        // signature plus the `DCIM/` prefix makes a false hit in binary essentially impossible.
        data class Media(val pos: Int, val end: Int, val path: String)
        val medias = ArrayList<Media>()
        var i = 0
        while (i < bytes.size) {
            val f = readPathField(bytes, i, sub = 1, prefix = "DCIM/")
            if (f != null) { medias.add(Media(i, f.end, f.value)); i = f.end } else i++
        }
        if (medias.isEmpty()) return emptyList()

        val boundary = listBoundary(bytes, medias.size)
        if (boundary > 0) log("datalink: 2 manifest lists ($boundary + ${medias.size - boundary} records)")

        val byPath = LinkedHashMap<String, CameraFile>() // dedup by media path (the list can page-repeat)
        for (k in medias.indices) {
            val m = medias[k]
            val lo = if (k > 0) medias[k - 1].end else 0
            val hi = if (k + 1 < medias.size) medias[k + 1].pos else bytes.size
            val group = if (boundary > 0 && k >= boundary) 1 else 0
            byPath.putIfAbsent(m.path, resolveRecord(bytes, m.path, lo, hi).copy(group = group))
        }
        return withCmdHandles(byPath.values.toList())
    }

    /**
     * Fill [CameraFile.cmdHandle] by fitting `handle = base + seq*step` **per storage list** to the video
     * records that expose a handle, then applying it to every record in that list (photos included, which
     * is the point — they carry no handle of their own but favorite/group-expand need one).
     *
     * Data-driven on purpose: the namespace differs per camera *and* per store — Nano `0x40100000` step
     * `0x40`, Xtra SD `0x00040000` step `0x10`, Xtra internal `0x40040000` — so hardcoding one formula
     * silently produced garbage handles on the Xtra (group-expand returned 0 frames, photo favorite
     * failed). [step] is the most common positive delta over seq-adjacent pairs and [base] the most common
     * `handle - seq*step`, so a stray record can't skew the fit; an unfittable list just leaves 0.
     */
    private fun withCmdHandles(files: List<CameraFile>): List<CameraFile> {
        val fits = HashMap<Int, Pair<Long, Long>>()          // storage list -> (base, step)
        for ((group, list) in files.groupBy { it.group }) {
            val pts = list.filter { it.handle != 0L && it.seq > 0 }
                .map { it.seq to it.handle }.distinctBy { it.first }.sortedBy { it.first }
            if (pts.size < 2) continue
            val steps = pts.zipWithNext()
                .mapNotNull { (a, b) -> ((b.second - a.second) / (b.first - a.first)).takeIf { it > 0 } }
            val step = steps.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: continue
            val base = pts.map { it.second - it.first * step }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: continue
            fits[group] = base to step
            log("datalink: handle fit (list $group): base=0x${base.toString(16)} step=0x${step.toString(16)}")
        }
        if (fits.isEmpty()) return files
        return files.map { f ->
            val fit = fits[f.group]
            if (fit == null || f.seq <= 0) f else f.copy(cmdHandle = fit.first + f.seq * fit.second)
        }
    }

    /**
     * Index of the first record of the *second* per-storage list, or -1 if the manifest holds one list.
     *
     * With a card in, the camera concatenates two lists — **SD first, then internal** — each opening
     * with its own `[u32-LE count][u32-LE size][u32-LE ts]…` header. Proven by dumping the same camera
     * with and without a card: the no-card manifest is byte-identical to the mixed manifest's *second*
     * list. So the leading count covers only the first list, and the rest belong to the second.
     *
     * The record handles corroborate it (SD `0x0004xxxx` vs internal `0x4004xxxx`), but the handle
     * namespace isn't confirmed across models — and photos carry no handle at all — so the split comes
     * from the count, which every model writes.
     */
    private fun listBoundary(bytes: ByteArray, records: Int): Int {
        if (bytes.size < 4) return -1
        val declared = u32le(bytes, 0).toInt()
        return if (declared in 1 until records) declared else -1
    }

    /**
     * Build one [CameraFile] from its media-path field. The base name is the last path component; the
     * thumbnail (`1a…02`) and the extension (`0d`) are found by walking the record window `[lo,hi)`
     * and matching *this* base — field order varies across the line (name before path on the Nano,
     * after it on the Xtra), so association is by trailing base, not position.
     */
    private fun resolveRecord(bytes: ByteArray, mediaDir: String, lo: Int, hi: Int): CameraFile {
        val base = mediaDir.substringAfterLast('/')      // e.g. DJI_20260721121344_0015_D_OP3

        // Thumbnail path: the 1a…02 field whose value ends with this base.
        var thumb: String? = null
        var t = lo
        while (t < hi) {
            val f = readPathField(bytes, t, sub = 2, prefix = "MISC/")
            if (f != null && f.value.endsWith(base)) { thumb = f.value; break }
            t++
        }

        // Extension: the 0d filename field `<base>.<ext>` — read by its length, matched by base (no
        // pattern). A camera that omits the extension here simply yields "" (a visible gap, not a 404).
        var ext = ""; var proxyExt: String? = null
        var n = lo
        while (n < hi - 2) {
            if (bytes[n] == 0x0D.toByte()) {
                val len = bytes[n + 1].toInt() and 0xFF
                if (len > base.length && n + 2 + len <= bytes.size) {
                    val v = String(bytes, n + 2, len, Charsets.ISO_8859_1)
                    if (v.length > base.length + 1 && v.startsWith(base) && v[base.length] == '.') {
                        val e = v.substring(base.length + 1).uppercase()
                        if (e in VIDEO_EXTS || e in setOf("JPG", "JPEG", "DNG", "HEIC")) ext = e
                        else if (e in setOf("LRF", "LRV", "XRF")) proxyExt = e
                    }
                }
            }
            n++
        }

        // Every record — photo as well as video — hangs its handle off a marker at head+8. The marker's
        // first byte is the media type (`03` video, `00` photo) and its second is the star flag
        // (`ff` clear, `fe` set), so the four bytes run `[03|00] [ff|fe] 19 06`.
        //
        // This used to match `03 ff 19 06` only, on the belief that photos carried no handle. They do —
        // and because the scan runs forward, failing to match a photo's own marker meant running past it
        // into the NEXT record's, so a photo was handed a neighbouring file's handle. `0x00/0x28` takes
        // a handle rather than a path, so that does not fail: it deletes the wrong file. Verified
        // against Mimo manifests from a real Nano and Xtra — see PhotoHandleTest.
        var head = -1
        var m = lo
        while (m < hi - 4) {
            val kind = bytes[m].toInt() and 0xFF
            val star = bytes[m + 1].toInt() and 0xFF
            if ((kind == 0x03 || kind == 0x00) && (star == 0xFF || star == 0xFE) &&
                bytes[m + 2] == 0x19.toByte() && bytes[m + 3] == 0x06.toByte() && m >= 8
            ) { head = m - 8; break }
            m++
        }
        val hasMarker = head >= 0
        val isVideo = ext in VIDEO_EXTS

        // Photos carry no `03 ff 19 06` head; their size + pixel dimensions hang off their own
        // `[ff|fe] 19 06` marker — size @ marker-14, width/height @ marker+58/+62, all u32-LE. RE'd
        // against ground truth (moov/HTTP/JPEG bounds) over a controlled clip+photo set.
        var photoSize = 0L; var photoRes: String? = null
        if (!isVideo) {
            var q = lo
            while (q < hi - 3) {
                if ((bytes[q] == 0xFF.toByte() || bytes[q] == 0xFE.toByte()) &&
                    bytes[q + 1] == 0x19.toByte() && bytes[q + 2] == 0x06.toByte()
                ) {
                    val mk = q + 1                                   // index of the 19 06 pair
                    if (mk >= 14) photoSize = u32le(bytes, mk - 14)  // size is pre-marker → both layouts
                    // Pixel W×H sit AFTER the marker — only in the DJI-proper (Nano) layout. The CAM_
                    // family (Xtra/Action) put the path there, so leave it null → the preview falls back
                    // to decoding the JPEG bounds.
                    if (base.startsWith("DJI_") && mk + 66 <= bytes.size) {
                        val w = u32le(bytes, mk + 58).toInt(); val h = u32le(bytes, mk + 62).toInt()
                        if (w in 1..60000 && h in 1..60000) photoRes = "${w}x${h}"
                    }
                    break
                }
                q++
            }
        }

        val path = if (ext.isNotEmpty()) "$mediaDir.$ext" else mediaDir
        val thumbPath = (thumb ?: mediaDir.replaceFirst("DCIM/", "MISC/THM/")) + ".scr"
        val handle = if (hasMarker) u32le(bytes, head) else 0L
        // Media byte size = u32-LE at head-4 (marker-12) for video; photos read it off their own marker
        // (above). Ground-truth-verified byte-exact against the SD card (video) and HTTP (photo).
        val size = if (isVideo && hasMarker && head >= 4) u32le(bytes, head - 4) else photoSize
        val fps = if (isVideo && hasMarker) fpsInRange(bytes, head, hi) else null
        // Video duration in whole seconds = u16-LE at **marker-4** (= head+4), immediately before the
        // frameRate/resolution codes: `… [dur:u16-LE][fps:u8][res:u8] 03 ff 19 06 …`. Universal —
        // ground-truthed 16/16 on the Nano and 3/3 on the Xtra (incl. a 75 s clip). NB the Nano also
        // mirrors it at marker+26; that copy does NOT exist on the CAM_ family, so read it here.
        val durationSec = if (isVideo && hasMarker && head + 6 <= bytes.size)
            (bytes[head + 4].toInt() and 0xFF) or ((bytes[head + 5].toInt() and 0xFF) shl 8) else 0
        // ⭐ starTag and the resolution index, both u8, ground-truth-verified against the SD card.
        // The star byte sits 9 bytes past the record's `[ff|fe] 19 06` marker (videos use `03 ff 19 06`,
        // photos a `fe 19 06` variant); the resolution index is at marker-1 (video header only).
        val starred = starFlag(bytes, lo, hi)
        val resolution = if (isVideo && hasMarker && head + 7 < bytes.size)
            resolutionForIndex(bytes[head + 7].toInt() and 0xFF) else photoRes
        return CameraFile(
            path = path, thumbPath = thumbPath, storage = 0,
            resLabel = fps?.let { "${it}fps" }, proxyPath = proxyExt?.let { "$mediaDir.$it" },
            handle = handle, sizeBytes = size, starred = starred, resolution = resolution,
            durationSec = durationSec,
        )
    }

    /**
     * The manifest resolution byte (`marker-1`) is a **DJI-wide video-format index** (the Nano and
     * the Xtra/Action-5 emit the same codes for the same sizes — 95=2.7K 4:3, 103=4K 4:3), *not* the
     * SDK's `VideoResolution` enum (whose codes only partially/coincidentally overlap). The app enums
     * are inconsistent downstream copies; this map is built empirically from clips cross-referenced
     * against the SD card. Unknown → null → the app falls back to the MP4 `moov`.
     */
    /**
     * ⭐ favourite flag: the byte 9 past the record's `[ff|fe] 19 06` marker, 1 when starred.
     *
     * **Only trusted when it actually reads as a boolean.** That offset is a real flag on the Nano —
     * `nano_delete.bin`, captured while favourites were being tested, splits 19 zeros to 26 ones — but
     * on an Xtra the same offset lands on a *length* byte: its records run `1a <len> 00 00 00 01
     * DCIM/…`, so the byte reads 44 or 48, the length of the path that follows. Those records simply
     * carry a different field order, and reading a length as a flag is how a "starred" badge could
     * appear on every file at once. Anything other than 0 or 1 means the layout is not the one this
     * offset was derived from, so say "not starred" rather than guess.
     *
     * ⚠️ Consequence: favourites still do not survive a re-list on the Xtra. Reading them there needs
     * that record layout worked out — a manifest dumped with known favourites will show it, which
     * `dumpManifest` now makes a one-run job.
     */
    private fun starFlag(bytes: ByteArray, lo: Int, hi: Int): Boolean {
        var q = lo
        while (q < hi - 9) {
            if ((bytes[q] == 0xFF.toByte() || bytes[q] == 0xFE.toByte()) &&
                bytes[q + 1] == 0x19.toByte() && bytes[q + 2] == 0x06.toByte()
            ) return (bytes[q + 9].toInt() and 0xFF) == 1   // strictly 1; 44/48 on an Xtra is a length
            q++
        }
        return false
    }

    private fun resolutionForIndex(code: Int): String? = when (code) {
        10 -> "1920x1080"  // 1080p 16:9  (Xtra-verified)
        12 -> "1920x1440"  // 1080p 4:3
        16 -> "3840x2160"  // 4K 16:9
        45 -> "2688x1512"  // 2.7K 16:9
        95 -> "2688x2016"  // 2.7K 4:3
        103 -> "3840x2880" // 4K 4:3
        else -> null
    }

    /**
     * Dump the reassembled manifest as hex when a model's layout doesn't decode, so a single test run
     * gives us the actual bytes to reverse. Emitted in fixed-width rows with the byte offset, the way
     * we hand-decoded the Nano/Xtra records — enough to see the count header and where the filename /
     * extension / handle fields sit. Contents are paths and filenames only; the passphrase and GPS
     * never travel on this datalink, so this is safe for the shared "Save logs" file.
     */
    private fun dumpManifest(bytes: ByteArray) {
        log("datalink: --- manifest hex (${bytes.size}B), report this to crack the layout ---")
        var off = 0
        while (off < bytes.size) {
            val end = minOf(off + 32, bytes.size)
            val hex = StringBuilder(); val asc = StringBuilder()
            for (i in off until end) {
                val b = bytes[i].toInt() and 0xFF
                hex.append("%02x".format(b))
                if (i - off == 15) hex.append(' ')
                asc.append(if (b in 0x20..0x7E) b.toChar() else '.')
            }
            log("  %04x  %-65s %s".format(off, hex.toString(), asc.toString()))
            off = end
        }
        log("datalink: --- end manifest hex ---")
    }

    /** Fallback scrape: whole-blob regex, joining fields by filename base. No per-record structure or
     *  count check — used when [decodeManifest] can't validate the layout. Osmo Nano uses
     *  DCIM/DJI_001/DJI_…; 360 & Action 5 use CAM_. */
    private fun parseFlat(bytes: ByteArray): List<CameraFile> {
        val text = String(bytes, Charsets.ISO_8859_1)
        val pathRe = Regex("""DCIM/(?:DJI|CAM)_\d{3}/(?:DJI|CAM)_\d{14}_\d{4}_D""")
        val nameRe = Regex("""(?:DJI|CAM)_\d{14}_\d{4}_D\.[A-Za-z0-9]{2,4}""")
        val bestExt = HashMap<String, String>()
        val proxyByBase = HashMap<String, String>() // base -> LRF/LRV proxy extension, if listed
        for (m in nameRe.findAll(text)) {
            val base = m.value.substringBeforeLast('.')
            val ext = m.value.substringAfterLast('.').uppercase()
            val cur = bestExt[base]
            if (cur == null || (ext in primaryExts && cur !in primaryExts)) bestExt[base] = ext
            if (ext in proxyExts) proxyByBase[base] = ext
        }
        val thumbRe = Regex("""MISC/THM/(?:DJI|CAM)_\d{3}/(?:DJI|CAM)_\d{14}_\d{4}_D(?:\.\w{2,4})?""")
        val thumbByBase = HashMap<String, String>()
        for (m in thumbRe.findAll(text)) {
            val v = m.value
            thumbByBase[v.substringAfterLast('/').substringBeforeLast('.')] =
                if (v.contains('.')) v else "$v.scr"
        }
        log("datalink: media exts=${bestExt.values.toSortedSet()} proxies=${proxyByBase.values.toSortedSet()}")
        return pathRe.findAll(text).map { it.value }.toSortedSet().map { p ->
            val base = p.substringAfterLast('/')
            val ext = bestExt[base]
            val mediaPath = ext?.let { "$p.$it" } ?: p
            val thumb = thumbByBase[base] ?: (p.replaceFirst("DCIM/", "MISC/THM/") + ".scr")
            val fps = if (ext in VIDEO_EXTS) fpsFor(bytes, "$base.$ext") else null
            val proxy = proxyByBase[base]?.let { "$p.$it" }
            val namePos = ext?.let { indexOf(bytes, "$base.$it".toByteArray(Charsets.ISO_8859_1)) } ?: -1
            val handle = if (namePos >= 0) handleForName(bytes, namePos) else 0L
            val sizeBytes = if (namePos >= 0) sizeForName(bytes, namePos, ext in VIDEO_EXTS) else 0L
            CameraFile(path = mediaPath, thumbPath = thumb, storage = 0,
                resLabel = fps?.let { "${it}fps" }, proxyPath = proxy, handle = handle, sizeBytes = sizeBytes)
        }
    }

    /**
     * The record encodes fps as a rational num/den (den ∈ {1000,1001}) shortly before the filename
     * field — 25000/1000 = 25, 30000/1001 = 29.97. That's the only reliable per-file metadata here:
     * pixel dimensions aren't stored, and the enum block can't separate 4K from 2.7K (both same
     * enums), so resolution is read from the MP4 moov instead.
     */
    private fun fpsFor(bytes: ByteArray, fileName: String): Int? {
        val idx = indexOf(bytes, fileName.toByteArray(Charsets.ISO_8859_1))
        if (idx < 0) return null
        return fpsInRange(bytes, idx - 220, idx)
    }

    /** Scan [start,end) for the fps rational (num/den, den ∈ {1000,1001}); takes the last match, which
     *  is the one nearest the filename — i.e. this record's own enum block. See [fpsFor] for the format. */
    private fun fpsInRange(bytes: ByteArray, start: Int, end: Int): Int? {
        var fps: Int? = null
        var i = maxOf(0, start)
        val stop = minOf(end, bytes.size) - 8
        while (i <= stop) {
            val den = u32le(bytes, i + 4)
            if (den == 1000L || den == 1001L) {
                val num = u32le(bytes, i)
                if (num in 20_000L..250_000L) fps = Math.round(num.toDouble() / den).toInt()
            }
            i++
        }
        return fps
    }

    /**
     * Position of a manifest record's **head** for the file whose name starts at [namePos], or -1.
     * Every record carries a constant marker `03 ff 19 06` at `head + 8`; the head holds the delete
     * **handle** (u32-LE at +0) and, for videos, the byte **size** (+38). We anchor on the marker
     * nearest *before* the filename — robust across the **Nano** (`DJI_`, 361-byte records, handles at
     * `0x40104000`+ stepping `0x40`) and the **Xtra / Action family** (`CAM_`, 272-byte records,
     * handles at `0x40040000`+ stepping `0x10`); the earlier `0x40`-aligned scan mis-read the Xtra
     * (whose handles aren't `0x40`-aligned) and grabbed a stray dword → the camera rejected it (0xd6).
     * Verified against a pcap: the three handles Mimo sent in a delete mapped to the three files that
     * then vanished, and on the Xtra the marker-derived handle deletes where the aligned one did not.
     */
    private fun recordStart(bytes: ByteArray, namePos: Int): Int {
        var i = minOf(namePos, bytes.size) - 4
        val lo = maxOf(0, namePos - 400)
        while (i >= lo) {
            if (bytes[i] == 0x03.toByte() && bytes[i + 1] == 0xFF.toByte() &&
                bytes[i + 2] == 0x19.toByte() && bytes[i + 3] == 0x06.toByte()) {
                return if (i - 8 >= 0) i - 8 else -1
            }
            i--
        }
        return -1
    }

    /** The delete handle (record head) for the file at [namePos], or 0 → non-deletable. */
    private fun handleForName(bytes: ByteArray, namePos: Int): Long {
        val p = recordStart(bytes, namePos)
        return if (p >= 0) u32le(bytes, p) else 0L
    }

    /** Full media byte size from the record (+38, u32-LE) — **video records only** (photos lay out
     *  differently); 0 when unavailable. Replaces an HTTP HEAD for the common case. */
    private fun sizeForName(bytes: ByteArray, namePos: Int, isVideo: Boolean): Long {
        if (!isVideo) return 0L
        val p = recordStart(bytes, namePos)
        return if (p >= 0 && p + 42 <= bytes.size) u32le(bytes, p + 38) else 0L
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }



    private fun appDeviceInfo(): ByteArray {
        // "\x00APP" + 37*00 + 02 + 8*00 + 02 08 + 10*00  (62 bytes) — mirrors file_list.py.
        val b = ByteArray(62)
        b[1] = 'A'.code.toByte(); b[2] = 'P'.code.toByte(); b[3] = 'P'.code.toByte()
        b[41] = 0x02; b[50] = 0x02; b[51] = 0x08
        return b
    }

    /**
     * Set the camera clock + timezone to the phone's (DUML **0x00/0x6a**, from a Mimo capture):
     * `01 00` · **unix seconds** (u64-LE) · **UTC offset minutes** (u16-LE, signed) · `len:u8` ·
     * IANA tz id ASCII (e.g. `Europe/Madrid`). Fire-and-forget on connect so recorded file timestamps
     * match the phone. Best-effort — a failure never blocks the media list.
     */
    private fun syncTime() {
        val nowMs = System.currentTimeMillis()
        val tz = java.util.TimeZone.getDefault()
        val nowSec = nowMs / 1000L
        val offMin = tz.getOffset(nowMs) / 60000                  // signed minutes east of UTC
        val tzId = tz.id.toByteArray(Charsets.US_ASCII)
        val b = java.io.ByteArrayOutputStream()
        b.write(0x01); b.write(0x00)
        for (k in 0 until 8) b.write(((nowSec ushr (k * 8)) and 0xFF).toInt())   // unix seconds, u64-LE
        b.write(offMin and 0xFF); b.write((offMin shr 8) and 0xFF)              // UTC offset (min), u16-LE
        b.write(tzId.size); b.write(tzId)
        // Receiver 0x28 = (id 1 << 5) | type 0x08 — the system/RTC subsystem (same one 0x00/0x99
        // subscribes to). Mimo addresses SetTime here; the file/media receiver (0x01) silently drops it.
        sendDuml(0x00, 0x6a, b.toByteArray(), receiverType = 0x08, receiverId = 1)
        recvAll(300); sendAck()
        log("datalink: time synced → ${tz.id} (${offMin}min), unix $nowSec")
    }

    /**
     * A `0x00/0x99` status subscription, byte-for-byte as Mimo sends it.
     *
     *     [02 02 00 00][subId u32-LE][00 00 00][innerLen u16-LE][nameLen u16-LE][name][00 00 00 00]
     *     innerLen = nameLen + 6                                  name is NOT padded
     *
     * Verified against three keys of different length in reference/captures/wifi/mimo_nano_delete.pcap
     * (`camcap_base` 30 B, `camcap_fov` 29 B, `camcap_video_format` 38 B) — the frame length tracks the
     * key length exactly, so there is no fixed-width field here.
     *
     * We previously padded every name to 20 bytes and carried an extra byte before it, which put the
     * name at offset 17 rather than 15 and made innerLen 26 for any short key. An Xtra accepts that and
     * pushes state anyway; a Nano is the reason to care, since it never sent us the per-store storage
     * frame (0x02/0xdc) that it does send Mimo.
     */
    private fun subscription(name: String, subId: Int): ByteArray {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val innerLen = nameBytes.size + 6
        return byteArrayOf(0x02, 0x02, 0x00, 0x00) +
            le32(subId) + byteArrayOf(0, 0, 0) +
            byteArrayOf((innerLen and 0xFF).toByte(), ((innerLen shr 8) and 0xFF).toByte()) +
            byteArrayOf((nameBytes.size and 0xFF).toByte(), ((nameBytes.size shr 8) and 0xFF).toByte()) +
            nameBytes + byteArrayOf(0, 0, 0, 0)
    }

}
