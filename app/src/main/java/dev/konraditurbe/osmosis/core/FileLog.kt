package dev.konraditurbe.osmosis.core

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide "Save logs" file writer, shared by the UI and the background services.
 *
 * It deliberately does **not** live on the Activity: GPS sync runs as a foreground service and the
 * user is typically out with the phone (screen off, Activity destroyed, no adb in sight), so the
 * R-SDK/GPS lines have to keep landing in the file after the UI is gone. Every write is flushed, so
 * whatever happened is on disk even if the process is later killed.
 *
 * Files: `<externalFilesDir>/logs/osmosis_<yyyyMMdd_HHmmss>.log`, newest 5 kept. Pull with
 * `adb pull /sdcard/Android/data/dev.konraditurbe.osmosis/files/logs/`.
 *
 * Never write coordinates or credentials here — this file is meant to be shared around.
 */
object FileLog {
    private const val PREFS = "osmosis"
    private const val KEY_ENABLED = "save_logs"
    private const val KEEP = 5

    private val lock = Any()
    private var writer: Writer? = null

    fun logsDir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }

    /** True while a log file is open. */
    fun isOn(): Boolean = synchronized(lock) { writer != null }

    /** Open a session log if the user's "Save logs" toggle is on. Idempotent + safe from any thread. */
    fun startIfEnabled(ctx: Context) {
        val on = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
        if (on) start(ctx)
    }

    /** Open a new timestamped session log, pruning to the [KEEP] newest. No-op if already open. */
    fun start(ctx: Context) {
        synchronized(lock) {
            if (writer != null) return
            runCatching {
                val dir = logsDir(ctx)
                dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
                    ?.sortedByDescending { it.lastModified() }?.drop(KEEP - 1)?.forEach { it.delete() }
                val name = "osmosis_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.log"
                val file = File(dir, name)
                writer = BufferedWriter(FileWriter(file, true))
                writeLocked("=== saving logs to ${file.absolutePath} ===")
            }.onFailure { android.util.Log.e("Osmosis", "FileLog.start failed", it) }
        }
    }

    fun stop() {
        synchronized(lock) {
            val w = writer ?: return
            writer = null
            runCatching { w.flush(); w.close() }
        }
    }

    /** Append a timestamped line; no-op when logging is off. Safe from any thread. */
    fun write(s: String) = synchronized(lock) { writeLocked(s) }

    private fun writeLocked(s: String) {
        val w = writer ?: return
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        runCatching { w.write("[$ts] $s\n"); w.flush() }
    }
}
