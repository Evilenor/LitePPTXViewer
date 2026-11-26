package com.litepptx.viewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.litepptx.viewer.pdf.PdfBuilder
import com.litepptx.viewer.pdf.PdfShareProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class PdfMakerActivity : Activity() {

    private val items = ArrayList<PdfBuilder.Item>()
    private var selected = -1
    private var pageMode = PdfBuilder.PAGE_A4
    private var marginMode = 1
    private var qualityMode = 2

    private lateinit var grid: GridView
    private lateinit var empty: View
    private lateinit var panel: View
    private lateinit var resultPanel: View
    private lateinit var busy: View
    private lateinit var tvBusy: TextView
    private lateinit var tvQDesc: TextView
    private lateinit var btnCreate: Button
    private lateinit var strip: List<View>
    private lateinit var adapter: ThumbAdapter

    private val ui = Handler(Looper.getMainLooper())
    private val builder = Executors.newSingleThreadExecutor()
    private val thumbPool = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 12).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @Volatile private var cancel = false
    private var building = false
    private var pdfFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf)

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

        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        panel = findViewById(R.id.panel)
        resultPanel = findViewById(R.id.result)
        busy = findViewById(R.id.busy)
        tvBusy = findViewById(R.id.tvBusy)
        tvQDesc = findViewById(R.id.tvQDesc)
        btnCreate = findViewById(R.id.btnCreate)

        val prefs = getSharedPreferences("pdf", Context.MODE_PRIVATE)
        pageMode = prefs.getInt("page", pageMode)
        marginMode = prefs.getInt("margin", marginMode)
        qualityMode = prefs.getInt("quality", qualityMode)

        buildSettings(prefs)

        adapter = ThumbAdapter()
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            selected = if (selected == pos) -1 else pos
            adapter.notifyDataSetChanged()
            updateStrip()
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { onBackPressed() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.btnAddEmpty).setOnClickListener { pickImages() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { pickImages() }
        findViewById<View>(R.id.btnCancel).setOnClickListener { cancel = true }
        btnCreate.setOnClickListener { create() }

        val left = findViewById<View>(R.id.btnLeft)
        val rotate = findViewById<View>(R.id.btnRotate)
        val delete = findViewById<View>(R.id.btnDelete)
        val right = findViewById<View>(R.id.btnRight)
        strip = listOf(left, rotate, delete, right)
        left.setOnClickListener { move(-1) }
        right.setOnClickListener { move(1) }
        rotate.setOnClickListener {
            if (selected in items.indices) {
                val it = items[selected]
                it.rotation = (it.rotation + 90) % 360
                adapter.notifyDataSetChanged()
            }
        }
        delete.setOnClickListener {
            if (selected in items.indices) {
                items.removeAt(selected)
                selected = -1
                refresh()
            }
        }

        findViewById<View>(R.id.btnShare).setOnClickListener { sharePdf() }
        findViewById<View>(R.id.btnSave).setOnClickListener { savePdf() }
        findViewById<View>(R.id.btnOpenPdf).setOnClickListener { openPdf() }
        findViewById<View>(R.id.btnNew).setOnClickListener {
            items.clear()
            selected = -1
            showEditor()
        }

        // Old results are not needed any more.
        File(cacheDir, "pdf").listFiles()?.forEach {
            if (System.currentTimeMillis() - it.lastModified() > 3_600_000L) it.delete()
        }

        addUris(urisFrom(intent))
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uris = urisFrom(intent)
        if (uris.isEmpty()) return
        if (resultPanel.visibility == View.VISIBLE) {
            items.clear()
            selected = -1
            showEditor()
        }
        addUris(uris)
        refresh()
    }

    override fun onDestroy() {
        cancel = true
        ui.removeCallbacksAndMessages(null)
        builder.shutdownNow()
        thumbPool.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            busy.visibility == View.VISIBLE -> cancel = true
            resultPanel.visibility == View.VISIBLE -> showEditor()
            else -> finish()
        }
    }

    // ------------------------------------------------------------------ input

    private fun urisFrom(i: Intent?): List<Uri> {
        val out = ArrayList<Uri>()
        if (i == null) return out
        try {
            when (i.action) {
                Intent.ACTION_SEND -> {
                    val u = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else @Suppress("DEPRECATION") (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
                    if (u != null) out.add(u)
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    val l = if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    if (l != null) out.addAll(l)
                }
            }
            if (out.isEmpty()) {
                i.clipData?.let { cd ->
                    for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { out.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun addUris(list: List<Uri>) {
        for (u in list) items.add(PdfBuilder.Item(u))
    }

    private fun pickImages() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(i, REQ_ADD)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.error_pdf, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_ADD -> {
                val cd = data.clipData
                if (cd != null) {
                    for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { items.add(PdfBuilder.Item(it)) }
                } else data.data?.let { items.add(PdfBuilder.Item(it)) }
                refresh()
            }
            REQ_SAVE -> data.data?.let { dest -> copyTo(dest) }
        }
    }

    private fun showMenu(anchor: View) {
        val p = PopupMenu(this, anchor)
        p.menuInflater.inflate(R.menu.pdf_menu, p.menu)
        p.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_add -> { pickImages(); true }
                R.id.action_clear -> { items.clear(); selected = -1; showEditor(); true }
                else -> false
            }
        }
        p.show()
    }

    // ------------------------------------------------------------------ editor state

    private fun refresh() {
        val has = items.isNotEmpty()
        if (selected >= items.size) selected = -1
        empty.visibility = if (has) View.GONE else View.VISIBLE
        grid.visibility = if (has) View.VISIBLE else View.GONE
        if (resultPanel.visibility != View.VISIBLE) panel.visibility = if (has) View.VISIBLE else View.GONE
        btnCreate.text = getString(R.string.pdf_create_n, items.size)
        btnCreate.isEnabled = has
        adapter.notifyDataSetChanged()
        updateStrip()
    }

    private fun updateStrip() {
        val sel = selected in items.indices
        strip[0].isEnabled = sel && selected > 0
        strip[1].isEnabled = sel
        strip[2].isEnabled = sel
        strip[3].isEnabled = sel && selected < items.size - 1
        for (v in strip) v.alpha = if (v.isEnabled) 1f else 0.3f
    }

    private fun move(d: Int) {
        val to = selected + d
        if (selected !in items.indices || to !in items.indices) return
        val t = items[selected]
        items[selected] = items[to]
        items[to] = t
        selected = to
        adapter.notifyDataSetChanged()
        updateStrip()
        grid.smoothScrollToPosition(to)
    }

    private fun showEditor() {
        resultPanel.visibility = View.GONE
        refresh()
    }

    // ------------------------------------------------------------------ settings UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildSettings(prefs: android.content.SharedPreferences) {
        val host = findViewById<LinearLayout>(R.id.settings)
        val qDesc = intArrayOf(R.string.q_desc_0, R.string.q_desc_1, R.string.q_desc_2, R.string.q_desc_3)
        tvQDesc.setText(qDesc[qualityMode])
        addRow(host, R.string.set_page,
            intArrayOf(R.string.page_fit, R.string.page_a4, R.string.page_letter), pageMode) {
            pageMode = it; prefs.edit().putInt("page", it).apply()
        }
        addRow(host, R.string.set_margin,
            intArrayOf(R.string.margin_none, R.string.margin_small, R.string.margin_normal), marginMode) {
            marginMode = it; prefs.edit().putInt("margin", it).apply()
        }
        addRow(host, R.string.set_quality,
            intArrayOf(R.string.q_original, R.string.q_high, R.string.q_smart, R.string.q_small), qualityMode) {
            qualityMode = it; prefs.edit().putInt("quality", it).apply()
            tvQDesc.setText(qDesc[it])
        }
    }

    private fun addRow(host: LinearLayout, label: Int, opts: IntArray, current: Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
        }
        row.addView(TextView(this).apply {
            setText(label)
            setTextColor(resources.getColor(R.color.text_secondary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        val segs = ArrayList<TextView>()
        fun paint(sel: Int) {
            for ((i, t) in segs.withIndex()) {
                t.setBackgroundResource(if (i == sel) R.drawable.bg_seg_on else R.drawable.bg_seg_off)
                t.setTextColor(if (i == sel) 0xFFFFFFFF.toInt() else 0xFFB4B9C4.toInt())
            }
        }
        for ((i, res) in opts.withIndex()) {
            val t = TextView(this).apply {
                setText(res)
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 1
                val lp = LinearLayout.LayoutParams(0, dp(32), 1f)
                lp.setMargins(dp(2), 0, dp(2), 0)
                layoutParams = lp
                setOnClickListener { paint(i); onPick(i) }
            }
            segs.add(t)
            row.addView(t)
        }
        paint(current)
        host.addView(row)
    }

    // ------------------------------------------------------------------ thumbnails

    private inner class ThumbAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_thumb, parent, false)
            val img = v.findViewById<ImageView>(R.id.img)
            val item = items[position]
            val key = "${item.uri}|${item.rotation}"
            v.findViewById<TextView>(R.id.num).text = (position + 1).toString()
            v.findViewById<View>(R.id.sel).visibility = if (position == selected) View.VISIBLE else View.GONE
            img.tag = key
            val c = cache.get(key)
            if (c != null) {
                img.setImageBitmap(c)
            } else {
                img.setImageDrawable(null)
                val uri = item.uri
                val rot = item.rotation
                try {
                    thumbPool.execute {
                        val b = decodeThumb(uri, rot)
                        if (b != null) {
                            cache.put(key, b)
                            ui.post { if (img.tag == key) img.setImageBitmap(b) }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            return v
        }
    }

    private fun decodeThumb(uri: Uri, rot: Int): Bitmap? {
        return try {
            val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bo) }
            if (bo.outWidth <= 0 || bo.outHeight <= 0) return null
            var s = 1
            while (bo.outWidth / (s * 2) >= 420 && bo.outHeight / (s * 2) >= 420) s *= 2
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
            } ?: return null
            var orient = 1
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    contentResolver.openInputStream(uri)?.use {
                        orient = ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                    }
                } catch (_: Throwable) {
                }
            }
            val m = PdfBuilder.matrixFor(orient, rot)
            if (m.isIdentity) bmp else {
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (r !== bmp) bmp.recycle()
                r
            }
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ create

    private fun create() {
        if (building || items.isEmpty()) return
        building = true
        cancel = false
        val list = items.map { PdfBuilder.Item(it.uri, it.rotation) }
        val opts = PdfBuilder.Options(pageMode, marginMode, qualityMode)
        val n = list.size
        tvBusy.text = getString(R.string.pdf_working, 1, n)
        busy.visibility = View.VISIBLE
        val ctx = applicationContext
        val name = "PDF_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".pdf"
        try {
            builder.execute {
                var res: PdfBuilder.Result? = null
                var failed = false
                val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
                val f = File(dir, name)
                try {
                    res = PdfBuilder.build(ctx, list, opts, f, name.removeSuffix(".pdf"),
                        { i -> ui.post { tvBusy.text = getString(R.string.pdf_working, i + 1, n) } },
                        { cancel })
                } catch (t: Throwable) {
                    android.util.Log.w("LitePPTX", "pdf failed", t)
                    failed = true
                }
                val r = res
                ui.post {
                    building = false
                    busy.visibility = View.GONE
                    when {
                        failed -> Toast.makeText(this, R.string.error_pdf, Toast.LENGTH_LONG).show()
                        r != null -> showResult(f, r)
                    }
                }
            }
        } catch (_: Exception) {
            building = false
            busy.visibility = View.GONE
        }
    }

    private fun showResult(f: File, r: PdfBuilder.Result) {
        pdfFile = f
        panel.visibility = View.GONE
        resultPanel.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvInfo).text =
            getString(R.string.pdf_info, r.pages, Formatter.formatShortFileSize(this, r.bytes))
        val tvSaved = findViewById<TextView>(R.id.tvSaved)
        val pct = if (r.sourceBytes > 0) (100 - r.bytes * 100 / r.sourceBytes).toInt() else 0
        if (pct >= 3) {
            tvSaved.text = getString(R.string.pdf_saved_pct, pct)
            tvSaved.visibility = View.VISIBLE
        } else tvSaved.visibility = View.GONE
    }

    // ------------------------------------------------------------------ result actions

    private fun sharePdf() {
        val f = pdfFile ?: return
        val uri = PdfShareProvider.uriFor(f.name)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(f.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        } catch (_: Exception) {
        }
    }

    private fun openPdf() {
        val f = pdfFile ?: return
        val uri = PdfShareProvider.uriFor(f.name)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            clipData = ClipData.newRawUri(f.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(view)
        } catch (_: ActivityNotFoundException) {
            sharePdf()
        }
    }

    private fun savePdf() {
        val f = pdfFile ?: return
        if (Build.VERSION.SDK_INT >= 29) {
            builder.execute {
                var ok = false
                try {
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                        put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val dest = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    if (dest != null) {
                        contentResolver.openOutputStream(dest)?.use { o -> f.inputStream().use { it.copyTo(o) } }
                        cv.clear()
                        cv.put(MediaStore.Downloads.IS_PENDING, 0)
                        contentResolver.update(dest, cv, null, null)
                        ok = true
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("LitePPTX", "save failed", t)
                }
                ui.post {
                    Toast.makeText(this, if (ok) R.string.saved_downloads else R.string.error_pdf, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/pdf"
                putExtra(Intent.EXTRA_TITLE, f.name)
            }
            try {
                startActivityForResult(i, REQ_SAVE)
            } catch (_: Exception) {
            }
        }
    }

    private fun copyTo(dest: Uri) {
        val f = pdfFile ?: return
        builder.execute {
            var ok = false
            try {
                contentResolver.openOutputStream(dest)?.use { o -> f.inputStream().use { it.copyTo(o) } }
                ok = true
            } catch (_: Throwable) {
            }
            ui.post {
                Toast.makeText(this, if (ok) R.string.saved_downloads else R.string.error_pdf, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        const val REQ_ADD = 2001
        const val REQ_SAVE = 2002
    }
}
