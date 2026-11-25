package com.litepptx.viewer

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import com.litepptx.viewer.pptx.Deck
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
    }

    fun attach(lv: ListView) {
        list = lv
    }

    fun release() {
        released = true
        pool.shutdownNow()
        cache.evictAll()
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
        try {
            pool.execute {
                val bmp = try {
                    renderer.render(slide, widthPx)
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
