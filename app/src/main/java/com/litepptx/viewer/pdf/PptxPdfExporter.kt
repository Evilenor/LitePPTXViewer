package com.litepptx.viewer.pdf

import android.graphics.Bitmap
import com.litepptx.viewer.pptx.Deck
import com.litepptx.viewer.pptx.SlideRenderer
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports a presentation as a PDF using the app's own slide renderer, so the PDF looks exactly like
 * what is on screen. One page per slide, sized like the slide itself, one JPEG image per page.
 */
object PptxPdfExporter {

    class Result(val pages: Int, val bytes: Long)

    private class Cancelled : RuntimeException()

    private const val RENDER_W = 1920     // about 144 dpi on a 13.3 inch slide
    private const val MAX_SIDE = 2600
    private const val JPEG_Q = 90

    /** Returns null when cancelled. Throws on failure. Call from a background thread. */
    fun export(
        deck: Deck,
        out: File,
        title: String,
        progress: (Int) -> Unit,
        cancelled: () -> Boolean
    ): Result? {
        val renderer = SlideRenderer(deck)
        val wPt = (deck.wEmu / 12700f).coerceIn(72f, 14400f)
        val hPt = (deck.hEmu / 12700f).coerceIn(72f, 14400f)
        val ratio = hPt / wPt
        var wpx = RENDER_W
        if (wpx * ratio > MAX_SIDE) wpx = (MAX_SIDE / ratio).toInt().coerceAtLeast(320)
        try {
            BufferedOutputStream(FileOutputStream(out), 64 * 1024).use { os ->
                val writer = PdfWriter(os, title)
                for ((i, slide) in deck.slides.withIndex()) {
                    if (cancelled()) throw Cancelled()
                    progress(i)
                    val bmp = renderer.render(slide, wpx)
                    val bos = ByteArrayOutputStream(maxOf(64 * 1024, bmp.width * bmp.height / 8))
                    bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, bos)
                    val bw = bmp.width
                    val bh = bmp.height
                    bmp.recycle()
                    writer.addPage(wPt, hPt, bos.toByteArray(), bw, bh, "DCTDecode", 3, false, 0f, 0f, wPt, hPt)
                }
                writer.finish(SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date()))
            }
        } catch (_: Cancelled) {
            out.delete()
            return null
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
        return Result(deck.slides.size, out.length())
    }
}
