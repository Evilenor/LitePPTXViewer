package com.litepptx.viewer.office

/**
 * OLE2 fallback for legacy `.doc` / `.ppt` / `.xls` files.
 * No OLE parser and no POI: the file is scanned for printable UTF-16LE and ASCII
 * runs, which recovers the readable text of most old Office files. Formatting,
 * tables and images are lost — the viewer says so up front.
 */
object LegacyOffice {

    private val OLE_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
    )

    const val MAX_CHARS = 200_000

    fun isOle(bytes: ByteArray): Boolean {
        if (bytes.size < OLE_MAGIC.size) return false
        for (i in OLE_MAGIC.indices) if (bytes[i] != OLE_MAGIC[i]) return false
        return true
    }

    /** Printable runs of length >= 4, deduplicated, joined with blank lines, capped. */
    fun extractText(bytes: ByteArray): String {
        val runs = ArrayList<String>()
        var totalLen = 0
        var i = 0
        while (i + 1 < bytes.size && totalLen < MAX_CHARS) {
            // UTF-16LE run: low byte printable, high byte 0x00 or 0x06 (Persian/Arabic).
            val wide = StringBuilder()
            var j = i
            while (j + 1 < bytes.size) {
                val lo = bytes[j].toInt() and 0xFF
                val hi = bytes[j + 1].toInt() and 0xFF
                val ch: Char? = when {
                    hi == 0x00 && lo in 0x20..0x7E -> lo.toChar()
                    hi == 0x06 && lo in 0x00..0xFF -> (0x0600 + lo).toChar()
                    hi == 0x00 && lo == 0x0D -> '\n'
                    hi == 0x00 && lo == 0x09 -> '\t'
                    else -> null
                }
                if (ch == null) break
                wide.append(ch)
                j += 2
            }
            if (wide.length >= 4) {
                totalLen += addRun(runs, wide.toString())
                i = j
                continue
            }
            // ASCII run.
            val ascii = StringBuilder()
            var k = i
            while (k < bytes.size) {
                val b = bytes[k].toInt() and 0xFF
                if (b in 0x20..0x7E || b == 0x0D || b == 0x09) {
                    ascii.append(if (b == 0x0D) '\n' else b.toChar())
                    k++
                } else break
            }
            if (ascii.length >= 4) {
                totalLen += addRun(runs, ascii.toString())
                i = k
                continue
            }
            i++
        }
        val joined = runs.joinToString("\n\n")
        return if (joined.length > MAX_CHARS) joined.substring(0, MAX_CHARS) else joined
    }

    /** Returns the length of the run added (0 when dropped). */
    private fun addRun(runs: MutableList<String>, raw: String): Int {
        val clean = raw.replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (clean.length < 4) return 0
        // OLE streams repeat the same text in several storages; drop exact repeats in a row.
        if (runs.isNotEmpty() && runs.last() == clean) return 0
        runs.add(clean)
        return clean.length
    }
}
