package com.apps.agent;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

/** Keeps vertical gestures inside the reasoning transcript instead of the parent RecyclerView. */
public final class AgentReasoningScrollView extends NestedScrollView {
    public AgentReasoningScrollView(@NonNull Context context) { super(context); }
    public AgentReasoningScrollView(@NonNull Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
    public AgentReasoningScrollView(@NonNull Context context, @Nullable AttributeSet attrs, int style) {
        super(context, attrs, style);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        ViewParent parent = getParent();
        int action = event == null ? MotionEvent.ACTION_CANCEL : event.getActionMasked();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(
                    action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL);
        }
        return super.dispatchTouchEvent(event);
    }
}
