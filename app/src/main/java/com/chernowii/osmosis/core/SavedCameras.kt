package com.chernowii.osmosis.core

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

    fun save(mac: String, name: String, modelId: Int?) {
        val next = prefs.getStringSet(KEY, emptySet()).orEmpty()
            .filterNot { it.substringBefore('|') == mac }
            .toMutableSet()
        next.add("$mac|$name|${modelId ?: -1}")
        prefs.edit().putStringSet(KEY, next).apply()
    }

    fun remove(mac: String) {
        val next = prefs.getStringSet(KEY, emptySet()).orEmpty()
            .filterNot { it.substringBefore('|') == mac }
            .toSet()
        prefs.edit().putStringSet(KEY, next).apply()
    }

    private fun parse(s: String): Entry? {
        val p = s.split('|')
        return if (p.size >= 3) Entry(p[0], p[1], p[2].toIntOrNull() ?: -1) else null
    }

    companion object { private const val KEY = "saved_cameras" }
}
