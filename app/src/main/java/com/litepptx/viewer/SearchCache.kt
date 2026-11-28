package com.litepptx.viewer

/**
 * In-memory cache of the text GlobalSearch already extracted from each recent
 * file. A search used to re-parse every file on every query; now the first
 * search extracts the text once and later searches read the cache. Entries are
 * keyed by file URI and invalidated by a fingerprint (size + last-modified),
 * so an edited file is re-parsed. Bounded by total text size, LRU-evicted,
 * never written to disk.
 */
object SearchCache {

    /** One searchable unit: a slide, a document block, or a sheet row. */
    class Unit(val pos: Int, val sheet: Int, val text: String)

    private class Entry(val fingerprint: String, val units: List<Unit>)

    private const val MAX_FILES = 24
    private const val MAX_CHARS = 2_000_000

    private val cache = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var totalChars = 0

    @Synchronized
    fun get(uri: String, fingerprint: String): List<Unit>? {
        val e = cache[uri] ?: return null
        if (e.fingerprint != fingerprint) {
            removeLocked(uri, e)
            return null
        }
        return e.units
    }

    @Synchronized
    fun put(uri: String, fingerprint: String, units: List<Unit>) {
        cache[uri]?.let { removeLocked(uri, it) }
        val chars = units.sumOf { it.text.length }
        if (chars > MAX_CHARS) return // one file too big to cache at all
        cache[uri] = Entry(fingerprint, units)
        totalChars += chars
        // Evict oldest first until both bounds hold. The entry just added is
        // the most recent, so it is only dropped if it alone breaks a bound
        // (which the check above already rules out).
        val it = cache.entries.iterator()
        while ((cache.size > MAX_FILES || totalChars > MAX_CHARS) && it.hasNext()) {
            val oldest = it.next()
            if (oldest.key == uri) break
            totalChars -= oldest.value.units.sumOf { u -> u.text.length }
            it.remove()
        }
        if (totalChars < 0) totalChars = 0
    }

    private fun removeLocked(uri: String, e: Entry) {
        cache.remove(uri)
        totalChars -= e.units.sumOf { it.text.length }
        if (totalChars < 0) totalChars = 0
    }
}
