package dev.konraditurbe.osmosis.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.GridView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.net.LinkProperties
import android.net.Network
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.konraditurbe.osmosis.R
import dev.konraditurbe.osmosis.ble.Brand
import dev.konraditurbe.osmosis.ble.CameraModel
import dev.konraditurbe.osmosis.ble.GattClient
import dev.konraditurbe.osmosis.ble.OsmoScanner
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.CameraStatus
import dev.konraditurbe.osmosis.core.FileLog
import dev.konraditurbe.osmosis.core.SavedCameras
import dev.konraditurbe.osmosis.core.TrimRange
import dev.konraditurbe.osmosis.duml.DjiMessage
import dev.konraditurbe.osmosis.net.ApJoiner
import dev.konraditurbe.osmosis.net.HttpClient
import dev.konraditurbe.osmosis.net.DatalinkClient
import dev.konraditurbe.osmosis.net.ImageLoader
import dev.konraditurbe.osmosis.net.MediaDownloader
import dev.konraditurbe.osmosis.net.MetaLoader
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import dev.konraditurbe.osmosis.rsdk.GpsService
import dev.konraditurbe.osmosis.rsdk.GpsSyncState
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 1: scan for the Osmo Nano, auto-connect to the first camera-looking device, bring up
 * the GATT DUML channel, and stream decoded telemetry to the log. The self-test from Phase 0
 * still runs on launch to prove the DUML core on-device.
 */
class MainActivity : AppCompatActivity(), OsmoScanner.Listener, GattClient.Listener {

    private lateinit var grid: GridView
    private lateinit var overallBar: ProgressBar
    private lateinit var fileBar: ProgressBar
    private lateinit var overallText: TextView
    private lateinit var fileText: TextView
    private lateinit var progressArea: View
    private lateinit var cameraList: ListView
    private lateinit var selectorGroup: View
    private lateinit var gridGroup: View
    private lateinit var selectorHint: TextView
    private lateinit var connectBar: LinearProgressIndicator
    private lateinit var savedCameras: SavedCameras
    private lateinit var statusPill: StatusPillView
    private lateinit var btnGps: MaterialButton
    private lateinit var gpsBanner: TextView
    private var pendingGpsTarget: Pair<String, String>? = null // (mac, name) awaiting location perms

    // GPS-sync lockout: while the R-SDK link owns the camera's BLE, media browsing must be blocked
    // (both flows fight over one GATT). The service publishes its state on GpsSyncState; we mirror it
    // into a banner + a disabled selector, and turn the satellite button into the stop control.
    private val gpsStateListener = dev.konraditurbe.osmosis.rsdk.GpsSyncState.Listener { phase, name ->
        main.post { renderGpsLock(phase, name) }
    }
    private var camRows: List<CamRow> = emptyList()
    private var currentStatus = CameraStatus()
    private val main = Handler(Looper.getMainLooper())

    private var btAdapter: BluetoothAdapter? = null
    private var scanner: OsmoScanner? = null
    private var gattClient: GattClient? = null
    private var apJoiner: ApJoiner? = null
    private var connecting = false

    private val http = HttpClient("192.168.2.1") { s -> logLine(s) }
    private var imageLoader: ImageLoader? = null
    private var metaLoader: MetaLoader? = null
    private var adapter: MediaGridAdapter? = null

    // Preview screen result: add/remove the previewed item (with optional trim) from the queue.
    private val previewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        val pos = data.getIntExtra(MediaPreviewActivity.EXTRA_POSITION, -1)
        if (pos < 0) return@registerForActivityResult
        val queued = data.getBooleanExtra(MediaPreviewActivity.EXTRA_QUEUED, false)
        val s = data.getLongExtra(MediaPreviewActivity.EXTRA_TRIM_START, -1L)
        val e = data.getLongExtra(MediaPreviewActivity.EXTRA_TRIM_END, -1L)
        adapter?.setQueued(pos, queued, if (s >= 0 && e > s) TrimRange(s, e) else null)
    }

    // The datalink session keeps the camera AP alive (the Action 5 sleeps its AP the moment the
    // datalink goes idle). Held open during browse/download; closed on a new offload / exit.
    private var datalink: DatalinkClient? = null

    // Pairing PIN string sent in SetPairingPIN — an app-chosen token (any value pairs; the camera
    // shows its approval popup once, then stores it for silent re-pair). Overridable via
    // `am start ... --es pin <value>`.
    private var pairPin = "osmo"

    // End-to-end offload: BLE-pair -> wake AP -> join WiFi -> probe manifest.
    private var offloadMode = false
    private var offloadSsid = ""
    private var offloadPass = ""
    private var offloadTriggered = false
    private var currentBrand = Brand.UNKNOWN
    private var currentModel = CameraModel.DEFAULT
    private var currentModelId: Int? = null
    private var currentAddress: String? = null

    // WiFi credentials over BLE: the camera hands out its own AP SSID + passphrase when asked
    // (0x07/0x07 = SSID, 0x07/0x0e = password), learned from the official app's BLE trace. We query
    // them right after pairing so no manual password entry is needed; a saved-password / prompt path
    // is the fallback for models that don't answer.
    private var credsRequested = false

    // The Osmo 360 AP is marked WPA3-SAE, but some phones (e.g. Android 10 tablets) fail to SAE-join
    // it; on that failure we retry the same AP as WPA2 once before giving up. One-shot per offload.
    private var wpa3FallbackDone = false

    // Telemetry flood control: log each distinct DUML (flags/set/cmd) once, then every 25th.
    private val typeCounts = HashMap<Int, Int>()
    private val reqSeen = HashSet<Int>() // inbound request types already logged

    // BLE keepalive: the Nano drops an idle paired link after ~5-6s, so we ping it ~1 Hz.
    private var keepaliveOn = false
    private var lastPairStatus = -99
    private val keepalive = object : Runnable {
        override fun run() {
            // Mimo keeps the paired link alive with 0x00/0x2b `01 01` roughly every 0.5-1 s (HCI
            // snoop), not by re-sending SetPairingPIN as we used to — re-pairing every tick is both
            // noisier and, on a sleeping camera, part of what got us dropped.
            gattClient?.writeCommand(
                dev.konraditurbe.osmosis.duml.OsmoCommands.sessionPing(
                    dev.konraditurbe.osmosis.duml.OsmoCommands.SESSION_KEEPALIVE
                )
            )
            main.postDelayed(this, 1000)
        }
    }

    private fun startKeepalive() {
        if (keepaliveOn) return
        keepaliveOn = true
        logLine("keepalive: started (0x00/0x2b every 1s, Mimo-style)")
        main.postDelayed(keepalive, 1000)
    }

    private fun stopKeepalive() {
        if (!keepaliveOn) return
        keepaliveOn = false
        main.removeCallbacks(keepalive)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Keep the screen on: the WifiNetworkSpecifier consent dialog is dismissed if the display
        // sleeps, which aborts the join.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        grid = findViewById(R.id.grid)
        overallBar = findViewById(R.id.overallBar)
        fileBar = findViewById(R.id.fileBar)
        overallText = findViewById(R.id.overallText)
        fileText = findViewById(R.id.fileText)
        progressArea = findViewById(R.id.progressArea)
        cameraList = findViewById(R.id.cameraList)
        selectorGroup = findViewById(R.id.selectorGroup)
        gridGroup = findViewById(R.id.gridGroup)
        selectorHint = findViewById(R.id.selectorHint)
        connectBar = findViewById(R.id.connectBar)
        statusPill = findViewById(R.id.statusPill)
        savedCameras = SavedCameras(getSharedPreferences("osmosis", MODE_PRIVATE))
        findViewById<View>(R.id.btnRescan).setOnClickListener { startCameraScan(select = true) }
        cameraList.setOnItemClickListener { _, _, pos, _ -> onCamRowClick(pos) }
        cameraList.setOnItemLongClickListener { _, _, pos, _ -> onCamRowLongClick(pos) }
        findViewById<View>(R.id.fabDownload).setOnClickListener { onDownloadClicked() }
        findViewById<View>(R.id.btnAll).setOnClickListener { adapter?.toggleAll() }
        // "Save logs" toggle → persist all log lines to a rotating .log file in external files dir.
        val prefs = getSharedPreferences("osmosis", MODE_PRIVATE)
        val saveLogs = findViewById<MaterialSwitch>(R.id.switchSaveLogs)
        saveLogs.isChecked = prefs.getBoolean("save_logs", false)
        if (saveLogs.isChecked) startFileLogging() // set state before the listener so this isn't double-fired
        saveLogs.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("save_logs", checked).apply()
            if (checked) startFileLogging() else stopFileLogging()
        }

        // 🛰️ GPS-sync mode (R-SDK): when on, picking a camera starts the GPS foreground service
        // instead of the usual WiFi offload. Default off.
        btnGps = findViewById(R.id.btnGps)
        gpsBanner = findViewById(R.id.gpsBanner)
        btnGps.isChecked = prefs.getBoolean("gps_mode", false)
        btnGps.addOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("gps_mode", checked).apply()
            // While a GPS link is bound, the satellite button is the STOP control: unchecking it ends
            // the service (the only action allowed during the lockout).
            if (!checked && dev.konraditurbe.osmosis.rsdk.GpsSyncState.locked) {
                logLine("GPS sync: stop requested (satellite tapped).")
                GpsService.stop(this)
            }
        }

        btAdapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        logLine("Osmosis $packageName started")
        selfTestDuml()

        // Test hooks: `--es pin <v>` overrides the pairing PIN; `--ez autoscan true` auto-starts
        // a scan (perms permitting) so device testing doesn't depend on tapping. Dormant otherwise.
        intent?.getStringExtra("pin")?.let { pairPin = it; logLine("pairPin set to \"$it\"") }
        if (intent?.getBooleanExtra("autoscan", false) == true) {
            main.postDelayed({ startCameraScan(select = true) }, 500)
        }
        // WiFi-only flow (AP already awake): `--ez wifi true --es ssid X --es pass <psk>`.
        if (intent?.getBooleanExtra("wifi", false) == true) {
            val ssid = intent.getStringExtra("ssid") ?: "OsmoNano-C2D8"
            val pass = intent.getStringExtra("pass") ?: ""
            main.postDelayed({ startWifiFlow(ssid, pass) }, 500)
        }
        // Full offload (test): `--ez offload true [--es pick <name|brand>]` — auto-picks a camera.
        if (intent?.getBooleanExtra("offload", false) == true) {
            logLine("OFFLOAD (intent)")
            val pick = intent.getStringExtra("pick")
            main.postDelayed({ startCameraScan(select = true, pick = pick) }, 500)
        }
        // Camera selector is the launch screen: show saved cameras, then scan to mark which are in
        // range and surface new ones (unless a test hook is already driving a scan/flow).
        rebuildCameraList()
        val hookDriving = intent?.getBooleanExtra("autoscan", false) == true ||
            intent?.getBooleanExtra("offload", false) == true ||
            intent?.getBooleanExtra("wifi", false) == true
        if (!hookDriving) startCameraScan(select = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Deliberately NOT closing the log file here: GPS sync runs as a foreground service and the
        // user is usually out with the Activity long gone, so the file has to stay open for it. Every
        // line is flushed, so nothing is lost if the process dies; the toggle closes it explicitly.
        stopKeepalive()
        datalink?.close()
        scanner?.stop()
        gattClient?.disconnect()
        gattClient?.close()
        apJoiner?.release()
        imageLoader?.shutdown()
        metaLoader?.shutdown()
    }

    override fun onStart() {
        super.onStart()
        // Registering re-delivers the current phase immediately, so returning to the app restores the
        // lockout if a GPS link is still bound.
        dev.konraditurbe.osmosis.rsdk.GpsSyncState.addListener(gpsStateListener)
    }

    override fun onStop() {
        super.onStop()
        dev.konraditurbe.osmosis.rsdk.GpsSyncState.removeListener(gpsStateListener)
    }

    /**
     * Mirror the GPS-sync service's state into the UI: a coloured banner and a disabled selector while
     * a link is bound (STARTING/ACTIVE), cleared when it stops. The satellite button stays live — it's
     * the only way out of the lockout.
     */
    private fun renderGpsLock(phase: GpsSyncState.Phase, name: String?) {
        val locked = phase != GpsSyncState.Phase.STOPPED
        val who = name ?: "the camera"
        when (phase) {
            GpsSyncState.Phase.ACTIVE -> {
                gpsBanner.setBackgroundColor(ContextCompat.getColor(this, R.color.osmo_danger))
                gpsBanner.text = "🛰️ GPS sync active with $who\nMedia browsing is disabled while GPS is streaming. Tap 🛰️ to stop."
                gpsBanner.visibility = View.VISIBLE
            }
            GpsSyncState.Phase.STARTING -> {
                gpsBanner.setBackgroundColor(ContextCompat.getColor(this, R.color.osmo_amber))
                gpsBanner.text = "🛰️ GPS sync connecting to $who… approve on the camera if it prompts. Tap 🛰️ to cancel."
                gpsBanner.visibility = View.VISIBLE
            }
            GpsSyncState.Phase.STOPPED -> gpsBanner.visibility = View.GONE
        }
        // Lock camera selection + rescan while the link owns the BLE; keep btnGps checked so its tap
        // reads as "stop". (onCamRowClick / onCameraChosen also hard-guard, in case a tap slips through.)
        cameraList.isEnabled = !locked
        cameraList.alpha = if (locked) 0.4f else 1f
        findViewById<View>(R.id.btnRescan).apply { isEnabled = !locked; alpha = if (locked) 0.4f else 1f }
        if (locked && !btnGps.isChecked) btnGps.isChecked = true
    }

    private fun selfTestDuml() {
        try {
            val payload = dev.konraditurbe.osmosis.duml.DjiPairMessagePayload("love").encode()
            val msg = DjiMessage(target = 0x0702, id = 0x8092, type = 0x450740, payload = payload)
            val bytes = msg.encode()
            val decoded = DjiMessage.fromBytes(bytes)
            logLine("DUML self-test ok (${bytes.size} B): ${decoded.format()}")
        } catch (t: Throwable) {
            logLine("DUML self-test FAILED: ${t.message}")
        }
    }

    // ---- Scan / permissions -------------------------------------------------

    private data class Cam(val device: BluetoothDevice, val name: String?, val brand: Brand, val rssi: Int, val modelId: Int?, val model: CameraModel)
    private val discovered = LinkedHashMap<String, Cam>()
    private var autoPick: String? = null

    /** Scan ~4s for DJI/Xtra cameras (bonds aren't reliable for these), then feed the selector list. */
    private fun startCameraScan(select: Boolean, pick: String? = null) {
        val adapter = btAdapter ?: run { logLine("No Bluetooth adapter."); return }
        if (!adapter.isEnabled) { logLine("Bluetooth is OFF — enable it and try again."); return }
        val missing = requiredPerms().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
            return
        }
        autoPick = pick
        discovered.clear()
        connecting = false
        selectorHint.text = "Scanning…"
        rebuildCameraList()
        val s = OsmoScanner(adapter, this); scanner = s; s.start()
        main.postDelayed({
            s.stop()
            if (connecting) return@postDelayed // auto-pick already connected
            rebuildCameraList()
            // Test-hook auto-pick (`--es pick <name|brand>`) connects without a tap.
            autoPick?.let { pk ->
                discovered.values.firstOrNull {
                    (it.name ?: "").contains(pk, true) || it.brand.name.equals(pk, true)
                }?.let { onCameraChosen(it.device) }
            }
        }, 4000)
    }

    /** Selector list: saved cameras first (📶 in range / 🚫 not), then newly-scanned ones tagged NEW. */
    private fun rebuildCameraList() {
        val scanned = discovered.values.toList()
        val byMac = scanned.associateBy { it.device.address }
        val saved = savedCameras.all()
        val savedMacs = saved.mapTo(HashSet()) { it.mac }
        val savedRows = saved.map { e ->
            val c = byMac[e.mac]
            CamRow(e.mac, c?.name ?: e.name,
                c?.model ?: CameraModel.resolve(e.modelId.takeIf { it >= 0 }, e.name, Brand.of(e.mac, e.name)),
                inRange = c != null, saved = true, device = c?.device)
        }
        val newRows = scanned.filter { it.device.address !in savedMacs }.map { c ->
            CamRow(c.device.address, c.name, c.model, inRange = true, saved = false, device = c.device)
        }
        camRows = savedRows + newRows
        cameraList.adapter = CameraListAdapter(camRows)
        if (scanner?.isScanning() != true) {
            selectorHint.text = if (camRows.isEmpty()) "No cameras yet — turn one on and tap Rescan."
            else "${savedRows.count { it.inRange }}/${savedRows.size} saved in range · ${newRows.size} new"
        }
    }

    private fun onCamRowClick(pos: Int) {
        // Locked out while a GPS link is bound — the satellite button is the only way forward.
        if (dev.konraditurbe.osmosis.rsdk.GpsSyncState.locked) {
            toast("GPS sync is active — tap 🛰️ to stop before selecting a camera.")
            return
        }
        val r = camRows.getOrNull(pos) ?: return
        // 🛰️ GPS-sync mode: connect over R-SDK (BLE only, no WiFi) via the foreground service.
        if (btnGps.isChecked) {
            if (r.device != null || r.saved) startGpsMode(r.mac, r.name ?: r.mac)
            else Toast.makeText(this, "${r.name ?: r.mac} isn't in range — turn it on, then Rescan", Toast.LENGTH_SHORT).show()
            return
        }
        val dev = r.device
        if (dev != null) onCameraChosen(dev)
        else Toast.makeText(this, "${r.name ?: r.mac} isn't in range — turn it on, then Rescan", Toast.LENGTH_SHORT).show()
    }

    /** Start the R-SDK GPS-sync foreground service for [mac], requesting location/notification perms first. */
    private fun startGpsMode(mac: String, name: String) {
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingGpsTarget = mac to name
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_GPS_PERMS)
            return
        }
        logLine("GPS sync: connecting R-SDK to $name ($mac)")
        // Cross-flow interlock: free the BLE GATT from any offload session first, so the R-SDK link
        // owns it exclusively. Running both at once is what caused the field disconnections.
        teardownOffload()
        GpsService.start(this, mac, name)
        Toast.makeText(this, "GPS sync starting for $name — approve on the camera if it prompts", Toast.LENGTH_LONG).show()
    }

    /** Drop any live WiFi-offload session (BLE GATT + datalink + WiFi request) so the R-SDK GPS flow
     *  can take the camera's single BLE link without contention. Safe to call when nothing is active. */
    private fun teardownOffload() {
        stopKeepalive()
        datalink?.close(); datalink = null
        apJoiner?.release(); apJoiner = null
        gattClient?.disconnect(); gattClient?.close(); gattClient = null
        offloadMode = false; offloadTriggered = false; connecting = false
        setConnectProgress(0)
    }

    private fun onCamRowLongClick(pos: Int): Boolean {
        val r = camRows.getOrNull(pos) ?: return false
        if (!r.saved) return false
        AlertDialog.Builder(this)
            .setTitle("${r.model.name}  (${r.name ?: r.mac})")
            .setItems(arrayOf("Re-enter WiFi password", "Forget camera")) { _, i ->
                when (i) {
                    0 -> promptPasswordFor(r.mac) { logLine("Password updated.") }
                    1 -> {
                        savedCameras.remove(r.mac)
                        getSharedPreferences("osmosis", MODE_PRIVATE).edit().remove("pass_${r.mac}").apply()
                        logLine("Forgot ${r.name ?: r.mac}")
                        rebuildCameraList()
                    }
                }
            }.show()
        return true
    }

    private fun switchToGrid() { selectorGroup.visibility = View.GONE; gridGroup.visibility = View.VISIBLE }

    private fun switchToSelector() {
        gridGroup.visibility = View.GONE
        selectorGroup.visibility = View.VISIBLE
        startCameraScan(select = true)
    }

    override fun onBackPressed() {
        if (gridGroup.visibility == View.VISIBLE) switchToSelector() else super.onBackPressed()
    }

    private fun safeName(d: BluetoothDevice): String? = try { d.name } catch (_: SecurityException) { null }

    private fun onCameraChosen(device: BluetoothDevice) {
        if (dev.konraditurbe.osmosis.rsdk.GpsSyncState.locked) {
            toast("Stop GPS sync (tap 🛰️) before browsing media.")
            return
        }
        val cam = discovered[device.address]
        currentBrand = Brand.of(device.address, cam?.name ?: safeName(device))
        currentModel = cam?.model ?: CameraModel.resolve(null, safeName(device), currentBrand)
        currentModelId = cam?.modelId
        currentAddress = device.address
        offloadSsid = cam?.name ?: safeName(device) ?: "camera"
        // No up-front password prompt: the camera hands us the passphrase over BLE after pairing
        // (see onPaired). savedPassFor seeds the fallback for models that don't expose it.
        connectAndOffload(device)
    }

    private fun connectAndOffload(device: BluetoothDevice) {
        datalink?.close()   // end any prior camera's datalink session
        apJoiner?.release() // drop any prior camera's WiFi request + process binding
        offloadPass = savedPassFor(device.address)
        offloadMode = true
        offloadTriggered = false
        credsRequested = false
        wpa3FallbackDone = false
        connecting = true
        setConnectProgress(3) // tap → connecting
        logLine("OFFLOAD [$currentBrand] $offloadSsid (${device.address})")
        // No wake broadcast here: an HCI snoop of Mimo waking a sleeping Nano showed it never
        // advertises. The sleeping camera keeps advertising ADV_IND itself, and Mimo simply connects
        // and drives it with DUML (0x00/0x2b -> pair -> 0x53/0x10). That's the sequence we follow in
        // onReady/onPaired. (DJI also documents a 'WKP' wake *broadcast*; an HCI snoop proved Mimo
        // never advertises, so it isn't used here — see ROADMAP #10.)
        val gc = GattClient(this, this)
        gattClient = gc
        gc.connect(device)
    }

    /** Password is stored per-camera (by MAC). No global fallback — that would leak one camera's
     *  password to another (e.g. the Nano's onto the Xtra). */
    private fun savedPassFor(addr: String): String =
        getSharedPreferences("osmosis", MODE_PRIVATE).getString("pass_$addr", "") ?: ""

    /** Per-camera password capture (keyed by MAC). SSID comes from the BLE device name. */
    private fun promptPasswordFor(addr: String, onSaved: () -> Unit) {
        val prefs = getSharedPreferences("osmosis", MODE_PRIVATE)
        val input = EditText(this).apply {
            hint = "Wi-Fi password"; setText(savedPassFor(addr)); setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Wi-Fi password for $offloadSsid")
            .setMessage("Shown on the camera screen under Connection settings. Saved per-camera.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val p = input.text.toString().trim()
                if (p.isEmpty()) { logLine("Password empty — not saved."); return@setPositiveButton }
                prefs.edit().putString("pass_$addr", p).apply()
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requiredPerms(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_GPS_PERMS) {
            val target = pendingGpsTarget; pendingGpsTarget = null
            if (target != null && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startGpsMode(target.first, target.second)
            } else logLine("GPS sync: location permission denied.")
            return
        }
        if (requestCode != REQ_PERMS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCameraScan(select = true)
        } else {
            logLine("Permissions denied — cannot scan.")
        }
    }

    // ---- WiFi manifest flow -------------------------------------------------

    /**
     * Paired — fetch the camera's WiFi SSID + passphrase over BLE (0x07/0x07, 0x07/0x0e) so no manual
     * entry is needed. The replies land in [onNotification] and drive the join. If the model doesn't
     * answer (older cameras), a fallback timer uses the saved password or prompts. Called once.
     */
    private fun onPaired() {
        if (!offloadMode || credsRequested) return
        credsRequested = true
        logLine("Paired — running Mimo's post-pair sequence, then reading WiFi creds…")
        // Paced writes: fff5 is write-without-response, so back-to-back frames drop, and an immediate
        // one also races the pairing-approval ACK. Order + spacing mirror the Mimo HCI snoop:
        //   0x53/0x10 -> (creds) 0x07/0x07 -> 0x07/0x0e
        // 0x53/0x10 is the one that matters: the camera answers 01 00 00 00 and wakes.
        val c = dev.konraditurbe.osmosis.duml.OsmoCommands
        main.postDelayed({ gattClient?.writeCommand(c.session5310()); logLine("sent 0x53/0x10 (wake)") }, 100)
        main.postDelayed({ gattClient?.writeCommand(c.wifiQuery(0x07, id = 0x8007)) }, 900)
        main.postDelayed({ gattClient?.writeCommand(c.wifiQuery(0x0E, id = 0x800E)) }, 1400)
        main.postDelayed({
            if (offloadTriggered) return@postDelayed
            val addr = currentAddress
            when {
                offloadPass.isNotEmpty() -> { logLine("No BLE creds — using the saved password."); maybeStartOffload() }
                addr != null -> { logLine("No BLE creds — asking for the password."); promptPasswordFor(addr) { offloadPass = savedPassFor(addr); maybeStartOffload() } }
            }
        }, 4500)
    }

    /** Parse a `[status:1][PackString]` reply (0x07/0x07 SSID, 0x07/0x0e password): status byte, then
     *  a length-prefixed string. Returns null if malformed. */
    private fun parseStatusPackString(p: ByteArray): String? {
        if (p.size < 2) return null
        val len = p[1].toInt() and 0xFF
        if (2 + len > p.size) return null
        return String(p, 2, len, Charsets.US_ASCII)
    }

    private fun maybeStartOffload() {
        if (!offloadMode || offloadTriggered) return
        offloadTriggered = true
        setConnectProgress(28) // paired → waking the AP
        // The wake/AP now comes from the session sequence in onPaired() (0x00/0x2b to 0xF0, then
        // 0x53/0x10 to 0x1C). ConnectToWiFi (0x07/0x47) is NOT in Mimo's flow at all and correlated
        // with a sleeping camera terminating the link (status=19), so it's only a fallback for
        // models that never surfaced creds over BLE.
        if (offloadPass.isEmpty()) {
            logLine("OFFLOAD: no BLE creds — falling back to ConnectToWiFi(0x07/47)")
            gattClient?.writeCommand(
                dev.konraditurbe.osmosis.duml.OsmoCommands.connectWifi(offloadSsid, offloadPass)
            )
        } else {
            logLine("OFFLOAD: paired -> AP up via the session sequence (0x00/0x2b + 0x53/0x10)")
        }
        // AP needs a few seconds to come up; the WifiNetworkSpecifier dialog keeps searching
        // until it appears, so a modest delay before requesting the network is fine.
        main.postDelayed({ promptWifiConsent(offloadSsid, offloadPass) }, 3000)
    }

    /** Friendly heads-up before Android's WifiNetworkSpecifier consent dialog appears. */
    private fun promptWifiConsent(ssid: String, pass: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Connect to the camera's Wi-Fi")
            .setMessage(
                "The camera's Wi-Fi is now on. Android will show a system popup to join " +
                    "“$ssid” — tap Connect on it to browse and download your media.\n\n" +
                    "(It may say “Searching for device…” for a few seconds first.)"
            )
            .setPositiveButton("Continue") { _, _ -> startWifiFlow(ssid, pass) }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()
    }

    private fun startWifiFlow(ssid: String, pass: String) {
        apJoiner?.release() // release any prior request so only one WiFi specifier is pending
        setConnectProgress(35) // requesting the WiFi join
        logLine("WiFi flow: ssid=\"$ssid\" passLen=${pass.length}")
        val joiner = ApJoiner(this, object : ApJoiner.Listener {
            override fun onLog(s: String) = logLine(s)
            override fun onFailed(reason: String) { logLine(reason); main.post { onWifiJoinFailed() } }
            override fun onNetwork(network: Network, link: LinkProperties?) {
                val ip4 = link?.linkAddresses?.map { it.address }
                    ?.firstOrNull { it is java.net.Inet4Address }
                setConnectProgress(58) // WiFi joined + bound
                logLine("WiFi link: ip=${ip4?.hostAddress}")
                Thread {
                    // Datalink port + poke come from the model AND brand: 10004/no-poke was only ever
                    // confirmed on the Xtra rebrand (own OUI EC:9E:EA), so a genuine DJI unit gets the
                    // DJI-standard 9004+poke. Either guess can be wrong on an untested model, so if the
                    // handshake never lands we retry the alternate config and log which port answered.
                    fun open(m: CameraModel): Pair<DatalinkClient, List<CameraFile>> {
                        logLine("=== media list [${m.name}] via udp/${m.datalinkPort} (poke=${m.tcpPoke}) ===")
                        val c = DatalinkClient(::logLine, m.datalinkPort, m.tcpPoke)
                        c.onStatus = { s -> main.post { onCameraStatus(s) } }
                        c.onFetchProgress = { fp -> setConnectProgress(60 + fp * 38 / 100) } // 60→98
                        val f = runCatching { c.fetchFileList("192.168.2.1") }
                            .getOrElse { logLine("datalink error: ${it.message}"); emptyList() }
                        return c to f
                    }

                    datalink?.close()
                    var (dl, files) = open(currentModel)
                    if (!dl.handshakeOk) {
                        val alt = currentModel.alternate()
                        logLine("datalink: nothing answered on udp/${currentModel.datalinkPort} — trying udp/${alt.datalinkPort}")
                        runCatching { dl.close() }
                        val retry = open(alt)
                        dl = retry.first; files = retry.second
                        if (dl.handshakeOk) logLine(
                            "datalink: *** ${currentModel.name} actually speaks udp/${alt.datalinkPort} " +
                                "(poke=${alt.tcpPoke}) — please report so the model table can be fixed ***"
                        )
                    }
                    datalink = dl
                    if (files.isNotEmpty()) dl.startKeepAlive() // hold the AP up while browsing
                    // Storage is per manifest *list*, not per manifest: a camera with a card returns an
                    // SD list and an internal list back to back, and probing only the first file stamped
                    // that one store on every file — so whichever half didn't match 404'd (blank
                    // thumbnails, failed downloads). Probe once per list instead: 1-2 HEADs per group.
                    storageByGroup = emptyMap()                 // fresh list → reset the group→store cache
                    val fixed = applyStorageAndSort(files)
                    logLine("MANIFEST: ${fixed.size} files — " +
                        storageByGroup.entries.joinToString(", ") { (g, s) ->
                            "list $g on storage=$s (${files.count { it.group == g }} files)"
                        } + (if (dl.moreAvailable) " · more on scroll" else ""))
                    main.post { showGrid(fixed) }
                }.start()
            }
        })
        apJoiner = joiner
        val useWpa3 = currentModel.wpa3 && !wpa3FallbackDone
        joiner.join(ssid, pass, useWpa3)
    }

    /**
     * WiFi join failed (WifiNetworkSpecifier `onUnavailable` — wrong password, AP down, or the user
     * dismissed the system dialog; Android can't tell them apart). If we joined with a *saved*
     * password, the usual cause is a stale one — the camera was factory-reset and regenerated it — so
     * offer to re-enter it and retry, instead of silently stranding the saved camera. The AP is still
     * up from the ConnectToWiFi we just sent, so retrying the join alone (no re-pair) works once the
     * password is right. First-time cameras (no saved password) already prompt up front, so there's
     * nothing stale to fix — just leave the user on the selector.
     */
    private fun onWifiJoinFailed() {
        if (isFinishing || isDestroyed) return
        // A WPA3 AP (the 360) that won't SAE-join on this phone: retry the same join as WPA2 once,
        // silently, before falling through to the password dialog. If the 360 is actually WPA2 this
        // also self-corrects the model table's guess.
        if (currentModel.wpa3 && !wpa3FallbackDone) {
            wpa3FallbackDone = true
            logLine("WiFi: WPA3 join failed — retrying \"$offloadSsid\" as WPA2")
            startWifiFlow(offloadSsid, offloadPass)
            return
        }
        setConnectProgress(0)
        val addr = currentAddress ?: return
        if (savedPassFor(addr).isEmpty()) return
        // If we already tried BOTH securities (WPA3 then the WPA2 fallback) and still failed, the
        // password is almost certainly fine — it's the phone not joining this AP's Wi-Fi security.
        // Don't send the user chasing a password that isn't the problem (as the 360 did before).
        val bothSecuritiesTried = currentModel.wpa3 && wpa3FallbackDone
        val message = if (bothSecuritiesTried)
            "This phone couldn't join $offloadSsid over either WPA3 or WPA2 — likely a Wi-Fi " +
                "compatibility limit on the phone, not the password. You can still re-enter the " +
                "password to rule it out."
        else
            "The camera Wi-Fi join failed. This usually means the saved password is out of date " +
                "— e.g. after a camera factory reset. Re-enter it and try again?"
        AlertDialog.Builder(this)
            .setTitle("Couldn't join $offloadSsid")
            .setMessage(message)
            .setPositiveButton("Re-enter password") { _, _ ->
                promptPasswordFor(addr) {
                    offloadPass = savedPassFor(addr)
                    logLine("Retrying Wi-Fi join with the updated password…")
                    startWifiFlow(offloadSsid, offloadPass)
                }
            }
            .setNegativeButton("Back to cameras") { _, _ -> switchToSelector() }
            .setCancelable(false)
            .show()
    }

    // ---- media grid + download ---------------------------------------------

    private fun showGrid(files: List<CameraFile>) {
        // Reaching here = pairing + WiFi + datalink all worked → remember this camera, show the grid.
        setConnectProgress(100) // first media in — connection complete
        currentAddress?.let { savedCameras.save(it, offloadSsid, currentModelId) }
        switchToGrid()
        statusPill.render(pillName(), "Connected · WiFi", currentStatus, showPower = isNano())
        if (files.isEmpty()) {
            logLine("No media found on camera.")
            return
        }
        imageLoader?.shutdown()
        metaLoader?.shutdown()
        val loader = ImageLoader(http, ::logLine)
        val ml = MetaLoader(http)
        imageLoader = loader
        metaLoader = ml
        val ad = MediaGridAdapter(files, loader, ml, onOpen = { openPreview(it) }, onLongPress = { onGridLongPress(it) })
        adapter = ad
        grid.adapter = ad
        loadingMore = false
        installPullToLoadMore()
        logLine("Grid ready: ${files.size} files. Tap a cell to preview + queue, then Download. Long-press a cell to delete.")
    }

    // ---- lazy grid pagination (pull up past the last row to load older pages) --------------------
    private var storageByGroup: Map<Int, Int> = emptyMap()
    private var loadingMore = false

    /** Stamp each file's storage from its manifest group (probing a group the first time it's seen) and
     *  sort newest-first — shared by the initial fetch and every lazily-loaded older page. */
    private fun applyStorageAndSort(files: List<CameraFile>): List<CameraFile> {
        val map = storageByGroup.toMutableMap()
        val out = files.map { f -> f.copy(storage = map.getOrPut(f.group) { detectStorage(f) }) }
        storageByGroup = map
        return out.sortedWith(compareByDescending<CameraFile> { it.timestamp }.thenByDescending { it.seq })
    }

    /**
     * Pull-up-to-load-more: while the grid is scrolled to the very bottom and there are more pages, an
     * upward drag raises + fades in the bottom spinner; releasing past the threshold spins it and fetches
     * the next (older) page. We only OBSERVE touches (never consume them) so normal scrolling/taps still
     * work — the grid absorbs the scroll, and any extra past the bottom is our "pull".
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun installPullToLoadMore() {
        val spinner = findViewById<View>(R.id.loadMoreSpinner) ?: return
        val armPx = resources.displayMetrics.density * 88f       // drag distance to arm the load
        var lastY = 0f
        var pull = 0f
        fun render() {
            if (loadingMore) return
            val p = (pull / armPx).coerceIn(0f, 1f)
            if (p <= 0f) { spinner.visibility = View.GONE; return }
            spinner.visibility = View.VISIBLE
            spinner.alpha = p
            spinner.scaleX = 0.6f + 0.4f * p; spinner.scaleY = spinner.scaleX
            spinner.translationY = (1f - p) * armPx * 0.5f       // rises from below as you pull
        }
        grid.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { lastY = ev.y; pull = 0f }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = lastY - ev.y; lastY = ev.y
                    val more = datalink?.moreAvailable == true
                    if (!loadingMore && more && !grid.canScrollVertically(1) && dy > 0f)
                        pull = (pull + dy).coerceAtMost(armPx * 1.4f)
                    else if (pull > 0f && dy < 0f)
                        pull = (pull + dy).coerceAtLeast(0f)
                    render()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (!loadingMore && pull >= armPx) loadMorePages()
                    else if (!loadingMore) spinner.animate().alpha(0f).setDuration(150)
                        .withEndAction { spinner.visibility = View.GONE }.start()
                    pull = 0f
                }
            }
            false   // never consume — grid keeps handling scroll + cell taps
        }
    }

    /** Fetch + append the next older page (guarded against re-entrancy); spinner spins meanwhile. */
    private fun loadMorePages() {
        val dl = datalink ?: return
        if (loadingMore || !dl.moreAvailable) return
        loadingMore = true
        findViewById<View>(R.id.loadMoreSpinner)?.apply {
            visibility = View.VISIBLE; alpha = 1f; scaleX = 1f; scaleY = 1f; translationY = 0f
        }
        Thread {
            val more = runCatching { applyStorageAndSort(dl.fetchNextPage()) }.getOrElse { emptyList() }
            main.post {
                adapter?.append(more)
                findViewById<View>(R.id.loadMoreSpinner)?.animate()?.alpha(0f)?.setDuration(180)
                    ?.withEndAction { findViewById<View>(R.id.loadMoreSpinner)?.visibility = View.GONE }?.start()
                if (more.isNotEmpty()) logLine("Loaded ${more.size} older (${adapter?.count ?: 0} total)")
                else logLine("No more media to load.")
                loadingMore = false
            }
        }.start()
    }

    private fun pillName() = "${currentModel.name} ${offloadSsid.substringAfterLast('-', "")}".trim()

    /** Live camera status → refresh the pill (only while the gallery is showing). */
    private fun onCameraStatus(s: CameraStatus) {
        currentStatus = s
        if (gridGroup.visibility == View.VISIBLE)
            statusPill.render(pillName(), "Connected · WiFi", s, showPower = isNano())
    }

    /** The `0x0d/0x02` power/dock frame was only mapped on the Nano, so its pill line is Nano-only. */
    private fun isNano() = currentModelId == CameraModel.ID_OSMO_NANO

    /**
     * Connection progress shown in the selector (between the hint and the camera list), from tapping
     * a camera through pairing, WiFi join, and the datalink manifest to the first media. 0 hides it,
     * 100 completes and hides (the grid takes over).
     */
    private fun setConnectProgress(pct: Int) = main.post {
        when {
            pct <= 0 -> connectBar.visibility = View.GONE
            pct >= 100 -> { connectBar.setProgressCompat(100, true); connectBar.visibility = View.GONE }
            else -> {
                if (connectBar.visibility != View.VISIBLE) { connectBar.visibility = View.VISIBLE; connectBar.progress = 0 }
                connectBar.setProgressCompat(pct, true)
            }
        }
    }

    /** Open the full-screen preview for the tapped cell; queue changes flow back via the launcher. */
    private fun openPreview(position: Int) {
        val ad = adapter ?: return
        val f = ad.getItem(position)
        previewLauncher.launch(
            MediaPreviewActivity.intent(this, "192.168.2.1", f, position, ad.isQueued(position), ad.trimFor(position)))
    }

    /**
     * Long-press a cell → delete that file from the camera (DUML 0x00/0x28). Guarded by a confirm
     * dialog and irreversible; only offered when the manifest yielded a delete handle for the file.
     * The delete rides the open datalink (single socket owner is the keep-alive thread).
     */
    private fun onGridLongPress(position: Int) {
        val ad = adapter ?: return
        val f = ad.getItem(position)
        if (!f.deletable) {
            logLine("Delete: no handle for ${f.name} — not deletable.")
            toast("Can't delete ${f.name} — no handle")
            return
        }
        val dl = datalink ?: run { logLine("Delete: no live datalink session."); return }
        val hx = "0x%08x".format(f.handle)
        AlertDialog.Builder(this)
            .setTitle("Delete from camera?")
            .setMessage("${f.name}\nhandle $hx\n\nThis permanently deletes the file on the camera's card. It cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                logLine("DELETE requested: ${f.name} (handle $hx)")
                toast("Deleting ${f.name}…")
                Thread {
                    val status = runCatching { dl.deleteFiles(listOf(f.handle)) }.getOrNull()
                    main.post {
                        when (status) {
                            0 -> {
                                logLine("DELETE OK (status 0x0000): ${f.name}")
                                toast("Deleted ${f.name}")
                                removeFromGrid(position)
                            }
                            null -> { logLine("DELETE: no response (timeout / no session)."); toast("Delete: no response") }
                            else -> {
                                logLine("DELETE failed: status 0x%04x for %s".format(status, f.name))
                                toast("Delete failed (0x%04x)".format(status))
                            }
                        }
                    }
                }.start()
            }
            .show()
    }

    /**
     * Drop one cell after a confirmed delete by rebuilding the grid without it. The remaining files'
     * handles are zeroed: a delete can shift the camera's object table (Mimo re-lists for this very
     * reason), so we require a reconnect to refresh handles before another delete — rather than risk
     * acting on a stale one. Size labels and preview keep working.
     */
    private fun removeFromGrid(position: Int) {
        val ad = adapter ?: return
        val remaining = (0 until ad.count).filter { it != position }.map { ad.getItem(it).copy(handle = 0L) }
        showGrid(remaining)
    }

    private fun toast(s: String) =
        main.post { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show() }

    /** Returns the camera mount index (0 or 1) that serves this file's path — by asking, since the
     *  index is not a fixed SD/internal mapping (an Xtra served SD at 0, internal at 1). */
    private fun detectStorage(f: CameraFile): Int {
        for (s in intArrayOf(1, 0)) {
            if (http.headCode("/v2?storage=$s&path=${f.path}") == 200) return s
        }
        return 0
    }

    private fun onDownloadClicked() {
        val ad = adapter ?: run { logLine("Nothing listed yet — tap Offload first."); return }
        val jobs = ad.selectedEntries().map { MediaDownloader.Job(it.first, it.second) }
        if (jobs.isEmpty()) {
            logLine("No files queued (tap a cell to preview + queue).")
            return
        }
        val trimmed = jobs.count { it.trim != null }
        logLine("Downloading ${jobs.size} item(s)${if (trimmed > 0) " ($trimmed trimmed)" else ""} to gallery...")
        val listener = object : MediaDownloader.Progress {
            private var totalBytes = 0L
            private var fileTotal = 0L
            private var count = 0
            private var lastO = -1
            private var lastF = -1

            override fun onStart(totalFiles: Int, tb: Long) {
                totalBytes = tb; count = totalFiles
                main.post {
                    progressArea.visibility = View.VISIBLE
                    overallText.text = "Overall: 0/$totalFiles — ${fmtBytes(tb)}"
                    overallBar.progress = 0; fileBar.progress = 0
                }
            }

            override fun onFileStart(index: Int, name: String, fileBytes: Long) {
                fileTotal = fileBytes; lastF = -1
                main.post {
                    overallText.text = "Overall: ${index + 1}/$count files"
                    fileText.text = name; fileBar.progress = 0
                }
            }

            override fun onTick(fileDone: Long, overallDone: Long) {
                val op = if (totalBytes > 0) (overallDone * 100 / totalBytes).toInt() else 0
                val fp = if (fileTotal > 0) (fileDone * 100 / fileTotal).toInt() else 0
                if (op == lastO && fp == lastF) return
                lastO = op; lastF = fp
                main.post {
                    overallBar.progress = op
                    fileBar.progress = fp
                    fileText.text = "$fp% — ${fmtBytes(fileDone)}/${fmtBytes(fileTotal)}"
                }
            }

            override fun onComplete(saved: Int, skipped: Int, failed: Int) {
                main.post {
                    overallBar.progress = 100
                    overallText.text = "Done: $saved saved, $skipped skipped, $failed failed"
                    fileText.text = ""
                    main.postDelayed({ progressArea.visibility = View.INVISIBLE }, 3000)
                }
                logLine("DONE: $saved saved, $skipped skipped, $failed failed")
            }
        }
        Thread { MediaDownloader(this, http, ::logLine).run(jobs, listener) }.start()
    }

    private fun fmtBytes(b: Long): String = when {
        b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
        b >= 1_000_000 -> "%.0f MB".format(b / 1e6)
        b >= 1_000 -> "%.0f KB".format(b / 1e3)
        else -> "$b B"
    }

    // ---- OsmoScanner.Listener ----------------------------------------------

    override fun onHit(device: BluetoothDevice, rssi: Int, name: String?, modelGuess: String?, modelId: Int?) {
        val addr = device.address
        // Brand matters, not just the model id: the Xtra rebrand shares model 0x0015 with the DJI
        // Osmo Action 5 Pro but uses a different datalink port. Its OUI gives it away.
        val model = CameraModel.resolve(modelId, name, Brand.of(addr, name))
        if (discovered.put(addr, Cam(device, name, Brand.of(addr, name), rssi, modelId, model)) == null) {
            logLine("found ${model.name} [${Brand.of(addr, name)}] (${name ?: addr}) rssi=$rssi" +
                if (!model.verified) "  ~experimental" else "")
            main.post { rebuildCameraList() }
        }
    }

    // ---- GattClient.Listener -----------------------------------------------

    override fun onReady(gatt: GattClient) {
        if (offloadMode) setConnectProgress(15) // GATT connected + services ready
        // Mimo opens with 0x00/0x2b `04 00` *before* pairing — it's the first thing it writes to a
        // sleeping camera (HCI snoop). Pairing follows a beat later so the two writes don't collide
        // on fff5 (write-without-response drops back-to-back frames).
        val woke = gatt.writeCommand(
            dev.konraditurbe.osmosis.duml.OsmoCommands.sessionPing(
                dev.konraditurbe.osmosis.duml.OsmoCommands.SESSION_WAKE
            )
        )
        logLine("READY — sent session wake 0x00/0x2b[04 00] ok=$woke")
        main.postDelayed({
            val frame = dev.konraditurbe.osmosis.duml.OsmoCommands.setPairingPin(pairPin)
            val ok = gattClient?.writeCommand(frame) ?: false
            logLine("sent SetPairingPIN(pin=\"$pairPin\") ok=$ok")
        }, 120)
        // The keepalive used to re-send SetPairingPIN every 2 s, which doubled as a retry if the
        // first write dropped (fff5 is write-without-response). Now that it pings 0x00/0x2b instead,
        // retry explicitly until the camera answers — but stop once paired, so we don't re-pair.
        for (delay in longArrayOf(2500, 5000)) {
            main.postDelayed({
                if (!credsRequested && lastPairStatus == -99 && gattClient != null) {
                    logLine("pairing: no reply yet — re-sending SetPairingPIN")
                    gattClient?.writeCommand(dev.konraditurbe.osmosis.duml.OsmoCommands.setPairingPin(pairPin))
                }
            }, delay)
        }
    }

    override fun onNotification(sourceChar: java.util.UUID, raw: ByteArray, parsed: DjiMessage?) {
        // The camera sends some messages as REQUESTS (flags=0x40) and drops us (~6s) if we don't
        // answer. Auto-reply with a matching response (flags=0xC0, swapped target, echoed id, a
        // single 0x00 "ok" byte). This is what keeps the paired BLE session alive.
        if (parsed != null && parsed.flags == 0x40) {
            val respTarget = ((parsed.target and 0xFF) shl 8) or ((parsed.target shr 8) and 0xFF)
            val respType = (parsed.type and 0xFFFF00) or 0xC0
            val respPayload = if (parsed.cmdSet == 0x00 && parsed.cmdId == 0x81)
                dev.konraditurbe.osmosis.duml.OsmoCommands.APP_DEVICE_INFO else parsed.payload
            val resp = DjiMessage(respTarget, parsed.id, respType, respPayload).encode()
            val ok = gattClient?.writeCommand(resp) ?: false
            val rk = (parsed.cmdSet shl 8) or parsed.cmdId
            if (reqSeen.add(rk)) {
                logLine("REQ <- 0x%02x/%02x (flags40) -> responded ok=%s".format(parsed.cmdSet, parsed.cmdId, ok))
            }
            // First-time pairing: the camera signals approval as a 0x07/46 REQUEST (flags 0x40), not
            // a response — so it's handled here, before the CmdSet 0x07 block below. ACK it (done
            // above), then start offload exactly like the already-paired 0x45=0x01 path; otherwise a
            // fresh camera pairs but never proceeds to WiFi/grid. (maybeStartOffload is idempotent.)
            if (parsed.cmdSet == 0x07 && parsed.cmdId == 0x46) {
                logLine("PAIRING <- 0x07/46 APPROVED (req)  [${parsed.payload.toHex()}]")
                onPaired()
            }
            return
        }

        // Pairing/WiFi responses (CmdSet 0x07) are load-bearing — always log them in full.
        if (parsed != null && parsed.cmdSet == 0x07) {
            val p = parsed.payload
            when (parsed.cmdId) {
                0x45 -> {
                    val status = if (p.size >= 2) p[1].toInt() and 0xFF else -1
                    if (status != lastPairStatus) { // retries may re-ask; only log changes
                        lastPairStatus = status
                        val meaning = when (status) {
                            0x01 -> "ALREADY PAIRED"
                            0x02 -> "APPROVAL REQUIRED — approve on the Nano screen"
                            else -> "status=0x%02x".format(status)
                        }
                        logLine("PAIRING <- 0x07/45 $meaning  [${p.toHex()}]")
                        if (status == 0x01) onPaired()
                    }
                }
                0x46 -> {
                    logLine("PAIRING <- 0x07/46 APPROVED  [${p.toHex()}]")
                    onPaired()
                }
                0x47 -> logLine("WIFI <- 0x07/47 result  [${p.toHex()}]")
                0x07 -> parseStatusPackString(p)?.takeIf { it.isNotEmpty() }?.let { // GetWifiSsid reply
                    offloadSsid = it
                    logLine("WIFI <- 0x07/07 SSID = \"$it\"")
                }
                0x0E -> { // GetWifiPassword reply — never log the value, only its length
                    val pass = parseStatusPackString(p)
                    if (!pass.isNullOrEmpty()) {
                        offloadPass = pass
                        currentAddress?.let { getSharedPreferences("osmosis", MODE_PRIVATE).edit().putString("pass_$it", pass).apply() }
                        logLine("WIFI <- 0x07/0e password retrieved over BLE (${pass.length} chars)")
                        maybeStartOffload()
                    } else logLine("WIFI <- 0x07/0e no password in reply")
                }
                else -> logLine("CMD07 <- 0x07/%02x  [%s]".format(parsed.cmdId, p.toHex()))
            }
            return
        }

        val key = parsed?.let { (it.flags shl 16) or (it.cmdSet shl 8) or it.cmdId } ?: -1
        val n = (typeCounts[key] ?: 0) + 1
        typeCounts[key] = n
        if (n == 1) {
            if (parsed != null) logLine("NOTIFY ${parsed.format().truncatePayload()}")
            else logLine("NOTIFY[${short(sourceChar)}] raw ${raw.toHex()}")
        } else if (n % 25 == 0) {
            val label = if (parsed != null)
                "set=0x%02x cmd=0x%02x".format(parsed.cmdSet, parsed.cmdId) else "unparsed"
            logLine("NOTIFY $label x$n")
        }
    }

    override fun onDisconnected() {
        connecting = false
        stopKeepalive()
        lastPairStatus = -99
        main.post {
            if (isFinishing || isDestroyed) return@post
            // A BLE drop before the grid is the normal control→WiFi handoff (status=8) — ignore it.
            // A drop while the gallery is up (status=19, camera terminated) means the camera is gone:
            // the gallery is now stale, so tear the session down and return to the camera selector.
            if (gridGroup.visibility == View.VISIBLE) {
                logLine("Camera link lost — returning to camera list.")
                datalink?.close()
                apJoiner?.release()
                grid.adapter = null
                adapter = null
                switchToSelector()
            } else {
                logLine("Disconnected.")
                // A drop after pairing is the normal WiFi handoff (keep the progress bar going);
                // a drop before pairing means the connection failed early — clear the bar.
                if (!offloadTriggered) setConnectProgress(0)
            }
        }
    }

    // ---- log / util ---------------------------------------------------------

    override fun onLog(s: String) = logLine(s)

    private fun logLine(s: String) {
        android.util.Log.i("Osmosis", s) // always to logcat (adb logcat)
        FileLog.write(s)                 // ...and to the file when "Save logs" is on
    }

    /** Open a session log file. Shared with the background services via [FileLog], so GPS-sync lines
     *  keep landing in the file after this Activity is gone. */
    private fun startFileLogging() = FileLog.start(this)

    private fun stopFileLogging() = FileLog.stop()

    private fun short(u: java.util.UUID) = u.toString().substring(4, 8)

    companion object {
        private const val REQ_PERMS = 1001
        private const val REQ_GPS_PERMS = 1002
    }
}

private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

/** Trim the "payload=<hex>" tail of DjiMessage.format() to keep telemetry lines short. */
private fun String.truncatePayload(): String {
    val idx = indexOf("payload=")
    if (idx < 0) return this
    val head = substring(0, idx)
    val hex = substring(idx + 8)
    val shown = if (hex.length > 64) hex.substring(0, 64) + "…(${hex.length / 2}B)" else hex
    return head + "payload=" + shown
}
