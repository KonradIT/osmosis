package dev.konraditurbe.osmosis.core

/**
 * The human-readable name for a frame size — a **lookup, not a calculation**.
 *
 * This replaces a `coarseRes(w, h)` that bucketed on `maxOf(w, h)` against thresholds. That works only
 * for 16:9, and the moment the vertical and square format codes were mapped it started lying: a
 * 2160×2160 square clip bucketed as **"1080p"** (2160 clears the 1900 threshold but not 3600), and
 * 3072×3072 as "2.7K". `3840×3840` came out right purely by luck. There is no threshold arrangement
 * that fixes this, because the mapping from pixels to a marketing name is not a function of any single
 * dimension — 1080×1920, 1920×1080 and 1080×1080 are three different names off the same two numbers.
 *
 * So: name every size we actually know, show the **pixel size** for the ones we don't, and keep "?" for
 * the case where the manifest gave us no size at all. A wrong name is believed; a plain `5472×3078` is
 * not, and it still tells a reader who knows their formats exactly what they are looking at.
 *
 * The camera entries are the format enum from MEDIA_PROTOCOL.md § Resolution, which is where their
 * names come from. The rest are sizes the drone table ([dev.konraditurbe.osmosis.dcf.DcfRecords])
 * can emit whose names are unambiguous. The other 36 drone sizes are deliberately NOT named: the two
 * enums, as the protocol doc notes, share codes but not vocabulary — the drone's `0x32` is 2880×1620
 * while the camera calls 3072 "3K" — so a name guessed from one side is not safe on the other. They
 * fall through to their pixel size, which is always true.
 */
object VideoFormats {

    /** Shown when a size has no name on record. */
    const val UNKNOWN = "?"

    private val NAMES: Map<String, String> = mapOf(
        // ---- camera video format enum (MEDIA_PROTOCOL.md § Resolution) ----
        "1920x1080" to "1080p",
        "1920x1440" to "1080p 4:3",
        "3840x2160" to "4K",
        "2688x1512" to "2.7K",
        "1080x1920" to "1080p 9:16",
        "1512x2688" to "2.7K 9:16",
        "2688x2016" to "2.7K 4:3",
        "3840x2880" to "4K 4:3",
        "1080x1080" to "1080p 1:1",
        "2160x2160" to "2160p 1:1",
        "3072x3072" to "3K 1:1",
        "1728x3072" to "3K 9:16",
        "3840x3840" to "4K OpenGate",

        // ---- drone sizes whose name is standard and unambiguous ----
        "640x480" to "480p",
        "1280x720" to "720p",
        "720x1280" to "720p 9:16",
        "2560x1440" to "1440p",
        "2704x1520" to "2.7K",
        "4096x2160" to "4K DCI",
        "5120x2700" to "5.1K",
        "7680x4320" to "8K",
    )

    /**
     * The name for a `"WxH"` string as the manifest decoders produce it.
     *
     * Three outcomes, in order: a **name** when the size has one; the **pixel size itself** when it
     * doesn't but the camera did tell us the frame ("5472×3078" beats "?" — a reader who knows the
     * format gets it, and one who doesn't is no worse off than with a shrug); and [UNKNOWN] only when
     * there is genuinely nothing to show, i.e. the manifest carried no size or an unreadable one.
     *
     * That fallback is why the 36 drone sizes with no agreed name are absent from [NAMES] rather than
     * mapped to themselves — an identity row per size would have to be maintained forever and would
     * make a *newly named* format look like a table edit instead of an addition.
     *
     * Takes the string rather than two ints on purpose: every producer already holds it in this form
     * ([CameraFile.resolution]), and parsing it back into numbers only to look it up again is the step
     * that invited arithmetic in the first place. The only thing done to the digits is swapping the
     * separator for a real `×`, which is display, not measurement.
     */
    fun label(resolution: String?): String {
        val px = resolution?.trim().orEmpty()
        if (px.isEmpty()) return UNKNOWN
        NAMES[px]?.let { return it }
        return prettyPixels(px) ?: UNKNOWN
    }

    /** `"3840x2160"` → `"3840×2160"`, or null if it isn't a pair of numbers. */
    private fun prettyPixels(px: String): String? {
        val parts = px.split('x')
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return "$w×$h"
    }

    /** True when [resolution] has a name — for callers that would rather show nothing than "?". */
    fun isNamed(resolution: String?): Boolean = resolution != null && resolution in NAMES
}
