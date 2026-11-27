package com.litepptx.viewer.office

import android.graphics.Color
import com.litepptx.viewer.pptx.Node
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Lightweight XLSX reader: workbook, shared strings, cell styles and sheet XML.
 * Numbers are formatted like Excel (dates, percents, plain numbers); formulas show
 * their cached value. One bad row or cell is skipped, never the whole sheet.
 */
object XlsxParser {

    const val MAX_ROWS = 5000
    const val MAX_COLS = 100
    private const val DEFAULT_COL_WIDTH = 8.43f

    private val DATE_FMT_IDS = setOf(
        14, 15, 16, 17, 22, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36,
        45, 46, 47, 48, 49, 50, 57
    )
    private val PERCENT_FMT_IDS = setOf(9, 10)

    private class Xf(val bold: Boolean, val fill: Int?, val align: String, val numFmtId: Int)

    fun parse(pkg: OoxmlPackage): Workbook {
        val wbRels = pkg.rels("xl/workbook.xml")
        val wbRoot = pkg.dom("xl/workbook.xml", true)
        val shared = parseSharedStrings(pkg)
        val styles = parseStyles(pkg)
        val sheets = ArrayList<Sheet>()
        var anyTruncated = false
        val sheetNodes = wbRoot?.child("sheets")?.children("sheet") ?: emptyList()
        for (sn in sheetNodes) {
            try {
                val name = sn.a("name") ?: "Sheet"
                val target = sn.ra("id")?.let { wbRels[it]?.target } ?: continue
                parseSheet(pkg, target, name, shared, styles)?.let {
                    sheets.add(it)
                    if (it.truncated) anyTruncated = true
                }
            } catch (_: Throwable) {
                // Skip one bad sheet.
            }
        }
        if (sheets.isEmpty()) {
            // Fall back to scanning the zip listing order when workbook.xml is odd.
            var i = 1
            while (pkg.hasEntry("xl/worksheets/sheet$i.xml")) {
                try {
                    parseSheet(pkg, "xl/worksheets/sheet$i.xml", "Sheet$i", shared, styles)?.let {
                        sheets.add(it)
                        if (it.truncated) anyTruncated = true
                    }
                } catch (_: Throwable) {
                }
                i++
            }
        }
        return Workbook(sheets, pkg, anyTruncated)
    }

    // ------------------------------------------------------------------ shared strings / styles

    private fun parseSharedStrings(pkg: OoxmlPackage): List<String> {
        val root = pkg.dom("xl/sharedStrings.xml", true) ?: return emptyList()
        val out = ArrayList<String>()
        for (si in root.children("si")) {
            val sb = StringBuilder()
            collectT(si, sb)
            out.add(sb.toString())
        }
        return out
    }

    private fun collectT(n: Node, sb: StringBuilder) {
        if (n.name == "t") sb.append(n.text)
        for (k in n.kids) collectT(k, sb)
    }

    private fun parseStyles(pkg: OoxmlPackage): List<Xf> {
        numFmtCodes.clear()
        val root = pkg.dom("xl/styles.xml", true) ?: return emptyList()
        val fonts = ArrayList<Boolean>()
        for (f in root.child("fonts")?.children("font") ?: emptyList()) {
            fonts.add(f.child("b") != null)
        }
        val fills = ArrayList<Int?>()
        for (fl in root.child("fills")?.children("fill") ?: emptyList()) {
            var c: Int? = null
            fl.child("patternFill")?.child("fgColor")?.a("rgb")?.let { rgb ->
                if (rgb.length == 8) {
                    try { c = Color.parseColor("#$rgb") } catch (_: Exception) { }
                }
            }
            fills.add(c)
        }
        val numFmts = HashMap<Int, String>()
        for (nf in root.child("numFmts")?.children("numFmt") ?: emptyList()) {
            val id = nf.i("numFmtId") ?: continue
            numFmts[id] = nf.a("formatCode") ?: ""
        }
        val out = ArrayList<Xf>()
        for (xf in root.child("cellXfs")?.children("xf") ?: emptyList()) {
            val fontId = xf.i("fontId") ?: 0
            val fillId = xf.i("fillId") ?: 0
            val numFmtId = xf.i("numFmtId") ?: 0
            val align = xf.child("alignment")?.a("horizontal") ?: ""
            out.add(
                Xf(
                    bold = fonts.getOrNull(fontId) ?: false,
                    fill = fills.getOrNull(fillId),
                    align = when (align) {
                        "center", "centerContinuous" -> "ctr"
                        "right" -> "r"
                        else -> "l"
                    },
                    numFmtId = numFmtId
                )
            )
        }
        // Keep the custom format codes alongside the xf list via a parallel lookup.
        numFmtCodes.putAll(numFmts)
        return out
    }

    private val numFmtCodes = HashMap<Int, String>()

    // ------------------------------------------------------------------ sheets

    private fun parseSheet(
        pkg: OoxmlPackage, path: String, name: String,
        shared: List<String>, styles: List<Xf>
    ): Sheet? {
        val root = pkg.dom(path, false) ?: return null
        val cols = FloatArray(MAX_COLS) { DEFAULT_COL_WIDTH }
        root.child("cols")?.children("col")?.forEach { col ->
            val w = col.f("width") ?: return@forEach
            val min = (col.i("min") ?: 1) - 1
            val max = (col.i("max") ?: 1) - 1
            for (c in min..max) if (c in 0 until MAX_COLS) cols[c] = w
        }
        val merged = ArrayList<IntArray>()
        root.child("mergeCells")?.children("mergeCell")?.forEach { mc ->
            parseRef(mc.a("ref"))?.let { merged.add(it) }
        }
        val rows = ArrayList<SheetRow>()
        var maxCol = -1
        var truncated = false
        val sheetData = root.child("sheetData") ?: return Sheet(name, cols, rows, merged, 0)
        for (rowNode in sheetData.children("row")) {
            val rIdx = (rowNode.i("r") ?: (rows.size + 1)) - 1
            if (rIdx >= MAX_ROWS) { truncated = true; break }
            val cells = HashMap<Int, SheetCell>()
            for (c in rowNode.children("c")) {
                try {
                    val ref = c.a("r") ?: continue
                    val colIdx = colOf(ref)
                    if (colIdx < 0 || colIdx >= MAX_COLS) continue
                    val xf = c.i("s")?.let { styles.getOrNull(it) }
                    val text = cellText(c, shared, xf)
                    if (text.isEmpty() && xf?.fill == null && xf?.bold != true) continue
                    cells[colIdx] = SheetCell(text, xf?.bold ?: false, xf?.fill, xf?.align ?: "l")
                    if (colIdx > maxCol) maxCol = colIdx
                } catch (_: Throwable) {
                    // Skip one bad cell.
                }
            }
            if (cells.isNotEmpty()) {
                rows.add(SheetRow(rIdx, cells, rowNode.f("ht") ?: 0f))
            }
        }
        return Sheet(name, cols, rows, merged, maxCol + 1, truncated)
    }

    private fun cellText(c: Node, shared: List<String>, xf: Xf?): String {
        val t = c.a("t") ?: "n"
        val vNode = c.child("v")
        return when (t) {
            "s" -> {
                val idx = vNode?.text?.toIntOrNull() ?: return ""
                shared.getOrNull(idx) ?: ""
            }
            "inlineStr" -> {
                val sb = StringBuilder()
                c.child("is")?.let { collectT(it, sb) }
                sb.toString()
            }
            "str" -> vNode?.text ?: ""
            "b" -> if (vNode?.text == "1" || vNode?.text == "true") "TRUE" else "FALSE"
            "e" -> vNode?.text ?: ""
            else -> {
                val raw = vNode?.text ?: c.child("f")?.text ?: return ""
                val num = raw.toDoubleOrNull() ?: return raw
                formatNumber(num, xf?.numFmtId ?: 0)
            }
        }
    }

    fun formatNumber(value: Double, numFmtId: Int): String {
        val code = numFmtCodes[numFmtId] ?: ""
        val isDate = numFmtId in DATE_FMT_IDS ||
            (code.isNotEmpty() && !code.contains('%') && looksLikeDateCode(code))
        if (isDate) return formatDate(value)
        if (numFmtId in PERCENT_FMT_IDS || code.contains('%')) {
            return plainNumber(value * 100) + "%"
        }
        return plainNumber(value)
    }

    private fun looksLikeDateCode(code: String): Boolean {
        val c = code.lowercase()
        return (c.contains('y') || c.contains('m')) && c.contains('d') ||
            (c.contains('y') && c.contains('m'))
    }

    private fun plainNumber(v: Double): String {
        return if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 9e15) {
            v.toLong().toString()
        } else {
            v.toString()
        }
    }

    /** Excel serial days since 1899-12-30 (the 1900 leap-year bug is baked into the epoch). */
    private fun formatDate(serial: Double): String {
        return try {
            val days = kotlin.math.floor(serial).toLong()
            val frac = serial - days
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.set(1899, Calendar.DECEMBER, 30, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.DATE, days.toInt())
            val datePart = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(cal.time)
            if (frac > 0.00001) {
                val mins = (frac * 24 * 60).toInt()
                val hh = (mins / 60).toString().padStart(2, '0')
                val mm = (mins % 60).toString().padStart(2, '0')
                "$datePart $hh:$mm"
            } else {
                datePart
            }
        } catch (_: Exception) {
            plainNumber(serial)
        }
    }

    // ------------------------------------------------------------------ refs

    /** "A1" -> column index 0-based; "AA12" -> 26. */
    fun colOf(ref: String): Int {
        var col = 0
        var seen = false
        for (ch in ref) {
            if (ch in 'A'..'Z') {
                col = col * 26 + (ch - 'A' + 1)
                seen = true
            } else if (ch in 'a'..'z') {
                col = col * 26 + (ch - 'a' + 1)
                seen = true
            } else break
        }
        return if (seen) col - 1 else -1
    }

    private fun rowOf(ref: String): Int {
        val digits = ref.filter { it.isDigit() }
        return (digits.toIntOrNull() ?: 1) - 1
    }

    /** "A1:B2" -> intArray(r1, c1, r2, c2), 0-based. */
    private fun parseRef(ref: String?): IntArray? {
        if (ref == null) return null
        val parts = ref.split(':')
        if (parts.size != 2) return null
        return intArrayOf(rowOf(parts[0]), colOf(parts[0]), rowOf(parts[1]), colOf(parts[1]))
    }
}
