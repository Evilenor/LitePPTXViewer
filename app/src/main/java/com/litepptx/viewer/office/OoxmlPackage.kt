package com.litepptx.viewer.office

import android.graphics.Color
import com.litepptx.viewer.pptx.Dom
import com.litepptx.viewer.pptx.Node
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Shared OOXML package helper: one [ZipFile] plus the cache [File] it came from.
 * DOCX and XLSX parsers both read through this instead of duplicating zip/rels logic.
 * Mirrors the helpers in [com.litepptx.viewer.pptx.PptxParser] (kept untouched).
 */
class OoxmlPackage(private val zip: ZipFile, private val file: File?) {

    class Rel(val target: String, val type: String)

    private val domCache = HashMap<String, Node>()

    fun dom(path: String, cache: Boolean): Node? {
        if (cache) domCache[path]?.let { return it }
        val e = zip.getEntry(path) ?: return null
        val node = zip.getInputStream(e).use { Dom.parse(it) } ?: return null
        if (cache) domCache[path] = node
        return node
    }

    fun resolve(dir: String, target: String): String {
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

    fun rels(part: String): Map<String, Rel> {
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

    fun entry(path: String): InputStream? = zip.getEntry(path)?.let { zip.getInputStream(it) }

    fun hasEntry(path: String): Boolean = zip.getEntry(path) != null

    fun close() {
        try { zip.close() } catch (_: Throwable) { }
        try { file?.delete() } catch (_: Throwable) { }
    }
}

/** Theme colours, with the same defaults as PptxParser, plus DrawingML colour maths. */
object OoxmlColor {

    private val colorNames = setOf("srgbClr", "schemeClr", "sysClr", "prstClr")

    fun themeOf(root: Node?): Map<String, Int> {
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

    /** Colour of the first srgbClr/schemeClr/sysClr/prstClr child of [holder], or null. */
    fun colorOf(holder: Node?, theme: Map<String, Int>): Int? {
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
                theme[v] ?: return null
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

    private fun hex(s: String?): Int? = try {
        if (s == null) null else Color.parseColor("#" + s.trim())
    } catch (_: Exception) {
        null
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    /** lumMod / lumOff in HSL space (same maths as PptxParser). */
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
