package com.apps.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.core.util.AppExecutors
import java.lang.ref.WeakReference
import kotlin.math.sqrt

/**
 * 自定义裁剪 View：绘制图片、半透明遮罩、裁剪框边线与九宫格辅助线，
 * 支持单指拖动与双指缩放，并在缩放/拖动后约束图片始终覆盖裁剪框。
 * 原为 AvatarCropActivity 的私有静态内类，拆分后独立成类。
 */
internal class AvatarCropView(
    context: Context,
    private val inputUri: Uri,
    private val onFailure: Runnable?
) : View(context) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val startMatrix = Matrix()

    private var bitmap: Bitmap? = null
    private var displayWidth = 0
    private var displayHeight = 0

    // 裁剪框（屏幕坐标系）
    private var cropLeft = 0f
    private var cropTop = 0f
    private var cropSize = 0f

    private var minScale = 1f
    private var maxScale = 5f

    // 触摸状态
    private var lastX = 0f
    private var lastY = 0f
    private var startDistance = 0f
    private var isScaling = false

    init {
        initDisplaySize()
        startLoad()
    }

    private fun initDisplaySize() {
        val dm = resources.displayMetrics
        displayWidth = dm.widthPixels
        displayHeight = dm.heightPixels
    }

    /** 异步解码：使用 WeakReference 避免线程持有已销毁的 View。 */
    private fun startLoad() {
        val selfRef = WeakReference(this)
        val appContext = context.applicationContext
        val uri = inputUri
        AppExecutors.runOnIo {
            var b: Bitmap? = null
            try {
                b = AvatarBitmapDecoder.decode(
                    appContext, uri, Math.min(displayWidth, displayHeight) * 2)
            } catch (oom: OutOfMemoryError) {
                throw oom
            } catch (e: Exception) {
                Log.w(TAG, "avatar-decode-failed", e)
            }
            val result = b
            MAIN.post {
                val view = selfRef.get()
                if (view == null) {
                    if (result != null) result.recycle()
                    return@post
                }
                view.onBitmapLoaded(result)
            }
        }
    }

    private fun onBitmapLoaded(result: Bitmap?) {
        val old = bitmap
        if (old != null && !old.isRecycled && old !== result) {
            old.recycle()
        }
        bitmap = result
        if (result == null) {
            if (onFailure != null) onFailure.run()
            return
        }
        if (width > 0 && height > 0) {
            setupCropBox(width, height)
            computeInitialMatrix()
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            setupCropBox(w, h)
            if (bitmap != null) {
                computeInitialMatrix()
            }
        }
    }

    /** 裁剪框：屏幕宽度的 80%，横向居中，纵向中心位于 View 高度的 45% 处。 */
    private fun setupCropBox(vw: Int, vh: Int) {
        cropSize = vw * 0.8f
        cropLeft = (vw - cropSize) / 2f
        val cropCenterY = vh * 0.45f
        cropTop = cropCenterY - cropSize / 2f
    }

    /** 初始矩阵：fitCenter 到 View，并放大到至少覆盖裁剪框，使裁剪框中心对齐图片中心。 */
    private fun computeInitialMatrix() {
        if (bitmap == null) return
        val vw = width
        val vh = height
        if (vw == 0 || vh == 0) return
        val bmp = bitmap ?: return
        val bw = bmp.width
        val bh = bmp.height
        val fitScale = Math.min(vw.toFloat() / bw, vh.toFloat() / bh)
        val coverScale = Math.max(cropSize / bw, cropSize / bh)
        val scale = Math.max(fitScale, coverScale)
        val scaledBw = bw * scale
        val scaledBh = bh * scale
        // 先让 bitmap 居中 View，再平移使 bitmap 中心对齐裁剪框中心
        var dx = (vw - scaledBw) / 2f
        var dy = (vh - scaledBh) / 2f
        dx += (cropLeft + cropSize / 2f) - vw / 2f
        dy += (cropTop + cropSize / 2f) - vh / 2f
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(dx, dy)
        minScale = coverScale
        maxScale = scale * 5f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vw = width
        val vh = height
        // 裁剪区画布基色用纯黑：深色底避免无图白屏，属绘制内容色而非页面取色（§3 纯白/纯黑豁免：仅作画布混合基色）
        canvas.drawColor(Color.BLACK)
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            canvas.drawBitmap(bmp, matrix, bitmapPaint)
        }
        // 半透明黑色遮罩（四角，alpha 140）
        shapePaint.style = Paint.Style.FILL
        shapePaint.color = Color.argb(140, 0, 0, 0)
        canvas.drawRect(0f, 0f, vw.toFloat(), cropTop, shapePaint)
        canvas.drawRect(0f, cropTop + cropSize, vw.toFloat(), vh.toFloat(), shapePaint)
        canvas.drawRect(0f, cropTop, cropLeft, cropTop + cropSize, shapePaint)
        canvas.drawRect(cropLeft + cropSize, cropTop, vw.toFloat(), cropTop + cropSize, shapePaint)
        // 裁剪框边线（白色 2dp alpha 200）
        val density = resources.displayMetrics.density
        shapePaint.style = Paint.Style.STROKE
        shapePaint.strokeWidth = 2f * density
        shapePaint.color = Color.argb(200, 255, 255, 255)
        canvas.drawRect(cropLeft, cropTop, cropLeft + cropSize, cropTop + cropSize, shapePaint)
        // 九宫格辅助线（白色 1dp alpha 80）
        shapePaint.strokeWidth = density
        shapePaint.color = Color.argb(80, 255, 255, 255)
        val step = cropSize / 3f
        for (i in 1 until 3) {
            val x = cropLeft + step * i
            canvas.drawLine(x, cropTop, x, cropTop + cropSize, shapePaint)
            val y = cropTop + step * i
            canvas.drawLine(cropLeft, y, cropLeft + cropSize, y, shapePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                isScaling = false
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    startDistance = spacing(event)
                    if (startDistance > 10f) {
                        isScaling = true
                        startMatrix.set(matrix)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isScaling && event.pointerCount >= 2) {
                    val newDist = spacing(event)
                    if (startDistance > 10f) {
                        var scaleFactor = newDist / startDistance
                        // 把总缩放限制在 [minScale, maxScale]
                        val startVals = FloatArray(9)
                        startMatrix.getValues(startVals)
                        val projected = startVals[Matrix.MSCALE_X] * scaleFactor
                        if (projected < minScale) {
                            scaleFactor = minScale / startVals[Matrix.MSCALE_X]
                        } else if (projected > maxScale) {
                            scaleFactor = maxScale / startVals[Matrix.MSCALE_X]
                        }
                        matrix.set(startMatrix)
                        val midX = (event.getX(0) + event.getX(1)) / 2f
                        val midY = (event.getY(0) + event.getY(1)) / 2f
                        matrix.postScale(scaleFactor, scaleFactor, midX, midY)
                        clampTranslate()
                        invalidate()
                    }
                } else if (!isScaling && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    matrix.postTranslate(dx, dy)
                    clampTranslate()
                    lastX = event.x
                    lastY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                isScaling = false
                val upIndex = event.actionIndex
                val remainIndex = if (upIndex == 0) 1 else 0
                lastX = event.getX(remainIndex)
                lastY = event.getY(remainIndex)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isScaling = false
            }
        }
        return true
    }

    private fun spacing(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    /** 约束矩阵：保证 bitmap 始终覆盖裁剪框（图片不能拖出裁剪框范围）。 */
    private fun clampTranslate() {
        val bmp = bitmap ?: return
        val values = FloatArray(9)
        matrix.getValues(values)
        var scale = values[Matrix.MSCALE_X]
        var transX = values[Matrix.MTRANS_X]
        var transY = values[Matrix.MTRANS_Y]
        val scaledBw = bmp.width * scale
        val scaledBh = bmp.height * scale
        if (scaledBw > cropSize) {
            val minX = cropLeft + cropSize - scaledBw
            val maxX = cropLeft
            if (transX < minX) transX = minX
            if (transX > maxX) transX = maxX
        } else {
            transX = cropLeft - (scaledBw - cropSize) / 2f
        }
        if (scaledBh > cropSize) {
            val minY = cropTop + cropSize - scaledBh
            val maxY = cropTop
            if (transY < minY) transY = minY
            if (transY > maxY) transY = maxY
        } else {
            transY = cropTop - (scaledBh - cropSize) / 2f
        }
        // 重建为纯 scale + translate，避免累计数值漂移
        matrix.setScale(scale, scale)
        matrix.postTranslate(transX, transY)
    }

    /** Java 调用方（AvatarCropActivity）使用：当前加载的原图。 */
    val sourceBitmap: Bitmap?
        get() = bitmap

    /** Java 调用方（AvatarCropActivity）使用：把裁剪框内的图片区域裁成正方形 Bitmap，并按需降采样到 512px。 */
    val croppedBitmap: Bitmap?
        get() {
            val bmp = bitmap ?: return null
            if (bmp.isRecycled) return null
            val values = FloatArray(9)
            matrix.getValues(values)
            val scale = values[Matrix.MSCALE_X]
            val transX = values[Matrix.MTRANS_X]
            val transY = values[Matrix.MTRANS_Y]
            // 裁剪框屏幕坐标 → source bitmap 坐标
            var srcX = (cropLeft - transX) / scale
            var srcY = (cropTop - transY) / scale
            val srcSize = cropSize / scale
            val bw = bmp.width
            val bh = bmp.height
            var sx = Math.round(srcX)
            var sy = Math.round(srcY)
            var ss = Math.round(srcSize)
            // 边界检查
            if (sx < 0) sx = 0
            if (sy < 0) sy = 0
            if (sx + ss > bw) ss = bw - sx
            if (sy + ss > bh) ss = bh - sy
            if (ss <= 0) return null
            var cropped: Bitmap?
            try {
                cropped = Bitmap.createBitmap(bmp, sx, sy, ss, ss)
            } catch (oom: OutOfMemoryError) {
                throw oom
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (cropped == null) return null
            if (cropped.width > MAX_OUTPUT_SIZE) {
                val scaled = Bitmap.createScaledBitmap(cropped, MAX_OUTPUT_SIZE, MAX_OUTPUT_SIZE, true)
                if (scaled !== cropped) cropped.recycle()
                cropped = scaled
            }
            return cropped
        }

    /** Java 调用方（AvatarCropActivity）使用：回收原图并置 null。 */
    fun release() {
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            bmp.recycle()
            bitmap = null
        }
    }

    companion object {
        private val MAIN = Handler(Looper.getMainLooper())
        private const val MAX_OUTPUT_SIZE = 512
        private const val TAG = "AvatarCropView"
    }
}
