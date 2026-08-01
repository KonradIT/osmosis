package dev.konraditurbe.osmosis

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * Applies Material You **dynamic color** to every Activity on Android 12+ (API 31), so the app's accent,
 * surfaces and background follow the user's system/wallpaper palette. It's a no-op below API 31, where the
 * static [dev.konraditurbe.osmosis.R.style] theme (teal accent, cream light / neutral dark) is used instead
 * — keeping everything working back to Android 10 (minSdk 29).
 */
class OsmosisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
