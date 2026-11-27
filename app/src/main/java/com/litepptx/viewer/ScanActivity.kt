package com.litepptx.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.litepptx.viewer.pdf.PdfActions
import com.litepptx.viewer.pdf.PdfBuilder
import com.litepptx.viewer.scan.CropView
import com.litepptx.viewer.scan.ScanFilters
import com.litepptx.viewer.scan.ScanImage
import com.litepptx.viewer.scan.ScanProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Document scanner. Photo (or gallery picks) -> drag the corners -> pick a look -> more pages -> PDF.
 * Everything runs on the phone. The system camera app takes the photo, so no permission is needed.
 */
class ScanActivity : Activity() {

    private enum class Stage { START, CROP, FILTER }

    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    @Volatile private var destroyed = false
    private var gen = 0

    private val pages = ArrayList<File>()
    private val queue = ArrayDeque<File>()
    private var stage = Stage.START
    private var rawFile: File? = null
    private var pendingCam: File? = null
    private var fromCamera = false
    private var idCardMode = false
    private var building = false

    private var previewBmp: Bitmap? = null
    private var detected = FloatArray(8)
    private var quad = FloatArray(8)
    private var rotation = 0
    private var filter = ScanFilters.ENHANCED
    private var flat: Bitmap? = null
    private var resultFile: File? = null

    private lateinit var start: View
    private lateinit var crop: CropView
    private lateinit var preview: ImageView
    private lateinit var busy: View
    private lateinit var tvBusy: TextView
    private lateinit var tvPage: TextView
    private lateinit var tvScanStep: TextView
    private lateinit var tvScanHint: TextView
    private lateinit var modeDoc: TextView
    private lateinit var modeId: TextView
    private lateinit var bottom: View
    private lateinit var cropBar: View
    private lateinit var filterBar: View
    private lateinit var tvAdjust: TextView
    private lateinit var btnNext: Button
    private lateinit var btnFinish: Button
    private lateinit var btnUndo: Button
    private lateinit var btnAddPage: Button
    private lateinit var btnDone: Button
    private lateinit var chips: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        UiHelper.applyInsets(findViewById(R.id.root))

        start = findViewById(R.id.start)
        crop = findViewById(R.id.crop)
        preview = findViewById(R.id.preview)
        busy = findViewById(R.id.busy)
        tvBusy = findViewById(R.id.tvBusy)
        tvPage = findViewById(R.id.tvPage)
        tvScanStep = findViewById(R.id.tvScanStep)
        tvScanHint = findViewById(R.id.tvScanHint)
        modeDoc = findViewById(R.id.modeDoc)
        modeId = findViewById(R.id.modeId)
        bottom = findViewById(R.id.bottom)
        cropBar = findViewById(R.id.cropBar)
        filterBar = findViewById(R.id.filterBar)
        tvAdjust = findViewById(R.id.tvAdjust)
        btnNext = findViewById(R.id.btnNext)
        btnFinish = findViewById(R.id.btnFinish)
        btnUndo = findViewById(R.id.btnUndo)
        btnAddPage = findViewById(R.id.btnAddPage)
        btnDone = findViewById(R.id.btnDone)
        chips = listOf(
            findViewById(R.id.fOriginal), findViewById(R.id.fClean),
            findViewById(R.id.fBw), findViewById(R.id.fGray),
            findViewById(R.id.fMagic)
        )

        idCardMode = savedInstanceState?.getBoolean("idCard")
            ?: intent.getBooleanExtra(EXTRA_ID_CARD, false)
        modeDoc.setOnClickListener { setScanMode(false) }
        modeId.setOnClickListener { setScanMode(true) }

        val prefs = getSharedPreferences("scan", MODE_PRIVATE)
        filter = prefs.getInt("filter", ScanFilters.ENHANCED).coerceIn(0, 4)
        for ((i, c) in chips.withIndex()) c.setOnClickListener {
            filter = i
            prefs.edit().putInt("filter", i).apply()
            applyFilter()
        }
        paintChips()

        findViewById<View>(R.id.btnBack).setOnClickListener { onBackPressed() }
        findViewById<View>(R.id.btnTake).setOnClickListener { takePhoto() }
        findViewById<View>(R.id.btnGallery).setOnClickListener { pickGallery() }
        btnFinish.setOnClickListener { buildPdf() }
        btnUndo.setOnClickListener {
            if (pages.isNotEmpty()) {
                pages.removeAt(pages.lastIndex).delete()
                showStart()
            }
        }
        findViewById<View>(R.id.btnSaveImages).setOnClickListener { saveImages() }
        findViewById<View>(R.id.btnRetake).setOnClickListener { retake() }
        findViewById<View>(R.id.btnFull).setOnClickListener {
            quad = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
            crop.setQuad(quad)
            syncNext()
        }
        findViewById<View>(R.id.btnAuto).setOnClickListener {
            quad = detected.copyOf()
            crop.setQuad(quad)
            syncNext()
        }
        btnNext.setOnClickListener { toFilter() }
        crop.onChanged = { syncNext() }
        findViewById<View>(R.id.btnRotate).setOnClickListener {
            rotation = (rotation + 90) % 360
            renderFilter()
        }
        btnAddPage.setOnClickListener { finalizePage { afterPage() } }
        btnDone.setOnClickListener {
            if (idCardMode && pages.isEmpty()) finalizePage { afterPage() }
            else finalizePage { clearCurrent(); buildPdf() }
        }

        val dir = ScanProvider.dir(this)
        dir.listFiles()?.forEach {
            if (System.currentTimeMillis() - it.lastModified() > 3 * 3_600_000L) it.delete()
        }
        var resume: File? = null
        if (savedInstanceState != null) {
            savedInstanceState.getStringArrayList("pages")?.forEach {
                val f = File(dir, it)
                if (f.isFile) pages.add(f)
            }
            savedInstanceState.getString("cam")?.let { pendingCam = File(dir, it) }
            savedInstanceState.getString("raw")?.let { resume = File(dir, it).takeIf { f -> f.isFile } }
            fromCamera = savedInstanceState.getBoolean("fromCamera")
        }
        showStart()
        val r = resume
        if (r != null) loadRaw(r)
        else if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_AUTO, false)) ui.post { takePhoto() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("pages", ArrayList(pages.map { it.name }))
        outState.putString("cam", pendingCam?.name)
        outState.putString("raw", rawFile?.name)
        outState.putBoolean("fromCamera", fromCamera)
        outState.putBoolean("idCard", idCardMode)
    }

    override fun onDestroy() {
        destroyed = true
        gen++
        ui.removeCallbacksAndMessages(null)
        pool.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            busy.visibility == View.VISIBLE -> {}
            stage == Stage.FILTER -> showCrop()
            stage == Stage.CROP -> {
                discardRaw()
                clearCurrent()
                nextOrStart()
            }
            pages.isNotEmpty() -> AlertDialog.Builder(this)
                .setTitle(R.string.scan_discard_title)
                .setMessage(getString(R.string.scan_discard_msg, pages.size))
                .setPositiveButton(R.string.scan_discard) { _, _ ->
                    pages.forEach { it.delete() }
                    pages.clear()
                    finish()
                }
                .setNegativeButton(R.string.scan_keep, null)
                .show()
            else -> finish()
        }
    }

    // ------------------------------------------------------------------ screens

    private fun setScanMode(idCard: Boolean) {
        if (pages.isNotEmpty() || idCardMode == idCard) return
        idCardMode = idCard
        showStart()
    }

    private fun paintMode() {
        val lock = pages.isNotEmpty()
        modeDoc.alpha = if (lock && idCardMode) 0.45f else 1f
        modeId.alpha = if (lock && !idCardMode) 0.45f else 1f
        modeDoc.setBackgroundResource(if (!idCardMode) R.drawable.bg_seg_on else R.drawable.bg_seg_off)
        modeId.setBackgroundResource(if (idCardMode) R.drawable.bg_seg_on else R.drawable.bg_seg_off)
        modeDoc.setTextColor(if (!idCardMode) 0xFFFFFFFF.toInt() else 0xFFB4B9C4.toInt())
        modeId.setTextColor(if (idCardMode) 0xFFFFFFFF.toInt() else 0xFFB4B9C4.toInt())
    }

    private fun showStart() {
        stage = Stage.START
        gen++
        start.visibility = View.VISIBLE
        crop.visibility = View.GONE
        preview.visibility = View.GONE
        bottom.visibility = View.GONE
        busy.visibility = View.GONE
        tvPage.visibility = View.GONE
        paintMode()
        if (idCardMode) {
            tvScanStep.visibility = View.VISIBLE
            tvScanStep.setText(if (pages.isEmpty()) R.string.scan_id_front else R.string.scan_id_back)
            tvScanHint.setText(R.string.scan_id_hint)
            val ready = pages.size >= 2
            btnFinish.visibility = if (ready) View.VISIBLE else View.GONE
            btnFinish.text = getString(R.string.pdf_create_n, 1)
        } else {
            tvScanStep.visibility = View.GONE
            tvScanHint.setText(R.string.scan_hint)
            btnFinish.visibility = if (pages.isEmpty()) View.GONE else View.VISIBLE
            btnFinish.text = getString(R.string.pdf_create_n, pages.size)
        }
        btnUndo.visibility = if (pages.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnSaveImages).visibility = if (pages.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showCrop() {
        stage = Stage.CROP
        gen++
        start.visibility = View.GONE
        preview.visibility = View.GONE
        crop.visibility = View.VISIBLE
        bottom.visibility = View.VISIBLE
        cropBar.visibility = View.VISIBLE
        filterBar.visibility = View.GONE
        busy.visibility = View.GONE
        syncNext()
        pageLabel()
    }

    private fun showFilter() {
        stage = Stage.FILTER
        start.visibility = View.GONE
        crop.visibility = View.GONE
        preview.visibility = View.VISIBLE
        bottom.visibility = View.VISIBLE
        cropBar.visibility = View.GONE
        filterBar.visibility = View.VISIBLE
        busy.visibility = View.GONE
        if (idCardMode && pages.isEmpty()) {
            btnAddPage.visibility = View.GONE
            btnDone.setText(R.string.scan_id_back)
        } else {
            btnAddPage.visibility = if (idCardMode) View.GONE else View.VISIBLE
            val pdfPages = if (idCardMode) 1 else pages.size + 1
            btnDone.text = getString(R.string.pdf_create_n, pdfPages)
        }
        pageLabel()
    }

    private fun pageLabel() {
        if (idCardMode) {
            tvPage.setText(if (pages.isEmpty()) R.string.scan_id_front else R.string.scan_id_back)
        } else {
            tvPage.text = getString(R.string.scan_page_n, pages.size + 1)
        }
        tvPage.visibility = View.VISIBLE
    }

    private fun setBusy(res: Int, vararg args: Any) {
        tvBusy.text = getString(res, *args)
        busy.visibility = View.VISIBLE
    }

    private fun syncNext() {
        btnNext.alpha = if (crop.isValid()) 1f else 0.4f
    }

    private fun paintChips() {
        for ((i, t) in chips.withIndex()) {
            t.setBackgroundResource(if (i == filter) R.drawable.bg_seg_on else R.drawable.bg_seg_off)
            t.setTextColor(if (i == filter) 0xFFFFFFFF.toInt() else 0xFFB4B9C4.toInt())
        }
    }

    private fun toast(res: Int) {
        if (!destroyed) Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    private fun bg(task: () -> Unit) {
        try {
            pool.execute { task() }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ getting a photo

    private fun takePhoto() {
        if (building) return
        val dir = ScanProvider.dir(this)
        dir.mkdirs()
        val f = File(dir, "raw_" + System.currentTimeMillis() + ".jpg")
        try {
            f.createNewFile()
        } catch (_: Exception) {
        }
        pendingCam = f
        val uri = ScanProvider.uriFor(f.name)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(i, REQ_CAM)
        } catch (_: Exception) {
            pendingCam = null
            f.delete()
            toast(R.string.scan_no_camera)
        }
    }

    private fun pickGallery() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (_: Exception) {
            toast(R.string.scan_error)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_CAM -> {
                val f = pendingCam
                pendingCam = null
                if (resultCode == RESULT_OK && f != null && f.length() > 0) {
                    fromCamera = true
                    loadRaw(f)
                } else {
                    f?.delete()
                }
            }
            REQ_PICK -> if (resultCode == RESULT_OK && data != null) importPicked(data)
            REQ_SAVE -> {
                val dest = data?.data
                val f = resultFile
                if (resultCode == RESULT_OK && dest != null && f != null) {
                    val ctx = applicationContext
                    bg {
                        val ok = PdfActions.copyTo(ctx, f, dest)
                        ui.post { toast(if (ok) R.string.saved_downloads else R.string.error_pdf) }
                    }
                }
            }
        }
    }

    private fun importPicked(data: Intent) {
        val uris = ArrayList<Uri>()
        val cd = data.clipData
        if (cd != null) {
            for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris.add(it) }
        } else {
            data.data?.let { uris.add(it) }
        }
        if (uris.isEmpty()) return
        setBusy(R.string.scan_finding)
        val dir = ScanProvider.dir(this).apply { mkdirs() }
        val ctx = applicationContext
        bg {
            val files = ArrayList<File>()
            for ((i, u) in uris.withIndex()) {
                try {
                    val f = File(dir, "raw_" + System.currentTimeMillis() + "_" + i + ".jpg")
                    ctx.contentResolver.openInputStream(u)?.use { ins -> f.outputStream().use { ins.copyTo(it) } }
                    if (f.length() > 0) files.add(f) else f.delete()
                } catch (t: Throwable) {
                    android.util.Log.w("LitePPTX", "import failed", t)
                }
            }
            ui.post {
                if (destroyed) return@post
                busy.visibility = View.GONE
                if (files.isEmpty()) {
                    toast(R.string.scan_error)
                } else {
                    fromCamera = false
                    queue.addAll(files)
                    nextOrStart()
                }
            }
        }
    }

    // ------------------------------------------------------------------ step 1: find and adjust the page

    private fun loadRaw(f: File) {
        rawFile = f
        val my = ++gen
        start.visibility = View.GONE
        setBusy(R.string.scan_finding)
        bg {
            var bmp: Bitmap? = null
            var det: ScanImage.Detected? = null
            try {
                bmp = ScanImage.decode(f.path, ScanImage.PREVIEW_LONG)
                if (bmp != null) det = ScanImage.detect(bmp)
            } catch (t: Throwable) {
                android.util.Log.w("LitePPTX", "scan load failed", t)
            }
            val b = bmp
            val d = det
            ui.post {
                if (destroyed || my != gen) return@post
                if (b == null || d == null) {
                    toast(R.string.scan_error)
                    discardRaw()
                    clearCurrent()
                    nextOrStart()
                    return@post
                }
                previewBmp = b
                detected = d.quad.copyOf()
                quad = d.quad.copyOf()
                rotation = 0
                crop.setBitmap(b)
                crop.setQuad(quad)
                tvAdjust.setText(if (d.found) R.string.scan_adjust else R.string.scan_adjust_manual)
                showCrop()
            }
        }
    }

    private fun retake() {
        discardRaw()
        clearCurrent()
        if (fromCamera) {
            showStart()
            takePhoto()
        } else {
            nextOrStart()
        }
    }

    private fun discardRaw() {
        rawFile?.delete()
        rawFile = null
    }

    private fun clearCurrent() {
        previewBmp = null
        flat = null
        crop.setBitmap(null)
        preview.setImageDrawable(null)
    }

    private fun nextOrStart() {
        val next = queue.poll()
        if (next != null) loadRaw(next) else showStart()
    }

    // ------------------------------------------------------------------ step 2: pick a look

    private fun toFilter() {
        if (!crop.isValid()) {
            toast(R.string.scan_bad_quad)
            return
        }
        quad = crop.getQuad()
        rotation = 0
        renderFilter()
    }

    /** Straightens the page at preview size and shows it with the chosen look. */
    private fun renderFilter() {
        val src = previewBmp ?: return
        val my = ++gen
        val q = quad.copyOf()
        val rot = rotation
        val mode = filter
        setBusy(R.string.scan_processing)
        bg {
            var f: Bitmap? = null
            var s: Bitmap? = null
            try {
                f = ScanImage.warp(src, q, ScanImage.FILTER_LONG, rot)
                s = ScanFilters.copyWith(f, mode)
                if (mode == ScanFilters.MAGIC) ScanFilters.sharpenInPlace(s)
            } catch (t: Throwable) {
                android.util.Log.w("LitePPTX", "scan preview failed", t)
            }
            ui.post {
                if (destroyed || my != gen) return@post
                if (f == null || s == null) {
                    busy.visibility = View.GONE
                    toast(R.string.scan_error)
                    return@post
                }
                flat = f
                preview.setImageBitmap(s)
                paintChips()
                showFilter()
            }
        }
    }

    private fun applyFilter() {
        paintChips()
        val f = flat ?: return
        val my = ++gen
        val mode = filter
        bg {
            var s: Bitmap? = null
            try {
                s = ScanFilters.copyWith(f, mode)
                if (mode == ScanFilters.MAGIC) ScanFilters.sharpenInPlace(s)
            } catch (t: Throwable) {
                android.util.Log.w("LitePPTX", "scan filter failed", t)
            }
            ui.post {
                if (!destroyed && my == gen && s != null) preview.setImageBitmap(s)
            }
        }
    }

    // ------------------------------------------------------------------ step 3: save the page, build the PDF

    /** Re-reads the original photo at full quality, straightens and filters it, and stores it as a page. */
    private fun finalizePage(after: () -> Unit) {
        val raw = rawFile ?: return
        if (busy.visibility == View.VISIBLE) return
        val q = quad.copyOf()
        val rot = rotation
        val mode = filter
        val out = File(ScanProvider.dir(this), "page_" + System.currentTimeMillis() + "_" + pages.size + ".jpg")
        setBusy(R.string.scan_saving)
        bg {
            var ok = false
            for (long in intArrayOf(ScanImage.PAGE_LONG, 1800, 1200)) {
                var src: Bitmap? = null
                var page: Bitmap? = null
                try {
                    src = ScanImage.decode(raw.path, long)
                    if (src == null) break
                    page = ScanImage.ensureMutable(ScanImage.warp(src, q, long, rot))
                    ScanFilters.applyInPlace(page, mode)
                    if (mode == ScanFilters.MAGIC) ScanFilters.sharpenInPlace(page)
                    ScanImage.saveJpeg(page, out, if (mode == ScanFilters.BW) 80 else 86)
                    ok = true
                    break
                } catch (e: OutOfMemoryError) {
                    // try again with a smaller page
                } catch (t: Throwable) {
                    android.util.Log.w("LitePPTX", "scan save failed", t)
                    break
                } finally {
                    src?.recycle()
                    page?.recycle()
                }
            }
            ui.post {
                if (destroyed) return@post
                busy.visibility = View.GONE
                if (ok) {
                    pages.add(out)
                    discardRaw()
                    after()
                } else {
                    out.delete()
                    toast(R.string.scan_error)
                }
            }
        }
    }

    private fun afterPage() {
        clearCurrent()
        val next = queue.poll()
        if (next != null) {
            loadRaw(next)
            return
        }
        showStart()
        if (fromCamera) takePhoto()
    }

    private fun buildPdf() {
        if (building || pages.isEmpty()) return
        val prefs = getSharedPreferences("scan", MODE_PRIVATE)
        val stored = prefs.getInt("scan_pdf_quality", 0)
        val names = arrayOf(getString(R.string.q_original), getString(R.string.q_smart), getString(R.string.q_small))
        val values = intArrayOf(0, 2, 3)
        val current = values.indexOf(stored).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.scan_quality_title)
            .setSingleChoiceItems(names, current) { dlg, which ->
                prefs.edit().putInt("scan_pdf_quality", values[which]).apply()
                dlg.dismiss()
                doBuild(values[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doBuild(quality: Int) {
        if (building || pages.isEmpty()) return
        building = true
        val ctx = applicationContext
        val prefix = if (idCardMode && pages.size >= 2) "ID_" else "Scan_"
        val name = prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".pdf"
        val expected = if (idCardMode && pages.size >= 2) 1 else pages.size
        setBusy(R.string.pdf_working, 1, expected)
        bg {
            var res: PdfBuilder.Result? = null
            var failed = false
            var sheet: File? = null
            val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
            val f = File(dir, name)
            try {
                val list = if (idCardMode && pages.size >= 2) {
                    sheet = composeIdSheet(pages[0], pages[1])
                    listOf(PdfBuilder.Item(ScanProvider.uriFor(sheet.name)))
                } else {
                    pages.map { PdfBuilder.Item(ScanProvider.uriFor(it.name)) }
                }
                val n = list.size
                res = PdfBuilder.build(
                    ctx, list, PdfBuilder.Options(PdfBuilder.PAGE_AUTO, 0, quality), f, name.removeSuffix(".pdf"),
                    { i -> ui.post { if (!destroyed) tvBusy.text = getString(R.string.pdf_working, i + 1, n) } },
                    { destroyed }
                )
            } catch (t: Throwable) {
                android.util.Log.w("LitePPTX", "scan pdf failed", t)
                failed = true
            } finally {
                sheet?.delete()
            }
            val r = res
            ui.post {
                building = false
                if (destroyed) return@post
                busy.visibility = View.GONE
                if (failed || r == null) {
                    toast(R.string.error_pdf)
                } else {
                    pages.forEach { it.delete() }
                    pages.clear()
                    queue.clear()
                    showStart()
                    resultFile = f
                    PdfActions.showResult(this, f, r.pages, r.bytes) { savePdf(it) }
                }
            }
        }
    }

    /** Saves each scanned page as a JPEG in Downloads (Android 10+). Pages stay in the scanner. */
    private fun saveImages() {
        if (pages.isEmpty() || Build.VERSION.SDK_INT < 29) {
            toast(R.string.error_pdf)
            return
        }
        val ctx = applicationContext
        val files = ArrayList(pages)
        bg {
            var saved = 0
            for (f in files) {
                try {
                    if (PdfActions.saveFileToDownloads(ctx, f, "image/jpeg")) saved++
                } catch (_: Throwable) {
                }
            }
            ui.post {
                if (!destroyed) {
                    if (saved > 0) Toast.makeText(this, getString(R.string.scan_images_saved, saved), Toast.LENGTH_LONG).show()
                    else toast(R.string.error_pdf)
                }
            }
        }
    }

    private fun savePdf(f: File) {
        if (Build.VERSION.SDK_INT >= 29) {
            val ctx = applicationContext
            bg {
                val ok = PdfActions.saveToDownloads(ctx, f)
                ui.post { toast(if (ok) R.string.saved_downloads else R.string.error_pdf) }
            }
        } else {
            try {
                startActivityForResult(PdfActions.createDocumentIntent(f.name), REQ_SAVE)
            } catch (_: Exception) {
            }
        }
    }

    /** Places front and back card scans centered on one crisp 200-DPI A4 page. */
    private fun composeIdSheet(front: File, back: File): File {
        val w = 1654
        val h = 2339
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val marginX = 180f
        val marginY = 200f
        val gap = 140f
        val slotW = w - 2f * marginX
        val slotH = (h - 2f * marginY - gap) / 2f

        fun drawSlot(file: File, top: Float) {
            val b = BitmapFactory.decodeFile(file.path) ?: return
            try {
                val s = minOf(slotW / b.width, slotH / b.height)
                val dw = b.width * s
                val dh = b.height * s
                val left = (w - dw) / 2f
                val y = top + (slotH - dh) / 2f
                c.drawBitmap(b, null, RectF(left, y, left + dw, y + dh), paint)
            } finally {
                b.recycle()
            }
        }

        drawSlot(front, marginY)
        drawSlot(back, marginY + slotH + gap)
        val f = File(ScanProvider.dir(this), "id_" + System.currentTimeMillis() + ".jpg")
        try {
            ScanImage.saveJpeg(out, f, 88)
        } finally {
            out.recycle()
        }
        return f
    }

    companion object {
        const val EXTRA_AUTO = "auto"
        const val EXTRA_ID_CARD = "id_card"
        private const val REQ_CAM = 3001
        private const val REQ_PICK = 3002
        private const val REQ_SAVE = 3003
    }
}
