package dev.konraditurbe.osmosis.ui

import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import kotlin.math.max
import kotlin.math.min

/**
 * Pinch-to-zoom and pan for the preview's photo.
 *
 * The view is left in `fitCenter` until the first zoom, and put straight back there the moment the
 * scale returns to 1. That matters for more than tidiness: the preview's swipe-to-navigate and
 * tap-to-toggle gestures are unchanged at rest, and [isZoomed] is what lets the activity suppress a
 * navigation fling while the user is panning around a magnified frame.
 *
 * The matrix is rebuilt from scratch on every change rather than accumulated, so scale and translation
 * cannot drift apart over a long gesture, and the clamp below is always applied to the final result.
 */
class PhotoZoom(private val view: ImageView) {

    private companion object {
        const val MIN = 1f
        const val MAX = 6f
        const val DOUBLE_TAP = 2.5f
    }

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    /** True once magnified — the activity checks this before treating a drag as prev/next navigation. */
    val isZoomed: Boolean get() = zoom > 1.001f

    private val scaleDetector = ScaleGestureDetector(
        view.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setZoom(zoom * d.scaleFactor, d.focusX, d.focusY)
                return true
            }
        },
    )

    private var lastX = 0f
    private var lastY = 0f
    private var panning = false

    /** Back to fit. Called whenever a different photo is shown, so zoom never leaks between items. */
    fun reset() {
        zoom = 1f; panX = 0f; panY = 0f; panning = false
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        view.imageMatrix = android.graphics.Matrix()
    }

    /** Double-tap: jump to [DOUBLE_TAP] about the tapped point, or back to fit if already zoomed. */
    fun toggle(focusX: Float, focusY: Float) {
        if (isZoomed) reset() else setZoom(DOUBLE_TAP, focusX, focusY)
    }

    /**
     * Feed a touch event. Returns true when this consumed it — i.e. a pinch is in progress, or a drag
     * that is panning a zoomed image. At rest it returns false so the activity's own detector sees the
     * gesture untouched.
     */
    fun onTouch(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        if (scaleDetector.isInProgress) { panning = false; return true }
        if (!isZoomed) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = ev.x; lastY = ev.y; panning = true }
            MotionEvent.ACTION_MOVE -> if (panning && ev.pointerCount == 1) {
                panX += ev.x - lastX
                panY += ev.y - lastY
                lastX = ev.x; lastY = ev.y
                apply()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> panning = false
            // A second finger going down mid-drag is the start of a pinch, not a jump in the pan.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastX = ev.x; lastY = ev.y
            }
        }
        return true
    }

    /** Set the zoom, keeping the content under ([focusX], [focusY]) put. */
    private fun setZoom(target: Float, focusX: Float, focusY: Float) {
        val next = min(MAX, max(MIN, target))
        if (next == zoom) return
        // Pan is stored in view pixels, so it scales with the image: the point under the fingers only
        // stays still if the existing offset grows by the same ratio the image does.
        val ratio = next / zoom
        panX = (panX - focusX) * ratio + focusX
        panY = (panY - focusY) * ratio + focusY
        zoom = next
        if (!isZoomed) reset() else apply()
    }

    private fun apply() {
        val d = view.drawable ?: return
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return

        val fit = min(vw / dw, vh / dh)          // what FIT_CENTER would have used
        val s = fit * zoom
        val w = dw * s
        val h = dh * s

        // Centre the axis while the image is smaller than the view; otherwise keep its edges outside it,
        // so a zoomed photo can never be dragged away leaving a black gap.
        panX = if (w <= vw) (vw - w) / 2f else panX.coerceIn(vw - w, 0f)
        panY = if (h <= vh) (vh - h) / 2f else panY.coerceIn(vh - h, 0f)

        view.scaleType = ImageView.ScaleType.MATRIX
        view.imageMatrix = android.graphics.Matrix().apply {
            setScale(s, s)
            postTranslate(panX, panY)
        }
    }

    /** Re-clamp after a layout change (rotation), keeping the current zoom. */
    fun onLayoutChanged() {
        if (isZoomed) apply()
    }

    /** Attach so a size change re-clamps rather than stranding the image off-view. */
    init {
        view.addOnLayoutChangeListener { _: View, _, _, _, _, _, _, _, _ -> onLayoutChanged() }
    }
}
