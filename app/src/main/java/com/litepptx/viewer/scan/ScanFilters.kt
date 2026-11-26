package com.litepptx.viewer.scan

import android.graphics.Bitmap
import java.util.Arrays
import kotlin.math.max
import kotlin.math.min

/**
 * Scan clean-up filters, pure integer/float maths so they are tiny and fast.
 *
 * All of them start with the same step, a flat-field correction: the paper colour is estimated
 * on a coarse grid (the bright end of every cell, grown past big dark blocks and smoothed) and every
 * pixel is divided by it. That removes shadows, uneven light and colour casts in one go, then a
 * levels curve turns the paper white and keeps the ink.
 */
object ScanFilters {

    const val ORIGINAL = 0
    const val ENHANCED = 1
    const val BW = 2
    const val GRAY = 3

    /** Returns [src] untouched for ORIGINAL, otherwise a filtered copy. Safe for previews. */
    fun copyWith(src: Bitmap, mode: Int): Bitmap {
        if (mode == ORIGINAL) return src
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        process(px, w, h, mode)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Filters a mutable bitmap in place (lowest memory use, for the final full-size page). */
    fun applyInPlace(bmp: Bitmap, mode: Int) {
        if (mode == ORIGINAL) return
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        process(px, w, h, mode)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** ARGB ints in, ARGB ints out (alpha forced to 255). */
    fun process(px: IntArray, w: Int, h: Int, mode: Int) {
        if (mode == ORIGINAL || w < 2 || h < 2) return

        val cell = max(6, max(w, h) / 100)
        val gw = (w + cell - 1) / cell
        val gh = (h + cell - 1) / cell
        val n = gw * gh
        var gr = FloatArray(n)
        var gg = FloatArray(n)
        var gb = FloatArray(n)
        val hist = IntArray(64)

        // ---- 1. paper colour per cell: average of the brightest 20% of its pixels ----
        for (cy in 0 until gh) {
            val y0 = cy * cell
            val y1 = min(h, y0 + cell)
            for (cx in 0 until gw) {
                val x0 = cx * cell
                val x1 = min(w, x0 + cell)
                Arrays.fill(hist, 0)
                for (y in y0 until y1) {
                    var i = y * w + x0
                    for (x in x0 until x1) {
                        hist[lum(px[i++]) shr 2]++
                    }
                }
                val want = max(1, (x1 - x0) * (y1 - y0) / 5)
                var acc = 0
                var bin = 63
                while (bin > 0) {
                    acc += hist[bin]
                    if (acc >= want) break
                    bin--
                }
                var sr = 0L
                var sg = 0L
                var sb = 0L
                var cnt = 0
                for (y in y0 until y1) {
                    var i = y * w + x0
                    for (x in x0 until x1) {
                        val p = px[i++]
                        if ((lum(p) shr 2) >= bin) {
                            sr += (p shr 16) and 255
                            sg += (p shr 8) and 255
                            sb += p and 255
                            cnt++
                        }
                    }
                }
                val k = cy * gw + cx
                val c = max(1, cnt).toFloat()
                gr[k] = sr / c
                gg[k] = sg / c
                gb[k] = sb / c
            }
        }

        // ---- 2. grow the paper colour over big dark blocks (photos, headings) ----
        val gl = FloatArray(n) { 0.299f * gr[it] + 0.587f * gg[it] + 0.114f * gb[it] }
        val mr = FloatArray(n)
        val mg = FloatArray(n)
        val mb = FloatArray(n)
        for (cy in 0 until gh) for (cx in 0 until gw) {
            var bestK = cy * gw + cx
            var bestL = gl[bestK]
            for (dy in -2..2) {
                val yy = cy + dy
                if (yy < 0 || yy >= gh) continue
                for (dx in -2..2) {
                    val xx = cx + dx
                    if (xx < 0 || xx >= gw) continue
                    val k = yy * gw + xx
                    if (gl[k] > bestL) {
                        bestL = gl[k]
                        bestK = k
                    }
                }
            }
            val k = cy * gw + cx
            mr[k] = gr[bestK]
            mg[k] = gg[bestK]
            mb[k] = gb[bestK]
        }
        gr = blur(mr, gw, gh)
        gg = blur(mg, gw, gh)
        gb = blur(mb, gw, gh)

        // ---- 3. never lift a region more than ~1.8x (keeps real dark pictures dark) ----
        val ls = FloatArray(n) { 0.299f * gr[it] + 0.587f * gg[it] + 0.114f * gb[it] }
        val sorted = ls.copyOf()
        Arrays.sort(sorted)
        val paper = sorted[(n * 3) / 4]
        val floorL = 0.55f * paper
        for (k in 0 until n) {
            val l = ls[k]
            if (l < floorL && l > 0.5f) {
                val s = floorL / l
                gr[k] *= s
                gg[k] *= s
                gb[k] *= s
            }
            gr[k] = max(gr[k], 1f)
            gg[k] = max(gg[k], 1f)
            gb[k] = max(gb[k], 1f)
        }

        // ---- 4. divide every pixel by its (bilinearly interpolated) paper colour ----
        val lo = if (mode == BW) 0.50f else 0.16f
        val hi = if (mode == BW) 0.74f else 0.94f
        val inv = 1f / (hi - lo)
        val x0s = IntArray(w)
        val fxs = FloatArray(w)
        for (x in 0 until w) {
            val g = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, (gw - 1).toFloat())
            val xi = min(g.toInt(), max(gw - 2, 0))
            x0s[x] = xi
            fxs[x] = g - xi
        }
        val xStep = if (gw > 1) 1 else 0
        val yStep = if (gh > 1) gw else 0
        for (y in 0 until h) {
            val g = ((y + 0.5f) / cell - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val yi = min(g.toInt(), max(gh - 2, 0))
            val fy = g - yi
            val rowA = yi * gw
            var i = y * w
            for (x in 0 until w) {
                val a = rowA + x0s[x]
                val b = a + xStep
                val c = a + yStep
                val d = c + xStep
                val fx = fxs[x]
                val bgR = mix(mix(gr[a], gr[b], fx), mix(gr[c], gr[d], fx), fy)
                val bgG = mix(mix(gg[a], gg[b], fx), mix(gg[c], gg[d], fx), fy)
                val bgB = mix(mix(gb[a], gb[b], fx), mix(gb[c], gb[d], fx), fy)
                val p = px[i]
                val nr = min(1f, ((p shr 16) and 255) / bgR)
                val ng = min(1f, ((p shr 8) and 255) / bgG)
                val nb = min(1f, (p and 255) / bgB)
                val out: Int
                if (mode == ENHANCED) {
                    out = (255 shl 24) or (level(nr, lo, inv) shl 16) or (level(ng, lo, inv) shl 8) or level(nb, lo, inv)
                } else {
                    val l = 0.299f * nr + 0.587f * ng + 0.114f * nb
                    val v = if (mode == BW) {
                        val t = ((l - lo) * inv).coerceIn(0f, 1f)
                        (t * t * (3f - 2f * t) * 255f + 0.5f).toInt()
                    } else {
                        level(l, lo, inv)
                    }
                    out = (255 shl 24) or (v shl 16) or (v shl 8) or v
                }
                px[i++] = out
            }
        }
    }

    private fun lum(p: Int): Int =
        ((((p shr 16) and 255) * 77) + (((p shr 8) and 255) * 151) + ((p and 255) * 28)) shr 8

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun level(v: Float, lo: Float, inv: Float): Int =
        (((v - lo) * inv).coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /** Two passes of a 3x3 box blur on a small grid. */
    private fun blur(src: FloatArray, w: Int, h: Int): FloatArray {
        var a = src
        repeat(2) {
            val o = FloatArray(a.size)
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f
                var c = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        s += a[yy * w + xx]
                        c++
                    }
                }
                o[y * w + x] = s / c
            }
            a = o
        }
        return a
    }
}
