package com.core.translation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.core.CorePreferences
import com.core.R
import com.core.launcher.LauncherUiBridge
import com.core.prefs.LauncherMainKeys
import com.core.util.AppExecutors

/**
 * 悬浮翻译 Service。
 *
 * 启动后通过 WindowManager 添加一个可拖动的悬浮按钮，覆盖在任意应用上方。
 * 点击按钮时通过缓存的 [MediaProjection] 截取当前屏幕，压缩为 JPEG 后
 * 调用 [VisionTranslationClient] 进行翻译，结果展示在悬浮卡片上。
 *
 * 不依赖任何模拟器代码，与游戏进程完全解耦。
 *
 * 职责切分（重构计划 3.5 阶段 94，§8:323 按职责切片）：
 *   - 截屏捕获链路 → [TranslationCapture]
 *   - 悬浮按钮/结果卡片 UI → [TranslationOverlayUi]
 * 本类保留 Service 生命周期、前台通知、翻译编排与关闭确认弹窗，公开 API
 * （Service 类名 / companion projectionData、projectionResultCode）不变。
 */
class OverlayTranslationService : Service() {

    companion object {
        private const val CHANNEL_ID = "translation_overlay"
        private const val NOTIFICATION_ID = 10086
        private const val PROJECTION_INIT_DELAY_MS = 500L

        /**
         * 缓存 MediaProjection 授权结果，避免每次截图都弹授权框。
         * 由翻译设置页在授权回调中写入。
         */
        @Volatile
        var projectionData: Intent? = null
        @Volatile
        var projectionResultCode: Int = 0
    }

    private lateinit var windowManager: WindowManager
    private lateinit var handler: Handler
    private lateinit var capture: TranslationCapture
    private lateinit var overlayUi: TranslationOverlayUi
    private var projectionInitTask: Runnable? = null
    private var translationRetryTask: Runnable? = null
    private var isTranslating = false
    private var closeConfirmShowing = false
    private lateinit var themePreferences: SharedPreferences
    private val themePreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == LauncherMainKeys.KEY_LAUNCHER_THEME_STYLE) {
            handler.post { overlayUi.refreshTheme() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())
        capture = TranslationCapture(this, handler).also { it.init() }
        overlayUi = TranslationOverlayUi(
            this, windowManager, handler,
            onTranslateClick = { triggerTranslation() },
            onLongPress = { showDisableTranslationConfirm() }
        )
        themePreferences = getSharedPreferences(CorePreferences.APP_PREFS, Context.MODE_PRIVATE)
        themePreferences.registerOnSharedPreferenceChangeListener(themePreferenceListener)
        startForegroundCompat()
        overlayUi.showFloatingButton()
        // 延迟创建 MediaProjection，等待前台 Service 完全就绪。
        val task = Runnable { capture.ensureProjection() }
        projectionInitTask = task
        handler.postDelayed(task, PROJECTION_INIT_DELAY_MS)
        Toast.makeText(this, R.string.translation_overlay_enabled, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::themePreferences.isInitialized) {
            themePreferences.unregisterOnSharedPreferenceChangeListener(themePreferenceListener)
        }
        projectionInitTask?.let { handler.removeCallbacks(it) }
        projectionInitTask = null
        translationRetryTask?.let { handler.removeCallbacks(it) }
        translationRetryTask = null
        overlayUi.removeFloatingButton()
        overlayUi.removeResultCard()
        capture.stop()
        Toast.makeText(this, R.string.translation_overlay_disabled, Toast.LENGTH_SHORT).show()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.translation_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.translation_service_running)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.translation_title))
            .setContentText(getString(R.string.translation_service_running))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 触发截图翻译流程。
     *
     * 捕获未就绪时先重建 MediaProjection 并等待首帧；就绪后取最新帧转 JPEG
     * 并调用 [VisionTranslationClient] 翻译，结果通过悬浮卡片展示。
     */
    private fun triggerTranslation() {
        if (isTranslating) {
            overlayUi.showResultCard(false, getString(R.string.translation_already_in_progress))
            return
        }
        if (!capture.projectionReady || !capture.hasActiveProjection()) {
            capture.ensureProjection()
            if (!capture.projectionReady || !capture.hasActiveProjection()) {
                overlayUi.showResultCard(false, getString(R.string.translation_capture_permission_expired))
                return
            }
            // 刚重建 projection，等待系统推送第一帧
            val task = Runnable {
                // 本次等待并非实际翻译；先解除占用状态，再进入正常截图与请求流程。
                isTranslating = false
                triggerTranslation()
            }
            translationRetryTask = task
            handler.postDelayed(task, 800)
            overlayUi.showLoadingCard()
            isTranslating = true
            return
        }
        isTranslating = true
        overlayUi.showLoadingCard()
        AppExecutors.runOnSingle {
            val jpegBytes = capture.takeLatestJpegBytes()
            if (jpegBytes == null) {
                handler.post {
                    isTranslating = false
                    overlayUi.showResultCard(false, getString(R.string.translation_capture_failed))
                }
                return@runOnSingle
            }
            val result = VisionTranslationClient.translate(this, jpegBytes)
            handler.post {
                isTranslating = false
                overlayUi.showResultCard(result.success, result.text)
            }
        }
    }

    private fun showDisableTranslationConfirm() {
        if (closeConfirmShowing) return
        closeConfirmShowing = true
        // Service 上下文不继承 Activity 的 AppCompat 主题；统一 Launcher 弹窗需要显式包装主题。
        val dialogContext = ContextThemeWrapper(this, com.core.R.style.Theme_YukiHub_Launcher)
        val shown = LauncherUiBridge.showOverlayConfirm(
            dialogContext,
            getString(R.string.translation_close_overlay_title),
            getString(R.string.translation_close_overlay_message),
            getString(R.string.translation_close),
            overlayUi.overlayWindowType(),
            {
                TranslationConfigStore.setEnabled(this, false)
                stopSelf()
            },
            { closeConfirmShowing = false }
        )
        if (!shown) closeConfirmShowing = false
    }
}
