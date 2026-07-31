package dev.konraditurbe.osmosis.net

import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.TrimRange

/**
 * Bridges the grid's **filtered** list and its download queue into the full-screen preview, so the
 * viewer can swipe between items (in the exact order + filter the grid shows) and toggle the queue live
 * — without stuffing the whole list into an Intent or relaunching the Activity per swipe. Same one-way
 * static-bridge pattern as [Highlights].
 *
 * [items] is set per launch (the current filtered list); the accessors are wired to the live adapter
 * while a grid is up and cleared on teardown. All calls happen on the main thread.
 */
object PreviewNav {
    @Volatile var items: List<CameraFile> = emptyList()
    @Volatile var isQueued: ((path: String) -> Boolean)? = null
    @Volatile var trimFor: ((path: String) -> TrimRange?)? = null
    @Volatile var setQueued: ((path: String, queued: Boolean, trim: TrimRange?, member: CameraFile?) -> Unit)? = null

    fun clear() { items = emptyList(); isQueued = null; trimFor = null; setQueued = null }
}
