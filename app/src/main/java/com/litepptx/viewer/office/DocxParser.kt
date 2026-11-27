package com.litepptx.viewer.office

import android.graphics.Color
import com.litepptx.viewer.pptx.Node
import com.litepptx.viewer.pptx.Para
import com.litepptx.viewer.pptx.Run

/**
 * Lightweight DOCX reader: paragraphs, styles, numbering, tables and inline images.
 * Runs are the same [Para]/[Run] model as the PPTX viewer, so the viewer renders
 * both formats with one code path. Images are never decoded here (lazy, from the zip).
 * One bad paragraph or table is skipped, never the whole document.
 */
object DocxParser {

    const val MAX_BLOCKS = 2000
    private const val TWIP_TO_EMU = 635f // 1 twip = 1/20 pt; 1 pt = 12700 EMU

    private class StyleInfo(val sizePt: Float?, val bold: Boolean, val color: Int?)

    fun parse(pkg: OoxmlPackage): DocDocument {
        val theme = OoxmlColor.themeOf(pkg.dom("word/theme/theme1.xml", true))
        val rels = pkg.rels("word/document.xml")
        val styles = parseStyles(pkg)
        val numbering = parseNumbering(pkg)
        val counters = HashMap<String, Int>()
        val root = pkg.dom("word/document.xml", false)
        val body = root?.child("body")
        val blocks = ArrayList<DocBlock>()
        var truncated = false
        if (body != null) {
            for (kid in body.kids) {
                if (blocks.size >= MAX_BLOCKS) { truncated = true; break }
                try {
                    when (kid.name) {
                        "p" -> blocks.addAll(parseParaBlocks(kid, styles, numbering, counters, rels, theme))
                        "tbl" -> parseTable(kid, styles, numbering, counters, rels, theme)?.let { blocks.add(it) }
                        "sectPr" -> {}
                        else -> {}
                    }
                } catch (_: Throwable) {
                    // Skip one bad block and keep the rest of the document.
                }
            }
        }
        return DocDocument(blocks, pkg, truncated)
    }

    // ------------------------------------------------------------------ styles / numbering

    private fun parseStyles(pkg: OoxmlPackage): Map<String, StyleInfo> {
        val root = pkg.dom("word/styles.xml", true) ?: return emptyMap()
        val map = HashMap<String, StyleInfo>()
        for (st in root.children("style")) {
            val id = st.a("styleId") ?: continue
            val rPr = st.child("rPr")
            val size = rPr?.child("sz")?.f("val")?.let { it / 2f }
            val bold = rPr?.child("b") != null
            val color = rPr?.child("color")?.let { c ->
                val v = c.a("val")
                if (v == null || v == "auto") null
                else try { Color.parseColor("#$v") } catch (_: Exception) { null }
            }
            val info = StyleInfo(size, bold, color)
            map[id] = info
            st.child("name")?.a("val")?.let { map[it.lowercase()] = info }
            // w:basedOn chains are ignored: Word's built-in styles carry their own sizes.
        }
        return map
    }

    /** numId -> (ilvl -> format): "bullet", "decimal", ... */
    private fun parseNumbering(pkg: OoxmlPackage): Map<String, Map<Int, String>> {
        val root = pkg.dom("word/numbering.xml", true) ?: return emptyMap()
        val abstracts = HashMap<String, Map<Int, String>>()
        for (abs in root.children("abstractNum")) {
            val id = abs.a("abstractNumId") ?: continue
            val lvls = HashMap<Int, String>()
            for (lvl in abs.children("lvl")) {
                val ilvl = lvl.i("ilvl") ?: 0
                lvls[ilvl] = lvl.child("numFmt")?.a("val") ?: "bullet"
            }
            abstracts[id] = lvls
        }
        val out = HashMap<String, Map<Int, String>>()
        for (num in root.children("num")) {
            val id = num.a("numId") ?: continue
            val absId = num.child("abstractNumId")?.a("val") ?: continue
            abstracts[absId]?.let { out[id] = it }
        }
        return out
    }

    // ------------------------------------------------------------------ paragraphs

    /** A paragraph yields its text block plus one image block per embedded picture. */
    private fun parseParaBlocks(
        p: Node, styles: Map<String, StyleInfo>, numbering: Map<String, Map<Int, String>>,
        counters: HashMap<String, Int>, rels: Map<String, OoxmlPackage.Rel>, theme: Map<String, Int>
    ): List<DocBlock> {
        val out = ArrayList<DocBlock>()
        val pPr = p.child("pPr")
        val styleId = pPr?.child("pStyle")?.a("val")
        val style = styleId?.let { styles[it] } ?: styleId?.lowercase()?.let { styles[it] }
        val baseSize = style?.sizePt ?: headingSize(styleId)
        val baseBold = style?.bold ?: (styleId != null && headingSize(styleId) != null)
        val baseColor = style?.color ?: Color.BLACK

        var align = "l"
        var rtl = false
        var marL = 0f
        var indent = 0f
        var spcBef = 0f
        var spcAft = 0f
        var bullet: String? = null
        if (pPr != null) {
            when (pPr.child("jc")?.a("val")) {
                "center" -> align = "ctr"
                "right", "end" -> align = "r"
                "both", "distribute" -> align = "l"
                "left", "start" -> align = "l"
            }
            rtl = pPr.child("bidi") != null
            pPr.child("ind")?.let { ind ->
                marL = (ind.f("left") ?: 0f) * TWIP_TO_EMU
                indent = (ind.f("firstLine") ?: 0f) * TWIP_TO_EMU
            }
            pPr.child("spacing")?.let { sp ->
                spcBef = (sp.f("before") ?: 0f) / 20f
                spcAft = (sp.f("after") ?: 0f) / 20f
            }
            pPr.child("numPr")?.let { numPr ->
                val numId = numPr.child("numId")?.a("val")
                val ilvl = numPr.child("ilvl")?.a("val")?.toIntOrNull() ?: 0
                val fmt = numId?.let { numbering[it]?.get(ilvl) }
                bullet = when (fmt) {
                    "bullet" -> "•"
                    "decimal", "decimalZero", "upperRoman", "lowerRoman",
                    "upperLetter", "lowerLetter" -> {
                        val key = "$numId/$ilvl"
                        val n = (counters[key] ?: 0) + 1
                        counters[key] = n
                        "$n."
                    }
                    null -> "•"
                    else -> "•"
                }
            }
        }

        val runs = ArrayList<Run>()
        val images = ArrayList<DocBlock.ImageBlock>()
        fun addRuns(node: Node) {
            for (k in node.kids) {
                when (k.name) {
                    "r" -> parseRun(k, baseSize, baseBold, baseColor, theme)?.let { runs.add(it) }
                    "hyperlink", "smartTag", "ins", "sdt", "sdtContent" -> addRuns(k)
                    "drawing" -> parseDrawing(k, rels)?.let { images.add(it) }
                    "pict" -> parsePict(k, rels)?.let { images.add(it) }
                    "bookmarkStart", "bookmarkEnd" -> {}
                    else -> {}
                }
            }
        }
        addRuns(p)
        // A paragraph that is only pictures still needs a (possibly empty) text block
        // so the images keep their place in the flow; the viewer skips empty paras.
        out.add(
            DocBlock.ParaBlock(
                Para(align, rtl, runs, bullet, marL, indent, 1f, spcBef, spcAft, baseSize)
            )
        )
        out.addAll(images)
        return out
    }

    private fun headingSize(styleId: String?): Float? = when (styleId?.lowercase()) {
        "title" -> 26f
        "heading1", "heading 1" -> 16f
        "heading2", "heading 2" -> 14f
        "heading3", "heading 3" -> 12f
        "subtitle" -> 14f
        else -> null
    }

    private fun parseRun(
        r: Node, baseSize: Float, baseBold: Boolean, baseColor: Int, theme: Map<String, Int>
    ): Run? {
        val rPr = r.child("rPr")
        val text = StringBuilder()
        for (k in r.kids) {
            when (k.name) {
                "t" -> text.append(k.text)
                "tab" -> text.append('\t')
                "br" -> text.append('\n')
                "cr" -> text.append('\n')
                else -> {}
            }
        }
        if (text.isEmpty()) return null
        val size = rPr?.child("sz")?.f("val")?.let { it / 2f } ?: baseSize
        var color = baseColor
        rPr?.child("color")?.let { c ->
            val v = c.a("val")
            if (v != null && v != "auto") {
                try { color = Color.parseColor("#$v") } catch (_: Exception) { }
            }
        }
        if (color == baseColor) {
            rPr?.child("solidFill")?.let { OoxmlColor.colorOf(it, theme)?.let { color = it } }
        }
        val bold = baseBold || rPr?.child("b") != null
        val italic = rPr?.child("i") != null
        val u = rPr?.child("u")?.a("val")
        return Run(text.toString(), size, color, bold, italic, u != null && u != "none")
    }

    private fun parseDrawing(drawing: Node, rels: Map<String, OoxmlPackage.Rel>): DocBlock.ImageBlock? {
        val wrap = drawing.child("inline") ?: drawing.child("anchor") ?: return null
        val extent = wrap.child("extent") ?: return null
        val cx = extent.a("cx")?.toLongOrNull() ?: return null
        val cy = extent.a("cy")?.toLongOrNull() ?: return null
        val blip = wrap.path("graphic", "graphicData", "pic", "blipFill", "blip") ?: return null
        val path = blip.ra("embed")?.let { rels[it]?.target } ?: return null
        return DocBlock.ImageBlock(path, cx, cy)
    }

    private fun parsePict(pict: Node, rels: Map<String, OoxmlPackage.Rel>): DocBlock.ImageBlock? {
        val data = pict.path("pict", "imagedata") ?: pict.child("imagedata") ?: return null
        val path = data.ra("id")?.let { rels[it]?.target } ?: return null
        // VML shape style is in points ("width:120pt;height:80pt").
        val style = pict.child("shape")?.a("style") ?: ""
        var wEmu = 0L
        var hEmu = 0L
        for (part in style.split(';')) {
            val kv = part.split(':')
            if (kv.size != 2) continue
            val pt = kv[1].trim().removeSuffix("pt").toFloatOrNull() ?: continue
            when (kv[0].trim()) {
                "width" -> wEmu = (pt * 12700f).toLong()
                "height" -> hEmu = (pt * 12700f).toLong()
            }
        }
        return DocBlock.ImageBlock(path, wEmu, hEmu)
    }

    // ------------------------------------------------------------------ tables

    private fun parseTable(
        tbl: Node, styles: Map<String, StyleInfo>, numbering: Map<String, Map<Int, String>>,
        counters: HashMap<String, Int>, rels: Map<String, OoxmlPackage.Rel>, theme: Map<String, Int>
    ): DocBlock.TableBlock? {
        val grid = tbl.child("tblGrid")?.children("gridCol")?.map { it.f("w") ?: 0f } ?: emptyList()
        val rows = ArrayList<List<DocCell>>()
        for (tr in tbl.children("tr")) {
            val cells = ArrayList<DocCell>()
            for (tc in tr.children("tc")) {
                try {
                    val tcPr = tc.child("tcPr")
                    val fill = tcPr?.child("shd")?.a("fill")?.let { v ->
                        if (v == "auto") null else try { Color.parseColor("#$v") } catch (_: Exception) { null }
                    }
                    val paras = ArrayList<Para>()
                    for (p in tc.children("p")) {
                        paras.addAll(parseParaBlocks(p, styles, numbering, counters, rels, theme).mapNotNull {
                            (it as? DocBlock.ParaBlock)?.para
                        })
                    }
                    cells.add(DocCell(fill, paras, tcPr?.child("gridSpan")?.a("val")?.toIntOrNull() ?: 1))
                } catch (_: Throwable) {
                    // Skip one bad cell.
                }
            }
            if (cells.isNotEmpty()) rows.add(cells)
        }
        if (rows.isEmpty()) return null
        return DocBlock.TableBlock(rows, grid.toFloatArray())
    }
}
