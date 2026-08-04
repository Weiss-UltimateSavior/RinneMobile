package com.apps.theme

import android.content.res.ColorStateList
import android.graphics.Color
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.core.R
import kotlin.math.max

/** 统一 SwitchCompat 启停按钮与 Material 3 风格开关的色调/轨道绘制。 */
internal object LauncherThemeSwitch {

    internal fun styleMaterialSwitch(switchCompat: SwitchCompat?) {
        if (switchCompat == null) return
        val context = switchCompat.context
        val primary = LauncherThemeColors.primary(context)
        val mutedGray = ContextCompat.getColor(context, R.color.launcher_text_muted_color)
        val density = context.resources.displayMetrics.density
        val trackWidth = (49f * density).toInt()
        val trackHeight = (29f * density).toInt()
        val thumbSize = (21f * density).toInt()
        val strokeWidth = max(1, (2f * density).toInt())

        switchCompat.showText = false
        switchCompat.splitTrack = false
        switchCompat.switchMinWidth = trackWidth
        // 保留点击行为，但去除 SwitchCompat 默认的按下波纹与背景高亮。
        switchCompat.background = null
        // SwitchCompat 会对独立 thumb 进行内部偏移和裁切；将轨道与圆点作为同一个
        // Drawable 绘制，确保关闭时的圆点保持完整圆形。
        switchCompat.trackDrawable = object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private var isChecked = false

            override fun draw(canvas: android.graphics.Canvas) {
                val bounds = bounds
                val radius = bounds.height() / 2f
                val centerY = bounds.exactCenterY()
                val horizontalInset = (4f * density)
                val thumbRadius = thumbSize / 2f
                val thumbCenterX = if (isChecked) {
                    bounds.right - horizontalInset - thumbRadius
                } else {
                    bounds.left + horizontalInset + thumbRadius
                }

                paint.style = android.graphics.Paint.Style.FILL
                paint.color = if (isChecked) primary else Color.TRANSPARENT
                canvas.drawRoundRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
                        bounds.bottom.toFloat(), radius, radius, paint)
                if (!isChecked) {
                    paint.style = android.graphics.Paint.Style.STROKE
                    paint.strokeWidth = strokeWidth.toFloat()
                    paint.color = mutedGray
                    canvas.drawRoundRect(bounds.left + strokeWidth / 2f, bounds.top + strokeWidth / 2f,
                            bounds.right - strokeWidth / 2f, bounds.bottom - strokeWidth / 2f,
                            radius, radius, paint)
                }
                paint.style = android.graphics.Paint.Style.FILL
                paint.color = if (isChecked) Color.WHITE else mutedGray
                canvas.drawCircle(thumbCenterX, centerY, thumbRadius, paint)
            }

            override fun isStateful() = true

            override fun onStateChange(stateSet: IntArray): Boolean {
                val checked = stateSet.any { it == android.R.attr.state_checked }
                if (isChecked == checked) return false
                isChecked = checked
                invalidateSelf()
                return true
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            override fun getIntrinsicWidth() = trackWidth
            override fun getIntrinsicHeight() = trackHeight
        }
        switchCompat.thumbDrawable = null
        switchCompat.thumbTintList = null
        switchCompat.trackTintList = null
    }
}
