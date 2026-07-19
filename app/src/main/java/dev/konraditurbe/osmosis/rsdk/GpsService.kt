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

    // Steady 1 Hz push using the latest fix — feeds the camera and keeps the BLE link alive.
    private val pushTick = object : Runnable {
        override fun run() {
            val loc = latest
            if (connected && loc != null) rsdk.sendGps(buildGpsFrame(loc))
            main.postDelayed(this, 1000)
        }
    }

    private val locListener = LocationListener { loc ->
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
        locationManager = (getSystemService(LOCATION_SERVICE) as LocationManager).also { lm ->
            runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, mainLooper) }
                .onFailure { Log.i("Osmosis", "GPS: location updates failed: ${it.message}") }
            runCatching { lm.registerGnssStatusCallback(gnssCallback, main) }
            latest = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
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
    private fun buildGpsFrame(loc: Location): ByteArray {
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

    private fun gpsHealthy(): Boolean =
        latest != null && System.currentTimeMillis() - lastFixMs < 6000 && (latest?.hasAccuracy() != true || latest!!.accuracy < 50f)

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
        const val ACTION_STOP = "dev.konraditurbe.osmosis.GPS_STOP"
        const val EXTRA_MAC = "mac"
        const val EXTRA_NAME = "name"

        fun start(ctx: Context, mac: String, name: String) {
            ctx.startForegroundService(Intent(ctx, GpsService::class.java).putExtra(EXTRA_MAC, mac).putExtra(EXTRA_NAME, name))
        }
    }
}
