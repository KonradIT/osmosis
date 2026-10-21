package dev.konraditurbe.osmosis.stream

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.konraditurbe.osmosis.ble.GattClient
import dev.konraditurbe.osmosis.duml.DjiMessage
import dev.konraditurbe.osmosis.duml.OsmoCommands
import java.util.UUID

/**
 * Drives DJI's RTMP livestream sequence over the same BLE DUML channel the media path uses:
 * pair -> prepare (stage 1+2) -> ConnectToWiFi -> optional stabilization -> configure -> start.
 * Ported from github.com/xaionaro-go/djictl (CC0), `ble connect-wifi-and-start-streaming`.
 *
 * The camera joins the *home* network here, so its own AP goes away and with it every offload
 * path in the app. [stop] is therefore the only clean exit — see MEDIA_PROTOCOL.md § RTMP.
 *
 * Every step is a request/reply pair on fff5, which is write-without-response: frames drop, so
 * each step is retried and any stale reply is drained first (djictl's Request does the same).
 * Replies are matched on cmdSet/cmdId rather than message id, because the start/stop/EIS replies
 * all share 0x02/0x8E and a pipelined stale one would satisfy the wrong wait otherwise.
 */
class RtmpStreamer(
    private val context: Context,
    private val listener: Listener,
) : GattClient.Listener {

    interface Listener {
        fun onLog(s: String)
        /** One line per completed step — the dialog status line, not the log. */
        fun onStep(step: String)
        /** The camera accepted the start frame. From here on, [stop] must be sent to end it. */
        fun onStreaming()
        /** Live battery percentage from the camera's 0x0d/0x02 pushes, while streaming. */
        fun onBattery(pct: Int)
        /** The flow ended — failed before starting, or the link went away. [reason] is for display. */
        fun onDone(reason: String)
    }

    /** Protocol bytes, not labels — the UI resolves them from [OsmoCommands]' tables. */
    data class StreamSettings(
        val ssid: String,
        val psk: String,
        val url: String,
        val resolutionByte: Int,
        val bitrateKbps: Int,
        val fpsByte: Int,
        /** Stabilization value; null on models djictl never sends it to (everything but Action 4/5). */
        val eisByte: Int?,
        val action5Pro: Boolean,
        val pin: String,
        val identifier: String,
    )

    private val main = Handler(Looper.getMainLooper())
    private var gatt: GattClient? = null

    @Volatile var isStreaming = false
        private set
    @Volatile private var closed = false
    @Volatile private var ready = false

    // Object, not Any(): this lock's callers use wait/notifyAll, which only Object exposes.
    private val lock = Object()
    private var reply: DjiMessage? = null
    private var pairApproved = false

    private var lastBattery = Int.MIN_VALUE
    private var lastBatteryLogMs = 0L
    private val reassembler = DumlReassembler()

    fun start(device: BluetoothDevice, settings: StreamSettings) {
        listener.onLog("RTMP: connecting to ${device.address}")
        val gc = GattClient(context, this, armPairing = true)
        gatt = gc
        closed = false
        ready = false
        pendingSettings = settings
        gc.connect(device)
        main.postDelayed(keepalive, KEEPALIVE_MS)
        // connectGatt can fail without ever calling back, which used to leave the stream stuck starting
        // until the app was killed — there is no other timer on the way to onReady.
        main.postDelayed(readyGuard, READY_TIMEOUT_MS)
    }

    /** Fires only if the GATT channel never came up; a normal start cancels it from [onReady]. */
    private val readyGuard = Runnable {
        if (closed || ready) return@Runnable
        listener.onLog("RTMP: GATT not ready after ${READY_TIMEOUT_MS / 1000}s")
        fail("Couldn't reach the camera")
    }

    /** Stop the stream and release the link. Safe to call when nothing is running. */
    fun stop() {
        if (closed) return // already ended; a second report would only repaint the banner
        if (isStreaming) {
            listener.onLog("RTMP: sending stop")
            gatt?.writeCommand(OsmoCommands.stopStream())
            // Nothing else clears this flag on this path: disconnect() + close() here suppresses the
            // callback that calls onDisconnected(), which is the only other place it is reset. Left
            // set, every later Start tap took the stop branch and the stream could never restart
            // without killing the app.
            isStreaming = false
        }
        disconnect()
        listener.onDone("Stream stopped")
    }

    private fun disconnect() {
        closed = true
        main.removeCallbacks(keepalive)
        main.removeCallbacks(readyGuard)
        synchronized(lock) { lock.notifyAll() }
        gatt?.disconnect(); gatt?.close(); gatt = null
    }

    /** Mimo repeats 0x00/0x2b `01 01` for the whole session; the camera drops a silent link. */
    private val keepalive = object : Runnable {
        override fun run() {
            if (closed) return
            // Before onReady there is no fff5 to write to, and each attempt logs a "fff5 missing" line.
            if (ready) gatt?.writeCommand(OsmoCommands.sessionPing(OsmoCommands.SESSION_KEEPALIVE))
            main.postDelayed(this, KEEPALIVE_MS)
        }
    }

    // ---- request/response -------------------------------------------------------------------

    private fun awaitReply(set: Int, id: Int, timeoutMs: Long): DjiMessage? {
        val end = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (!closed) {
                val r = reply
                if (r != null && (r.flags and 0x80) != 0 && r.cmdSet == set && r.cmdId == id) {
                    reply = null
                    return r
                }
                val left = end - System.currentTimeMillis()
                if (left <= 0) return null
                lock.wait(left)
            }
        }
        return null
    }

    private fun request(
        frame: ByteArray, set: Int, id: Int, what: String, attempts: Int = 2,
    ): DjiMessage? {
        for (attempt in 1..attempts) {
            synchronized(lock) { reply = null } // a reply already queued would satisfy the wrong wait
            val ok = gatt?.writeCommand(frame) ?: false
            listener.onLog("RTMP: sent $what ok=$ok (attempt $attempt)")
            val r = awaitReply(set, id, STEP_TIMEOUT_MS)
            if (r != null) return r
            // The pairing approval arrives as a request, not the status reply we are waiting for.
            if (pairApproved && set == 0x07 && id == 0x45) return null
            listener.onLog("RTMP: no reply to $what")
        }
        return null
    }

    /** Pairing is the one step that waits for a human, so it retries for a minute. */
    private fun pair(s: StreamSettings): Boolean {
        pairApproved = false
        val end = System.currentTimeMillis() + PAIR_TIMEOUT_MS
        while (!closed && System.currentTimeMillis() < end) {
            val r = request(OsmoCommands.setPairingPin(s.pin, identifier = s.identifier), 0x07, 0x45, "SetPairingPIN")
            val status = r?.payload?.let { if (it.size >= 2) it[1].toInt() and 0xFF else -1 } ?: -1
            when (status) {
                0x01 -> { listener.onLog("RTMP: already paired"); return true }
                0x02 -> listener.onStep("Approve pairing on the camera")
            }
            if (pairApproved) return true
        }
        return false
    }

    /** Runs on a background thread — [request] blocks. */
    private fun runSequence(s: StreamSettings) {
        try {
            listener.onStep("Pairing")
            if (!pair(s)) return fail("Camera did not approve pairing")

            listener.onStep("Preparing the stream")
            val st1 = request(OsmoCommands.prepareLiveStreamStage1(), 0x02, 0xE1, "prepare (stage 1)")
            if (st1 == null || st1.payload.isEmpty() || st1.payload[0].toInt() != 0x00)
                return fail("Camera refused prepare stage 1")
            if (request(OsmoCommands.prepareLiveStreamStage2(), 0x02, 0x8E, "prepare (stage 2)") == null)
                return fail("No reply to prepare stage 2")

            listener.onStep("Joining ${s.ssid}")
            val wifi = request(OsmoCommands.connectWifi(s.ssid, s.psk), 0x07, 0x47, "ConnectToWiFi",
                attempts = 3)
            if (wifi == null || wifi.payload.size < 2 || wifi.payload[0].toInt() != 0x00)
                return fail("Camera could not join ${s.ssid}")

            if (s.eisByte != null) {
                listener.onStep("Stabilization")
                request(OsmoCommands.setImageStabilization(s.eisByte, s.action5Pro),
                    0x02, 0x8E, "SetImageStabilization")
                    ?: return fail("No reply to the stabilization command")
            }

            listener.onStep("Configuring")
            val cfg = request(
                OsmoCommands.configureStream(s.resolutionByte, s.bitrateKbps, s.fpsByte, s.url, s.action5Pro),
                0x08, 0x78, "ConfigureStreaming",
            ) ?: return fail("No reply to the stream configuration")
            if (cfg.payload.isNotEmpty() && cfg.payload[0].toInt() != 0x00)
                return fail("Camera rejected the stream configuration")

            listener.onStep("Starting")
            if (request(OsmoCommands.startStream(), 0x02, 0x8E, "StartStreaming") == null)
                return fail("No reply to the start command")

            isStreaming = true
            listener.onStep("Streaming")
            listener.onStreaming()
        } catch (e: Exception) {
            fail("RTMP flow crashed: ${e.message}")
        }
    }

    private fun fail(reason: String) {
        // stop() already disconnected and reported while a step was in flight — don't overwrite it.
        if (closed) return
        isStreaming = false
        listener.onLog("RTMP: $reason")
        disconnect()
        listener.onDone(reason)
    }

    // ---- GattClient.Listener ----------------------------------------------------------------

    override fun onLog(s: String) = listener.onLog(s)

    override fun onReady(gatt: GattClient) {
        ready = true
        main.removeCallbacks(readyGuard)
        listener.onLog("RTMP: GATT ready — waking the session")
        gatt.writeCommand(OsmoCommands.sessionPing(OsmoCommands.SESSION_WAKE))
        main.postDelayed({
            if (!closed) pendingSettings?.let { Thread { runSequence(it) }.start() }
        }, 120)
    }

    /** The settings handed to [start], held so onReady can kick the sequence off the binder thread. */
    private var pendingSettings: StreamSettings? = null

    override fun onNotification(sourceChar: UUID, raw: ByteArray, parsed: DjiMessage?) {
        val frames = reassembler.feed(raw)
        for (f in frames) {
            // The camera sends some messages as REQUESTS (flags 0x40) and drops us (~6 s) if we
            // don't answer. Same auto-reply MainActivity uses on the media path.
            if (f.flags == 0x40) {
                val respTarget = ((f.target and 0xFF) shl 8) or ((f.target shr 8) and 0xFF)
                val respType = (f.type and 0xFFFF00) or 0xC0
                val respPayload = if (f.cmdSet == 0x00 && f.cmdId == 0x81)
                    OsmoCommands.APP_DEVICE_INFO else f.payload
                gatt?.writeCommand(DjiMessage(respTarget, f.id, respType, respPayload).encode())
                if (f.cmdSet == 0x07 && f.cmdId == 0x46) {
                    listener.onLog("RTMP: pairing approved")
                    pairApproved = true
                    synchronized(lock) { lock.notifyAll() }
                }
                continue
            }
            // Live battery pushes (0x0d/0x02): the only signal we get while streaming.
            if (f.cmdSet == 0x0D && f.cmdId == 0x02) {
                val p = f.payload
                val pct = when {
                    p.size >= 21 -> p[20].toInt()
                    p.size >= 13 -> p[12].toInt()
                    else -> -1
                } and 0xFF
                val now = System.currentTimeMillis()
                if (pct in 0..100) listener.onBattery(pct)
                if (pct in 0..100 && (pct != lastBattery || now - lastBatteryLogMs > BATTERY_LOG_MS)) {
                    lastBattery = pct; lastBatteryLogMs = now
                    listener.onLog("RTMP: camera battery $pct%")
                }
                continue
            }
            synchronized(lock) {
                reply = f
                lock.notifyAll()
            }
        }
    }

    override fun onDisconnected() {
        val wasStreaming = isStreaming
        isStreaming = false
        main.removeCallbacks(keepalive)
        main.removeCallbacks(readyGuard)
        synchronized(lock) { lock.notifyAll() }
        // We closed it ourselves (stop, or the end of a failed step) and already reported; staying
        // quiet here is what keeps the dialog from flashing "Disconnected" over the real reason.
        if (closed) return
        closed = true
        listener.onDone(if (wasStreaming) "Stream ended — the BLE link dropped" else "Disconnected")
    }

    private companion object {
        const val KEEPALIVE_MS = 1000L
        const val STEP_TIMEOUT_MS = 8000L
        const val PAIR_TIMEOUT_MS = 60_000L
        const val BATTERY_LOG_MS = 30_000L
        const val READY_TIMEOUT_MS = 15_000L
    }
}
