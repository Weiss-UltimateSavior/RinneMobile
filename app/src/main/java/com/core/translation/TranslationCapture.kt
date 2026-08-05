package com.core.translation

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * MediaProjection 截屏捕获链路（重构计划 3.5 阶段 94 拆分自 OverlayTranslationService）。
 *
 * 持有持续录屏的 VirtualDisplay + ImageReader，向调用方提供最新帧转 JPEG。
 * 授权结果通过 [OverlayTranslationService] 的 companion 字段读取；线程模型：
 * onImageAvailable 回调在 captureHandler 线程更新 latestImage，
 * [takeLatestJpegBytes] 由调用方在 IO 线程调用（会短暂自旋等待首帧）。
 */
internal class TranslationCapture(
    private val context: Context,
    private val mainHandler: Handler
) {

    companion object {
        private const val TAG = "OverlayTranslation"
        private const val MAX_IMAGE_DIMENSION = 1536
        private const val JPEG_QUALITY = 85
    }

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: android.media.ImageReader? = null
    private var latestImage: Image? = null
    private var projectionRestartTask: Runnable? = null

    /** MediaProjection 就绪标记；重建期间为 false，供调用方决定重试。 */
    var projectionReady = false
        private set

    fun hasActiveProjection(): Boolean = mediaProjection != null

    /** 创建捕获 HandlerThread（Service onCreate 调用一次）。 */
    fun init() {
        val thread = HandlerThread("TranslationCapture").also { it.start() }
        captureThread = thread
        captureHandler = Handler(thread.looper)
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by system, will retry on next click")
            projectionReady = false
            mediaProjection = null
            teardownCapture()
            // 延迟自动重建，避免在系统资源紧张时立即重试。
            val task = Runnable { ensureProjection() }
            projectionRestartTask = task
            mainHandler.postDelayed(task, 1000)
        }
    }

    /**
     * 创建或重建 MediaProjection 实例，并建立持续录屏的 VirtualDisplay。
     *
     * 采用持续录屏策略，避免部分设备在每次点击时重建捕获链路并再次弹出截图授权。
     */
    @Synchronized
    fun ensureProjection() {
        if (projectionReady && mediaProjection != null && virtualDisplay != null) return
        val data = OverlayTranslationService.projectionData ?: return
        try {
            // 清理可能残留的旧实例
            teardownCapture()
            try {
                mediaProjection?.unregisterCallback(projectionCallback)
                mediaProjection?.stop()
            } catch (_: Exception) {
                // 旧 MediaProjection 已停止时抛异常可安全忽略（重建前清理尽力而为）
            }
            mediaProjection = null
            projectionReady = false

            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(OverlayTranslationService.projectionResultCode, data) ?: run {
                Log.e(TAG, "getMediaProjection returned null (token may be expired)")
                return
            }
            projection.registerCallback(projectionCallback, mainHandler)
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
            // ImageReader 已关闭时置空监听器抛异常可安全忽略（teardown 尽力而为）
        }
        synchronized(this) {
            try {
                latestImage?.close()
            } catch (_: Exception) {
                // Image 已关闭时重复 close 抛异常可安全忽略（teardown 尽力而为）
            }
            latestImage = null
        }
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
            // VirtualDisplay 已释放时重复 release 抛异常可安全忽略（teardown 尽力而为）
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
            // ImageReader 已关闭时重复 close 抛异常可安全忽略（teardown 尽力而为）
        }
        imageReader = null
    }

    /**
     * 取走最新帧并转为 JPEG 字节数组（由调用方在 IO 线程调用）。
     *
     * 通过 synchronized 取走 [latestImage] 最新帧进行翻译，避免与
     * onImageAvailable 回调并发操作同一 Image 导致 use-after-close。
     * 最多自旋等待 1.5s；获取或转换失败返回 null。
     */
    fun takeLatestJpegBytes(): ByteArray? {
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
        val frame = image
        return if (frame != null) {
            try {
                imageToJpegBytes(frame)
            } catch (e: Exception) {
                Log.e(TAG, "imageToJpegBytes failed in trigger", e)
                null
            }
        } else {
            null
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
                // 错误路径下 Image 可能已关闭，close 抛异常可安全忽略（兜底清理）
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

    /** 停止投影并释放全部资源（Service onDestroy 调用）。 */
    fun stop() {
        projectionRestartTask?.let { mainHandler.removeCallbacks(it) }
        projectionRestartTask = null
        teardownCapture()
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
            // MediaProjection 已停止/回调已注销时抛异常可安全忽略（onDestroy 清理尽力而为）
        }
        mediaProjection = null
        projectionReady = false
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
    }
}
