package com.core.util

import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/**
 * 播放时长与日期格式化工具。
 */
object TimeFormatUtil {
    @JvmStatic
    fun playTime(millis: Long): String {
        if (millis <= 0) return "0s"
        if (millis < 60_000L) {
            val seconds = maxOf(1L, (millis / 1000.0).roundToLong())
            return "${seconds}s"
        }
        val hours = millis / 3_600_000.0
        if (hours < 1.0) {
            val minutes = maxOf(1L, (millis / 60_000.0).roundToLong())
            return "${minutes}m"
        }
        return DecimalFormat("0.0").format(hours) + "h"
    }

    @JvmStatic
    fun date(time: Long): String {
        if (time <= 0) return "从未游玩"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
    }

    /** 短日期展示（MM-dd HH:mm），用于列表行等横向空间受限场景。 */
    @JvmStatic
    fun shortDate(time: Long): String {
        if (time <= 0) return ""
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
    }

    /** 仅时分展示（HH:mm），用于消息时间标签等场景。 */
    @JvmStatic
    fun clock(time: Long): String {
        if (time <= 0) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
    }

    /**
     * 星期缩写（如"周一"/"周二"），固定使用 [Locale.CHINA]。
     * 用于个人资料页一周热力图等场景，避免随系统语言变化。
     */
    @JvmStatic
    fun weekDayLabel(time: Long): String {
        if (time <= 0) return ""
        return SimpleDateFormat("E", Locale.CHINA).format(Date(time))
    }
}
