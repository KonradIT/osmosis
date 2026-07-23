package dev.konraditurbe.osmosis.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.Handler
import android.os.Looper

/**
 * Wakes a **sleeping** Osmo camera by broadcasting DJI's documented wake advertisement, so a camera
 * that dozed off is reachable again without touching its power button.
 *
 * DJI's own R-SDK docs (`Osmo-GPS-Controller-Demo/docs/protocol_data_segment.md`, *Camera Power Mode
 * Settings (001A)*) give the packet verbatim:
 *
 * ```c
 * static uint8_t adv_data[] = { 10, 0xff, 'W','K','P','1','2','3','4','5','6' };
 * ```
 *
 * — AD length 10, AD type `0xFF` (manufacturer-specific), the ASCII magic `WKP`, then the target
 * camera's MAC **in reverse byte order**.
 *
 * **The catch:** there is *no company id*. DJI puts the magic straight after the AD type, but
 * Android's [AdvertiseData.Builder.addManufacturerData] always emits a 2-byte company id first — so
 * the obvious implementation lands a company id where the camera expects `'W','K'` and the packet is
 * silently ignored. We therefore smuggle the first two magic bytes *through* the company-id field:
 * id `0x4B57` serialises little-endian as `57 4B` = `'W','K'`, and the payload carries `'P'` + the
 * reversed MAC. On air the bytes are then byte-identical to DJI's sample (verified by [wakePacket]'s
 * unit test).
 *
 * Must be **connectable undirected** advertising (`ADV_IND`): the cameras filter on PDU type when
 * scanning for wake packets, so a non-connectable beacon never registers. We also suppress the device
 * name and TX-power AD structures to keep the payload exactly as DJI specifies.
 *
 * **Preconditions** (DJI's, not ours): the phone must have connected to this camera *recently*, and
 * the camera must have been asleep **< 30 minutes**. Outside that window the camera won't listen and
 * nothing here will help — waking then needs the power button.
 */
@SuppressLint("MissingPermission")
class CameraWaker(
    private val adapter: BluetoothAdapter?,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var active: AdvertiseCallback? = null

    /**
     * Broadcast the wake packet for [BROADCAST_MS], then invoke [onDone] (always, success or not —
     * the connect flow must continue regardless; a camera that was already awake just ignores this).
     */
    fun wake(mac: String, onDone: () -> Unit) {
        val advertiser = adapter?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser
        if (advertiser == null) {
            log("wake: BLE advertising unavailable on this phone — skipping")
            onDone(); return
        }
        val payload = wakePayload(mac) ?: run {
            log("wake: unusable MAC '$mac' — skipping")
            onDone(); return
        }

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)   // keep the packet byte-identical to DJI's sample
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(WKP_MAGIC_AS_COMPANY_ID, payload)
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)          // ADV_IND — cameras filter on PDU type
            .setTimeout(0)                 // we stop it ourselves after BROADCAST_MS
            .build()

        stop() // never run two advertisements at once
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                log("wake: broadcasting WKP for $mac (${BROADCAST_MS}ms, connectable)")
            }
            override fun onStartFailure(errorCode: Int) {
                log("wake: advertising FAILED (${advertiseError(errorCode)})")
                active = null
                main.post { onDone() }
            }
        }
        active = cb
        runCatching { advertiser.startAdvertising(settings, data, cb) }
            .onFailure {
                log("wake: advertising threw: ${it.message}")
                active = null; onDone(); return
            }
        main.postDelayed({ stop(); onDone() }, BROADCAST_MS)
    }

    fun stop() {
        val cb = active ?: return
        active = null
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
    }

    private fun advertiseError(code: Int) = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "peripheral mode unsupported"
        else -> "code $code"
    }

    companion object {
        /**
         * `'W','K'` read as a little-endian u16 — the magic rides the company-id field so the bytes
         * on air match DJI's `{ …, 0xff, 'W','K','P', mac… }` exactly. See the class doc.
         */
        const val WKP_MAGIC_AS_COMPANY_ID = 0x4B57

        /** DJI: "broadcast a specific data packet for 2 seconds" (a little margin on top). */
        const val BROADCAST_MS = 2_200L

        /** Manufacturer payload after the company id: `'P'` + the camera MAC reversed, or null. */
        fun wakePayload(mac: String): ByteArray? {
            val parts = mac.trim().split(':')
            if (parts.size != 6) return null
            val rev = runCatching { ByteArray(6) { i -> parts[5 - i].toInt(16).toByte() } }.getOrNull()
                ?: return null
            return byteArrayOf('P'.code.toByte()) + rev
        }

        /** The full on-air AD structure, for tests: `[len][0xFF]['W']['K']['P'][mac reversed]`. */
        fun wakePacket(mac: String): ByteArray? {
            val payload = wakePayload(mac) ?: return null
            val body = byteArrayOf(
                0xFF.toByte(),
                (WKP_MAGIC_AS_COMPANY_ID and 0xFF).toByte(),
                ((WKP_MAGIC_AS_COMPANY_ID shr 8) and 0xFF).toByte(),
            ) + payload
            return byteArrayOf(body.size.toByte()) + body
        }
    }
}
