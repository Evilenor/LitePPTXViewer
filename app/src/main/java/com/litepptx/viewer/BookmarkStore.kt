package com.litepptx.viewer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bookmarks: a saved position inside a file, so the user can jump straight back to it.
 * Stored privately as a tiny JSON string, never leaves the phone. Modeled on [RecentStore].
 */
object BookmarkStore {

    class Entry(
        val uri: String,
        val name: String,
        val kind: String, // "pptx" | "doc" | "sheet" | "pdf"
        val pos: Int, // slide index, doc block index, sheet list position, or PDF page (0-based)
        val label: String,
        val time: Long,
        val sheet: Int = 0 // sheet index for "sheet" bookmarks; 0 otherwise
    )

    private const val KEY = "bookmarks"
    private const val MAX = 50

    fun load(ctx: Context): List<Entry> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Entry(
                    o.getString("u"), o.optString("n"), o.optString("k", "pptx"),
                    o.optInt("p"), o.optString("l"), o.optLong("t"), o.optInt("sh", 0)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun find(ctx: Context, uri: String, pos: Int, sheet: Int = 0): Entry? =
        load(ctx).firstOrNull { it.uri == uri && it.pos == pos && it.sheet == sheet }

    /** Adds the bookmark, replacing any earlier one at the same position. Newest first. */
    fun add(ctx: Context, e: Entry) {
        save(ctx, listOf(e) + load(ctx).filterNot { it.uri == e.uri && it.pos == e.pos && it.sheet == e.sheet })
    }

    fun remove(ctx: Context, uri: String, pos: Int, sheet: Int = 0) =
        save(ctx, load(ctx).filterNot { it.uri == uri && it.pos == pos && it.sheet == sheet })

    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY).apply()

    /** "Slide 3" / "Block 12" / "Row 5" / "Page 4" — the number is the one the counter shows. */
    fun label(ctx: Context, kind: String, pos: Int): String = when (kind) {
        "doc" -> ctx.getString(R.string.bookmark_block, pos + 1)
        "sheet" -> ctx.getString(R.string.bookmark_row, pos + 1)
        "pdf" -> ctx.getString(R.string.bookmark_page, pos + 1)
        else -> ctx.getString(R.string.bookmark_slide, pos + 1)
    }

    private fun save(ctx: Context, list: List<Entry>) {
        val a = JSONArray()
        list.take(MAX).forEach {
            a.put(
                JSONObject().put("u", it.uri).put("n", it.name).put("k", it.kind)
                    .put("p", it.pos).put("l", it.label).put("t", it.time).put("sh", it.sheet)
            )
        }
        prefs(ctx).edit().putString(KEY, a.toString()).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("lite", Context.MODE_PRIVATE)
}
