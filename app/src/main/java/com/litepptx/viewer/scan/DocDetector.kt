package com.litepptx.viewer.scan

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Finds the outline of a sheet of paper in a photo, in pure Kotlin on a small grayscale copy
 * (no OpenCV, no ML model):
 *
 *   blur -> Sobel edges -> Hough lines (each edge pixel only votes for lines roughly
 *   perpendicular to its gradient) -> two families of near-perpendicular lines ->
 *   the quadrilateral whose four sides sit on the most real edge.
 *
 * It is a good first guess, not magic: the user can always drag the corners afterwards.
 */
object DocDetector {

    private const val SPREAD = 7        // degrees a pixel may deviate from its gradient direction
    private const val FAMILY_TOL = 38   // degrees a side may deviate from the dominant orientation
    private const val MAX_PEAKS = 7     // strongest lines kept per family
    private const val NONE: Byte = -1

    private class Line(val t: Int, val rho: Float, val votes: Int) {
        val c = cos(Math.toRadians(t.toDouble())).toFloat()
        val s = sin(Math.toRadians(t.toDouble())).toFloat()
    }

    private class Cand(val p: FloatArray, val score: Float, val area: Float)

    /** A neutral first guess: the whole frame with a small margin. TL, TR, BR, BL. */
    fun defaultQuad(w: Int, h: Int): FloatArray {
        val mx = w * 0.05f
        val my = h * 0.05f
        return floatArrayOf(mx, my, w - mx, my, w - mx, h - my, mx, h - my)
    }

    /**
     * @param gray w*h luminance values 0..255 (a copy of about 500 px on the long side is plenty).
     * @return TL, TR, BR, BL as x0,y0,...,x3,y3 in pixels of that copy, or null if no page was found.
     */
    fun detect(gray: IntArray, w: Int, h: Int): FloatArray? {
        if (w < 48 || h < 48 || gray.size < w * h) return null

        val b = boxBlur(gray, w, h, 2)

        // ---- Sobel edges with quantised gradient direction (degrees, mod 180) ----
        val gxs = IntArray(w * h)
        val gys = IntArray(w * h)
        val hist = IntArray(256)
        for (y in 1 until h - 1) {
            val r0 = (y - 1) * w
            val r1 = y * w
            val r2 = (y + 1) * w
            for (x in 1 until w - 1) {
                val gx = (b[r0 + x + 1] + 2 * b[r1 + x + 1] + b[r2 + x + 1]) -
                    (b[r0 + x - 1] + 2 * b[r1 + x - 1] + b[r2 + x - 1])
                val gy = (b[r2 + x - 1] + 2 * b[r2 + x] + b[r2 + x + 1]) -
                    (b[r0 + x - 1] + 2 * b[r0 + x] + b[r0 + x + 1])
                gxs[r1 + x] = gx
                gys[r1 + x] = gy
                hist[min(255, (abs(gx) + abs(gy)) shr 3)]++
            }
        }
        val total = (w - 2) * (h - 2)
        var cum = 0
        var thr = 90
        for (i in 0 until 256) {
            cum += hist[i]
            if (cum >= total * 0.92) {
                thr = max(90, (i + 1) * 8)
                break
            }
        }

        val dirQ = ByteArray(w * h) { NONE }
        val diag = ceil(hypot(w.toDouble(), h.toDouble())).toInt()
        val nr = 2 * diag + 1
        val acc = IntArray(180 * nr)
        val cs = FloatArray(180) { cos(Math.toRadians(it.toDouble())).toFloat() }
        val sn = FloatArray(180) { sin(Math.toRadians(it.toDouble())).toFloat() }
        var edges = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = gxs[i]
                val gy = gys[i]
                if (abs(gx) + abs(gy) <= thr) continue
                val a = Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())).roundToInt()
                val tc = ((a % 180) + 180) % 180
                dirQ[i] = tc.toByte()
                edges++
                for (d in -SPREAD..SPREAD) {
                    val t = (tc + d + 180) % 180
                    val rho = x * cs[t] + y * sn[t]
                    acc[t * nr + rho.roundToInt() + diag]++
                }
            }
        }
        if (edges < 60) return null

        // light smoothing across rho
        val accS = IntArray(acc.size)
        for (t in 0 until 180) {
            val o = t * nr
            for (r in 1 until nr - 1) accS[o + r] = (acc[o + r - 1] + 2 * acc[o + r] + acc[o + r + 1]) shr 2
        }

        // ---- dominant orientation (a page has two perpendicular families) ----
        val e = DoubleArray(180)
        for (t in 0 until 180) {
            var s = 0.0
            val o = t * nr
            for (r in 0 until nr) {
                val v = accS[o + r].toDouble()
                s += v * v
            }
            e[t] = s
        }
        var bestE = -1.0
        var t0 = 0
        for (t in 0 until 90) {
            var s = 0.0
            for (d in -6..6) {
                val k = (t + d + 90) % 90
                s += e[k] + e[k + 90]
            }
            if (s > bestE) {
                bestE = s
                t0 = t
            }
        }

        val minVotes = max(20, (0.2f * min(w, h)).toInt())
        val fam1 = peaks(accS, nr, diag, t0, minVotes)
        val fam2 = peaks(accS, nr, diag, (t0 + 90) % 180, minVotes)
        if (fam1.size < 2 || fam2.size < 2) return null

        // ---- best quadrilateral ----
        val margin = 0.10f * max(w, h)
        val minArea = 0.12f * w * h
        var best: Cand? = null
        val cands = ArrayList<Cand>()
        for (i in 0 until fam1.size - 1) for (j in i + 1 until fam1.size) {
            for (k in 0 until fam2.size - 1) for (l in k + 1 until fam2.size) {
                val a1 = fam1[i]
                val a2 = fam1[j]
                val b1 = fam2[k]
                val b2 = fam2[l]
                val p11 = cross(a1, b1) ?: continue
                val p12 = cross(a1, b2) ?: continue
                val p22 = cross(a2, b2) ?: continue
                val p21 = cross(a2, b1) ?: continue
                val p = floatArrayOf(p11[0], p11[1], p12[0], p12[1], p22[0], p22[1], p21[0], p21[1])
                if (!plausible(p, w, h, margin, minArea)) continue
                val s1 = side(dirQ, w, h, p[0], p[1], p[2], p[3], a1.t)
                val s2 = side(dirQ, w, h, p[2], p[3], p[4], p[5], b2.t)
                val s3 = side(dirQ, w, h, p[4], p[5], p[6], p[7], a2.t)
                val s4 = side(dirQ, w, h, p[6], p[7], p[0], p[1], b1.t)
                val mn = min(min(s1, s2), min(s3, s4))
                val mean = (s1 + s2 + s3 + s4) / 4f
                if (mean < 0.5f || mn < 0.25f) continue
                val area = polyArea(p)
                val score = (0.5f * mn + 0.5f * mean) * Math.pow((area / (w * h)).toDouble(), 0.35).toFloat()
                val c = Cand(p, score, area)
                cands.add(c)
                if (best == null || score > best.score) best = c
            }
        }
        val top = best ?: return null
        // Prefer the outermost page when a smaller shape inside it scores about as well.
        var pick = top
        for (c in cands) if (c.score >= 0.88f * top.score && c.area > pick.area) pick = c

        return orderAndClamp(pick.p, w, h)
    }

    // ------------------------------------------------------------------ helpers

    private fun peaks(accS: IntArray, nr: Int, diag: Int, center: Int, minVotes: Int): List<Line> {
        val found = ArrayList<Long>()
        for (t in 0 until 180) {
            var dd = abs(t - center)
            if (dd > 90) dd = 180 - dd
            if (dd > FAMILY_TOL) continue
            val o = t * nr
            for (r in 1 until nr - 1) {
                val v = accS[o + r]
                if (v >= minVotes && v >= accS[o + r - 1] && v >= accS[o + r + 1]) {
                    found.add((v.toLong() shl 32) or (o + r).toLong())
                }
            }
        }
        found.sortDescending()
        val nmsR = max(8f, diag * 0.025f)
        val out = ArrayList<Line>()
        for (f in found) {
            val idx = (f and 0xFFFFFFFFL).toInt()
            val t = idx / nr
            val rho = (idx % nr - diag).toFloat()
            var dup = false
            for (p in out) {
                var dt = abs(t - p.t)
                var r2 = p.rho
                if (dt > 90) {
                    dt = 180 - dt
                    r2 = -r2
                }
                if (dt <= 12 && abs(rho - r2) <= nmsR) {
                    dup = true
                    break
                }
            }
            if (dup) continue
            out.add(Line(t, rho, (f shr 32).toInt()))
            if (out.size >= MAX_PEAKS) break
        }
        return out
    }

    private fun cross(a: Line, b: Line): FloatArray? {
        val det = a.c * b.s - a.s * b.c
        if (abs(det) < 0.3f) return null
        val rho1 = a.rho
        val rho2 = b.rho
        return floatArrayOf((rho1 * b.s - rho2 * a.s) / det, (a.c * rho2 - b.c * rho1) / det)
    }

    private fun polyArea(p: FloatArray): Float {
        var s = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]
        }
        return abs(s) / 2f
    }

    private fun plausible(p: FloatArray, w: Int, h: Int, margin: Float, minArea: Float): Boolean {
        for (i in 0 until 4) {
            if (p[2 * i] < -margin || p[2 * i] > w + margin) return false
            if (p[2 * i + 1] < -margin || p[2 * i + 1] > h + margin) return false
        }
        val minSide = 0.15f * min(w, h)
        var sign = 0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val k = (i + 2) % 4
            val ax = p[2 * j] - p[2 * i]
            val ay = p[2 * j + 1] - p[2 * i + 1]
            val bx = p[2 * k] - p[2 * j]
            val by = p[2 * k + 1] - p[2 * j + 1]
            if (hypot(ax, ay) < minSide) return false
            val cr = ax * by - ay * bx
            val sg = if (cr > 0) 1 else -1
            if (sign == 0) sign = sg else if (sg != sign) return false
            // interior angle between the two sides, 50..130 degrees
            val cosA = (-(ax * bx + ay * by)) / (hypot(ax, ay) * hypot(bx, by))
            if (cosA > 0.65f || cosA < -0.65f) return false
        }
        val area = polyArea(p)
        return area >= minArea && area <= (w + 2 * margin) * (h + 2 * margin)
    }

    /** Fraction of a side that lies on a real edge pointing the right way (0.5 = mostly outside the frame). */
    private fun side(dirQ: ByteArray, w: Int, h: Int, x0: Float, y0: Float, x1: Float, y1: Float, t: Int): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx, dy)
        val n = max(8, (len / 2f).toInt())
        var tot = 0
        var sup = 0
        for (k in 0..n) {
            val f = k / n.toFloat()
            val x = (x0 + dx * f).roundToInt()
            val y = (y0 + dy * f).roundToInt()
            if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) continue
            tot++
            if (supported(dirQ, w, h, x, y, t)) sup++
        }
        return if (tot < 0.35f * (n + 1)) 0.5f else sup / tot.toFloat()
    }

    private fun supported(dirQ: ByteArray, w: Int, h: Int, x: Int, y: Int, t: Int): Boolean {
        for (dy in -2..2) {
            val row = (y + dy) * w
            for (dx in -2..2) {
                val v = dirQ[row + x + dx].toInt() and 0xFF
                if (v == 255) continue
                var dd = abs(v - t)
                if (dd > 90) dd = 180 - dd
                if (dd <= 18) return true
            }
        }
        return false
    }

    /** Clockwise from the top-left corner, clamped to the image. */
    private fun orderAndClamp(p: FloatArray, w: Int, h: Int): FloatArray {
        var s = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]
        }
        val idx = if (s >= 0f) intArrayOf(0, 1, 2, 3) else intArrayOf(0, 3, 2, 1)
        var start = 0
        var bestSum = Float.MAX_VALUE
        for (n in 0 until 4) {
            val sum = p[2 * idx[n]] + p[2 * idx[n] + 1]
            if (sum < bestSum) {
                bestSum = sum
                start = n
            }
        }
        val out = FloatArray(8)
        for (n in 0 until 4) {
            val k = idx[(start + n) % 4]
            out[2 * n] = p[2 * k].coerceIn(0f, (w - 1).toFloat())
            out[2 * n + 1] = p[2 * k + 1].coerceIn(0f, (h - 1).toFloat())
        }
        return out
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val d = 2 * r + 1
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (i in -r..r) sum += src[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / d
                sum += src[row + min(x + r + 1, w - 1)] - src[row + max(x - r, 0)]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (i in -r..r) sum += tmp[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / d
                sum += tmp[min(y + r + 1, h - 1) * w + x] - tmp[max(y - r, 0) * w + x]
            }
        }
        return out
    }
}
