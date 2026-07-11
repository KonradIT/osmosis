package com.chernowii.osmosis.ui

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
import com.chernowii.osmosis.R
import com.chernowii.osmosis.core.CameraStatus

/**
 * Compact camera status card shown atop the gallery — ported from the Claude Design "Status Pill":
 * a white rounded card with a camera-name + battery-% header, a battery bar, then dot-labelled rows
 * for connection, mode (+ REC), storage and firmware. Built in code (the app uses plain Views).
 */
class StatusPillView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val nameView: TextView
    private val batteryText: TextView
    private val batteryBar: ProgressBar
    private val rows: LinearLayout

    init {
        orientation = VERTICAL
        background = context.getDrawable(R.drawable.pill_bg)
        setPadding(dp(20), dp(16), dp(20), dp(16))
        elevation = dp(6).toFloat()

        nameView = mkText(17f, true, INK).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        batteryText = mkText(15f, true, INK)
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

    fun render(name: String, connection: String, s: CameraStatus) {
        nameView.text = name
        val pct = s.batteryPercent
        batteryText.text = if (pct in 0..100) "$pct%" else "—"
        batteryBar.progress = pct.coerceIn(0, 100)
        batteryBar.progressTintList = ColorStateList.valueOf(
            when { pct < 0 -> TRACK; pct <= 15 -> RED; pct <= 35 -> ORANGE; else -> GREEN }
        )

        rows.removeAllViews()
        row(GREEN, connection)
        row(TEAL, "Mode · ${s.mode ?: "—"}")
        row(storageDot(s), storageLabel(s))
        row(if (s.recording) RED else GREEN,
            if (s.recording) "Recording · ${s.recordSeconds}s" else "Idle")
    }

    private fun storageDot(s: CameraStatus): Int {
        if (s.storageFreeMb < 0 || s.storageTotalMb <= 0) return GRAY
        val ratio = s.storageFreeMb.toFloat() / s.storageTotalMb
        return when { ratio < 0.08f -> RED; ratio < 0.2f -> ORANGE; else -> GREEN }
    }

    private fun storageLabel(s: CameraStatus): String {
        val store = if (s.sdInserted) "SD" else "Internal"
        if (s.storageFreeMb < 0) return "$store · —"
        val freeGb = s.storageFreeMb / 1024f
        return if (s.storageTotalMb > 0) "$store · %.1f / %.1f GB".format(freeGb, s.storageTotalMb / 1024f)
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
            addView(mkText(12f, false, MUTED).apply { text = label })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
    }

    private fun mkText(sizeSp: Float, bold: Boolean, color: Int) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val INK = 0xFF2B2722.toInt()
        private const val MUTED = 0xFF8C867D.toInt()
        private const val GREEN = 0xFF52B788.toInt()
        private const val ORANGE = 0xFFE0A83E.toInt()
        private const val RED = 0xFFE05A4E.toInt()
        private const val TEAL = 0xFF00838F.toInt()
        private const val GRAY = 0xFFD8D2C8.toInt()
        private const val TRACK = 0xFFE2DCD2.toInt()
    }
}
