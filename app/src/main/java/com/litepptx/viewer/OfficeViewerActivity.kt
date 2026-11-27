package com.litepptx.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import com.litepptx.viewer.office.DocBlock
import com.litepptx.viewer.office.DocDocument
import com.litepptx.viewer.office.DocxParser
import com.litepptx.viewer.office.LegacyOffice
import com.litepptx.viewer.office.OoxmlPackage
import com.litepptx.viewer.office.Sheet
import com.litepptx.viewer.office.TextFormats
import com.litepptx.viewer.office.Workbook
import com.litepptx.viewer.office.XlsxParser
import com.litepptx.viewer.pptx.Para
import com.litepptx.viewer.pptx.Run
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * One viewer for Word, Sheets and plain-text files: .docx, .doc, .xlsx, .xls,
 * .ppt (text only), .txt, .csv, .rtf and .md.
 *
 * DOCX/XLSX are parsed from their OOXML zip on a background thread; legacy
 * .doc/.ppt/.xls fall back to printable-text recovery (formatting is lost and
 * the screen says so); .txt/.csv/.rtf/.md are parsed in memory. Images inside
 * a .docx are decoded lazily, sampled to the screen width.
 */
class OfficeViewerActivity : Activity() {

    private lateinit var tvFileName: TextView
    private lateinit var tvCounter: TextView
    private lateinit var tvNote: TextView
    private lateinit var list: ZoomableListView
    private lateinit var progressBar: ProgressBar
    private lateinit var sheetTabs: View
    private lateinit var sheetTabBar: LinearLayout

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val imgPool = Executors.newFixedThreadPool(2)
    @Volatile private var destroyed = false
    @Volatile private var gen = 0

    private val imgCache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    private var sourceUri: Uri? = null
    private var fileName: String = ""
    private var doc: DocDocument? = null
    private var workbook: Workbook? = null
    private var curSheet = 0
    private var curPos = 0
    private var total = 0
    private var keepAwake = false
    private var night = false
    private var textScale = 1f
    private var adapter: BaseAdapter? = null
    private var sheetScrollX = 0
    private var syncingScroll = false
    private val sheetScrollViews = ArrayList<HorizontalScrollView>()

    private val hideCounter = Runnable {
        tvCounter.animate().alpha(0f).setDuration(250).withEndAction {
            tvCounter.visibility = View.GONE
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_office)
        UiHelper.applyInsets(findViewById(R.id.root))

        tvFileName = findViewById(R.id.tvFileName)
        tvCounter = findViewById(R.id.tvCounter)
        tvNote = findViewById(R.id.tvNote)
        list = findViewById(R.id.listOffice)
        progressBar = findViewById(R.id.progressBar)
        sheetTabs = findViewById(R.id.sheetTabs)
        sheetTabBar = findViewById(R.id.sheetTabBar)

        textScale = getSharedPreferences("lite", MODE_PRIVATE).getFloat("office_text_scale", 1f).coerceIn(0.8f, 1.6f)

        findViewById<View>(R.id.btnBack).setOnClickListener { onBackPressed() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMenu(it) }
        tvCounter.setOnClickListener {
            if (workbook != null) {
                val sheet = workbook?.sheets?.getOrNull(curSheet) ?: return@setOnClickListener
                val rows = (sheet.rows.maxOfOrNull { it.index } ?: -1) + 1
                if (rows > 1) UiHelper.showGoToDialog(this, R.string.goto_row_title, rows, curPos) { jumpToSheetRow(it) }
            }
        }

        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {}
            override fun onScroll(view: AbsListView, first: Int, visible: Int, totalCount: Int) {
                if (total == 0) return
                val c0 = view.getChildAt(0) ?: return
                var pos = first
                if (c0.bottom < c0.height / 2) pos++
                curPos = pos.coerceIn(0, total - 1)
                showCounter("${curPos + 1} / $total")
            }
        })

        cacheDir.listFiles()?.forEach { if (it.name.startsWith("doc-")) it.delete() }

        night = UiHelper.isNightMode(this)
        applyNight()

        val savedUri = savedInstanceState?.getString("uri")
        curSheet = intent.getIntExtra(EXTRA_SHEET, -1).takeIf { it >= 0 }
            ?: savedInstanceState?.getInt("sheet", 0) ?: 0
        val uri = intent?.data ?: streamUri(intent) ?: savedUri?.let { Uri.parse(it) }
        val savedPos = intent.getIntExtra(EXTRA_POS, -1).takeIf { it >= 0 }
            ?: savedInstanceState?.getInt("pos", 0)
            ?: uri?.let { RecentStore.find(this, it.toString())?.last } ?: 0
        if (uri == null) {
            Toast.makeText(this, R.string.office_error_open, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        load(uri, savedPos)
    }

    /** ACTION_SEND hands the file over in EXTRA_STREAM instead of the data URI. */
    private fun streamUri(i: Intent?): Uri? {
        if (i?.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    /**
     * Night mode: white page, dark text in the day; black page, lightened text at night.
     * Table and sheet fills are darkened, with the text colour picked from the fill.
     */
    private fun applyNight() {
        list.setBackgroundColor(if (night) Color.BLACK else Color.WHITE)
        tvNote.setBackgroundColor(if (night) 0xFF2A2A2A.toInt() else 0xFFEEEEEE.toInt())
        tvNote.setTextColor(if (night) Color.WHITE else Color.BLACK)
        adapter?.notifyDataSetChanged()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sourceUri?.let {
            outState.putString("uri", it.toString())
            outState.putInt("pos", curPos)
            outState.putInt("sheet", curSheet)
        }
    }

    override fun onPause() {
        sourceUri?.let {
            val kind = if (workbook != null) "sheet" else "doc"
            RecentStore.setLast(this, it.toString(), curPos)
            // Touch keeps the entry fresh without losing the kind (set in load).
            if (total > 0) RecentStore.touch(this, it.toString(), fileName, total, kind)
        }
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (list.isZoomed()) list.resetZoom() else finish()
    }

    override fun onDestroy() {
        destroyed = true
        gen++
        ui.removeCallbacksAndMessages(null)
        imgPool.shutdownNow()
        io.shutdownNow()
        doc?.close()
        workbook?.close()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ loading

    private fun load(uri: Uri, resumePos: Int) {
        sourceUri = uri
        fileName = UiHelper.displayName(this, uri) ?: getString(R.string.office_viewer_title)
        tvFileName.text = fileName
        progressBar.visibility = View.VISIBLE
        val myGen = ++gen
        val ext = fileName.substringAfterLast('.', "").lowercase()
        try {
            io.execute {
                var parsedDoc: DocDocument? = null
                var parsedWb: Workbook? = null
                var legacy = false
                try {
                    val f = File(cacheDir, "doc-$myGen.$ext")
                    contentResolver.openInputStream(uri)?.use { src ->
                        f.outputStream().use { dst -> src.copyTo(dst, 64 * 1024) }
                    } ?: throw IllegalStateException("no stream")
                    when (ext) {
                        "docx" -> {
                            val pkg = OoxmlPackage(ZipFile(f), f)
                            parsedDoc = try { DocxParser.parse(pkg) } catch (t: Throwable) {
                                pkg.close()
                                throw t
                            }
                        }
                        "xlsx" -> {
                            val pkg = OoxmlPackage(ZipFile(f), f)
                            parsedWb = try { XlsxParser.parse(pkg) } catch (t: Throwable) {
                                pkg.close()
                                throw t
                            }
                        }
                        "doc", "ppt", "xls" -> {
                            val bytes = f.readBytes()
                            if (LegacyOffice.isOle(bytes)) {
                                f.delete()
                                val text = LegacyOffice.extractText(bytes)
                                parsedDoc = legacyDoc(text)
                                legacy = true
                            } else if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                                // A modern file with a legacy extension: try the OOXML parser.
                                val pkg = OoxmlPackage(ZipFile(f), f)
                                parsedDoc = try { DocxParser.parse(pkg) } catch (t: Throwable) {
                                    pkg.close()
                                    throw t
                                }
                            } else {
                                f.delete()
                                throw IllegalStateException("not an Office file")
                            }
                        }
                        "csv" -> {
                            parsedWb = TextFormats.parseCsv(readText(f))
                            f.delete()
                        }
                        "txt", "rtf", "md", "markdown" -> {
                            val text = readText(f)
                            f.delete()
                            parsedDoc = when (ext) {
                                "rtf" -> TextFormats.parseRtf(text)
                                "md", "markdown" -> TextFormats.parseMd(text)
                                else -> TextFormats.parseTxt(text)
                            }
                        }
                        else -> throw IllegalStateException("unsupported extension")
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("LitePPTX", "office load failed", t)
                    parsedDoc = null
                    parsedWb = null
                }
                val d = parsedDoc
                val w = parsedWb
                val isLegacy = legacy
                ui.post {
                    if (destroyed || myGen != gen) {
                        d?.close()
                        w?.close()
                        return@post
                    }
                    progressBar.visibility = View.GONE
                    when {
                        d != null -> showDoc(d, isLegacy, resumePos)
                        w != null -> showSheet(w, resumePos)
                        else -> {
                            Toast.makeText(this, R.string.office_error_open, Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            progressBar.visibility = View.GONE
            Toast.makeText(this, R.string.office_error_open, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun readText(f: File): String {
        val bytes = f.readBytes()
        val text = String(bytes, Charsets.UTF_8)
        return if (text.startsWith("\uFEFF")) text.substring(1) else text
    }

    /** Legacy text as plain paragraphs, headed by the "formatting lost" note. */
    private fun legacyDoc(text: String): DocDocument {
        val blocks = ArrayList<DocBlock>()
        for (chunk in text.split("\n\n")) {
            val t = chunk.trim()
            if (t.isEmpty()) continue
            val rtl = TextFormats.firstStrongIsRtl(t)
            blocks.add(
                DocBlock.ParaBlock(
                    Para("l", rtl, listOf(Run(t.replace('\n', ' '), 11f, Color.BLACK, false, false, false)), null, 0f, 0f, 1f, 0f, 0f, 11f)
                )
            )
        }
        return DocDocument(blocks, null)
    }

    // ------------------------------------------------------------------ showing

    private fun showDoc(d: DocDocument, legacy: Boolean, resumePos: Int) {
        doc?.close()
        workbook?.close()
        doc = d
        workbook = null
        sheetTabs.visibility = View.GONE
        var blocks = d.blocks
        if (legacy) {
            val note = DocBlock.ParaBlock(
                Para(
                    "l", false,
                    listOf(Run(getString(R.string.office_legacy_note), 11f, Color.GRAY, false, true, false)),
                    null, 0f, 0f, 1f, 0f, 6f, 11f
                )
            )
            blocks = listOf(note) + blocks
        }
        if (d.truncated) {
            tvNote.visibility = View.VISIBLE
            tvNote.text = getString(R.string.office_truncated)
        } else {
            tvNote.visibility = View.GONE
        }
        total = blocks.size
        val kindDoc = DocDocument(blocks, d.pkg, d.truncated)
        // Keep the package owned by the original document; the wrapper must not close it twice.
        doc = kindDoc
        RecentStore.touch(this, sourceUri.toString(), fileName, total, "doc")
        val a = DocAdapter(blocks)
        adapter = a
        list.resetZoom()
        list.adapter = a
        curPos = resumePos.coerceIn(0, (total - 1).coerceAtLeast(0))
        list.setSelection(curPos)
        showCounter("${curPos + 1} / $total")
    }

    private fun showSheet(w: Workbook, resumePos: Int) {
        doc?.close()
        workbook?.close()
        workbook = w
        doc = null
        if (w.sheets.isEmpty()) {
            Toast.makeText(this, R.string.office_error_open, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        curSheet = curSheet.coerceIn(0, w.sheets.size - 1)
        sheetTabs.visibility = View.VISIBLE
        paintTabs()
        total = w.sheets.sumOf { it.rows.size }
        RecentStore.touch(this, sourceUri.toString(), fileName, total, "sheet")
        if (w.truncated) {
            tvNote.visibility = View.VISIBLE
            tvNote.text = getString(R.string.sheet_truncated)
        } else {
            tvNote.visibility = View.GONE
        }
        showSheetPage(resumePos)
    }

    private fun showSheetPage(resumePos: Int) {
        val sheet = workbook?.sheets?.getOrNull(curSheet) ?: return
        sheetScrollViews.clear()
        sheetScrollX = 0
        val a = SheetAdapter(sheet)
        adapter = a
        list.resetZoom()
        list.adapter = a
        total = sheet.rows.size + 1 // +1 for the A/B/C header row
        curPos = resumePos.coerceIn(0, (total - 1).coerceAtLeast(0))
        list.setSelection(curPos)
        showCounter("${curPos + 1} / $total")
        paintTabs()
    }

    private fun paintTabs() {
        val w = workbook ?: return
        sheetTabBar.removeAllViews()
        val dp = resources.displayMetrics.density
        for ((i, s) in w.sheets.withIndex()) {
            val b = Button(this).apply {
                text = s.name
                contentDescription = getString(R.string.sheet_tab, s.name)
                textSize = 13f
                setTextColor(if (i == curSheet) Color.WHITE else 0xFFB4B9C4.toInt())
                setBackgroundResource(if (i == curSheet) R.drawable.bg_seg_on else R.drawable.bg_seg_off)
                setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
                isAllCaps = false
                minWidth = 0
            }
            b.setOnClickListener {
                if (curSheet != i) {
                    curSheet = i
                    showSheetPage(0)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (36 * dp).toInt()
            ).apply { marginEnd = (6 * dp).toInt() }
            sheetTabBar.addView(b, lp)
        }
    }

    private fun jumpToSheetRow(rowZero: Int) {
        val sheet = workbook?.sheets?.getOrNull(curSheet) ?: return
        val idx = sheet.rows.indexOfFirst { it.index == rowZero }
        if (idx < 0) return
        curPos = idx + 1 // +1 for the header row
        list.resetZoom()
        list.setSelection(curPos)
        showCounter("${curPos + 1} / $total")
    }

    private fun showCounter(text: String) {
        ui.removeCallbacks(hideCounter)
        tvCounter.animate().cancel()
        tvCounter.text = text
        tvCounter.alpha = 1f
        tvCounter.visibility = View.VISIBLE
        ui.postDelayed(hideCounter, 1800)
    }

    // ------------------------------------------------------------------ menu / search

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.office_menu, popup.menu)
        val isSheet = workbook != null
        popup.menu.findItem(R.id.action_goto_row)?.isVisible = isSheet
        popup.menu.findItem(R.id.action_bookmark)?.apply {
            isVisible = doc != null || workbook != null
            isChecked = sourceUri?.let { BookmarkStore.find(this@OfficeViewerActivity, it.toString(), curPos, curSheet) } != null
        }
        popup.menu.findItem(R.id.action_night_mode)?.isChecked = night
        popup.menu.findItem(R.id.action_keep_awake)?.isChecked = keepAwake
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search -> { search(); true }
                R.id.action_text_bigger -> { changeTextScale(0.1f); true }
                R.id.action_text_smaller -> { changeTextScale(-0.1f); true }
                R.id.action_goto_row -> {
                    val sheet = workbook?.sheets?.getOrNull(curSheet)
                    val rows = ((sheet?.rows?.maxOfOrNull { it.index } ?: -1) + 1)
                    if (rows > 1) UiHelper.showGoToDialog(this, R.string.goto_row_title, rows, curPos) { jumpToSheetRow(it) }
                    true
                }
                R.id.action_bookmark -> { toggleBookmark(); true }
                R.id.action_share -> { shareOriginal(); true }
                R.id.action_open_ext -> { openExternal(); true }
                R.id.action_reset_zoom -> { list.resetZoom(); true }
                R.id.action_night_mode -> {
                    night = !night
                    UiHelper.setNightMode(this, night)
                    applyNight()
                    true
                }
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

    /** Adds a bookmark at the current block/row, or removes it if it is already there. */
    private fun toggleBookmark() {
        val uri = sourceUri ?: return
        val kind = if (workbook != null) "sheet" else "doc"
        if (BookmarkStore.find(this, uri.toString(), curPos, curSheet) != null) {
            BookmarkStore.remove(this, uri.toString(), curPos, curSheet)
            Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
        } else {
            BookmarkStore.add(
                this,
                BookmarkStore.Entry(
                    uri.toString(), fileName, kind, curPos,
                    BookmarkStore.label(this, kind, curPos),
                    System.currentTimeMillis(), curSheet
                )
            )
            Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
        }
    }

    private fun changeTextScale(delta: Float) {
        textScale = (textScale + delta).coerceIn(0.8f, 1.6f)
        getSharedPreferences("lite", MODE_PRIVATE).edit().putFloat("office_text_scale", textScale).apply()
        (adapter as? DocAdapter)?.refresh()
        (adapter as? SheetAdapter)?.refresh()
    }

    private fun shareOriginal() {
        val uri = sourceUri ?: return
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "doc" -> "application/msword"
            "xls" -> "application/vnd.ms-excel"
            "ppt" -> "application/vnd.ms-powerpoint"
            "csv" -> "text/csv"
            "rtf" -> "application/rtf"
            "md", "markdown" -> "text/markdown"
            else -> "text/plain"
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.menu_share)))
        } catch (_: Exception) {
        }
    }

    private fun openExternal() {
        val uri = sourceUri ?: return
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "doc" -> "application/msword"
            "xls" -> "application/vnd.ms-excel"
            "ppt" -> "application/vnd.ms-powerpoint"
            "csv" -> "text/csv"
            "rtf" -> "application/rtf"
            "md", "markdown" -> "text/markdown"
            else -> "text/plain"
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(view)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.office_error_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun search() {
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * dp).toInt(), pad, 0)
        }
        val input = EditText(this).apply {
            hint = getString(R.string.office_search_hint)
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
                val hits = ArrayList<Triple<Int, Int, String>>() // sheetIdx, position, label
                doc?.let { d ->
                    for ((i, b) in d.blocks.withIndex()) {
                        val txt = blockText(b)
                        val idx = txt.indexOf(q, ignoreCase = true)
                        if (idx >= 0) {
                            hits.add(Triple(0, i, snippet(txt, idx, q)))
                        }
                    }
                }
                workbook?.let { w ->
                    for ((si, s) in w.sheets.withIndex()) {
                        for ((ri, row) in s.rows.withIndex()) {
                            for ((_, cell) in row.cells) {
                                val idx = cell.text.indexOf(q, ignoreCase = true)
                                if (idx >= 0) {
                                    hits.add(Triple(si, ri + 1, getString(R.string.sheet_rows, s.name, row.index + 1, snippet(cell.text, idx, q))))
                                    break
                                }
                            }
                        }
                    }
                }
                when {
                    hits.isEmpty() -> Toast.makeText(this, R.string.office_search_none, Toast.LENGTH_SHORT).show()
                    hits.size == 1 -> jumpToHit(hits[0])
                    else -> AlertDialog.Builder(this)
                        .setTitle(getString(R.string.menu_search) + " (${hits.size})")
                        .setItems(hits.map { it.third }.toTypedArray()) { _, which -> jumpToHit(hits[which]) }
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun jumpToHit(hit: Triple<Int, Int, String>) {
        if (workbook != null) {
            if (curSheet != hit.first) {
                curSheet = hit.first
                showSheetPage(0)
            }
            curPos = hit.second.coerceIn(0, (total - 1).coerceAtLeast(0))
            list.resetZoom()
            list.setSelection(curPos)
            showCounter("${curPos + 1} / $total")
        } else {
            curPos = hit.second.coerceIn(0, (total - 1).coerceAtLeast(0))
            list.resetZoom()
            list.setSelection(curPos)
            showCounter("${curPos + 1} / $total")
        }
    }

    private fun snippet(txt: String, idx: Int, q: String): String {
        val from = (idx - 18).coerceAtLeast(0)
        val to = (idx + q.length + 38).coerceAtMost(txt.length)
        return (if (from > 0) "…" else "") + txt.substring(from, to) + (if (to < txt.length) "…" else "")
    }

    private fun blockText(b: DocBlock): String {
        val sb = StringBuilder()
        when (b) {
            is DocBlock.ParaBlock -> for (r in b.para.runs) sb.append(r.text)
            is DocBlock.TableBlock -> for (row in b.rows) for (cell in row) for (p in cell.paras) for (r in p.runs) sb.append(r.text).append(' ')
            is DocBlock.ImageBlock -> {}
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ formatting helpers

    private fun emuToPx(emu: Float): Int {
        val dpi = resources.displayMetrics.densityDpi
        return (emu / 914400f * dpi).toInt()
    }

    private fun ptToPx(pt: Float): Int {
        val dpi = resources.displayMetrics.densityDpi
        return (pt / 72f * dpi).toInt()
    }

    private fun paraTextView(p: Para): TextView {
        val tv = TextView(this)
        val sb = SpannableStringBuilder()
        val bulletWidth = if (p.bullet != null) (14 * resources.displayMetrics.density * textScale).toInt() else 0
        val firstMargin = emuToPx(p.marL + p.indent)
        val restMargin = emuToPx(p.marL) + bulletWidth
        if (p.bullet != null) sb.append(p.bullet).append("  ")
        for (run in p.runs) {
            val start = sb.length
            sb.append(run.text)
            val end = sb.length
            if (end <= start) continue
            val size = (run.sizePt * textScale).toInt().coerceAtLeast(6)
            sb.setSpan(AbsoluteSizeSpan(size, true), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val runColor = if (night) UiHelper.nightTextColor(run.color) else run.color
            sb.setSpan(ForegroundColorSpan(runColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (run.bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (run.italic) sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (run.underline) sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = sb
        if (p.runs.isEmpty()) {
            tv.minHeight = ptToPx(p.emptySizePt * textScale)
        }
        if (firstMargin != 0 || restMargin != 0) {
            val span = LeadingMarginSpan.Standard(firstMargin, restMargin)
            sb.setSpan(span, 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            tv.text = sb
        }
        tv.setLineSpacing(0f, p.lnSpcPct.coerceIn(0.5f, 3f))
        val pad = (10 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad, ptToPx(p.spcBefPt) + 2, pad, ptToPx(p.spcAftPt) + 2)
        tv.gravity = when (p.align) {
            "ctr" -> Gravity.CENTER
            "r" -> if (p.rtl) Gravity.START else Gravity.END
            else -> if (p.rtl) Gravity.END else Gravity.START
        }
        if (p.rtl) tv.textDirection = View.TEXT_DIRECTION_RTL
        return tv
    }

    // ------------------------------------------------------------------ doc adapter

    private inner class DocAdapter(private val blocks: List<DocBlock>) : BaseAdapter() {
        private val screenW = resources.displayMetrics.widthPixels

        fun refresh() = notifyDataSetChanged()

        override fun getCount(): Int = blocks.size
        override fun getItem(position: Int): Any = blocks[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 3
        override fun getItemViewType(position: Int): Int = when (blocks[position]) {
            is DocBlock.ParaBlock -> 0
            is DocBlock.TableBlock -> 1
            is DocBlock.ImageBlock -> 2
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val b = blocks[position]) {
                is DocBlock.ParaBlock -> paraTextView(b.para)
                is DocBlock.TableBlock -> tableView(b)
                is DocBlock.ImageBlock -> imageView(position, b, convertView as? ImageView)
            }
        }

        private fun tableView(b: DocBlock.TableBlock): TableLayout {
            val table = TableLayout(this@OfficeViewerActivity)
            table.isStretchAllColumns = true
            val dp = resources.displayMetrics.density
            val pad = (8 * dp).toInt()
            val weights = if (b.colWidths.isNotEmpty()) b.colWidths else FloatArray(1) { 1f }
            for (row in b.rows) {
                val tr = TableRow(this@OfficeViewerActivity)
                var col = 0
                for (cell in row) {
                    if (col >= 20) break
                    val tv = TextView(this@OfficeViewerActivity)
                    val sb = StringBuilder()
                    var sizePt = 11f
                    for ((pi, p) in cell.paras.withIndex()) {
                        if (pi > 0) sb.append('\n')
                        for (r in p.runs) {
                            sb.append(r.text)
                            sizePt = r.sizePt
                        }
                    }
                    tv.text = sb.toString()
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (sizePt * textScale).coerceIn(6f, 32f))
                    tv.setPadding(pad, pad / 2, pad, pad / 2)
                    val fill = cell.fill?.let { if (night) UiHelper.nightFillColor(it) else it }
                    if (fill != null) {
                        tv.setBackgroundColor(fill)
                        tv.setTextColor(if (UiHelper.luminance(fill) > 140f) Color.BLACK else Color.WHITE)
                    } else {
                        tv.setTextColor(if (night) Color.WHITE else Color.BLACK)
                    }
                    val w = weights.getOrElse(col) { 1f }.coerceAtLeast(1f)
                    val lp = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, w)
                    lp.span = cell.span.coerceIn(1, 20 - col)
                    tr.addView(tv, lp)
                    col += lp.span
                }
                table.addView(tr)
            }
            return table
        }

        private fun imageView(position: Int, b: DocBlock.ImageBlock, reuse: ImageView?): ImageView {
            val iv = reuse ?: ImageView(this@OfficeViewerActivity)
            iv.adjustViewBounds = true
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.tag = position
            val cached = imgCache.get(position)
            if (cached != null) {
                iv.setImageBitmap(cached)
            } else {
                iv.setImageDrawable(null)
                requestImage(position, b)
            }
            return iv
        }

        private fun requestImage(position: Int, b: DocBlock.ImageBlock) {
            try {
                imgPool.execute {
                    var bmp: Bitmap? = null
                    try {
                        val bytes = doc?.pkg?.entry(b.zipPath)?.use { it.readBytes() } ?: return@execute
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        if (bounds.outWidth <= 0) return@execute
                        var sample = 1
                        while (bounds.outWidth / (sample * 2) >= screenW && sample < 16) sample *= 2
                        val o = BitmapFactory.Options().apply { inSampleSize = sample }
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    } catch (t: Throwable) {
                        android.util.Log.w("LitePPTX", "office image failed", t)
                    }
                    val result = bmp
                    ui.post {
                        if (destroyed) return@post
                        if (result != null) {
                            imgCache.put(position, result)
                            for (i in 0 until list.childCount) {
                                val iv = list.getChildAt(i) as? ImageView ?: continue
                                if (iv.tag == position) {
                                    iv.setImageBitmap(result)
                                    break
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------------ sheet adapter

    private inner class SheetAdapter(private val sheet: Sheet) : BaseAdapter() {

        fun refresh() = notifyDataSetChanged()

        /** Position 0 is the A/B/C header; data rows follow. */
        override fun getCount(): Int = sheet.rows.size + 1
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val dp = resources.displayMetrics.density
            val scroll = convertView as? HorizontalScrollView ?: HorizontalScrollView(this@OfficeViewerActivity).apply {
                isFillViewport = false
                isHorizontalScrollBarEnabled = false
                viewTreeObserver.addOnScrollChangedListener(object : ViewTreeObserver.OnScrollChangedListener {
                    override fun onScrollChanged() {
                        if (syncingScroll) return
                        syncingScroll = true
                        sheetScrollX = scrollX
                        for (other in sheetScrollViews) if (other !== this@apply) other.scrollTo(sheetScrollX, 0)
                        syncingScroll = false
                    }
                })
            }
            if (!sheetScrollViews.contains(scroll)) sheetScrollViews.add(scroll)
            scroll.scrollTo(sheetScrollX, 0)
            val row = LinearLayout(this@OfficeViewerActivity).apply { orientation = LinearLayout.HORIZONTAL }
            scroll.removeAllViews()
            scroll.addView(row)
            if (position == 0) {
                for (c in 0 until sheet.maxCol) {
                    row.addView(headerCell(colName(c), sheet.colWidths.getOrElse(c) { 8.43f }, dp))
                }
            } else {
                val dataRow = sheet.rows[position - 1]
                val mergedForRow = sheet.merged.filter { it[0] <= dataRow.index && dataRow.index <= it[2] }
                var c = 0
                while (c < sheet.maxCol) {
                    val range = mergedForRow.firstOrNull { it[1] <= c && c <= it[3] }
                    if (range != null && (range[0] != dataRow.index || range[1] != c)) {
                        c++ // merged slave cell: hidden
                        continue
                    }
                    val cell = dataRow.cells[c]
                    val widthChars = if (range != null) {
                        var sum = 0f
                        for (cc in range[1]..range[3]) sum += sheet.colWidths.getOrElse(cc) { 8.43f }
                        sum
                    } else {
                        sheet.colWidths.getOrElse(c) { 8.43f }
                    }
                    row.addView(dataCell(cell, widthChars, dp))
                    c += if (range != null) (range[3] - range[1] + 1) else 1
                }
            }
            return scroll
        }

        private fun colName(c: Int): String {
            var n = c
            val sb = StringBuilder()
            do {
                sb.insert(0, ('A' + n % 26))
                n = n / 26 - 1
            } while (n >= 0)
            return sb.toString()
        }

        private fun cellWidthPx(chars: Float, dp: Float): Int {
            return (chars * 7f * dp).toInt().coerceIn((48 * dp).toInt(), (320 * dp).toInt())
        }

        private fun headerCell(text: String, widthChars: Float, dp: Float): TextView {
            return TextView(this@OfficeViewerActivity).apply {
                this.text = text
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                if (night) {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0xFF2A2A2A.toInt())
                } else {
                    setTextColor(Color.BLACK)
                    setBackgroundColor(0xFFEEEEEE.toInt())
                }
                val w = cellWidthPx(widthChars, dp)
                layoutParams = LinearLayout.LayoutParams(w, (40 * dp).toInt())
                setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
            }
        }

        private fun dataCell(cell: com.litepptx.viewer.office.SheetCell?, widthChars: Float, dp: Float): TextView {
            return TextView(this@OfficeViewerActivity).apply {
                text = cell?.text ?: ""
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * textScale)
                gravity = Gravity.CENTER_VERTICAL or when (cell?.align) {
                    "ctr" -> Gravity.CENTER_HORIZONTAL
                    "r" -> Gravity.END
                    else -> Gravity.START
                }
                setPadding((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
                minHeight = (40 * dp).toInt()
                if (cell?.bold == true) setTypeface(null, Typeface.BOLD)
                val fill = cell?.fill?.let { if (night) UiHelper.nightFillColor(it) else it }
                if (fill != null) {
                    setBackgroundColor(fill)
                    setTextColor(if (UiHelper.luminance(fill) > 140f) Color.BLACK else Color.WHITE)
                } else {
                    setTextColor(if (night) Color.WHITE else Color.BLACK)
                }
                layoutParams = LinearLayout.LayoutParams(cellWidthPx(widthChars, dp), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    companion object {
        /** 0-based block/row to open at (bookmarks, search hits). */
        const val EXTRA_POS = "pos"
        /** 0-based sheet to open (bookmarks, search hits). */
        const val EXTRA_SHEET = "sheet"
    }
}
