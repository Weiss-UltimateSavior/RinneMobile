package com.apps.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

import com.apps.theme.LauncherTheme;

/**
 * Consistent text cursor for Launcher fields on Android 8 and 9, where the
 * platform does not expose a public cursor-drawable API.
 */
public class LauncherEditText extends AppCompatEditText {
    private final Paint compatCursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean compatCursorVisible = true;

    public LauncherEditText(Context context) { super(context); init(); }
    public LauncherEditText(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public LauncherEditText(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        compatCursorPaint.setStrokeWidth(LauncherTheme.dp(getContext(), 2f));
        compatCursorPaint.setColor(LauncherTheme.primary(getContext()));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) super.setCursorVisible(false);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || !shouldDrawCompatCursor()) return;

        Layout layout = getLayout();
        if (layout == null) return;
        int offset = getSelectionStart();
        int line = layout.getLineForOffset(offset);
        float x = getCompoundPaddingLeft() + layout.getPrimaryHorizontal(offset) - getScrollX();
        float top = getExtendedPaddingTop() + layout.getLineTop(line) - getScrollY();
        float bottom = getExtendedPaddingTop() + layout.getLineBottom(line) - getScrollY();
        compatCursorPaint.setColor(LauncherTheme.primary(getContext()));
        canvas.drawLine(x, top, x, bottom, compatCursorPaint);
        postInvalidateDelayed(500L);
    }

    private boolean shouldDrawCompatCursor() {
        if (!isFocused() || !compatCursorVisible) return false;
        int start = getSelectionStart();
        return start >= 0 && start == getSelectionEnd() && (SystemClock.uptimeMillis() / 500L) % 2L == 0L;
    }

    @Override protected void onFocusChanged(boolean focused, int direction, @Nullable android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        invalidate();
    }

    @Override protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        invalidate();
    }

    @Override public void setCursorVisible(boolean visible) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            super.setCursorVisible(visible);
            return;
        }
        compatCursorVisible = visible;
        super.setCursorVisible(false);
        invalidate();
    }
}
