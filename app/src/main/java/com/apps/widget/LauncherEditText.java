package com.apps.widget;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

/**
 * 仅作为 AppCompatEditText 的统一子类存在，光标行为完全交由系统原生处理。
 * 不再覆盖 {@link #setTextCursorDrawable(android.graphics.drawable.Drawable)} 等方法，
 * 让 {@link com.apps.theme.LauncherTheme#styleTextInput} 等调用方设置的光标 drawable
 * 直接生效，由框架原生绘制。
 */
public class LauncherEditText extends AppCompatEditText {
    public LauncherEditText(Context context) { super(context); }
    public LauncherEditText(Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
    public LauncherEditText(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }
}
