package dev.konraditurbe.osmosis.camera

import dev.konraditurbe.osmosis.ble.CameraModel.LegacyActionAlbum
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.StorageRules
import dev.konraditurbe.osmosis.core.AlbumJpeg
import dev.konraditurbe.osmosis.core.AlbumVideo
import dev.konraditurbe.osmosis.dcf.ActionFileTransfer
import dev.konraditurbe.osmosis.dcf.AlbumFrames
import dev.konraditurbe.osmosis.dcf.DcfAddressing.Route
import dev.konraditurbe.osmosis.dcf.DcfRecords
import dev.konraditurbe.osmosis.dcf.DcfTransferProbe
import dev.konraditurbe.osmosis.duml.DjiCrc
import dev.konraditurbe.osmosis.net.ActionHttpMedia
import dev.konraditurbe.osmosis.net.DumlSession
import dev.konraditurbe.osmosis.net.DumlTransport
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A media session with an **Osmo camera** — registration, the CompositePack media list (`0x00/0x26`)
 * and every per-file command (delete, favourite, burst expand, highlights).
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
    /**
     * `--ez probe4a true` — run [probeTransferKinds] on an Action-1 manifest.
     *
     * **Default off, deliberately.** It ran unattended in the first probe build, and that build is the
     * one where a tester's Action 1 started rebooting. The probe is not established as the cause — the
     * same build also made the grid non-empty for the first time, so preview became reachable in the
     * same step — but sending speculative transfer frames to firmware is exactly the kind of thing that
     * could do it, and it is not a tester's job to run an experiment we can't yet exonerate.
     */
    private val probeTransfers: Boolean = false,
    private val legacyAlbum: LegacyActionAlbum? = null,
    restoreAfterRejoin: Int? = null,
) : DumlSession(log, port, tcpPoke, isDrone = false) {

    private val httpViaWorkMode = legacyAlbum == LegacyActionAlbum.ACTION_1
    /** List frames must pass their CRC16 too. See [forEachListFrame]. */
    private val strictListFrames = legacyAlbum == LegacyActionAlbum.ACTION_1
    private var dcfRoute = if (legacyAlbum == LegacyActionAlbum.ACTION_3) Route.V2 else Route.V1
    init { logWorkMode = httpViaWorkMode }

    /** The work mode that opened `:80` this session, held for browse and restored on teardown. */
    @Volatile private var httpWorkMode: Int? = null

    /** What the camera was in before we touched it, for [close] to put back. */
    @Volatile private var originalWorkMode: Int? = null
    @Volatile private var inheritedWorkMode = restoreAfterRejoin
    internal val captureModeToRestore: Int?
        get() = if (httpWorkMode != null) originalWorkMode else inheritedWorkMode
    private val albumLock = Any()
    @Volatile private var closing = false

    @Volatile override var httpServing: Boolean? = null
        private set

    /**
     * Remaining hex-dump budget for this session, in bytes of manifest.
     *
     * The raw file-list blob is dumped for **every** camera, verified or not: a failed decode has
     * always dumped, which is useless for the case that actually matters — a new camera whose
     * manifest decodes *fine* but whose bytes we have never seen. A Pocket 4 Pro listed 46 files
     * perfectly and left us with no fixture, because success was silent.
     *
     * The budget is the only thing keeping that affordable. A 45-record manifest is ~37 KB, which is
     * ~1200 log lines: fine once as a diagnostic, ruinous on every page of an infinite scroll. Two
     * pages' worth is enough to reconstruct a fixture and see how paging changes the blob; past that
     * the log stops being something a tester can send. Lines only reach a file when "Save logs" is
     * on, so an ordinary run pays nothing but logcat.
     */
    private var dumpBudgetBytes = 80_000

    /** Ceiling on a single dump, budgeted or not — the head carries the layout, the tail is noise. */
    private val DUMP_MAX_BYTES = 64_000

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
     * drops without one. We used to send `0x02/0x8E` ~3x/s, which knocked the camera OUT of playback —
     * not because the command is forbidden, but because it is a keyed parameter GET and polling one
     * while holding playback is what breaks the mode. (Mimo does send it, just not where we did: a
     * capture of it deleting on an **Xtra** carries 486 of them and never enters playback at all,
     * while the same operation on a **Nano** enters playback once and sends zero. Per model, not
     * universal.) Removing it left no beat at all, and the mode then fell over
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
    // fresh-session fallback (favorite, highlights). So the mode flapped on/off underneath operations
    // that assume it is held, which is what made favourites and deletes race and let a Pocket 3 sit in
    // capture mode — recording — throughout a transfer. Now: confirm on entry, retry, hold for the
    // whole session, and release only on teardown.
    @Volatile private var playbackHeld = false
    /** Set by [close] so the keep-alive thread sends the leave before the socket goes. */
    @Volatile private var releasePlaybackOnExit = false
    @Volatile private var playbackReleased = false

    // Lazy grid pagination — ONE CURSOR PER STORE, because a page request is a PAIR of queries and
    // each one selects a store: counter 1 carries an SD handle, counter 2 an internal handle (the
    // cursor's 0x40000000 bit is the store selector, which is why the newest-page selectors are
    // 0x00000001 and 0x40000001). [seenKeys] dedups across pages, [moreAvailable] = at least one store
    // has an older page.
    //
    // This used to be a single cursor, seeded as the oldest handle at/above 0x40000000 and sent on
    // counter 2, while counter 1 stayed pinned at 0x00000001 forever. So paging walked the INTERNAL
    // store only: SD was re-asked for its newest page every time and the identical answer was deduped
    // away. Two consequences, the second silent —
    //
    //   * on any camera, SD content past the newest page was unreachable;
    //   * on an SD-only body (Action 4, Pocket 3) every handle clears the bit, so the cursor seeded to
    //     0, `moreAvailable` was false however full the card, and the grid simply stopped at 45.
    //
    // The 0x40000000 floor that fitted the old cursor is gone with it. It existed to keep SD handles
    // from derailing a walk through the internal namespace; now each cursor is confined to its own
    // store's slice, so the namespaces cannot mix and nothing needs a hardcoded base. That is also what
    // makes this work on a camera whose handles are 0x0004xxxx — the same code, no per-model constant.
    private val SD_STORE = 0
    private val INTERNAL_STORE = 1
    /** Newest-page selector per store: the value the camera reads as "start from the top". */
    private val NEWEST_SD = 0x00000001L
    private val NEWEST_INTERNAL = 0x40000001L

    /**
     * Records requested per file-list query — the `2d` at byte 14 of every `0x00/0x26` we build.
     *
     * Not a number we picked: Mimo's own `BaseFetchData.mCount` is 45, and byte 14 is `0x2d` in all 36
     * list requests across four Mimo captures. Named here so the short-page end-of-list test below is
     * obviously tied to what we actually asked for.
     */
    private val PAGE_SIZE = 45
    /** Records to ask for: [PAGE_SIZE] unless `--ei pagesize N` (DEBUG) overrides it. */
    private val pageSize: Int get() = debugPageSize.takeIf { it in 1..255 } ?: PAGE_SIZE

    /**
     * What one store's answer to a page query looked like on the wire, kept by [collectStores] for the
     * page step that follows ([stepPagination]) and for the evidence log.
     *
     * [declared] is the manifest's own `u32-LE` header count — the number of records this page carries
     * (45 on every full page in the fixtures; `0` on an Action 5/6, which writes no count). [records]
     * is how many media-path fields were actually in the bytes. A page with fewer records than it
     * declared was cut short in transit — the collector stopped listening before the last chunks
     * landed — and its tail is missing, which on a list ordered newest-first is the oldest files.
     *
     * [endMarker] is the `0c 01` TLV that sits immediately before the last record's `0d` name field in
     * every captured final page (xtra_13, oa4_6, oa6_*, op3_*) and in none of the full 45-record ones
     * (nano_45, oa4_45, op4_45) — Mimo's `isPageLastFile`. It is the camera saying "nothing older".
     */
    internal class SliceInfo(val declared: Int, val records: Int, val endMarker: Boolean, val ended: Boolean) {
        /**
         * Fewer records than the header promised: the answer was truncated, not short.
         *
         * [declared] is only trusted when it is a plausible per-page count. Not every model writes the
         * count at offset 0 — several fixtures (`oa4_6`, `nano_delete`, `op3_29`) and the live Xtra carry
         * a large non-count value there (`0x1310…`), which would otherwise read as "declared 300 million,
         * 41 arrived → truncated" on every page. A real page count never exceeds the records we asked for.
         */
        val incomplete: Boolean get() = declared in 1..COUNT_MAX && records < declared
    }
    private val lastSlices = HashMap<Int, SliceInfo>()

    /**
     * How far the camera has got in answering one list query, read off its `0x00/0x27` frames in
     * arrival order by [answerState]. It decides when that query's transfer may be released.
     *
     *  - [SILENT]: nothing under this counter yet.
     *  - [WAITING]: `4A 03` state frames and no data. An Action 6 answered its card this way, 15 state
     *    frames across two queries and never a chunk, while the same pass listed 45 internal files
     *    (issue #53). A drone raises this frame before its data and waits for a `4A 02` proceed.
     *  - [EMPTY]: a `4A 04` and no data, the answer of a store with nothing mounted (Nano, Xtra).
     *  - [STREAMING]: data chunks, the last one not in yet.
     *  - [DONE]: the chunk flagged final arrived, or every record the header declared is in.
     *
     * A state frame is not an end marker. An Action 6 raises one before a store's data on most older
     * pages, and the read that took it for "this answer is closed" returned the internal store with 31
     * of 45 records, and then with none, on the same run that finally listed its card (issue #53).
     */
    internal enum class Answer { SILENT, WAITING, EMPTY, STREAMING, DONE }

    /**
     * When to release one list query's transfer, decided once per collect step. Each store's query has
     * its own: [ctr] is the request counter, 1 for the SD query and 2 for the internal one.
     *
     * The release is the `4A 04` frame this file called the "trigger" and sent on a timer, one step
     * after the SD query whatever had come back, and never for the internal one. In Mimo it belongs to
     * a transfer: it carries that query's counter and goes out once its data is in
     * (`mimo_nano_delete.pcap`: query 2.42 s, data 2.47 s, release 2.66 s), and only then is the next
     * store asked.
     *
     * [next] keeps the old timing when the answer is already there, so nothing changes for a store that
     * answers at once: the release never goes out before [minStep]. Past that it waits for the answer
     * to finish. A stream that loses its final chunk is released after [quietSteps] steps without a new
     * record, a camera that only raises state frames is told to proceed once and given [proceedWait]
     * steps to start sending, and one that says nothing at all is released at [maxSteps].
     *
     * [hold] false is for a store with nothing older to give (never populated, or past its end marker):
     * it is released on [minStep] whatever it says, which is the timing every store had before.
     *
     * The proceed was first sent on the drone's precedent. An Action 6 then delivered its card's list
     * after it on every older page (issue #53); whether the data would have come without it is not known.
     *
     * [RESEND][Action.RESEND] is for a stream that went quiet with a known hole: [next]'s `gap` says the
     * answer's own header declares more than arrived. It is asked for up to [MAX_RESENDS] times, a
     * quiet step apart, before the transfer is released. Retail AC101 firmware sends every list chunk
     * without waiting for an ACK, then keeps the transfer open for about six seconds (a one-second push
     * timer, five pushes) and resends the chunks an ACK lists as missing (`DjiTransSrv_DoResend`,
     * "ReSend List seq"). A 16-file Action 1 list lost its second chunk and showed an empty grid.
     */
    internal class ListTransfer(
        val ctr: Int,
        private val minStep: Int,
        private val quietSteps: Int,
        private val proceedWait: Int,
        private val maxSteps: Int,
        private val hold: Boolean = true,
    ) {
        enum class Action { WAIT, PROCEED, RESEND, RELEASE }

        var released = false
            private set
        private var proceededAt = -1
        private var lastRecords = -1
        private var quiet = 0
        private var resends = 0

        /**
         * [step] counts collect steps since the query went out; [records] is this store's records in so
         * far; [gap] is true when the answer is known to be missing a chunk the camera could resend.
         */
        fun next(step: Int, answer: Answer, records: Int, gap: Boolean = false): Action {
            if (released) return Action.WAIT
            quiet = if (records == lastRecords) quiet + 1 else 0
            lastRecords = records
            if (hold && answer == Answer.WAITING && proceededAt < 0) {
                proceededAt = step
                return Action.PROCEED
            }
            if (hold && gap && answer != Answer.DONE && quiet >= quietSteps && resends < MAX_RESENDS) {
                resends++
                quiet = 0
                return Action.RESEND
            }
            val due = !hold || when (answer) {
                Answer.DONE, Answer.EMPTY -> true
                Answer.STREAMING -> quiet >= quietSteps
                Answer.WAITING -> step - proceededAt >= proceedWait
                Answer.SILENT -> step >= maxSteps
            }
            if (step < minStep || !due) return Action.WAIT
            released = true
            return Action.RELEASE
        }

        companion object {
            /** Resend requests per transfer. Each asks for one chunk, so a list can be up to this many chunks short. */
            const val MAX_RESENDS = 3
        }
    }

    // Request counters we put at byte 4 of each 0x00/0x26, echoed back at sub-header byte 4 of every
    // 0x00/0x27 chunk answering it. Counter 1 is the SD query, counter 2 the internal one; each carries
    // its store's newest-page selector on the first load and that store's page cursor thereafter.
    private val SD_QUERY_CTR = 1
    private val INTERNAL_QUERY_CTR = 2
    // The SD query's transfer control frames, both carrying its counter at byte 4. They are the `0x4a`
    // family a drone uses (DroneManifest.listAck / transferGo, pinned there against DJI Fly's bytes):
    // `4A 04` releases a transfer, `4A 02` answers a state frame raised before any data.
    internal val SD_RELEASE = hex("4a040e1001000000000001000000")
    internal val SD_PROCEED = hex("4a020f100100000000000000000000")
    /** The release and proceed frames for request [ctr]: the SD ones with that counter at byte 4. */
    internal fun listRelease(ctr: Int) = SD_RELEASE.copyOf().also { it[4] = ctr.toByte() }
    internal fun listProceed(ctr: Int) = SD_PROCEED.copyOf().also { it[4] = ctr.toByte() }

    /**
     * The `4A 02` ACK for request [ctr] that confirms chunks before [chunk] and lists [chunk] as missing:
     * `[confirm:u32 @10][missing pairs:u8 @14][seq:u32][count:u32]`. Retail AC101 V01.10.00.40 reads
     * those fields in `DjiTransSrv_MainProc` (`0x47db68` to `0x47dbf0`) and queues the pair for
     * `DjiTransSrv_DoResend`. The proceed above is the same frame with nothing confirmed or missing.
     */
    internal fun listResend(ctr: Int, chunk: Long): ByteArray =
        java.nio.ByteBuffer.allocate(23).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put(0x4A).put(0x02).putShort((0x1000 or 23).toShort()).putShort(ctr.toShort()).putInt(0)
            putInt(chunk.toInt()).put(1).putInt(chunk.toInt()).putInt(1)
        }.array()
    // What each store's query last came to ([answerState] over the page's own frames), and the stores
    // that have nothing older to give: past their end marker, or given up on. See [unansweredStores].
    private val lastAnswers = HashMap<Int, Answer>()
    private val storeEnded = HashSet<Int>()
    private var unansweredPages = 0
    // Frame limit for a burst/interval group-expand query (byte 14). Generous — covers long interval
    // timelapses; any spill into older files is filtered out by the group base name. See expandBurstGroup.
    private val GROUP_EXPAND_LIMIT = 120
    private var sdCursor = NEWEST_SD
    private var internalCursor = NEWEST_INTERNAL
    private val seenKeys = HashSet<String>()


    /** AC101's general ping renews its app-link timer. The socket owner sends it during lists,
     * HTTP checks, idle browsing and file transfers. A fresh handshake starts a new timer. */
    private fun startAction1Heartbeat() {
        val payload = byteArrayOf(1, 0) // Mimo's Wi-Fi link and foreground state
        var lastPing = System.nanoTime()
        var count = 0
        var maxGapMs = 0L
        fun ping() {
            val now = System.nanoTime()
            val elapsed = now - lastPing
            if (closing || (count > 0 && elapsed < 500_000_000L)) return
            sendDuml(0x00, 0x00, payload, receiverType = 1, receiverId = 0, cmdType = 0)
            lastPing = now
            count++
            maxGapMs = maxOf(maxGapMs, elapsed / 1_000_000L)
            if (count == 1 || count % 60 == 0 || elapsed >= 2_000_000_000L)
                log("datalink: Action 1 Wi-Fi heartbeat count=$count maxGap=${maxGapMs}ms")
        }
        tx.onReceivePoll = ::ping
        ping()
    }

    /**
     * Bring the datalink up ([openDatalink]) and then register: device-info, register, gimbal-init and
     * the param subscriptions. Leaves the socket open and the send sequence synced on success.
     *
     * Shared by [fetchFileList] and the delete flow — the delete re-runs this so it rides the same
     * fresh, in-window session the list does.
     */
    private fun openAndRegister(ip: String, subscribe: Boolean = true): Boolean {
        if (closing || !openDatalink(ip) || closing) return false

        // AC101 routes the album commands directly to the camera. Its firmware logs route failures
        // for the newer registration, subscriptions and gimbal init (receivers 8/2, 8/1 and 3/0).
        // openDatalink already established and acknowledged its UDP session.
        if (legacyAlbum == LegacyActionAlbum.ACTION_1) {
            startAction1Heartbeat()
            // A restore sent on the old Wi-Fi network may have missed the camera. Keep ownership
            // only if the fresh session confirms that the camera is still in our album mode.
            if (inheritedWorkMode != null) reconcileRejoinedMode(readWorkMode() ?: workModeReported)
            return true
        }

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

        // Enter playback BEFORE asking for the list.
        //
        // A Pocket 3 that is still in capture declares the right file count and then serves only part
        // of the records: `6 files (batch 0)` followed by two decoded, both the oldest, after a 4 s
        // wait for data that never came. Once it is in playback the same query returns everything. The
        // official app does it in this order too — playback first, list second.
        //
        // Safe on the bodies that do not need it: entering here is the same call the keep-alive would
        // make a moment later, it is idempotent, and [startKeepAlive] skips it when it already holds.
        // Runs on this thread, which is the session thread and the only one sending right now, so the
        // sequence-interleaving rule that puts this on the keep-alive thread still holds.
        //
        // Not on a work-mode camera: an Action 1 answers 0x02/0x0c with 0xe0 every time, and the
        // 0x01/0x01 fallback after it never moved one either. Its list works without playback.
        if (!httpViaWorkMode) enterPlaybackConfirmed()

        // No retry on an empty answer, deliberately. There WAS one, added for a log where a camera
        // handshook, streamed status and served no media across several connects — but the cause was
        // ours: the tester tapped connect three times, each tap opened a datalink while the last was
        // still live, and the camera was holding OUR previous session. Concurrent sessions are stopped
        // at the source now, and no log since has contained an empty manifest.
        //
        // The retry could not tell "answered nothing" from "answered, and the card is empty", so its
        // one live effect was to make an empty camera pay a re-registration and then be told to power
        // itself off and on. If a camera ever does wedge for some other reason, a log saying so beats a
        // retry that hides it — the sequence-channel mismatch is still reported by [openDatalink].
        val files = queryNewestPage()

        // Seed lazy-pagination state: dedup set + one cursor per store, each the oldest handle in that
        // store's slice of this page. A store with nothing on it keeps its newest-page selector and
        // simply never advances; the other store still pages.
        seenKeys.clear(); seenKeys.addAll(files.map { pageKey(it) })
        val sdOldest = oldestHandle(files, SD_STORE, below = Long.MAX_VALUE)
        val intOldest = oldestHandle(files, INTERNAL_STORE, below = Long.MAX_VALUE)
        sdCursor = sdOldest ?: NEWEST_SD
        internalCursor = intOldest ?: NEWEST_INTERNAL
        moreAvailable = storeHasOlderPage(storeSlice(files, SD_STORE).size, sdOldest != null, lastSlices[SD_STORE]) ||
            storeHasOlderPage(storeSlice(files, INTERNAL_STORE).size, intOldest != null, lastSlices[INTERNAL_STORE])
        if (debugPageForce) moreAvailable = sdOldest != null || intOldest != null
        noteEndedStores(files, sdOldest != null, intOldest != null)
        log("datalink: parsed ${files.size} media files (newest page; ${cursorLog()}; more=$moreAvailable)")
        logPageEvidence(files, files, NEWEST_SD, NEWEST_INTERNAL)
        if (legacyAlbum == LegacyActionAlbum.ACTION_1) {
            // AC101 has no HTTP route to probe. Mimo sends its originals through the datalink handler,
            // and port 80 refused every connection in every tester session (issue #32), in RECORD and
            // after 30 s in TRANSCODE alike. The check cost about 32 s a connect and never succeeded.
            httpServing = false
            log("datalink: Action 1 album uses the datalink for originals, thumbnails and stills (no HTTP check)")
        } else if (legacyAlbum != null && files.any { it.isIndexed }) {
            // Prefer small originals. A missing or stale item should not hide a valid route.
            val samples = files.filter { it.isIndexed }.sortedBy { it.sizeBytes }.take(2)
            val probe = ActionHttpMedia(ip, log)
            val route = checkAlbumRoute(samples, listOf(dcfRoute), probe).route
            httpServing = route != null
            if (route != null) dcfRoute = route
            log("datalink: HTTP media route: ${route ?: "no original verified"}")
        }
        onFetchProgress?.invoke(100)
        return files.map { if (it.isIndexed) it.copy(dcfRoute = dcfRoute) else it }
    }

    private data class AlbumCheck(val route: Route?, val peerActive: Boolean)

    private fun checkAlbumRoute(samples: List<CameraFile>, routes: List<Route>, probe: ActionHttpMedia,
                                retry: Boolean = false): AlbumCheck {
        var lastRx = System.nanoTime()
        var lastPresence = lastRx
        val deadline = lastRx + (if (retry) 30L else 12L) * 1_000_000_000L
        var peerActive = true
        val route = probe.checkWhileConnected(samples, routes, retry) {
            if (closing || !tx.isOpen || System.nanoTime() >= deadline) return@checkWhileConnected false
            val packets = recvAll(200)
            parseStatus(packets)
            val now = System.nanoTime()
            if (packets.isNotEmpty()) lastRx = now
            sendAck()
            if (now - lastPresence >= 1_000_000_000L) {
                holdPlayback()
                lastPresence = now
            }
            peerActive = now - lastRx <= 3_000_000_000L
            if (!peerActive) log("datalink: camera stopped answering during the HTTP check")
            peerActive
        }
        return AlbumCheck(route, peerActive && !closing && tx.isOpen)
    }

    private fun reconcileRejoinedMode(current: Int?) {
        val restore = inheritedWorkMode ?: return
        if (current == null) return
        if (current == WorkMode.TRANSCODE) {
            originalWorkMode = restore
            httpWorkMode = WorkMode.TRANSCODE
        }
        inheritedWorkMode = null
    }

    /** `0x02/0x11`: the camera's work mode, or null if it didn't answer. */
    private fun readWorkMode(): Int? {
        sendDuml(0x02, 0x11, ByteArray(0), receiverType = 0x01, receiverId = 0)
        val deadline = System.nanoTime() + 1_500_000_000L
        while (!closing && tx.isOpen && System.nanoTime() < deadline) {
            val dg = recvAll(150)
            parseStatus(dg)
            val reply = findReply(dg, 0x02, 0x11)
            if (reply != null) {
                val mode = WorkMode.parseGetReply(reply)
                if (mode == null) log("datalink: GetMode reply not understood: ${reply.hexStr()}")
                sendAck()
                return mode
            }
            sendAck()
        }
        log("datalink: GetMode (0x02/0x11) — no reply")
        return null
    }

    /**
     * `0x02/0x10`: switch the work mode. Returns the reply's status byte (0 = ok), or null when nothing
     * came back within 3 s. That timeout is DJI Fly's own for this command: a mode switch is slow.
     */
    private fun setWorkMode(mode: Int): Int? {
        sendDuml(0x02, 0x10, WorkMode.setPayload(mode), receiverType = 0x01, receiverId = 0)
        val deadline = System.nanoTime() + 3_000_000_000L
        var beat = 0
        while (!closing && tx.isOpen && System.nanoTime() < deadline) {
            val dg = recvAll(150)
            parseStatus(dg)
            val reply = findReply(dg, 0x02, 0x10)
            sendAck()
            reply?.firstOrNull()?.let { return it.toInt() and 0xFF }
            if (++beat % 6 == 0) holdPlayback()     // the ~1 Hz presence beat, so the session holds
        }
        return null
    }

    private fun ByteArray.hexStr() = joinToString("") { "%02x".format(it) }

    /**
     * One `0x00/0x88` presence beat, sent from inside a collect loop.
     *
     * Playback is dropped about a second after it is set unless the app keeps beating, and the
     * keep-alive thread that normally does that ([startKeepAlive]) only takes over once the fetch has
     * returned. A fetch short enough to finish inside the grace period hid this; adding the SD retry
     * pushed it past nine seconds, and the camera fell out of playback and back in — a visible flash on
     * the body, and a window where a store-specific query could be answered from the wrong mode. Each
     * collect batch is a ~700-800 ms receive, so one beat per batch is the documented ~1 Hz cadence.
     */
    private fun holdPlayback() {
        if (legacyAlbum != LegacyActionAlbum.ACTION_1)
            sendDuml(0x00, 0x88, APP_PRESENCE, receiverType = 0x08, receiverId = 1)
    }

    /**
     * The fast initial load: SD query, release of its transfer, internal query, for the newest ~45
     * files of each store. Older pages are lazy — the grid's infinite scroll calls [fetchNextPage].
     */
    private fun queryNewestPage(): List<CameraFile> {
        // Byte-identical to the proven newest-page blob (PaginationTest pins it); built through listCmd
        // so a debug page size reaches the first page too.
        sendDuml(0x00, 0x26, listCmd(SD_QUERY_CTR, NEWEST_SD), receiverType = 0x01, receiverId = 0)
        val blob = java.io.ByteArrayOutputStream()
        storeEnded.clear(); unansweredPages = 0
        collectPage(blob, NEWEST_INTERNAL, batches = 15, sdHold = true, internalHold = true) { batch, count, changed ->
            holdPlayback()
            if (changed) log("datalink: $count files (batch $batch)")
            onFetchProgress?.invoke((55 + batch * 6).coerceAtMost(95))
        }
        return collectStores(retrySdIfNotReady(blob))
    }

    /**
     * Collect one two-store page into [blob], batch-paced (first load, fresh-session page). The SD
     * query is already out. Its transfer is released once its answer is in, then the internal store is
     * asked with [internalCursor] and its transfer run the same way; the page is over when that one is
     * released. With stores that answer at once: SD release on batch 1, internal query on batch 2,
     * internal release and the end of the page on batch 3, as it always was.
     */
    private fun collectPage(
        blob: java.io.ByteArrayOutputStream, internalCursor: Long, batches: Int,
        sdHold: Boolean, internalHold: Boolean, perBatch: (batch: Int, count: Int, changed: Boolean) -> Unit = { _, _, _ -> },
    ) {
        val sd = transferByBatch(SD_QUERY_CTR, sdHold)
        val internal = transferByBatch(INTERNAL_QUERY_CTR, internalHold)
        var internalAskedAt = -1
        var internalFrom = 0
        var lastCount = -1
        val frames = AlbumFrames(tx.sessionId)
        for (batch in 0 until batches) {
            for (r in withSplitFrames(recvAll(800), frames)) blob.write(r); sendAck()
            val raw = blob.toByteArray()
            val count = distinctPaths(blob)
            perBatch(batch, count, count != lastCount)
            lastCount = count
            if (!sd.released) stepTransfer(sd, batch, raw)
            else if (internalAskedAt < 0) {
                sendDuml(0x00, 0x26, listCmd(INTERNAL_QUERY_CTR, internalCursor), receiverType = 0x01, receiverId = 0)
                internalAskedAt = batch
                internalFrom = raw.size                    // frames before this belong to an earlier transfer
            } else if (stepTransfer(internal, batch - internalAskedAt, raw, internalFrom)) break
        }
    }

    /**
     * [datagrams] as they go into a list blob, each preceded by any frame that began in an earlier
     * envelope of the same channel and ends in it.
     *
     * A list blob is scanned for frames across whole datagrams, so a frame split between two SW UDP
     * envelopes carries the second envelope's 20-byte header inside it, fails its checksum and is lost.
     * The drone's list lost chunks that way until [dev.konraditurbe.osmosis.drone.DroneSession] began
     * stripping the headers. Retail AC101 firmware sends a list's chunks back to back without waiting for
     * an ACK (`DjiTransSrv_SendListFrag` raises event `0x10` and `MainProc` sends the next), so a 16-file
     * Action 1 list went out as a 918-byte chunk and a 130-byte one; the second never decoded (issue #32).
     * A split like this is the likeliest reason. Frames that fit inside one envelope are left to the
     * datagram itself, so nothing is counted twice.
     */
    private fun withSplitFrames(datagrams: List<ByteArray>, frames: AlbumFrames?): List<ByteArray> {
        if (frames == null || datagrams.isEmpty()) return datagrams
        val out = ArrayList<ByteArray>(datagrams.size)
        for (dg in datagrams) {
            frames.frames(dg) { frame, carried ->
                if (carried) {
                    out += frame
                    rejoinedFrames++
                }
            }
            out += dg
        }
        return out
    }

    /** Frames [withSplitFrames] rebuilt since [collectStores] last reported them. Diagnostic only. */
    @Volatile private var rejoinedFrames = 0

    /** Test seam: a list blob built from [datagrams] the way the collect loops build it. */
    internal fun listBlobForTest(datagrams: List<ByteArray>, session: Int): ByteArray =
        withSplitFrames(datagrams, AlbumFrames(session)).fold(ByteArray(0)) { acc, b -> acc + b }

    /** A store's transfer for the batch-paced paths (~800 ms a step). */
    private fun transferByBatch(ctr: Int, hold: Boolean = true) = ListTransfer(
        ctr, minStep = 1, quietSteps = 1, proceedWait = PROCEED_WAIT_BATCHES,
        maxSteps = if (ctr == SD_QUERY_CTR) SD_SILENT_BATCHES else INTERNAL_SILENT_BATCHES, hold = hold)

    /** A store's transfer for the inline page, stepped on the keep-alive's 200 ms tick. */
    private fun transferByTick(ctr: Int, hold: Boolean = true) = ListTransfer(
        ctr, minStep = 1, quietSteps = STREAM_QUIET_TICKS, proceedWait = PROCEED_WAIT_TICKS,
        maxSteps = if (ctr == SD_QUERY_CTR) SD_SILENT_TICKS else INTERNAL_SILENT_TICKS, hold = hold)

    /**
     * One collect step of a list transfer's lifecycle: tell a camera that is waiting to proceed, or
     * release the transfer once [t] says its answer is in. True on the step the release goes out.
     *
     * [from] is where this query's frames start in [raw]: after an earlier answer under the same
     * counter (the SD retry), or after whatever a previous page's transfer was still sending.
     */
    private fun stepTransfer(t: ListTransfer, step: Int, raw: ByteArray, from: Int = 0): Boolean {
        val name = if (t.ctr == SD_QUERY_CTR) "SD" else "internal"
        val bytes = manifestBytes(raw, requestCtr = t.ctr)
        val records = listRecordCount(bytes)
        var answer = answerState(raw, t.ctr, from)
        // Where the body writes a count, every declared record being in settles it, flag or no flag.
        if (answer == Answer.STREAMING && bytes.size >= 4 && u32le(bytes, 0) in 1..COUNT_MAX.toLong() &&
            records >= u32le(bytes, 0)) answer = Answer.DONE
        val gap = action1ListGap(raw, t.ctr, from)
        when (t.next(step, answer, records, gap != null)) {
            ListTransfer.Action.PROCEED -> {
                log("datalink: $name list raised a state frame before any data — sending proceed (4A 02)")
                sendDuml(0x00, 0x26, listProceed(t.ctr), receiverType = 0x01, receiverId = 0)
            }
            ListTransfer.Action.RESEND -> if (gap != null) {
                log("datalink: $name list stopped at ${bytes.size} of ${u32le(bytes, 4)} bytes — " +
                    "asking the camera to resend chunk $gap (4A 02)")
                sendDuml(0x00, 0x26, listResend(t.ctr, gap), receiverType = 0x01, receiverId = 0)
            }
            ListTransfer.Action.RELEASE -> {
                if (answer == Answer.WAITING || answer == Answer.SILENT)
                    log("datalink: $name list still ${answer.name.lowercase()} — releasing its transfer unanswered")
                sendDuml(0x00, 0x26, listRelease(t.ctr), receiverType = 0x01, receiverId = 0)
                return true
            }
            ListTransfer.Action.WAIT -> {}
        }
        return false
    }

    /**
     * Stores that still hold older files and answered this page with nothing: no records, and neither
     * a start frame (a store with nothing on it) nor a finished list. The camera raised state frames
     * and never sent the data, or said nothing. That is "ask again", not "the store has run out":
     * taking it for the end is how an Action 6 stopped at 177 of its internal files (issue #53).
     */
    private fun unansweredStores(page: List<CameraFile>): List<Int> =
        listOf(SD_STORE to (sdCursor != NEWEST_SD), INTERNAL_STORE to (internalCursor != NEWEST_INTERNAL))
            .filter { (store, populated) ->
                populated && store !in storeEnded && storeSlice(page, store).isEmpty() &&
                    lastAnswers[store] != Answer.DONE && lastAnswers[store] != Answer.EMPTY
            }.map { it.first }

    /** Latch the stores this page showed to be finished: answered, and nothing older behind it. */
    private fun noteEndedStores(page: List<CameraFile>, sdMoved: Boolean, internalMoved: Boolean) {
        for ((store, moved) in listOf(SD_STORE to sdMoved, INTERNAL_STORE to internalMoved)) {
            val slice = storeSlice(page, store)
            if (slice.isNotEmpty() && !storeHasOlderPage(slice.size, moved, lastSlices[store])) storeEnded += store
        }
    }

    /** Does [store] get a patient transfer on an older page? Only while it has older files to give. */
    private fun holds(store: Int) =
        store !in storeEnded && (if (store == SD_STORE) sdCursor != NEWEST_SD else internalCursor != NEWEST_INTERNAL)

    /**
     * Re-ask for the SD list when the first query came back with nothing.
     *
     * A store is not necessarily mounted the moment the camera confirms playback. An Osmo Nano with a
     * card in its dock answers the store-0 query `0x00/0x26` -> `d8` and opens an empty transfer, while
     * answering the store-1 query `00` in the same session a few ms later — so the grid shows internal
     * only and the card looks absent. DJI Mimo never sees `d8` because it lets ~1.7 s pass between the
     * playback confirmation and its first list query; we send ours ~50 ms after it.
     *
     * Rather than delay every load for the one camera that needs it, ask again once the first pass is
     * over — by then several seconds have gone by, which is the whole point. Costs one query, and only
     * on a camera that returned an empty SD slice (which is also the genuine no-card case).
     */
    private fun retrySdIfNotReady(blob: java.io.ByteArrayOutputStream): ByteArray {
        val first = blob.toByteArray()
        if (manifestBytes(first, requestCtr = SD_QUERY_CTR).isNotEmpty()) return first
        log("datalink: SD store answered empty — re-asking once (it may not have been mounted yet)")
        sendDuml(0x00, 0x26, listCmd(SD_QUERY_CTR, NEWEST_SD), receiverType = 0x01, receiverId = 0)
        val sd = transferByBatch(SD_QUERY_CTR)
        val frames = AlbumFrames(tx.sessionId)
        for (batch in 0 until 6) {
            for (r in withSplitFrames(recvAll(700), frames)) blob.write(r); sendAck()
            holdPlayback()
            // Judge this query by its own frames: the blob still holds the first, empty answer.
            if (!sd.released) stepTransfer(sd, batch, blob.toByteArray(), from = first.size)
        }
        val again = blob.toByteArray()
        val n = listRecordCount(manifestBytes(again, requestCtr = SD_QUERY_CTR))
        log("datalink: SD retry -> $n record(s)")
        return again
    }

    private fun listCmd(ctr: Int, cursor: Long): ByteArray =
        hex("4a002a10010000000000010000002d000d0100ffffffffffffffff000100000000000000000000000000").also {
            it[4] = ctr.toByte()
            it[14] = pageSize.toByte()
            it[10] = (cursor and 0xFF).toByte()
            it[11] = ((cursor ushr 8) and 0xFF).toByte()
            it[12] = ((cursor ushr 16) and 0xFF).toByte()
            it[13] = ((cursor ushr 24) and 0xFF).toByte()
        }


    /**
     * Fetch the next OLDER page for the grid's infinite scroll, returning ONLY the newly-seen files
     * (empty when exhausted). Pagination (from a DJI-Mimo pcap): enter PLAYBACK MODE (0x02/0x0c=01 01 00
     * 01), then per page query(SD cursor) → release of that transfer once it has answered ([ListTransfer])
     * → query(internal cursor), each cursor a 4-byte LE handle at bytes 10-13. The camera drops the
     * datalink after ~2 pages and any keepalive drifts udpSeq out of window, so each call runs in a
     * FRESH registered session (like the delete flow), then restores the browse keep-alive. Blocking;
     * call off the UI thread.
     */
    @Synchronized
    override fun fetchNextPage(): List<CameraFile> {
        if (!moreAvailable) return emptyList()

        // Inline on the live session first (playback held) — the win that makes scroll instant (#12).
        // Same frame sequence as the fresh path: query(sdCursor) → release of that transfer once its
        // answer is in → query(internalCursor). Run stepPagination (which advances the cursors) ONLY on
        // a non-empty decode, so a failed inline query falls through to the fresh path without
        // double-advancing.
        if (keepAliveOn) {
            // A two-store page can take several seconds: the Xtra answered the internal query ~2.5 s
            // after the SD one on a newest-page load. The old 4 s deadline cut such pages short.
            val blob = runManifestQuery(
                listCmd(SD_QUERY_CTR, sdCursor),
                listCmd(INTERNAL_QUERY_CTR, internalCursor),
                timeoutMs = PAGE_STREAM_TIMEOUT_MS,
                sdList = true)
            val page = blob?.let { collectStores(it) } ?: emptyList()
            // A truncated slice is not a page: taking it would make the count rule call the library
            // finished. Neither is one where a store that has older files answered with nothing. Re-ask
            // in a fresh session instead, whose 800 ms windows give the camera room to finish.
            val truncated = lastSlices.values.any { it.incomplete }
            val unanswered = unansweredStores(page)
            if (page.isNotEmpty() && !truncated && unanswered.isEmpty()) {
                unansweredPages = 0
                return takePage(page, "next page(inline)")
            }
            if (truncated || unanswered.isNotEmpty()) logPageEvidence(page, emptyList(), sdCursor, internalCursor)
            log("datalink: next page(inline) ${when {
                truncated -> "TRUNCATED"
                unanswered.isNotEmpty() -> "${unanswered.joinToString { storeName(it) }} UNANSWERED"
                else -> "empty"
            }} — fresh-session fallback")
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

    /** One older page in a fresh playback session; advances both cursors + [moreAvailable]; returns new files. */
    private fun freshSessionPage(ip: String): List<CameraFile> {
        if (!openAndRegister(ip, subscribe = false)) { log("datalink: next-page open FAILED"); return emptyList() }
        if (httpViaWorkMode) ensureHttpWorkMode()
        else sendDuml(0x02, 0x0c, hex("01010001"), receiverType = 0x01, receiverId = 0)     // enter playback
        for (b in 0 until 2) { recvAll(250); sendAck() }
        val blob = java.io.ByteArrayOutputStream()
        sendDuml(0x00, 0x26, listCmd(SD_QUERY_CTR, sdCursor), receiverType = 0x01, receiverId = 0)
        collectPage(blob, internalCursor, batches = 14, sdHold = holds(SD_STORE), internalHold = holds(INTERNAL_STORE))
        val page = collectStores(blob.toByteArray())
        // A store that went unanswered keeps the page open for another pull. Twice in a row and it is
        // given up on, so a store that will never answer cannot keep the spinner armed for ever.
        val unanswered = unansweredStores(page)
        if (unanswered.isEmpty()) unansweredPages = 0
        else if (++unansweredPages >= UNANSWERED_PAGE_LIMIT) {
            log("datalink: ${unanswered.joinToString { storeName(it) }} went unanswered $unansweredPages pages running — giving up on it")
            storeEnded += unanswered; unansweredPages = 0
        }
        return takePage(page, "next page", retry = unansweredStores(page).isNotEmpty())
    }

    private fun storeName(store: Int) = if (store == SD_STORE) "SD" else "internal"

    /**
     * Step pagination over a collected [page]: advance the cursors, latch the stores it finished, set
     * [moreAvailable] and return the new files. [retry] keeps the pull-up armed for a store that has
     * yet to answer, whatever the stores that did answer say.
     */
    private fun takePage(page: List<CameraFile>, what: String, retry: Boolean = false): List<CameraFile> {
        val before = sdCursor to internalCursor
        val step = stepPagination(sdCursor, internalCursor, page, seenKeys, lastSlices)
        noteEndedStores(page, step.sdCursor != sdCursor, step.internalCursor != internalCursor)
        sdCursor = step.sdCursor
        internalCursor = step.internalCursor
        moreAvailable = step.moreAvailable || retry
        log("datalink: $what ${cursorLog()} +${step.fresh.size} new (more=$moreAvailable)" +
            if (retry) " — ${unansweredStores(page).joinToString { storeName(it) }} unanswered, will ask again" else "")
        logPageEvidence(page, step.fresh, before.first, before.second)
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
        val frames = AlbumFrames(tx.sessionId)
        for (batch in 0 until 10) {
            for (r in withSplitFrames(recvAll(700), frames)) blob.write(r); sendAck()
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
     * Mimo answers the same question from a per-record `isPageLastFile` flag. That flag **is** in the
     * manifest: the `0c 01` TLV before the last record's name field, present in every captured final
     * page and absent from every full one ([hasEndMarker]). [storeHasOlderPage] layers it, and the
     * truncation check, on top of this count rule; this stays as the fallback for a decode with no
     * per-store wire facts.
     */
    internal fun hasOlderPage(pageSize: Int, cursor: Long): Boolean =
        cursor > 0L && pageSize >= this.pageSize

    /**
     * Does [store]'s slice of a page leave an older page to fetch? Needs the cursor to have moved, and then:
     *
     *  - the camera's `0c 01` end marker ([SliceInfo.endMarker]) is final — nothing older, even on a
     *    page that happens to be full;
     *  - a slice with fewer records than its header declared ([SliceInfo.incomplete]) was **cut short**,
     *    not short: whatever it lost is older than the cursor we just advanced to, so the next page
     *    brings it back. Calling that "end" is exactly how an Xtra lost its three oldest files;
     *  - otherwise the count rule of [hasOlderPage]: a full page means more, a short one means done.
     *
     * [info] null (a merged, no-counter-echo decode) falls back to the count rule alone.
     */
    internal fun storeHasOlderPage(sliceSize: Int, cursorMoved: Boolean, info: SliceInfo?): Boolean = when {
        !cursorMoved -> false
        info == null -> sliceSize >= pageSize
        // Truncation outranks the marker: the marker rides the LAST record's name field, and a page that
        // lost chunks in the middle can carry it while the records before it are missing — seen on an
        // Xtra page declaring 21, delivering 17, marker present, 0003–0006 gone.
        info.incomplete -> true
        info.endMarker -> false
        else -> maxOf(sliceSize, info.records) >= pageSize
    }

    /**
     * DEBUG evidence line per store for one page: what the camera declared, what we decoded, whether the
     * end-of-list TLV was there, how the cursor moved, and every record as `seq:handle` in manifest
     * order — enough to reconstruct the walk from a log and to compare the two end-of-list rules
     * (record count vs `0c 01` marker) against what the camera actually had.
     */
    private fun logPageEvidence(page: List<CameraFile>, fresh: List<CameraFile>, sdBefore: Long, intBefore: Long) {
        for ((store, name, before, after) in listOf(
            Quad(SD_STORE, "SD", sdBefore, sdCursor), Quad(INTERNAL_STORE, "internal", intBefore, internalCursor))) {
            val slice = storeSlice(page, store)
            val info = lastSlices[store]
            val freshN = fresh.count { storeOf(it) == store }
            val handles = slice.map { it.handle }.filter { it != 0L }
            val moved = after != before          // a newest-page selector is replaced, not walked below
            val countRule = hasOlderPage(slice.size, if (moved) after else 0L)
            val verdict = storeHasOlderPage(slice.size, moved, info)
            log("datalink: page[$name] hdr=${info?.declared ?: "-"} records=${info?.records ?: "-"} decoded=${slice.size}" +
                " end0c01=${info?.endMarker ?: "-"} ended=${info?.ended ?: "-"} incomplete=${info?.incomplete ?: "-"}" +
                " fresh=$freshN cursor 0x%08x→0x%08x".format(before, after) +
                " min=0x%08x last=0x%08x".format(handles.minOrNull() ?: 0L, slice.lastOrNull()?.handle ?: 0L) +
                " | more=$verdict (count-rule alone=$countRule)")
            // The record list is a kilobyte a page — only under a debug page flag, where a walk is
            // being reconstructed; the summary line above is what an ordinary log needs.
            if (slice.isNotEmpty() && (debugPageForce || debugPageSize > 0)) log("datalink: page[$name] " +
                slice.joinToString(" ") { "%04d:%08x".format(it.seq, it.handle) })
        }
    }
    private data class Quad(val store: Int, val name: String, val before: Long, val after: Long)

    /**
     * Pull a video's highlight / moment marks (side-button presses) — DUML **0x02/0xff**, RE'd from an
     * Xtra pcap (ROADMAP #7). Inline on the live session first (the faithful seq window from #12 makes
     * that work); if the camera doesn't answer, **give up** rather than tear the session down. The marks
     * are decorative scrubber chapters, usually empty — not worth an 8 s fresh-session rebuild per
     * preview (which on a Nano returned `0 []` and re-entered playback) the way delete/pagination are.
     * Returns each mark's start time in **ms**, ascending; empty if none / no session. [handle] = the
     * video's manifest delete-handle. Off-UI.
     */
    @Synchronized
    override fun getHighlights(handle: Long): List<Int> {
        if (handle == 0L) return emptyList()
        if (keepAliveOn) {
            val reply = runCommand(0x02, 0xFF, highlightCmd(handle), receiverType = 0x01, receiverId = 0,
                timeoutMs = 4000)
            if (reply != null) {
                val marks = parseHighlights(reply)
                log("datalink: highlights(inline) 0x${handle.toString(16)} → ${marks.size} $marks")
                return marks
            }
            // Give up rather than rebuild the session. Highlights are chapter marks on the scrubber —
            // decorative, and usually empty. The fallback below tears the live session down and re-opens
            // it, which on a Nano cost 8 s of churn per preview and re-entered playback mode, all to
            // return `0 []`. Pagination and delete earn that cost because nothing else can do their job;
            // this does not. A missing mark list just means no marks are drawn.
            log("datalink: highlights(inline) 0x${handle.toString(16)} — no reply; skipping " +
                "(not rebuilding a live session for chapter marks)")
            return emptyList()
        }
        val ip = tx.peerIp ?: return emptyList()
        keepAliveOn = false
        Thread.sleep(600)                // let the keep-alive loop finish its recv and exit
        tx.close()                       // free udp/$port for the fresh session
        val marks = runCatching { freshSessionHighlights(ip, handle) }
            .getOrElse { log("datalink: highlights session error: ${it.message}"); emptyList() }
        if (tx.isOpen) runCatching { startKeepAlive() }
        return marks
    }

    private fun freshSessionHighlights(ip: String, handle: Long): List<Int> {
        if (!openAndRegister(ip, subscribe = false)) { log("datalink: highlights — fresh session open FAILED"); return emptyList() }
        sendDuml(0x02, 0x0c, hex("01010001"), receiverType = 0x01, receiverId = 0)   // enter playback mode
        recvAll(200)
        sendDuml(0x02, 0xFF, highlightCmd(handle), receiverType = 0x01, receiverId = 0)
        var reply: ByteArray? = null
        val deadline = System.nanoTime() + 2_000_000_000L
        while (reply == null && System.nanoTime() < deadline) {
            reply = findReply(recvAll(200), 0x02, 0xFF)
            if (reply == null) sendAck()
        }
        // No leave here on purpose: the browse continues after this fresh session and the keep-alive is
        // about to resume. Dropping the camera out of playback between operations is what made
        // favourite/delete race and let a Pocket 3 fall back into capture mid-transfer.
        val marks = reply?.let { parseHighlights(it) } ?: emptyList()
        log("datalink: highlights(fresh) 0x${handle.toString(16)} → ${marks.size} $marks")
        return marks
    }

    /** 0x02/0xff request: `40 2f 00 01 0b 00 00 00 [handle:u32-LE] 00 00`. */
    private fun highlightCmd(handle: Long): ByteArray =
        byteArrayOf(0x40, 0x2f, 0x00, 0x01, 0x0b, 0x00, 0x00, 0x00) + le32(handle.toInt()) + byteArrayOf(0, 0)

    /** Decode the 0x02/0xff reply: `00 · 40 2f 00 01 · [len:u32] · [handle:u32] · [count:u8] · 00 ·
     *  { 00 [startMs:u32-LE] } × count`. Count at payload byte 13, first mark at 16, stride 5. */
    private fun parseHighlights(p: ByteArray): List<Int> {
        if (p.size < 14 || p[0].toInt() != 0) return emptyList()
        val count = p[13].toInt() and 0xFF
        val marks = ArrayList<Int>(count)
        for (k in 0 until count) {
            val o = 16 + k * 5
            if (o + 4 > p.size) break
            marks.add(u32le(p, o).toInt())
        }
        return marks.sorted()
    }

    internal data class PageStep(
        val fresh: List<CameraFile>,
        val sdCursor: Long,
        val internalCursor: Long,
        val moreAvailable: Boolean,
    )

    /**
     * Which store a record pages under.
     *
     * [CameraFile.storageKnown] is the fact — set when the record came back from a store-specific query,
     * which is the normal path. The fallbacks are for the camera that doesn't echo the request counter,
     * where [collectStores] returns one merged list: the handle's store bit answers it (the same rule
     * [StorageRules] uses for the HTTP mount), and only if there is no handle at all does the manifest's
     * own list boundary stand in.
     */
    internal fun storeOf(f: CameraFile): Int =
        if (f.isIndexed) dev.konraditurbe.osmosis.dcf.DcfIndex.storage(f.fileIndex)
        else if (f.storageKnown) f.storage
        else StorageRules.mountGuess(singleSdStorage = false, handle = f.handle, cmdHandle = f.cmdHandle)
            ?: f.group

    private fun storeSlice(page: List<CameraFile>, store: Int) = page.filter { storeOf(it) == store }

    /**
     * The oldest usable handle in [store]'s slice of [page] that is strictly older (smaller) than [below],
     * or null when that store has nothing older left.
     *
     * No namespace floor: the slice is already one store, so every handle in it shares a base by
     * construction — that is the whole reason the old `>= 0x40000000` test is gone. Handle 0 is skipped
     * because it means "no handle" (a record whose scan disagreed with the fit, see [withCmdHandles]),
     * not a very old file, and taking it would park the cursor at the bottom forever.
     */
    private fun oldestHandle(page: List<CameraFile>, store: Int, below: Long): Long? =
        storeSlice(page, store).map { if (it.isIndexed) it.fileIndex else it.handle }.filter { it != 0L && it < below }.minOrNull()

    /** Both cursors in hex, for the log — the line that tells you which store is still advancing. */
    private fun cursorLog() = "sd=0x%08x int=0x%08x".format(sdCursor, internalCursor)

    /** Dedup key across pages. Store-qualified because the same filename can exist on BOTH stores. */
    private fun pageKey(f: CameraFile) = "${storeOf(f)}:${f.path}"

    /**
     * Pure pagination step for the grid's infinite scroll — **one cursor per store**, advanced
     * independently, because a page request is two queries and each selects a store.
     *
     * Given the current cursors and a freshly-decoded [page]: returns the files not already in [seen]
     * (which it updates, keyed by store + path); each store's next cursor = the oldest handle in that
     * store's slice strictly older than that store's current cursor, or the cursor unchanged when the
     * store is out; and whether another page is likely — new files on this one AND at least one store
     * both advanced and came back full ([hasOlderPage]).
     *
     * A store that runs out simply stops advancing while the other keeps going, and a camera with only
     * one store populated pages on that one. Under the old single cursor an SD-only body could not page
     * at all, and every camera's SD content stopped at the newest page.
     */
    internal fun stepPagination(
        sdCursor: Long,
        internalCursor: Long,
        page: List<CameraFile>,
        seen: MutableSet<String>,
        info: Map<Int, SliceInfo> = emptyMap(),
    ): PageStep {
        val fresh = page.filter { seen.add(pageKey(it)) }
        val sdOldest = oldestHandle(page, SD_STORE, below = sdCursor)
        val intOldest = oldestHandle(page, INTERNAL_STORE, below = internalCursor)
        var more = fresh.isNotEmpty() && (
            storeHasOlderPage(storeSlice(page, SD_STORE).size, sdOldest != null, info[SD_STORE]) ||
                storeHasOlderPage(storeSlice(page, INTERNAL_STORE).size, intOldest != null, info[INTERNAL_STORE]))
        // DEBUG `--ez pageforce true`: ignore the short-page test and keep walking while a cursor moves
        // and the page brought something new — to see whether a "final" page really was final.
        if (debugPageForce) more = fresh.isNotEmpty() && (sdOldest != null || intOldest != null)
        return PageStep(fresh, sdOldest ?: sdCursor, intOldest ?: internalCursor, more)
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
        val sdTransfer: ListTransfer? = null,       // set = the query is an SD list: release its transfer first, then prime
        val internalTransfer: ListTransfer? = null, // set with it: the last prime frame is the internal query, and the
                                                    // page is over when this transfer is released
        val frames: AlbumFrames? = null,            // a stream query's frames split across envelopes ([withSplitFrames])
    ) {
        val blob = java.io.ByteArrayOutputStream()   // raw datagrams for a stream query
        @Volatile var reply: ByteArray? = null
        @Volatile var done = false
        var ticks = 0; var lastCount = -1; var stable = 0
        var primeFrom = 0                   // the tick the prime frames follow: 0, or the SD release's
        var internalAskedAt = -1            // the tick the internal query went out, and where its frames
        var internalFrom = 0                // start in [blob]
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
    fun runManifestQuery(
        payload: ByteArray, vararg primeFrames: ByteArray, timeoutMs: Long = 4000, sdList: Boolean = false,
    ): ByteArray? {
        // Completion is decided on the keep-alive tick: the camera's own `end` frame for every counter it
        // opened, else the path count sitting still for STREAM_QUIET_TICKS, else [timeoutMs] — and a
        // deadline hit hands back whatever arrived, which the page step then checks for truncation.
        //
        // With [sdList], [payload] is the SD list query and the last prime frame the internal one. The
        // SD transfer is released when its answer is in ([stepTransfer]), the prime frames follow that
        // release one per tick, and the page is over when the internal transfer is released in turn —
        // not on an end frame, which an Action 6 raises before the data. Without [sdList] the prime
        // frames go out one per tick straight after the query.
        if (!keepAliveOn) return null
        val c = PendingCmd(0x00, 0x26, payload, 0x01, 0, 2, 0x00, 0x27,
            System.currentTimeMillis() + timeoutMs, primeFrames = primeFrames.toList(), stream = true,
            sdTransfer = if (sdList) transferByTick(SD_QUERY_CTR, holds(SD_STORE)) else null,
            internalTransfer = if (sdList) transferByTick(INTERNAL_QUERY_CTR, holds(INTERNAL_STORE)) else null,
            frames = AlbumFrames(tx.sessionId))
        cmdQueue.add(c)
        while (!c.done && System.currentTimeMillis() < c.deadlineMs + 600) runCatching { Thread.sleep(30) }
        return c.reply
    }

    /**
     * Keep the datalink alive (ACK ~2×/s so the AP doesn't sleep), beat `0x00/0x88` at ~1 Hz to hold
     * **playback mode** for the whole browse session (so inline commands needing it —
     * favorite/group-expand/pagination — work), decode the status frames (battery/storage/firmware)
     * the camera pushes on its own, and service the inline command queue ([runCommand]).
     *
     * It polls the camera for **nothing**. Status arrives unprompted once registered, and the three
     * frames this loop used to send — `0x02/0x8E`, `0x02/0xA0`, `0x02/0x61` — are respectively a
     * parameter GET that knocks the camera out of playback, Audio Param Get, and Histogram Get.
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
        // Ask the camera before telling it anything. Playback is camera-wide and outlives our session,
        // so after a re-registration it is usually still in it — and on a body that refuses 0x02/0x0c
        // this saves three pointless refusals. Costs one drain when the mode really is off.
        parseStatus(recvAll(120))
        if (playbackReported == true) {
            playbackHeld = true
            log("datalink: playback mode already held (camera says so)")
            return true
        }
        repeat(attempts) { n ->
            sendDuml(0x02, 0x0C, hex("01010001"), receiverType = 0x01, receiverId = 0)
            val deadline = System.nanoTime() + 900_000_000L
            while (System.nanoTime() < deadline) {
                val dg = recvAll(100)
                parseStatus(dg)                       // also updates playbackReported, which decides this
                // The CAMERA's answer decides it, not the command's. A Pocket 3 replies status 0 to
                // this and stays in capture — screen on the live view, gimbal unfolded — so an ack
                // means "received", nothing more. Bit 30 is the only thing that means "in playback".
                if (playbackReported == true) {
                    playbackHeld = true
                    log("datalink: playback mode held" + if (n > 0) " (confirmed on attempt ${n + 1})" else "")
                    return true
                }
                val reply = findReply(dg, 0x02, 0x0C)
                if (reply != null) {
                    val status = reply[0].toInt() and 0xFF
                    if (status != 0x00) {
                        // A refusal is definitive. Re-sending a command the body does not implement
                        // costs a second an attempt and cannot start working, so switch route now.
                        log("datalink: playback refused (0x%02x) — trying 0x01/0x01".format(status))
                        return enterPlaybackViaControl()
                    }
                }
                sendAck()
            }
        }
        // 0x02/0x0c did not move the camera. Try the other route before giving up.
        if (enterPlaybackViaControl()) return true
        // Not fatal — the browse still works, but the camera stays in capture (gimbal unfolded, and it
        // can still be recording), so say so rather than pretend we hold it.
        log("datalink: playback mode NOT confirmed after $attempts attempts — camera may still be in capture")
        return false
    }

    /**
     * The Pocket 3's playback entry: `0x01/0x01` (`SPECIAL Control`), not `0x02/0x0c`.
     *
     * Replayed from a labelled capture of the official app on a Pocket 3 (2026-08-17), where
     * `0x02/0x0c` does not appear once in 128 s. Two payloads go out at ~20 Hz across 1.35 s and the
     * camera's playback bit sets 358 ms into the second; nothing else in that capture uses cmdset
     * `0x01`. `cmd_type 0x00` and no reply, so there is nothing to wait for — [playbackReported] is
     * the only signal, which is exactly why this is safe to attempt on any body: a camera that ignores
     * it leaves the bit alone and we report not-held, the same as before.
     *
     * The two payloads differ in byte 0 (`03`→`00`) and byte 9 (`07`→`04`). Which one carries the mode
     * is unknown, so both are sent verbatim in the captured order rather than reduced to a guess.
     *
     * No exit counterpart: the capture never left playback, so there is nothing to replay for it.
     * Teardown still sends `0x02/0x0c 01010000`, which a Pocket 3 ignores as harmlessly as the enter.
     */
    private fun enterPlaybackViaControl(): Boolean {
        log("datalink: 0x02/0x0C did not move the camera — trying 0x01/0x01 (the Pocket 3 route)")
        val prelude = hex("0300000000040000000701")
        val enter = hex("0000000000040000000401")
        val deadline = System.nanoTime() + 2_500_000_000L
        var sent = 0
        var silent = 0
        while (System.nanoTime() < deadline) {
            // Six of the first, then the second for the rest — the capture's own proportions.
            sendDuml(0x01, 0x01, if (sent < 6) prelude else enter,
                receiverType = 0x01, receiverId = 0, cmdType = 0)
            sent++
            val got = recvAll(50)
            parseStatus(got)
            if (playbackReported == true) {
                playbackHeld = true
                log("datalink: playback mode held via 0x01/0x01 (after $sent frames)")
                return true
            }
            // Stop if the camera has gone quiet. This is cmd_type 0 with no reply, so the state bit is
            // the only feedback and there is nothing else to notice a departure by — without this the
            // burst keeps firing into a camera that is powering down (its 5-minute idle timer lands
            // mid-session readily enough), which is the last thing to be writing an unknown control
            // command into.
            silent = if (got.isEmpty()) silent + 1 else 0
            if (silent >= 8) {
                log("datalink: 0x01/0x01 — camera stopped answering after $sent frames, aborting")
                return false
            }
        }
        log("datalink: 0x01/0x01 sent $sent frames, camera still reports capture")
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
        // DEBUG (`--ez nowriterefresh true`): let the write go out on however old a session it finds.
        // The mitigation below hides the very thing it mitigates — with it on, no write ever reaches the
        // camera on a session old enough to have drifted, so whether the drift is still there cannot be
        // observed. Turning it off is how a control-window change gets tested rather than assumed.
        if (debugNoWriteRefresh) {
            if (keepAliveOn && sessionAgeMs() > WRITE_WINDOW_MS)
                log("datalink: nowriterefresh — attempting $what on a ${sessionAgeMs() / 1000}s session")
            return false
        }
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

    override val datalinkOriginals: Boolean
        get() = datalinkJpegs && httpServing == false

    override val datalinkJpegs: Boolean
        get() = legacyAlbum == LegacyActionAlbum.ACTION_1 && handshakeOk && tx.isOpen && !closing

    override val datalinkVideo: Boolean get() = datalinkJpegs && keepAliveOn
    private val requestedVideo = AtomicReference<ActionVideoPlayback?>(null)

    override fun openAlbumVideo(file: CameraFile, listener: AlbumVideo.Listener): AlbumVideo? {
        if (!datalinkVideo || !file.isActionDcf || !file.isVideo) return null
        val playback = ActionVideoPlayback(file.fileIndex, listener)
        requestedVideo.getAndSet(playback)?.close()
        return playback
    }

    private fun playAlbumVideo(playback: ActionVideoPlayback) {
        var restore: Int? = null
        var failure: String? = null
        try {
            if (playback.closed || !keepAliveOn) return
            val previous = readWorkMode() ?: workModeReported ?: error("unknown camera work mode")
            if (playback.closed || !keepAliveOn) return
            if (previous != WorkMode.TRANSCODE) {
                restore = previous
                originalWorkMode = previous
                httpWorkMode = WorkMode.TRANSCODE
                val status = setWorkMode(WorkMode.TRANSCODE)
                check(status == null || status == 0) { "TRANSCODE refused (status=$status)" }
                check((readWorkMode() ?: workModeReported) == WorkMode.TRANSCODE) {
                    "camera did not enter TRANSCODE"
                }
            }
            if (playback.closed || !keepAliveOn) return
            log("datalink: Action 1 playback index=${playback.index}")
            tx.withDownloadPolling {
                playback.play(tx.sessionId, { keepAliveOn && !closing && tx.isOpen },
                    { cmd, body -> sendDuml(2, cmd, body, receiverType = 1, receiverId = 0) },
                    { recvAll(30) }, tx::sendAck)
            }
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
            log("datalink: Action 1 playback index=${playback.index}: $failure")
        } finally {
            if (restore != null && !closing && tx.isOpen) {
                runCatching { if (setWorkMode(restore) == 0) httpWorkMode = null }
                    .onFailure { log("datalink: restoring work mode after Action 1 playback: ${it.message}") }
            }
            requestedVideo.compareAndSet(playback, null)
            playback.finish(failure)
        }
    }

    private val albumQueue = ArrayBlockingQueue<FutureTask<Boolean>>(1)
    private var nextAlbumTransferId = 0x100

    override fun fetchAlbumJpeg(file: CameraFile, rendition: AlbumJpeg): ByteArray? {
        val bytes = java.io.ByteArrayOutputStream()
        return if (transferAlbumFile(file, 0, bytes, rendition) {}) bytes.toByteArray() else null
    }

    override fun downloadOriginal(file: CameraFile, offset: Long, output: java.io.OutputStream,
                                  progress: (Long) -> Unit): Boolean {
        requestedVideo.get()?.close()
        return transferAlbumFile(file, offset, output, null, progress)
    }

    // Serialize with pagination so its fresh-session fallback cannot retire the socket mid-file.
    @Synchronized
    private fun transferAlbumFile(file: CameraFile, offset: Long, output: java.io.OutputStream,
                                  jpeg: AlbumJpeg?, progress: (Long) -> Unit): Boolean {
        fun available() = if (jpeg == null) datalinkOriginals
            else datalinkJpegs && requestedVideo.get()?.closed != false
        if (!available() || !keepAliveOn || !file.isActionDcf || Thread.currentThread().isInterrupted) return false
        val cancelled = AtomicBoolean(false)
        val task = FutureTask {
            val buffered = output.buffered(64 * 1024)
            try {
                fun nextId(): Int {
                    val id = nextAlbumTransferId
                    nextAlbumTransferId = if (id == 0xffff) 0x100 else id + 1
                    return id
                }
                fun connected() = available() && keepAliveOn && !cancelled.get()
                fun runTransfer(transfer: ActionFileTransfer) = tx.withDownloadPolling {
                    transfer.run(tx.sessionId,
                        send = { sendDuml(0, 0x26, it, receiverType = 1, receiverId = 0) },
                        receive = { recvAll(30) }, ackWindow = ::sendAck,
                        connected = ::connected, log = log)
                }
                // An original restarts from its contiguous end after a stall (see fetchOriginal).
                // A JPEG is small and starts at zero, so it stays a single transfer.
                if (jpeg != null) runTransfer(ActionFileTransfer(file, offset, nextId(), buffered, jpeg, progress))
                else ActionFileTransfer.fetchOriginal(file, offset, buffered, progress, ::nextId, ::connected, log,
                    ::runTransfer)
            } finally {
                buffered.flush()
            }
        }
        if (!albumQueue.offer(task)) return false
        // Once the owner takes the task it may be writing to MediaStore. Let it return the stream
        // before the downloader closes it, including on disconnect or caller interruption.
        var interrupted = false
        try {
            while (true) {
                if ((!keepAliveOn || closing) && albumQueue.remove(task)) return false
                try {
                    return task.get(250, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // Observe teardown even when it happens before the owner takes the task.
                } catch (_: InterruptedException) {
                    interrupted = true
                    cancelled.set(true)
                    if (albumQueue.remove(task)) return false
                } catch (e: ExecutionException) {
                    log("datalink: album transfer failed: ${e.cause?.message}")
                    return false
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    override fun startKeepAlive() {
        if (keepAliveOn || closing) return
        keepAliveOn = true
        playbackReleased = false
        resetStatus()
        Thread {
            var tick = 0
            // Unconditional, and cheap when it is a no-op: [enterPlaybackConfirmed] asks the camera
            // first and returns straight away if it is already in playback. Gating this on our own
            // playbackHeld instead was wrong — that flag survives a re-registration, so a refreshed
            // session (the delete flow) would have skipped entry on a body that drops the mode when
            // the old session goes away, and written outside playback believing it held.
            // A work-mode camera skips playback (it refuses 0x02/0x0c) and re-checks its mode instead:
            // after a re-registration (delete, fresh page) the switch may not have survived the socket.
            if (httpViaWorkMode) ensureHttpWorkMode() else enterPlaybackConfirmed()
            while (keepAliveOn) {
                if (awaitingCmd == null) {
                    albumQueue.poll()?.run()
                    requestedVideo.get()?.let(::playAlbumVideo)
                }
                if (!keepAliveOn) break
                runCatching {
                    val dg = recvAll(200)
                    parseStatus(dg)
                    awaitingCmd?.let { c ->
                        if (c.stream) {
                            for (r in withSplitFrames(dg, c.frames)) c.blob.write(r)
                            c.ticks++
                            val sd = c.sdTransfer
                            val internal = c.internalTransfer
                            if (sd != null && !sd.released) {           // hold the prime frames for the SD release
                                if (stepTransfer(sd, c.ticks, c.blob.toByteArray())) c.primeFrom = c.ticks
                            } else if (c.ticks - c.primeFrom in 1..c.primeFrames.size) {  // then one prime frame per tick
                                sendDuml(c.set, c.cmd, c.primeFrames[c.ticks - c.primeFrom - 1], receiverType = c.rType, receiverId = c.rId, cmdType = c.cType)
                                if (c.ticks - c.primeFrom == c.primeFrames.size) {       // that was the internal query
                                    c.internalAskedAt = c.ticks; c.internalFrom = c.blob.size()
                                }
                            } else if (internal != null && c.internalAskedAt >= 0) {
                                val raw = c.blob.toByteArray()
                                if (stepTransfer(internal, c.ticks - c.internalAskedAt, raw, c.internalFrom)) {
                                    c.reply = raw; c.done = true; awaitingCmd = null
                                }
                            }
                            val cnt = distinctPaths(c.blob)
                            // First choice: the camera closed every answer it opened (`4A 03` end frame per
                            // counter). Second: the count has not moved for STREAM_QUIET_TICKS. The old
                            // 2-tick (400 ms) quiet test fired inside a mid-stream pause and returned a
                            // page missing its last chunks — the oldest records — which the short-page
                            // rule then read as the end of the library.
                            // Those two tests are for the other stream queries (group expand). A list page
                            // ends on its internal transfer above: an end frame is no proof of an answer.
                            val allSent = internal == null && c.ticks >= c.primeFrom + c.primeFrames.size + 1
                            if (c.done) {
                                // the internal transfer closed the page on this tick
                            } else if (allSent && cnt > 0 && streamsEnded(c.blob.toByteArray())) {
                                c.reply = c.blob.toByteArray(); c.done = true; awaitingCmd = null
                            } else if (allSent && cnt > 0 && cnt == c.lastCount) {
                                if (++c.stable >= STREAM_QUIET_TICKS) { c.reply = c.blob.toByteArray(); c.done = true; awaitingCmd = null }
                            } else c.stable = 0
                            c.lastCount = cnt
                            if (!c.done && System.currentTimeMillis() > c.deadlineMs) {
                                log("datalink: manifest stream deadline after ${c.ticks} ticks, $cnt paths — reply may be truncated")
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
                    // Mimo does not send it WHILE HOLDING PLAYBACK, which is the case that matters
                    // here. In a 49 s Nano session (reference/captures/wifi/mimo_nano_delete.pcap) it
                    // enters playback once and sends 0x02/0x8E, 0x02/0xa0 and 0x02/0x61 exactly ZERO
                    // times, yet still gets status — because the camera PUSHES 0x02/0x80 (493x) and
                    // 0x02/0x82 (480x) on its own once registered. The polls were never needed for the
                    // pill; parseStatus reads those pushes either way.
                    //
                    // Not a blanket rule about the command: doing the same delete on an Xtra
                    // (mimo_xtra_delete.pcap) Mimo sends 486 of them and never enters playback at all.
                    // Two different strategies, per model.
                    //
                    // ~1 Hz app-presence beat (tick is 300 ms). Without it the camera drops playback a
                    // second after we set it — see APP_PRESENCE. This is the beat 0x02/0x8E was standing
                    // in for, badly: we polled it *during* playback, which is what knocked the mode out.
                    if (tick % 3 == 0) holdPlayback()
                    // No 0x02/0xA0 or 0x02/0x61 either. Both were polled here as "state query" and
                    // "status poll", which is not what they are: DJI's own command catalogue names them
                    // **Audio Param Get** and **Histogram Get**. Neither feeds the pill — the camera
                    // pushes 0x02/0x80 and 0x02/0x82 unprompted once registered — so for us they were
                    // two writes a second buying nothing, on a link whose write budget is the very
                    // thing that runs out.
                    //
                    // This said "Mimo sends neither", which was read off one Nano capture and is not
                    // true generally: on a Pocket 3 the official app sends 1044 of 0x02/0xA0 and 210
                    // of 0x02/0x61 in two minutes. It polls them because it draws an audio meter and a
                    // histogram; we draw neither. Not sending them is still right for this app — the
                    // claim about the other app was not.
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
            // Same for a work-mode switch: put the camera back in the mode we found it in, so it isn't
            // left serving files (and out of its capture mode) after we've gone.
            if (releasePlaybackOnExit) restoreAlbumOnClose()
            playbackReleased = true
        }.apply { isDaemon = true; name = "datalink-keepalive" }.start()
    }

    private fun restoreAlbumOnClose() {
        val back = originalWorkMode ?: return
        if (httpWorkMode == null || !tx.isOpen) return
        runCatching {
            sendDuml(0x02, 0x10, WorkMode.setPayload(back), receiverType = 0x01, receiverId = 0)
            log("datalink: requested original work mode ${WorkMode.name(back)}")
        }
        httpWorkMode = null
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
        synchronized(albumLock) { closing = true }
        val video = requestedVideo.get()
        video?.close()
        if ((video != null || playbackHeld || (httpWorkMode != null && originalWorkMode != null)) && keepAliveOn) {
            releasePlaybackOnExit = true
            keepAliveOn = false
            val deadline = System.currentTimeMillis() + 500
            while (!playbackReleased && System.currentTimeMillis() < deadline) runCatching { Thread.sleep(25) }
            releasePlaybackOnExit = false
        } else if (!keepAliveOn) {
            restoreAlbumOnClose()
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
     * caller must confirm intent and pass only handles it means to destroy. MEDIA_PROTOCOL.md §2.
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
        // No leave here — see freshSessionHighlights. The keep-alive resumes and keeps holding playback.
        log("datalink: FAVORITE reply status=${status?.let { "0x%04x".format(it) } ?: "none"}")
        return status == 0
    }

    private fun listRecordCount(bytes: ByteArray): Int {
        val paths = countMediaPaths(bytes)
        if (paths == 0 && legacyAlbum != null && DcfRecords.decodeAction1(bytes, variableRecords = true).isNotEmpty())
            return u32le(bytes, 0).toInt()
        return paths
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
        // An Action 1 answer can be completed by a resent chunk, which arrives after the chunks that
        // followed it, and two resend requests can bring the same chunk twice. Within one counter the
        // chunk sequence numbers are the camera's own, so order by them and keep each once.
        if (requestCtr != null && legacyAlbum == LegacyActionAlbum.ACTION_1) {
            val out = java.io.ByteArrayOutputStream()
            for ((p, size) in action1Chunks(raw, requestCtr).values) out.write(raw, p + 10, size - 10)
            return out.toByteArray()
        }
        // The file list streams back as DUML data frames (cmd 0x00/0x27) whose payload is
        //   [10-byte sub-header][chunk]:  4A 01 .. .. <seq:u16le @6> 00 00, then the chunk bytes.
        // byte1==0x01 marks a data chunk; the control frames (4A 04.. start / 4A 03.. end) are 10
        // bytes of sub-header with no chunk. The chunks reassemble into the real manifest, which opens
        // with a u32-LE file count. We must strip the sub-header before concatenating — otherwise a
        // record whose path straddles a frame boundary gets those 10 bytes injected mid-path
        // (e.g. "DCIM/DJI_" + "J….001/…") and silently drops from the grid. Concatenate in arrival
        // order (not seq-sorted): on multi-page lists the seq counter restarts per page.
        val out = java.io.ByteArrayOutputStream()
        forEachListFrame(raw) { p, size ->
            // The counter identifies the store. Each data frame has a ten-byte list sub-header.
            if ((raw[p + 1].toInt() and 0xFF) == 1 &&
                (requestCtr == null || (raw[p + 4].toInt() and 0xFF) == requestCtr))
                out.write(raw, p + 10, size - 10)
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
        // The label is only for the log: each store's slice is decoded on its own, so every record in it
        // is group 0 and both stores would otherwise report "list 0" with different bases — which reads
        // like one list being fitted twice rather than two stores each being fitted once.
        val tally = chunkTally(raw)
        lastSlices.clear()
        if (rejoinedFrames > 0) {
            log("datalink: rebuilt $rejoinedFrames list frame(s) split across UDP envelopes")
            rejoinedFrames = 0
        }
        fun sliceOf(ctr: Int): List<CameraFile> {
            val store = if (ctr == SD_QUERY_CTR) "SD" else "internal"
            val bytes = manifestBytes(raw, requestCtr = ctr)
            val files = bytes.takeIf { it.isNotEmpty() }?.let { decodeManifest(it, store) } ?: emptyList()
            val indexed = files.firstOrNull()?.isIndexed == true
            val info = SliceInfo(
                declared = if (bytes.size >= 4) u32le(bytes, 0).toInt() else -1,
                records = when {
                    !indexed -> countMediaPaths(bytes)
                    bytes.size >= 8 && u32le(bytes, 4) > bytes.size -> files.size  // a short Action 1 list
                    else -> u32le(bytes, 0).toInt()
                },
                endMarker = files.firstOrNull()?.isIndexed != true && hasEndMarker(bytes),
                ended = (tally[ctr]?.get(0x03) ?: 0) > 0)
            lastSlices[if (ctr == SD_QUERY_CTR) SD_STORE else INTERNAL_STORE] = info
            lastAnswers[if (ctr == SD_QUERY_CTR) SD_STORE else INTERNAL_STORE] = answerState(raw, ctr)
            if (info.incomplete) log("datalink: $store slice TRUNCATED — header declares ${info.declared}" +
                " records, ${info.records} arrived (end frame ${if (info.ended) "seen" else "missing"})")
            return files
        }
        val sd = sliceOf(SD_QUERY_CTR)
        val internal = sliceOf(INTERNAL_QUERY_CTR)
        // A store that answers with nothing and a store that was never answered look identical once the
        // slice is empty, and they need opposite fixes: an unreadable card vs a query the camera dropped.
        // The census separates them by counting the reply frames the camera actually sent under each
        // request counter. Measured on a Nano whose card was unreachable:
        //
        //     ctr1={start=1}  ctr2={data=17,end=12}
        //
        // i.e. the camera OPENED the SD transfer and then streamed no data and no end frame — it answered
        // "nothing there". A missing ctr1 entirely would mean the query never got served, which is ours
        // to fix; `start` with no `data` is the camera's answer and is not a bug.
        if (sd.isEmpty()) log("datalink: SD slice empty — reply frames by request counter: ${chunkCensus(raw)}" +
            "; SD control frames: ${controlFrames(raw, SD_QUERY_CTR)}")
        val ambiguous = sd.isNotEmpty() && internal.isNotEmpty() &&
            sd.map { it.albumKey }.toSet() == internal.map { it.albumKey }.toSet()
        if ((sd.isEmpty() && internal.isEmpty()) || ambiguous) {
            if (ambiguous && sd.all { it.isIndexed }) return sd
            val merged = decodeManifest(manifestBytes(raw))
            log("datalink: store split unavailable (${if (ambiguous) "both queries same list" else "no counter echo"})" +
                " — ${merged.size} files, storage resolved per file")
            return merged
        }
        log("datalink: per-store lists — SD ${sd.size}, internal ${internal.size} (no probing needed)")
        // Internal first: it is the larger store on every camera we have, so on the rare overlap the
        // kept copy is the one more likely to be right.
        fun fromStore(files: List<CameraFile>, mount: Int) = files.map {
            it.copy(storage = if (it.isIndexed) it.storage else mount, storageKnown = true)
        }
        return (fromStore(internal, 1) + fromStore(sd, 0)).distinctBy { it.albumKey }
    }

    /**
     * Does a reassembled per-store manifest carry the `0c 01` end-of-list TLV? It sits immediately
     * before a record's `0d` name field (`… 13 00 | 0c 01 | 0d <len> <name>`), so the three-byte run
     * `0c 01 0d` is the test. Present in every captured final page (xtra_13, oa4_6, oa6_*, op3_*),
     * absent in every captured full page (nano_45, oa4_45, op4_45).
     */
    internal fun hasEndMarker(bytes: ByteArray): Boolean {
        for (i in 0 until bytes.size - 2)
            if (bytes[i] == 0x0C.toByte() && bytes[i + 1] == 0x01.toByte() && bytes[i + 2] == 0x0D.toByte()) return true
        return false
    }

    /**
     * Tally the `0x00/0x27` reply frames in a collected blob by sub-header byte 4 (the request counter
     * the camera echoes) and by `4A` subtype, e.g. `ctr1={start=1,data=0,end=1} ctr2={…}`.
     *
     * Diagnostic only. It answers the one question the per-store split cannot: whether a store that
     * decoded to zero files was *answered* with zero, or never answered at all. See [collectStores] for
     * how to read the output.
     */
    private fun chunkCensus(raw: ByteArray): String {
        val tally = chunkTally(raw)
        if (tally.isEmpty()) return "none"
        fun name(sub: Int) = when (sub) { 0x04 -> "start"; 0x01 -> "data"; 0x03 -> "end"; else -> "sub$sub" }
        return tally.entries.joinToString(" ") { (ctr, subs) ->
            "ctr$ctr={" + subs.entries.joinToString(",") { (s, n) -> "${name(s)}=$n" } + "}"
        }
    }

    /**
     * Has the camera closed every answer in [raw]? A counter is closed when it sent its `4A 03` end
     * frame, or when it opened (`4A 04`) and never sent a data chunk — that is how a store with nothing
     * on it answers (`ctr1={start=1}` on a Nano with no card, in both raw fixtures), and no end frame
     * ever follows. Every counter seen must be closed, and so must each [required] one (a query the
     * camera has not even started answering is not closed). At least one counter must have carried
     * data. The end frame is the camera's own completion signal; a collector that stops on a quiet gap
     * instead can hand back a page missing its last chunks.
     */
    internal fun streamsEnded(raw: ByteArray, vararg required: Int): Boolean {
        val tally = chunkTally(raw)
        if (tally.values.none { (it[0x01] ?: 0) > 0 }) return false
        fun closed(ctr: Int): Boolean {
            val subs = tally[ctr] ?: return false
            return (subs[0x03] ?: 0) > 0 || ((subs[0x04] ?: 0) > 0 && (subs[0x01] ?: 0) == 0)
        }
        return required.all { closed(it) } && tally.keys.all { closed(it) }
    }

    /** `0x00/0x27` reply frames in [raw] by request counter, then by `4A` subtype (0x04 start · 0x01 data · 0x03 end). */
    private fun chunkTally(raw: ByteArray): java.util.SortedMap<Int, MutableMap<Int, Int>> {
        val tally = sortedMapOf<Int, MutableMap<Int, Int>>()
        forEachListFrame(raw) { p, _ ->
            val ctr = raw[p + 4].toInt() and 0xFF
            val sub = raw[p + 1].toInt() and 0xFF
            tally.getOrPut(ctr) { sortedMapOf() }.merge(sub, 1, Int::plus)
        }
        return tally
    }

    /** Visit every `0x00/0x27` `4A` frame in [raw] from offset [from] on, in arrival order, by payload offset. */
    private inline fun forEachListFrame(raw: ByteArray, from: Int = 0, visit: (payloadAt: Int, payloadSize: Int) -> Unit) {
        var i = from
        while (i + 13 <= raw.size) {
            if ((raw[i].toInt() and 0xFF) != 0x55) { i++; continue }
            val len = ((raw[i + 1].toInt() and 0xFF) or ((raw[i + 2].toInt() and 0xFF) shl 8)) and 0x3FF
            if (len < 13 || i + len > raw.size) { i++; continue }
            // A UDP session ID or ACK cursor can contain 0x55. Its apparent length can grow to
            // fit as more packets arrive, making an already decoded record disappear on the next pass.
            if ((raw[i + 2].toInt() ushr 2) != 1 ||
                DjiCrc.computeCrc8(raw.copyOfRange(i, i + 3)) != (raw[i + 3].toInt() and 0xFF)) {
                i++
                continue
            }
            // A frame split across two SW UDP envelopes still has a good header in a whole-datagram blob,
            // with the second envelope's header inside its body. Read as a chunk, it puts 20 foreign bytes
            // into an Action 1 list, and skipping its length jumps over the rebuilt copy [withSplitFrames]
            // put after it. The other cameras keep the header-only check their fixtures were pinned with.
            if (strictListFrames && DjiCrc.computeCrc16(raw, offset = i, length = len - 2) !=
                ((raw[i + len - 2].toInt() and 0xFF) or ((raw[i + len - 1].toInt() and 0xFF) shl 8))) {
                i++
                continue
            }
            if ((raw[i + 9].toInt() and 0xFF) == 0x00 && (raw[i + 10].toInt() and 0xFF) == 0x27 &&
                len - 13 >= 10 && (raw[i + 11].toInt() and 0xFF) == 0x4A
            ) visit(i + 11, len - 13)
            i += len
        }
    }

    /**
     * Where the camera is in answering request [ctr] — see [Answer].
     *
     * The last data chunk carries `0x1000` in the sub-header's `u16` at `+2`. It is set on the final
     * chunk of both raw captures (`nano_45.bin`, `xtra_13.bin`) and on no other, and it alone closes an
     * answer here. A `4A 03` state frame does not, wherever it falls: with no data before it the camera
     * is waiting, and after some data it may be the end or a frame raised mid-stream. [stepTransfer]
     * adds the header's record count, and a stream that loses its final chunk ends on going quiet.
     */
    internal fun answerState(raw: ByteArray, ctr: Int, from: Int = 0): Answer {
        var start = false; var state = false; var data = false; var closed = false
        forEachListFrame(raw, from) { p, _ ->
            if ((raw[p + 4].toInt() and 0xFF) == ctr) when (raw[p + 1].toInt() and 0xFF) {
                0x04 -> start = true
                0x03 -> state = true
                0x01 -> { data = true; closed = closed || (raw[p + 3].toInt() and 0x10) != 0 }
            }
        }
        return when {
            data -> if (closed) Answer.DONE else Answer.STREAMING
            start -> Answer.EMPTY
            state -> Answer.WAITING
            else -> Answer.SILENT
        }
    }

    /** An Action 1 answer's data chunks under request [ctr] from [from] on: chunk sequence → (payload offset, size). First copy wins. */
    private fun action1Chunks(raw: ByteArray, ctr: Int, from: Int = 0): java.util.TreeMap<Long, Pair<Int, Int>> {
        val chunks = java.util.TreeMap<Long, Pair<Int, Int>>()
        forEachListFrame(raw, from) { p, size ->
            if ((raw[p + 1].toInt() and 0xFF) == 1 && (raw[p + 4].toInt() and 0xFF) == ctr)
                chunks.putIfAbsent(u32le(raw, p + 6), p to size)
        }
        return chunks
    }

    /**
     * The first chunk an Action 1 list answer to [ctr] is missing, when the chunks from [from] on carry
     * fewer bytes than its header declares (`[count][total]`, the total counting the header itself).
     * Null when the answer is complete, has no first chunk yet, or the camera is not an Action 1.
     */
    internal fun action1ListGap(raw: ByteArray, ctr: Int, from: Int = 0): Long? {
        if (legacyAlbum != LegacyActionAlbum.ACTION_1) return null
        val chunks = action1Chunks(raw, ctr, from)
        val (first, size) = chunks[0L] ?: return null
        if (size < 18) return null
        val declared = u32le(raw, first + 14)
        if (chunks.values.sumOf { (it.second - 10).toLong() } >= declared) return null
        var missing = 0L
        while (missing in chunks) missing++
        return missing
    }

    /**
     * The control frames (everything but data) the camera sent under request [ctr], as distinct
     * sub-headers with a count, e.g. `4a030a00010000000000×15`. Diagnostic only: the four bytes after
     * the counter are the transfer state, which a frame count alone does not show.
     */
    private fun controlFrames(raw: ByteArray, ctr: Int): String {
        val seen = LinkedHashMap<String, Int>()
        forEachListFrame(raw) { p, _ ->
            if ((raw[p + 4].toInt() and 0xFF) == ctr && (raw[p + 1].toInt() and 0xFF) != 0x01)
                seen.merge((p until p + 10).joinToString("") { "%02x".format(raw[it]) }, 1, Int::plus)
        }
        return seen.entries.joinToString(" ") { (frame, n) -> "$frame×$n" }.ifEmpty { "none" }
    }

    /** Distinct media paths seen so far — lets the collect loop stop once the list stops growing. */
    private fun distinctPaths(blob: java.io.ByteArrayOutputStream): Int =
        listRecordCount(manifestBytes(blob.toByteArray()))

    private val primaryExts = setOf("MP4", "MOV", "JPG", "JPEG", "DNG", "OSV", "INSV", "HEIC")
    private val proxyExts = setOf("LRF", "LRV")

    /** Test seam: run the full raw-blob → frame-reassemble → decode pipeline on a captured manifest. */
    internal fun decodeManifestBlobForTest(rawBlob: ByteArray): List<CameraFile> =
        decodeManifest(manifestBytes(rawBlob))

    /** Test seam: the per-store split, so it can be checked against the handle-bit rule it replaces. */
    internal fun collectStoresForTest(rawBlob: ByteArray): List<CameraFile> = collectStores(rawBlob)

    /** Test seam: what [collectStores] recorded about each store's answer on its last run. */
    internal fun lastSlicesForTest(): Map<Int, SliceInfo> = lastSlices.toMap()

    /** Test seam: the `0c 01` end-of-list marker on a reassembled manifest (raw blob or bare manifest). */
    internal fun endMarkerForTest(rawBlob: ByteArray): Boolean = hasEndMarker(manifestBytes(rawBlob))

    /** Test seam: decode an already-reassembled CompositePack manifest (post frame-reassembly). */
    internal fun decodeCompositeForTest(manifest: ByteArray): List<CameraFile> = decodeComposite(manifest)

    /**
     * Test seam: an assembled manifest through the FULL decode, collision guard included.
     *
     * Not the same as [decodeCompositeForTest], which stops before [flagHandleCollisions] and so
     * reports every record as deletable — including the ones sharing a handle, which is the exact
     * condition the guard exists to refuse.
     */
    internal fun decodeManifestForTest(manifest: ByteArray): List<CameraFile> = decodeManifest(manifest)

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
    private fun flagHandleCollisions(files: List<CameraFile>): List<CameraFile> {
        val dupes = files.filter { it.handle != 0L }.groupBy { it.handle }.filter { it.value.size > 1 }
        for ((h, group) in dupes) {
            log("datalink: ⚠ HANDLE COLLISION 0x%08x shared by %s — delete disabled for these"
                .format(h, group.joinToString { it.name }))
        }
        if (files.size <= 12) {
            for (f in files) log("datalink:   %-44s handle=0x%08x".format(f.name.take(44), f.handle))
        }
        if (dupes.isEmpty()) return files
        val shared = dupes.keys
        return files.map { if (it.handle in shared) it.copy(handleShared = true) else it }
    }

    /**
     * Put the work mode that opened `:80` back after a re-registration, if the camera has left it.
     *
     * The delete and the fresh-page fallback close the socket and register again. Closing the socket is
     * what takes the newer cameras out of playback, so the Action 1 may drop its mode the same way.
     * Reads the status push first and only sends when the mode has actually changed. No-op when no mode
     * was found.
     */
    private fun ensureHttpWorkMode() {
        if (httpServing != true || closing) return
        val mode = httpWorkMode ?: return
        parseStatus(recvAll(200))
        val current = workModeReported ?: readWorkMode() ?: return
        if (current == mode || closing) return
        val status = setWorkMode(mode)
        log("datalink: work mode was ${WorkMode.name(current)} after re-registering — " +
            "SetMode ${WorkMode.name(mode)} again → ${if (status == null) "no reply" else "0x%02x".format(status)}")
    }

    /**
     * ROADMAP #6's open question, asked directly: does this camera serve file bytes over the datalink?
     *
     * Runs once per session on an Action-1-style manifest, right after the list lands and while the
     * session is provably alive. See [DcfTransferProbe] for the reasoning, what a silent result is
     * worth (little), and why every probe releases. Read-only: query + release, nothing that writes.
     */
    private fun probeTransferKinds(fileIndex: Long) {
        log("datalink: --- 0x4a transfer-kind probe on index 0x%08x (read-only) ---".format(fileIndex))
        var answered = 0
        var sawState = 0
        for ((n, base) in DcfTransferProbe.BASES.withIndex()) {
            val seq = 0x60 + n
            sendDuml(0x00, 0x26, DcfTransferProbe.query(base, seq, fileIndex),
                receiverType = 0x01, receiverId = 0)
            val heard = LinkedHashMap<Int, Int>()
            var sample: ByteArray? = null
            for (round in 0 until 3) {
                for (dg in recvAll(250)) {
                    for ((set, cmd, pl) in scanFrames(dg)) {
                        if (set != 0x00 || cmd != 0x27) continue
                        DcfTransferProbe.familyMember(pl, base, seq)?.let { off ->
                            heard.merge(off, 1, Int::plus)
                            if (sample == null) sample = pl.copyOfRange(0, minOf(40, pl.size))
                        }
                    }
                }
                sendAck()
            }
            // Always release, answer or not — a leaked slot makes the camera go quiet later and looks
            // exactly like "unsupported", which would send us away from a route that works.
            sendDuml(0x00, 0x26, DcfTransferProbe.release(base, seq), receiverType = 0x01, receiverId = 0)
            recvAll(150); sendAck()

            if (heard.isEmpty()) {
                log("datalink:   base 0x%02x — no reply".format(base))
            } else {
                answered++
                if (DcfTransferProbe.STATE in heard.keys) sawState++
                log("datalink:   base 0x%02x — ANSWERED: %s".format(base,
                    heard.entries.joinToString(", ") { "${DcfTransferProbe.kindName(it.key)}×${it.value}" }))
                sample?.let { log("datalink:     %s".format(it.joinToString("") { b -> "%02x".format(b) })) }
            }
        }
        // Distinguish "the kind exists" from "a transfer started". An Action 1 answered base 0x20 with
        // a `release` and nothing else: the family IS understood there — a foreign base would be
        // ignored outright — but the camera closed the transfer instead of raising a state and sending.
        // The earlier wording called any reply "transfer is LIVE", which overstated exactly this case.
        log(when {
            sawState > 0 -> "datalink: --- probe: $sawState kind(s) raised STATE — a transfer STARTED, report this ---"
            answered > 0 -> "datalink: --- probe: $answered/${DcfTransferProbe.BASES.size} kind(s) replied but none " +
                "raised STATE — the transfer family is understood there, the transfer was refused. " +
                "Most likely our query payload is wrong, not that the route is closed ---"
            else -> "datalink: --- probe: nothing answered. NOT a refutation — the query payload is a guess, " +
                "and a wrong shape looks the same as an unsupported kind. See DcfTransferProbe ---"
        })
    }

    private fun decodeManifest(bytes: ByteArray, store: String = ""): List<CameraFile> {
        // The older Osmo Action generation answers with a flat DCF record array instead of
        // CompositePack — no paths, no filenames, files addressed by packed index. Its header is
        // self-describing ([u32 count][u32 totalBytes] where totalBytes covers itself), so this can be
        // tried first without risking a CompositePack manifest being misread as one.
        // Where a newer Action supplies a physical path, keep the name and mount from its manifest.
        var dcf = if (legacyAlbum != null && countMediaPaths(bytes) > 0) emptyList()
            else DcfRecords.decodeAction1(bytes, variableRecords = legacyAlbum != null)
        // Last resort, after the resend requests: an Action 1 list still short of its header shows the
        // whole records that did arrive, newest first, instead of an empty grid. [collectStores] reports
        // the slice as truncated.
        if (dcf.isEmpty() && legacyAlbum == LegacyActionAlbum.ACTION_1 && countMediaPaths(bytes) == 0) {
            dcf = DcfRecords.decodeAction1(bytes, variableRecords = true, allowShort = true)
            if (dcf.isNotEmpty()) log("datalink: Action 1 list${storeTag(store)} is short: ${bytes.size} of " +
                "${u32le(bytes, 4)} bytes — showing the ${dcf.size} whole record(s) that arrived")
        }
        if (dcf.isNotEmpty()) {
            log("datalink: decoded ${dcf.size} DCF index records${storeTag(store)} (older Osmo Action generation)")
            if (probeTransfers && (legacyAlbum == null || httpViaWorkMode)) probeTransferKinds(dcf.first().fileIndex)
            dumpManifest(bytes)
            return dcf.map { it.toCameraFile(dcfRoute) }
        }
        val comp = decodeComposite(bytes, store)
        if (comp.isNotEmpty()) {
            log("datalink: decoded ${comp.size} CompositePack records${storeTag(store)} " +
                "(${comp.count { it.resLabel != null }} fps, ${comp.count { it.proxyPath != null }} proxies, " +
                "${comp.count { it.starred }} starred, " +
                "${comp.count { it.deletable }} deletable, ${comp.count { it.sizeBytes > 0 }} sized)")
            dumpManifest(bytes)
            return flagHandleCollisions(comp)
        }
        log("datalink: no CompositePack records — dumping manifest, falling back to flat scrape")
        // Unbudgeted: a decode failure is rare, and it is the one case where the bytes are the
        // whole report. Losing them to a budget would defeat the point.
        dumpManifest(bytes, budgeted = false)
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
    private fun decodeComposite(bytes: ByteArray, store: String = ""): List<CameraFile> {
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
            byPath.putIfAbsent(m.path, resolveRecord(bytes, m.path, lo, hi, m.pos).copy(group = group))
        }
        return withCmdHandles(byPath.values.toList(), store)
    }

    /** " [internal]" when the caller knows which store these bytes came from; "" when it doesn't. */
    private fun storeTag(store: String) = if (store.isEmpty()) "" else " [$store]"

    /**
     * How a handle fit names the list it fitted.
     *
     * A store-specific query's answer is decoded on its own, so every record in it is group 0 — two
     * stores would both report "list 0" with different bases, reading like one list fitted twice. When
     * the caller knows the store, say so; the merged decode (no counter echo) still has real groups and
     * keeps naming them.
     */
    private fun fitLabel(store: String, group: Int) = when {
        store.isEmpty() -> "list $group"
        group == 0 -> store
        else -> "$store list $group"
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
    private fun withCmdHandles(files: List<CameraFile>, store: String = ""): List<CameraFile> {
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
            log("datalink: handle fit (${fitLabel(store, group)}): " +
                "base=0x${base.toString(16)} step=0x${step.toString(16)}")
        }
        if (fits.isEmpty()) return files
        var promoted = 0
        var revoked = 0
        val out = files.map { f ->
            val fit = fits[f.group]
            if (fit == null || f.seq <= 0) return@map f
            val fitted = fit.first + f.seq * fit.second
            when {
                // Two independent sources agree: the bytes at the record's fixed marker position, and
                // a formula fitted to the handles OTHER records exposed. That is the bar for a
                // command that destroys a file — and it is how a Pocket 3's stills become deletable
                // without inventing anything, since their handle was always in the record and only the
                // guard-byte scan refused to read it.
                f.handle == 0L && f.handleCandidate != 0L && f.handleCandidate == fitted -> {
                    promoted++
                    f.copy(handle = f.handleCandidate, cmdHandle = fitted)
                }
                // The scan produced a handle the fit contradicts. Something is being read out of the
                // wrong place — a record boundary overrun did exactly this once, handing a photo the
                // neighbouring video's handle — and there is no way to tell which of the two is right.
                // Drop it: the file loses delete, which is recoverable, rather than deleting whatever
                // else lives at that handle, which is not.
                f.handle != 0L && f.handle != fitted -> {
                    revoked++
                    f.copy(handle = 0L, cmdHandle = fitted)
                }
                else -> f.copy(cmdHandle = fitted)
            }
        }
        if (promoted > 0) log("datalink: $promoted record(s) took their handle from the fixed marker (fit agrees)")
        if (revoked > 0) log("datalink: $revoked handle(s) disagreed with the fit — not deletable")
        return out
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
    private fun resolveRecord(bytes: ByteArray, mediaDir: String, lo: Int, hi: Int, selfPos: Int = -1): CameraFile {
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
        // Stop at this record's OWN media path, not at the next record's.
        //
        // The marker precedes the path it belongs to, and [hi] deliberately reaches into the next
        // record so the other fields can be found. For a body whose photo records carry no marker at
        // all — a Pocket 3 — scanning that far forward walks past the photo's path and matches the
        // NEXT record's marker, handing the photo a video's handle. That looked like the camera
        // reusing handles; it was us reading across a record boundary. It also cost the video: the
        // collision guard then refuses to delete BOTH files, so a real video became undeletable
        // because a photo borrowed its handle.
        val markerEnd = if (selfPos in (lo + 1)..hi) selfPos else hi
        while (m < markerEnd - 4) {
            val kind = bytes[m].toInt() and 0xFF
            val star = bytes[m + 1].toInt() and 0xFF
            if ((kind == 0x03 || kind == 0x00) && (star == 0xFF || star == 0xFE) &&
                bytes[m + 2] == 0x19.toByte() && bytes[m + 3] == 0x06.toByte() && m >= 8
            ) { head = m - 8; break }
            m++
        }
        // A video whose guard byte the scan does not accept: anchor on the tag's FIXED position instead,
        // the one [mediaType] and [handleCandidate] read. The chapters of a looping recording come back
        // from a group-expand as `03 f2 19 06`, path immediately after the tag — the scan missed all
        // six, and every chapter decoded with no size, duration or resolution (nano_loop_expand.bin).
        // Video only (`03` before the guard byte), so a still's handle still has to pass the fit.
        if (head < 0 && ext in VIDEO_EXTS && selfPos >= 21 && selfPos <= bytes.size &&
            bytes[selfPos - 7] == 0x19.toByte() && bytes[selfPos - 6] == 0x06.toByte() &&
            bytes[selfPos - 9] == 0x03.toByte()
        ) head = selfPos - 17
        val hasMarker = head >= 0
        val isVideo = ext in VIDEO_EXTS

        // Photos carry no `03 ff 19 06` head; their size + pixel dimensions hang off their own
        // `[ff|fe] 19 06` marker — size @ marker-14, width/height @ marker+58/+62, all u32-LE. RE'd
        // against ground truth (moov/HTTP/JPEG bounds) over a controlled clip+photo set.
        var photoSize = 0L; var photoRes: String? = null
        if (!isVideo) {
            // The `19 06` pair sits at a FIXED position — seven bytes before the record's own path
            // field — so read it there rather than scanning for it. Scanning needs a byte to match on,
            // and the only candidate is the one before the tag, which is `ff`/`fe` on some bodies but
            // `f6` (still) or `c7` (panorama) on a Pocket 3. Failing to match ran the scan into the
            // NEXT record and read its size: a photo followed by a video reported the video's byte
            // count exactly, and a photo followed by another photo reported nothing. Same overrun as
            // the delete handle had.
            val fixed = selfPos - 7
            if (selfPos >= 21 && fixed + 1 < bytes.size &&
                bytes[fixed] == 0x19.toByte() && bytes[fixed + 1] == 0x06.toByte()
            ) {
                photoSize = u32le(bytes, fixed - 14)
                if (base.startsWith("DJI_") && fixed + 66 <= bytes.size) {
                    val w = u32le(bytes, fixed + 58).toInt(); val h = u32le(bytes, fixed + 62).toInt()
                    if (w in 1..60000 && h in 1..60000) photoRes = "${w}x${h}"
                }
            }
            var q = lo
            while (photoSize == 0L && q < markerEnd - 3) {
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
        // Bounded at the NEXT record's head, for the same reason the handle scan is bounded:
        // [fpsInRange] keeps the LAST rational it finds and [hi] reaches into the next record, so a
        // clip whose neighbour was shot at a different frame rate reported the neighbour's rate.
        //
        // The ceiling has to be the next head rather than this record's own path, because the two
        // field orders disagree about where the path sits: the DJI_ family writes
        // `head · enum block · filename · media path`, the CAM_ family `head · media path · enum
        // block · filename`. Only "somewhere before the next record starts" is true of both.
        val fps = if (isVideo && hasMarker) fpsInRange(bytes, head, nextHead(bytes, head, hi)) else null
        // Video duration in whole seconds = u16-LE at **marker-4** (= head+4), immediately before the
        // frameRate/resolution codes: `… [dur:u16-LE][fps:u8][res:u8] 03 ff 19 06 …`. Universal —
        // ground-truthed 16/16 on the Nano and 3/3 on the Xtra (incl. a 75 s clip). NB the Nano also
        // mirrors it at marker+26; that copy does NOT exist on the CAM_ family, so read it here.
        val durationSec = if (isVideo && hasMarker && head + 6 <= bytes.size)
            (bytes[head + 4].toInt() and 0xFF) or ((bytes[head + 5].toInt() and 0xFF) shl 8) else 0
        // ⭐ starTag and the resolution index, both u8, ground-truth-verified against the SD card.
        // The star byte sits 9 bytes past the record's `[ff|fe] 19 06` marker (videos use `03 ff 19 06`,
        // photos a `fe 19 06` variant); the resolution index is at marker-1 (video header only).
        // Prefer the signature read where the record has one: it is the only thing that works on a
        // body whose stills carry no marker, and it agreed with the marker read everywhere both fired.
        val starred = starFlagBySignature(bytes, selfPos.takeIf { it >= 0 } ?: lo, hi)
            ?: starFlag(bytes, lo, hi)
        val resIndex = if (isVideo && hasMarker && head + 7 < bytes.size)
            bytes[head + 7].toInt() and 0xFF else -1
        val resolution = if (resIndex >= 0) resolutionForIndex(resIndex) else photoRes
        // An unmapped code is the only thing standing between a clip and its resolution label, and
        // until now it was invisible: the record decoded fine, the field just came out null. Name it,
        // so one connect to a body shooting an unlisted format is enough to extend the table.
        if (resIndex >= 0 && resolution == null) log("datalink: video format index $resIndex not mapped")
        // Media type: the byte before the constant `19 06` tag, which sits at mediaPath-13. Read only
        // when that tag is actually there, so a record shaped differently yields -1 rather than
        // whatever byte happens to precede the path. 0x00 still, 0x03 video, 0x04 panorama.
        // selfPos is the start of the path FIELD (`1a <len> 00 00 00 <sub>`), so the path text begins six
        // bytes later and the tag lands at selfPos-7.
        val mediaType = if (selfPos >= 9 && selfPos <= bytes.size &&
            bytes[selfPos - 7] == 0x19.toByte() && bytes[selfPos - 6] == 0x06.toByte()
        ) bytes[selfPos - 9].toInt() and 0xFF else -1
        // The handle at the marker's FIXED position — `u32-LE` at `(19 06) − 10`, the same tag
        // [mediaType] reads. Unlike the scan above this needs no guard byte to match and cannot run
        // into the next record, so it finds the stills a Pocket 3 writes with `f6`/`c7` guard bytes
        // that the scan rejects. Untrusted here: [withCmdHandles] promotes it only where the
        // independent base+step fit agrees.
        val handleCandidate =
            if (selfPos >= 17 && selfPos <= bytes.size &&
                bytes[selfPos - 7] == 0x19.toByte() && bytes[selfPos - 6] == 0x06.toByte()
            ) u32le(bytes, selfPos - 17) else 0L
        return CameraFile(
            path = path, thumbPath = thumbPath, storage = 0,
            resLabel = fps?.let { "${it}fps" }, proxyPath = proxyExt?.let { "$mediaDir.$it" },
            handle = handle, sizeBytes = size, starred = starred, resolution = resolution,
            durationSec = durationSec, mediaType = mediaType, handleCandidate = handleCandidate,
        )
    }

    /**
     * The manifest resolution byte (`marker-1`) is a **DJI-wide video-format index** (the Nano and
     * the Xtra/Action-5 emit the same codes for the same sizes — 95=2.7K 4:3, 103=4K 4:3), *not* the
     * drone record's resolution code (whose codes only partially/coincidentally overlap — see
     * `DcfRecords.droneResolution`). This map is built empirically from clips cross-referenced against
     * the SD card. Unknown → null → the app falls back to the MP4 `moov`.
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
     * ⚠️ This offset alone would mean no favourite survives a re-list on that family. It does not,
     * because [starFlagBySignature] runs first and an Xtra record does carry [STAR_SIG] — see there.
     * This function is the fallback for the bodies that have no signature (Nano, Action 6), where
     * the marker read is the correct one.
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

    /**
     * The favourite flag as a Pocket 3 writes it: a `00`/`01` byte after a fixed 12-byte signature.
     *
     * The marker-relative read above cannot work on this body. It anchors on `[ff|fe] 19 06`, and a
     * Pocket 3 **still** carries no such marker at all — so a favourited photo could never show a heart,
     * whatever offset was used. This anchors on [STAR_SIG] instead, which sits *after* the record's own
     * media path and is present once per record whatever the media type.
     *
     * Established by a controlled A/B on one card, camera in hand (2026-08-17): two dumps of the same
     * nine files, differing in exactly three bytes, and all three were the favourites that had been
     * changed between them — one video cleared, one video and one **photo** set. Eighteen records
     * across the two dumps, every one agreeing with the camera's own gallery.
     *
     * **Not Pocket 3 only — this is what reads an Xtra's favourites too.** The signature is present
     * once per record in both Xtra fixtures (13 in `xtra_13.bin`, 44 in `xtra_delete.bin`, matching
     * their record counts), and the byte after it is strictly `00`/`01` there — never the 44/48 path
     * length that makes the marker offset in [starFlag] unusable on that family. `xtra_delete.bin`
     * carries five favourites that read cleanly. Confirmed on an Xtra Edge Pro in hand (2026-08-30):
     * hearts set on the camera survive a re-list.
     *
     * The Nano and Action 6 carry no signature at all (zero hits across `nano_45.bin`, `nano_delete.bin`
     * and `oa6_sd_3.bin`), which is why the [starFlag] fallback stays.
     */
    private fun starFlagBySignature(bytes: ByteArray, lo: Int, hi: Int): Boolean? {
        var q = lo
        val end = minOf(hi, bytes.size - STAR_SIG.size - 1)
        while (q <= end) {
            var k = 0
            while (k < STAR_SIG.size && bytes[q + k] == STAR_SIG[k]) k++
            if (k == STAR_SIG.size) return (bytes[q + STAR_SIG.size].toInt() and 0xFF) == 1
            q++
        }
        return null
    }

    /** See [starFlagBySignature]. Constant across every Pocket 3 record dumped, once per record. */
    private val STAR_SIG = byteArrayOf(
        0x1b, 0x0a, 0x00, 0x00, 0x00, 0x02, 0x02, 0x01, 0x14, 0x02, 0x15, 0x03,
    )

    internal fun resolutionForIndex(code: Int): String? = when (code) {
        10 -> "1920x1080"  // 0x0A  1080p 16:9  (Xtra-verified)
        12 -> "1920x1440"  // 0x0C  1080p 4:3
        16 -> "3840x2160"  // 0x10  4K 16:9
        45 -> "2688x1512"  // 0x2D  2.7K 16:9
        66 -> "1080x1920"  // 0x42  1080p 9:16 vertical
        67 -> "1512x2688"  // 0x43  2.7K 9:16 vertical
        95 -> "2688x2016"  // 0x5F  2.7K 4:3
        103 -> "3840x2880" // 0x67  4K 4:3
        105 -> "1080x1080" // 0x69  1080p 1:1
        106 -> "2160x2160" // 0x6A  2160p 1:1
        107 -> "3072x3072" // 0x6B  3K 1:1
        108 -> "1728x3072" // 0x6C  3K 9:16 vertical
        125 -> "3840x3840" // 0x7D  4K 1:1 aka OpenGate
        else -> null
    }

    /**
     * Dump the reassembled manifest as hex when a model's layout doesn't decode, so a single test run
     * gives us the actual bytes to reverse. Emitted in fixed-width rows with the byte offset, the way
     * we hand-decoded the Nano/Xtra records — enough to see the count header and where the filename /
     * extension / handle fields sit. Contents are paths and filenames only; the passphrase and GPS
     * never travel on this datalink, so this is safe for the shared "Save logs" file.
     */
    private fun dumpManifest(bytes: ByteArray, budgeted: Boolean = true) {
        // File-only. The dump exists to turn a *saved* log into a fixture; sending it to logcat as
        // well actively destroys the thing it was meant to help with. Measured on a Nano browse: the
        // 256 KiB ring buffer ended up 61% hex with only 39 of our lines left in it, and the whole
        // session — connect, storage pushes, the manifest summary — had already been evicted by the
        // time anyone went looking. Logging off means no dump, which is also the right default.
        if (!dev.konraditurbe.osmosis.core.FileLog.isOn()) return
        if (budgeted) {
            if (dumpBudgetBytes <= 0) return
            if (bytes.size > dumpBudgetBytes) {
                log("datalink: manifest hex suppressed (${bytes.size}B, budget ${dumpBudgetBytes}B left) " +
                    "— earlier pages in this log already carry the layout")
                dumpBudgetBytes = 0
                return
            }
            dumpBudgetBytes -= bytes.size
        }
        // Hard cap even on the unbudgeted failure path. "A decode failure is rare, so dump it all"
        // was wrong: when reassembly finds no chunks, manifestBytes hands back the whole RAW blob —
        // every datagram the collect loop hoovered up, telemetry included. A Pocket 3 log did this
        // twice in 90 s, 541 KB and 878 KB, and 21000 of that log's 44578 lines were the two dumps.
        // The head is where the layout lives; past that it is a transcript of the camera talking to
        // itself, and a log too big to send is a log we do not get.
        val shown = minOf(bytes.size, DUMP_MAX_BYTES)
        // The declared size must be what is actually dumped: tools/hexdump_to_bin.py verifies the
        // recovered length against it and drops the block on a mismatch. Total goes after it.
        dev.konraditurbe.osmosis.core.ManifestHex.dump({ log(it) }, bytes, shown)
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

    /**
     * Where the record after the one at [head] begins, or [hi] if there is no other in range.
     *
     * The record marker `[00|03][ff|fe] 19 06` sits at `head + 8`, so the next occurrence of it marks
     * the next record — the only boundary that holds whichever order a body writes its fields in.
     */
    private fun nextHead(bytes: ByteArray, head: Int, hi: Int): Int {
        var m = head + 9                                    // past this record's own marker
        val stop = minOf(hi, bytes.size) - 4
        while (m < stop) {
            val kind = bytes[m].toInt() and 0xFF
            val star = bytes[m + 1].toInt() and 0xFF
            if ((kind == 0x03 || kind == 0x00) && (star == 0xFF || star == 0xFE) &&
                bytes[m + 2] == 0x19.toByte() && bytes[m + 3] == 0x06.toByte()
            ) return m - 8
            m++
        }
        return hi
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
        log("datalink: time synced (UTC offset ${offMin}min), unix $nowSec")
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

    companion object {
        /**
         * Keep-alive ticks (200 ms each) the path count must sit still before an inline manifest stream
         * is taken as finished, when the camera has not sent its end frame. Was 2: a 400 ms pause
         * between the SD and internal answers, or inside one, returned a page missing its tail.
         */
        private const val STREAM_QUIET_TICKS = 5

        /**
         * Limits on holding a list transfer open before releasing it ([ListTransfer]), in collect steps.
         * A batch is a ~800 ms receive (first load, fresh-session page, SD retry), a tick the
         * keep-alive's 200 ms (inline page).
         *
         * PROCEED_WAIT: how long a camera gets to start sending after a proceed, ~3 s. An Action 6
         * took up to that to deliver a card page after its state frame (issue #53).
         *
         * SILENT: how long a store that says nothing at all is waited on. The internal values are the
         * old loops' own patience for a silent second store (three batches, or the quiet rule's
         * seven-odd ticks), so a single-store body pays nothing new.
         */
        private const val PROCEED_WAIT_BATCHES = 4
        private const val PROCEED_WAIT_TICKS = 15
        private const val SD_SILENT_BATCHES = 4
        private const val SD_SILENT_TICKS = 15
        private const val INTERNAL_SILENT_BATCHES = 3
        private const val INTERNAL_SILENT_TICKS = 6

        /** Consecutive pages a store may go unanswered before its paging is given up on. */
        private const val UNANSWERED_PAGE_LIMIT = 2

        /**
         * Deadline for an inline two-store page. A newest-page load on an Xtra takes ~4.5 s; a page on
         * which both stores raise a state frame and take their time is two proceed waits and two streams.
         */
        private const val PAGE_STREAM_TIMEOUT_MS = 16_000L

        /**
         * Largest value at manifest offset 0 still treated as this page's record count. A real page never
         * holds more than [PAGE_SIZE] (255 at the widest a `--ei pagesize` debug run asks for); anything
         * bigger is not a count — the field is model-dependent, and several bodies write other data there.
         */
        private const val COUNT_MAX = 512

        /**
         * `--ez nowriterefresh true` — DEBUG ONLY, off in every ordinary run.
         *
         * Suppresses [refreshSessionForWrite] so an inline command goes out on whatever session exists,
         * however old. The point is to make the write cliff observable again: the refresh is a
         * mitigation that fires before any write can reach a drifted session, so with it in place a
         * session-sequence change cannot be told from no change at all.
         */
        @Volatile
        var debugNoWriteRefresh = false

        /**
         * `--ez pageforce true` — DEBUG ONLY. Keep paging while a cursor still advances and the page
         * brought new files, ignoring the short-page end-of-list test. Walks past a page the count rule
         * would have called final, so the log shows whether anything was behind it.
         */
        @Volatile
        var debugPageForce = false

        /**
         * `--ei pagesize N` — DEBUG ONLY (1..255). Records asked for per list query instead of Mimo's
         * 45. A camera that honours a bigger page gives an independent listing to compare a walk against.
         */
        @Volatile
        var debugPageSize = 0
    }
}
