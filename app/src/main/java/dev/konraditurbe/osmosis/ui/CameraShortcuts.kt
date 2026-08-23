package dev.konraditurbe.osmosis.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.konraditurbe.osmosis.R
import dev.konraditurbe.osmosis.core.SavedCameras

/**
 * Launcher **App Shortcuts** for the paired cameras (long-press the app icon). Surfaces the cameras
 * the user actually reaches for, newest-connected first — the same idea as a messaging app showing
 * recent chats. Tapping one launches [MainActivity] straight into the connect flow for that camera
 * (see [EXTRA_MAC]); with no paired cameras there are no shortcuts.
 *
 * Dynamic (not manifest) shortcuts, because the list is per-user and changes as cameras are onboarded
 * or forgotten. [refresh] is called on every event that can change the set: app start, a successful
 * connect, and forgetting a camera.
 */
object CameraShortcuts {
    /** Intent extra on a shortcut launch: the BLE MAC of the camera to connect to. */
    const val EXTRA_MAC = "shortcut_mac"

    /** The launcher long-press menu only shows a handful; more than this is never seen. */
    private const val MAX = 4

    fun refresh(ctx: Context) {
        val cap = MAX.coerceAtMost(ShortcutManagerCompat.getMaxShortcutCountPerActivity(ctx).coerceAtLeast(1))
        val cameras = SavedCameras(ctx.getSharedPreferences("osmosis", Context.MODE_PRIVATE)).recent().take(cap)
        val shortcuts = cameras.map { cam ->
            ShortcutInfoCompat.Builder(ctx, "cam_${cam.mac}")
                .setShortLabel(cam.name)
                .setLongLabel(cam.name)
                .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_shortcut_camera))
                .setIntent(
                    Intent(ctx, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        putExtra(EXTRA_MAC, cam.mac)
                    }
                )
                .build()
        }
        // setDynamicShortcuts replaces the whole set, so a forgotten camera drops off and the order
        // re-sorts by last-connected on every refresh. Guarded: some launchers/limited profiles reject
        // the call, and a shortcut failure must never take down a connect.
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(ctx, shortcuts) }
    }
}
