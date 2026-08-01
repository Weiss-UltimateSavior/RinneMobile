package com.apps.HDModel

import android.app.Activity
import android.content.Intent
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

    /**
     * 在 HD 嵌入环境下代为启动截屏授权。
     *
     * LocalActivityManager 嵌入的 Activity 无法稳定接收 Activity Result；智能翻译设置页
     * 需要宿主 Fragment 代为启动 MediaProjection 授权，再把 resultCode/data 回传。
     */
    fun launchTranslationProjection(callback: (resultCode: Int, data: Intent?) -> Unit): Boolean {
        return false
    }

    /**
     * 在 HD 嵌入环境下代为申请通知权限。
     *
     * Android 13+ 前台 Service 通知权限使用 Activity Result API；嵌入 Activity 下同样
     * 需要由宿主 Fragment 接管回调。拒绝通知权限不阻止智能翻译悬浮服务启动。
     */
    fun requestTranslationNotificationPermission(callback: (Boolean) -> Unit): Boolean {
        return false
    }
}
