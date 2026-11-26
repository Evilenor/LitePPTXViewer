package com.litepptx.viewer.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Shows a photo with a draggable four-corner outline. Drag a corner, or the dot in the middle of a
 * side to move that whole side. A magnifier follows the finger so the corner can be placed exactly.
 * Corners are kept normalised (0..1 of the photo) as TL, TR, BR, BL.
 */
class CropView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var bmp: Bitmap? = null
    private val q = FloatArray(8)
    private val dst = RectF()
    private val d = resources.displayMetrics.density
    private val pad = 24f * d
    private var active = -1          // 0..3 corners, 4..7 side midpoints
    private var grabX = 0f
    private var grabY = 0f

    private val accent = 0xFF4C8DF6.toInt()
    private val warn = 0xFFFF6B6B.toInt()
    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dim = Paint().apply { color = 0x99000000.toInt(); style = Paint.Style.FILL }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeJoin = Paint.Join.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val path = Path()
    private val clip = Path()
    private val mat = Matrix()

    var onChanged: (() -> Unit)? = null

    fun setBitmap(b: Bitmap?) {
        bmp = b
        invalidate()
    }

    /** Copies 8 normalised values (TL, TR, BR, BL). */
    fun setQuad(n: FloatArray) {
        for (i in 0 until 8) q[i] = n[i].coerceIn(0f, 1f)
        invalidate()
    }

    fun getQuad(): FloatArray = q.copyOf()

    fun isValid(): Boolean {
        val b = bmp ?: return false
        return ScanImage.isValidQuad(q, b.width.toFloat() / b.height)
    }

    private fun layoutDst(): Boolean {
        val b = bmp ?: return false
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        if (aw <= 0 || ah <= 0) return false
        val s = min(aw / b.width, ah / b.height)
        val w = b.width * s
        val h = b.height * s
        val l = (width - w) / 2f
        val t = (height - h) / 2f
        dst.set(l, t, l + w, t + h)
        return true
    }

    private fun px(i: Int) = dst.left + q[2 * i] * dst.width()
    private fun py(i: Int) = dst.top + q[2 * i + 1] * dst.height()
    private fun mx(i: Int) = (px(i) + px((i + 1) % 4)) / 2f
    private fun my(i: Int) = (py(i) + py((i + 1) % 4)) / 2f

    private fun hx(h: Int) = if (h < 4) px(h) else mx(h - 4)
    private fun hy(h: Int) = if (h < 4) py(h) else my(h - 4)

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        if (!layoutDst()) return
        c.drawBitmap(b, null, dst, imgPaint)

        quadPath(path)
        val ok = isValid()
        val col = if (ok) accent else warn

        // dim everything outside the outline
        clip.reset()
        clip.fillType = Path.FillType.EVEN_ODD
        clip.addRect(dst, Path.Direction.CW)
        clip.addPath(path)
        c.drawPath(clip, dim)

        fill.color = (col and 0x00FFFFFF) or 0x22000000
        c.drawPath(path, fill)
        line.color = col
        c.drawPath(path, line)

        ring.color = col
        val rc = 11f * d
        val rm = 7f * d
        for (i in 0 until 4) {
            c.drawCircle(mx(i), my(i), rm, dot)
            c.drawCircle(mx(i), my(i), rm, ring)
        }
        for (i in 0 until 4) {
            c.drawCircle(px(i), py(i), rc, dot)
            c.drawCircle(px(i), py(i), rc, ring)
        }

        if (active >= 0) drawLoupe(c, b, hx(active), hy(active), col)
    }

    private fun quadPath(p: Path) {
        p.reset()
        p.moveTo(px(0), py(0))
        for (i in 1 until 4) p.lineTo(px(i), py(i))
        p.close()
    }

    private fun drawLoupe(c: Canvas, b: Bitmap, x: Float, y: Float, col: Int) {
        val r = 56f * d
        val cx = if (x < width / 2f) width - r - 14f * d else r + 14f * d
        val cy = r + 14f * d
        val zoom = 2.6f
        val s = zoom * dst.width() / b.width
        val bx = (x - dst.left) / dst.width() * b.width
        val by = (y - dst.top) / dst.height() * b.height
        mat.reset()
        mat.postScale(s, s)
        mat.postTranslate(cx - bx * s, cy - by * s)
        clip.reset()
        clip.addCircle(cx, cy, r, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        c.drawColor(Color.BLACK)
        c.drawBitmap(b, mat, imgPaint)
        c.drawLine(cx - r, cy, cx + r, cy, cross)
        c.drawLine(cx, cy - r, cx, cy + r, cross)
        c.restore()
        ring.color = col
        c.drawCircle(cx, cy, r, ring)
    }

    private fun hit(x: Float, y: Float): Int {
        val reach = 34f * d
        var best = -1
        var bestD = reach
        for (h in 0 until 8) {
            // corners win over side dots when both are in reach
            val dd = hypot(x - hx(h), y - hy(h)) - (if (h < 4) 8f * d else 0f)
            if (dd < bestD) {
                bestD = dd
                best = h
            }
        }
        return best
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bmp == null || !layoutDst()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = hit(e.x, e.y)
                if (active < 0) return false
                grabX = hx(active) - e.x
                grabY = hy(active) - e.y
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (active < 0) return false
                val tx = e.x + grabX
                val ty = e.y + grabY
                move(tx, ty)
                invalidate()
                onChanged?.invoke()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active >= 0) {
                    active = -1
                    invalidate()
                    onChanged?.invoke()
                    return true
                }
            }
        }
        return super.onTouchEvent(e)
    }

    private fun move(tx: Float, ty: Float) {
        val nx = (tx - dst.left) / dst.width()
        val ny = (ty - dst.top) / dst.height()
        if (active < 4) {
            q[2 * active] = nx.coerceIn(0f, 1f)
            q[2 * active + 1] = ny.coerceIn(0f, 1f)
        } else {
            val a = active - 4
            val b = (a + 1) % 4
            val curX = (q[2 * a] + q[2 * b]) / 2f
            val curY = (q[2 * a + 1] + q[2 * b + 1]) / 2f
            var dx = nx - curX
            var dy = ny - curY
            dx = dx.coerceIn(max(-q[2 * a], -q[2 * b]), min(1f - q[2 * a], 1f - q[2 * b]))
            dy = dy.coerceIn(max(-q[2 * a + 1], -q[2 * b + 1]), min(1f - q[2 * a + 1], 1f - q[2 * b + 1]))
            q[2 * a] += dx
            q[2 * b] += dx
            q[2 * a + 1] += dy
            q[2 * b + 1] += dy
        }
    }
}
