package com.apps.HDModel

import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * HD 页面统一切换动画。
 *
 * 根 Fragment 使用 launcher_fragment_* 资源；嵌入 Activity 的内容复用
 * launcher_activity_* 的时长、位移和透明度规范。
 */
internal object HdPageMotion {
    private const val OPEN_DURATION_MS = 330L
    private const val OLD_PAGE_EXIT_DURATION_MS = 280L
    private const val CLOSE_DURATION_MS = 290L

    fun showEmbedded(container: FrameLayout, content: View) {
        val current = container.getChildAt(container.childCount - 1)
        if (current === content) return

        (content.parent as? ViewGroup)?.removeView(content)
        current?.animate()?.cancel()
        content.animate().cancel()

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        if (current == null) {
            container.removeAllViews()
            reset(content)
            container.addView(content, params)
            return
        }

        while (container.childCount > 1) {
            container.removeViewAt(0)
        }
        val distance = (container.height.takeIf { it > 0 } ?: current.height).toFloat()
        content.alpha = 0f
        content.translationY = distance * 0.04f
        container.addView(content, params)

        current.animate()
            .alpha(0.88f)
            .translationY(-distance * 0.02f)
            .setDuration(OLD_PAGE_EXIT_DURATION_MS)
            .setInterpolator(AccelerateInterpolator(2f))
            .withEndAction {
                container.removeView(current)
                reset(current)
            }
            .start()
        content.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(OPEN_DURATION_MS)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()
    }

    fun closeEmbedded(
        container: FrameLayout,
        hideContainer: Boolean = false,
        onClosed: () -> Unit,
    ) {
        val content = container.getChildAt(container.childCount - 1)
        if (content == null) {
            if (hideContainer) container.visibility = View.GONE
            onClosed()
            return
        }
        content.animate().cancel()
        val distance = (container.height.takeIf { it > 0 } ?: content.height).toFloat()
        content.animate()
            .alpha(0f)
            .translationY(distance * 0.05f)
            .setDuration(CLOSE_DURATION_MS)
            .setInterpolator(AccelerateInterpolator(2f))
            .withEndAction {
                container.removeAllViews()
                reset(content)
                if (hideContainer) container.visibility = View.GONE
                onClosed()
            }
            .start()
    }

    private fun reset(view: View) {
        view.alpha = 1f
        view.translationY = 0f
    }
}
