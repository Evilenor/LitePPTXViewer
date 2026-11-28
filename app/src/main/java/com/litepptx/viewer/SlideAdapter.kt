package com.litepptx.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import com.litepptx.viewer.pptx.Deck
import com.litepptx.viewer.pptx.Slide
import com.litepptx.viewer.pptx.SlideRenderer
import java.util.concurrent.Executors

/**
 * Renders slides lazily (only what is on / next to the screen) and keeps a small
 * memory-bounded cache, so even 300-slide decks use a constant amount of RAM.
 */
class SlideAdapter(
    context: Context,
    private val deck: Deck,
    private val widthPx: Int
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)
    private val renderer = SlideRenderer(deck)
    private val ratio = deck.hEmu.toFloat() / deck.wEmu.toFloat()
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private val pending = HashSet<Int>()
    private var list: ListView? = null
    @Volatile private var released = false

    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount

        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            // An evicted bitmap is only worth pooling when no visible row is
            // still showing it: redrawing into a displayed bitmap would change
            // the slide under the user.
            if (evicted && !released && bitmapPool.size < 3 && !isDisplayed(oldValue)) {
                bitmapPool.addLast(oldValue)
            }
        }
    }

    /** Bitmaps the renderer can draw into again instead of allocating. Main thread only. */
    private val bitmapPool = ArrayDeque<Bitmap>()

    /** True when a visible row is currently showing [bmp]. */
    private fun isDisplayed(bmp: Bitmap): Boolean {
        val lv = list ?: return false
        for (i in 0 until lv.childCount) {
            val iv = lv.getChildAt(i)?.findViewById<SlideImageView>(R.id.ivSlide) ?: continue
            val d = iv.drawable as? BitmapDrawable ?: continue
            if (d.bitmap === bmp) return true
        }
        return false
    }

    /** A pooled bitmap of the config this slide renders in, or null. Main thread only. */
    private fun takeReusable(slide: Slide): Bitmap? {
        val config = renderer.configFor(slide)
        val it = bitmapPool.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (b.config == config) {
                it.remove()
                return b
            }
        }
        return null
    }

    fun attach(lv: ListView) {
        list = lv
    }

    fun release() {
        released = true
        pool.shutdownNow()
        cache.evictAll()
        for (b in bitmapPool) try { b.recycle() } catch (_: Throwable) { }
        bitmapPool.clear()
        list = null
    }

    override fun getCount(): Int = deck.slides.size
    override fun getItem(position: Int): Any = deck.slides[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: inflater.inflate(R.layout.item_slide, parent, false)
        val iv = row.findViewById<SlideImageView>(R.id.ivSlide)
        iv.ratio = ratio
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
        val slide = deck.slides[pos]
        val reuse = takeReusable(slide)
        try {
            pool.execute {
                val bmp = try {
                    renderer.render(slide, widthPx, reuse)
                } catch (t: Throwable) {
                    android.util.Log.w("LitePPTX", "render failed", t)
                    null
                }
                main.post {
                    pending.remove(pos)
                    if (released) return@post
                    if (bmp != null) {
                        cache.put(pos, bmp)
                        showIfVisible(pos, bmp)
                    }
                }
            }
        } catch (_: Throwable) {
            pending.remove(pos)
            // The render never ran: hand the bitmap back to the pool.
            if (reuse != null && bitmapPool.size < 3) bitmapPool.addLast(reuse)
        }
    }

    private fun showIfVisible(pos: Int, bmp: Bitmap) {
        val lv = list ?: return
        for (i in 0 until lv.childCount) {
            val iv = lv.getChildAt(i)?.findViewById<SlideImageView>(R.id.ivSlide) ?: continue
            if (iv.tag == pos) {
                iv.setImageBitmap(bmp)
                lv.invalidate()
                break
            }
        }
    }
}
