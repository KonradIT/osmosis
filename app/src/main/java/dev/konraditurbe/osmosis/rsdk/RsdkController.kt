package dev.konraditurbe.osmosis.rsdk

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.konraditurbe.osmosis.ble.GattClient
import dev.konraditurbe.osmosis.duml.DjiMessage
import java.util.UUID
import kotlin.random.Random

/**
 * Drives the DJI **R-SDK** control session (GPS + status), ported from Osmo-GPS-Controller-Demo's
 * `connect_logic`. Transport is the shared GATT (fff0 / notify fff4 / write fff5) via [GattClient],
 * but the frames are R-SDK ([RsdkProtocol]), not media-path DUML.
 *
 * Handshake ([connect_logic_protocol_connect]): send Connection Request (0x00/0x19) → the camera
 * shows an approval popup on first use → it sends a Connection Request back with `verify_mode=2`
 * (`verify_data=0` = approved) → we ACK it → connected. Then we subscribe to camera status
 * (0x1D/0x05) and stream GPS (0x00/0x17).
 */
class RsdkController(private val context: Context, private val listener: Listener) : GattClient.Listener {

    interface Listener {
        fun onLog(s: String)
        fun onConnected()
        fun onStatus(status: RsdkProtocol.CameraStatus)
        fun onModeInfo(info: RsdkProtocol.ModeInfo)
        fun onDisconnected()
        fun onFailed(reason: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: GattClient? = null
    private var seq = 0
    private var connected = false
    private val approvalTimeout = Runnable { fail("Camera didn't approve the R-SDK connection (approve it on-screen, then retry)") }

    // Stable per-install controller identity (the camera remembers us by these).
    private val prefs = context.getSharedPreferences("osmosis", Context.MODE_PRIVATE)
    private val deviceId: Int by lazy {
        prefs.getInt("rsdk_device_id", 0).takeIf { it != 0 }
            ?: (Random.nextInt() or 1).also { prefs.edit().putInt("rsdk_device_id", it).apply() }
    }
    private val controllerMac: ByteArray by lazy {
        prefs.getString("rsdk_mac", null)?.let { hex -> ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
            ?: ByteArray(6).also { Random.nextBytes(it); prefs.edit().putString("rsdk_mac", it.joinToString("") { b -> "%02x".format(b) }).apply() }
    }

    fun connect(device: BluetoothDevice) {
        listener.onLog("R-SDK: connecting to ${device.address}")
        val gc = GattClient(context, this, armPairing = false)
        gatt = gc
        gc.connect(device)
    }

    fun disconnect() {
        main.removeCallbacks(approvalTimeout)
        main.removeCallbacks(statusPoll)
        gatt?.disconnect(); gatt?.close(); gatt = null
    }

    private fun nextSeq(): Int { seq = (seq + 1) and 0xFFFF; return seq }

    private fun send(cmdSet: Int, cmdId: Int, cmdType: Int, payload: ByteArray, useSeq: Int = nextSeq()) {
        gatt?.writeCommand(RsdkProtocol.frame(cmdSet, cmdId, cmdType, payload, useSeq))
    }

    /** Push one GPS fix (0x00/0x17). Fields already in DJI units — see [RsdkProtocol.gpsPush].
     *  Returns whether the BLE write was actually issued, so a silently dying link is visible. */
    fun sendGps(frame: ByteArray): Boolean = gatt?.writeCommand(frame) ?: false

    fun gpsFrame(
        yearMonthDay: Int, hourMinuteSecond: Int, lon1e7: Int, lat1e7: Int, heightMm: Int,
        speedNorthCmS: Float, speedEastCmS: Float, speedDownCmS: Float,
        vertAccMm: Int, horizAccMm: Int, speedAccCmS: Int, satellites: Int,
    ): ByteArray = RsdkProtocol.frame(
        RsdkProtocol.SET_GENERAL, RsdkProtocol.ID_GPS_PUSH, RsdkProtocol.CMD_NO_RESPONSE,
        RsdkProtocol.gpsPush(yearMonthDay, hourMinuteSecond, lon1e7, lat1e7, heightMm,
            speedNorthCmS, speedEastCmS, speedDownCmS, vertAccMm, horizAccMm, speedAccCmS, satellites),
        nextSeq(),
    )

    // ---- GattClient.Listener -------------------------------------------------

    override fun onLog(s: String) = listener.onLog(s)

    override fun onReady(g: GattClient) {
        // STEP 1: send the Connection Request. verify_mode=0 → camera decides / shows a code popup.
        val verifyData = Random.nextInt(0, 10000)
        listener.onLog("R-SDK: sending connection request (approve on the camera if prompted)…")
        send(RsdkProtocol.SET_GENERAL, RsdkProtocol.ID_CONNECTION, RsdkProtocol.CMD_WAIT_RESULT,
            RsdkProtocol.connectionRequest(deviceId, controllerMac, verifyMode = 0, verifyData = verifyData))
        main.postDelayed(approvalTimeout, 40_000)
    }

    override fun onNotification(sourceChar: UUID, raw: ByteArray, parsed: DjiMessage?) {
        val f = RsdkProtocol.parse(raw)
        if (f == null) {
            // Dropping these silently made "the camera sends no status" and "it sends status we can't
            // read" the same observation. A frame the camera addressed to us and we threw away is
            // worth a line — capped, because a mis-framed link would otherwise flood the log.
            if (raw.isNotEmpty() && (raw[0].toInt() and 0xFF) == 0xAA && unparsed++ < 20) {
                val len = if (raw.size >= 3) ((raw[1].toInt() and 0xFF) or ((raw[2].toInt() and 0xFF) shl 8)) and 0x03FF else -1
                listener.onLog("R-SDK: unparsed frame, ${raw.size}B on the wire, header says ${len}B" +
                    (if (len > raw.size) " (fragmented)" else "") + " — ${raw.take(16).joinToString("") { "%02x".format(it) }}")
            }
            return
        }
        when {
            f.cmdSet == RsdkProtocol.SET_GENERAL && f.cmdId == RsdkProtocol.ID_CONNECTION -> onConnectionFrame(f)
            f.cmdSet == RsdkProtocol.SET_CAMERA && f.cmdId == RsdkProtocol.ID_STATUS_PUSH ->
                RsdkProtocol.parseCameraStatus(f.payload)?.also { lastStatusMs = System.currentTimeMillis() }
                    ?.let { main.post { listener.onStatus(it) } }
                    ?: listener.onLog("R-SDK: status push too short to decode (${f.payload.size}B)")
            // The "new" status push: an Action 6 reports its mode here as text and never sends the
            // numeric 0x1D/0x02 at all, which is why the mode read as unknown on it.
            f.cmdSet == RsdkProtocol.SET_CAMERA && f.cmdId == RsdkProtocol.ID_STATUS_PUSH_NEW ->
                RsdkProtocol.parseNewCameraStatus(f.payload)?.also { lastStatusMs = System.currentTimeMillis() }
                    ?.let { main.post { listener.onModeInfo(it) } }
                    ?: listener.onLog("R-SDK: new status push not in the documented shape " +
                        "(${f.payload.size}B) — ${f.payload.take(8).joinToString("") { "%02x".format(it) }}")
            // Anything else the camera volunteers: we asked for a subscription and this is what came
            // back, so name it rather than discard it.
            else -> if (unhandled++ < 20)
                listener.onLog("R-SDK: unhandled %02x/%02x type=0x%02x %dB payload=%s  ascii=%s"
                    .format(f.cmdSet, f.cmdId, f.cmdType, f.payload.size,
                        f.payload.joinToString("") { "%02x".format(it) },
                        f.payload.joinToString("") { b ->
                            val c = b.toInt() and 0xFF
                            if (c in 0x20..0x7E) c.toChar().toString() else "."
                        }))
        }
    }

    private var unparsed = 0
    private var unhandled = 0

    /** Subscribe: push_mode 3 = periodic + on-change, push_freq 20 (2 Hz, fixed) — DJI's own values. */
    private fun subscribeStatus() = send(
        RsdkProtocol.SET_CAMERA, RsdkProtocol.ID_STATUS_SUB, RsdkProtocol.CMD_NO_RESPONSE,
        RsdkProtocol.statusSubscription(pushMode = 3, pushFreq = 20),
    )

    private var lastStatusMs = 0L
    private var polling = false

    /**
     * Keep the status feed alive on a camera that answers the subscription **once**.
     *
     * An Action 6 replies to `0x1D/0x05` with a single `0x1D/0x06` and then never pushes again — not
     * periodically, not when the mode changes, not when recording starts — so a client that subscribes
     * and waits, as DJI's demo does, shows the mode the camera happened to be in at connect for the
     * rest of the session.
     *
     * Re-subscribing only when the feed has gone quiet leaves a camera that *does* push properly
     * completely alone: on those, this never fires a single extra frame.
     */
    private val statusPoll = object : Runnable {
        override fun run() {
            if (!connected) return
            if (System.currentTimeMillis() - lastStatusMs > STATUS_STALE_MS) {
                if (!polling && lastStatusMs != 0L) {
                    polling = true
                    listener.onLog("R-SDK: camera answers the status subscription once — polling it instead")
                }
                subscribeStatus()
            }
            main.postDelayed(this, STATUS_POLL_MS)
        }
    }

    /** The camera's side of the 0x00/0x19 handshake — its approval/rejection command frame. */
    private fun onConnectionFrame(f: RsdkProtocol.Frame) {
        if (connected) return
        if (f.isResponse) { // an early response frame: ret_code at payload[4]
            if (f.payload.size > 4 && f.payload[4].toInt() != 0) fail("Camera rejected the connection (ret_code=${f.payload[4].toInt()})")
            return
        }
        // Command frame = connection_request_command_frame: verify_mode @26, verify_data @27 (u16 LE).
        if (f.payload.size < 29) return
        val verifyMode = f.payload[26].toInt() and 0xFF
        val verifyData = (f.payload[27].toInt() and 0xFF) or ((f.payload[28].toInt() and 0xFF) shl 8)
        if (verifyMode != 2) { listener.onLog("R-SDK: unexpected verify_mode=$verifyMode"); return }
        if (verifyData != 0) { fail("Camera rejected the R-SDK connection"); return }

        // Approved → ACK with the camera's seq, then we're connected.
        main.removeCallbacks(approvalTimeout)
        send(RsdkProtocol.SET_GENERAL, RsdkProtocol.ID_CONNECTION, RsdkProtocol.ACK_NO_RESPONSE,
            RsdkProtocol.connectionResponse(deviceId, retCode = 0, cameraReserved = 0), useSeq = f.seq)
        connected = true
        listener.onLog("R-SDK: connected — subscribing to camera status")
        subscribeStatus()
        main.postDelayed(statusPoll, STATUS_POLL_MS)
        main.post { listener.onConnected() }
    }

    override fun onDisconnected() {
        main.removeCallbacks(approvalTimeout)
        main.removeCallbacks(statusPoll)
        connected = false
        main.post { listener.onDisconnected() }
    }

    private companion object {
        /** How often to check the status feed. Also the poll rate on a camera that needs one. */
        const val STATUS_POLL_MS = 1500L
        /** Quiet for longer than this and the subscription is treated as not running. */
        const val STATUS_STALE_MS = 2000L
    }

    private fun fail(reason: String) {
        main.removeCallbacks(approvalTimeout)
        disconnect()
        main.post { listener.onFailed(reason) }
    }
}
