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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import dev.konraditurbe.osmosis.camera.PathAddressing
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.CameraStatus
import dev.konraditurbe.osmosis.core.FileLog
import dev.konraditurbe.osmosis.core.SavedCameras
import dev.konraditurbe.osmosis.core.TrimRange
import dev.konraditurbe.osmosis.duml.DjiMessage
import dev.konraditurbe.osmosis.net.ApJoiner
import dev.konraditurbe.osmosis.net.HttpClient
import dev.konraditurbe.osmosis.camera.CameraSession
import dev.konraditurbe.osmosis.core.MediaSession
import dev.konraditurbe.osmosis.drone.DronePairing
import dev.konraditurbe.osmosis.drone.DroneSession
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

    private lateinit var grid: RecyclerView
    private var gridCols = 3   // current grid column count (3 portrait / 6 landscape); updated on rotation
    // Gallery toolbar chips (Photos/Videos are a mutually-exclusive type filter; Faved + Select combine).
    private lateinit var chipPhotos: MaterialButton
    private lateinit var chipVideos: MaterialButton
    private lateinit var chipFaved: MaterialButton
    private lateinit var chipSelect: MaterialButton
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

    // ---- download / AP-loss state (all main-thread confined) ----------------
    // One download run at a time. Without this every tap on Download spawned another thread over the
    // same jobs: otherwise we got N MediaDownloaders opening the
    // SAME MediaStore URI "rw", each seeking to the shared statSize — three writers racing on one
    // file and three transfers competing for the camera's AP, for one file's worth of progress.
    private var downloadRunning = false
    private var wifiUp = false
    /** True once the first join has kicked off [startDatalink]; a later join is a rejoin, not a start. */
    private var datalinkStarted = false
    private var wifiRejoins = 0
    private var resumeDownloadOnRejoin = false

    /**
     * Generation stamp for the datalink worker, bumped on every start and every teardown.
     *
     * [startDatalink] does its work on a thread, and the slow part — `fetchFileList`, 10-20 s — runs
     * before the session is ever assigned to [datalink]. So a reconnect during that window could not
     * see the session in flight, could not close it, and simply started a second one: two
     * `CameraSession`s on udp/9004 against a camera that has exactly one session. Caught on a Pocket 3
     * where connect #4 began 0.4 s *before* connect #3 reported its result, and the two failed
     * attempts got zero `0x00/0x27` frames while the camera pushed 1000+ of everything else — the
     * query was reaching a camera whose session we had already replaced underneath it.
     *
     * A worker compares this on completion and drops its result if it has been superseded. Atomic
     * because it is touched from the main thread and the ConnectivityManager callback.
     */
    private val datalinkGen = java.util.concurrent.atomic.AtomicInteger(0)

    /** The session a worker is building. Published *before* the fetch so teardown can close it. */
    @Volatile private var pendingSession: dev.konraditurbe.osmosis.core.MediaSession? = null

    private val http = HttpClient("192.168.2.1") { s -> logLine(s) }
    private var imageLoader: ImageLoader? = null
    private var metaLoader: MetaLoader? = null
    private var adapter: MediaGridAdapter? = null

    // Preview screen result: add/remove the previewed item (with optional trim) from the queue.
    private val previewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        val ad = adapter ?: return@registerForActivityResult
        // Cells are identified by their (lead) path — stable across filtering/pagination.
        val leadPath = data.getStringExtra(MediaPreviewActivity.EXTRA_PATH) ?: return@registerForActivityResult
        val f = ad.fileForPath(leadPath) ?: return@registerForActivityResult
        val queued = data.getBooleanExtra(MediaPreviewActivity.EXTRA_QUEUED, false)
        val s = data.getLongExtra(MediaPreviewActivity.EXTRA_TRIM_START, -1L)
        val e = data.getLongExtra(MediaPreviewActivity.EXTRA_TRIM_END, -1L)
        // A burst preview queues the exact frame the user was viewing: the viewer hands back that frame's
        // own path/thumb (the grid never probed the group), so we rebuild it off the lead. Null → the lead.
        val selPath = data.getStringExtra(MediaPreviewActivity.EXTRA_GROUP_SEL_PATH)
        val member = selPath?.let {
            f.copy(path = it, thumbPath = data.getStringExtra(MediaPreviewActivity.EXTRA_GROUP_SEL_THUMB) ?: f.thumbPath)
        }
        ad.setQueuedByPath(leadPath, queued, if (s >= 0 && e > s) TrimRange(s, e) else null, member)
        // Favorite lives on the grid long-press now (see onGridLongPress), not the preview — the preview
        // no longer touches the datalink, so it can't perturb the browse keep-alive.
    }

    // The scan the user asked for, held while we send them to enable Bluetooth; resumed when they return.
    private var pendingScan: Pair<Boolean, String?>? = null
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (btAdapter?.isEnabled == true) pendingScan?.let { (sel, pk) -> pendingScan = null; startCameraScan(sel, pk) }
        else logLine("Bluetooth still off — tap Rescan once it's on.")
    }
    // Returning from the Wi-Fi settings panel: re-check and continue the camera Wi-Fi join.
    private val wifiPanelLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { promptWifiConsent(offloadSsid, offloadPass) }

    // The datalink session keeps the camera AP alive (the Action 5 sleeps its AP the moment the
    // datalink goes idle). Held open during browse/download; closed on a new offload / exit.
    private var datalink: MediaSession? = null

    // EVERY camera write goes through this one worker. They can each fall back to tearing the keep-alive
    // down and re-handshaking, so two running at once fight over the socket. Observed on an Xtra Edge Pro:
    // four deletes tapped in ~15 s ran on four threads, the first one's verify re-list re-opened the
    // session underneath the other three, and the result was `handshake FAILED`, a 51-byte garbage
    // manifest, a grid emptied to 0 files and a dead session — from four deletes the camera had accepted.
    // Serialized, they queue behind each other instead.
    private val cmdExec = java.util.concurrent.Executors.newSingleThreadExecutor()

    // Pairing PIN string sent in SetPairingPIN — an app-chosen token (any value pairs; the camera
    // shows its approval popup once, then stores it for silent re-pair). Overridable via
    // `am start ... --es pin <value>`.
    private var pairPin = "osmo"

    /** `--ez nojoin true` — skip the WiFi join/bind and talk over whatever network is already default. */
    private var noJoin = false

    /** Serial + tag read off the drone's identity beacon over BLE, handed to the datalink session. */
    private var bleDroneSerial: Pair<ByteArray, Int>? = null
    private var pinOverride: String? = null // `--es pin <v>` test hook; wins over the per-model token

    // Shown while the camera/drone is waiting for the user to confirm pairing (0x07/45 → 0x02).
    // A camera confirms on its own screen; a drone (e.g. Mavic) needs a ~2 s press of its power button.
    private var pairingAlert: AlertDialog? = null

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

    /** DJI's `LctActivateState`, off the camera's own 0x00/0x32 push. -1 until it says. */
    private var activateState = -1

    /** DJI's `LctActivateState` enum, from Mimo's `LctActivateState.java`. */
    private fun activateStateName(s: Int) = when (s) {
        0 -> "not activated"; 1 -> "activated"; 2 -> "uninitialized"; 3 -> "factory activated"
        0xFFFE -> "not supported"; else -> "unknown ($s)"
    }

    /** True only when the camera has said, in its own words, that it isn't activated yet. */
    private fun saysNotActivated() = activateState == 0 || activateState == 2

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
        findViewById<View>(R.id.fabDelete).setOnClickListener { onBulkDeleteClicked() }
        wireGalleryChips()
        // "Save logs" toggle → persist all log lines to a rotating .log file in external files dir.
        val prefs = getSharedPreferences("osmosis", MODE_PRIVATE)
        val saveLogs = findViewById<MaterialSwitch>(R.id.switchSaveLogs)
        saveLogs.isChecked = prefs.getBoolean("save_logs", false)
        if (saveLogs.isChecked) startFileLogging() // set state before the listener so this isn't double-fired
        saveLogs.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("save_logs", checked).apply()
            if (checked) {
                startFileLogging()
            } else {
                val saved = FileLog.currentFile()      // grab it before stop() nulls nothing, just to be safe
                stopFileLogging()
                if (saved != null && saved.exists() && saved.length() > 0) offerToShareLogs(saved)
            }
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
        intent?.getStringExtra("pin")?.let { pinOverride = it; pairPin = it; logLine("pairPin set to \"$it\"") }
        // `--ez nojoin true`: phone is already on the AP via Android WiFi settings, so skip the join +
        // bindProcessToNetwork. Needed to capture our own session — see maybeStartOffload.
        if (intent?.getBooleanExtra("nojoin", false) == true) {
            noJoin = true; logLine("nojoin: will use the current default network, no WiFi join")
        }
        // `--ez nowriterefresh true`: don't re-register before a write, so writes land on a session old
        // enough to have drifted. Needed to see whether the control window still loses them.
        if (intent?.getBooleanExtra("nowriterefresh", false) == true) {
            dev.konraditurbe.osmosis.camera.CameraSession.debugNoWriteRefresh = true
            logLine("nowriterefresh: writes will go out on old sessions, no re-registration")
        }
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
        // Keep the launcher App Shortcuts in sync with the current paired-camera set on every launch.
        CameraShortcuts.refresh(this)
        // App Shortcut launch: the user long-pressed the app icon and picked a paired camera. Assume
        // it's powered on and advertising, so scan and auto-connect to that MAC — same path as a tap.
        val shortcutMac = intent?.getStringExtra(CameraShortcuts.EXTRA_MAC)
        if (shortcutMac != null) { autoPickMac = shortcutMac; logLine("shortcut: connect to $shortcutMac") }
        val hookDriving = intent?.getBooleanExtra("autoscan", false) == true ||
            intent?.getBooleanExtra("offload", false) == true ||
            intent?.getBooleanExtra("wifi", false) == true
        when {
            shortcutMac != null -> main.postDelayed({ startCameraScan(select = true) }, 300)
            !hookDriving -> startCameraScan(select = true)
        }
    }

    /**
     * A launcher App Shortcut was tapped while we were already running (singleTop). Tear down any live
     * session, return to the selector, and connect to the chosen camera by MAC — see [CameraShortcuts].
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val mac = intent.getStringExtra(CameraShortcuts.EXTRA_MAC) ?: return
        if (dev.konraditurbe.osmosis.rsdk.GpsSyncState.locked) {
            toast(getString(R.string.gps_active_select_blocked)); return
        }
        logLine("shortcut (running): connect to $mac")
        teardownOffload()
        switchToSelector()
        autoPickMac = mac
        startCameraScan(select = true)
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
     * We opt out of Activity recreation on rotation (manifest `configChanges`) so a flip keeps the live
     * camera session — BLE/datalink/WiFi and the loaded grid — instead of tearing it all down and bouncing
     * to the selector. The only thing that actually needs to change is the grid's column count, so re-span
     * the layout manager in place (scroll position, queue and connection all preserved).
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationChrome()
        val cols = gridColumns()
        if (cols == gridCols) return
        gridCols = cols
        (grid.layoutManager as? GridLayoutManager)?.let { lm ->
            lm.spanCount = cols
            lm.spanSizeLookup.invalidateSpanIndexCache()
            grid.invalidateItemDecorations()
            grid.requestLayout()
        }
    }

    /** Per-orientation chrome that the old land layout used to do — now dynamic since we don't recreate the
     *  Activity on rotation. Landscape: hide the status pill to give the grid the full height. */
    private fun applyOrientationChrome() {
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        statusPill.visibility = if (landscape) View.GONE else View.VISIBLE
    }

    /**
     * Mirror the GPS-sync service's state into the UI: a coloured banner and a disabled selector while
     * a link is bound (STARTING/ACTIVE), cleared when it stops. The satellite button stays live — it's
     * the only way out of the lockout.
     */
    private fun renderGpsLock(phase: GpsSyncState.Phase, name: String?) {
        val locked = phase != GpsSyncState.Phase.STOPPED
        val who = name ?: getString(R.string.the_camera)
        when (phase) {
            GpsSyncState.Phase.ACTIVE -> {
                gpsBanner.setBackgroundColor(ContextCompat.getColor(this, R.color.osmo_danger))
                gpsBanner.text = getString(R.string.gps_sync_active_banner, who)
                gpsBanner.visibility = View.VISIBLE
            }
            GpsSyncState.Phase.STARTING -> {
                gpsBanner.setBackgroundColor(ContextCompat.getColor(this, R.color.osmo_amber))
                gpsBanner.text = getString(R.string.gps_sync_connecting_banner, who)
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
    // MAC of a camera launched from a launcher App Shortcut: connect the moment it advertises, no tap
    // (see CameraShortcuts / onHit). Cleared once consumed.
    private var autoPickMac: String? = null

    /** Scan ~4s for DJI/Xtra cameras (bonds aren't reliable for these), then feed the selector list. */
    private fun startCameraScan(select: Boolean, pick: String? = null) {
        val adapter = btAdapter ?: run { logLine("No Bluetooth adapter."); toast(getString(R.string.no_bluetooth)); return }
        if (!adapter.isEnabled) { promptEnableBluetooth(select, pick); return }
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
        selectorHint.text = getString(R.string.scanning)
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

    /** Bluetooth is off — scanning would silently find nothing, so ask the user to turn it on and resume
     *  the scan when they return (via [enableBtLauncher]). Falls back to the BT settings screen if the
     *  in-app enable request can't run (e.g. BLUETOOTH_CONNECT not yet granted on API 31+). */
    private fun promptEnableBluetooth(select: Boolean, pick: String?) {
        logLine("Bluetooth is OFF — prompting to enable.")
        AlertDialog.Builder(this)
            .setTitle(R.string.bluetooth_off_title)
            .setMessage(R.string.bluetooth_off_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.turn_on) { _, _ ->
                pendingScan = select to pick
                runCatching { enableBtLauncher.launch(android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    .onFailure {
                        logLine("BT enable request failed (${it.javaClass.simpleName}) — opening settings.")
                        runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
                    }
            }
            .show()
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
                c?.model ?: CameraModel.resolve(e.modelId.takeIf { it >= 0 }, e.name, Brand.of(e.mac, e.name, djiCid = e.modelId >= 0)),
                inRange = c != null, saved = true, device = c?.device)
        }
        val newRows = scanned.filter { it.device.address !in savedMacs }.map { c ->
            CamRow(c.device.address, c.name, c.model, inRange = true, saved = false, device = c.device)
        }
        camRows = savedRows + newRows
        cameraList.adapter = CameraListAdapter(camRows)
        if (scanner?.isScanning() != true) {
            selectorHint.text = if (camRows.isEmpty()) getString(R.string.no_cameras_hint)
            else getString(R.string.cameras_in_range, savedRows.count { it.inRange }, savedRows.size, newRows.size)
        }
    }

    private fun onCamRowClick(pos: Int) {
        // Locked out while a GPS link is bound — the satellite button is the only way forward.
        if (dev.konraditurbe.osmosis.rsdk.GpsSyncState.locked) {
            toast(getString(R.string.gps_active_select_blocked))
            return
        }
        val r = camRows.getOrNull(pos) ?: return
        // 🛰️ GPS-sync mode: connect over R-SDK (BLE only, no WiFi) via the foreground service.
        if (btnGps.isChecked) {
            if (r.device != null || r.saved) startGpsMode(r.mac, r.name ?: r.mac)
            else Toast.makeText(this, getString(R.string.camera_not_in_range, r.name ?: r.mac), Toast.LENGTH_SHORT).show()
            return
        }
        val dev = r.device
        if (dev != null) onCameraChosen(dev)
        else Toast.makeText(this, getString(R.string.camera_not_in_range, r.name ?: r.mac), Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, getString(R.string.gps_sync_starting, name), Toast.LENGTH_LONG).show()
    }

    /** Drop any live WiFi-offload session (BLE GATT + datalink + WiFi request) so the R-SDK GPS flow
     *  can take the camera's single BLE link without contention. Safe to call when nothing is active. */
    private fun teardownOffload() {
        stopKeepalive()
        dev.konraditurbe.osmosis.net.Highlights.provider = null
        dev.konraditurbe.osmosis.net.PreviewNav.clear()
        // Supersede any datalink worker still running, and close the session it is mid-fetch on.
        // Bumping the generation alone is not enough — that only stops it *publishing* its result,
        // while its socket would keep holding udp/9004 against a camera the next connect is about to
        // handshake with. Closing it here is what actually frees the port.
        datalinkGen.incrementAndGet()
        runCatching { pendingSession?.close() }; pendingSession = null
        datalink?.close(); datalink = null
        apJoiner?.release(); apJoiner = null
        gattClient?.disconnect(); gattClient?.close(); gattClient = null
        offloadMode = false; offloadTriggered = false; connecting = false
        // A stale datalinkStarted would make the next session's first join look like a rejoin and skip
        // startDatalink entirely, leaving the camera connected with no grid.
        wifiUp = false; datalinkStarted = false; wifiRejoins = 0; resumeDownloadOnRejoin = false
        // close() above cancels the gatt callback, so onDisconnected won't fire to reset these — do it
        // here, or the next camera's pairing/REQ replies get mis-deduped against the last camera's state.
        lastPairStatus = -99; credsRequested = false; activateState = -1; reqSeen.clear()
        setConnectProgress(0)
    }

    private fun onCamRowLongClick(pos: Int): Boolean {
        val r = camRows.getOrNull(pos) ?: return false
        if (!r.saved) return false
        AlertDialog.Builder(this)
            .setTitle("${r.model.name}  (${r.name ?: r.mac})")
            .setItems(arrayOf(getString(R.string.reenter_wifi_password), getString(R.string.forget_camera))) { _, i ->
                when (i) {
                    0 -> promptPasswordFor(r.mac) { logLine("Password updated.") }
                    1 -> {
                        savedCameras.remove(r.mac)
                        getSharedPreferences("osmosis", MODE_PRIVATE).edit().remove("pass_${r.mac}").apply()
                        logLine("Forgot ${r.name ?: r.mac}")
                        rebuildCameraList()
                        CameraShortcuts.refresh(this)   // drop it from the launcher shortcuts too
                    }
                }
            }.show()
        return true
    }

    private fun switchToGrid() { selectorGroup.visibility = View.GONE; gridGroup.visibility = View.VISIBLE }

    private fun switchToSelector() {
        // Returning to the overview must fully release the current camera — GATT, datalink, WiFi binding,
        // AND the 1 Hz BLE keepalive. Leaving the old GATT connected (+ keepalive pinging it) kept the
        // camera from re-advertising ("not available" on rescan) and wedged the next camera's connect on
        // its first GATT step. teardownOffload is null-safe/idempotent, so redundant callers are fine.
        teardownOffload()
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
            toast(getString(R.string.gps_stop_before_browse))
            return
        }
        val cam = discovered[device.address]
        currentBrand = Brand.of(device.address, cam?.name ?: safeName(device), djiCid = cam?.modelId != null)
        currentModel = cam?.model ?: CameraModel.resolve(null, safeName(device), currentBrand)
        currentModelId = cam?.modelId
        currentAddress = device.address
        offloadSsid = cam?.name ?: safeName(device) ?: "camera"
        // Pairing token is per-device: a drone only releases its WiFi creds to "DJI FLY", cameras to
        // "osmo". An explicit `--es pin` (pinOverride) still wins, for testing.
        pairPin = pinOverride ?: currentModel.pairingToken
        // No up-front password prompt: the camera hands us the passphrase over BLE after pairing
        // (see onPaired). savedPassFor seeds the fallback for models that don't expose it.
        connectAndOffload(device)
    }

    private fun connectAndOffload(device: BluetoothDevice) {
        teardownOffload()   // fully release any prior camera (GATT, datalink, WiFi, keepalive) first —
                            // a leaked GATT/keepalive from the last camera otherwise stalls this connect
        offloadPass = savedPassFor(device.address)
        offloadMode = true
        offloadTriggered = false
        credsRequested = false
        activateState = -1
        wpa3FallbackDone = false
        connecting = true
        setConnectProgress(3) // tap → connecting
        logLine("OFFLOAD [$currentBrand] $offloadSsid (${device.address})")
        // No wake broadcast here: an HCI snoop of Mimo waking a sleeping Nano showed it never
        // advertises. The sleeping camera keeps advertising ADV_IND itself, and Mimo simply connects
        // and drives it with DUML (0x00/0x2b -> pair -> 0x53/0x10). That's the sequence we follow in
        // onReady/onPaired. (DJI also documents a 'WKP' wake *broadcast*; an HCI snoop proved Mimo
        // never advertises, so it isn't used here — see MEDIA_PROTOCOL.md § "Waking a sleeping camera".)
        val gc = GattClient(this, this)
        gattClient = gc
        gc.connect(device)
    }

    /** Password is stored per-camera (by MAC). No global fallback — that would leak one camera's
     *  password to another (e.g. the Nano's onto the Xtra). */
    private fun savedPassFor(addr: String): String =
        getSharedPreferences("osmosis", MODE_PRIVATE).getString("pass_$addr", "") ?: ""

    /** Per-camera password capture (keyed by MAC). SSID comes from the BLE device name. */
    /**
     * Shown instead of the password prompt when the camera reports it has never been activated
     * ([saysNotActivated]) — it keeps its WiFi off, so there is no network any password would reach.
     *
     * There is no Activate button because there is nothing we could put behind it: activation is a
     * challenge-response the camera answers only to DJI's servers (0x00/0x32, see the protocol map),
     * so pointing at Mimo is the honest answer rather than a placeholder.
     */
    private fun showNotActivated() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.camera_not_activated_title, pillName()))
            .setMessage(R.string.camera_not_activated_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun promptPasswordFor(addr: String, onSaved: () -> Unit) {
        val prefs = getSharedPreferences("osmosis", MODE_PRIVATE)
        val input = EditText(this).apply {
            setHint(R.string.wifi_password_hint); setText(savedPassFor(addr)); setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.wifi_password_for, offloadSsid))
            .setMessage(R.string.wifi_password_message)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val p = input.text.toString().trim()
                if (p.isEmpty()) { logLine("Password empty — not saved."); return@setPositiveButton }
                prefs.edit().putString("pass_$addr", p).apply()
                onSaved()
            }
            .setNegativeButton(R.string.cancel, null)
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
    /**
     * The identity half of SetPairingPIN: DJI Fly's for a drone, the generic one for a camera. Both
     * call sites (first write + retry) must agree — a device keys its remembered approval on this
     * string, so two writes with different identities read as two different apps asking to pair.
     */
    private fun pairIdentity(): String =
        if (currentModel.isDrone) DronePairing.identifier(getSharedPreferences("osmosis", MODE_PRIVATE))
        else dev.konraditurbe.osmosis.duml.DjiPairMessagePayload.DEFAULT_IDENTIFIER

    /**
     * Prompt the user to confirm the pairing on the device.
     *
     * A camera says so on its own screen, so words are enough. A drone has no screen — the user has to
     * find one unlabelled button on the back of an aircraft they may have just unboxed — so it gets the
     * illustration, with the button blinking the same blue the aircraft's own lights use.
     */
    private fun showPairingApproval() {
        if (isFinishing || isDestroyed) return
        pairingAlert?.dismiss()
        val b = AlertDialog.Builder(this)
            .setTitle(getString(R.string.pairing_approval_title, currentModel.name))
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> gattClient?.disconnect() }
        if (currentModel.isDrone) {
            val view = layoutInflater.inflate(R.layout.dialog_drone_approval, null)
            view.findViewById<TextView>(R.id.approvalText).text = getString(R.string.drone_approval_message)
            startPowerBlink(view.findViewById(R.id.powerBlink))
            b.setView(view)
        } else {
            b.setMessage(R.string.pairing_approval_message)
        }
        pairingAlert = b.show()
    }

    /**
     * Pulse the blue power-button overlay while the dialog is up: a slow fade in/out that reads as the
     * button waiting to be pressed, matching the aircraft's own LEDs.
     *
     * Held so [dismissPairingApproval] can cancel it — an infinite animator on a detached view keeps
     * the view (and this Activity) reachable, and would otherwise outlive the dialog.
     */
    private var powerBlink: android.animation.Animator? = null

    private fun startPowerBlink(target: View) {
        powerBlink?.cancel()
        powerBlink = android.animation.ObjectAnimator.ofFloat(target, View.ALPHA, 1f, 0.15f).apply {
            duration = 900
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun dismissPairingApproval() {
        powerBlink?.cancel(); powerBlink = null
        pairingAlert?.dismiss(); pairingAlert = null
    }

    private fun onPaired() {
        // GATT notifications are dispatched straight off the BluetoothGattCallback, which is not
        // guaranteed to be the main thread — GattClient says as much, and `showPairingApproval` is
        // already posted for that reason. Everything below touches dialogs, animators and Activity
        // state, so hop first.
        //
        // This was load-bearing, not hygiene: `dismissPairingApproval` cancels the blink animator, and
        // ValueAnimator.cancel() throws AndroidRuntimeException outright on a thread with no Looper.
        // It threw before the dismiss AND before the credentials request, so a drone that had just
        // been approved sat with the dialog still up and the flow dead — recoverable only by
        // cancelling and reconnecting, which then took the silent already-paired path.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            logLine("onPaired: hopping to main from \"${Thread.currentThread().name}\"")
            main.post { onPaired() }
            return
        }
        dismissPairingApproval()
        if (!offloadMode || credsRequested) return
        credsRequested = true
        logLine("Paired — running Mimo's post-pair sequence, then reading WiFi creds…")
        // Paced writes: fff5 is write-without-response, so back-to-back frames drop, and an immediate
        // one also races the pairing-approval ACK. Order + spacing mirror the Mimo HCI snoop:
        //   0x53/0x10 -> (creds) 0x07/0x07 -> 0x07/0x0e
        // 0x53/0x10 is the one that matters: the camera answers 01 00 00 00 and wakes.
        val c = dev.konraditurbe.osmosis.duml.OsmoCommands
        main.postDelayed({ gattClient?.writeCommand(c.session5310()); logLine("sent 0x53/0x10 (wake)") }, 100)
        if (currentModel.isDrone) DronePairing.sendBleSetup(
            write = { f -> gattClient?.writeCommand(f) },
            schedule = { delay, action -> main.postDelayed(action, delay) },
            log = ::logLine,
        )
        main.postDelayed({ gattClient?.writeCommand(c.wifiQuery(0x07, id = 0x8007)) }, 900)
        main.postDelayed({ gattClient?.writeCommand(c.wifiQuery(0x0E, id = 0x800E)) }, 1400)
        main.postDelayed({
            if (offloadTriggered) return@postDelayed
            val addr = currentAddress
            // A camera that has never been activated keeps its WiFi off — there is no AP to join and no
            // password that would help — and it says so itself, in the 0x00/0x32 state push read below.
            // Show what's actually wrong rather than a password prompt for a network that doesn't exist.
            if (saysNotActivated()) {
                logLine("Camera is ${activateStateName(activateState)} — it has never been activated, so it has no WiFi AP to join.")
                showNotActivated()
                return@postDelayed
            }
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
        // DEBUG (`--ez nojoin true`): assume the phone is ALREADY on the camera/drone AP via Android's
        // own WiFi settings, and skip WifiNetworkSpecifier + bindProcessToNetwork entirely.
        //
        // This exists to make the session capturable. PCAPdroid captures through a VPN interface, but
        // bindProcessToNetwork pins our sockets to the AP network and bypasses that VPN — so a VPN-mode
        // capture sees none of our traffic (and on this tablet the bind then fails outright, killing the
        // datalink). Joining the AP normally makes it the DEFAULT route, so our traffic goes through the
        // VPN, gets captured, and still reaches the drone.
        if (noJoin) {
            logLine("OFFLOAD: --ez nojoin — skipping the WiFi join, using the current default network")
            main.postDelayed({ startDatalink() }, 1500)
            return
        }
        // AP needs a few seconds to come up; the WifiNetworkSpecifier dialog keeps searching
        // until it appears, so a modest delay before requesting the network is fine.
        main.postDelayed({ promptWifiConsent(offloadSsid, offloadPass) }, 3000)
    }

    /** Kick off the camera Wi-Fi join. Android's own WifiNetworkSpecifier consent popup is explanatory
     *  enough, so there's no app heads-up first — we only intervene if the *phone's* Wi-Fi is off (the
     *  join fails silently otherwise), routing the user to enable it and resuming here. */
    private fun promptWifiConsent(ssid: String, pass: String) {
        if (isFinishing || isDestroyed) return
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wifi != null && !wifi.isWifiEnabled) { promptEnableWifi(); return }
        startWifiFlow(ssid, pass)
    }

    /** The phone's Wi-Fi is off, so the join would fail — send the user to turn it on. Apps can't enable
     *  Wi-Fi programmatically since Android 10, so open the slide-up Wi-Fi panel (settings on older); on
     *  return [wifiPanelLauncher] re-checks and continues the join. */
    private fun promptEnableWifi() {
        logLine("Wi-Fi is OFF — prompting to enable before the camera join.")
        AlertDialog.Builder(this)
            .setTitle(R.string.wifi_off_title)
            .setMessage(R.string.wifi_off_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.turn_on_wifi) { _, _ ->
                val intent = if (Build.VERSION.SDK_INT >= 29)
                    android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI)
                else android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                runCatching { wifiPanelLauncher.launch(intent) }
                    .onFailure { runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) } }
            }
            .setCancelable(false)
            .show()
    }

    private fun startWifiFlow(ssid: String, pass: String) {
        apJoiner?.release() // release any prior request so only one WiFi specifier is pending
        setConnectProgress(35) // requesting the WiFi join
        logLine("WiFi flow: ssid=\"$ssid\" passLen=${pass.length}")
        datalinkStarted = false; wifiRejoins = 0; resumeDownloadOnRejoin = false
        val joiner = ApJoiner(this, object : ApJoiner.Listener {
            override fun onLog(s: String) = logLine(s)
            override fun onFailed(reason: String) { logLine(reason); main.post { onWifiJoinFailed() } }
            // Both callbacks arrive on a ConnectivityManager thread; hop to main so the download /
            // AP-loss flags stay single-threaded and the check-and-set in onDownloadClicked is safe.
            override fun onNetwork(network: Network, link: LinkProperties?) {
                val ip4 = link?.linkAddresses?.map { it.address }
                    ?.firstOrNull { it is java.net.Inet4Address }
                main.post {
                    wifiUp = true
                    // A second onAvailable is a rejoin. Do NOT re-run startDatalink: it would re-fetch
                    // the whole manifest and rebuild the grid, throwing away the user's queue and
                    // scroll position mid-transfer. Downloads only need HTTP, which the rebind in
                    // onAvailable has just restored.
                    if (datalinkStarted) {
                        logLine("WiFi: rejoined (ip=${ip4?.hostAddress}) — grid kept; " +
                            "list/delete/paging may need a fresh Offload")
                        maybeResumeAfterRejoin()
                        return@post
                    }
                    datalinkStarted = true
                    setConnectProgress(58) // WiFi joined + bound
                    logLine("WiFi link: ip=${ip4?.hostAddress}")
                    startDatalink()
                }
            }
            override fun onLost() {
                main.post {
                    wifiUp = false
                    if (!offloadMode) return@post
                    // Remember to pick the transfer back up: the in-flight run is about to fail out
                    // with ENONET and its own resume loop can't help — with no network it moves zero
                    // bytes, trips the "no progress" guard, and pauses on the first attempt.
                    if (downloadRunning) resumeDownloadOnRejoin = true
                    if (wifiRejoins >= MAX_WIFI_REJOINS) {
                        logLine("WiFi: AP gone and $MAX_WIFI_REJOINS rejoin attempts used — " +
                            "giving up, tap Offload to restart")
                        return@post
                    }
                    wifiRejoins++
                    logLine("WiFi: AP gone — rejoining (attempt $wifiRejoins/$MAX_WIFI_REJOINS)")
                    if (apJoiner?.rejoin() != true) logLine("WiFi: nothing to rejoin")
                }
            }
        })
        apJoiner = joiner
        val useWpa3 = currentModel.wpa3 && !wpa3FallbackDone
        joiner.join(ssid, pass, useWpa3)
    }

    /** Open the datalink and fetch the media list. Split out of the join callback so the `nojoin`
     *  debug path can run it against whatever network is already current. */
    private fun startDatalink() {
                val gen = datalinkGen.incrementAndGet()
                Thread {
                    // Datalink port + poke come from the model AND brand: 10004/no-poke was only ever
                    // confirmed on the Xtra rebrand (own OUI EC:9E:EA), so a genuine DJI unit gets the
                    // DJI-standard 9004+poke. Either guess can be wrong on an untested model, so if the
                    // handshake never lands we retry the alternate config and log which port answered.
                    fun open(m: CameraModel): Pair<MediaSession, List<CameraFile>> {
                        logLine("=== media list [${m.name}] via udp/${m.datalinkPort} (poke=${m.tcpPoke}) ===")
                        // A drone speaks a different protocol end to end — the 0x51 session-open gate,
                        // flat DCF records instead of CompositePack, /v1 instead of /v2 (DroneSession).
                        // This is the only place in the app that decides which of the two it is.
                        val c: MediaSession =
                            if (m.isDrone) DroneSession(::logLine, m.datalinkPort, bleDroneSerial)
                            else CameraSession(::logLine, m.datalinkPort, m.tcpPoke)
                        c.onStatus = { s -> main.post { onCameraStatus(s) } }
                        c.onFetchProgress = { fp -> setConnectProgress(60 + fp * 38 / 100) } // 60→98
                        // Publish before the fetch, not after: fetchFileList owns the next 10-20 s and
                        // teardown has to be able to close this socket during it.
                        pendingSession = c
                        val f = runCatching { c.fetchFileList("192.168.2.1") }
                            .getOrElse { logLine("datalink error: ${it.message}"); emptyList() }
                        return c to f
                    }

                    /** Abandon this worker's session if a newer connect has replaced it. */
                    fun superseded(dl: dev.konraditurbe.osmosis.core.MediaSession): Boolean {
                        if (gen == datalinkGen.get()) return false
                        logLine("datalink: this connect was superseded by a newer one — dropping its session")
                        runCatching { dl.close() }
                        return true
                    }

                    datalink?.close()
                    var (dl, files) = open(currentModel)
                    if (superseded(dl)) return@Thread
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
                    if (superseded(dl)) return@Thread
                    pendingSession = null
                    datalink = dl
                    dev.konraditurbe.osmosis.net.Highlights.provider = { h -> dl.getHighlights(h) }
                    // Always: it holds the AP up, polls status for the pill, and holds playback (#12).
                    // Gating on files.isNotEmpty() left an empty camera (e.g. an Action 6 with no media)
                    // with a dead pill — status is only parsed in this loop.
                    dl.startKeepAlive()
                    // Storage (/v2 mount) is resolved per file from its handle's store bit (internal
                    // 0x40000000 → storage 1, else 0), confirmed by one HEAD per store. See resolveStorage.
                    storageForBit.clear()
                    val fixed = applyStorageAndSort(files)
                    logLine("MANIFEST: ${fixed.size} files — " +
                        fixed.groupBy { it.storage }.entries.sortedBy { it.key }
                            .joinToString(", ") { (s, list) -> "storage=$s (${list.size} files)" } +
                        (if (dl.moreAvailable) " · more on scroll" else ""))
                    main.post { showGrid(fixed) }
                }.start()
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
        val message = if (bothSecuritiesTried) getString(R.string.wifi_join_failed_wpa_message, offloadSsid)
        else getString(R.string.wifi_join_failed_message)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.couldnt_join_wifi, offloadSsid))
            .setMessage(message)
            .setPositiveButton(R.string.reenter_password) { _, _ ->
                promptPasswordFor(addr) {
                    offloadPass = savedPassFor(addr)
                    logLine("Retrying Wi-Fi join with the updated password…")
                    startWifiFlow(offloadSsid, offloadPass)
                }
            }
            .setNegativeButton(R.string.back_to_cameras) { _, _ -> switchToSelector() }
            .setCancelable(false)
            .show()
    }

    // ---- media grid + download ---------------------------------------------

    private fun showGrid(files: List<CameraFile>, preserveFilters: Boolean = false) {
        // Reaching here = pairing + WiFi + datalink all worked → remember this camera, show the grid.
        setConnectProgress(100) // first media in — connection complete
        currentAddress?.let {
            savedCameras.save(it, offloadSsid, currentModelId)
            CameraShortcuts.refresh(this)   // just connected → float this camera to the top of the shortcuts
        }
        switchToGrid()
        statusPill.render(pillName(), getString(R.string.connected_wifi), currentStatus, showPower = isNano())
        applyOrientationChrome()   // hide the pill if we're (re)entering the grid in landscape
        if (!preserveFilters) resetGalleryChips()      // a fresh camera list starts unfiltered
        if (files.isEmpty()) {
            // Clear the grid before returning. The pill above has already been repainted with the
            // new camera's name, so leaving the previous camera's adapter in place shows one camera's
            // files under another camera's header — which reads as "this camera holds those videos".
            // An empty camera has to look empty.
            adapter = null
            grid.adapter = null
            imageLoader?.shutdown(); imageLoader = null
            metaLoader?.shutdown(); metaLoader = null
            updateDownloadFab()        // nothing to download; drop any queue carried from the old camera
            // "No media" and "media the camera will not list" look identical on screen, and the
            // camera itself can tell them apart: it reports each store's used space in the same
            // session. Real content behind a zero-length list is a card the camera is not indexing —
            // typically one written by another body, or not formatted in this one — and saying so is
            // the difference between a user checking their card and filing a bug against us.
            val st = currentStatus
            val usedMb = maxOf(st.sdTotalMb - st.sdFreeMb, 0) +
                maxOf(st.internalTotalMb - st.internalFreeMb, 0)
            if (usedMb > EMPTY_LIST_USED_MB) {
                logLine("No media listed, yet the camera reports ${usedMb / 1024} GB in use — the " +
                    "card may have been written by another camera, or may need formatting in this one.")
            } else {
                logLine("No media found on camera.")
            }
            toast(getString(R.string.no_media_found, pillName()))
            return
        }
        imageLoader?.shutdown()
        metaLoader?.shutdown()
        val loader = ImageLoader(http, ::logLine)
        val ml = MetaLoader(http)
        imageLoader = loader
        metaLoader = ml
        gridCols = gridColumns()
        val ad = MediaGridAdapter(this, files, loader, ml, gridCols,
            onOpen = { openPreview(it) }, onLongPress = { onGridLongPress(it) })
        adapter = ad
        ad.onQueueChanged = { updateDownloadFab() }
        // Bridge the live queue into the preview so swiping between items toggles it directly (see PreviewNav).
        dev.konraditurbe.osmosis.net.PreviewNav.isQueued = { p -> ad.isQueuedPath(p) }
        dev.konraditurbe.osmosis.net.PreviewNav.trimFor = { p -> ad.trimForPath(p) }
        dev.konraditurbe.osmosis.net.PreviewNav.setQueued = { p, q, t, m -> ad.setQueuedByPath(p, q, t, m) }
        val lm = GridLayoutManager(this, gridCols)
        // Reads the mutable gridCols so rotation can re-span without rebuilding the adapter (see
        // onConfigurationChanged) — headers span the full row at whatever the current column count is.
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (ad.isHeader(position)) gridCols else 1
        }
        grid.layoutManager = lm
        installGridSpacing()
        grid.adapter = ad
        applyChipsToAdapter()                          // re-apply any active filter to the fresh adapter
        updateDownloadFab()                            // queue survives rebuilds (path-keyed) → reflect it
        loadingMore = false
        installPullToLoadMore()
        logLine("Grid ready: ${files.size} files. Tap a cell to preview + queue, then Download. Long-press a cell to delete.")
    }

    /** 3 columns portrait, 6 landscape — matches the old GridView numColumns. */
    private fun gridColumns() =
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 6 else 3

    // Only one spacing decoration is ever attached; re-created if the column count changes.
    private var gridSpacer: RecyclerView.ItemDecoration? = null

    /** Even ~6dp gaps between cells (a touch more than the old 2dp), full-bleed date headers. */
    private fun installGridSpacing() {
        gridSpacer?.let { grid.removeItemDecoration(it) }
        val gap = (resources.displayMetrics.density * 3f).toInt()   // 3dp per edge → ~6dp between cells
        val dec = object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: android.graphics.Rect, view: View,
                                        parent: RecyclerView, state: RecyclerView.State) {
                val pos = parent.getChildAdapterPosition(view)
                if (pos != RecyclerView.NO_POSITION && adapter?.isHeader(pos) == true) {
                    outRect.set(0, gap, 0, 0)          // headers span full width; just breathe above
                } else {
                    outRect.set(gap, gap, gap, gap)
                }
            }
        }
        gridSpacer = dec
        grid.addItemDecoration(dec)
    }

    /** Wire the Photos/Videos/Faved/Select chips. Called once in onCreate; the chips act on whatever
     *  adapter is current (null before the first grid, which can't be reached without one). */
    private fun wireGalleryChips() {
        chipPhotos = findViewById(R.id.btnFilterPhotos)
        chipVideos = findViewById(R.id.btnFilterVideos)
        chipFaved = findViewById(R.id.btnFilterFaved)
        chipSelect = findViewById(R.id.btnSelect)
        chipPhotos.setOnClickListener { if (chipPhotos.isChecked) chipVideos.isChecked = false; applyChipsToAdapter() }
        chipVideos.setOnClickListener { if (chipVideos.isChecked) chipPhotos.isChecked = false; applyChipsToAdapter() }
        chipFaved.setOnClickListener { adapter?.setFavedOnly(chipFaved.isChecked) }
        chipSelect.setOnClickListener { adapter?.setSelectMode(chipSelect.isChecked); updateDownloadFab() }
        chipSelect.setOnLongClickListener {
            val ad = adapter ?: return@setOnLongClickListener true
            if (!chipSelect.isChecked) { chipSelect.isChecked = true; ad.setSelectMode(true) }
            ad.selectAllVisible(ad.selectedCount() == 0)   // nothing queued → select all visible, else clear
            true
        }
    }

    /** Reflect the queued count on the Download FAB: "Download", "Download (1)", "Download (2)", … */
    private fun updateDownloadFab() {
        val n = adapter?.selectedCount() ?: 0
        findViewById<com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton>(R.id.fabDownload)
            ?.apply {
                text = when {
                    downloadRunning -> getString(R.string.download_running)
                    n > 0 -> getString(R.string.download_count, n)
                    else -> getString(R.string.download)
                }
                // Belt to the guard's braces: the guard is what actually prevents a second run, this
                // just stops the button looking tappable while one is in flight.
                isEnabled = !downloadRunning
            }
        updateBulkDeleteFab()
    }

    /**
     * The bulk-delete FAB only exists while Select is on with something ticked — an irreversible
     * button has no business sitting on the ordinary browse screen. Hidden outright rather than
     * disabled, so there is nothing to fat-finger.
     */
    private fun updateBulkDeleteFab() {
        val n = adapter?.selectedCount() ?: 0
        val show = n > 0 && ::chipSelect.isInitialized && chipSelect.isChecked && !downloadRunning
        findViewById<com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton>(R.id.fabDelete)
            ?.apply {
                visibility = if (show) View.VISIBLE else View.GONE
                text = getString(R.string.delete_selected, n)
            }
    }

    private fun resetGalleryChips() {
        chipPhotos.isChecked = false; chipVideos.isChecked = false
        chipFaved.isChecked = false; chipSelect.isChecked = false
    }

    /** Push the chips' current state onto the active adapter. */
    private fun applyChipsToAdapter() {
        val ad = adapter ?: return
        ad.setTypeFilter(when {
            chipPhotos.isChecked -> MediaGridAdapter.TypeFilter.PHOTOS
            chipVideos.isChecked -> MediaGridAdapter.TypeFilter.VIDEOS
            else -> MediaGridAdapter.TypeFilter.ALL
        })
        ad.setFavedOnly(chipFaved.isChecked)
        ad.setSelectMode(chipSelect.isChecked)
    }

    // ---- lazy grid pagination (pull up past the last row to load older pages) --------------------
    private var loadingMore = false
    private var storageForBit = HashMap<Int, Int>()   // handle store-bit (0/1) -> resolved /v2 mount (cached)

    /** Stamp each file's HTTP storage index (per-file, by its handle's store bit) and sort newest-first —
     *  shared by the initial fetch and every lazily-loaded older page. See [resolveStorage]. */
    private fun applyStorageAndSort(files: List<CameraFile>): List<CameraFile> {
        // Drone media is index-addressed (/v1?file_index=…) — there is no /v2 mount to resolve, and its
        // names carry no `_<14 digits>_` stamp for the camera sort to key on, so order by the manifest's
        // own mtime + index instead. Probing storage here would fire a pointless HEAD per file.
        if (files.any { it.isIndexed }) return files.sortedWith(
            compareByDescending<CameraFile> { it.mtimeEpoch }.thenByDescending { it.fileIndex }
        )
        val out = files.map { f -> f.copy(storage = resolveStorage(f)) }
        return out.sortedWith(compareByDescending<CameraFile> { it.timestamp }.thenByDescending { it.seq })
    }

    /**
     * The `/v2?storage=N` mount for [f], resolved **per file** from its handle's store bit — so even a
     * manifest that fails to split its SD+internal lists into separate groups (the Action 6 has a history
     * of that) still stamps each file's own store correctly, rather than lumping one mount onto both.
     *
     * The handle encodes the physical store: internal sets bit `0x40000000` (Nano `0x4010xxxx`, Xtra/
     * Action 5 internal `0x4004xxxx` → `storage=1`), SD clears it (Xtra SD `0x0004xxxx` → `storage=0`).
     * That's only a **guess** (held 26/26 in the Xtra pcap + on the Nano, but single-store models aren't
     * uniform — Nano/Action 6 serve at storage=1, the Pocket 3 at storage=0), so one HEAD per distinct
     * store confirms it, correcting on a miss. The (bit → mount) result is cached, so a whole manifest
     * costs at most two probes. Photos carry no delete handle → use the group-fitted [CameraFile.cmdHandle];
     * a file with no handle at all (a photos-only list) → direct probe, uncached.
     */
    private fun resolveStorage(f: CameraFile): Int {
        // Pocket 3 (single microSD) is pinned to 0 — no handle math, no probe. See StorageRules.
        if (currentModel.singleSdStorage) return 0
        // The session already knows: this record came back from the store-specific query (cursor
        // 0x00000001 = SD, 0x40000001 = internal), so the mount is a fact, not an inference. Nothing
        // below this line runs for such a file — no handle bit, no HEAD.
        if (f.storageKnown) return f.storage
        // Guess the mount from the record handle's store bit, then confirm with one HEAD (cached per bit).
        val bit = dev.konraditurbe.osmosis.core.StorageRules.mountGuess(false, f.handle, f.cmdHandle)
            ?: return probeStorage(f)
        return storageForBit.getOrPut(bit) {
            val other = 1 - bit
            when {
                http.headCode(PathAddressing.byPath(bit, f.path)) == 200 -> bit
                http.headCode(PathAddressing.byPath(other, f.path)) == 200 -> other
                else -> bit
            }
        }
    }

    /** Blind mount probe for a file with no handle at all (e.g. a photos-only list, no fittable handle). */
    private fun probeStorage(f: CameraFile): Int {
        for (s in intArrayOf(1, 0)) if (http.headCode(PathAddressing.byPath(s, f.path)) == 200) return s
        return 0
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
            val more = runCatching {
                applyStorageAndSort(dl.fetchNextPage())
            }.getOrElse { emptyList() }
            main.post {
                adapter?.append(more)
                findViewById<View>(R.id.loadMoreSpinner)?.animate()?.alpha(0f)?.setDuration(180)
                    ?.withEndAction { findViewById<View>(R.id.loadMoreSpinner)?.visibility = View.GONE }?.start()
                if (more.isNotEmpty()) logLine("Loaded ${more.size} older (${adapter?.totalFiles() ?: 0} total)")
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
            statusPill.render(pillName(), getString(R.string.connected_wifi), s, showPower = isNano())
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

    /** Open the full-screen preview for the tapped cell; queue changes flow back via the launcher. For a
     *  burst/interval group, first enumerate its frames off-UI (DUML group-expand, no probing) so the
     *  viewer opens with the thumbnail strip ready. */
    private fun openPreview(f: CameraFile) {
        val dl = datalink
        if (f.isBurst && dl != null) {
            toast(getString(R.string.loading_burst))
            Thread {
                val frames = runCatching { dl.expandBurstGroup(f) }.getOrElse { listOf(f) }
                main.post { launchPreview(f, frames) }
            }.start()
        } else launchPreview(f, emptyList())
    }

    private fun launchPreview(f: CameraFile, group: List<CameraFile>) {
        val ad = adapter ?: return
        // Hand the preview the current filtered list + the tapped item's index so it can swipe prev/next.
        dev.konraditurbe.osmosis.net.PreviewNav.items = ad.visibleFiles()
        val startIndex = ad.visibleIndexOf(f.path)
        previewLauncher.launch(MediaPreviewActivity.intent(
            this, "192.168.2.1", f, startIndex, ad.isQueuedPath(f.path), ad.trimForPath(f.path), group))
    }

    /**
     * Long-press a cell → an actions dialog: **Favorite/Unfavorite** (DUML 0x02/0xbf) and, when the file
     * has a delete handle, **Delete** (0x00/0x28). Both are camera writes run off the UI thread. Keeping
     * these on the grid (not the preview) means the preview never touches the datalink.
     */
    private fun onGridLongPress(f: CameraFile) {
        val dl = datalink ?: run { logLine("Long-press: no live datalink session."); toast(getString(R.string.not_connected)); return }
        val fav = getString(if (f.starred) R.string.unfavorite else R.string.favorite)
        val del = getString(R.string.delete)
        val actions = if (f.deletable) arrayOf(fav, del) else arrayOf(fav)
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(actions) { _, which ->
                if (actions[which] == del) confirmDelete(f, dl) else toggleFavorite(f, dl)
            }
            .show()
    }

    /** Toggle the camera's ⭐ favorite for [f] (DUML 0x02/0xbf). Optimistic grid badge; the write runs on
     *  the serialized favorite worker and reverts the badge on failure. */
    private fun toggleFavorite(f: CameraFile, dl: MediaSession) {
        val on = !f.starred
        // A drone addresses by file_index; a path camera by its manifest handle, or the manifest-fitted
        // one for photos (a hardcoded Nano formula is why photo favorites failed on the Xtra — see
        // withCmdHandles). opHandle covers handle and file_index; cmdHandle is the photo fallback.
        val favHandle = if (f.opHandle != 0L) f.opHandle else f.cmdHandle
        if (favHandle == 0L) { toast(getString(R.string.favorite_no_handle, f.name)); return }
        // Optimistic badge only — the camera's manifest is the single source of truth for star state, so a
        // reload shows whatever the camera reports (the Xtra reports none; that's fine, we don't fake it).
        adapter?.setStarredByPath(f.path, on)
        toast(getString(if (on) R.string.favoriting else R.string.unfavoriting, f.name))
        cmdExec.execute {
            val ok = runCatching { dl.setFavorite(favHandle, on) }.getOrDefault(false)
            if (!ok) main.post { adapter?.setStarredByPath(f.path, !on); toast(getString(R.string.favorite_failed)) }
        }
    }

    /** Confirm + delete [f] from the camera (DUML 0x00/0x28) — irreversible, so it's gated by a dialog. */
    private fun confirmDelete(f: CameraFile, dl: MediaSession) {
        val hx = "0x%08x".format(f.opHandle)
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_from_camera_title)
            .setMessage(getString(R.string.delete_from_camera_message, f.name, hx))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                logLine("DELETE requested: ${f.name} (handle $hx)")
                toast(getString(R.string.deleting, f.name))
                cmdExec.execute {
                    val status = runCatching { dl.deleteFiles(listOf(f.opHandle)) }.getOrNull()
                    main.post {
                        when (status) {
                            0 -> {
                                logLine("DELETE OK (status 0x0000): ${f.name}")
                                toast(getString(R.string.deleted, f.name))
                                removeFromGrid(f.path)
                            }
                            null -> { logLine("DELETE: no response (timeout / no session)."); toast(getString(R.string.delete_no_response)) }
                            else -> {
                                logLine("DELETE failed: status 0x%04x for %s".format(status, f.name))
                                toast(getString(R.string.delete_failed, status))
                            }
                        }
                    }
                }
            }
            .show()
    }

    /**
     * Confirm + delete everything ticked in Select mode.
     *
     * One `0x00/0x28` carries the whole selection — the official app deleting eleven files sends a
     * single command with eleven handles and gets one `0000` back, so this is not a loop over
     * [confirmDelete] and must not become one: eleven round trips would each pay the write-window
     * re-registration, and a partial failure halfway through leaves no way to say what went.
     *
     * Files the manifest gave no usable handle for are dropped from the batch rather than guessed at
     * — [CameraFile.deletable] already covers both a missing handle (a Pocket 3 still) and one shared
     * with another file, which for an irreversible command must disqualify every claimant.
     */
    private fun onBulkDeleteClicked() {
        val ad = adapter ?: return
        val dl = datalink ?: return
        val picked = ad.selectedEntries().map { it.first }
        if (picked.isEmpty()) return
        val (deletable, skipped) = picked.partition { it.deletable }
        if (deletable.isEmpty()) { toast(getString(R.string.bulk_delete_none)); return }

        // Name the first few rather than all of them: the count is in the title, and a dialog listing
        // forty filenames scrolls the buttons off screen.
        val shown = deletable.take(BULK_DELETE_NAMES_SHOWN).joinToString("\n") { it.name }
        val more = deletable.size - BULK_DELETE_NAMES_SHOWN
        val body = buildString {
            append(shown)
            if (more > 0) append("\n").append(getString(R.string.bulk_delete_and_more, more))
            if (skipped.isNotEmpty()) append("\n\n").append(getString(R.string.bulk_delete_skipping, skipped.size))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.bulk_delete_title, deletable.size))
            .setMessage(getString(R.string.bulk_delete_message, body))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> runBulkDelete(deletable, dl) }
            .show()
    }

    private fun runBulkDelete(files: List<CameraFile>, dl: MediaSession) {
        val handles = files.map { it.opHandle }
        logLine("DELETE requested: ${files.size} files, handles " +
            handles.joinToString(" ") { "0x%08x".format(it) })
        toast(getString(R.string.bulk_deleting, files.size))
        cmdExec.execute {
            val status = runCatching { dl.deleteFiles(handles) }.getOrNull()
            main.post {
                when (status) {
                    0 -> {
                        logLine("DELETE OK (status 0x0000): ${files.size} files")
                        toast(getString(R.string.bulk_deleted, files.size))
                        removeFromGrid(files.map { it.path }.toSet())
                    }
                    // One command, one answer: there is no partial success to report. A no-reply may
                    // still have landed, which is why nothing is dropped from the grid here — the next
                    // list is the truth.
                    null -> { logLine("DELETE: no response (timeout / no session)."); toast(getString(R.string.delete_no_response)) }
                    else -> {
                        logLine("DELETE failed: status 0x%04x for %d files".format(status, files.size))
                        toast(getString(R.string.delete_failed, status))
                    }
                }
            }
        }
    }

    /**
     * Drop one cell after a confirmed delete by rebuilding the grid without it.
     *
     * The surviving files keep their handles. This used to zero them all, on the worry that a delete
     * might shift the camera's object table and leave us holding a handle that now points at a
     * different file — which for an irreversible command is the worst possible failure. A Mimo capture
     * settles it: across two deletes, the second file's handle was byte-identical before and after the
     * first was destroyed. Mimo does re-list after each delete, but to refresh what it *shows*, not
     * because the handles moved. Zeroing them made every delete after the first look unavailable.
     */
    private fun removeFromGrid(path: String) = removeFromGrid(setOf(path))

    private fun removeFromGrid(paths: Set<String>) {
        val ad = adapter ?: return
        // Drop them from the queue as well, or the Download FAB keeps counting files that no longer
        // exist and a later download tries to fetch them.
        ad.dequeuePaths(paths)
        showGrid(ad.allFilesSnapshot().filter { it.path !in paths }, preserveFilters = true)
    }

    private fun toast(s: String) =
        main.post { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show() }

    /**
     * Pick a transfer back up after the AP dropped and we got it back.
     *
     * Only fires when the previous run has actually finished — the loss and the rejoin race each
     * other, so this is called from both the rejoin and the run's teardown and whichever lands last
     * does the work. Failed items are still queued (`dequeuePaths` only drops what landed), and
     * `downloadOne` resumes from the partial file's size, so this continues rather than restarts.
     */
    private fun maybeResumeAfterRejoin() {
        if (!resumeDownloadOnRejoin || downloadRunning || !wifiUp) return
        resumeDownloadOnRejoin = false
        if ((adapter?.selectedCount() ?: 0) == 0) return
        logLine("resuming interrupted download after the WiFi rejoin")
        onDownloadClicked()
    }

    private fun onDownloadClicked() {
        // Re-entrancy guard. Main-thread confined, so a plain read/write is enough.
        if (downloadRunning) {
            logLine("Download already running — ignoring the extra tap.")
            return
        }
        val ad = adapter ?: run { logLine("Nothing listed yet — tap Offload first."); return }
        val jobs = ad.selectedEntries().map { MediaDownloader.Job(it.first, it.second) }
        // Queue keys parallel to [jobs] — used to drop each cell from the queue once it lands. Bursts queue
        // under the lead's path (the map key), which is NOT job.file.path, so we map by index, not by file.
        val keys = ad.selectedKeys()
        if (jobs.isEmpty()) {
            logLine("No files queued (tap a cell to preview + queue).")
            return
        }
        val trimmed = jobs.count { it.trim != null }
        logLine("Downloading ${jobs.size} item(s)${if (trimmed > 0) " ($trimmed trimmed)" else ""} to gallery...")
        val doneKeys = java.util.Collections.synchronizedList(mutableListOf<String>())
        val listener = object : MediaDownloader.Progress {
            private var totalBytes = 0L
            private var fileTotal = 0L
            private var count = 0
            private var lastO = -1
            private var lastF = -1

            override fun onFileDone(index: Int, done: Boolean) {
                if (done) keys.getOrNull(index)?.let { doneKeys.add(it) }
            }

            override fun onStart(totalFiles: Int, tb: Long) {
                totalBytes = tb; count = totalFiles
                main.post {
                    progressArea.visibility = View.VISIBLE
                    overallText.text = getString(R.string.overall_progress_bytes, totalFiles, fmtBytes(tb))
                    overallBar.progress = 0; fileBar.progress = 0
                }
            }

            override fun onFileStart(index: Int, name: String, fileBytes: Long) {
                fileTotal = fileBytes; lastF = -1
                main.post {
                    overallText.text = getString(R.string.overall_progress_files, index + 1, count)
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
                    fileText.text = getString(R.string.file_progress, fp, fmtBytes(fileDone), fmtBytes(fileTotal))
                }
            }

            override fun onComplete(saved: Int, skipped: Int, failed: Int) {
                main.post {
                    // Everything now on the device leaves the queue (saved + already-present); only
                    // failed/paused items stay so a later Download resumes them.
                    adapter?.dequeuePaths(doneKeys.toList())
                    updateDownloadFab()
                    overallBar.progress = 100
                    overallText.text = getString(R.string.download_done, saved, skipped, failed)
                    fileText.text = ""
                    main.postDelayed({ progressArea.visibility = View.INVISIBLE }, 3000)
                }
                logLine("DONE: $saved saved, $skipped skipped, $failed failed")
            }
        }
        downloadRunning = true
        updateDownloadFab()
        Thread {
            try {
                MediaDownloader(this, http, ::logLine).run(jobs, listener)
            } finally {
                // In a finally, not in onComplete: a throw anywhere in the run would otherwise wedge
                // the guard on and leave Download dead for the rest of the session.
                main.post {
                    downloadRunning = false
                    updateDownloadFab()
                    maybeResumeAfterRejoin()
                }
            }
        }.start()
    }

    private fun fmtBytes(b: Long): String = when {
        b >= 1_000_000_000 -> getString(R.string.fmt_bytes_gb, b / 1e9)
        b >= 1_000_000 -> getString(R.string.fmt_bytes_mb, b / 1e6)
        b >= 1_000 -> getString(R.string.fmt_bytes_kb, b / 1e3)
        else -> getString(R.string.fmt_bytes_b, b)
    }

    // ---- OsmoScanner.Listener ----------------------------------------------

    override fun onHit(device: BluetoothDevice, rssi: Int, name: String?, modelGuess: String?, modelId: Int?) {
        val addr = device.address
        // Brand matters, not just the model id: the Xtra rebrand shares model 0x0015 with the DJI
        // Osmo Action 5 Pro but uses a different datalink port. Its OUI gives it away.
        // modelId is non-null only when the DJI company id was in the advertisement (OsmoScanner sets
        // it inside that match), so it doubles as the robust "this is a DJI device" signal for Brand.
        val brand = Brand.of(addr, name, djiCid = modelId != null)
        val model = CameraModel.resolve(modelId, name, brand)
        if (discovered.put(addr, Cam(device, name, brand, rssi, modelId, model)) == null) {
            logLine("found ${model.name} [$brand] (${name ?: addr}) rssi=$rssi" +
                if (!model.verified) "  🧪" else "")
            main.post { rebuildCameraList() }
            // App Shortcut target just appeared — connect immediately, no tap, as onCamRowClick would.
            if (!connecting && !btnGps.isChecked && addr.equals(autoPickMac, ignoreCase = true)) {
                autoPickMac = null
                main.post { onCameraChosen(device) }
            }
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
            val frame = dev.konraditurbe.osmosis.duml.OsmoCommands.setPairingPin(pairPin, identifier = pairIdentity())
            val ok = gattClient?.writeCommand(frame) ?: false
            logLine("sent SetPairingPIN(pin=\"$pairPin\" id=\"${pairIdentity().take(8)}…\") ok=$ok")
        }, 120)
        // The keepalive used to re-send SetPairingPIN every 2 s, which doubled as a retry if the
        // first write dropped (fff5 is write-without-response). Now that it pings 0x00/0x2b instead,
        // retry explicitly until the camera answers — but stop once paired, so we don't re-pair.
        for (delay in longArrayOf(2500, 5000)) {
            main.postDelayed({
                if (!credsRequested && lastPairStatus == -99 && gattClient != null) {
                    logLine("pairing: no reply yet — re-sending SetPairingPIN")
                    // Must carry the SAME identity as the first attempt. This retry used to omit it and
                    // fall back to the camera default, so a dropped first write silently re-paired a
                    // drone under the wrong identity — and, for the rotation test, quietly undid it.
                    gattClient?.writeCommand(
                        dev.konraditurbe.osmosis.duml.OsmoCommands.setPairingPin(pairPin, identifier = pairIdentity())
                    )
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
                            0x02 -> "APPROVAL REQUIRED — approve on the camera / press the drone button 2s"
                            else -> "status=0x%02x".format(status)
                        }
                        logLine("PAIRING <- 0x07/45 $meaning  [${p.toHex()}]")
                        if (status == 0x02) main.post { showPairingApproval() }
                    }
                    // onPaired() is load-bearing and idempotent (guarded by credsRequested) — call it on
                    // EVERY already-paired reply, not only when the status *changes*. Gating it on the
                    // log-dedup above wedged the next camera: lastPairStatus lingered at 0x01 from the
                    // previous session (teardown's disconnect+close cancels onDisconnected, so it never
                    // reset), so the new camera's identical 0x01 was skipped and offload never started.
                    if (status == 0x01) onPaired()
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

        // The camera volunteers its own activation state, once a second, until it is activated —
        // 0x00/0x32 (AMT.OneTimeVerify), sub-command 0x33, state byte at 20, then a length-prefixed
        // serial. Measured against an HCI snoop of a factory-fresh Nano being activated: 155 of these
        // before, and the push stops dead afterwards. The values are DJI's own LctActivateState.
        if (parsed != null && parsed.cmdSet == 0x00 && parsed.cmdId == 0x32) {
            val p = parsed.payload
            if (p.size >= 21 && p[0].toInt() == 0x33 && p[1].toInt() == 0x33) {
                val st = p[20].toInt() and 0xFF
                if (st != activateState) {
                    activateState = st
                    logLine("Activation state: ${activateStateName(st)}")
                }
            }
        }

        // A drone tunnels its identity beacon inside 0x51/0x01 — and sends it over BLE too, long before
        // its AP exists. Reading the serial here means the datalink never has to hunt for one.
        if (parsed != null && parsed.cmdSet == 0x51 && parsed.cmdId == 0x01 && bleDroneSerial == null) {
            dev.konraditurbe.osmosis.drone.DroneSerial.inTunnelFrame(parsed.payload)?.let { (s, tag) ->
                bleDroneSerial = s to tag
                logLine("drone serial over BLE: ${String(s, Charsets.US_ASCII)} " +
                    "(${s.size} chars, tag 0x%02x)".format(tag))
            }
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
            dismissPairingApproval()
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

    /** After the user turns "Save logs" off, ask whether to send the just-closed log to Konrad. */
    private fun offerToShareLogs(log: java.io.File) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.share_logs_title)
            .setMessage(log.name)
            .setPositiveButton(R.string.share) { _, _ -> shareLogGzipped(log) }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    /** gzip the log into external cache and hand a content:// URI to the system share sheet. */
    private fun shareLogGzipped(log: java.io.File) {
        runCatching {
            val dir = java.io.File(externalCacheDir, "shared_logs").apply { mkdirs() }
            val gz = java.io.File(dir, log.name + ".gz")
            java.util.zip.GZIPOutputStream(gz.outputStream().buffered()).use { out ->
                log.inputStream().buffered().use { it.copyTo(out) }
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", gz)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/gzip"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.logs_email_subject, log.name))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(send, getString(R.string.share_logs_chooser)))
        }.onFailure {
            android.util.Log.e("Osmosis", "shareLogGzipped failed", it)
            android.widget.Toast.makeText(this, getString(R.string.share_logs_failed, it.message), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun short(u: java.util.UUID) = u.toString().substring(4, 8)

    companion object {
        private const val REQ_PERMS = 1001
        private const val REQ_GPS_PERMS = 1002
        /** Total AP rejoins allowed per offload session — a cap, deliberately not reset on success,
         *  so a flapping AP ends in a clear "tap Offload" rather than an endless reconnect loop. */
        private const val MAX_WIFI_REJOINS = 3
        /** Used space that makes an empty media list worth questioning rather than reporting.
         *  Comfortably above the few hundred MB of thumbnails, logs and settings a camera keeps
         *  on a card it considers empty. */
        private const val EMPTY_LIST_USED_MB = 2_000
        /** How many filenames the bulk-delete confirmation names before it says "…and N more". */
        private const val BULK_DELETE_NAMES_SHOWN = 6
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
