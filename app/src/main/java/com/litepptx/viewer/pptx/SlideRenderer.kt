package com.litepptx.viewer.pptx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.LruCache

/** Thread-safe: no mutable state except the media cache. */
class SlideRenderer(private val deck: Deck) {

    private val media = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun render(slide: Slide, widthPx: Int): Bitmap {
        val ratio = deck.hEmu.toFloat() / deck.wEmu.toFloat()
        val heightPx = (widthPx * ratio).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val k = widthPx / deck.wEmu.toFloat()        // EMU -> px
        val ptPx = k * 12700f                         // pt -> px
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val full = RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())

        if (slide.bg != null) {
            paint.style = Paint.Style.FILL
            applyFill(paint, slide.bg, full)
            canvas.drawRect(full, paint)
            paint.shader = null
        } else {
            canvas.drawColor(Color.WHITE)
        }
        slide.bgImage?.let { p ->
            decode(p, widthPx, heightPx)?.let { canvas.drawBitmap(it, null, full, paint) }
        }

        for (el in slide.elements) {
            val r = RectF(el.x * k, el.y * k, (el.x + el.w) * k, (el.y + el.h) * k)
            canvas.save()
            if (el.rot != 0f) canvas.rotate(el.rot, r.centerX(), r.centerY())
            try {
                when (el) {
                    is Shape -> drawShape(canvas, el, r, k, ptPx, paint)
                    is Pic -> drawPic(canvas, el, r, paint)
                    is Tbl -> drawTable(canvas, el, r, k, ptPx, paint)
                }
            } catch (_: Exception) {
                // never let one odd element break the whole slide
            }
            canvas.restore()
        }
        return bmp
    }

    // ------------------------------------------------------------------ fills

    private fun applyFill(paint: Paint, f: Fill, r: RectF) {
        val g = f.grad
        if (g != null && f.pos != null) {
            val a = Math.toRadians(f.angle.toDouble())
            val dx = Math.cos(a).toFloat()
            val dy = Math.sin(a).toFloat()
            val half = (Math.abs(r.width() * dx) + Math.abs(r.height() * dy)) / 2f
            val cx = r.centerX()
            val cy = r.centerY()
            paint.shader = LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, g, f.pos, Shader.TileMode.CLAMP)
        } else {
            paint.shader = null
            paint.color = f.color
        }
    }

    // ------------------------------------------------------------------ shapes

    private fun drawShape(c: Canvas, s: Shape, r: RectF, k: Float, ptPx: Float, paint: Paint) {
        val fill = s.fill
        val stroke = s.stroke
        if (fill != null || stroke != null) {
            c.save()
            if (s.flipH || s.flipV) c.scale(if (s.flipH) -1f else 1f, if (s.flipV) -1f else 1f, r.centerX(), r.centerY())
            if (fill != null && s.geom != "line" && s.geom != "straightConnector1") {
                paint.style = Paint.Style.FILL
                applyFill(paint, fill, r)
                drawGeom(c, s, r, paint)
                paint.shader = null
            }
            if (stroke != null) {
                paint.style = Paint.Style.STROKE
                paint.color = stroke
                paint.strokeWidth = (s.strokeW * k).coerceAtLeast(1f)
                drawGeom(c, s, r, paint)
            }
            c.restore()
        }
        if (s.paras.isNotEmpty()) drawText(c, s, r, ptPx)
    }

    private fun drawGeom(c: Canvas, s: Shape, r: RectF, paint: Paint) {
        when (s.geom) {
            "ellipse" -> c.drawOval(r, paint)
            "roundRect" -> {
                val adj = if (s.adj >= 0f) s.adj / 100000f else 0.16667f
                val rad = minOf(r.width(), r.height()) * adj.coerceIn(0f, 0.5f)
                c.drawRoundRect(r, rad, rad, paint)
            }
            "line", "straightConnector1" -> c.drawLine(r.left, r.top, r.right, r.bottom, paint)
            "triangle" -> {
                val adj = if (s.adj >= 0f) s.adj / 100000f else 0.5f
                val p = Path()
                p.moveTo(r.left + r.width() * adj, r.top)
                p.lineTo(r.right, r.bottom)
                p.lineTo(r.left, r.bottom)
                p.close()
                c.drawPath(p, paint)
            }
            "rtTriangle" -> {
                val p = Path()
                p.moveTo(r.left, r.top); p.lineTo(r.right, r.bottom); p.lineTo(r.left, r.bottom); p.close()
                c.drawPath(p, paint)
            }
            "diamond" -> {
                val p = Path()
                p.moveTo(r.centerX(), r.top); p.lineTo(r.right, r.centerY())
                p.lineTo(r.centerX(), r.bottom); p.lineTo(r.left, r.centerY()); p.close()
                c.drawPath(p, paint)
            }
            "cust" -> {
                val cp = s.path ?: return
                val p = Path()
                val sx = r.width() / cp.w
                val sy = r.height() / cp.h
                for (o in cp.ops) {
                    when (o[0].toInt()) {
                        0 -> p.moveTo(r.left + o[1] * sx, r.top + o[2] * sy)
                        1 -> p.lineTo(r.left + o[1] * sx, r.top + o[2] * sy)
                        2 -> p.cubicTo(r.left + o[1] * sx, r.top + o[2] * sy, r.left + o[3] * sx, r.top + o[4] * sy, r.left + o[5] * sx, r.top + o[6] * sy)
                        3 -> p.quadTo(r.left + o[1] * sx, r.top + o[2] * sy, r.left + o[3] * sx, r.top + o[4] * sy)
                        4 -> p.close()
                    }
                }
                c.drawPath(p, paint)
            }
            else -> c.drawRect(r, paint)
        }
    }

    // ------------------------------------------------------------------ pictures

    private fun drawPic(c: Canvas, p: Pic, r: RectF, paint: Paint) {
        val bmp = decode(p.path, r.width().toInt(), r.height().toInt()) ?: return
        val src = Rect(
            (bmp.width * p.cropL).toInt(), (bmp.height * p.cropT).toInt(),
            (bmp.width * (1f - p.cropR)).toInt(), (bmp.height * (1f - p.cropB)).toInt()
        )
        if (src.width() <= 0 || src.height() <= 0) return
        c.save()
        if (p.flipH || p.flipV) c.scale(if (p.flipH) -1f else 1f, if (p.flipV) -1f else 1f, r.centerX(), r.centerY())
        paint.style = Paint.Style.FILL
        paint.shader = null
        c.drawBitmap(bmp, src, r, paint)
        c.restore()
    }

    private fun decode(path: String, reqW: Int, reqH: Int): Bitmap? {
        val key = "$path#$reqW"
        media.get(key)?.let { return it }
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val st = deck.entry(path) ?: return null
            st.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var s = 1
            while (o.outWidth / (s * 2) >= reqW && o.outHeight / (s * 2) >= reqH) s *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = s }
            val b = deck.entry(path)?.use { BitmapFactory.decodeStream(it, null, o2) }
            if (b != null && b.byteCount < 4 * 1024 * 1024) media.put(key, b)
            b
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ tables

    private fun drawTable(c: Canvas, t: Tbl, r: RectF, k: Float, ptPx: Float, paint: Paint) {
        var y = r.top
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#33000000")
            strokeWidth = 1f
        }
        for (row in t.rows) {
            val rh = row.h * k
            var x = r.left
            var col = 0
            for (cell in row.cells) {
                var cw = 0f
                for (i in 0 until cell.span) cw += (t.colW.getOrElse(col + i) { 0f }) * k
                col += cell.span
                val cr = RectF(x, y, x + cw, y + rh)
                x += cw
                if (cell.merged) continue
                if (cell.fill != null) {
                    paint.style = Paint.Style.FILL
                    paint.shader = null
                    paint.color = cell.fill
                    c.drawRect(cr, paint)
                }
                c.drawRect(cr, line)
                if (cell.paras.isNotEmpty()) {
                    val sh = Shape()
                    sh.paras = cell.paras
                    sh.lIns = 91440f; sh.rIns = 91440f; sh.tIns = 45720f; sh.bIns = 45720f
                    sh.anchor = "t"
                    drawText(c, sh, cr, ptPx)
                }
            }
            y += rh
        }
    }

    // ------------------------------------------------------------------ text

    private fun drawText(c: Canvas, s: Shape, r: RectF, ptPx: Float) {
        val k = ptPx / 12700f
        val lIns = s.lIns * k
        val rIns = s.rIns * k
        val availW = (r.width() - lIns - rIns).toInt().coerceAtLeast(1)
        val layoutW = if (s.wrap) availW else maxOf(availW, (r.width() * 3f).toInt())

        class Item(val layout: StaticLayout, val before: Float, val after: Float, val align: Layout.Alignment)

        val items = ArrayList<Item>(s.paras.size)
        var autoNum = 0
        var total = 0f
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)

        for (p in s.paras) {
            val sb = SpannableStringBuilder()
            var firstSize = p.emptySizePt
            if (p.runs.isEmpty()) {
                sb.append(" ")
                sb.setSpan(AbsoluteSizeSpan(px(p.emptySizePt, ptPx, s.fontScale), false), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                firstSize = p.runs[0].sizePt
                var bulletText: String? = null
                if (p.bullet != null) {
                    if (p.bullet == "#") { autoNum++; bulletText = "$autoNum. " } else { autoNum = 0; bulletText = p.bullet + " " }
                } else {
                    autoNum = 0
                }
                if (bulletText != null) {
                    sb.append(bulletText)
                    sb.setSpan(AbsoluteSizeSpan(px(firstSize, ptPx, s.fontScale), false), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(p.runs[0].color), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                for (run in p.runs) {
                    val a = sb.length
                    sb.append(run.text)
                    val b = sb.length
                    sb.setSpan(AbsoluteSizeSpan(px(run.sizePt, ptPx, s.fontScale), false), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(run.color), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val st = when {
                        run.bold && run.italic -> Typeface.BOLD_ITALIC
                        run.bold -> Typeface.BOLD
                        run.italic -> Typeface.ITALIC
                        else -> Typeface.NORMAL
                    }
                    if (st != Typeface.NORMAL) sb.setSpan(StyleSpan(st), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (run.underline) sb.setSpan(UnderlineSpan(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            if (p.marL != 0f || p.indent != 0f) {
                val rest = (p.marL * k).toInt().coerceAtLeast(0)
                val first = ((p.marL + p.indent) * k).toInt().coerceAtLeast(0)
                sb.setSpan(LeadingMarginSpan.Standard(first, rest), 0, sb.length, Spanned.SPAN_PARAGRAPH or Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            }

            val align = when (p.align) {
                "ctr" -> Layout.Alignment.ALIGN_CENTER
                "r" -> if (p.rtl) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
                else -> if (p.rtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
            }
            val layout = if (Build.VERSION.SDK_INT >= 23) {
                StaticLayout.Builder.obtain(sb, 0, sb.length, tp, layoutW)
                    .setAlignment(align)
                    .setIncludePad(false)
                    .setLineSpacing(0f, p.lnSpcPct)
                    .setTextDirection(if (p.rtl) android.text.TextDirectionHeuristics.RTL else android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(sb, tp, layoutW, align, p.lnSpcPct, 0f, false)
            }
            val before = if (items.isEmpty()) 0f else p.spcBefPt * ptPx
            val after = p.spcAftPt * ptPx
            total += before + layout.height + after
            items.add(Item(layout, before, after, align))
        }

        val tIns = s.tIns * k
        val bIns = s.bIns * k
        var y = when (s.anchor) {
            "ctr" -> r.top + (r.height() - total) / 2f
            "b" -> r.bottom - bIns - total
            else -> r.top + tIns
        }
        for (it in items) {
            y += it.before
            var x = r.left + lIns
            if (!s.wrap) {
                x = when (it.align) {
                    Layout.Alignment.ALIGN_CENTER -> r.centerX() - layoutW / 2f
                    Layout.Alignment.ALIGN_OPPOSITE -> r.right - rIns - layoutW
                    else -> x
                }
            }
            c.save()
            c.translate(x, y)
            it.layout.draw(c)
            c.restore()
            y += it.layout.height + it.after
        }
    }

    private fun px(pt: Float, ptPx: Float, scale: Float): Int = (pt * ptPx * scale).toInt().coerceAtLeast(4)
}
