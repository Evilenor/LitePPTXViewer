package com.litepptx.viewer

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.litepptx.viewer.pdf.PdfActions
import java.io.File
import java.util.concurrent.Executors

/**
 * Lightweight offline PDF viewer powered by Android's built-in [PdfRenderer] (API 21+, zero dependencies).
 * Renders visible pages lazily into a memory-bounded cache and reuses [ZoomableListView].
 */
class PdfViewerActivity : Activity() {

    private lateinit var tvFileName: TextView
    private lateinit var tvPageCounter: TextView
    private lateinit var list: ZoomableListView
    private lateinit var progressBar: ProgressBar

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var destroyed = false

    private var sourceUri: Uri? = null
    private var localFile: File? = null
    private var fileName: String = "Document.pdf"
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var ratios: FloatArray = FloatArray(0)
    private var pageCount = 0
    private var curPage = 0
    private var keepAwake = false
    private var adapter: PageAdapter? = null

    private val hideCounter = Runnable {
        tvPageCounter.animate().alpha(0f).setDuration(250).withEndAction {
            tvPageCounter.visibility = View.GONE
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)
        UiHelper.applyInsets(findViewById(R.id.root))

        tvFileName = findViewById(R.id.tvFileName)
        tvPageCounter = findViewById(R.id.tvPageCounter)
        list = findViewById(R.id.listPages)
        progressBar = findViewById(R.id.progressBar)

        findViewById<View>(R.id.btnBack).setOnClickListener { onBackPressed() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMenu(it) }
        tvPageCounter.setOnClickListener {
            UiHelper.showGoToDialog(this, R.string.goto_page_title, pageCount, curPage) { jumpTo(it) }
        }

        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {}
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                if (pageCount == 0) return
                val c0 = view.getChildAt(0) ?: return
                var pos = first
                if (c0.bottom < c0.height / 2) pos++
                pos = pos.coerceIn(0, pageCount - 1)
                curPage = pos
                showCounter("${pos + 1} / $pageCount")
            }
        })

        // Clean stale temp PDF viewer copies older than 1 hour.
        cacheDir.listFiles()?.forEach {
            if (it.name.startsWith("pdf-view-") && System.currentTimeMillis() - it.lastModified() > 3_600_000L) {
                it.delete()
            }
        }

        val uri = intent?.data
        if (uri == null) {
            Toast.makeText(this, R.string.pdf_error_open, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        load(uri, intent.getStringExtra(EXTRA_NAME))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { load(it, intent.getStringExtra(EXTRA_NAME)) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (list.isZoomed()) {
            list.resetZoom()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        destroyed = true
        ui.removeCallbacksAndMessages(null)
        adapter?.release()
        closePdf()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun closePdf() {
        try { renderer?.close() } catch (_: Throwable) {}
        try { pfd?.close() } catch (_: Throwable) {}
        renderer = null
        pfd = null
    }

    private fun load(uri: Uri, explicitName: String?) {
        sourceUri = uri
        fileName = (explicitName ?: UiHelper.displayName(this, uri) ?: "Document.pdf").let {
            if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf"
        }
        tvFileName.text = fileName
        progressBar.visibility = View.VISIBLE
        tvPageCounter.visibility = View.GONE

        try {
            io.execute {
                var newPfd: ParcelFileDescriptor? = null
                var newRenderer: PdfRenderer? = null
                var copiedFile: File? = null
                var pageRatios = FloatArray(0)
                try {
                    // Copy to a seekable cache file so streams from any chat/cloud provider work reliably.
                    val tmp = File(cacheDir, "pdf-view-${System.currentTimeMillis()}.pdf")
                    val inp = contentResolver.openInputStream(uri) ?: throw IllegalStateException()
                    inp.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst, 64 * 1024) } }
                    copiedFile = tmp
                    newPfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
                    newRenderer = PdfRenderer(newPfd)
                    val n = newRenderer.pageCount
                    if (n <= 0) throw IllegalStateException()
                    pageRatios = FloatArray(n) { 1.414f }
                    // Read exact page ratios up front for crisp, jump-free scrolling.
                    val cap = minOf(n, 200)
                    for (i in 0 until cap) {
                        newRenderer.openPage(i).use { p ->
                            if (p.width > 0) pageRatios[i] = p.height.toFloat() / p.width.toFloat()
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("LitePPTX", "pdf view failed", t)
                    try { newRenderer?.close() } catch (_: Throwable) {}
                    try { newPfd?.close() } catch (_: Throwable) {}
                    copiedFile?.delete()
                    newRenderer = null
                    newPfd = null
                    copiedFile = null
                }

                val r = newRenderer
                val fd = newPfd
                val f = copiedFile
                ui.post {
                    if (destroyed) {
                        try { r?.close() } catch (_: Throwable) {}
                        try { fd?.close() } catch (_: Throwable) {}
                        f?.delete()
                        return@post
                    }
                    progressBar.visibility = View.GONE
                    if (r == null || fd == null || f == null) {
                        Toast.makeText(this, R.string.pdf_error_open, Toast.LENGTH_LONG).show()
                        finish()
                        return@post
                    }
                    adapter?.release()
                    closePdf()
                    localFile = f
                    pfd = fd
                    renderer = r
                    ratios = pageRatios
                    pageCount = r.pageCount
                    curPage = 0

                    val width = (resources.displayMetrics.widthPixels * 1.3f).toInt().coerceIn(1080, 1600)
                    val a = PageAdapter(r, width)
                    adapter = a
                    list.resetZoom()
                    list.adapter = a
                    list.setSelection(0)
                    showCounter("1 / $pageCount")
                }
            }
        } catch (_: Exception) {
            progressBar.visibility = View.GONE
            Toast.makeText(this, R.string.pdf_error_open, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun jumpTo(pos: Int) {
        if (pageCount <= 0) return
        val target = pos.coerceIn(0, pageCount - 1)
        curPage = target
        list.resetZoom()
        list.setSelection(target)
        showCounter("${target + 1} / $pageCount")
    }

    private fun showCounter(text: String) {
        ui.removeCallbacks(hideCounter)
        tvPageCounter.animate().cancel()
        tvPageCounter.text = text
        tvPageCounter.alpha = 1f
        tvPageCounter.visibility = View.VISIBLE
        ui.postDelayed(hideCounter, 1800)
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.pdf_viewer_menu, popup.menu)
        popup.menu.findItem(R.id.action_goto)?.isVisible = pageCount > 1
        popup.menu.findItem(R.id.action_keep_awake)?.isChecked = keepAwake
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_goto -> {
                    UiHelper.showGoToDialog(this, R.string.goto_page_title, pageCount, curPage) { jumpTo(it) }
                    true
                }
                R.id.action_share -> { sharePdf(); true }
                R.id.action_save -> { savePdf(); true }
                R.id.action_open_ext -> {
                    sourceUri?.let { PdfActions.openExternal(this, it, fileName, getString(R.string.share)) }
                    true
                }
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

    private fun sharePdf() {
        val f = localFile ?: return
        // Copy into cache/pdf so PdfShareProvider can serve it uniformly.
        try {
            io.execute {
                val shareFile = File(File(cacheDir, "pdf").apply { mkdirs() }, fileName)
                if (f.canonicalPath != shareFile.canonicalPath) f.copyTo(shareFile, overwrite = true)
                ui.post { if (!destroyed) PdfActions.share(this, shareFile, getString(R.string.share)) }
            }
        } catch (_: Exception) {
        }
    }

    private fun savePdf() {
        val f = localFile ?: return
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                io.execute {
                    val named = File(File(cacheDir, "pdf").apply { mkdirs() }, fileName)
                    if (f.canonicalPath != named.canonicalPath) f.copyTo(named, overwrite = true)
                    val ok = PdfActions.saveToDownloads(this, named)
                    ui.post {
                        if (!destroyed) Toast.makeText(
                            this, if (ok) R.string.saved_downloads else R.string.error_pdf, Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (_: Exception) {
            }
        } else {
            try {
                startActivityForResult(PdfActions.createDocumentIntent(fileName), REQ_SAVE)
            } catch (_: Exception) {
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAVE && resultCode == RESULT_OK) {
            val dest = data?.data ?: return
            val f = localFile ?: return
            try {
                io.execute {
                    val ok = PdfActions.copyTo(this, f, dest)
                    ui.post {
                        if (!destroyed) Toast.makeText(
                            this, if (ok) R.string.saved_downloads else R.string.error_pdf, Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private inner class PageAdapter(
        private val pdf: PdfRenderer,
        private val widthPx: Int
    ) : BaseAdapter() {

        private val inflater = LayoutInflater.from(this@PdfViewerActivity)
        private val pool = Executors.newSingleThreadExecutor()
        private val pending = HashSet<Int>()
        @Volatile private var released = false

        private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
            override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
        }

        fun release() {
            released = true
            pool.shutdownNow()
            cache.evictAll()
        }

        override fun getCount(): Int = pageCount
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: inflater.inflate(R.layout.item_slide, parent, false)
            val iv = row.findViewById<SlideImageView>(R.id.ivSlide)
            iv.ratio = ratios.getOrElse(position) { 1.414f }
            iv.tag = position
            val bmp = cache.get(position)
            if (bmp != null) {
                iv.setImageBitmap(bmp)
            } else {
                iv.setImageDrawable(null)
                request(position)
            }
            if (position + 1 < count && cache.get(position + 1) == null) request(position + 1)
            return row
        }

        private fun request(pos: Int) {
            if (released || !pending.add(pos)) return
            try {
                pool.execute {
                    if (released || destroyed) return@execute
                    var bmp: Bitmap? = null
                    var pageRatio = 1.414f
                    try {
                        pdf.openPage(pos).use { page ->
                            pageRatio = if (page.width > 0) page.height.toFloat() / page.width.toFloat() else 1.414f
                            val h = (widthPx * pageRatio).toInt().coerceAtLeast(1)
                            val out = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                            Canvas(out).drawColor(Color.WHITE)
                            page.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp = out
                        }
                    } catch (t: Throwable) {
                        android.util.Log.w("LitePPTX", "pdf page render failed", t)
                    }
                    val result = bmp
                    ui.post {
                        pending.remove(pos)
                        if (released || destroyed) return@post
                        if (pos in ratios.indices) ratios[pos] = pageRatio
                        if (result != null) {
                            cache.put(pos, result)
                            showIfVisible(pos, result, pageRatio)
                        }
                    }
                }
            } catch (_: Throwable) {
                pending.remove(pos)
            }
        }

        private fun showIfVisible(pos: Int, bmp: Bitmap, r: Float) {
            for (i in 0 until list.childCount) {
                val iv = list.getChildAt(i)?.findViewById<SlideImageView>(R.id.ivSlide) ?: continue
                if (iv.tag == pos) {
                    iv.ratio = r
                    iv.setImageBitmap(bmp)
                    list.invalidate()
                    break
                }
            }
        }
    }

    companion object {
        const val EXTRA_NAME = "name"
        private const val REQ_SAVE = 4001
    }
}
