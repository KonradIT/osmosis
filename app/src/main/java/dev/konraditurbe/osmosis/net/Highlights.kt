package dev.konraditurbe.osmosis.net

/**
 * Bridge so [dev.konraditurbe.osmosis.ui.MediaPreviewActivity] (a separate activity with no datalink of
 * its own) can pull a video's highlight marks on demand. MainActivity points [provider] at the live
 * [DatalinkClient] while a session is up and clears it on teardown. Called off the UI thread; null = no
 * session → no marks.
 */
object Highlights {
    @Volatile
    var provider: ((handle: Long) -> List<Int>)? = null
}
