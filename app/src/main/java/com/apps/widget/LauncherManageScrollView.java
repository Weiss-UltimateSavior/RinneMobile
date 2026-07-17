package com.apps.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

/**
 * Management-page scroll container that keeps vertical drags available even when they begin
 * on a clickable action row.
 */
public class LauncherManageScrollView extends ScrollView {
    private final int touchSlop;
    private float downX;
    private float downY;
    private float lastDragY;
    private boolean rowDragInProgress;

    public LauncherManageScrollView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public LauncherManageScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public LauncherManageScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        boolean intercept = super.onInterceptTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                rowDragInProgress = false;
                return intercept;
            case MotionEvent.ACTION_MOVE:
                float distanceX = Math.abs(event.getX() - downX);
                float distanceY = Math.abs(event.getY() - downY);
                if (distanceY > touchSlop && distanceY > distanceX) {
                    // A row click owns a tap only; a vertical gesture always belongs to the list.
                    rowDragInProgress = true;
                    lastDragY = event.getY();
                    super.requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return intercept;
            default:
                return intercept;
        }
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // Action rows may handle taps, but must not prevent this container from handling a drag.
        if (!disallowIntercept) {
            super.requestDisallowInterceptTouchEvent(false);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!rowDragInProgress) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                int deltaY = Math.round(lastDragY - event.getY());
                if (deltaY != 0) scrollBy(0, deltaY);
                lastDragY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                rowDragInProgress = false;
                return true;
            default:
                return true;
        }
    }
}
