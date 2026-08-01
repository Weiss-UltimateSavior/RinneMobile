package com.apps

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import java.io.File

object LauncherSplash {

    private const val KEY_SPLASH_ENABLED = "launcher_splash_enabled"
    private const val CUSTOM_SPLASH_IMAGE_FILE = "launcher_splash_image"

    /** The image is kept in private storage so it remains available after URI grants expire. */
    @JvmStatic
    fun customSplashImageFile(context: Context): File =
        File(context.applicationContext.filesDir, CUSTOM_SPLASH_IMAGE_FILE)

    @JvmStatic
    fun hasCustomSplashImage(context: Context): Boolean =
        customSplashImageFile(context).isFile

    /** 是否在应用启动时显示启动图片。 */
    @JvmStatic
    fun isSplashImageEnabled(context: Context): Boolean =
        context.getSharedPreferences(LauncherPreferences.APP_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SPLASH_ENABLED, true)

    @JvmStatic
    fun setSplashImageEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(LauncherPreferences.APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SPLASH_ENABLED, enabled)
            .apply()
    }

    @JvmStatic
    fun applyCustomSplashImage(context: Context, imageView: ImageView?) {
        if (imageView == null) return
        val imageFile = customSplashImageFile(context)
        if (!imageFile.isFile) return
        // 隐藏 ImageView 避免 setContentView 后先显示 XML 默认启动图，
        // 待自定义图片解码成功后再设为 VISIBLE，消除"默认图→自定义图"的视觉切换。
        imageView.visibility = View.INVISIBLE
        try {
            imageView.setImageURI(Uri.fromFile(imageFile))
            if (imageView.drawable != null) {
                imageView.visibility = View.VISIBLE
            } else {
                // 异步解码：等待 drawable 就绪后再显示，500ms 超时后回退默认图
                val handler = Handler(Looper.getMainLooper())
                val preDraw = object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (imageView.drawable != null) {
                            imageView.viewTreeObserver.removeOnPreDrawListener(this)
                            handler.removeCallbacksAndMessages(null)
                            imageView.visibility = View.VISIBLE
                            return true
                        }
                        return false
                    }
                }
                imageView.viewTreeObserver.addOnPreDrawListener(preDraw)
                handler.postDelayed({
                    if (imageView.visibility != View.VISIBLE) {
                        imageView.viewTreeObserver.removeOnPreDrawListener(preDraw)
                        imageView.visibility = View.VISIBLE
                    }
                }, 500)
            }
        } catch (_: Throwable) {
            // 解码失败时恢复显示默认启动图
            imageView.visibility = View.VISIBLE
        }
    }
}
