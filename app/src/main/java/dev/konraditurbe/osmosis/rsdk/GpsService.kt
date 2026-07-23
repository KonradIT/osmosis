package dev.konraditurbe.osmosis.rsdk

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Foreground service for R-SDK **GPS sync**: connects to the camera over BLE (R-SDK, not the media
 * path), streams the phone's GPS into it, and shows a live notification (mode / recording / GPS
 * health) with a Stop action. Foreground + `location` type is what lets Android keep feeding us
 * location while the app is backgrounded.
 */
class GpsService : Service(), RsdkController.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var rsdk: RsdkController
    private var locationManager: LocationManager? = null

    private var latest: Location? = null
    private var lastFixMs = 0L
    private var satellites = 0
    private var status: RsdkProtocol.CameraStatus? = null
    private var connected = false

    private var pushes = 0
    private var lastWriteOk = true

    /**
     * The newest fix, but only if it's actually recent. `getLastKnownLocation` can hand back a fix
     * that's hours old, and if provider updates never arrive we'd otherwise stream that one stale
     * position at 1 Hz for the whole recording — which is exactly the "only the start location"
     * symptom. Mirrors the demo's `is_current_gps_data_valid()` gate. Age is measured from the fix's
     * own timestamp, so a stale seed can never masquerade as live.
     */
    private fun freshFix(maxAgeMs: Long = PUSH_MAX_AGE_MS): Location? {
        val loc = latest ?: return null
        return if (System.currentTimeMillis() - loc.time in 0..maxAgeMs) loc else null
    }

    // Steady 1 Hz push using the latest fix — feeds the camera and keeps the BLE link alive.
    private val pushTick = object : Runnable {
        override fun run() {
            val loc = if (connected) freshFix() else null
            if (loc != null) {
                val ok = rsdk.sendGps(buildGpsFrame(loc))
                pushes++
                // Health only — never the position. Log on change or every ~15 s so a stalled feed
                // (or a dead BLE write) is diagnosable from logcat without leaking coordinates.
                if (ok != lastWriteOk || pushes % 15 == 0) {
                    val age = System.currentTimeMillis() - loc.time
                    Log.i("Osmosis", "GPS: pushes=$pushes fixAge=${age}ms sats=$satellites write=${if (ok) "ok" else "FAILED"}")
                }
                lastWriteOk = ok
            }
            main.postDelayed(this, 1000)
        }
    }

    private val locListener = LocationListener { loc ->
        // Keep the freshest fix, but don't let a coarse network fix stomp a good, recent GPS one.
        val cur = latest
        if (cur != null && System.currentTimeMillis() - lastFixMs < 5_000 &&
            loc.hasAccuracy() && cur.hasAccuracy() && loc.accuracy > 3f * cur.accuracy
        ) return@LocationListener
        latest = loc; lastFixMs = System.currentTimeMillis(); updateNotification()
    }

    @SuppressLint("MissingPermission")
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            var used = 0
            for (i in 0 until s.satelliteCount) if (s.usedInFix(i)) used++
            satellites = used
        }
    }

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "GPS sync", NotificationManager.IMPORTANCE_LOW)
        )
        rsdk = RsdkController(this, this)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stop(); return START_NOT_STICKY }
        val mac = intent?.getStringExtra(EXTRA_MAC) ?: run { stopSelf(); return START_NOT_STICKY }
        cameraName = intent.getStringExtra(EXTRA_NAME) ?: mac

        startForegroundCompat(buildNotification())

        // Start location + satellite feed (permission checked by the caller before starting us).
        // Subscribe to EVERY provider we can, not just GPS: on many devices GPS_PROVIDER delivers
        // sparsely (or not at all once backgrounded), which used to leave us pushing the seeded
        // last-known fix for the whole recording.
        locationManager = (getSystemService(LOCATION_SERVICE) as LocationManager).also { lm ->
            val wanted = buildList {
                if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
                add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
            }
            val live = wanted.filter { p ->
                runCatching { lm.requestLocationUpdates(p, 1000L, 0f, locListener, mainLooper); true }
                    .getOrElse { Log.i("Osmosis", "GPS: provider '$p' unavailable: ${it.message}"); false }
            }
            Log.i("Osmosis", "GPS: subscribed to providers=$live")
            runCatching { lm.registerGnssStatusCallback(gnssCallback, main) }
            // Seed only for the notification — freshFix() decides whether it's recent enough to send.
            latest = wanted.firstNotNullOfOrNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        }

        val device = (getSystemService(BluetoothManager::class.java))?.adapter?.getRemoteDevice(mac)
        if (device == null) { stop(); return START_NOT_STICKY }
        rsdk.connect(device)
        main.postDelayed(pushTick, 1000)
        return START_NOT_STICKY
    }

    // ---- RsdkController.Listener ---------------------------------------------
    override fun onLog(s: String) { Log.i("Osmosis", s) }
    override fun onConnected() { connected = true; updateNotification() }
    override fun onStatus(s: RsdkProtocol.CameraStatus) { status = s; updateNotification() }
    override fun onDisconnected() { if (connected) { connected = false; stop() } }
    override fun onFailed(reason: String) { Log.i("Osmosis", "GPS: $reason"); stop() }

    // ---- GPS frame from a phone fix ------------------------------------------
    /**
     * The camera stores `hour_minute_second` as a **bare local wall-clock** — the protocol carries no
     * timezone field at all (DJI's own reference demo hardcodes `hour + 8` for UTC+8). So we stamp the
     * phone's local time and the recorded time is only as right as the phone's timezone; log which
     * zone we used once, so a mismatch is diagnosable (an offset is not position data).
     */
    private fun logTimeZoneOnce() {
        if (tzLogged) return
        tzLogged = true
        val tz = java.util.TimeZone.getDefault()
        val offMin = tz.getOffset(System.currentTimeMillis()) / 60000
        Log.i("Osmosis", "GPS: stamping local wall-clock from tz=${tz.id} (UTC%+03d:%02d); protocol has no timezone field"
            .format(offMin / 60, kotlin.math.abs(offMin % 60)))
    }
    private var tzLogged = false

    private fun buildGpsFrame(loc: Location): ByteArray {
        logTimeZoneOnce()
        val cal = Calendar.getInstance().apply { timeInMillis = loc.time }
        val ymd = (cal.get(Calendar.YEAR)) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)
        val hms = cal.get(Calendar.HOUR_OF_DAY) * 10000 + cal.get(Calendar.MINUTE) * 100 + cal.get(Calendar.SECOND)
        // Split scalar speed + bearing into N/E components (cm/s); phone has no vertical velocity.
        val speedCmS = loc.speed * 100f
        val brg = Math.toRadians(loc.bearing.toDouble())
        val horizAcc = if (loc.hasAccuracy()) (loc.accuracy * 1000).roundToInt() else 1000
        val vertAcc = if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy()) (loc.verticalAccuracyMeters * 1000).roundToInt() else 1000
        val speedAcc = if (Build.VERSION.SDK_INT >= 26 && loc.hasSpeedAccuracy()) (loc.speedAccuracyMetersPerSecond * 100).roundToInt() else 10
        return rsdk.gpsFrame(
            yearMonthDay = ymd, hourMinuteSecond = hms,
            lon1e7 = (loc.longitude * 1e7).roundToInt(), lat1e7 = (loc.latitude * 1e7).roundToInt(),
            heightMm = if (loc.hasAltitude()) (loc.altitude * 1000).roundToInt() else 0,
            speedNorthCmS = (speedCmS * cos(brg)).toFloat(), speedEastCmS = (speedCmS * sin(brg)).toFloat(),
            speedDownCmS = 0f, vertAccMm = vertAcc, horizAccMm = horizAcc, speedAccCmS = speedAcc, satellites = satellites,
        )
    }

    // ---- Notification --------------------------------------------------------
    private var cameraName = ""

    private fun gpsHealthy(): Boolean {
        val loc = freshFix(HEALTH_MAX_AGE_MS) ?: return false
        return !loc.hasAccuracy() || loc.accuracy < 50f
    }

    private fun buildNotification(): Notification {
        val mode = status?.modeName ?: if (connected) "—" else "connecting…"
        val rec = if (status?.recording == true) "yes" else "no"
        val gps = if (gpsHealthy()) "healthy" else "not healthy"
        val stop = PendingIntent.getService(
            this, 0, Intent(this, GpsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("GPS sync · $cameraName")
            .setContentText("Mode - $mode - Rec - $rec - gps: $gps")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    private fun updateNotification() {
        if (!connected && status == null && latest == null) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIF_ID, n)
    }

    private fun stop() {
        main.removeCallbacks(pushTick)
        runCatching { locationManager?.removeUpdates(locListener) }
        runCatching { locationManager?.unregisterGnssStatusCallback(gnssCallback) }
        rsdk.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { super.onDestroy(); main.removeCallbacks(pushTick) }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "gps_sync"
        private const val NOTIF_ID = 42
        /** Never stream a fix older than this — a stale seed must not pose as the live position. */
        private const val PUSH_MAX_AGE_MS = 30_000L
        /** Tighter bound for the notification's "gps: healthy" readout. */
        private const val HEALTH_MAX_AGE_MS = 6_000L
        const val ACTION_STOP = "dev.konraditurbe.osmosis.GPS_STOP"
        const val EXTRA_MAC = "mac"
        const val EXTRA_NAME = "name"

        fun start(ctx: Context, mac: String, name: String) {
            ctx.startForegroundService(Intent(ctx, GpsService::class.java).putExtra(EXTRA_MAC, mac).putExtra(EXTRA_NAME, name))
        }
    }
}
