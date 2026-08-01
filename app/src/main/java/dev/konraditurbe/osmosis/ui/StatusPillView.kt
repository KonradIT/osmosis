package dev.konraditurbe.osmosis.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import dev.konraditurbe.osmosis.R
import dev.konraditurbe.osmosis.core.CameraStatus

/**
 * Compact camera status card shown atop the gallery — ported from the Claude Design "Status Pill":
 * a white rounded card with a camera-name + battery-% header, a battery bar, then dot-labelled rows
 * for connection and storage. Built in code (the app uses plain Views).
 */
class StatusPillView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val nameView: TextView
    private val batteryText: TextView
    private val batteryBar: ProgressBar
    private val rows: LinearLayout

    // Neutral colors follow the theme (so they adapt to dark mode + Material You dynamic color); the
    // battery/storage status hues are fixed semantic colors (with a -night variant).
    private val ink = attr(com.google.android.material.R.attr.colorOnSurface, 0xFF2B2722.toInt())
    private val muted = attr(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF8C867D.toInt())
    private val track = attr(com.google.android.material.R.attr.colorSurfaceVariant, 0xFFE2DCD2.toInt())
    private val gray = attr(com.google.android.material.R.attr.colorOutline, 0xFFD8D2C8.toInt())
    private val green = ContextCompat.getColor(context, R.color.osmo_green)
    private val orange = ContextCompat.getColor(context, R.color.osmo_warn)
    private val red = ContextCompat.getColor(context, R.color.osmo_low)

    private fun attr(attrRes: Int, fallback: Int) = MaterialColors.getColor(context, attrRes, fallback)

    init {
        orientation = VERTICAL
        background = context.getDrawable(R.drawable.pill_bg)
        setPadding(dp(20), dp(16), dp(20), dp(16))
        elevation = dp(6).toFloat()

        nameView = mkText(17f, true, ink).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        batteryText = mkText(15f, true, ink)
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(nameView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(batteryText)
        })

        batteryBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = context.getDrawable(R.drawable.battery_progress)
        }
        addView(batteryBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(12) })

        rows = LinearLayout(context).apply { orientation = VERTICAL }
        addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    /**
     * [showPower] gates the voltage / current / dock line: the `0x0d/0x02` byte layout was only ever
     * mapped on the Nano (by docking/undocking one and diffing the frame), so other models could show
     * garbage. The caller passes `true` only for the Nano.
     */
    fun render(name: String, connection: String, s: CameraStatus, showPower: Boolean) {
        nameView.text = name
        val pct = s.batteryPercent
        // A charging bolt next to the percentage — the camera reports this while docked.
        batteryText.text = when {
            pct !in 0..100 -> "—"
            s.charging -> "⚡ $pct%"
            else -> "$pct%"
        }
        batteryBar.progress = pct.coerceIn(0, 100)
        batteryBar.progressTintList = ColorStateList.valueOf(
            when { pct < 0 -> track; s.charging -> green; pct <= 15 -> red; pct <= 35 -> orange; else -> green }
        )

        rows.removeAllViews()
        row(green, connection)
        // One line per store the camera actually has, so a card is never mislabelled as built-in
        // (which is what happened while we showed only the active store under a fixed "Internal").
        for ((label, free, total) in storeLines(s)) row(storageDot(free, total), storeLabel(label, free, total))
        if (showPower && s.hasPowerInfo) row(if (s.charging) green else gray, powerLabel(s))
    }

    /**
     * Power line from the `0x0d/0x02` battery frame: pack voltage plus charge/draw current, and
     * whether the dock is attached. The dock's *own* charge level isn't reported by the camera at all
     * (ROADMAP #5), so we show the camera's power state rather than inventing a dock percentage.
     */
    private fun powerLabel(s: CameraStatus): String {
        val volts = "%.2f V".format(s.batteryMilliVolts / 1000f)
        val ma = s.batteryMilliAmps
        val flow = when {
            ma > 0 -> "charging %d mA".format(ma)
            ma < 0 -> "drawing %d mA".format(-ma)
            else -> "idle"
        }
        // Attached but not (yet) taking charge — seen mid-transition and when the pack is full.
        val dock = if (s.docked && !s.charging) " · docked" else ""
        return "$volts · $flow$dock"
    }

    /**
     * The store lines to render: `SD card` first (only when one is actually in) then `Internal`.
     * Falls back to the single active-store figure from `0x02/0x80` for cameras that haven't sent a
     * per-store `0x02/0xDC` frame yet — labelled neutrally, since which store it refers to is unknown.
     */
    private fun storeLines(s: CameraStatus): List<Triple<String, Int, Int>> {
        val lines = ArrayList<Triple<String, Int, Int>>()
        if (s.sdInserted) lines.add(Triple("SD card", s.sdFreeMb, s.sdTotalMb))
        if (s.internalTotalMb > 0) lines.add(Triple("Internal", s.internalFreeMb, s.internalTotalMb))
        if (lines.isEmpty()) lines.add(Triple("Storage", s.storageFreeMb, s.storageTotalMb))
        return lines
    }

    private fun storageDot(freeMb: Int, totalMb: Int): Int {
        if (freeMb < 0 || totalMb <= 0) return gray
        val ratio = freeMb.toFloat() / totalMb
        return when { ratio < 0.08f -> red; ratio < 0.2f -> orange; else -> green }
    }

    private fun storeLabel(store: String, freeMb: Int, totalMb: Int): String {
        if (freeMb < 0) return "$store · —"
        val freeGb = freeMb / 1024f
        return if (totalMb > 0) "$store · %.1f / %.1f GB".format(freeGb, totalMb / 1024f)
        else "$store · %.1f GB free".format(freeGb)
    }

    private fun row(dotColor: Int, label: String) {
        val dot = View(context).apply {
            background = context.getDrawable(R.drawable.status_dot)
            backgroundTintList = ColorStateList.valueOf(dotColor)
        }
        rows.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(8) })
            addView(mkText(12f, false, muted).apply { text = label })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
    }

    private fun mkText(sizeSp: Float, bold: Boolean, color: Int) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
