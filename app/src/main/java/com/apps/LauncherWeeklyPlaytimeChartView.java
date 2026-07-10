package com.apps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.yuki.yukihub.util.TimeFormatUtil;

/** Compact seven-day actual-playtime line chart for Launcher cards. */
public class LauncherWeeklyPlaytimeChartView extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long[] dailyDurations = new long[7];
    private String[] dayLabels = new String[7];

    public LauncherWeeklyPlaytimeChartView(Context context) { this(context, null); }
    public LauncherWeeklyPlaytimeChartView(Context context, @Nullable AttributeSet attrs) { this(context, attrs, 0); }
    public LauncherWeeklyPlaytimeChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setDailyDurations(long[] durations, String[] labels) {
        dailyDurations = durations != null && durations.length == 7 ? durations.clone() : new long[7];
        dayLabels = labels != null && labels.length == 7 ? labels.clone() : new String[7];
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        float horizontal = dp(12);
        float top = dp(16);
        float bottom = height - dp(24);
        float chartWidth = Math.max(1f, width - horizontal * 2f);
        long max = 0L;
        for (long duration : dailyDurations) max = Math.max(max, Math.max(0L, duration));

        labelPaint.setTextSize(dp(10));
        labelPaint.setColor(LauncherTheme.textMuted(getContext()));
        if (max == 0L) {
            labelPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("近 7 日暂无实际游玩记录", width / 2f, (top + bottom) / 2f, labelPaint);
            drawDayLabels(canvas, horizontal, bottom, chartWidth);
            return;
        }

        linePaint.setColor(LauncherTheme.primary(getContext()));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        pointPaint.setColor(LauncherTheme.primary(getContext()));
        pointPaint.setStyle(Paint.Style.FILL);

        Path path = new Path();
        for (int i = 0; i < 7; i++) {
            float x = horizontal + chartWidth * i / 6f;
            float y = bottom - (bottom - top) * Math.max(0L, dailyDurations[i]) / max;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, linePaint);
        for (int i = 0; i < 7; i++) {
            float x = horizontal + chartWidth * i / 6f;
            float y = bottom - (bottom - top) * Math.max(0L, dailyDurations[i]) / max;
            canvas.drawCircle(x, y, dp(4), pointPaint);
        }

        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(TimeFormatUtil.playTime(max), horizontal, dp(11), labelPaint);
        drawDayLabels(canvas, horizontal, bottom, chartWidth);
    }

    private void drawDayLabels(Canvas canvas, float horizontal, float bottom, float chartWidth) {
        labelPaint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < 7; i++) {
            float x = horizontal + chartWidth * i / 6f;
            canvas.drawText(dayLabels[i] == null ? "" : dayLabels[i], x, bottom + dp(18), labelPaint);
        }
    }

    private float dp(int value) { return value * getResources().getDisplayMetrics().density; }
}
