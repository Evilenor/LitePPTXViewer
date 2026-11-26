package com.litepptx.viewer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.View
import android.view.WindowInsets
import android.widget.AbsListView
import android.widget.Button
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.litepptx.viewer.pdf.PdfActions
import com.litepptx.viewer.pdf.PptxPdfExporter
import com.litepptx.viewer.pptx.Deck
import com.litepptx.viewer.pptx.PptxParser
import com.litepptx.viewer.update.Updater
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile

class MainActivity : Activity() {

    private lateinit var tvFileName: TextView
    private lateinit var tvSlideCounter: TextView
    private lateinit var list: ZoomableListView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyState: View
    private lateinit var updateBar: View
    private lateinit var tvUpdate: TextView
    private lateinit var btnUpdate: Button
    private lateinit var moreBadge: View
    private val net = Executors.newSingleThreadExecutor()
    private val exportPool = Executors.newSingleThreadExecutor()
    @Volatile private var exportBusy = false
    @Volatile private var exportCancel = false
    private var exportFile: File? = null
    private var updInfo: Updater.Info? = null
    private var updFile: File? = null
    @Volatile private var updBusy = false
    @Volatile private var updChecking = false
    private var pendingInstall = false
    @Volatile private var updCancel = false

    private lateinit var donateCard: View
    private lateinit var ivHeart: View

    /** A slow double-beat on the heart, only while the start screen is visible. */
    private val heartbeat = object : Runnable {
        override fun run() {
            if (isFinishing || !donateCard.isShown) {
                ui.postDelayed(this, 1500)
                return
            }
            ivHeart.animate().scaleX(1.22f).scaleY(1.22f).setDuration(260).withEndAction {
                ivHeart.animate().scaleX(1f).scaleY(1f).setDuration(260).withEndAction {
                    ivHeart.animate().scaleX(1.12f).scaleY(1.12f).setDuration(200).withEndAction {
                        ivHeart.animate().scaleX(1f).scaleY(1f).setDuration(300).withEndAction {
                            ui.postDelayed(this, 1400)
                        }.start()
                    }.start()
                }.start()
            }.start()
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var token = 0
    private var currentUri: Uri? = null
    private var deck: Deck? = null
    private var adapter: SlideAdapter? = null
    private var slideCount = 0

    private val hideCounter = Runnable {
        tvSlideCounter.animate().alpha(0f).setDuration(250).withEndAction {
            tvSlideCounter.visibility = View.GONE
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvFileName = findViewById(R.id.tvFileName)
        tvSlideCounter = findViewById(R.id.tvSlideCounter)
        list = findViewById(R.id.listSlides)
        progressBar = findViewById(R.id.progressBar)
        emptyState = findViewById(R.id.emptyState)
        updateBar = findViewById(R.id.updateBar)
        tvUpdate = findViewById(R.id.tvUpdate)
        btnUpdate = findViewById(R.id.btnUpdate)
        moreBadge = findViewById(R.id.moreBadge)
        btnUpdate.setOnClickListener { onUpdateClick() }
        donateCard = findViewById(R.id.donateCard)
        ivHeart = findViewById(R.id.ivHeart)
        donateCard.setOnClickListener { openDonate() }

        // Edge-to-edge is enforced on Android 15, so keep the bars clear of system UI.
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnMore).setOnClickListener { showMenu(it) }
        findViewById<Button>(R.id.btnOpenFile).setOnClickListener { pickFile() }
        findViewById<Button>(R.id.btnImagesPdf).setOnClickListener { openPdfMaker() }
        findViewById<View>(R.id.btnScan).setOnClickListener { openScanner() }

        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {}
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                if (slideCount == 0) return
                val c0 = view.getChildAt(0) ?: return
                var pos = first
                if (c0.bottom < c0.height / 2) pos++
                pos = pos.coerceIn(0, slideCount - 1)
                showCounter("${pos + 1} / $slideCount")
            }
        })

        // Remove files left behind by an earlier, killed process.
        cacheDir.listFiles()?.forEach { if (it.name.startsWith("doc-")) it.delete() }

        val saved = savedInstanceState?.getString("uri")
        val uri = uriFrom(intent) ?: saved?.let { Uri.parse(it) }
        if (uri != null) load(uri)

        // Show a known pending update straight away (and a finished download), then let onResume
        // look for a newer one. Nothing is forgotten when the app is closed.
        Updater.cleanup(this)
        Updater.loadPending(this)?.let { showUpdate(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        uriFrom(intent)?.let { load(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentUri?.let { outState.putString("uri", it.toString()) }
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(heartbeat)
        ui.postDelayed(heartbeat, 900)
        // Back from Android's "allow installs from this app" screen: carry on with the install.
        val ready = updFile
        if (pendingInstall && ready != null && Updater.canInstall(this)) {
            pendingInstall = false
            install(ready)
        } else {
            checkUpdate(false)
        }
    }

    override fun onPause() {
        ui.removeCallbacks(heartbeat)
        ivHeart.animate().cancel()
        ivHeart.scaleX = 1f
        ivHeart.scaleY = 1f
        super.onPause()
    }

    override fun onDestroy() {
        token++
        exportCancel = true
        ui.removeCallbacksAndMessages(null)
        adapter?.release()
        deck?.close()
        io.shutdownNow()
        net.shutdownNow()
        exportPool.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ intents

    private fun uriFrom(i: Intent?): Uri? {
        if (i == null) return null
        if (i.action == Intent.ACTION_SEND) {
            return if (Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
        }
        return i.data
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/octet-stream"
                )
            )
        }
        try {
            startActivityForResult(i, REQ_OPEN)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) data?.data?.let { load(it) }
        if (requestCode == REQ_SAVE && resultCode == RESULT_OK) {
            val dest = data?.data
            val f = exportFile
            if (dest != null && f != null) {
                try {
                    exportPool.execute {
                        val ok = PdfActions.copyTo(this, f, dest)
                        ui.post { toastSaved(ok) }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.viewer_menu, popup.menu)
        // While an update is waiting, the menu entry becomes the action itself.
        updInfo?.let { u ->
            popup.menu.findItem(R.id.action_check_update)?.title = getString(
                if (updFile != null) R.string.menu_install_update else R.string.menu_get_update, u.version
            )
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_open -> { pickFile(); true }
                R.id.action_images_pdf -> { openPdfMaker(); true }
                R.id.action_scan -> { openScanner(); true }
                R.id.action_export_pdf -> { exportPdf(); true }
                R.id.action_check_update -> { if (updInfo != null) onUpdateClick() else checkUpdate(true); true }
                R.id.action_donate -> { openDonate(); true }
                R.id.action_share -> { share(); true }
                R.id.action_reset_zoom -> { list.resetZoom(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun openPdfMaker() {
        startActivity(Intent(this, PdfMakerActivity::class.java))
    }

    private fun openScanner() {
        startActivity(Intent(this, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_AUTO, true))
    }

    // ------------------------------------------------------------------ export as PDF

    private fun exportPdf() {
        val d = deck
        if (d == null) {
            Toast.makeText(this, R.string.export_open_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (exportBusy) return
        exportBusy = true
        exportCancel = false
        val n = d.slides.size
        val base = (displayName(currentUri ?: Uri.EMPTY) ?: "")
            .removeSuffix(".pptx").removeSuffix(".PPTX")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Presentation" }
        val name = "$base.pdf"
        val my = token
        val dlg = android.app.AlertDialog.Builder(this)
            .setMessage(getString(R.string.export_working, 1, n))
            .setNegativeButton(R.string.cancel) { _, _ -> exportCancel = true }
            .setCancelable(false)
            .create()
        dlg.show()
        try {
            exportPool.execute {
                val dir = File(cacheDir, "pdf").apply { mkdirs() }
                dir.listFiles()?.forEach {
                    if (System.currentTimeMillis() - it.lastModified() > 3_600_000L) it.delete()
                }
                val f = File(dir, name)
                var res: PptxPdfExporter.Result? = null
                var failed = false
                try {
                    res = PptxPdfExporter.export(d, f, base,
                        { i -> ui.post { if (dlg.isShowing) dlg.setMessage(getString(R.string.export_working, i + 1, n)) } },
                        { exportCancel || isFinishing || token != my })
                } catch (t: Throwable) {
                    android.util.Log.w("LitePPTX", "export failed", t)
                    failed = true
                }
                val r = res
                ui.post {
                    exportBusy = false
                    try { dlg.dismiss() } catch (_: Exception) {}
                    when {
                        isFinishing || token != my -> {}
                        failed -> Toast.makeText(this, R.string.error_pdf, Toast.LENGTH_LONG).show()
                        r != null -> showExported(f, r)
                    }
                }
            }
        } catch (_: Exception) {
            exportBusy = false
            try { dlg.dismiss() } catch (_: Exception) {}
        }
    }

    private fun showExported(f: File, r: PptxPdfExporter.Result) {
        exportFile = f
        PdfActions.showResult(this, f, r.pages, r.bytes) { saveExported(it) }
    }

    private fun saveExported(f: File) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                exportPool.execute {
                    val ok = PdfActions.saveToDownloads(this, f)
                    ui.post { toastSaved(ok) }
                }
            } catch (_: Exception) {
            }
        } else {
            try {
                startActivityForResult(PdfActions.createDocumentIntent(f.name), REQ_SAVE)
            } catch (_: Exception) {
            }
        }
    }

    private fun toastSaved(ok: Boolean) {
        if (isFinishing) return
        Toast.makeText(this, if (ok) R.string.saved_downloads else R.string.error_pdf, Toast.LENGTH_SHORT).show()
    }

    /** Opens the support page in the browser. The app itself sends nothing. */
    private fun openDonate() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL)))
        } catch (_: Exception) {
            Toast.makeText(this, DONATE_URL, Toast.LENGTH_LONG).show()
        }
    }

    private fun share() {
        val uri = currentUri ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.menu_share)))
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ updates

    /** Looks for a newer GitHub release. Silent on launch, reports the outcome when the user asked. */
    private fun checkUpdate(manual: Boolean) {
        if (updBusy || updChecking) return
        val prefs = getSharedPreferences("lite", MODE_PRIVATE)
        // Silent checks (on launch and when coming back to the app) run at most every 6 hours.
        if (!manual && System.currentTimeMillis() - prefs.getLong("upd_check", 0L) < 6 * 3600_000L) return
        updChecking = true
        try {
            net.execute {
                var info: Updater.Info? = null
                var failed = false
                try {
                    info = Updater.check(this)
                } catch (t: Throwable) {
                    failed = true
                }
                val found = info
                if (!failed) {
                    prefs.edit().putLong("upd_check", System.currentTimeMillis()).apply()
                    if (found != null) Updater.savePending(this, found) else Updater.clearPending(this)
                }
                ui.post {
                    updChecking = false
                    if (isFinishing || updBusy) return@post
                    if (found != null) {
                        showUpdate(found)
                    } else if (!failed) {
                        // Nothing newer than what is installed: drop any stale bar.
                        updInfo = null
                        updFile = null
                        updateBar.visibility = View.GONE
                        moreBadge.visibility = View.GONE
                    }
                    if (found == null && manual) {
                        val msg = if (failed) getString(R.string.update_check_failed)
                        else getString(R.string.update_up_to_date, Updater.installedVersion(this))
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (_: Exception) {
            updChecking = false
        }
    }

    /** Shows the update bar: "Update" when it still has to be downloaded, "Install" when it is already on the phone. */
    private fun showUpdate(info: Updater.Info) {
        if (updInfo?.version != info.version || updFile == null) {
            updFile = Updater.readyFile(this, info)
        }
        updInfo = info
        tvUpdate.setOnClickListener { showWhatsNew() }
        if (updFile != null) {
            tvUpdate.text = getString(R.string.update_ready, info.version)
            btnUpdate.text = getString(R.string.update_install)
        } else {
            tvUpdate.text = getString(R.string.update_available, info.version)
            btnUpdate.text = getString(R.string.update)
        }
        btnUpdate.isEnabled = true
        updateBar.visibility = View.VISIBLE
        moreBadge.visibility = View.VISIBLE
    }

    private fun showWhatsNew() {
        val info = updInfo ?: return
        if (info.notes.isEmpty()) return
        // Release notes are Markdown; show them as plain, lightly cleaned text.
        val text = info.notes.replace(Regex("(?m)^#+\\s*"), "").replace("**", "").replace("`", "")
            .replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "$1")
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.whats_new, info.version))
            .setMessage(text)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun onUpdateClick() {
        val ready = updFile
        if (ready != null && ready.isFile) {
            install(ready)
            return
        }
        if (ready != null) {
            // The saved APK is gone (storage cleaned): go back to "Update" and download again.
            updFile = null
            updInfo?.let { showUpdate(it) }
        }
        val info = updInfo ?: return
        if (updBusy) {
            updCancel = true
            btnUpdate.isEnabled = false
            return
        }
        updBusy = true
        updCancel = false
        btnUpdate.text = getString(R.string.update_cancel)
        tvUpdate.text = getString(R.string.update_downloading, 0)
        try {
            net.execute {
                var file: File? = null
                try {
                    file = Updater.download(
                        this, info,
                        { done, total ->
                            val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                            ui.post { tvUpdate.text = getString(R.string.update_downloading, pct) }
                        },
                        { isFinishing || updCancel }
                    )
                } catch (t: Throwable) {
                    android.util.Log.w("LitePPTX", "update download failed", t)
                }
                val done = file
                ui.post {
                    updBusy = false
                    if (isFinishing) return@post
                    if (done == null) {
                        if (updCancel) {
                            tvUpdate.text = getString(R.string.update_available, info.version)
                        } else {
                            tvUpdate.text = getString(R.string.update_failed)
                        }
                        btnUpdate.text = getString(R.string.update)
                        btnUpdate.isEnabled = true
                    } else {
                        updFile = done
                        tvUpdate.text = getString(R.string.update_ready, info.version)
                        btnUpdate.text = getString(R.string.update_install)
                        btnUpdate.isEnabled = true
                        install(done)
                    }
                    updCancel = false
                }
            }
        } catch (_: Exception) {
            updBusy = false
            btnUpdate.text = getString(R.string.update)
        }
    }

    private fun install(f: File) {
        if (!Updater.canInstall(this)) {
            pendingInstall = true
            Toast.makeText(this, R.string.update_allow, Toast.LENGTH_LONG).show()
            try {
                startActivity(Updater.unknownSourcesIntent(this))
            } catch (_: Exception) {
            }
            return
        }
        try {
            startActivity(Updater.installIntent(f))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.update_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ loading

    private fun load(uri: Uri) {
        val my = ++token
        currentUri = uri
        tvFileName.text = displayName(uri) ?: getString(R.string.app_name)
        emptyState.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        tvSlideCounter.visibility = View.GONE

        try {
            io.execute {
                var parsed: Deck? = null
                try {
                    // Copy to cache so slides/media can be read lazily with random access.
                    val f = File(cacheDir, "doc-$my.pptx")
                    val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException()
                    input.use { src -> f.outputStream().use { dst -> src.copyTo(dst, 64 * 1024) } }
                    val zip = ZipFile(f)
                    parsed = try {
                        PptxParser(zip, f).parse()
                    } catch (t: Throwable) {
                        try { zip.close() } catch (_: Throwable) {}
                        f.delete()
                        throw t
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("LitePPTX", "load failed", t)
                    parsed = null
                }
                val result = parsed
                ui.post {
                    if (my != token || isFinishing) {
                        result?.close()
                    } else if (result == null || result.slides.isEmpty()) {
                        result?.close()
                        showError()
                    } else {
                        show(result)
                    }
                }
            }
        } catch (_: Exception) {
            showError()
        }
    }

    private fun show(d: Deck) {
        adapter?.release()
        deck?.close()
        deck = d
        slideCount = d.slides.size
        val width = (resources.displayMetrics.widthPixels * 1.3f).toInt().coerceIn(1080, 1600)
        val a = SlideAdapter(this, d, width)
        a.attach(list)
        adapter = a
        list.resetZoom()
        list.adapter = a
        list.setSelection(0)
        progressBar.visibility = View.GONE
        showCounter("1 / $slideCount")
    }

    private fun showError() {
        progressBar.visibility = View.GONE
        if (deck == null) emptyState.visibility = View.VISIBLE
        Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
    }

    private fun showCounter(text: String) {
        ui.removeCallbacks(hideCounter)
        tvSlideCounter.animate().cancel()
        tvSlideCounter.text = text
        tvSlideCounter.alpha = 1f
        tvSlideCounter.visibility = View.VISIBLE
        ui.postDelayed(hideCounter, 1500)
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) return c.getString(i)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return uri.lastPathSegment
    }

    private companion object {
        const val REQ_OPEN = 1001
        const val REQ_SAVE = 1002
        const val DONATE_URL = "https://the-evilenor.vercel.app/support"
    }
}
