package com.litepptx.viewer

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ListView

/**
 * ListView with pinch-zoom, double-tap zoom and panning.
 * While zoomed, horizontal drags pan and vertical drags keep scrolling through the slides.
 */
class ZoomableListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = android.R.attr.listViewStyle
) : ListView(context, attrs, defStyle) {

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val maxScale = 4f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomTo(scale * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > 1.05f) zoomTo(1f, e.x, e.y) else zoomTo(2.5f, e.x, e.y)
                return true
            }
        }
    )

    fun resetZoom() {
        scale = 1f
        tx = 0f
        ty = 0f
        invalidate()
    }

    private fun zoomTo(target: Float, fx: Float, fy: Float) {
        val ns = target.coerceIn(1f, maxScale)
        val k = ns / scale
        tx = fx - (fx - tx) * k
        ty = fy - (fy - ty) * k
        scale = ns
        clamp()
        invalidate()
    }

    private fun clamp() {
        if (scale <= 1.001f) {
            scale = 1f
            tx = 0f
            ty = 0f
        } else {
            tx = tx.coerceIn(width * (1f - scale), 0f)
            ty = ty.coerceIn(height * (1f - scale), 0f)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clamp()
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (scale > 1.001f || e.pointerCount > 1) return true
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val wasZoomed = scale > 1.001f
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !wasZoomed) {
            // a second finger arrived: cancel the list's own one-finger scroll
            val cancel = MotionEvent.obtain(e)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.onTouchEvent(cancel)
            cancel.recycle()
        }
        scaleDetector.onTouchEvent(e)
        tapDetector.onTouchEvent(e)

        if (wasZoomed || scale > 1.001f || scaleDetector.isInProgress || e.pointerCount > 1) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x
                    lastY = e.y
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    val skip = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
                    val i = if (skip == 0) 1 else 0
                    if (i < e.pointerCount) {
                        lastX = e.getX(i)
                        lastY = e.getY(i)
                    }
                }
                MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress && e.pointerCount == 1) {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x
                    lastY = e.y
                    tx += dx
                    clamp()
                    if (dy != 0f) scrollListBy(-(dy / scale).toInt())
                    invalidate()
                }
            }
            return true
        }
        return super.onTouchEvent(e)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (scale > 1.001f) {
            canvas.save()
            canvas.translate(tx, ty)
            canvas.scale(scale, scale)
            super.dispatchDraw(canvas)
            canvas.restore()
        } else {
            super.dispatchDraw(canvas)
        }
    }
}
