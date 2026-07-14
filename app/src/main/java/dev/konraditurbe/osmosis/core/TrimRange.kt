package dev.konraditurbe.osmosis.core

/** A time window (ms, on the clip's own timeline) selected in the preview for a trimmed download. */
data class TrimRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
    val isValid: Boolean get() = endMs > startMs
}
