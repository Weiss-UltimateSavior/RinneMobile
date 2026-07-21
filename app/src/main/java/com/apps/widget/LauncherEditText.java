package com.apps.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.Layout;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

import com.apps.theme.LauncherTheme;

/**
 * Consistent text cursor for Launcher fields on all API levels. The platform
 * cursor drawable cannot be reliably recolored: {@code setTextCursorTintList}
 * is a hidden API blocked by non-SDK enforcement on Q+, and replacing the
 * drawable via {@code setTextCursorDrawable} conflicts with the drawable
 * installed by {@code LauncherTheme.styleTextInput}. Instead we always hide
 * the system cursor and draw a compat cursor in {@link #onDraw(Canvas)} so
 * {@link #setCursorColor(int)} fully controls the color.
 */
public class LauncherEditText extends AppCompatEditText {
    private final Paint compatCursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean compatCursorVisible = true;
    private int overrideCursorColor;

    /**
     * 透明 1×1 占位 drawable。设置为系统 textCursorDrawable 后，框架认为 drawable
     * 已存在（非 null），不会从主题回退加载默认光标；同时绘制结果完全不可见。
     * 避免 setTextCursorDrawable(null) / getTextCursorDrawable() 返回 null 在
     * Android 12+ 触发 theme fallback 重新加载系统光标的问题。
     */
    private static final Drawable INVISIBLE_CURSOR = new ColorDrawable(Color.TRANSPARENT);

    public LauncherEditText(Context context) { super(context); init(); }
    public LauncherEditText(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public LauncherEditText(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        compatCursorPaint.setStrokeWidth(LauncherTheme.dp(getContext(), 2f));
        compatCursorPaint.setColor(LauncherTheme.primary(getContext()));
        super.setCursorVisible(false);
        // 用透明占位 drawable 替代 null：阻止框架从主题重新加载默认光标
        super.setTextCursorDrawable(INVISIBLE_CURSOR);
    }

    @Override protected void onDraw(Canvas canvas) {
        // 双维度强制禁用系统光标（在 super.onDraw 前最后一刻）：
        // 1. setCursorVisible(false) → Editor.mCursorVisible = false → drawCursor() 短路返回
        // 2. setTextCursorDrawable(INVISIBLE_CURSOR) → 即使 mCursorVisible 被 OEM 代码绕过，
        //    drawable 也是透明的，绘制结果不可见
        super.setCursorVisible(false);
        super.setTextCursorDrawable(INVISIBLE_CURSOR);
        super.onDraw(canvas);
        if (isFocused() && compatCursorVisible && isCursorSinglePoint()) {
            postInvalidateDelayed(500L);
        }
        if (!shouldDrawCompatCursor()) return;

        Layout layout = getLayout();
        if (layout == null) return;
        int offset = getSelectionStart();
        int line = layout.getLineForOffset(offset);
        float x = getCompoundPaddingLeft() + layout.getPrimaryHorizontal(offset) - getScrollX();
        float top = getExtendedPaddingTop() + layout.getLineTop(line) - getScrollY();
        float bottom = getExtendedPaddingTop() + layout.getLineBottom(line) - getScrollY();
        compatCursorPaint.setColor(overrideCursorColor != 0 ? overrideCursorColor : LauncherTheme.primary(getContext()));
        canvas.drawLine(x, top, x, bottom, compatCursorPaint);
    }

    private boolean isCursorSinglePoint() {
        int start = getSelectionStart();
        return start >= 0 && start == getSelectionEnd();
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
        compatCursorVisible = visible;
        super.setCursorVisible(false);
        invalidate();
    }

    /**
     * 拦截外部对系统光标 drawable 的设置（如 LauncherTheme.styleTextInput()），
     * 始终替换为透明占位 drawable，防止系统光标可见。
     */
    @Override public void setTextCursorDrawable(Drawable drawable) {
        super.setTextCursorDrawable(INVISIBLE_CURSOR);
    }

    @Override public Drawable getTextCursorDrawable() {
        return INVISIBLE_CURSOR;
    }

    /** Override the cursor color; 0 resets to the default primary tone. */
    public void setCursorColor(int color) {
        overrideCursorColor = color;
        invalidate();
    }
}
