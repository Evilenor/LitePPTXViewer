package com.litepptx.viewer.pptx

import android.graphics.Color
import java.io.File
import java.util.zip.ZipFile

/**
 * Lightweight PPTX reader. Resolves slide -> layout -> master inheritance for
 * placeholders, backgrounds, theme colours and text styles. Media is never loaded here
 * (it is decoded lazily by the renderer straight from the zip).
 */
class PptxParser(private val zip: ZipFile, private val file: File? = null) {

    private class Rel(val target: String, val type: String)
    private class Master(
        val root: Node,
        val rels: Map<String, Rel>,
        val theme: Map<String, Int>,
        val clrMap: Map<String, String>
    )
    private class Layout(val path: String, val root: Node, val rels: Map<String, Rel>, val master: Master)
    private class Ctx(val m: Master, val rels: Map<String, Rel>, val layout: Layout?, val isSlide: Boolean)
    private class Tf(val sx: Float = 1f, val sy: Float = 1f, val tx: Float = 0f, val ty: Float = 0f)
    private class Xf(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val rot: Float, val flipH: Boolean, val flipV: Boolean,
        val chX: Float, val chY: Float, val chW: Float, val chH: Float
    )

    private val domCache = HashMap<String, Node>()
    private val masters = HashMap<String, Master>()
    private val layouts = HashMap<String, Layout>()
    private val underCache = HashMap<String, List<El>>()
    private var presDefault: Node? = null
    private val NOFILL = Fill(0)

    private val colorNames = setOf("srgbClr", "schemeClr", "sysClr", "prstClr")

    // ------------------------------------------------------------------ entry point

    fun parse(): Deck {
        val pres = dom("ppt/presentation.xml", true)
        var cx = 12192000L
        var cy = 6858000L
        pres?.child("sldSz")?.let {
            cx = it.a("cx")?.toLongOrNull() ?: cx
            cy = it.a("cy")?.toLongOrNull() ?: cy
        }
        presDefault = pres?.child("defaultTextStyle")
        val presRels = rels("ppt/presentation.xml")

        var paths: List<String> = pres?.child("sldIdLst")?.children("sldId")
            ?.mapNotNull { n -> n.ra("id")?.let { presRels[it]?.target } } ?: emptyList()
        if (paths.isEmpty()) {
            paths = zip.entries().asSequence().map { it.name }
                .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                .sortedBy { it.removePrefix("ppt/slides/slide").removeSuffix(".xml").toIntOrNull() ?: 9999 }
                .toList()
        }

        val slides = ArrayList<Slide>(paths.size)
        for ((i, p) in paths.withIndex()) {
            parseSlide(i + 1, p)?.let { slides.add(it) }
        }
        domCache.clear()
        return Deck(cx, cy, slides, zip, file)
    }

    // ------------------------------------------------------------------ package helpers

    private fun dom(path: String, cache: Boolean): Node? {
        domCache[path]?.let { return it }
        val e = zip.getEntry(path) ?: return null
        val node = zip.getInputStream(e).use { Dom.parse(it.readBytes()) } ?: return null
        if (cache) domCache[path] = node
        return node
    }

    private fun resolve(dir: String, target: String): String {
        if (target.startsWith("/")) return target.substring(1)
        val parts = ArrayList<String>()
        if (dir.isNotEmpty()) parts.addAll(dir.split('/'))
        for (seg in target.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun rels(part: String): Map<String, Rel> {
        val dir = part.substringBeforeLast('/', "")
        val name = part.substringAfterLast('/')
        val rp = (if (dir.isEmpty()) "" else "$dir/") + "_rels/$name.rels"
        val root = dom(rp, false) ?: return emptyMap()
        val map = HashMap<String, Rel>()
        for (r in root.kids) {
            if (r.name != "Relationship" || r.a("TargetMode") == "External") continue
            val id = r.a("Id") ?: continue
            val t = r.a("Target") ?: continue
            map[id] = Rel(resolve(dir, t), r.a("Type") ?: "")
        }
        return map
    }

    // ------------------------------------------------------------------ master / layout

    private fun parseTheme(root: Node?): Map<String, Int> {
        val map = HashMap<String, Int>()
        map["dk1"] = Color.BLACK; map["lt1"] = Color.WHITE
        map["dk2"] = Color.parseColor("#44546A"); map["lt2"] = Color.parseColor("#E7E6E6")
        map["accent1"] = Color.parseColor("#4472C4"); map["accent2"] = Color.parseColor("#ED7D31")
        map["accent3"] = Color.parseColor("#A5A5A5"); map["accent4"] = Color.parseColor("#FFC000")
        map["accent5"] = Color.parseColor("#5B9BD5"); map["accent6"] = Color.parseColor("#70AD47")
        map["hlink"] = Color.parseColor("#0563C1"); map["folHlink"] = Color.parseColor("#954F72")
        val cs = root?.path("themeElements", "clrScheme") ?: return map
        for (k in cs.kids) {
            val c = k.kids.firstOrNull() ?: continue
            val v = when (c.name) {
                "srgbClr" -> hex(c.a("val"))
                "sysClr" -> hex(c.a("lastClr"))
                else -> null
            }
            if (v != null) map[k.name] = v
        }
        return map
    }

    private fun loadMaster(path: String?): Master {
        if (path != null) masters[path]?.let { return it }
        val root = path?.let { dom(it, true) }
        val rl = if (path != null) rels(path) else emptyMap()
        val themePath = rl.values.firstOrNull { it.type.endsWith("/theme") }?.target ?: "ppt/theme/theme1.xml"
        val cm = HashMap<String, String>()
        root?.child("clrMap")?.attrs?.let { cm.putAll(it) }
        val m = Master(root ?: Node("sldMaster", emptyMap()), rl, parseTheme(dom(themePath, true)), cm)
        if (path != null) masters[path] = m
        return m
    }

    private fun loadLayout(path: String): Layout? {
        layouts[path]?.let { return it }
        val root = dom(path, true) ?: return null
        val rl = rels(path)
        val mPath = rl.values.firstOrNull { it.type.endsWith("/slideMaster") }?.target
        val l = Layout(path, root, rl, loadMaster(mPath))
        layouts[path] = l
        return l
    }

    /** Non-placeholder decorations of a master or layout (logos, bars, ...). */
    private fun underElements(path: String, root: Node, rl: Map<String, Rel>, m: Master): List<El> {
        underCache[path]?.let { return it }
        val out = ArrayList<El>()
        val tree = root.path("cSld", "spTree")
        if (tree != null) parseTree(tree, Tf(), Ctx(m, rl, null, false), out)
        underCache[path] = out
        return out
    }

    // ------------------------------------------------------------------ slide

    private fun parseSlide(index: Int, path: String): Slide? {
        val root = dom(path, false) ?: return null
        val rl = rels(path)
        val layoutPath = rl.values.firstOrNull { it.type.endsWith("/slideLayout") }?.target
        val layout = layoutPath?.let { loadLayout(it) }
        val m = layout?.master ?: loadMaster(null)
        val ctx = Ctx(m, rl, layout, true)

        val els = ArrayList<El>()
        val showAll = root.a("showMasterSp") != "0"
        if (showAll && layout != null) {
            if (layout.root.a("showMasterSp") != "0") {
                val mp = masters.entries.firstOrNull { it.value === m }?.key
                if (mp != null) els.addAll(underElements(mp, m.root, m.rels, m))
            }
            els.addAll(underElements(layout.path, layout.root, layout.rels, m))
        }
        root.path("cSld", "spTree")?.let { parseTree(it, Tf(), ctx, els) }

        var bg = bgOf(root, m, rl)
        if (bg == null && layout != null) bg = bgOf(layout.root, m, layout.rels)
        if (bg == null) bg = bgOf(m.root, m, m.rels)
        val fill = bg?.first ?: if (bg?.second != null) null else Fill(scheme(m, "bg1") ?: Color.WHITE)
        return Slide(index, fill, bg?.second, els)
    }

    private fun bgOf(root: Node, m: Master, rl: Map<String, Rel>): Pair<Fill?, String?>? {
        val bg = root.path("cSld", "bg") ?: return null
        bg.child("bgPr")?.let { pr ->
            val img = pr.path("blipFill", "blip")?.ra("embed")?.let { rl[it]?.target }
            if (img != null) return Pair(null, img)
            val f = parseFill(pr, m)
            if (f === NOFILL) return Pair(Fill(Color.WHITE), null)
            if (f != null) return Pair(f, null)
        }
        bg.child("bgRef")?.let { ref ->
            colorOf(ref, m)?.let { return Pair(Fill(it), null) }
        }
        return null
    }

    // ------------------------------------------------------------------ shape tree

    private fun parseTree(tree: Node, tf: Tf, c: Ctx, out: MutableList<El>) {
        for (n in tree.kids) {
            when (n.name) {
                "sp", "cxnSp" -> parseSp(n, tf, c)?.let { out.add(it) }
                "pic" -> parsePic(n, tf, c)?.let { out.add(it) }
                "graphicFrame" -> parseFrame(n, tf, c)?.let { out.add(it) }
                "grpSp" -> {
                    val xf = xfrmOf(n.child("grpSpPr"))
                    if (xf != null && xf.chW > 0f && xf.chH > 0f) {
                        val gx = tf.sx * xf.x + tf.tx
                        val gy = tf.sy * xf.y + tf.ty
                        val sx = xf.w * tf.sx / xf.chW
                        val sy = xf.h * tf.sy / xf.chH
                        parseTree(n, Tf(sx, sy, gx - xf.chX * sx, gy - xf.chY * sy), c, out)
                    } else {
                        parseTree(n, tf, c, out)
                    }
                }
                "AlternateContent" -> (n.child("Choice") ?: n.child("Fallback"))?.let { parseTree(it, tf, c, out) }
            }
        }
    }

    private fun xfrmOf(spPr: Node?): Xf? {
        val x = spPr?.child("xfrm") ?: return null
        val off = x.child("off")
        val ext = x.child("ext")
        val cho = x.child("chOff")
        val che = x.child("chExt")
        return Xf(
            off?.f("x") ?: 0f, off?.f("y") ?: 0f, ext?.f("cx") ?: 0f, ext?.f("cy") ?: 0f,
            (x.f("rot") ?: 0f) / 60000f, x.a("flipH") == "1", x.a("flipV") == "1",
            cho?.f("x") ?: 0f, cho?.f("y") ?: 0f, che?.f("cx") ?: 0f, che?.f("cy") ?: 0f
        )
    }

    private fun place(el: El, xf: Xf, tf: Tf) {
        el.x = tf.sx * xf.x + tf.tx
        el.y = tf.sy * xf.y + tf.ty
        el.w = xf.w * tf.sx
        el.h = xf.h * tf.sy
        el.rot = xf.rot
        el.flipH = xf.flipH
        el.flipV = xf.flipV
    }

    private fun normType(t: String?): String = when (t) {
        null, "obj", "body", "subTitle", "tbl", "chart", "pic", "media" -> "body"
        "ctrTitle", "title" -> "title"
        else -> t
    }

    private fun findPh(root: Node?, type: String?, idx: String?): Node? {
        val tree = root?.path("cSld", "spTree") ?: return null
        var byType: Node? = null
        for (sp in tree.kids) {
            if (sp.name != "sp") continue
            val ph = sp.path("nvSpPr", "nvPr", "ph") ?: continue
            if (idx != null && ph.a("idx") == idx) return sp
            if (byType == null && normType(ph.a("type")) == normType(type)) byType = sp
        }
        return byType
    }

    private fun parseSp(n: Node, tf: Tf, c: Ctx): Shape? {
        val nv = n.child("nvSpPr") ?: n.child("nvCxnSpPr")
        val ph = nv?.path("nvPr", "ph")
        if (ph != null && !c.isSlide) return null
        val spPr = n.child("spPr")
        var xf = xfrmOf(spPr)
        var layoutSp: Node? = null
        var masterSp: Node? = null
        if (ph != null) {
            layoutSp = findPh(c.layout?.root, ph.a("type"), ph.a("idx"))
            val lt = layoutSp?.path("nvSpPr", "nvPr", "ph")?.a("type") ?: ph.a("type")
            masterSp = findPh(c.m.root, lt, null)
            if (xf == null) xf = xfrmOf(layoutSp?.child("spPr")) ?: xfrmOf(masterSp?.child("spPr"))
        }
        if (xf == null || (xf.w <= 0f && xf.h <= 0f)) return null

        val s = Shape()
        place(s, xf, tf)

        // geometry
        val pg = spPr?.child("prstGeom")
        if (pg != null) {
            s.geom = pg.a("prst") ?: "rect"
            pg.child("avLst")?.child("gd")?.a("fmla")?.removePrefix("val ")?.toFloatOrNull()?.let { s.adj = it }
        } else if (spPr?.child("custGeom") != null) {
            s.geom = "cust"
            s.path = custPath(spPr.child("custGeom")!!)
        } else if (n.name == "cxnSp") {
            s.geom = "line"
        }

        // fill
        val style = n.child("style")
        var f = parseFill(spPr, c.m)
        if (f == null && ph != null) f = parseFill(layoutSp?.child("spPr"), c.m) ?: parseFill(masterSp?.child("spPr"), c.m)
        if (f === NOFILL) {
            s.fill = null
        } else if (f != null) {
            s.fill = f
        } else {
            val fr = style?.child("fillRef")
            if (fr != null && (fr.i("idx") ?: 0) > 0) colorOf(fr, c.m)?.let { s.fill = Fill(it) }
        }

        // outline
        val ln = spPr?.child("ln")
        if (ln != null && ln.child("noFill") != null) {
            s.stroke = null
        } else {
            var lc: Int? = ln?.child("solidFill")?.let { colorOf(it, c.m) }
            var lw = ln?.f("w")
            if (lc == null && ln?.child("gradFill") != null) lc = parseFill(ln, c.m)?.color
            if (lc == null) {
                val lr = style?.child("lnRef")
                val idx = lr?.i("idx") ?: 0
                if (lr != null && idx > 0) {
                    lc = colorOf(lr, c.m)
                    if (lw == null) lw = 9525f * idx
                }
            }
            s.stroke = lc
            if (lw != null) s.strokeW = lw
        }

        // text
        val tx = n.child("txBody")
        if (tx != null) {
            val bps = listOfNotNull(
                tx.child("bodyPr"),
                layoutSp?.path("txBody", "bodyPr"),
                masterSp?.path("txBody", "bodyPr")
            )
            fun bp(name: String): String? = bps.firstNotNullOfOrNull { it.a(name) }
            bp("lIns")?.toFloatOrNull()?.let { s.lIns = it }
            bp("tIns")?.toFloatOrNull()?.let { s.tIns = it }
            bp("rIns")?.toFloatOrNull()?.let { s.rIns = it }
            bp("bIns")?.toFloatOrNull()?.let { s.bIns = it }
            bp("anchor")?.let { s.anchor = it }
            s.wrap = bp("wrap") != "none"
            tx.child("bodyPr")?.child("normAutofit")?.f("fontScale")?.let { s.fontScale = it / 100000f }

            val phType = ph?.let { normType(layoutSp?.path("nvSpPr", "nvPr", "ph")?.a("type") ?: it.a("type")) }
            val styleNode = c.m.root.child("txStyles")?.let {
                when (phType) {
                    null -> null
                    "title" -> it.child("titleStyle")
                    "body" -> it.child("bodyStyle")
                    else -> it.child("otherStyle")
                }
            }
            val chain = listOfNotNull(
                tx.child("lstStyle"),
                layoutSp?.path("txBody", "lstStyle"),
                masterSp?.path("txBody", "lstStyle"),
                styleNode,
                presDefault
            )
            val fontRefColor = style?.child("fontRef")?.let { colorOf(it, c.m) }
            val defColor = fontRefColor ?: scheme(c.m, "tx1") ?: Color.BLACK
            s.paras = parseParas(tx, chain, c.m, defColor, null, false)
        }
        if (s.fill == null && s.stroke == null && s.paras.isEmpty()) return null
        return s
    }

    private fun custPath(cg: Node): CustPath? {
        val p = cg.path("pathLst", "path") ?: return null
        val w = p.f("w") ?: 0f
        val h = p.f("h") ?: 0f
        if (w <= 0f || h <= 0f) return null
        val ops = ArrayList<FloatArray>()
        fun pt(n: Node?): Pair<Float, Float> = Pair(n?.f("x") ?: 0f, n?.f("y") ?: 0f)
        for (k in p.kids) {
            when (k.name) {
                "moveTo" -> { val a = pt(k.child("pt")); ops.add(floatArrayOf(0f, a.first, a.second)) }
                "lnTo" -> { val a = pt(k.child("pt")); ops.add(floatArrayOf(1f, a.first, a.second)) }
                "cubicBezTo" -> {
                    val q = k.children("pt").map { pt(it) }
                    if (q.size == 3) ops.add(floatArrayOf(2f, q[0].first, q[0].second, q[1].first, q[1].second, q[2].first, q[2].second))
                }
                "quadBezTo" -> {
                    val q = k.children("pt").map { pt(it) }
                    if (q.size == 2) ops.add(floatArrayOf(3f, q[0].first, q[0].second, q[1].first, q[1].second))
                }
                "close" -> ops.add(floatArrayOf(4f))
            }
        }
        return if (ops.isEmpty()) null else CustPath(w, h, ops)
    }

    private fun parsePic(n: Node, tf: Tf, c: Ctx): Pic? {
        val xf = xfrmOf(n.child("spPr")) ?: return null
        if (xf.w <= 0f || xf.h <= 0f) return null
        val bf = n.child("blipFill") ?: return null
        val path = bf.child("blip")?.ra("embed")?.let { c.rels[it]?.target } ?: return null
        val p = Pic()
        place(p, xf, tf)
        p.path = path
        bf.child("srcRect")?.let {
            p.cropL = (it.f("l") ?: 0f) / 100000f
            p.cropT = (it.f("t") ?: 0f) / 100000f
            p.cropR = (it.f("r") ?: 0f) / 100000f
            p.cropB = (it.f("b") ?: 0f) / 100000f
        }
        return p
    }

    // ------------------------------------------------------------------ tables

    private fun parseFrame(n: Node, tf: Tf, c: Ctx): Tbl? {
        val tbl = n.path("graphic", "graphicData", "tbl") ?: return null
        val off = n.child("xfrm")?.child("off") ?: return null
        val ext = n.child("xfrm")?.child("ext")
        val x = off.f("x") ?: 0f
        val y = off.f("y") ?: 0f
        val cols = tbl.child("tblGrid")?.children("gridCol")?.map { it.f("w") ?: 0f } ?: return null
        if (cols.isEmpty()) return null

        val pr = tbl.child("tblPr")
        val defaultStyle = pr?.child("tableStyleId")?.text?.trim() == "{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}"
        val firstRow = pr?.a("firstRow") == "1"
        val bandRow = pr?.a("bandRow") == "1"
        val accent = scheme(c.m, "accent1") ?: Color.parseColor("#4472C4")
        val tx1 = scheme(c.m, "tx1") ?: Color.BLACK
        val chain = listOfNotNull(presDefault)

        val rows = ArrayList<Row>()
        var totalH = 0f
        for ((ri, tr) in tbl.children("tr").withIndex()) {
            val cells = ArrayList<Cell>()
            for (tc in tr.children("tc")) {
                val explicit = tc.child("tcPr")?.child("solidFill")?.let { colorOf(it, c.m) }
                var fill = explicit
                var color = tx1
                var bold = false
                if (defaultStyle && explicit == null) {
                    if (firstRow && ri == 0) {
                        fill = accent; color = Color.WHITE; bold = true
                    } else {
                        val dataIdx = if (firstRow) ri - 1 else ri
                        fill = mix(accent, Color.WHITE, if (bandRow && dataIdx % 2 == 0) 0.4f else 0.2f)
                    }
                }
                cells.add(
                    Cell(
                        fill,
                        parseParas(tc.child("txBody"), chain, c.m, color, if (bold) color else null, bold),
                        tc.i("gridSpan") ?: 1,
                        tc.a("hMerge") == "1"
                    )
                )
            }
            val h = tr.f("h") ?: 0f
            totalH += h
            rows.add(Row(h, cells))
        }
        if (rows.isEmpty()) return null
        val t = Tbl(cols.toFloatArray(), rows)
        t.x = tf.sx * x + tf.tx
        t.y = tf.sy * y + tf.ty
        t.w = cols.sum() * tf.sx
        t.h = (if (totalH > 0f) totalH else (ext?.f("cy") ?: 0f)) * tf.sy
        return t
    }

    // ------------------------------------------------------------------ text

    private fun parseParas(
        tx: Node?, chain: List<Node>, m: Master, defColor: Int,
        forceColor: Int?, forceBold: Boolean
    ): List<Para> {
        if (tx == null) return emptyList()
        val out = ArrayList<Para>()
        for (p in tx.children("p")) {
            val pPr = p.child("pPr")
            val lvl = pPr?.i("lvl") ?: 0
            val lv = chain.mapNotNull { it.child("lvl${lvl + 1}pPr") }
            fun attr(name: String): String? = pPr?.a(name) ?: lv.firstNotNullOfOrNull { it.a(name) }
            fun defR(name: String): String? = lv.firstNotNullOfOrNull { it.child("defRPr")?.a(name) }
            val defRColor = lv.firstNotNullOfOrNull { it.child("defRPr")?.child("solidFill")?.let { f -> colorOf(f, m) } }

            // bullet
            var bullet: String? = null
            val buNodes = listOfNotNull(pPr) + lv
            for (b in buNodes) {
                if (b.child("buNone") != null) { bullet = null; break }
                val ch = b.child("buChar")?.a("char")
                if (ch != null) { bullet = ch; break }
                if (b.child("buAutoNum") != null) { bullet = "#"; break }
            }

            fun spc(parent: String): Float? {
                val node = pPr?.child(parent) ?: lv.firstNotNullOfOrNull { it.child(parent) } ?: return null
                return node.child("spcPts")?.f("val")?.let { it / 100f }
            }
            val pct = (pPr?.child("lnSpc") ?: lv.firstNotNullOfOrNull { it.child("lnSpc") })
                ?.child("spcPct")?.f("val")?.let { it / 100000f } ?: 1f

            val runs = ArrayList<Run>()
            for (k in p.kids) {
                when (k.name) {
                    "r", "fld" -> {
                        val text = k.child("t")?.text ?: ""
                        if (text.isEmpty()) continue
                        val rPr = k.child("rPr")
                        val size = (rPr?.f("sz") ?: defR("sz")?.toFloatOrNull() ?: 1800f) / 100f
                        val color = forceColor
                            ?: rPr?.child("solidFill")?.let { colorOf(it, m) }
                            ?: defRColor ?: defColor
                        val bold = forceBold || (rPr?.a("b") ?: defR("b")) == "1"
                        val italic = (rPr?.a("i") ?: defR("i")) == "1"
                        val u = rPr?.a("u")
                        runs.add(Run(text, size, color, bold, italic, u != null && u != "none"))
                    }
                    "br" -> {
                        val size = (k.child("rPr")?.f("sz") ?: defR("sz")?.toFloatOrNull() ?: 1800f) / 100f
                        runs.add(Run("\n", size, defColor, false, false, false))
                    }
                }
            }
            val emptySz = (p.child("endParaRPr")?.f("sz") ?: defR("sz")?.toFloatOrNull() ?: 1800f) / 100f
            out.add(
                Para(
                    attr("algn") ?: "l",
                    attr("rtl") == "1",
                    runs,
                    bullet,
                    attr("marL")?.toFloatOrNull() ?: 0f,
                    attr("indent")?.toFloatOrNull() ?: 0f,
                    pct,
                    spc("spcBef") ?: 0f,
                    spc("spcAft") ?: 0f,
                    emptySz
                )
            )
        }
        return out
    }

    // ------------------------------------------------------------------ colours & fills

    private fun parseFill(parent: Node?, m: Master): Fill? {
        if (parent == null) return null
        for (k in parent.kids) {
            when (k.name) {
                "noFill" -> return NOFILL
                "solidFill" -> return colorOf(k, m)?.let { Fill(it) }
                "pattFill" -> return colorOf(k.child("fgClr"), m)?.let { Fill(it) }
                "gradFill" -> {
                    val stops = k.child("gsLst")?.children("gs") ?: return null
                    val cols = ArrayList<Int>()
                    val pos = ArrayList<Float>()
                    for (gs in stops) {
                        val col = colorOf(gs, m) ?: continue
                        cols.add(col)
                        pos.add((gs.f("pos") ?: 0f) / 100000f)
                    }
                    if (cols.isEmpty()) return null
                    if (cols.size == 1) return Fill(cols[0])
                    val ang = (k.child("lin")?.f("ang") ?: 5400000f) / 60000f
                    return Fill(cols[0], cols.toIntArray(), pos.toFloatArray(), ang)
                }
            }
        }
        return null
    }

    private fun scheme(m: Master, name: String): Int? = m.theme[m.clrMap[name] ?: name]

    private fun hex(s: String?): Int? = try {
        if (s == null) null else Color.parseColor("#" + s.trim())
    } catch (_: Exception) {
        null
    }

    private fun colorOf(holder: Node?, m: Master): Int? {
        val c = holder?.kids?.firstOrNull { it.name in colorNames } ?: return null
        var base: Int = when (c.name) {
            "srgbClr" -> hex(c.a("val")) ?: return null
            "sysClr" -> hex(c.a("lastClr")) ?: return null
            "prstClr" -> when (c.a("val")) {
                "black" -> Color.BLACK
                "white" -> Color.WHITE
                else -> return null
            }
            else -> {
                val v = c.a("val") ?: return null
                if (v == "phClr") return null
                scheme(m, v) ?: return null
            }
        }
        var alpha = 255
        for (t in c.kids) {
            val v = (t.f("val") ?: continue) / 100000f
            when (t.name) {
                "lumMod" -> base = lum(base, v, 0f)
                "lumOff" -> base = lum(base, 1f, v)
                "alpha" -> alpha = (v * 255f).toInt().coerceIn(0, 255)
                "tint" -> base = mix(base, Color.WHITE, 1f - v)
                "shade" -> base = mix(base, Color.BLACK, 1f - v)
            }
        }
        return Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    /** lumMod / lumOff in HSL space. */
    private fun lum(color: Int, mod: Float, off: Float): Int {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        var l = (mx + mn) / 2f
        var h = 0f
        var s = 0f
        if (mx != mn) {
            val d = mx - mn
            s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
            h = when (mx) {
                r -> (g - b) / d + (if (g < b) 6f else 0f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } / 6f
        }
        l = (l * mod + off).coerceIn(0f, 1f)
        fun hue(p: Float, q: Float, tt: Float): Float {
            var t = tt
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 1f / 2f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        val rr: Float
        val gg: Float
        val bb: Float
        if (s == 0f) {
            rr = l; gg = l; bb = l
        } else {
            val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
            val p = 2f * l - q
            rr = hue(p, q, h + 1f / 3f)
            gg = hue(p, q, h)
            bb = hue(p, q, h - 1f / 3f)
        }
        return Color.rgb((rr * 255f + 0.5f).toInt(), (gg * 255f + 0.5f).toInt(), (bb * 255f + 0.5f).toInt())
    }
}
