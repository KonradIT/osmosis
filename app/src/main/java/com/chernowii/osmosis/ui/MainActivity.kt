package com.chernowii.osmosis.ui

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
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.net.LinkProperties
import android.net.Network
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chernowii.osmosis.R
import com.chernowii.osmosis.ble.Brand
import com.chernowii.osmosis.ble.CameraModel
import com.chernowii.osmosis.ble.GattClient
import com.chernowii.osmosis.ble.OsmoScanner
import com.chernowii.osmosis.core.CameraFile
import com.chernowii.osmosis.core.CameraStatus
import com.chernowii.osmosis.core.SavedCameras
import com.chernowii.osmosis.core.TrimRange
import com.chernowii.osmosis.duml.DjiMessage
import com.chernowii.osmosis.net.ApJoiner
import com.chernowii.osmosis.net.HttpClient
import com.chernowii.osmosis.net.DatalinkClient
import com.chernowii.osmosis.net.ImageLoader
import com.chernowii.osmosis.net.MediaDownloader
import com.chernowii.osmosis.net.MetaLoader
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

    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
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

    // Credential-probe mode: after pairing, sweep 0x07 WiFi commands and search every notification
    // for the known SSID/password, to discover whether the Nano leaks its AP creds over BLE.
    private var credProbeMode = false
    private var knownSsid = ""
    private var knownPass = ""
    private var credFound = false
    private var credMatchCount = 0

    // Telemetry flood control: log each distinct DUML (flags/set/cmd) once, then every 25th.
    private val typeCounts = HashMap<Int, Int>()
    private val reqSeen = HashSet<Int>() // inbound request types already logged

    // BLE keepalive: the Nano drops an idle paired link after ~5-6s. Re-send a benign frame
    // (SetPairingPIN, which it just answers ALREADY PAIRED) every 2s to keep the session alive.
    private var keepaliveOn = false
    private var lastPairStatus = -99
    private val keepalive = object : Runnable {
        override fun run() {
            gattClient?.writeCommand(com.chernowii.osmosis.duml.OsmoCommands.setPairingPin(pairPin))
            main.postDelayed(this, 2000)
        }
    }

    private fun startKeepalive() {
        if (keepaliveOn) return
        keepaliveOn = true
        logLine("keepalive: started (2s)")
        main.postDelayed(keepalive, 2000)
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
        log = findViewById(R.id.txtLog)
        scroll = findViewById(R.id.scroll)
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
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            log.text = ""
            typeCounts.clear()
        }

        // Persist WiFi creds seeded via `--es ssid/pass` so the Offload button can reuse them
        // without the password living in source.
        val prefs = getSharedPreferences("osmosis", MODE_PRIVATE)
        intent?.getStringExtra("ssid")?.let { prefs.edit().putString("ssid", it).apply() }
        intent?.getStringExtra("pass")?.let { prefs.edit().putString("pass", it).apply() }
        knownSsid = prefs.getString("ssid", "") ?: ""
        knownPass = prefs.getString("pass", "") ?: ""

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
        // Credential probe: `--ez credprobe true` — pair, then sweep 0x07 cmds and watch for creds.
        if (intent?.getBooleanExtra("credprobe", false) == true) {
            credProbeMode = true
            logLine("CRED-PROBE mode: known ssid=\"$knownSsid\" passLen=${knownPass.length}")
            main.postDelayed({ startCameraScan(select = false) }, 500)
        }

        // Camera selector is the launch screen: show saved cameras, then scan to mark which are in
        // range and surface new ones (unless a test hook is already driving a scan/flow).
        rebuildCameraList()
        val hookDriving = intent?.getBooleanExtra("autoscan", false) == true ||
            intent?.getBooleanExtra("offload", false) == true ||
            intent?.getBooleanExtra("wifi", false) == true ||
            intent?.getBooleanExtra("credprobe", false) == true
        if (!hookDriving) startCameraScan(select = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopKeepalive()
        datalink?.close()
        scanner?.stop()
        gattClient?.disconnect()
        gattClient?.close()
        apJoiner?.release()
        imageLoader?.shutdown()
        metaLoader?.shutdown()
    }

    private fun selfTestDuml() {
        try {
            val payload = com.chernowii.osmosis.duml.DjiPairMessagePayload("love").encode()
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
            if (connecting) return@postDelayed // credprobe/auto-pick already connected
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
                c?.model ?: CameraModel.resolve(e.modelId.takeIf { it >= 0 }, e.name),
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
        val r = camRows.getOrNull(pos) ?: return
        val dev = r.device
        if (dev != null) onCameraChosen(dev)
        else Toast.makeText(this, "${r.name ?: r.mac} isn't in range — turn it on, then Rescan", Toast.LENGTH_SHORT).show()
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
        val cam = discovered[device.address]
        currentBrand = Brand.of(device.address, cam?.name ?: safeName(device))
        currentModel = cam?.model ?: CameraModel.resolve(null, safeName(device))
        currentModelId = cam?.modelId
        currentAddress = device.address
        offloadSsid = cam?.name ?: safeName(device) ?: "camera"
        if (savedPassFor(device.address).isEmpty()) {
            promptPasswordFor(device.address) { connectAndOffload(device) }
        } else {
            connectAndOffload(device)
        }
    }

    private fun connectAndOffload(device: BluetoothDevice) {
        datalink?.close()   // end any prior camera's datalink session
        apJoiner?.release() // drop any prior camera's WiFi request + process binding
        offloadPass = savedPassFor(device.address)
        offloadMode = true
        offloadTriggered = false
        connecting = true
        setConnectProgress(3) // tap → connecting
        logLine("OFFLOAD [$currentBrand] $offloadSsid (${device.address})")
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
        if (requestCode != REQ_PERMS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCameraScan(select = true)
        } else {
            logLine("Permissions denied — cannot scan.")
        }
    }

    // ---- WiFi manifest flow -------------------------------------------------

    private fun startCredProbe() {
        logLine("CRED-PROBE: sweeping 0x07 cmds (empty + arg payloads)...")
        var delay = 150L
        fun send(cmd: Int, payload: ByteArray, id: Int) {
            main.postDelayed({
                gattClient?.writeCommand(com.chernowii.osmosis.duml.OsmoCommands.wifiQuery(cmd, payload, id))
            }, delay)
            delay += 100
        }
        // Phase 1: empty payload across the range.
        for (cmd in 0x40..0x5F) send(cmd, ByteArray(0), 0x8000 or cmd)
        // Phase 2: the string-returning / getter-looking cmds with a 1-byte index argument.
        for (cmd in intArrayOf(0x40, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x52)) {
            for (arg in 0..4) send(cmd, byteArrayOf(arg.toByte()), 0x9000 or (cmd shl 4) or arg)
        }
        main.postDelayed({ logLine("CRED-PROBE: sweep done (passwordFound=$credFound)") }, delay + 600)
    }

    private fun maybeStartOffload() {
        if (!offloadMode || offloadTriggered) return
        offloadTriggered = true
        setConnectProgress(28) // paired → waking the AP
        logLine("OFFLOAD: paired -> waking AP via ConnectToWiFi(0x07/47)")
        gattClient?.writeCommand(
            com.chernowii.osmosis.duml.OsmoCommands.connectWifi(offloadSsid, offloadPass)
        )
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
                    // Datalink port + poke come from the model (e.g. Action 5 Pro = 10004/no-poke,
                    // the rest = 9004/poke); the Xtra resolves to Action 5 Pro by its model byte.
                    val dlPort = currentModel.datalinkPort
                    val dlPoke = currentModel.tcpPoke
                    logLine("=== media list [${currentModel.name}] via udp/$dlPort ===")
                    datalink?.close()
                    val dl = DatalinkClient(::logLine, dlPort, dlPoke)
                    datalink = dl
                    dl.onStatus = { s -> main.post { onCameraStatus(s) } }
                    dl.onFetchProgress = { fp -> setConnectProgress(60 + fp * 38 / 100) } // 60→98 during the manifest
                    val files = runCatching { dl.fetchFileList("192.168.2.1") }
                        .getOrElse { logLine("datalink error: ${it.message}"); emptyList() }
                    if (files.isNotEmpty()) dl.startKeepAlive() // hold the AP up while browsing
                    // Files live on internal or SD; detect from the first and apply to all.
                    val storage = if (files.isNotEmpty()) detectStorage(files.first()) else 0
                    val fixed = files.map { it.copy(storage = storage) }
                        .sortedWith(compareByDescending<CameraFile> { it.timestamp }.thenByDescending { it.seq })
                    logLine("MANIFEST: ${fixed.size} files on storage=$storage")
                    main.post { showGrid(fixed) }
                }.start()
            }
        })
        apJoiner = joiner
        joiner.join(ssid, pass, currentModel.wpa3)
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
        setConnectProgress(0)
        val addr = currentAddress ?: return
        if (savedPassFor(addr).isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Couldn't join $offloadSsid")
            .setMessage(
                "The camera Wi-Fi join failed. This usually means the saved password is out of date " +
                    "— e.g. after a camera factory reset. Re-enter it and try again?"
            )
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
        statusPill.render(pillName(), "Connected · WiFi", currentStatus)
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
        val ad = MediaGridAdapter(files, loader, ml) { openPreview(it) }
        adapter = ad
        grid.adapter = ad
        logLine("Grid ready: ${files.size} files. Tap a cell to preview + queue, then Download.")
    }

    private fun pillName() = "${currentModel.name} ${offloadSsid.substringAfterLast('-', "")}".trim()

    /** Live camera status → refresh the pill (only while the gallery is showing). */
    private fun onCameraStatus(s: CameraStatus) {
        currentStatus = s
        if (gridGroup.visibility == View.VISIBLE) statusPill.render(pillName(), "Connected · WiFi", s)
    }

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

    /** Returns the storage id (1=SD, 0=internal) that actually serves this file's path. */
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
        val model = CameraModel.resolve(modelId, name)
        if (discovered.put(addr, Cam(device, name, Brand.of(addr, name), rssi, modelId, model)) == null) {
            logLine("found ${model.name} [${Brand.of(addr, name)}] (${name ?: addr}) rssi=$rssi" +
                if (!model.verified) "  ~experimental" else "")
            main.post { rebuildCameraList() }
        }
        // Credential-probe is headless — connect to the first camera immediately.
        if (credProbeMode && !connecting) {
            connecting = true
            scanner?.stop()
            offloadSsid = name ?: addr
            logLine("CRED-PROBE: connecting to ${name ?: addr}")
            val gc = GattClient(this, this); gattClient = gc; gc.connect(device)
        }
    }

    // ---- GattClient.Listener -----------------------------------------------

    override fun onReady(gatt: GattClient) {
        if (offloadMode) setConnectProgress(15) // GATT connected + services ready
        val frame = com.chernowii.osmosis.duml.OsmoCommands.setPairingPin(pairPin)
        val ok = gatt.writeCommand(frame)
        logLine("READY — sent SetPairingPIN(pin=\"$pairPin\") ok=$ok")
    }

    override fun onNotification(sourceChar: java.util.UUID, raw: ByteArray, parsed: DjiMessage?) {
        // CRED-PROBE: does any BLE message carry the AP SSID/password we already know?
        if (credProbeMode && !credFound && (knownPass.isNotEmpty() || knownSsid.length >= 6)) {
            val ascii = String(raw, Charsets.ISO_8859_1)
            val hasPass = knownPass.isNotEmpty() && ascii.contains(knownPass)
            val hasSsid = knownSsid.length >= 6 && ascii.contains(knownSsid)
            if (hasPass || hasSsid) {
                val where = parsed?.let { "0x%02x/%02x".format(it.cmdSet, it.cmdId) } ?: "raw"
                logLine("*** CRED MATCH in $where pass=$hasPass ssid=$hasSsid: ${raw.toHex()}")
                credMatchCount++
                if (hasPass || credMatchCount >= 12) credFound = true
            }
        }

        // The camera sends some messages as REQUESTS (flags=0x40) and drops us (~6s) if we don't
        // answer. Auto-reply with a matching response (flags=0xC0, swapped target, echoed id, a
        // single 0x00 "ok" byte). This is what keeps the paired BLE session alive.
        if (parsed != null && parsed.flags == 0x40) {
            val respTarget = ((parsed.target and 0xFF) shl 8) or ((parsed.target shr 8) and 0xFF)
            val respType = (parsed.type and 0xFFFF00) or 0xC0
            val respPayload = if (parsed.cmdSet == 0x00 && parsed.cmdId == 0x81)
                com.chernowii.osmosis.duml.OsmoCommands.APP_DEVICE_INFO else parsed.payload
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
                if (credProbeMode) startCredProbe() else maybeStartOffload()
            }
            return
        }

        // Pairing/WiFi responses (CmdSet 0x07) are load-bearing — always log them in full.
        if (parsed != null && parsed.cmdSet == 0x07) {
            val p = parsed.payload
            when (parsed.cmdId) {
                0x45 -> {
                    val status = if (p.size >= 2) p[1].toInt() and 0xFF else -1
                    if (status != lastPairStatus) { // keepalive re-asks every 2s; only log changes
                        lastPairStatus = status
                        val meaning = when (status) {
                            0x01 -> "ALREADY PAIRED"
                            0x02 -> "APPROVAL REQUIRED — approve on the Nano screen"
                            else -> "status=0x%02x".format(status)
                        }
                        logLine("PAIRING <- 0x07/45 $meaning  [${p.toHex()}]")
                        if (status == 0x01) { if (credProbeMode) startCredProbe() else maybeStartOffload() }
                    }
                }
                0x46 -> {
                    logLine("PAIRING <- 0x07/46 APPROVED  [${p.toHex()}]")
                    if (credProbeMode) startCredProbe() else maybeStartOffload()
                }
                0x47 -> logLine("WIFI <- 0x07/47 result  [${p.toHex()}]")
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
        android.util.Log.i("Osmosis", s)
        main.post {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            log.append("[$ts] $s\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun short(u: java.util.UUID) = u.toString().substring(4, 8)

    companion object {
        private const val REQ_PERMS = 1001
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
