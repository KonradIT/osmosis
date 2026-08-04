package dev.konraditurbe.osmosis.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Frame source for the scrub preview: decodes frames out of a clip that is still **on the camera**,
 * without downloading it. [MediaMetadataRetriever] speaks HTTP and range-requests, so it fetches the
 * container header plus only the keyframes it needs — the same property that lets the player scrub a
 * multi-GB clip over the camera's AP. It is pointed at whatever URL the player ended up streaming,
 * normally the small `.LRF`/`.XRF` proxy, which keeps each frame to a handful of ranges.
 *
 * Two tiers, both served by [nearest] so the bubble always has *something* to show:
 *  - a coarse **grid** laid down by [prefetch] in the background — instant, permanently cached, and
 *    what the user sees the moment they touch the bar;
 *  - **on-demand** frames from [request] as the finger settles, which jump the queue and sharpen the
 *    preview to the keyframe actually under the thumb.
 *
 * One worker thread owns the retriever for its whole life — a retriever is not thread-safe, and
 * releasing one underneath an in-flight `getFrameAtTime` can take the native decoder down with it.
 */
class ScrubFrames(private val log: (String) -> Unit) {

    private data class Job(val ms: Long, val onDemand: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingDeque<Job>()
    /** The coarse grid: small, fixed, never evicted — the fallback that keeps the bubble non-empty. */
    private val grid = ConcurrentSkipListMap<Long, Bitmap>()
    /** On-demand frames, bounded: newest [MAX_EXACT] kept, oldest dropped (a drag can ask for many). */
    private val exact = ConcurrentSkipListMap<Long, Bitmap>()
    private val exactOrder = ArrayDeque<Long>()
    private var worker: Thread? = null
    @Volatile private var closed = true
    /** Newest on-demand request; anything else queued as on-demand is a finger position we've left. */
    @Volatile private var wanted = -1L

    /**
     * Point at [url] and start the worker. Frames come back on the main thread as `(requestedMs,
     * bitmap)`, decoded to at most [cellW]×[cellH] so a whole session's cache is a few MB, not a
     * screenful of full-size bitmaps. A source that won't open just means no frames ever arrive.
     */
    fun open(url: String, cellW: Int, cellH: Int, onFrame: (Long, Bitmap) -> Unit) {
        close()
        closed = false
        worker = Thread {
            val mmr = MediaMetadataRetriever()
            var opened = false
            try {
                mmr.setDataSource(url, emptyMap())
                opened = true
                while (!closed) {
                    val job = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    if (job.onDemand && job.ms != wanted) continue   // the finger has moved on
                    if (cached(job.ms) != null) continue
                    val started = System.currentTimeMillis()
                    // CLOSEST_SYNC = nearest keyframe, the only cheap seek: an exact-frame request
                    // would drag in every P-frame back to the previous I-frame, over the AP. On a
                    // proxy's short GOP that lands within a second of the thumb, which is all a
                    // scrub preview needs.
                    val bmp = runCatching {
                        mmr.getScaledFrameAtTime(
                            job.ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, cellW, cellH
                        )
                    }.getOrNull()
                    if (bmp == null) continue
                    put(job, bmp)
                    log("scrub: frame @${job.ms} ms in ${System.currentTimeMillis() - started} ms" +
                        if (job.onDemand) " (on demand)" else "")
                    main.post { if (!closed) onFrame(job.ms, bmp) }
                }
            } catch (e: Exception) {
                log("scrub: ${if (opened) "decode stopped" else "can't open"} $url — " +
                    "${e.javaClass.simpleName} ${e.message}")
            } finally {
                runCatching { mmr.release() }
            }
        }.apply { isDaemon = true; name = "scrub-frames" }.also { it.start() }
    }

    /** Queue the coarse grid: [count] frames spread over the clip, decoded behind any live request. */
    fun prefetch(durationMs: Long, count: Int) {
        gridTimes(durationMs, count).forEach { queue.addLast(Job(it, onDemand = false)) }
    }

    /** Ask for the frame at [ms] ahead of everything else. Callers should debounce — a drag would
     *  otherwise queue one per pixel (they'd be skipped as stale, but for free churn). */
    fun request(ms: Long) {
        wanted = ms
        queue.addFirst(Job(ms, onDemand = true))
    }

    /** The cached frame closest to [ms] from either tier, or null while the cache is still empty. */
    fun nearest(ms: Long): Bitmap? {
        val a = closestIn(grid, ms)
        val b = closestIn(exact, ms)
        return when {
            a == null -> b?.second
            b == null -> a.second
            kotlin.math.abs(a.first - ms) <= kotlin.math.abs(b.first - ms) -> a.second
            else -> b.second
        }
    }

    /** Stop the worker and drop the cache — call when the viewer moves to another clip or closes. */
    fun close() {
        closed = true
        worker = null
        queue.clear()
        grid.clear()
        synchronized(exactOrder) { exact.clear(); exactOrder.clear() }
        wanted = -1L
    }

    private fun cached(ms: Long): Bitmap? = grid[ms] ?: exact[ms]

    private fun put(job: Job, bmp: Bitmap) {
        if (!job.onDemand) { grid[job.ms] = bmp; return }
        synchronized(exactOrder) {
            exact[job.ms] = bmp
            exactOrder.addLast(job.ms)
            while (exactOrder.size > MAX_EXACT) exact.remove(exactOrder.removeFirst())
        }
    }

    private fun closestIn(map: ConcurrentSkipListMap<Long, Bitmap>, ms: Long): Pair<Long, Bitmap>? {
        val lo = map.floorEntry(ms)
        val hi = map.ceilingEntry(ms)
        val pick = when {
            lo == null -> hi
            hi == null -> lo
            ms - lo.key <= hi.key - ms -> lo
            else -> hi
        } ?: return null
        return pick.key to pick.value
    }

    companion object {
        /** How many on-demand frames to keep. Each is bubble-sized, so this is a couple of MB. */
        private const val MAX_EXACT = 16

        /**
         * Timestamps (ms) for a [count]-cell grid: the **middle** of each cell's slice, so a cell
         * represents its span rather than the cut at its left edge — and so cell 0 isn't the black
         * first frame most clips open on.
         */
        fun gridTimes(durationMs: Long, count: Int): List<Long> =
            (0 until count).map { durationMs * (2 * it + 1) / (2L * count) }
    }
}
