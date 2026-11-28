package com.litepptx.viewer.pptx

import android.graphics.Color
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

/**
 * A presentation whose slides parse on first access. Opening a 50-slide deck
 * only reads presentation.xml up front; each slide is parsed when it is shown,
 * searched or exported, and the last few stay cached. [loader] is the parser
 * that owns the master/layout caches, so it must stay alive with this Deck.
 */
class Deck(
    val wEmu: Long,
    val hEmu: Long,
    private val loader: (Int) -> Slide?,
    private val slideCount: Int,
    private val zip: ZipFile,
    private val file: File?
) {
    /**
     * Lazy slide list: [AbstractList] gives index access, iteration, size and
     * getOrNull for free, all through [get]. Every access is serialized on
     * this Deck, because the render threads, the search and the PDF export can
     * all ask for slides at once. A slide that cannot be parsed (or is asked
     * for after the zip closed) comes back as a blank slide, never a crash.
     */
    val slides: List<Slide> = LazySlides()

    private inner class LazySlides : AbstractList<Slide>() {
        private val cache = object : LinkedHashMap<Int, Slide>(8, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, Slide>): Boolean = size > 8
        }

        override val size: Int get() = slideCount

        override fun get(index: Int): Slide {
            if (index !in 0 until slideCount) throw IndexOutOfBoundsException("slide $index of $slideCount")
            synchronized(this@Deck) {
                cache[index]?.let { return it }
                val s = try {
                    loader(index)
                } catch (_: Throwable) {
                    null
                } ?: Slide(index + 1, Fill(Color.WHITE), null, emptyList())
                cache[index] = s
                return s
            }
        }

        fun clear() {
            cache.clear()
        }
    }

    fun entry(path: String) = zip.getEntry(path)?.let { zip.getInputStream(it) }

    fun close() {
        synchronized(this) { (slides as LazySlides).clear() }
        try { zip.close() } catch (_: Throwable) { }
        try { file?.delete() } catch (_: Throwable) { }
    }
}
