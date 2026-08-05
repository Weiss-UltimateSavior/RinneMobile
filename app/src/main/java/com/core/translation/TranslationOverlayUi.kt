package com.core.translation

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.core.R
import com.core.launcher.LauncherUiBridge

/**
 * 悬浮翻译浮层 UI（重构计划 3.5 阶段 94 拆分自 OverlayTranslationService）。
 *
 * 负责悬浮按钮与加载/结果卡片的 WindowManager 渲染；按钮的点击/长按通过
 * 构造回调交付给 Service 处理。行为与原 Service 私有实现逐字等价。
 */
internal class TranslationOverlayUi(
    private val context: Context,
    private val windowManager: WindowManager,
    private val mainHandler: Handler,
    private val onTranslateClick: () -> Unit,
    private val onLongPress: () -> Unit
) {

    companion object {
        private const val TAG = "OverlayTranslation"
    }

    private var floatingButton: View? = null
    private var floatingButtonParams: WindowManager.LayoutParams? = null
    private var resultCard: View? = null

    /** Rebuilds the overlay so its color and logo track the newly selected Launcher theme. */
    fun refreshTheme() {
        if (floatingButton == null) return
        removeFloatingButton()
        try {
            showFloatingButton()
        } catch (error: Exception) {
            Log.e(TAG, "refreshFloatingButtonTheme failed", error)
        }
    }

    /**
     * 创建并显示悬浮按钮。
     * 按钮支持拖动，点击触发截图翻译。
     */
    fun showFloatingButton() {
        val button = createFloatingButton()
        val params = floatingButtonParams ?: createOverlayParams().apply {
            gravity = Gravity.TOP or Gravity.START
            x = context.resources.displayMetrics.widthPixels - dp(51)
            y = dp(80)
        }
        // WindowManager 会用自身的 LayoutParams 覆盖 View 的初始 LayoutParams；
        // 显式固定窗口尺寸，避免圆形按钮随内部图标缩小而一起缩小。
        params.width = dp(35)
        params.height = dp(35)
        setupButtonTouchListener(button, params)
        windowManager.addView(button, params)
        floatingButton = button
        floatingButtonParams = params
    }

    private fun createFloatingButton(): View {
        val size = dp(35)
        // 跟随主题色调：取主题 primary 色，叠加 80% 不透明度（与原 #CC18B978 视觉一致）
        val primaryColor = LauncherUiBridge.primaryColor(context)
        val buttonColor = (0xCC shl 24) or (primaryColor and 0x00FFFFFF)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(buttonColor)
            }
            // 阴影：elevation 需要 API 21+，且背景为 GradientDrawable 时可正常投影
            elevation = dp(8).toFloat()
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        val icon = android.widget.ImageView(context).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            setImageResource(LauncherUiBridge.themeLogoRes(context))
            setColorFilter(Color.WHITE)
        }
        container.addView(icon)
        return container
    }

    private fun createOverlayParams(): WindowManager.LayoutParams {
        val type = overlayWindowType()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
    }

    /**
     * 按钮触摸处理：区分拖动、点击与长按。
     * 拖动更新悬浮位置；点击触发截图翻译；长按打开关闭确认。
     */
    private fun setupButtonTouchListener(button: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var hasMoved = false
        var longPressHandled = false
        val longPress = Runnable {
            if (!hasMoved) {
                longPressHandled = true
                button.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onLongPress()
            }
        }
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    hasMoved = false
                    longPressHandled = false
                    mainHandler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) {
                        hasMoved = true
                        mainHandler.removeCallbacks(longPress)
                    }
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    try {
                        windowManager.updateViewLayout(button, params)
                    } catch (_: Exception) {
                        // 悬浮按钮已移除/布局被替换时更新抛异常可安全忽略（拖动位置更新尽力而为）
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPress)
                    if (!hasMoved && !longPressHandled) onTranslateClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }
    }

    fun removeFloatingButton() {
        floatingButton?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
                // 悬浮按钮可能已被系统移除，removeView 抛异常可安全忽略（清理尽力而为）
            }
        }
        floatingButton = null
    }

    /**
     * 显示加载中悬浮卡片。
     */
    fun showLoadingCard() {
        removeResultCard()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#F0222222"))
            }
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val progress = ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            indeterminateDrawable?.setColorFilter(
                LauncherUiBridge.primaryColor(context),
                PorterDuff.Mode.SRC_IN
            )
        }
        val text = TextView(context).apply {
            text = context.getString(R.string.translation_translating)
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
        }
        row.addView(progress)
        row.addView(text)
        container.addView(row)
        val params = createOverlayParams().apply {
            gravity = Gravity.CENTER
        }
        try {
            windowManager.addView(container, params)
            resultCard = container
        } catch (e: Exception) {
            Log.e(TAG, "showLoadingCard failed", e)
        }
    }

    /**
     * 显示翻译结果悬浮卡片，点击关闭。
     */
    fun showResultCard(success: Boolean, text: String) {
        removeResultCard()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#F0222222"))
            }
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        val titleView = TextView(context).apply {
            this.text = context.getString(
                if (success) R.string.translation_result_tap_to_close
                else R.string.translation_failed_tap_to_close
            )
            // 成功时标题色跟随主题色调，失败时固定红色
            setTextColor(if (success) LauncherUiBridge.primaryColor(context) else Color.parseColor("#FF6B6B"))
            textSize = 11f
        }
        val msgView = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            setLineSpacing(2f, 1f)
            setPadding(0, dp(8), 0, 0)
        }
        container.addView(titleView)
        container.addView(msgView)
        container.setOnClickListener { removeResultCard() }
        val params = createOverlayParams().apply {
            gravity = Gravity.CENTER
            width = (context.resources.displayMetrics.widthPixels * 0.82f).toInt()
        }
        try {
            windowManager.addView(container, params)
            resultCard = container
        } catch (e: Exception) {
            Log.e(TAG, "showResultCard failed", e)
        }
    }

    fun removeResultCard() {
        resultCard?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
                // 结果卡片可能已被系统移除，removeView 抛异常可安全忽略（清理尽力而为）
            }
        }
        resultCard = null
    }

    fun overlayWindowType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
