package com.litepptx.viewer.pdf

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import com.litepptx.viewer.R
import java.io.File

/** Share / open / save helpers for a finished PDF in cache/pdf (served by [PdfShareProvider]). */
object PdfActions {

    fun share(a: Activity, f: File, chooserTitle: String) {
        val uri = PdfShareProvider.uriFor(f.name)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(f.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            a.startActivity(Intent.createChooser(send, chooserTitle))
        } catch (_: Exception) {
        }
    }

    fun open(a: Activity, f: File, chooserTitle: String) {
        val uri = PdfShareProvider.uriFor(f.name)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            clipData = ClipData.newRawUri(f.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            a.startActivity(view)
        } catch (_: ActivityNotFoundException) {
            share(a, f, chooserTitle)
        }
    }

    /** Android 10+: writes straight into Downloads. Blocking, call off the main thread. */
    fun saveToDownloads(ctx: Context, f: File): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val dest = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return false
            ctx.contentResolver.openOutputStream(dest)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            cv.clear()
            cv.put(MediaStore.Downloads.IS_PENDING, 0)
            ctx.contentResolver.update(dest, cv, null, null)
            true
        } catch (t: Throwable) {
            android.util.Log.w("LitePPTX", "save failed", t)
            false
        }
    }

    /** Android 5-9: let the user pick where to save (no storage permission needed). */
    fun createDocumentIntent(name: String): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "application/pdf"
        putExtra(Intent.EXTRA_TITLE, name)
    }

    /** "Your PDF is ready" dialog. Its buttons keep it open, so a PDF can be saved and then shared. */
    fun showResult(a: Activity, f: File, pages: Int, bytes: Long, onSave: (File) -> Unit): AlertDialog {
        val dlg = AlertDialog.Builder(a)
            .setTitle(R.string.pdf_ready)
            .setMessage(a.getString(R.string.pdf_info, pages, Formatter.formatShortFileSize(a, bytes)))
            .setPositiveButton(R.string.share, null)
            .setNeutralButton(R.string.save, null)
            .setNegativeButton(R.string.open, null)
            .create()
        dlg.setOnShowListener {
            val title = a.getString(R.string.share)
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { share(a, f, title) }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { onSave(f) }
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { open(a, f, title) }
        }
        dlg.show()
        return dlg
    }

    fun copyTo(ctx: Context, f: File, dest: Uri): Boolean = try {
        ctx.contentResolver.openOutputStream(dest)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        true
    } catch (_: Throwable) {
        false
    }
}
