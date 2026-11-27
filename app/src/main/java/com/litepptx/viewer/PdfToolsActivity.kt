package com.litepptx.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.litepptx.viewer.pdf.PdfActions
import com.litepptx.viewer.pdf.PdfTools
import java.io.File
import java.util.concurrent.Executors

/**
 * Compress / merge / split PDFs.
 *
 * Compress first tries a lossless rebuild (re-deflating Flate streams, classic
 * xref-table PDFs only). When that cannot shrink the file by at least 5%, the
 * user is offered a rendered rebuild at the chosen preset — which does shrink
 * it, but turns every page into an image.
 */
class PdfToolsActivity : Activity() {

    private lateinit var tvPreset: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCancel: Button

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var destroyed = false
    @Volatile private var cancelled = false
    @Volatile private var busy = false
    private var preset = PdfTools.Preset.BALANCED
    private var resultFile: File? = null
    private var splitSrc: File? = null
    private var splitName: String = "Document"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_tools)
        UiHelper.applyInsets(findViewById(R.id.root))

        tvPreset = findViewById(R.id.tvPreset)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        btnCancel = findViewById(R.id.btnCancel)

        val prefs = getSharedPreferences("lite", MODE_PRIVATE)
        preset = PdfTools.Preset.values().getOrNull(prefs.getInt("pdf_tools_preset", 1)) ?: PdfTools.Preset.BALANCED
        paintPreset()

        findViewById<View>(R.id.btnBack).setOnClickListener { onBackPressed() }
        findViewById<View>(R.id.presetRow).setOnClickListener { choosePreset() }
        findViewById<Button>(R.id.btnCompress).setOnClickListener { pickPdf(REQ_COMPRESS, true) }
        findViewById<Button>(R.id.btnMerge).setOnClickListener { pickPdf(REQ_MERGE, true) }
        findViewById<Button>(R.id.btnSplit).setOnClickListener { pickPdf(REQ_SPLIT, false) }
        btnCancel.setOnClickListener { cancelled = true }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (busy) {
            cancelled = true
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        destroyed = true
        cancelled = true
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ preset

    private fun paintPreset() {
        val name = when (preset) {
            PdfTools.Preset.KEEP -> getString(R.string.preset_keep)
            PdfTools.Preset.BALANCED -> getString(R.string.preset_balanced)
            PdfTools.Preset.SMALL -> getString(R.string.preset_small)
        }
        val desc = when (preset) {
            PdfTools.Preset.KEEP -> getString(R.string.preset_keep_desc)
            PdfTools.Preset.BALANCED -> getString(R.string.preset_balanced_desc)
            PdfTools.Preset.SMALL -> getString(R.string.preset_small_desc)
        }
        tvPreset.text = "$name — $desc"
    }

    private fun choosePreset() {
        val names = arrayOf(
            getString(R.string.preset_keep), getString(R.string.preset_balanced), getString(R.string.preset_small)
        )
        val current = PdfTools.Preset.values().indexOf(preset)
        AlertDialog.Builder(this)
            .setTitle(R.string.pdf_tools_preset)
            .setSingleChoiceItems(names, current) { dlg, which ->
                preset = PdfTools.Preset.values()[which]
                getSharedPreferences("lite", MODE_PRIVATE).edit().putInt("pdf_tools_preset", which).apply()
                paintPreset()
                dlg.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ picking

    private fun pickPdf(req: Int, multiple: Boolean) {
        if (busy) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(i, req)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.pdf_error_open, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAVE && resultCode == RESULT_OK) {
            val dest = data?.data
            val f = resultFile
            if (dest != null && f != null) {
                io.execute {
                    val ok = PdfActions.copyTo(this, f, dest)
                    ui.post { if (!destroyed) Toast.makeText(this, if (ok) R.string.saved_downloads else R.string.error_pdf, Toast.LENGTH_SHORT).show() }
                }
            }
            return
        }
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_COMPRESS -> {
                val uris = ArrayList<Uri>()
                val cd = data.clipData
                if (cd != null) for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris.add(it) }
                data.data?.let { uris.add(it) }
                if (uris.size > 1) batchCompress(uris) else uris.firstOrNull()?.let { compress(it) }
            }
            REQ_SPLIT -> data.data?.let { prepareSplit(it) }
            REQ_MERGE -> {
                val uris = ArrayList<Uri>()
                val cd = data.clipData
                if (cd != null) for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris.add(it) }
                data.data?.let { uris.add(it) }
                if (uris.size >= 2) merge(uris) else Toast.makeText(this, R.string.pdf_merge_hint, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun baseName(uri: Uri): String {
        val raw = UiHelper.displayName(this, uri) ?: "Document.pdf"
        return raw.removeSuffix(".pdf").removeSuffix(".PDF")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Document" }
    }

    /** Copies a picked PDF into cache/pdf so PdfRenderer can seek it. Returns null on failure. */
    private fun copyToCache(uri: Uri, tag: String): File? {
        return try {
            val dir = File(cacheDir, "pdf").apply { mkdirs() }
            val f = File(dir, "tools-$tag-${System.currentTimeMillis()}.pdf")
            contentResolver.openInputStream(uri)?.use { src -> f.outputStream().use { dst -> src.copyTo(dst, 64 * 1024) } }
            if (f.length() > 0) f else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun setWorking(show: Boolean, res: Int = 0, vararg args: Any) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        btnCancel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            tvStatus.visibility = View.VISIBLE
            if (res != 0) tvStatus.text = getString(res, *args)
        } else {
            tvStatus.visibility = View.GONE
        }
    }

    private fun showDone(f: File, pages: Int) {
        resultFile = f
        PdfActions.showResult(this, f, pages, f.length()) { saveResult(it) }
    }

    private fun saveResult(f: File) {
        if (Build.VERSION.SDK_INT >= 29) {
            io.execute {
                val ok = PdfActions.saveToDownloads(this, f)
                ui.post { if (!destroyed) Toast.makeText(this, if (ok) R.string.saved_downloads else R.string.error_pdf, Toast.LENGTH_SHORT).show() }
            }
        } else {
            try {
                startActivityForResult(PdfActions.createDocumentIntent(f.name), REQ_SAVE)
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ compress

    private fun compress(uri: Uri) {
        if (busy) return
        busy = true
        cancelled = false
        val name = baseName(uri)
        setWorking(true, R.string.pdf_working, 1, 1)
        io.execute {
            val src = copyToCache(uri, "src")
            var failed = false
            var outFile: File? = null
            var pages = 0
            var losslessOk = false
            var savedPct = 0
            if (src == null) {
                failed = true
            } else {
                pages = PdfTools.pageCount(src)
                if (pages <= 0) {
                    failed = true
                } else {
                    val dir = File(cacheDir, "pdf").apply { mkdirs() }
                    val out = File(dir, "${name}_compressed.pdf")
                    losslessOk = PdfTools.compressLossless(src, out)
                    if (losslessOk) {
                        savedPct = ((src.length() - out.length()) * 100 / src.length()).toInt()
                        if (savedPct >= 5) outFile = out else {
                            out.delete()
                            losslessOk = false
                        }
                    }
                    if (!losslessOk) {
                        // Keep the source around for the rebuild choice below.
                        outFile = null
                    }
                }
            }
            val srcFile = src
            val doneFile = outFile
            ui.post {
                if (destroyed) return@post
                if (failed || srcFile == null) {
                    busy = false
                    setWorking(false)
                    Toast.makeText(this, R.string.pdf_error_open, Toast.LENGTH_LONG).show()
                    return@post
                }
                if (doneFile != null) {
                    busy = false
                    setWorking(false)
                    Toast.makeText(this, getString(R.string.pdf_compress_lossless_ok, savedPct), Toast.LENGTH_LONG).show()
                    showDone(doneFile, pages)
                    return@post
                }
                // Lossless could not help: offer a rendered rebuild (pages become images).
                setWorking(false)
                AlertDialog.Builder(this)
                    .setTitle(R.string.pdf_compress_lossless_fail)
                    .setMessage(R.string.pdf_compress_rebuild_note)
                    .setPositiveButton(R.string.preset_balanced) { _, _ -> rebuild(srcFile, name, PdfTools.Preset.BALANCED, pages) }
                    .setNeutralButton(R.string.preset_small) { _, _ -> rebuild(srcFile, name, PdfTools.Preset.SMALL, pages) }
                    .setNegativeButton(R.string.cancel) { _, _ -> busy = false }
                    .setOnCancelListener { busy = false }
                    .show()
            }
        }
    }

    private fun rebuild(src: File, name: String, usePreset: PdfTools.Preset, pages: Int) {
        cancelled = false
        setWorking(true, R.string.pdf_working, 1, pages)
        io.execute {
            val dir = File(cacheDir, "pdf").apply { mkdirs() }
            val out = File(dir, "${name}_compressed.pdf")
            val res = PdfTools.compressRendered(
                this, src, out, usePreset,
                { i -> ui.post { if (!destroyed) tvStatus.text = getString(R.string.pdf_working, i, pages) } },
                { destroyed || cancelled }
            )
            ui.post {
                busy = false
                setWorking(false)
                if (destroyed) return@post
                if (res == null) {
                    Toast.makeText(this, R.string.error_pdf, Toast.LENGTH_LONG).show()
                } else {
                    showDone(out, res.pages)
                }
            }
        }
    }

    // ------------------------------------------------------------------ batch compress

    private class BatchResult(
        val name: String,
        val before: Long,
        val after: Long,
        val method: String, // "lossless" | "rendered" | "failed"
        val file: File?
    )

    /**
     * Compresses several PDFs in a row. Same per-file logic as [compress],
     * but a rendered rebuild uses the current preset directly instead of
     * asking per file. Each result can be saved from the results dialog.
     */
    private fun batchCompress(uris: List<Uri>) {
        if (busy) return
        busy = true
        cancelled = false
        val total = uris.size
        io.execute {
            val results = ArrayList<BatchResult>()
            for ((idx, uri) in uris.withIndex()) {
                if (destroyed || cancelled) break
                val name = baseName(uri)
                ui.post { if (!destroyed) setWorking(true, R.string.pdf_batch_working, idx + 1, total) }
                val src = copyToCache(uri, "b$idx")
                if (src == null || PdfTools.pageCount(src) <= 0) {
                    results += BatchResult(name, src?.length() ?: 0, src?.length() ?: 0, "failed", null)
                    continue
                }
                val before = src.length()
                val dir = File(cacheDir, "pdf").apply { mkdirs() }
                val out = File(dir, "${name}_compressed_$idx.pdf")
                var method = "failed"
                var after = before
                var done: File? = null
                if (PdfTools.compressLossless(src, out)) {
                    val savedPct = ((before - out.length()) * 100 / before).toInt()
                    if (savedPct >= 5) {
                        method = "lossless"
                        after = out.length()
                        done = out
                    } else {
                        out.delete()
                    }
                }
                if (done == null && !destroyed && !cancelled) {
                    val res = PdfTools.compressRendered(
                        this, src, out, preset,
                        { i -> ui.post { if (!destroyed) tvStatus.text = getString(R.string.pdf_batch_working, idx + 1, total) } },
                        { destroyed || cancelled }
                    )
                    if (res != null) {
                        method = "rendered"
                        after = out.length()
                        done = out
                    } else {
                        out.delete()
                    }
                }
                results += BatchResult(name, before, after, method, done)
            }
            ui.post {
                busy = false
                setWorking(false)
                if (destroyed || cancelled) return@post
                showBatchResults(results)
            }
        }
    }

    private fun showBatchResults(results: List<BatchResult>) {
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in results) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, (8 * dp).toInt(), pad, (8 * dp).toInt())
            }
            row.addView(TextView(this).apply {
                text = r.name
                setTypeface(null, Typeface.BOLD)
            })
            val method = when (r.method) {
                "lossless" -> getString(R.string.pdf_batch_method_lossless)
                "rendered" -> getString(R.string.pdf_batch_method_rendered)
                else -> getString(R.string.pdf_batch_failed)
            }
            row.addView(TextView(this).apply {
                text = if (r.file != null) {
                    getString(
                        R.string.pdf_batch_row,
                        Formatter.formatShortFileSize(this@PdfToolsActivity, r.before),
                        Formatter.formatShortFileSize(this@PdfToolsActivity, r.after),
                        method
                    )
                } else {
                    method
                }
            })
            if (r.file != null) {
                row.addView(Button(this).apply {
                    text = getString(R.string.pdf_batch_save)
                    setOnClickListener {
                        // The create-document flow on older Android saves resultFile.
                        resultFile = r.file
                        saveResult(r.file)
                    }
                })
            }
            list.addView(row)
        }
        val scroll = ScrollView(this).apply { addView(list) }
        AlertDialog.Builder(this)
            .setTitle(R.string.pdf_batch_results)
            .setView(scroll)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------------ merge

    private fun merge(uris: List<Uri>) {
        if (busy) return
        busy = true
        cancelled = false
        val name = baseName(uris[0])
        setWorking(true, R.string.pdf_working, 1, uris.size)
        io.execute {
            val files = ArrayList<File>()
            var failed = false
            for ((i, u) in uris.withIndex()) {
                val f = copyToCache(u, "m$i")
                if (f == null) { failed = true; break }
                files.add(f)
            }
            var res: PdfTools.Result? = null
            var totalPages = 0
            if (!failed) {
                totalPages = files.sumOf { PdfTools.pageCount(it) }
                val dir = File(cacheDir, "pdf").apply { mkdirs() }
                val out = File(dir, "${name}_merged.pdf")
                res = PdfTools.merge(
                    this, files, out, preset,
                    { i -> ui.post { if (!destroyed) tvStatus.text = getString(R.string.pdf_working, i, totalPages) } },
                    { destroyed || cancelled }
                )
                if (res != null) {
                    val done = out
                    val doneRes = res
                    ui.post {
                        busy = false
                        setWorking(false)
                        if (!destroyed) showDone(done, doneRes.pages)
                    }
                    return@execute
                }
            }
            ui.post {
                busy = false
                setWorking(false)
                if (!destroyed) Toast.makeText(this, R.string.error_pdf, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------ split

    private fun prepareSplit(uri: Uri) {
        if (busy) return
        busy = true
        splitName = baseName(uri)
        setWorking(true, R.string.pdf_working, 1, 1)
        io.execute {
            val src = copyToCache(uri, "split")
            val pages = if (src != null) PdfTools.pageCount(src) else 0
            ui.post {
                setWorking(false)
                if (destroyed) return@post
                if (src == null || pages <= 0) {
                    busy = false
                    Toast.makeText(this, R.string.pdf_error_open, Toast.LENGTH_LONG).show()
                    return@post
                }
                splitSrc = src
                showSplitDialog(pages)
            }
        }
    }

    private fun showSplitDialog(pages: Int) {
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * dp).toInt(), pad, 0)
        }
        val from = EditText(this).apply {
            hint = getString(R.string.pdf_split_from)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1")
        }
        val to = EditText(this).apply {
            hint = getString(R.string.pdf_split_to)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(pages.toString())
        }
        box.addView(from)
        box.addView(to)
        AlertDialog.Builder(this)
            .setTitle(R.string.pdf_tools_split)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val f = from.text.toString().toIntOrNull() ?: 0
                val t = to.text.toString().toIntOrNull() ?: 0
                if (f < 1 || t < f || t > pages) {
                    busy = false
                    Toast.makeText(this, getString(R.string.pdf_split_range_error, pages), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                split(f, t, pages)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> busy = false }
            .setOnCancelListener { busy = false }
            .show()
    }

    private fun split(from: Int, to: Int, totalPages: Int) {
        val src = splitSrc ?: run { busy = false; return }
        cancelled = false
        setWorking(true, R.string.pdf_working, 1, to - from + 1)
        io.execute {
            val dir = File(cacheDir, "pdf").apply { mkdirs() }
            val out = File(dir, "${splitName}_p$from-p$to.pdf")
            val res = PdfTools.split(
                this, src, from, to, out, preset,
                { i -> ui.post { if (!destroyed) tvStatus.text = getString(R.string.pdf_working, i, to - from + 1) } },
                { destroyed || cancelled }
            )
            ui.post {
                busy = false
                setWorking(false)
                if (destroyed) return@post
                if (res == null) {
                    Toast.makeText(this, R.string.error_pdf, Toast.LENGTH_LONG).show()
                } else {
                    showDone(out, res.pages)
                }
            }
        }
    }

    private companion object {
        const val REQ_COMPRESS = 5001
        const val REQ_MERGE = 5002
        const val REQ_SPLIT = 5003
        const val REQ_SAVE = 5004
    }
}
