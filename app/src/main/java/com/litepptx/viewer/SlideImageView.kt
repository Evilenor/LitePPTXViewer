package com.litepptx.viewer

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

/** ImageView whose height is always width * ratio, so list rows never jump while slides render. */
class SlideImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    var ratio = 0.5625f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * ratio).toInt().coerceAtLeast(1))
    }
}
