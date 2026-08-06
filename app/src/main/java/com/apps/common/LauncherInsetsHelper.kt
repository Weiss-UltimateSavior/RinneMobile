package com.apps.common

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 各 Fragment/Activity 共用的系统栏 insets 应用助手（阶段 135 抽离）。
 *
 * 原各页面各自实现的 applySystemBarInsets 逐字重复（捕获目标 View 原始 padding + 在
 * systemWindowInset* 上累加），统一收拢于此并改用 WindowInsetsCompat.Type.systemBars()
 *（废弃属性 systemWindowInset* 现代化）；监听统一挂 [root] 并主动 requestApplyInsets，
 * 回调返回 insets 不消费，子级分发不受影响。LauncherRegisterFragment 的复杂形态
 *（IME + 滚动聚焦状态机）与 Java 存量页面不纳入（见 4.5）。
 */
internal object LauncherInsetsHelper {
    /** 顶部 systemBars inset 累加 [target].paddingTop，其余 padding 保留原始值。 */
    @JvmStatic
    fun applyTopInset(root: View, target: View) {
        applyInsetsInternal(root, target, mode = Mode.TOP)
    }

    /** 顶部 + 底部 systemBars inset 累加，左右 padding 保留原始值。 */
    @JvmStatic
    fun applyTopAndBottomInsets(root: View, target: View) {
        applyInsetsInternal(root, target, mode = Mode.TOP_BOTTOM)
    }

    /** 四边 systemBars inset 全部累加。 */
    @JvmStatic
    fun applyInsets(root: View, target: View) {
        applyInsetsInternal(root, target, mode = Mode.ALL)
    }

    /** 顶部 inset 累加 + bottom 由 [bottomPadding] 按原始值计算（LauncherHome 特化）。 */
    @JvmStatic
    fun applyTopInset(root: View, target: View, bottomPadding: (Int) -> Int) {
        applyInsetsInternal(root, target, mode = Mode.TOP, customBottom = bottomPadding)
    }

    private enum class Mode { TOP, TOP_BOTTOM, ALL }

    private fun applyInsetsInternal(
        root: View,
        target: View,
        mode: Mode,
        customBottom: ((Int) -> Int)? = null,
    ) {
        val left = target.paddingLeft
        val top = target.paddingTop
        val right = target.paddingRight
        val bottom = target.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val newBottom = when {
                customBottom != null -> customBottom(bottom)
                mode == Mode.TOP_BOTTOM || mode == Mode.ALL -> bottom + bars.bottom
                else -> bottom
            }
            target.setPadding(
                if (mode == Mode.ALL) left + bars.left else left,
                top + bars.top,
                if (mode == Mode.ALL) right + bars.right else right,
                newBottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
