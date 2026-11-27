package com.litepptx.viewer.office

import android.graphics.Color
import com.litepptx.viewer.pptx.Para
import com.litepptx.viewer.pptx.Run

/**
 * Plain-text formats, parsed fully in memory (no package): .txt, .csv, .rtf, .md.
 * All of them produce the same [DocDocument]/[Workbook] models as the OOXML parsers.
 */
object TextFormats {

    private const val DEFAULT_SIZE = 11f
    private const val BLACK = Color.BLACK

    private fun para(
        text: String, size: Float = DEFAULT_SIZE, bold: Boolean = false,
        align: String = "l", bullet: String? = null
    ): Para {
        val rtl = firstStrongIsRtl(text)
        return Para(align, rtl, listOf(Run(text, size, BLACK, bold, false, false)), bullet, 0f, 0f, 1f, 0f, 0f, size)
    }

    /** RTL when the first strong (letter) character is in a right-to-left block. */
    fun firstStrongIsRtl(text: String): Boolean {
        for (ch in text) {
            val b = Character.getDirectionality(ch)
            if (b == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                b == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
            ) return true
            if (b == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false
        }
        return false
    }

    // ------------------------------------------------------------------ .txt

    fun parseTxt(text: String): DocDocument {
        val blocks = ArrayList<DocBlock>()
        val cleaned = text.replace("\r\n", "\n").replace('\r', '\n')
        for (chunk in cleaned.split("\n\n")) {
            val t = chunk.trim()
            if (t.isEmpty()) continue
            blocks.add(DocBlock.ParaBlock(para(t.replace('\n', ' '))))
        }
        if (blocks.isEmpty() && cleaned.isNotBlank()) {
            blocks.add(DocBlock.ParaBlock(para(cleaned.trim())))
        }
        return DocDocument(blocks, null)
    }

    // ------------------------------------------------------------------ .csv (RFC 4180)

    fun parseCsv(text: String): Workbook {
        val rows = parseCsvRows(text.replace("\r\n", "\n").replace('\r', '\n'))
        val sheetRows = ArrayList<SheetRow>()
        var maxCol = 0
        for ((ri, fields) in rows.withIndex()) {
            if (ri >= XlsxParser.MAX_ROWS) break
            val cells = HashMap<Int, SheetCell>()
            for ((ci, f) in fields.withIndex()) {
                if (ci >= XlsxParser.MAX_COLS) break
                cells[ci] = SheetCell(f, ri == 0, null, "l")
                if (ci + 1 > maxCol) maxCol = ci + 1
            }
            if (cells.isNotEmpty()) sheetRows.add(SheetRow(ri, cells, 0f))
        }
        val widths = FloatArray(maxOf(maxCol, 1)) { 12f }
        val sheet = Sheet("Sheet1", widths, sheetRows, emptyList(), maxCol, rows.size > XlsxParser.MAX_ROWS)
        return Workbook(listOf(sheet), null, rows.size > XlsxParser.MAX_ROWS)
    }

    /** Quoted fields may contain commas, newlines and doubled quotes. */
    fun parseCsvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                inQuotes && ch == '"' -> {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cur.append('"')
                        i += 2
                    } else {
                        inQuotes = false
                        i++
                    }
                }
                inQuotes -> {
                    cur.append(ch)
                    i++
                }
                ch == '"' && cur.isEmpty() -> {
                    inQuotes = true
                    i++
                }
                ch == ',' -> {
                    fields.add(cur.toString())
                    cur.setLength(0)
                    i++
                }
                ch == '\n' -> {
                    fields.add(cur.toString())
                    cur.setLength(0)
                    rows.add(fields)
                    fields = ArrayList()
                    i++
                }
                else -> {
                    cur.append(ch)
                    i++
                }
            }
        }
        if (cur.isNotEmpty() || fields.isNotEmpty()) {
            fields.add(cur.toString())
            rows.add(fields)
        }
        return rows.filter { row -> row.any { it.isNotBlank() } }
    }

    // ------------------------------------------------------------------ .rtf

    fun parseRtf(text: String): DocDocument {
        val blocks = ArrayList<DocBlock>()
        val paras = ArrayList<Para>()
        var bold = false
        var italic = false
        var underline = false
        val cur = StringBuilder()
        var i = 0
        var depth = 0
        var skipDepth = -1 // inside \fonttbl / \colortbl / header groups: ignore content

        fun flush() {
            val t = cur.toString().trim()
            if (t.isNotEmpty()) {
                paras.add(
                    Para(
                        "l", firstStrongIsRtl(t),
                        listOf(Run(t, DEFAULT_SIZE, BLACK, bold, italic, underline)),
                        null, 0f, 0f, 1f, 0f, 0f, DEFAULT_SIZE
                    )
                )
            }
            cur.setLength(0)
        }

        while (i < text.length) {
            val ch = text[i]
            if (skipDepth >= 0) {
                // Inside a header table group: only braces matter, the rest is ignored.
                when (ch) {
                    '{' -> { depth++; i++ }
                    '}' -> {
                        if (depth == skipDepth) skipDepth = -1
                        depth--
                        i++
                    }
                    else -> i++
                }
                continue
            }
            when {
                ch == '{' -> {
                    depth++
                    i++
                }
                ch == '}' -> {
                    depth--
                    i++
                }
                ch == '\\' && i + 1 < text.length -> {
                    val n = text[i + 1]
                    when {
                        n == '\'' && i + 3 < text.length -> {
                            // \'hh hex byte (Windows-1252-ish; keep the byte as a char).
                            val hex = text.substring(i + 2, i + 4)
                            hex.toIntOrNull(16)?.let { cur.append(it.toChar()) }
                            i += 4
                        }
                        n == '\\' || n == '{' || n == '}' -> {
                            cur.append(n)
                            i += 2
                        }
                        n.isLetter() -> {
                            var j = i + 1
                            while (j < text.length && text[j].isLetter()) j++
                            val word = text.substring(i + 1, j)
                            var k = j
                            var num: Int? = null
                            if (k < text.length && (text[k] == '-' || text[k].isDigit())) {
                                val s = k
                                if (text[k] == '-') k++
                                while (k < text.length && text[k].isDigit()) k++
                                num = text.substring(s, k).toIntOrNull()
                            }
                            if (k < text.length && text[k] == ' ') k++
                            when (word) {
                                "par", "line" -> {
                                    flush()
                                    paras.add(para("", DEFAULT_SIZE))
                                }
                                "b" -> bold = num != 0
                                "i" -> italic = num != 0
                                "ul" -> underline = num != 0
                                "ulnone" -> underline = false
                                "u" -> {
                                    val code = num ?: 0
                                    cur.append(if (code < 0) (code + 65536).toChar() else code.toChar())
                                    // Skip the single fallback character that follows \uN.
                                    if (k < text.length) {
                                        if (text[k] == '\\' && k + 1 < text.length && text[k + 1] == '\'') k += 4
                                        else k++
                                    }
                                }
                                "fonttbl", "colortbl", "stylesheet", "info", "pict" -> skipDepth = depth
                                "tab" -> cur.append('\t')
                                else -> {}
                            }
                            i = k
                        }
                        else -> i += 2
                    }
                }
                else -> {
                    cur.append(ch)
                    i++
                }
            }
        }
        flush()
        // Drop the empty paragraphs \par leaves behind, keep the rest in order.
        for (p in paras) {
            val hasText = p.runs.any { it.text.isNotBlank() }
            if (hasText) blocks.add(DocBlock.ParaBlock(p))
        }
        return DocDocument(blocks, null)
    }

    // ------------------------------------------------------------------ .md

    fun parseMd(text: String): DocDocument {
        val blocks = ArrayList<DocBlock>()
        val cleaned = text.replace("\r\n", "\n").replace('\r', '\n')
        for (chunk in cleaned.split("\n\n")) {
            val raw = chunk.trim()
            if (raw.isEmpty()) continue
            val lines = raw.lines()
            for (line in lines) {
                val t = line.trim()
                if (t.isEmpty()) continue
                when {
                    t.startsWith("### ") -> blocks.add(DocBlock.ParaBlock(para(t.removePrefix("### "), 12f, true)))
                    t.startsWith("## ") -> blocks.add(DocBlock.ParaBlock(para(t.removePrefix("## "), 14f, true)))
                    t.startsWith("# ") -> blocks.add(DocBlock.ParaBlock(para(t.removePrefix("# "), 16f, true)))
                    t.startsWith("- ") || t.startsWith("* ") ->
                        blocks.add(DocBlock.ParaBlock(inlinePara(t.substring(2), bullet = "•")))
                    t.startsWith("> ") -> blocks.add(DocBlock.ParaBlock(inlinePara(t.removePrefix("> "))))
                    else -> blocks.add(DocBlock.ParaBlock(inlinePara(t)))
                }
            }
        }
        return DocDocument(blocks, null)
    }

    /** **bold**, *italic*, `code` inline scan; code runs are shown as-is. */
    private fun inlinePara(text: String, bullet: String? = null): Para {
        val runs = ArrayList<Run>()
        var bold = false
        var italic = false
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotEmpty()) {
                runs.add(Run(cur.toString(), DEFAULT_SIZE, BLACK, bold, italic, false))
                cur.setLength(0)
            }
        }
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> { flush(); bold = !bold; i += 2 }
                text.startsWith("__", i) -> { flush(); bold = !bold; i += 2 }
                text[i] == '*' || text[i] == '_' -> { flush(); italic = !italic; i++ }
                text[i] == '`' -> {
                    // Code span: copy literally until the closing backtick.
                    flush()
                    val end = text.indexOf('`', i + 1)
                    val code = if (end > i) text.substring(i + 1, end) else text.substring(i + 1)
                    if (code.isNotEmpty()) runs.add(Run(code, DEFAULT_SIZE, BLACK, false, false, false))
                    i = if (end > i) end + 1 else text.length
                }
                else -> { cur.append(text[i]); i++ }
            }
        }
        flush()
        if (runs.isEmpty()) runs.add(Run(text, DEFAULT_SIZE, BLACK, false, false, false))
        return Para("l", firstStrongIsRtl(text), runs, bullet, 0f, 0f, 1f, 0f, 0f, DEFAULT_SIZE)
    }
}
