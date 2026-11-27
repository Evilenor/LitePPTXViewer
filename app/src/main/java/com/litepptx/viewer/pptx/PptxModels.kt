package com.litepptx.viewer.pptx

import java.io.File
import java.util.zip.ZipFile

/** Solid colour (ARGB) or linear gradient. */
class Fill(
    val color: Int,
    val grad: IntArray? = null,
    val pos: FloatArray? = null,
    val angle: Float = 0f
)

class Run(
    val text: String,
    val sizePt: Float,
    val color: Int,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean
)

class Para(
    val align: String,
    val rtl: Boolean,
    val runs: List<Run>,
    val bullet: String?,
    val marL: Float,
    val indent: Float,
    val lnSpcPct: Float,
    val spcBefPt: Float,
    val spcAftPt: Float,
    val emptySizePt: Float
)

/** Custom geometry: ops encoded as [code, args...]; 0=move 1=line 2=cubic 3=quad 4=close. */
class CustPath(val w: Float, val h: Float, val ops: List<FloatArray>)

sealed class El {
    var x = 0f
    var y = 0f
    var w = 0f
    var h = 0f
    var rot = 0f
    var flipH = false
    var flipV = false
}

class Shape : El() {
    var geom = "rect"
    var adj = -1f
    var path: CustPath? = null
    var fill: Fill? = null
    var stroke: Int? = null
    var strokeW = 12700f
    var paras: List<Para> = emptyList()
    var anchor = "t"
    var lIns = 91440f
    var tIns = 45720f
    var rIns = 91440f
    var bIns = 45720f
    var fontScale = 1f
    var wrap = true
}

class Pic : El() {
    var path = ""
    var cropL = 0f
    var cropT = 0f
    var cropR = 0f
    var cropB = 0f
}

class Cell(
    val fill: Int?,
    val paras: List<Para>,
    val span: Int,
    val merged: Boolean
)

class Row(val h: Float, val cells: List<Cell>)

class Tbl(val colW: FloatArray, val rows: List<Row>) : El()

class Slide(
    val index: Int,
    val bg: Fill?,
    val bgImage: String?,
    val elements: List<El>,
    val notes: String? = null
) {
    /** Extracts readable text across shapes, tables and speaker notes for in-deck search. */
    fun textContent(): String {
        val sb = StringBuilder()
        fun addParas(ps: List<Para>) {
            for (p in ps) {
                for (r in p.runs) sb.append(r.text)
                sb.append(' ')
            }
        }
        for (el in elements) {
            when (el) {
                is Shape -> addParas(el.paras)
                is Tbl -> for (row in el.rows) for (cell in row.cells) if (!cell.merged) addParas(cell.paras)
                is Pic -> {}
            }
        }
        if (!notes.isNullOrBlank()) sb.append(notes)
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}

class Deck(
    val wEmu: Long,
    val hEmu: Long,
    val slides: List<Slide>,
    private val zip: ZipFile,
    private val file: File?
) {
    fun entry(path: String) = zip.getEntry(path)?.let { zip.getInputStream(it) }

    fun close() {
        try { zip.close() } catch (_: Throwable) { }
        try { file?.delete() } catch (_: Throwable) { }
    }
}
