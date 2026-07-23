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
        val f = RsdkProtocol.parse(raw) ?: return // ignore non-R-SDK / partial notifications
        when {
            f.cmdSet == RsdkProtocol.SET_GENERAL && f.cmdId == RsdkProtocol.ID_CONNECTION -> onConnectionFrame(f)
            f.cmdSet == RsdkProtocol.SET_CAMERA && f.cmdId == RsdkProtocol.ID_STATUS_PUSH ->
                RsdkProtocol.parseCameraStatus(f.payload)?.let { main.post { listener.onStatus(it) } }
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
        // Subscribe: push_mode 3 = periodic + on-change, push_freq 20 (2 Hz, fixed).
        send(RsdkProtocol.SET_CAMERA, RsdkProtocol.ID_STATUS_SUB, RsdkProtocol.CMD_NO_RESPONSE,
            RsdkProtocol.statusSubscription(pushMode = 3, pushFreq = 20))
        main.post { listener.onConnected() }
    }

    override fun onDisconnected() {
        main.removeCallbacks(approvalTimeout)
        connected = false
        main.post { listener.onDisconnected() }
    }

    private fun fail(reason: String) {
        main.removeCallbacks(approvalTimeout)
        disconnect()
        main.post { listener.onFailed(reason) }
    }
}
