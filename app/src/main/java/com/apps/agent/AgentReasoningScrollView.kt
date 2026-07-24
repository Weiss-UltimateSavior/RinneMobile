package com.apps.agent

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.widget.NestedScrollView

/** Keeps vertical gestures inside the reasoning transcript instead of its parent RecyclerView. */
class AgentReasoningScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {
    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        val action = event?.actionMasked ?: MotionEvent.ACTION_CANCEL
        parent?.requestDisallowInterceptTouchEvent(
            action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
        )
        return super.dispatchTouchEvent(event)
    }
}
