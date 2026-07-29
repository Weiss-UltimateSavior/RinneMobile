package com.apps.HDModel

import android.app.Activity
import android.net.Uri

/** Lets the HD shell dismiss an embedded child Activity without finishing HdModeActivity. */
internal interface HdEmbeddedActivityOwner {
    fun closeEmbeddedActivity(child: Activity? = null): Boolean

    /**
     * 在 HD 嵌入环境下代为启动系统图片选择器。
     *
     * 嵌入到 LocalActivityManager 中的 Activity 无法收到 startActivityForResult 的回调，
     * 因此由宿主 Fragment 使用自身的 ActivityResultRegistry 启动选择器，
     * 再通过 [callback] 把选中的 Uri 回传给发起请求的嵌入 Activity。
     *
     * @return true 表示宿主已接管；false 表示当前没有可用宿主，调用方需自行启动选择器。
     */
    fun launchSplashImagePicker(callback: (Uri?) -> Unit): Boolean {
        return false
    }
}
