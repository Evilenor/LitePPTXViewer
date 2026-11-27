package com.litepptx.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.AbsListView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.litepptx.viewer.pdf.PdfActions
import com.litepptx.viewer.pdf.PptxPdfExporter
import com.litepptx.viewer.pptx.Deck
import com.litepptx.viewer.pptx.PptxParser
import com.litepptx.viewer.pptx.SlideRenderer
import com.litepptx.viewer.update.Updater
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.zip.ZipFile

class MainActivity : Activity() {

    private lateinit var tvFileName: TextView
    private lateinit var btnBack: ImageButton
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
    private lateinit var recentSection: View
    private lateinit var recentList: LinearLayout
    private lateinit var btnNotes: TextView
    private var curSlide = 0
    private var startAt = 0
    private var loadingFromRecent = false
    private var openedFromHome = false
    private var keepAwake = false

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
        UiHelper.applyInsets(findViewById(R.id.root))

        tvFileName = findViewById(R.id.tvFileName)
        btnBack = findViewById(R.id.btnBack)
        tvSlideCounter = findViewById(R.id.tvSlideCounter)
        btnNotes = findViewById(R.id.btnNotes)
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
        recentSection = findViewById(R.id.recentSection)
        recentList = findViewById(R.id.recentList)
        donateCard.setOnClickListener { openDonate() }
        findViewById<View>(R.id.tvRecentClear).setOnClickListener {
            RecentStore.clear(this)
            renderRecent()
        }

        btnBack.setOnClickListener { onBackPressed() }
        btnBack.visibility = View.INVISIBLE
        findViewById<ImageButton>(R.id.btnMore).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.btnOpenFile).setOnClickListener { pickFile() }
        findViewById<View>(R.id.btnImagesPdf).setOnClickListener { openPdfMaker() }
        findViewById<View>(R.id.btnScan).setOnClickListener { openScanner(false) }
        findViewById<View>(R.id.btnIdCard).setOnClickListener { openScanner(true) }
        findViewById<View>(R.id.btnOpenPdf).setOnClickListener { pickPdf() }
        tvSlideCounter.setOnClickListener { goToSlide() }
        btnNotes.setOnClickListener { showNotes() }

        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {}
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                if (slideCount == 0) return
                val c0 = view.getChildAt(0) ?: return
                var pos = first
                if (c0.bottom < c0.height / 2) pos++
                pos = pos.coerceIn(0, slideCount - 1)
                curSlide = pos
                showCounter("${pos + 1} / $slideCount")
                syncNotesBadge()
            }
        })

        // Remove files left behind by an earlier, killed process.
        cacheDir.listFiles()?.forEach { if (it.name.startsWith("doc-")) it.delete() }

        val saved = savedInstanceState?.getString("uri")
        val savedSlide = savedInstanceState?.getInt("slide", 0) ?: 0
        val extUri = uriFrom(intent)
        val uri = extUri ?: saved?.let { Uri.parse(it) }
        if (uri != null) {
            openedFromHome = savedInstanceState?.getBoolean("fromHome", false) ?: false
            load(uri, fromRecent = false, resumeSlide = savedSlide, fromHome = openedFromHome)
        } else {
            renderRecent()
        }

        // Show a known pending update straight away (and a finished download), then let onResume
        // look for a newer one. Nothing is forgotten when the app is closed.
        Updater.cleanup(this)
        Updater.loadPending(this)?.let { showUpdate(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        uriFrom(intent)?.let { load(it, fromRecent = false, resumeSlide = 0, fromHome = false) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentUri?.let {
            outState.putString("uri", it.toString())
            outState.putInt("slide", curSlide)
            outState.putBoolean("fromHome", openedFromHome)
        }
    }

    override fun onResume() {
        super.onResume()
        if (deck == null) renderRecent()
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
        currentUri?.let { if (slideCount > 0) RecentStore.setLast(this, it.toString(), curSlide) }
        ui.removeCallbacks(heartbeat)
        ivHeart.animate().cancel()
        ivHeart.scaleX = 1f
        ivHeart.scaleY = 1f
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            list.isZoomed() -> list.resetZoom()
            deck != null && openedFromHome -> closeDeck()
            else -> finish()
        }
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
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
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

    private fun pickPdf() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        try {
            startActivityForResult(i, REQ_OPEN_PDF)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.pdf_error_open, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
                val saved = RecentStore.find(this, uri.toString())?.last ?: 0
                load(uri, fromRecent = false, resumeSlide = saved, fromHome = true)
            }
        }
        if (requestCode == REQ_OPEN_PDF && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                val view = Intent(this, PdfViewerActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(view)
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.pdf_error_open, Toast.LENGTH_SHORT).show()
                }
            }
        }
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
        val hasDeck = deck != null
        val menu = popup.menu
        menu.findItem(R.id.action_goto)?.isVisible = hasDeck && slideCount > 1
        menu.findItem(R.id.action_search)?.isVisible = hasDeck
        menu.findItem(R.id.action_notes)?.isVisible = hasDeck
        menu.findItem(R.id.action_share_slide)?.isVisible = hasDeck
        menu.findItem(R.id.action_export_pdf)?.isVisible = hasDeck
        menu.findItem(R.id.action_share)?.isVisible = hasDeck
        menu.findItem(R.id.action_reset_zoom)?.isVisible = hasDeck
        menu.findItem(R.id.action_keep_awake)?.apply {
            isVisible = hasDeck
            isChecked = keepAwake
        }
        // While an update is waiting, the menu entry becomes the action itself.
        updInfo?.let { u ->
            menu.findItem(R.id.action_check_update)?.title = getString(
                if (updFile != null) R.string.menu_install_update else R.string.menu_get_update, u.version
            )
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_open -> { pickFile(); true }
                R.id.action_goto -> { goToSlide(); true }
                R.id.action_search -> { searchSlides(); true }
                R.id.action_notes -> { showNotes(); true }
                R.id.action_share_slide -> { shareCurrentSlide(); true }
                R.id.action_images_pdf -> { openPdfMaker(); true }
                R.id.action_scan -> { openScanner(false); true }
                R.id.action_open_pdf -> { pickPdf(); true }
                R.id.action_export_pdf -> { exportPdf(); true }
                R.id.action_check_update -> { if (updInfo != null) onUpdateClick() else checkUpdate(true); true }
                R.id.action_donate -> { openDonate(); true }
                R.id.action_share -> { share(); true }
                R.id.action_reset_zoom -> { list.resetZoom(); true }
                R.id.action_keep_awake -> {
                    keepAwake = !keepAwake
                    UiHelper.setKeepAwake(this, keepAwake)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun openPdfMaker() {
        startActivity(Intent(this, PdfMakerActivity::class.java))
    }

    private fun openScanner(idCard: Boolean) {
        startActivity(
            Intent(this, ScanActivity::class.java)
                .putExtra(ScanActivity.EXTRA_AUTO, !idCard)
                .putExtra(ScanActivity.EXTRA_ID_CARD, idCard)
        )
    }

    // ------------------------------------------------------------------ viewer tools

    private fun goToSlide() {
        if (slideCount <= 1) return
        UiHelper.showGoToDialog(this, R.string.goto_title, slideCount, curSlide) { jumpTo(it) }
    }

    private fun jumpTo(pos: Int) {
        if (slideCount <= 0) return
        val target = pos.coerceIn(0, slideCount - 1)
        curSlide = target
        list.resetZoom()
        list.setSelection(target)
        showCounter("${target + 1} / $slideCount")
        syncNotesBadge()
    }

    private fun syncNotesBadge() {
        val hasNotes = !deck?.slides?.getOrNull(curSlide)?.notes.isNullOrBlank()
        btnNotes.visibility = if (hasNotes) View.VISIBLE else View.GONE
    }

    private fun showNotes() {
        val notes = deck?.slides?.getOrNull(curSlide)?.notes
        if (notes.isNullOrBlank()) {
            Toast.makeText(this, R.string.notes_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_title, curSlide + 1))
            .setMessage(notes)
            .setPositiveButton(R.string.ok, null)
            .show()
        dlg.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    private fun searchSlides() {
        val d = deck ?: return
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * dp).toInt(), pad, 0)
        }
        val input = EditText(this).apply {
            hint = getString(R.string.search_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_search)
            .setView(box)
            .setPositiveButton(R.string.goto_go) { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) return@setPositiveButton
                val hits = ArrayList<Pair<Int, String>>()
                for ((i, s) in d.slides.withIndex()) {
                    val txt = s.textContent()
                    val idx = txt.indexOf(q, ignoreCase = true)
                    if (idx >= 0) {
                        val from = (idx - 18).coerceAtLeast(0)
                        val to = (idx + q.length + 38).coerceAtMost(txt.length)
                        val snippet = (if (from > 0) "…" else "") + txt.substring(from, to) + (if (to < txt.length) "…" else "")
                        hits.add(i to getString(R.string.search_match, i + 1, snippet))
                    }
                }
                when {
                    hits.isEmpty() -> Toast.makeText(this, R.string.search_none, Toast.LENGTH_SHORT).show()
                    hits.size == 1 -> jumpTo(hits[0].first)
                    else -> AlertDialog.Builder(this)
                        .setTitle(getString(R.string.menu_search) + " (${hits.size})")
                        .setItems(hits.map { it.second }.toTypedArray()) { _, which -> jumpTo(hits[which].first) }
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shareCurrentSlide() {
        val d = deck ?: return
        val slide = d.slides.getOrNull(curSlide) ?: return
        val n = curSlide + 1
        try {
            exportPool.execute {
                var f: File? = null
                try {
                    val dir = File(cacheDir, "pdf").apply { mkdirs() }
                    val out = File(dir, "Slide_$n.png")
                    val bmp = SlideRenderer(d).render(slide, 1600)
                    try {
                        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally {
                        bmp.recycle()
                    }
                    f = out
                } catch (t: Throwable) {
                    android.util.Log.w("LitePPTX", "slide share failed", t)
                }
                val done = f
                ui.post {
                    if (isFinishing) return@post
                    if (done != null) PdfActions.shareImage(this, done, getString(R.string.menu_share_slide))
                    else Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: Exception) {
        }
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
        val base = (UiHelper.displayName(this, currentUri ?: Uri.EMPTY) ?: "")
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

    // ------------------------------------------------------------------ recent files

    private fun renderRecent() {
        val entries = RecentStore.load(this)
        recentList.removeAllViews()
        if (entries.isEmpty()) {
            recentSection.visibility = View.GONE
            return
        }
        recentSection.visibility = View.VISIBLE
        for (e in entries) {
            val row = layoutInflater.inflate(R.layout.item_recent, recentList, false)
            row.findViewById<TextView>(R.id.tvRecentName).text = e.name
            val t = relativeTime(e.time)
            row.findViewById<TextView>(R.id.tvRecentMeta).text = if (e.last > 0 && e.slides > 0) {
                getString(R.string.recent_resume, e.last + 1, e.slides, t)
            } else {
                val countStr = resources.getQuantityString(R.plurals.recent_slides, e.slides, e.slides)
                getString(R.string.recent_meta, countStr, t)
            }
            row.setOnClickListener {
                load(Uri.parse(e.uri), fromRecent = true, resumeSlide = e.last, fromHome = true)
            }
            row.findViewById<View>(R.id.btnRecentRemove).setOnClickListener {
                RecentStore.remove(this, e.uri)
                renderRecent()
            }
            recentList.addView(row)
        }
    }

    private fun relativeTime(ms: Long): String {
        val diff = (System.currentTimeMillis() - ms).coerceAtLeast(0L)
        val min = diff / 60_000L
        val hr = diff / 3_600_000L
        val day = diff / 86_400_000L
        return when {
            min < 1 -> getString(R.string.time_now)
            hr < 1 -> getString(R.string.time_min, min.toInt())
            day < 1 -> getString(R.string.time_hour, hr.toInt())
            day < 14 -> getString(R.string.time_day, day.toInt())
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
        }
    }

    private fun closeDeck() {
        currentUri?.let { if (slideCount > 0) RecentStore.setLast(this, it.toString(), curSlide) }
        token++
        adapter?.release()
        deck?.close()
        adapter = null
        deck = null
        currentUri = null
        slideCount = 0
        curSlide = 0
        openedFromHome = false
        list.resetZoom()
        list.adapter = null
        progressBar.visibility = View.GONE
        tvSlideCounter.visibility = View.GONE
        btnNotes.visibility = View.GONE
        tvFileName.setText(R.string.app_name)
        btnBack.visibility = View.INVISIBLE
        emptyState.visibility = View.VISIBLE
        renderRecent()
        ui.removeCallbacks(heartbeat)
        ui.postDelayed(heartbeat, 600)
    }

    // ------------------------------------------------------------------ loading

    private fun load(uri: Uri, fromRecent: Boolean = false, resumeSlide: Int = 0, fromHome: Boolean = false) {
        val my = ++token
        currentUri?.let { if (slideCount > 0) RecentStore.setLast(this, it.toString(), curSlide) }
        currentUri = uri
        startAt = resumeSlide
        loadingFromRecent = fromRecent
        openedFromHome = fromHome
        tvFileName.text = UiHelper.displayName(this, uri) ?: getString(R.string.app_name)
        btnBack.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        tvSlideCounter.visibility = View.GONE
        btnNotes.visibility = View.GONE

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
                        showError(uri)
                    } else {
                        show(result, uri)
                    }
                }
            }
        } catch (_: Exception) {
            showError(uri)
        }
    }

    private fun show(d: Deck, uri: Uri) {
        adapter?.release()
        deck?.close()
        deck = d
        slideCount = d.slides.size
        val name = UiHelper.displayName(this, uri) ?: getString(R.string.app_name)
        tvFileName.text = name
        RecentStore.touch(this, uri.toString(), name, slideCount)

        val width = (resources.displayMetrics.widthPixels * 1.3f).toInt().coerceIn(1080, 1600)
        val a = SlideAdapter(this, d, width)
        a.attach(list)
        adapter = a
        val jump = startAt.coerceIn(0, slideCount - 1)
        curSlide = jump
        list.resetZoom()
        list.adapter = a
        list.setSelection(jump)
        progressBar.visibility = View.GONE
        showCounter("${jump + 1} / $slideCount")
        syncNotesBadge()
    }

    private fun showError(failedUri: Uri) {
        progressBar.visibility = View.GONE
        if (loadingFromRecent) {
            RecentStore.remove(this, failedUri.toString())
            loadingFromRecent = false
            Toast.makeText(this, R.string.recent_gone, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
        }
        if (deck == null) {
            currentUri = null
            tvFileName.setText(R.string.app_name)
            btnBack.visibility = View.INVISIBLE
            emptyState.visibility = View.VISIBLE
            renderRecent()
        }
    }

    private fun showCounter(text: String) {
        ui.removeCallbacks(hideCounter)
        tvSlideCounter.animate().cancel()
        tvSlideCounter.text = text
        tvSlideCounter.alpha = 1f
        tvSlideCounter.visibility = View.VISIBLE
        ui.postDelayed(hideCounter, 1800)
    }

    private companion object {
        const val REQ_OPEN = 1001
        const val REQ_SAVE = 1002
        const val REQ_OPEN_PDF = 1003
        const val DONATE_URL = "https://the-evilenor.vercel.app/support"
    }
}
