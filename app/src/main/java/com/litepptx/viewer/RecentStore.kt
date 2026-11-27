package com.litepptx.viewer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The "Recent" list on the start screen: the last few presentations with the slide you were on.
 * Stored privately as a tiny JSON string, never leaves the phone.
 */
object RecentStore {

    class Entry(
        val uri: String,
        val name: String,
        val time: Long,
        val slides: Int,
        val last: Int,
        val kind: String = "pptx" // "pptx" | "doc" | "sheet"
    )

    private const val KEY = "recent"
    private const val MAX = 8

    fun load(ctx: Context): List<Entry> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Entry(
                    o.getString("u"), o.optString("n"), o.optLong("t"),
                    o.optInt("s"), o.optInt("l"), o.optString("k", "pptx")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun find(ctx: Context, uri: String): Entry? = load(ctx).firstOrNull { it.uri == uri }

    /** Puts the file at the top. Keeps the saved position when the same file is opened again. */
    fun touch(ctx: Context, uri: String, name: String, slides: Int, kind: String = "pptx") {
        val old = find(ctx, uri)
        val keep = old?.last?.takeIf { it in 0 until slides } ?: 0
        save(ctx, listOf(Entry(uri, name, System.currentTimeMillis(), slides, keep, kind)) + load(ctx).filter { it.uri != uri })
    }

    fun setLast(ctx: Context, uri: String, last: Int) {
        val all = load(ctx)
        if (all.none { it.uri == uri }) return
        save(ctx, all.map { if (it.uri == uri) Entry(it.uri, it.name, it.time, it.slides, last, it.kind) else it })
    }

    fun remove(ctx: Context, uri: String) = save(ctx, load(ctx).filter { it.uri != uri })

    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY).apply()

    private fun save(ctx: Context, list: List<Entry>) {
        val a = JSONArray()
        list.take(MAX).forEach {
            a.put(
                JSONObject().put("u", it.uri).put("n", it.name).put("t", it.time)
                    .put("s", it.slides).put("l", it.last).put("k", it.kind)
            )
        }
        prefs(ctx).edit().putString(KEY, a.toString()).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("lite", Context.MODE_PRIVATE)
}
