package dev.konraditurbe.osmosis.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar

/**
 * A SeekBar that draws ◇ marks on its own track, one per highlight timestamp.
 *
 * Drawing inside the SeekBar rather than in a sibling overlay is deliberate: it inherits the widget's
 * exact geometry (thumb inset, track height, measured size) for free, so the marks always line up with
 * the thumb and the layout is unchanged from a plain SeekBar. [marks] are milliseconds; [max] is the
 * clip duration in ms, so a mark's x is the same fraction the thumb uses.
 */
class HighlightSeekBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatSeekBar(context, attrs) {

    var marks: List<Int> = emptyList()
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 15f * resources.displayMetrics.scaledDensity
    }

    fun setMarkColor(color: Int) { paint.color = color; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)   // track + thumb first
        val duration = max
        if (duration <= 0 || marks.isEmpty()) return
        val left = paddingLeft.toFloat()
        val track = (width - paddingLeft - paddingRight).toFloat()
        if (track <= 0f) return
        val cy = height / 2f - (paint.descent() + paint.ascent()) / 2f
        for (ms in marks) {
            val frac = (ms.toFloat() / duration).coerceIn(0f, 1f)
            canvas.drawText("🔶", left + frac * track, cy, paint)
        }
    }
}
