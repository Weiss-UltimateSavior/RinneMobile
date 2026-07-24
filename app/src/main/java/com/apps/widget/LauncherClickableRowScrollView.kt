package com.apps.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Scroll container that preserves row taps while explicitly handling vertical drags. */
open class LauncherClickableRowScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastDragY = 0f
    private var rowDragInProgress = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        val intercept = super.onInterceptTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                rowDragInProgress = false
                return intercept
            }
            MotionEvent.ACTION_MOVE -> {
                val distanceX = abs(event.x - downX)
                val distanceY = abs(event.y - downY)
                if (distanceY > touchSlop && distanceY > distanceX) {
                    rowDragInProgress = true
                    lastDragY = event.y
                    super.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return intercept
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // A child row may own a tap, but it cannot keep a vertical drag from this container.
        if (!disallowIntercept) super.requestDisallowInterceptTouchEvent(false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!rowDragInProgress) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val deltaY = (lastDragY - event.y).roundToInt()
                if (deltaY != 0) scrollBy(0, deltaY)
                lastDragY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> rowDragInProgress = false
        }
        return true
    }
}
