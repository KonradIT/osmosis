package dev.konraditurbe.osmosis.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings

/**
 * BLE scanner for DJI Osmo cameras. Scans with NO hardware filter (the Pocket 3 omits
 * manufacturer data, so a filter could hide the Nano) and classifies in software:
 *  - DJI manufacturer id present -> parse model id, log raw payload (this is how we learn
 *    the Nano's model byte);
 *  - name contains "osmo"/"nano" -> treat as a camera hit;
 *  - any other named device -> logged dimly so an unexpectedly-named Nano is still visible.
 */
@SuppressLint("MissingPermission")
class OsmoScanner(
    private val adapter: BluetoothAdapter,
    private val listener: Listener,
) {
    interface Listener {
        fun onLog(s: String)
        fun onHit(device: BluetoothDevice, rssi: Int, name: String?, modelGuess: String?, modelId: Int?)
    }

    private var scanning = false
    private val seen = HashSet<String>()

    fun isScanning() = scanning

    fun start() {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            listener.onLog("Scan: no LE scanner (is Bluetooth on?)")
            return
        }
        seen.clear()
        scanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, cb)
        listener.onLog("Scan: started")
    }

    fun stop() {
        if (!scanning) return
        scanning = false
        try {
            adapter.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
        }
        listener.onLog("Scan: stopped")
    }

    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val rec = result.scanRecord
            val name = rec?.deviceName ?: safeName(dev)

            var modelGuess: String? = null
            var modelId: Int? = null
            var mfrHex: String? = null
            val msd = rec?.manufacturerSpecificData
            if (msd != null) {
                for (i in 0 until msd.size()) {
                    val cid = msd.keyAt(i)
                    val data = msd.valueAt(i) ?: continue
                    if (BleConstants.isDjiCompanyId(cid)) {
                        mfrHex = "cid=%04x %s".format(cid, data.joinToString("") { "%02x".format(it) })
                        val d = BleAdvert.decode(data)
                        modelId = d.modelId
                        modelGuess = when {
                            d.modelId != null ->
                                BleConstants.MODEL_NAMES[d.modelId] ?: "unknown(0x%04x)".format(d.modelId)
                            // New format, product type we haven't mapped. Say the number: it is the one
                            // thing that identifies the camera, and a tester's log then names it for us.
                            d.newFormat && d.rawProductType != null ->
                                "unknown(productType=%d)".format(d.rawProductType)
                            else -> null
                        }
                    }
                }
            }

            // Recognize DJI *and* Xtra (rebrand): by OUI (EC:9E:EA)/name via Brand, or — most robustly
            // — the DJI company id in the mfr data (mfrHex is only set when that cid matched).
            val brand = Brand.of(dev.address, name, djiCid = mfrHex != null)
            val looksCamera = brand != Brand.UNKNOWN || modelGuess != null

            val key = dev.address
            if (!seen.add(key)) return // one line per device per scan

            if (looksCamera) {
                listener.onLog(
                    "HIT [%s] %s rssi=%d name=%s model=%s%s".format(
                        brand, dev.address, result.rssi, name ?: "?", modelGuess ?: "?",
                        mfrHex?.let { "  mfr[$it]" } ?: ""
                    )
                )
                listener.onHit(dev, result.rssi, name, modelGuess, modelId)
            } else if (name != null) {
                listener.onLog("  (other) %s %s".format(dev.address, name))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener.onLog("Scan: FAILED code=$errorCode")
        }
    }

    private fun safeName(dev: BluetoothDevice): String? = try {
        dev.name
    } catch (_: SecurityException) {
        null
    }
}
