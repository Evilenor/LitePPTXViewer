package com.litepptx.viewer.scan

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Private working folder of the scanner (cache/scan). The system camera writes the photo here and
 * the PDF maker reads the finished pages from here, so no storage permission is ever needed.
 */
class ScanProvider : ContentProvider() {

    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (name.contains('/') || name.contains('\\') || name.contains("..")) throw FileNotFoundException()
        return File(dir(context!!), name)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileFor(uri)
        val m = ParcelFileDescriptor.parseMode(mode)
        if (mode.contains('w')) f.parentFile?.mkdirs() else if (!f.isFile) throw FileNotFoundException()
        return ParcelFileDescriptor.open(f, m)
    }

    override fun getType(uri: Uri): String = "image/jpeg"

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
        const val AUTHORITY = "com.litepptx.viewer.scan"

        fun dir(ctx: Context): File = File(ctx.cacheDir, "scan")

        fun uriFor(name: String): Uri = Uri.parse("content://$AUTHORITY/${Uri.encode(name)}")
    }
}
