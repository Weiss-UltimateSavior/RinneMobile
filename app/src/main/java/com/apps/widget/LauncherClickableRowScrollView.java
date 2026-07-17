package com.apps.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

/**
 * Scroll container for vertically stacked clickable rows.
 *
 * <p>Some devices leave a drag with the row that received ACTION_DOWN instead of allowing a
 * regular {@link ScrollView} to intercept it. A tap remains available to the row, while a
 * predominantly vertical gesture is explicitly handled by this container.</p>
 */
public class LauncherClickableRowScrollView extends ScrollView {
    private final int touchSlop;
    private float downX;
    private float downY;
    private float lastDragY;
    private boolean rowDragInProgress;

    public LauncherClickableRowScrollView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public LauncherClickableRowScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public LauncherClickableRowScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
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
        // A child row may own a tap, but it cannot keep a vertical drag from the list.
        if (!disallowIntercept) {
            super.requestDisallowInterceptTouchEvent(false);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!rowDragInProgress) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                int deltaY = Math.round(lastDragY - event.getY());
                if (deltaY != 0) {
                    scrollBy(0, deltaY);
                }
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
