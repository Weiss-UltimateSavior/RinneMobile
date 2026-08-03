package com.apps.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.apps.theme.LauncherTheme
import com.core.util.TimeFormatUtil
import kotlin.math.max

/** Compact seven-day actual-playtime line chart for Launcher cards. */
class LauncherWeeklyPlaytimeChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var dailyDurations = LongArray(7)
    private var dayLabels = arrayOfNulls<String>(7)

    fun setDailyDurations(durations: LongArray?, labels: Array<String?>?) {
        dailyDurations = durations?.takeIf { it.size == 7 }?.clone() ?: LongArray(7)
        dayLabels = labels?.takeIf { it.size == 7 }?.clone() ?: arrayOfNulls(7)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width
        val height = height
        if (width <= 0 || height <= 0) return
        val horizontal = LauncherTheme.dpFloat(context, 12f)
        val top = LauncherTheme.dpFloat(context, 16f)
        val bottom = height - LauncherTheme.dpFloat(context, 24f)
        val chartWidth = max(1f, width - horizontal * 2f)
        val maxDuration = dailyDurations.maxOf { max(0L, it) }
        labelPaint.textSize = LauncherTheme.dpFloat(context, 10f)
        labelPaint.color = LauncherTheme.textMuted(context)
        if (maxDuration == 0L) {
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                context.getString(com.core.R.string.home_weekly_no_playtime),
                width / 2f,
                (top + bottom) / 2f,
                labelPaint
            )
            drawDayLabels(canvas, horizontal, bottom, chartWidth)
            return
        }
        linePaint.apply {
            color = LauncherTheme.primary(context)
            style = Paint.Style.STROKE
            strokeWidth = LauncherTheme.dpFloat(context, 2f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        pointPaint.apply { color = LauncherTheme.primary(context); style = Paint.Style.FILL }
        val path = Path()
        repeat(7) { index ->
            val x = horizontal + chartWidth * index / 6f
            val y = bottom - (bottom - top) * max(0L, dailyDurations[index]) / maxDuration
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
        repeat(7) { index ->
            val x = horizontal + chartWidth * index / 6f
            val y = bottom - (bottom - top) * max(0L, dailyDurations[index]) / maxDuration
            canvas.drawCircle(x, y, LauncherTheme.dpFloat(context, 4f), pointPaint)
        }
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(TimeFormatUtil.playTime(maxDuration), horizontal, LauncherTheme.dpFloat(context, 11f), labelPaint)
        drawDayLabels(canvas, horizontal, bottom, chartWidth)
    }

    private fun drawDayLabels(canvas: Canvas, horizontal: Float, bottom: Float, chartWidth: Float) {
        labelPaint.textAlign = Paint.Align.CENTER
        repeat(7) { index ->
            canvas.drawText(dayLabels[index].orEmpty(), horizontal + chartWidth * index / 6f, bottom + LauncherTheme.dpFloat(context, 18f), labelPaint)
        }
    }
}
