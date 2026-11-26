package com.litepptx.viewer.pdf

import java.io.OutputStream
import java.util.Locale

/**
 * Minimal streaming PDF 1.4 writer. Pages hold exactly one image XObject, so there is no
 * re-encoding by the platform PDF stack and the output is as small as the image data allows.
 */
class PdfWriter(private val out: OutputStream, private val title: String) {

    private var pos = 0L
    // index = object number; 1 = catalog, 2 = pages, 3 = info (written last)
    private val offsets = ArrayList<Long>().apply { add(0); add(0); add(0); add(0) }
    private val pageObjs = ArrayList<Int>()

    init {
        raw("%PDF-1.4\n")
        raw(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    private fun raw(s: String) = raw(s.toByteArray(Charsets.ISO_8859_1))

    private fun raw(b: ByteArray) {
        out.write(b)
        pos += b.size
    }

    private fun newObj(): Int {
        offsets.add(pos)
        val n = offsets.size - 1
        raw("$n 0 obj\n")
        return n
    }

    private fun f(v: Float): String = String.format(Locale.US, "%.2f", v)

    /**
     * @param filter  "DCTDecode" (JPEG) or "FlateDecode"
     * @param colors  1 = gray, 3 = RGB
     * @param predictor true when Flate data carries PNG-"Up" row filters
     */
    fun addPage(
        pageW: Float, pageH: Float,
        data: ByteArray, imgW: Int, imgH: Int,
        filter: String, colors: Int, predictor: Boolean,
        x: Float, y: Float, w: Float, h: Float
    ) {
        val cs = if (colors == 1) "/DeviceGray" else "/DeviceRGB"
        val imgObj = newObj()
        val parms = if (predictor) " /DecodeParms << /Predictor 15 /Colors $colors /BitsPerComponent 8 /Columns $imgW >>" else ""
        raw(
            "<< /Type /XObject /Subtype /Image /Width $imgW /Height $imgH /ColorSpace $cs " +
                "/BitsPerComponent 8 /Filter /$filter$parms /Length ${data.size} >>\nstream\n"
        )
        raw(data)
        raw("\nendstream\nendobj\n")

        val content = "q ${f(w)} 0 0 ${f(h)} ${f(x)} ${f(y)} cm /Im0 Do Q"
        val contObj = newObj()
        raw("<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")

        val pageObj = newObj()
        raw(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(pageW)} ${f(pageH)}] " +
                "/Resources << /XObject << /Im0 $imgObj 0 R >> /ProcSet [/PDF /ImageB /ImageC] >> " +
                "/Contents $contObj 0 R >>\nendobj\n"
        )
        pageObjs.add(pageObj)
    }

    fun finish(date: String) {
        offsets[1] = pos
        raw("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        offsets[2] = pos
        val kids = pageObjs.joinToString(" ") { "$it 0 R" }
        raw("2 0 obj\n<< /Type /Pages /Count ${pageObjs.size} /Kids [$kids] >>\nendobj\n")

        offsets[3] = pos
        raw(
            "3 0 obj\n<< /Title ${pdfString(title)} /Producer (LitePPTX) /Creator (LitePPTX) " +
                "/CreationDate (D:$date) >>\nendobj\n"
        )

        val xrefPos = pos
        val n = offsets.size
        val sb = StringBuilder()
        sb.append("xref\n0 ").append(n).append("\n0000000000 65535 f \n")
        for (i in 1 until n) sb.append(String.format(Locale.US, "%010d 00000 n \n", offsets[i]))
        sb.append("trailer\n<< /Size ").append(n).append(" /Root 1 0 R /Info 3 0 R >>\nstartxref\n")
            .append(xrefPos).append("\n%%EOF\n")
        raw(sb.toString())
        out.flush()
    }

    /** UTF-16BE hex string so any title (including Persian) is valid. */
    private fun pdfString(s: String): String {
        val sb = StringBuilder("<FEFF")
        for (ch in s) sb.append(String.format(Locale.US, "%04X", ch.code))
        return sb.append('>').toString()
    }
}
