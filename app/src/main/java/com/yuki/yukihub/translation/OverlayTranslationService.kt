package com.yuki.yukihub.translation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.media.Image
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.appcompat.app.AlertDialog
import com.apps.theme.LauncherDialogFactory
import com.yuki.yukihub.util.AppExecutors
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 悬浮翻译 Service。
 *
 * 启动后通过 WindowManager 添加一个可拖动的悬浮按钮，覆盖在任意应用上方。
 * 点击按钮时通过缓存的 [MediaProjection] 截取当前屏幕，压缩为 JPEG 后
 * 调用 [VisionTranslationClient] 进行翻译，结果展示在悬浮卡片上。
 *
 * 不依赖任何模拟器代码，与游戏进程完全解耦。
 */
class OverlayTranslationService : Service() {

    companion object {
        private const val TAG = "OverlayTranslation"
        private const val CHANNEL_ID = "translation_overlay"
        private const val NOTIFICATION_ID = 10086
        private const val MAX_IMAGE_DIMENSION = 1536
        private const val JPEG_QUALITY = 85
        private const val PROJECTION_INIT_DELAY_MS = 500L

        /**
         * 缓存 MediaProjection 授权结果，避免每次截图都弹授权框。
         * 由 [TranslationSettingActivity] 在授权回调中写入。
         */
        @Volatile
        var projectionData: Intent? = null
        @Volatile
        var projectionResultCode: Int = 0
    }

    private lateinit var windowManager: WindowManager
    private lateinit var handler: Handler
    private lateinit var captureHandler: Handler
    private var captureThread: android.os.HandlerThread? = null
    private var floatingButton: View? = null
    private var floatingButtonParams: WindowManager.LayoutParams? = null
    private var resultCard: View? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: android.media.ImageReader? = null
    @Volatile private var latestImage: Image? = null
    private var isTranslating = false
    private var projectionReady = false
    private var closeConfirmDialog: AlertDialog? = null
    private lateinit var themePreferences: SharedPreferences
    private val themePreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "launcher_theme_style") {
            handler.post { refreshFloatingButtonTheme() }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by system, will retry on next click")
            projectionReady = false
            mediaProjection = null
            teardownCapture()
            // 延迟自动重建，避免在系统资源紧张时立即重试。
            handler.postDelayed({ ensureMediaProjection() }, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())
        themePreferences = getSharedPreferences("yukihub_prefs", Context.MODE_PRIVATE)
        themePreferences.registerOnSharedPreferenceChangeListener(themePreferenceListener)
        captureThread = android.os.HandlerThread("TranslationCapture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        startForegroundCompat()
        showFloatingButton()
        // 延迟创建 MediaProjection，等待前台 Service 完全就绪。
        handler.postDelayed({ ensureMediaProjection() }, PROJECTION_INIT_DELAY_MS)
        Toast.makeText(this, "悬浮翻译已开启", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::themePreferences.isInitialized) {
            themePreferences.unregisterOnSharedPreferenceChangeListener(themePreferenceListener)
        }
        removeFloatingButton()
        removeResultCard()
        teardownCapture()
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        projectionReady = false
        captureThread?.quitSafely()
        captureThread = null
        Toast.makeText(this, "悬浮翻译已关闭", Toast.LENGTH_SHORT).show()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "智能翻译",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "悬浮翻译服务运行中"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("智能翻译")
            .setContentText("悬浮翻译服务运行中")
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
     * 创建或重建 MediaProjection 实例，并建立持续录屏的 VirtualDisplay。
     *
     * 采用持续录屏策略，避免部分设备在每次点击时重建捕获链路并再次弹出截图授权。
     */
    @Synchronized
    private fun ensureMediaProjection() {
        if (projectionReady && mediaProjection != null && virtualDisplay != null) return
        val data = projectionData ?: return
        try {
            // 清理可能残留的旧实例
            teardownCapture()
            try {
                mediaProjection?.unregisterCallback(projectionCallback)
                mediaProjection?.stop()
            } catch (_: Exception) {
            }
            mediaProjection = null
            projectionReady = false

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(projectionResultCode, data) ?: run {
                Log.e(TAG, "getMediaProjection returned null (token may be expired)")
                return
            }
            projection.registerCallback(projectionCallback, handler)
            mediaProjection = projection
            projectionReady = true
            Log.i(TAG, "MediaProjection ready, starting continuous capture")

            // 创建持续捕获链路，复用同一次用户授权。
            startContinuousCapture(projection)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            projectionReady = false
        }
    }

    /**
     * 建立持续录屏：创建 ImageReader + VirtualDisplay 并保持运行。
     * 系统持续推送帧到 [imageReader]，[onImageAvailable] 回调更新 [latestImage]。
     */
    private fun startContinuousCapture(projection: MediaProjection) {
        val metrics = Resources.getSystem().displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = android.media.ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            // 持续更新 latestImage，只保留最新帧
            val newImage = reader.acquireLatestImage()
            if (newImage != null) {
                val old: Image?
                synchronized(this) {
                    old = latestImage
                    latestImage = newImage
                }
                old?.close()
            }
        }, captureHandler)

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "TranslationCapture", width, height, density,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
            Log.i(TAG, "continuous capture started: ${width}x${height}")
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
        }
    }

    /**
     * 释放 VirtualDisplay 和 ImageReader，但保留 MediaProjection。
     *
     * 先注销 onImageAvailable 回调，再 close latestImage，避免回调在
     * close 后再次赋值 latestImage 导致 Image native buffer 泄漏。
     */
    private fun teardownCapture() {
        // 先注销 listener，阻止新的 Image 进入 latestImage
        try {
            imageReader?.setOnImageAvailableListener(null, null)
        } catch (_: Exception) {
        }
        synchronized(this) {
            try {
                latestImage?.close()
            } catch (_: Exception) {
            }
            latestImage = null
        }
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
    }

    /**
     * 创建并显示悬浮按钮。
     * 按钮支持拖动，点击触发截图翻译。
     */
    private fun showFloatingButton() {
        val button = createFloatingButton()
        val params = floatingButtonParams ?: createOverlayParams().apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - dp(51)
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

    /** Rebuilds the overlay so its color and logo track the newly selected Launcher theme. */
    private fun refreshFloatingButtonTheme() {
        if (floatingButton == null) return
        removeFloatingButton()
        try {
            showFloatingButton()
        } catch (error: Exception) {
            Log.e(TAG, "refreshFloatingButtonTheme failed", error)
        }
    }

    private fun createFloatingButton(): View {
        val size = dp(35)
        // 跟随主题色调：取主题 primary 色，叠加 80% 不透明度（与原 #CC18B978 视觉一致）
        val primaryColor = com.apps.theme.LauncherTheme.primary(this)
        val buttonColor = (0xCC shl 24) or (primaryColor and 0x00FFFFFF)
        val container = LinearLayout(this).apply {
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
        val icon = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            setImageResource(currentThemeLogoRes())
            setColorFilter(Color.WHITE)
        }
        container.addView(icon)
        return container
    }

    /**
     * 返回当前主题对应的 Logo 图标资源，与主页底部导航栏中间按钮保持一致。
     */
    private fun currentThemeLogoRes(): Int {
        return when {
            com.apps.LauncherActivity.isRinneTheme(this) ->
                com.yuki.yukihub.R.drawable.launcher_theme_rinne_def
            com.apps.LauncherActivity.isAnriTheme(this) ->
                com.yuki.yukihub.R.drawable.launcher_theme_anri_def
            com.apps.LauncherActivity.isXinhaitianTheme(this) ->
                com.yuki.yukihub.R.drawable.launcher_theme_xinhaitian_def
            com.apps.LauncherActivity.isNatsumeTheme(this) ->
                com.yuki.yukihub.R.drawable.launcher_theme_natsume_def
            else ->
                com.yuki.yukihub.R.drawable.launcher_game_center_default
        }
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
                showDisableTranslationConfirm()
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
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) {
                        hasMoved = true
                        handler.removeCallbacks(longPress)
                    }
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    try {
                        windowManager.updateViewLayout(button, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (!hasMoved && !longPressHandled) triggerTranslation()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }
    }

    private fun showDisableTranslationConfirm() {
        if (closeConfirmDialog?.isShowing == true) return
        // Service 上下文不继承 Activity 的 AppCompat 主题；统一 Launcher 弹窗需要显式包装主题。
        val dialogContext = ContextThemeWrapper(this, com.yuki.yukihub.R.style.Theme_YukiHub_Launcher)
        val dialog = LauncherDialogFactory.showOverlayConfirm(
            dialogContext,
            "关闭悬浮翻译",
            "确定关闭悬浮翻译吗？",
            "关闭",
            {
                TranslationConfigStore.setEnabled(this, false)
                stopSelf()
            },
            overlayWindowType()
        )
        dialog.setOnDismissListener { closeConfirmDialog = null }
        closeConfirmDialog = dialog
    }

    private fun overlayWindowType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun removeFloatingButton() {
        floatingButton?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
            }
        }
        floatingButton = null
    }

    /**
     * 触发截图翻译流程。
     *
     * 通过 synchronized 取走 [latestImage] 最新帧进行翻译，避免与
     * [onImageAvailable] 回调并发操作同一 Image 导致 use-after-close。
     */
    private fun triggerTranslation() {
        if (isTranslating) {
            showResultCard(false, "正在翻译中，请稍候")
            return
        }
        if (!projectionReady || mediaProjection == null) {
            ensureMediaProjection()
            if (!projectionReady || mediaProjection == null) {
                showResultCard(false, "截屏权限已失效，请回到智能翻译设置页重新授权截屏权限")
                return
            }
            // 刚重建 projection，等待系统推送第一帧
            handler.postDelayed({
                // 本次等待并非实际翻译；先解除占用状态，再进入正常截图与请求流程。
                isTranslating = false
                triggerTranslation()
            }, 800)
            showLoadingCard()
            isTranslating = true
            return
        }
        isTranslating = true
        showLoadingCard()
        AppExecutors.runOnSingle {
            // 取走 latestImage（置 null 避免被 onImageAvailable 再次 close）
            var image: Image? = null
            var waited = 0
            while (image == null && waited < 1500) {
                synchronized(this) {
                    image = latestImage
                    latestImage = null
                }
                if (image == null) {
                    try {
                        Thread.sleep(100)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    waited += 100
                }
            }
            val jpegBytes = if (image != null) {
                try {
                    imageToJpegBytes(image!!)
                } catch (e: Exception) {
                    Log.e(TAG, "imageToJpegBytes failed in trigger", e)
                    null
                }
            } else {
                null
            }
            if (jpegBytes == null) {
                handler.post {
                    isTranslating = false
                    showResultCard(false, "截图失败，可能是截屏权限已失效，请重新授权")
                }
                return@runOnSingle
            }
            val result = VisionTranslationClient.translate(this, jpegBytes)
            handler.post {
                isTranslating = false
                showResultCard(result.success, result.text)
            }
        }
    }

    /**
     * 将 Image(RGBA_8888) 转为压缩后的 JPEG 字节数组。
     * 图片会按 [MAX_IMAGE_DIMENSION] 限制最长边，控制传输体积。
     *
     * 使用 Image 自身的尺寸而非外部传入参数，避免设备旋转后
     * ImageReader 尺寸与当前屏幕方向不匹配导致 Bitmap 越界崩溃。
     */
    private fun imageToJpegBytes(image: Image): ByteArray {
        try {
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            // 从 ImageInfo 获取实际分辨率，避免与 ImageReader 创建时的尺寸不匹配
            val imgWidth = image.width
            val imgHeight = image.height
            // rowStride 可能大于 width * pixelStride（含 padding），bitmap 需要容纳整行
            val bitmapWidth = rowStride / pixelStride
            val bitmap = Bitmap.createBitmap(bitmapWidth, imgHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()
            // 裁掉右侧 padding（若有）
            val cropped = if (bitmap.width != imgWidth) {
                Bitmap.createBitmap(bitmap, 0, 0, imgWidth, imgHeight)
            } else {
                bitmap
            }
            // 裁切下半屏：Galgame 对话框通常位于横屏画面的下半部分，
            // 只提交下半部分可减少传输体积并提升识别准确率
            val halfHeight = cropped.height / 2
            val dialogArea = Bitmap.createBitmap(
                cropped, 0, halfHeight, cropped.width, cropped.height - halfHeight
            )
            // 缩放到合理尺寸
            val scaled = scaleBitmap(dialogArea, MAX_IMAGE_DIMENSION)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (scaled !== dialogArea) scaled.recycle()
            if (dialogArea !== cropped) dialogArea.recycle()
            if (cropped !== bitmap) cropped.recycle()
            bitmap.recycle()
            return out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "imageToJpegBytes failed", e)
            try {
                image.close()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private fun scaleBitmap(src: Bitmap, maxDimension: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDimension && h <= maxDimension) return src
        val ratio = minOf(maxDimension.toFloat() / w, maxDimension.toFloat() / h)
        val newW = (w * ratio).toInt()
        val newH = (h * ratio).toInt()
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    /**
     * 显示加载中悬浮卡片。
     */
    private fun showLoadingCard() {
        removeResultCard()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#F0222222"))
            }
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val progress = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            indeterminateDrawable?.setColorFilter(
                com.apps.theme.LauncherTheme.primary(this@OverlayTranslationService),
                PorterDuff.Mode.SRC_IN
            )
        }
        val text = TextView(this).apply {
            text = "正在翻译..."
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
    private fun showResultCard(success: Boolean, text: String) {
        removeResultCard()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#F0222222"))
            }
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        val titleView = TextView(this).apply {
            this.text = if (success) "翻译结果（点击关闭）" else "翻译失败（点击关闭）"
            // 成功时标题色跟随主题色调，失败时固定红色
            setTextColor(if (success) com.apps.theme.LauncherTheme.primary(this@OverlayTranslationService) else Color.parseColor("#FF6B6B"))
            textSize = 11f
        }
        val msgView = TextView(this).apply {
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
            width = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        }
        try {
            windowManager.addView(container, params)
            resultCard = container
        } catch (e: Exception) {
            Log.e(TAG, "showResultCard failed", e)
        }
    }

    private fun removeResultCard() {
        resultCard?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
            }
        }
        resultCard = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
