package com.litepptx.viewer.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.litepptx.viewer.pdf.PdfShareProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Self-updater backed by GitHub Releases. The only network requests the app ever makes are
 * "GET releases/latest" and, when the user taps Update, the APK download itself.
 */
object Updater {

    private const val REPO = "Evilenor/LitePPTXViewer"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val UA = "LitePPTX-Updater"

    class Info(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val size: Long,
        val sha256: String?,
        val pageUrl: String
    )

    class Cancelled : IOException("cancelled")

    fun installedVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    /** Returns release info if a newer build than the installed one exists, otherwise null. Throws on network errors. */
    fun check(ctx: Context): Info? {
        val c = open(API)
        try {
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            if (j.optBoolean("draft") || j.optBoolean("prerelease")) return null
            val tag = j.optString("tag_name").removePrefix("v").removePrefix("V")
            if (tag.isEmpty() || compare(tag, installedVersion(ctx)) <= 0) return null
            val assets = j.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val digest = a.optString("digest", "")
                return Info(
                    version = tag,
                    notes = j.optString("body", "").trim(),
                    apkUrl = a.getString("browser_download_url"),
                    size = a.optLong("size", -1),
                    sha256 = if (digest.startsWith("sha256:")) digest.substring(7).lowercase() else null,
                    pageUrl = j.optString("html_url")
                )
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** Downloads the APK into the share cache. Verifies size, SHA-256 (when GitHub provides it) and package identity. */
    fun download(
        ctx: Context,
        info: Info,
        progress: (done: Long, total: Long) -> Unit,
        cancelled: () -> Boolean
    ): File {
        val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name.endsWith(".apk") || it.name.endsWith(".part")) it.delete() }
        val part = File(dir, "LitePPTX-${info.version}.apk.part")
        val out = File(dir, "LitePPTX-${info.version}.apk")
        val md = MessageDigest.getInstance("SHA-256")
        val c = open(info.apkUrl)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else info.size
            var done = 0L
            var last = 0L
            c.inputStream.use { input ->
                FileOutputStream(part).use { os ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 120) { last = now; progress(done, total) }
                    }
                }
            }
            progress(done, total)
            if (info.size > 0 && part.length() != info.size) throw IOException("Incomplete download")
        } catch (t: Throwable) {
            part.delete()
            throw t
        } finally {
            c.disconnect()
        }

        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (info.sha256 != null && info.sha256 != hex) {
            part.delete()
            throw IOException("Checksum mismatch")
        }
        if (!part.renameTo(out)) throw IOException("Cannot store update")

        // Sanity check: must be this app.
        @Suppress("DEPRECATION")
        val pi = ctx.packageManager.getPackageArchiveInfo(out.path, 0)
        if (pi == null || pi.packageName != ctx.packageName) {
            out.delete()
            throw IOException("Not a LitePPTX package")
        }
        return out
    }

    /** True when the system lets this app hand the APK to the package installer. */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    fun installIntent(file: File): Intent {
        val uri = PdfShareProvider.uriFor(file.name)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Numeric dotted-version comparison: 1.10.0 > 1.9.3 > 1.9. */
    fun compare(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        return c
    }
}
