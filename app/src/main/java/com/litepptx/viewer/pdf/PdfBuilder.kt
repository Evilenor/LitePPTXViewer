package com.litepptx.viewer.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Turns a list of images into one PDF.
 *
 * Quality presets (long-edge cap / target DPI / JPEG quality):
 *  0 Original - no resampling, JPEGs are embedded byte-for-byte, PNGs stay lossless
 *  1 High     - 3600 px / 300 dpi / q90 (PNG screenshots stay lossless)
 *  2 Smart    - 2600 px / 200 dpi / q82 (visually identical on screen and in print)
 *  3 Small    - 1600 px / 130 dpi / q68
 */
object PdfBuilder {

    const val PAGE_AUTO = 0
    const val PAGE_A4 = 1
    const val PAGE_LETTER = 2

    class Item(val uri: Uri, var rotation: Int = 0)
    class Options(val page: Int, val margin: Int, val quality: Int)
    class Result(val pages: Int, val bytes: Long, val sourceBytes: Long)

    private class Cancelled : RuntimeException()

    private val CAPS = intArrayOf(Int.MAX_VALUE, 3600, 2600, 1600)
    private val DPIS = floatArrayOf(0f, 300f, 200f, 130f)
    private val QUALITY = intArrayOf(95, 90, 82, 68)
    private val MARGINS = floatArrayOf(0f, 20f, 36f)
    private const val LONG_PT = 841.89f

    /** Returns null when cancelled. Throws on failure. */
    fun build(
        ctx: Context,
        items: List<Item>,
        opt: Options,
        outFile: File,
        title: String,
        progress: (Int) -> Unit,
        cancelled: () -> Boolean
    ): Result? {
        var source = 0L
        try {
            BufferedOutputStream(FileOutputStream(outFile), 64 * 1024).use { os ->
                val writer = PdfWriter(os, title)
                for ((i, item) in items.withIndex()) {
                    if (cancelled()) throw Cancelled()
                    progress(i)
                    val data = ctx.contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
                        ?: throw IOException("Cannot open image")
                    source += data.size
                    addImage(writer, data, item.rotation, opt)
                }
                writer.finish(SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date()))
            }
        } catch (_: Cancelled) {
            outFile.delete()
            return null
        } catch (t: Throwable) {
            outFile.delete()
            throw t
        }
        return Result(items.size, outFile.length(), source)
    }

    private fun addImage(w: PdfWriter, data: ByteArray, userRot: Int, opt: Options) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) throw IOException("Unsupported image")
        val mime = bounds.outMimeType ?: ""
        val isJpeg = mime == "image/jpeg"
        val isPng = mime == "image/png"

        val orient = if (isJpeg || mime == "image/webp" || mime.contains("heif") || mime.contains("heic")) exifOrientation(data) else 1
        val user = ((userRot % 360) + 360) % 360
        val needsTransform = orient > 1 || user != 0

        val m = matrixFor(orient, user)

        val exifSwap = orient in 5..8
        val swap = exifSwap xor (user == 90 || user == 270)
        val ew = if (swap) sh else sw
        val eh = if (swap) sw else sh

        // ---- page geometry (PDF points) ----
        val margin = MARGINS[opt.margin.coerceIn(0, 2)]
        val pageW: Float
        val pageH: Float
        val px: Float
        val py: Float
        val pw: Float
        val ph: Float
        if (opt.page == PAGE_AUTO) {
            if (ew >= eh) { pw = LONG_PT; ph = LONG_PT * eh / ew } else { ph = LONG_PT; pw = LONG_PT * ew / eh }
            pageW = pw + 2 * margin
            pageH = ph + 2 * margin
            px = margin
            py = margin
        } else {
            val baseW = if (opt.page == PAGE_LETTER) 612f else 595.276f
            val baseH = if (opt.page == PAGE_LETTER) 792f else 841.89f
            pageW = if (ew > eh) baseH else baseW
            pageH = if (ew > eh) baseW else baseH
            val s = minOf((pageW - 2 * margin) / ew, (pageH - 2 * margin) / eh)
            pw = ew * s
            ph = eh * s
            px = (pageW - pw) / 2f
            py = (pageH - ph) / 2f
        }

        // ---- target resolution ----
        val q = opt.quality.coerceIn(0, 3)
        var scale = 1f
        if (q != 0) {
            scale = minOf(1f, CAPS[q].toFloat() / maxOf(ew, eh))
            scale = minOf(scale, (pw / 72f * DPIS[q]) / ew)
            scale = scale.coerceIn(0.05f, 1f)
        }
        val tw = maxOf(1, Math.round(ew * scale))
        val th = maxOf(1, Math.round(eh * scale))

        // ---- lossless pass-through for JPEGs that already are small enough ----
        if (isJpeg && !needsTransform && scale >= 0.98f) {
            val bytesPerPixel = data.size.toFloat() / (sw.toLong() * sh)
            if (q == 0 || bytesPerPixel <= 0.6f) {
                val comps = jpegComponents(data)
                if (comps == 1 || comps == 3) {
                    w.addPage(pageW, pageH, data, sw, sh, "DCTDecode", comps, false, px, py, pw, ph)
                    return
                }
            }
        }

        // ---- decode (sampled), rotate + scale in one pass ----
        val stw = if (swap) th else tw
        val sth = if (swap) tw else th
        var sample = 1
        while (sw / (sample * 2) >= stw && sh / (sample * 2) >= sth) sample *= 2

        var bmp: Bitmap? = null
        while (bmp == null) {
            try {
                val o = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                bmp = BitmapFactory.decodeByteArray(data, 0, data.size, o) ?: throw IOException("Decode failed")
            } catch (e: OutOfMemoryError) {
                if (sample >= 64) throw e
                sample *= 2
            }
        }

        val sx = stw.toFloat() / bmp.width
        if (Math.abs(sx - 1f) > 0.01f) m.postScale(sx, sx)
        var out: Bitmap = bmp
        if (!m.isIdentity) {
            out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out !== bmp) bmp.recycle()
        }
        if (out.hasAlpha()) {
            val flat = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(flat)
            c.drawColor(Color.WHITE)
            c.drawBitmap(out, 0f, 0f, null)
            out.recycle()
            out = flat
        }

        val ow = out.width
        val oh = out.height
        val lossless = isPng && q <= 1 && ow.toLong() * oh <= 16_000_000L
        if (lossless) {
            val flate = flateRgb(out)
            out.recycle()
            w.addPage(pageW, pageH, flate, ow, oh, "FlateDecode", 3, true, px, py, pw, ph)
        } else {
            val bos = ByteArrayOutputStream(maxOf(64 * 1024, ow * oh / 6))
            out.compress(Bitmap.CompressFormat.JPEG, QUALITY[q], bos)
            out.recycle()
            w.addPage(pageW, pageH, bos.toByteArray(), ow, oh, "DCTDecode", 3, false, px, py, pw, ph)
        }
    }

    /** Lossless RGB with PNG "Up" row filters + deflate: very small for screenshots and scans of text. */
    private fun flateRgb(b: Bitmap): ByteArray {
        val w = b.width
        val h = b.height
        val bos = ByteArrayOutputStream(maxOf(64 * 1024, w * h / 4))
        val dos = DeflaterOutputStream(bos, Deflater(9), 32 * 1024)
        val px = IntArray(w)
        var prev = ByteArray(w * 3)
        var cur = ByteArray(w * 3)
        val row = ByteArray(1 + w * 3)
        row[0] = 2
        for (y in 0 until h) {
            b.getPixels(px, 0, w, 0, y, w, 1)
            var j = 0
            for (i in 0 until w) {
                val p = px[i]
                cur[j++] = (p shr 16).toByte()
                cur[j++] = (p shr 8).toByte()
                cur[j++] = p.toByte()
            }
            for (i in 0 until w * 3) row[1 + i] = (cur[i] - prev[i]).toByte()
            dos.write(row)
            val t = prev; prev = cur; cur = t
        }
        dos.finish()
        dos.close()
        return bos.toByteArray()
    }

    /** EXIF orientation (1..8) followed by an extra clockwise user rotation (0/90/180/270). */
    fun matrixFor(orient: Int, user: Int): Matrix {
        val m = Matrix()
        when (orient) {
            2 -> m.postScale(-1f, 1f)
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f)
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.postRotate(90f)
            7 -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            8 -> m.postRotate(270f)
        }
        if (user != 0) m.postRotate(user.toFloat())
        return m
    }

    fun exifOrientation(data: ByteArray): Int {
        if (Build.VERSION.SDK_INT < 24) return 1
        return try {
            ExifInterface(ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        } catch (_: Throwable) {
            1
        }
    }

    /** Number of colour components of a baseline/progressive JPEG, or 0 if it cannot be embedded as is. */
    private fun jpegComponents(d: ByteArray): Int {
        var i = 2
        while (i + 9 < d.size) {
            if (d[i] != 0xFF.toByte()) { i++; continue }
            val mk = d[i + 1].toInt() and 0xFF
            if (mk == 0xFF) { i++; continue }
            if (mk == 0xD8 || mk == 0x01 || mk in 0xD0..0xD7 || mk == 0x00) { i += 2; continue }
            if (mk == 0xDA) return 0
            val len = ((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF)
            if (mk == 0xC0 || mk == 0xC1 || mk == 0xC2) return d[i + 9].toInt() and 0xFF
            if (mk in 0xC3..0xCF && mk != 0xC4 && mk != 0xC8 && mk != 0xCC) return 0
            i += 2 + len
        }
        return 0
    }
}
