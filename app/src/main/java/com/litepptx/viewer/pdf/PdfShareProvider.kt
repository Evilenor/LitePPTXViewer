package com.litepptx.viewer.pdf

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Serves finished PDFs from cache/pdf so they can be shared or opened without any storage permission. */
class PdfShareProvider : ContentProvider() {

    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (name.contains('/') || name.contains('\\') || name.contains("..")) throw FileNotFoundException()
        val f = File(File(context!!.cacheDir, "pdf"), name)
        if (!f.isFile) throw FileNotFoundException()
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment?.endsWith(".apk") == true) "application/vnd.android.package-archive" else "application/pdf"

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val f = try { fileFor(uri) } catch (_: Exception) { return null }
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else f.name })
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.litepptx.viewer.pdf"
        fun uriFor(name: String): Uri = Uri.parse("content://$AUTHORITY/${Uri.encode(name)}")
    }
}
