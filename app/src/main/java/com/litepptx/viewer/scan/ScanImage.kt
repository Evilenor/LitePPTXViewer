package com.litepptx.viewer.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import com.litepptx.viewer.pdf.PdfBuilder
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Bitmap plumbing for the scanner: decode, find the page, straighten it, save it. */
object ScanImage {

    /** Long edge of a finished page (matches the "Smart" PDF preset). */
    const val PAGE_LONG = 2600
    /** Working copy shown in the crop screen. */
    const val PREVIEW_LONG = 1400
    /** Straightened copy used for the live filter preview. */
    const val FILTER_LONG = 1100

    class Detected(val quad: FloatArray, val found: Boolean)

    /**
     * Decodes [path] with its EXIF rotation applied and the long edge at most [maxLong] px.
     * Never returns more pixels than needed; retries with a smaller sample if memory is short.
     */
    fun decode(path: String, maxLong: Int): Bitmap? {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bo)
        val sw = bo.outWidth
        val sh = bo.outHeight
        if (sw <= 0 || sh <= 0) return null
        val orient = try {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        } catch (_: Throwable) {
            1
        }
        var sample = 1
        while (max(sw, sh) / (sample * 2) >= maxLong) sample *= 2
        var bmp: Bitmap? = null
        while (bmp == null) {
            try {
                val o = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                bmp = BitmapFactory.decodeFile(path, o) ?: return null
            } catch (e: OutOfMemoryError) {
                if (sample >= 64) throw e
                sample *= 2
            }
        }
        val m = PdfBuilder.matrixFor(orient, 0)
        val longEdge = max(bmp.width, bmp.height)
        if (longEdge > maxLong) {
            val s = maxLong.toFloat() / longEdge
            m.postScale(s, s)
        }
        if (m.isIdentity) return bmp
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /** Finds the page in [preview]. Corners are TL, TR, BR, BL, normalised to 0..1. */
    fun detect(preview: Bitmap): Detected {
        val maxSide = 520
        val s = min(1f, maxSide.toFloat() / max(preview.width, preview.height))
        val w = max(1, (preview.width * s).roundToInt())
        val h = max(1, (preview.height * s).roundToInt())
        val small = if (s < 1f) Bitmap.createScaledBitmap(preview, w, h, true) else preview
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== preview) small.recycle()
        for (i in px.indices) {
            val p = px[i]
            px[i] = ((((p shr 16) and 255) * 77) + (((p shr 8) and 255) * 151) + ((p and 255) * 28)) shr 8
        }
        val found = try {
            DocDetector.detect(px, w, h)
        } catch (_: Throwable) {
            null
        }
        val q = found ?: DocDetector.defaultQuad(w, h)
        for (i in 0 until 4) {
            q[2 * i] = (q[2 * i] / w).coerceIn(0f, 1f)
            q[2 * i + 1] = (q[2 * i + 1] / h).coerceIn(0f, 1f)
        }
        if (found != null) {
            // Pull the outline a hair inside the page so no strip of table is left at the edges.
            val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
            val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
            for (i in 0 until 4) {
                q[2 * i] += (cx - q[2 * i]) * 0.008f
                q[2 * i + 1] += (cy - q[2 * i + 1]) * 0.008f
            }
        }
        return Detected(q, found != null)
    }

    /**
     * Cuts the quadrilateral [qn] (normalised, TL TR BR BL) out of [src] and stretches it to a flat
     * rectangle, then turns it by [rotate] degrees clockwise. The long edge is at most [maxLong].
     */
    fun warp(src: Bitmap, qn: FloatArray, maxLong: Int, rotate: Int): Bitmap {
        val sx = src.width.toFloat()
        val sy = src.height.toFloat()
        val p = FloatArray(8) { if (it % 2 == 0) qn[it] * sx else qn[it] * sy }
        fun dist(a: Int, b: Int) = hypot(p[2 * a] - p[2 * b], p[2 * a + 1] - p[2 * b + 1])
        var ow = max(max(dist(0, 1), dist(3, 2)), 8f)
        var oh = max(max(dist(0, 3), dist(1, 2)), 8f)
        val k = min(1f, maxLong / max(ow, oh))
        ow *= k
        oh *= k
        val w = max(1, ow.roundToInt())
        val h = max(1, oh.roundToInt())
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(p, 0, dst, 0, 4)) {
            m.setRectToRect(RectF(0f, 0f, sx, sy), RectF(0f, 0f, w.toFloat(), h.toFloat()), Matrix.ScaleToFit.FILL)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return if (rotate % 360 == 0) out else rotated(out, rotate)
    }

    fun rotated(b: Bitmap, deg: Int): Bitmap {
        val m = Matrix()
        m.postRotate(deg.toFloat())
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (r !== b) b.recycle()
        return r
    }

    /** True for a sensible, non-crossing quadrilateral with no tiny side. [qn] is normalised, [aspect] is w/h. */
    fun isValidQuad(qn: FloatArray, aspect: Float): Boolean {
        val x = FloatArray(4) { qn[2 * it] * aspect }
        val y = FloatArray(4) { qn[2 * it + 1] }
        var sign = 0
        var area = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val k = (i + 2) % 4
            val ax = x[j] - x[i]
            val ay = y[j] - y[i]
            val bx = x[k] - x[j]
            val by = y[k] - y[j]
            if (hypot(ax, ay) < 0.06f) return false
            val cr = ax * by - ay * bx
            val sg = if (cr > 0f) 1 else -1
            if (sign == 0) sign = sg else if (sg != sign) return false
            area += x[i] * y[j] - x[j] * y[i]
        }
        return kotlin.math.abs(area) / 2f > 0.01f * aspect
    }

    fun ensureMutable(b: Bitmap): Bitmap {
        if (b.isMutable) return b
        val c = b.copy(Bitmap.Config.ARGB_8888, true)
        b.recycle()
        return c
    }

    fun saveJpeg(b: Bitmap, f: File, quality: Int) {
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }
}
