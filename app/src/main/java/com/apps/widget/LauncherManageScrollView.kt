package com.apps.widget

import android.content.Context
import android.util.AttributeSet

/** Backward-compatible XML name for the management-page scroll container. */
class LauncherManageScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LauncherClickableRowScrollView(context, attrs, defStyleAttr)
