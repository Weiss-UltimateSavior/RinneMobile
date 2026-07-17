package com.apps.widget;

import android.content.Context;
import android.util.AttributeSet;

/**
 * Backward-compatible name for the management page's clickable-row scroll container.
 */
public class LauncherManageScrollView extends LauncherClickableRowScrollView {

    public LauncherManageScrollView(Context context) {
        super(context);
    }

    public LauncherManageScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public LauncherManageScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
}
