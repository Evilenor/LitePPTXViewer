package com.litepptx.viewer.office

import com.litepptx.viewer.pptx.Para

/** What kind of file the office viewer was asked to open. */
enum class OfficeKind {
    PPTX, PDF, OFFICE, UNSUPPORTED;

    companion object {
        fun fromName(name: String?): OfficeKind {
            val n = name?.substringAfterLast('/')?.substringAfterLast('\\')?.lowercase() ?: return UNSUPPORTED
            return when {
                n.endsWith(".pptx") -> PPTX
                n.endsWith(".pdf") -> PDF
                n.endsWith(".docx") || n.endsWith(".xlsx") || n.endsWith(".doc") ||
                    n.endsWith(".xls") || n.endsWith(".ppt") || n.endsWith(".txt") ||
                    n.endsWith(".csv") || n.endsWith(".rtf") || n.endsWith(".md") ||
                    n.endsWith(".markdown") -> OFFICE
                else -> UNSUPPORTED
            }
        }
    }
}

// ------------------------------------------------------------------ documents

sealed class DocBlock {
    class ParaBlock(val para: Para) : DocBlock()
    class TableBlock(val rows: List<List<DocCell>>, val colWidths: FloatArray = FloatArray(0)) : DocBlock()
    class ImageBlock(val zipPath: String, val wEmu: Long, val hEmu: Long) : DocBlock()
}

class DocCell(val fill: Int?, val paras: List<Para>, val span: Int)

class DocDocument(val blocks: List<DocBlock>, val pkg: OoxmlPackage?, val truncated: Boolean = false) {
    fun textContent(): String {
        val sb = StringBuilder()
        for (b in blocks) {
            when (b) {
                is DocBlock.ParaBlock -> {
                    for (r in b.para.runs) sb.append(r.text)
                    sb.append('\n')
                }
                is DocBlock.TableBlock -> for (row in b.rows) for (cell in row) for (p in cell.paras) {
                    for (r in p.runs) sb.append(r.text)
                    sb.append(' ')
                }
                is DocBlock.ImageBlock -> {}
            }
        }
        return sb.toString()
    }

    fun close() = pkg?.close()
}

// ------------------------------------------------------------------ spreadsheets

class SheetCell(val text: String, val bold: Boolean, val fill: Int?, val align: String)

class SheetRow(val index: Int, val cells: Map<Int, SheetCell>, val heightPt: Float)

class Sheet(
    val name: String,
    val colWidths: FloatArray, // in characters
    val rows: List<SheetRow>,
    val merged: List<IntArray>, // r1, c1, r2, c2 (0-based)
    val maxCol: Int,
    val truncated: Boolean = false
) {
    fun textContent(): String {
        val sb = StringBuilder()
        for (row in rows) {
            val top = row.cells.keys.maxOrNull() ?: -1
            for (c in 0..top) {
                row.cells[c]?.let { sb.append(it.text) }
                sb.append('\t')
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}

class Workbook(val sheets: List<Sheet>, val pkg: OoxmlPackage?, val truncated: Boolean = false) {
    fun textContent(): String {
        val sb = StringBuilder()
        for (s in sheets) {
            sb.append(s.name).append('\n')
            sb.append(s.textContent())
        }
        return sb.toString()
    }

    fun close() = pkg?.close()
}
