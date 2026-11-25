package com.litepptx.viewer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowInsets
import android.widget.AbsListView
import android.widget.Button
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.litepptx.viewer.pptx.Deck
import com.litepptx.viewer.pptx.PptxParser
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile

class MainActivity : Activity() {

    private lateinit var tvFileName: TextView
    private lateinit var tvSlideCounter: TextView
    private lateinit var list: ZoomableListView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyState: View

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

    override fun onDestroy() {
        token++
        ui.removeCallbacksAndMessages(null)
        adapter?.release()
        deck?.close()
        io.shutdownNow()
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
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.viewer_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_open -> { pickFile(); true }
                R.id.action_share -> { share(); true }
                R.id.action_reset_zoom -> { list.resetZoom(); true }
                else -> false
            }
        }
        popup.show()
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
    }
}
