package com.litepptx.viewer.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * PDF tools: lossless recompression, rendered rebuilds (compress / merge / split).
 *
 * Lossless only works on classic xref-table PDFs: streams with a direct integer
 * /Length and a sole /FlateDecode filter are re-deflated at level 9 and kept only
 * when smaller; everything else is copied verbatim and the file is rewritten with
 * a fresh xref table. Anything else (xref streams, object streams, indirect
 * lengths, incremental updates) returns false — the input is never touched.
 * The rendered rebuilds rasterise pages to JPEG and rebuild with [PdfBuilder];
 * the text layer is lost, and the UI must say so.
 */
object PdfTools {

    enum class Preset(val dpi: Int, val quality: Int) {
        KEEP(200, 88), BALANCED(170, 80), SMALL(130, 70)
    }

    class Result(val pages: Int, val bytes: Long, val sourceBytes: Long)

    fun pageCount(file: File): Int {
        if (!file.isFile) return 0
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            renderer.pageCount
        } catch (_: Throwable) {
            0
        } finally {
            try { renderer?.close() } catch (_: Throwable) { }
            try { pfd?.close() } catch (_: Throwable) { }
        }
    }

    // ------------------------------------------------------------------ lossless

    fun compressLossless(src: File, out: File): Boolean {
        var ok = false
        try {
            val data = src.readBytes()
            if (!startsWith(data, "%PDF-".toByteArray(Charsets.ISO_8859_1))) return false
            // Object streams and xref streams cannot be rewritten by this optimiser.
            if (indexOf(data, "/ObjStm".toByteArray(Charsets.ISO_8859_1), 0) >= 0) return false
            if (indexOf(data, "/XRefStm".toByteArray(Charsets.ISO_8859_1), 0) >= 0) return false

            val startxref = lastIndexOf(data, "startxref".toByteArray(Charsets.ISO_8859_1))
            if (startxref < 0) return false
            val xrefPos = parseIntAfter(data, startxref + "startxref".length) ?: return false
            if (!startsWithAt(data, xrefPos, "xref".toByteArray(Charsets.ISO_8859_1))) return false

            val entries = parseXref(data, xrefPos) ?: return false
            val trailer = parseTrailer(data, entries.trailerPos) ?: return false
            if (trailer.contains("/Prev")) return false
            val rootRef = Regex("/Root\\s+(\\d+\\s+\\d+\\s+R)").find(trailer)?.groupValues?.get(1) ?: return false
            val infoRef = Regex("/Info\\s+(\\d+\\s+\\d+\\s+R)").find(trailer)?.groupValues?.get(1)

            val maxObj = entries.objs.keys.maxOrNull() ?: return false
            val rebuilt = HashMap<Int, ByteArray>()
            var anySmaller = false
            for ((num, e) in entries.objs) {
                if (e.free) continue
                val objBytes = sliceObject(data, num, e.gen, e.offset) ?: return false
                val smaller = tryRecompress(objBytes)
                if (smaller != null) {
                    rebuilt[num] = smaller
                    anySmaller = true
                } else {
                    rebuilt[num] = objBytes
                }
            }
            if (!anySmaller) return false

            val bos = ByteArrayOutputStream(data.size)
            bos.write("%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1))
            bos.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
            val offsets = LongArray(maxObj + 1)
            for (num in 1..maxObj) {
                val bytes = rebuilt[num] ?: continue
                offsets[num] = bos.size().toLong()
                bos.write(bytes)
                bos.write('\n'.code)
            }
            val xrefAt = bos.size().toLong()
            val sb = StringBuilder()
            sb.append("xref\n0 ").append(maxObj + 1).append('\n')
            sb.append("0000000000 65535 f \n")
            for (num in 1..maxObj) {
                if (rebuilt.containsKey(num)) {
                    sb.append(String.format(java.util.Locale.US, "%010d 00000 n \n", offsets[num]))
                } else {
                    sb.append("0000000000 65535 f \n")
                }
            }
            sb.append("trailer\n<< /Size ").append(maxObj + 1).append(" /Root ").append(rootRef)
            if (infoRef != null) sb.append(" /Info ").append(infoRef)
            sb.append(" >>\nstartxref\n").append(xrefAt).append("\n%%EOF\n")
            bos.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            FileOutputStream(out).use { it.write(bos.toByteArray()) }

            // Verify: the result must open and have the same page count, and be smaller.
            val before = pageCount(src)
            val after = pageCount(out)
            ok = before > 0 && after == before && out.length() < src.length()
            if (!ok) out.delete()
            return ok
        } catch (_: Throwable) {
            try { out.delete() } catch (_: Throwable) { }
            return false
        }
    }

    private class XrefEntry(val offset: Int, val gen: Int, val free: Boolean)
    private class Xref(val objs: Map<Int, XrefEntry>, val trailerPos: Int)

    /** Parses "xref" subsections ("first count" + 20-byte entries) up to "trailer". */
    private fun parseXref(data: ByteArray, xrefPos: Int): Xref? {
        val text = String(data, Charsets.ISO_8859_1)
        var pos = xrefPos + "xref".length
        val objs = HashMap<Int, XrefEntry>()
        val lineRe = Regex("(\\d+)\\s+(\\d+)")
        val entryRe = Regex("(\\d{10})\\s+(\\d{5})\\s+([nf])")
        while (true) {
            // Skip whitespace/newlines.
            while (pos < text.length && text[pos].isWhitespace()) pos++
            if (text.startsWith("trailer", pos)) return Xref(objs, pos)
            val lineEnd = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
            val line = text.substring(pos, lineEnd).trim()
            val m = lineRe.matchEntire(line) ?: return null
            val first = m.groupValues[1].toIntOrNull() ?: return null
            val count = m.groupValues[2].toIntOrNull() ?: return null
            if (count < 0 || count > 100_000) return null
            pos = lineEnd + 1
            for (k in 0 until count) {
                val eEnd = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
                val eLine = text.substring(pos, eEnd).trim()
                val em = entryRe.matchEntire(eLine) ?: return null
                val off = em.groupValues[1].toIntOrNull() ?: return null
                val gen = em.groupValues[2].toIntOrNull() ?: 0
                objs[first + k] = XrefEntry(off, gen, em.groupValues[3] == "f")
                pos = eEnd + 1
            }
        }
    }

    /** Reads the trailer dict (balanced << >>) starting at [trailerPos] ("trailer" keyword). */
    private fun parseTrailer(data: ByteArray, trailerPos: Int): String? {
        val text = String(data, Charsets.ISO_8859_1)
        val start = text.indexOf("<<", trailerPos)
        if (start < 0) return null
        var depth = 0
        var i = start
        while (i + 1 < text.length) {
            if (text[i] == '<' && text[i + 1] == '<') { depth++; i += 2 } else if (text[i] == '>' && text[i + 1] == '>') {
                depth--
                i += 2
                if (depth == 0) return text.substring(start, i)
            } else i++
        }
        return null
    }

    /** Exact bytes of one indirect object, from "N G obj" through "endobj". */
    private fun sliceObject(data: ByteArray, num: Int, gen: Int, offset: Int): ByteArray? {
        if (offset < 0 || offset >= data.size) return null
        val head = "$num $gen obj".toByteArray(Charsets.ISO_8859_1)
        if (!startsWithAt(data, offset, head)) return null
        val end = indexOf(data, "endobj".toByteArray(Charsets.ISO_8859_1), offset)
        if (end < 0) return null
        return data.copyOfRange(offset, end + "endobj".length)
    }

    /**
     * Re-deflates a FlateDecode stream object at level 9. Returns the rebuilt object
     * bytes when smaller, null when the object is not a candidate.
     */
    private fun tryRecompress(obj: ByteArray): ByteArray? {
        val streamAt = indexOf(obj, "stream".toByteArray(Charsets.ISO_8859_1), 0)
        if (streamAt < 0) return null
        val dict = String(obj, 0, streamAt, Charsets.ISO_8859_1)
        // Only a sole /FlateDecode filter with a direct integer /Length.
        val filter = Regex("/Filter\\s*(/\\w+|\\[[^\\]]*\\])").find(dict)?.groupValues?.get(1) ?: return null
        if (filter != "/FlateDecode" && filter != "[/FlateDecode]") return null
        val lenMatch = Regex("/Length\\s+(\\d+)").find(dict) ?: return null
        val length = lenMatch.groupValues[1].toIntOrNull() ?: return null
        var dataStart = streamAt + "stream".length
        if (dataStart < obj.size && obj[dataStart] == '\r'.code.toByte()) dataStart++
        if (dataStart < obj.size && obj[dataStart] == '\n'.code.toByte()) dataStart++
        if (dataStart + length > obj.size) return null
        val raw = obj.copyOfRange(dataStart, dataStart + length)
        val inflated = try {
            InflaterInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
        } catch (_: Exception) {
            return null
        }
        val recompressed = ByteArrayOutputStream(inflated.size / 2 + 64)
        DeflaterOutputStream(recompressed, Deflater(9)).use { it.write(inflated) }
        val out = recompressed.toByteArray()
        if (out.size >= length) return null
        // Rebuild: dict with the new /Length, then the new stream data.
        val head = dict.replaceRange(lenMatch.range, "/Length ${out.size}")
        val bos = ByteArrayOutputStream(head.length + out.size + 32)
        bos.write(head.toByteArray(Charsets.ISO_8859_1))
        bos.write("stream\n".toByteArray(Charsets.ISO_8859_1))
        bos.write(out)
        bos.write("\nendstream\nendobj".toByteArray(Charsets.ISO_8859_1))
        return bos.toByteArray()
    }

    // ------------------------------------------------------------------ rendered rebuilds

    fun compressRendered(
        ctx: Context, src: File, out: File, preset: Preset,
        progress: (Int) -> Unit, cancelled: () -> Boolean
    ): Result? {
        val n = pageCount(src)
        if (n <= 0) return null
        return renderToPdf(ctx, listOf(src to (0 until n)), out, preset, progress, cancelled)
    }

    fun merge(
        ctx: Context, files: List<File>, out: File, preset: Preset,
        progress: (Int) -> Unit, cancelled: () -> Boolean
    ): Result? {
        val jobs = ArrayList<Pair<File, IntRange>>()
        for (f in files) {
            val n = pageCount(f)
            if (n <= 0) return null
            jobs.add(f to (0 until n))
        }
        if (jobs.isEmpty()) return null
        return renderToPdf(ctx, jobs, out, preset, progress, cancelled)
    }

    fun split(
        ctx: Context, src: File, from1: Int, to1: Int, out: File, preset: Preset,
        progress: (Int) -> Unit, cancelled: () -> Boolean
    ): Result? {
        val n = pageCount(src)
        if (n <= 0) return null
        val from = from1.coerceIn(1, n)
        val to = to1.coerceIn(from, n)
        return renderToPdf(ctx, listOf(src to ((from - 1) until to)), out, preset, progress, cancelled)
    }

    /**
     * Renders every page in [jobs] (in order) to a JPEG at [preset] resolution, then
     * rebuilds one PDF with [PdfBuilder]. Returns null when cancelled or failed.
     */
    private fun renderToPdf(
        ctx: Context, jobs: List<Pair<File, IntRange>>, out: File, preset: Preset,
        progress: (Int) -> Unit, cancelled: () -> Boolean
    ): Result? {
        val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
        val temps = ArrayList<File>()
        var sourceBytes = 0L
        try {
            var done = 0
            for ((file, range) in jobs) {
                sourceBytes += file.length()
                var pfd: ParcelFileDescriptor? = null
                var renderer: PdfRenderer? = null
                try {
                    pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    renderer = PdfRenderer(pfd)
                    for (pageIdx in range) {
                        if (cancelled()) return null
                        val tmp = File(dir, "tmp_${System.currentTimeMillis()}_$done.jpg")
                        var bmp: Bitmap? = null
                        try {
                            renderer.openPage(pageIdx).use { page ->
                                val scale = preset.dpi / 72f
                                var w = (page.width * scale).toInt().coerceAtLeast(1)
                                var h = (page.height * scale).toInt().coerceAtLeast(1)
                                val longest = maxOf(w, h)
                                if (longest > 3500) {
                                    val k = 3500f / longest
                                    w = (w * k).toInt().coerceAtLeast(1)
                                    h = (h * k).toInt().coerceAtLeast(1)
                                }
                                val rendered = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bmp = rendered
                                val c = Canvas(rendered)
                                c.drawColor(Color.WHITE)
                                page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            }
                            val doneBmp = bmp ?: throw IllegalStateException("render failed")
                            FileOutputStream(tmp).use { doneBmp.compress(Bitmap.CompressFormat.JPEG, preset.quality, it) }
                            temps.add(tmp)
                        } finally {
                            bmp?.recycle()
                        }
                        done++
                        progress(done)
                    }
                } finally {
                    try { renderer?.close() } catch (_: Throwable) { }
                    try { pfd?.close() } catch (_: Throwable) { }
                }
            }
            if (temps.isEmpty() || cancelled()) return null
            val items = temps.map { PdfBuilder.Item(PdfShareProvider.uriFor(it.name)) }
            val res = PdfBuilder.build(
                ctx, items, PdfBuilder.Options(PdfBuilder.PAGE_AUTO, 0, 0),
                out, out.name.removeSuffix(".pdf"), { i -> progress(i + 1) }, cancelled
            ) ?: return null
            return Result(res.pages, res.bytes, sourceBytes)
        } catch (t: Throwable) {
            android.util.Log.w("LitePPTX", "pdf tools failed", t)
            try { out.delete() } catch (_: Throwable) { }
            return null
        } finally {
            temps.forEach { try { it.delete() } catch (_: Throwable) { } }
        }
    }

    // ------------------------------------------------------------------ byte helpers

    private fun startsWith(data: ByteArray, needle: ByteArray): Boolean = startsWithAt(data, 0, needle)

    private fun startsWithAt(data: ByteArray, at: Int, needle: ByteArray): Boolean {
        if (at < 0 || at + needle.size > data.size) return false
        for (i in needle.indices) if (data[at + i] != needle[i]) return false
        return true
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || from >= data.size) return -1
        outer@ for (i in from..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun lastIndexOf(data: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return -1
        for (i in data.size - needle.size downTo 0) {
            var match = true
            for (j in needle.indices) if (data[i + j] != needle[j]) { match = false; break }
            if (match) return i
        }
        return -1
    }

    /** Integer that follows [at] (skipping whitespace). */
    private fun parseIntAfter(data: ByteArray, at: Int): Int? {
        var i = at
        while (i < data.size && (data[i] == ' '.code.toByte() || data[i] == '\n'.code.toByte() || data[i] == '\r'.code.toByte())) i++
        var j = i
        while (j < data.size && data[j] in '0'.code.toByte()..'9'.code.toByte()) j++
        if (j == i) return null
        return String(data, i, j - i, Charsets.ISO_8859_1).toIntOrNull()
    }
}
