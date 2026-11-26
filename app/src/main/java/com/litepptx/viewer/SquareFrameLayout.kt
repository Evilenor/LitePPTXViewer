package com.litepptx.viewer

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** FrameLayout that is always as tall as it is wide (grid thumbnails). */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        )
    }
}
