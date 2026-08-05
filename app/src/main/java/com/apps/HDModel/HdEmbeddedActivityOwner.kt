package com.apps.HDModel

import android.app.Activity

/** Lets the HD shell dismiss an embedded child Activity without finishing HdModeActivity. */
internal interface HdEmbeddedActivityOwner {
    fun closeEmbeddedActivity(child: Activity? = null): Boolean
}
