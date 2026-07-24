package com.apps.PadUi

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.roundToInt

/** Card container that uses a 3:5 aspect ratio unless a fixed height is supplied. */
class PadGameCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private var fixedCardHeight = 0

    fun setFixedCardHeight(height: Int) {
        val normalizedHeight = max(0, height)
        if (fixedCardHeight == normalizedHeight) return
        fixedCardHeight = normalizedHeight
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (fixedCardHeight > 0) fixedCardHeight else (width * 5f / 3f).roundToInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}
