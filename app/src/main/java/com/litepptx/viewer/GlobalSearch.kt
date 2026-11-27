package com.litepptx.viewer

import android.content.Context
import android.graphics.Color
import android.net.Uri
import com.litepptx.viewer.office.DocBlock
import com.litepptx.viewer.office.DocDocument
import com.litepptx.viewer.office.DocxParser
import com.litepptx.viewer.office.LegacyOffice
import com.litepptx.viewer.office.OoxmlPackage
import com.litepptx.viewer.office.TextFormats
import com.litepptx.viewer.office.Workbook
import com.litepptx.viewer.office.XlsxParser
import com.litepptx.viewer.pptx.Para
import com.litepptx.viewer.pptx.PptxParser
import com.litepptx.viewer.pptx.Run
import java.io.File
import java.util.zip.ZipFile

/** One text hit inside a recent file. */
class GlobalHit(
    val uri: String,
    val name: String,
    val kind: String, // "pptx" | "doc" | "sheet"
    val pos: Int, // slide, doc block or sheet list position (0-based)
    val label: String,
    val snippet: String,
    val sheet: Int = 0 // sheet index for "sheet" hits
)

/**
 * Searches the text of every recent file. Runs on the caller's thread, so call
 * it from a background thread. PDFs are excluded: PdfRenderer cannot extract
 * text. A file that moved, broke or vanished is skipped, never fatal.
 * Temp copies are named gsearch-* and deleted after parsing.
 */
object GlobalSearch {

    const val MAX_HITS = 50
    private const val MAX_PER_FILE = 10

    fun search(ctx: Context, query: String): List<GlobalHit> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val hits = ArrayList<GlobalHit>()
        for (e in RecentStore.load(ctx)) {
            if (hits.size >= MAX_HITS) break
            try {
                hits += searchFile(ctx, e, q)
            } catch (_: Exception) {
                // Skip the file; the search goes on.
            }
        }
        return hits.take(MAX_HITS)
    }

    private fun searchFile(ctx: Context, e: RecentStore.Entry, q: String): List<GlobalHit> {
        val uri = Uri.parse(e.uri)
        val ext = e.name.substringAfterLast('.', "").lowercase()
        val f = File(ctx.cacheDir, "gsearch-${System.nanoTime()}.$ext")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { src ->
                f.outputStream().use { dst -> src.copyTo(dst, 64 * 1024) }
            } ?: return emptyList()
            return when (e.kind) {
                "pptx" -> searchPptx(ctx, e, f, q)
                "sheet" -> searchSheet(ctx, e, f, ext, q)
                else -> searchDoc(ctx, e, f, ext, q)
            }
        } finally {
            f.delete()
        }
    }

    private fun searchPptx(ctx: Context, e: RecentStore.Entry, f: File, q: String): List<GlobalHit> {
        val hits = ArrayList<GlobalHit>()
        val deck = PptxParser(ZipFile(f), f).parse()
        try {
            for (slide in deck.slides) {
                val text = slide.textContent()
                val at = text.lowercase().indexOf(q)
                if (at < 0) continue
                hits += GlobalHit(
                    e.uri, e.name, "pptx", slide.index,
                    ctx.getString(R.string.global_hit_slide, slide.index + 1),
                    snippet(text, at, q.length)
                )
                if (hits.size >= MAX_PER_FILE) break
            }
        } finally {
            deck.close()
        }
        return hits
    }

    private fun searchDoc(ctx: Context, e: RecentStore.Entry, f: File, ext: String, q: String): List<GlobalHit> {
        val doc = parseDoc(f, ext)
        val hits = ArrayList<GlobalHit>()
        try {
            for ((i, b) in doc.blocks.withIndex()) {
                val text = blockText(b)
                val at = text.lowercase().indexOf(q)
                if (at < 0) continue
                hits += GlobalHit(
                    e.uri, e.name, "doc", i,
                    ctx.getString(R.string.global_hit_block, i + 1),
                    snippet(text, at, q.length)
                )
                if (hits.size >= MAX_PER_FILE) break
            }
        } finally {
            doc.close()
        }
        return hits
    }

    private fun searchSheet(ctx: Context, e: RecentStore.Entry, f: File, ext: String, q: String): List<GlobalHit> {
        val wb: Workbook = when (ext) {
            "xlsx" -> {
                val pkg = OoxmlPackage(ZipFile(f), f)
                try {
                    XlsxParser.parse(pkg)
                } catch (t: Throwable) {
                    pkg.close()
                    throw t
                }
            }
            else -> TextFormats.parseCsv(readText(f))
        }
        val hits = ArrayList<GlobalHit>()
        try {
            for ((si, sheet) in wb.sheets.withIndex()) {
                for (row in sheet.rows) {
                    val text = row.cells.entries.sortedBy { it.key }.joinToString(" ") { it.value.text }
                    val at = text.lowercase().indexOf(q)
                    if (at < 0) continue
                    // List position 0 is the A/B/C header, so a data row sits at index + 1.
                    val pos = row.index + 1
                    hits += GlobalHit(
                        e.uri, e.name, "sheet", pos,
                        ctx.getString(R.string.global_hit_row, row.index + 1),
                        snippet(text, at, q.length), si
                    )
                    if (hits.size >= MAX_PER_FILE) return hits
                }
            }
        } finally {
            wb.close()
        }
        return hits
    }

    /** Same format dispatch as the office viewer, minus the UI. */
    private fun parseDoc(f: File, ext: String): DocDocument {
        return when (ext) {
            "docx" -> {
                val pkg = OoxmlPackage(ZipFile(f), f)
                try {
                    DocxParser.parse(pkg)
                } catch (t: Throwable) {
                    pkg.close()
                    throw t
                }
            }
            "doc", "ppt", "xls" -> {
                val bytes = f.readBytes()
                if (LegacyOffice.isOle(bytes)) {
                    legacyDoc(LegacyOffice.extractText(bytes))
                } else if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                    // A modern file with a legacy extension: try the OOXML parser.
                    val pkg = OoxmlPackage(ZipFile(f), f)
                    try {
                        DocxParser.parse(pkg)
                    } catch (t: Throwable) {
                        pkg.close()
                        throw t
                    }
                } else {
                    throw IllegalStateException("not an Office file")
                }
            }
            "rtf" -> TextFormats.parseRtf(readText(f))
            "md", "markdown" -> TextFormats.parseMd(readText(f))
            else -> TextFormats.parseTxt(readText(f))
        }
    }

    private fun blockText(b: DocBlock): String {
        val sb = StringBuilder()
        when (b) {
            is DocBlock.ParaBlock -> for (r in b.para.runs) sb.append(r.text)
            is DocBlock.TableBlock -> for (row in b.rows) for (cell in row) for (p in cell.paras) {
                for (r in p.runs) sb.append(r.text)
                sb.append(' ')
            }
            is DocBlock.ImageBlock -> {}
        }
        return sb.toString()
    }

    /** Legacy text as plain paragraphs. */
    private fun legacyDoc(text: String): DocDocument {
        val blocks = ArrayList<DocBlock>()
        for (chunk in text.split("\n\n")) {
            val t = chunk.trim()
            if (t.isEmpty()) continue
            val rtl = TextFormats.firstStrongIsRtl(t)
            blocks.add(
                DocBlock.ParaBlock(
                    Para("l", rtl, listOf(Run(t.replace('\n', ' '), 11f, Color.BLACK, false, false, false)), null, 0f, 0f, 1f, 0f, 0f, 11f)
                )
            )
        }
        return DocDocument(blocks, null)
    }

    private fun readText(f: File): String {
        val bytes = f.readBytes()
        val text = String(bytes, Charsets.UTF_8)
        return if (text.startsWith("\uFEFF")) text.substring(1) else text
    }

    /** A window around the match: 18 chars before, the match, 38 after. */
    private fun snippet(text: String, at: Int, len: Int): String {
        val start = (at - 18).coerceAtLeast(0)
        val end = (at + len + 38).coerceAtMost(text.length)
        val core = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
        return (if (start > 0) "…" else "") + core + (if (end < text.length) "…" else "")
    }
}
