package com.apps.widget

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/**
 * AppCompatEditText marker type; cursor drawing remains framework-controlled.
 *
 * defStyleAttr 必须使用 android.R.attr.editTextStyle 而非 0，否则 XML inflate
 * 时会丢失 EditText 的默认样式（包括 editable 行为相关属性），导致 IME 弹起
 * 但无法输入文字。
 */
class LauncherEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr)
