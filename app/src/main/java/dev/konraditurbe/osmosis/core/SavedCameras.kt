package dev.konraditurbe.osmosis.core

import android.content.SharedPreferences

/**
 * Cameras the user has onboarded (paired + connected at least once). Persisted in the "osmosis"
 * prefs as a string set of "mac|name|modelId" (modelId = BLE model byte, or -1 if unknown, e.g. the
 * Pocket 3 which sends no manufacturer data). The WiFi password stays under its own "pass_<mac>" key.
 */
class SavedCameras(private val prefs: SharedPreferences) {
    data class Entry(val mac: String, val name: String, val modelId: Int)

    fun all(): List<Entry> =
        prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull(::parse).sortedBy { it.name.lowercase() }

    /**
     * Saved cameras ordered most-recently-connected first — drives the launcher App Shortcuts, which
     * surface the cameras the user reaches for. Never-timestamped entries (saved before this existed)
     * sort last in name order, since [all] is name-sorted and the sort below is stable.
     */
    fun recent(): List<Entry> = all().sortedByDescending { prefs.getLong(tsKey(it.mac), 0L) }

    /** Save (or refresh) a camera and stamp it as just-connected, so [recent] floats it to the top. */
    fun save(mac: String, name: String, modelId: Int?) {
        val next = prefs.getStringSet(KEY, emptySet()).orEmpty()
            .filterNot { it.substringBefore('|') == mac }
            .toMutableSet()
        next.add("$mac|$name|${modelId ?: -1}")
        prefs.edit().putStringSet(KEY, next).putLong(tsKey(mac), System.currentTimeMillis()).apply()
    }

    fun remove(mac: String) {
        val next = prefs.getStringSet(KEY, emptySet()).orEmpty()
            .filterNot { it.substringBefore('|') == mac }
            .toSet()
        prefs.edit().putStringSet(KEY, next).remove(tsKey(mac)).apply()
    }

    private fun parse(s: String): Entry? {
        val p = s.split('|')
        return if (p.size >= 3) Entry(p[0], p[1], p[2].toIntOrNull() ?: -1) else null
    }

    private fun tsKey(mac: String) = "cam_ts_$mac"

    companion object { private const val KEY = "saved_cameras" }
}
