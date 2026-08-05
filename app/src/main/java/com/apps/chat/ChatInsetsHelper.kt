package com.apps.chat

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.apps.theme.LauncherTheme

/**
 * AiChat/PublicChat 聊天消息布局共用的 insets + IME + overlay padding 逻辑。
 *
 * 两聊天 Fragment 的 applyInsets/setOverlayMargins/updateMessageListOverlayPadding 逐字相同
 * （仅 ViewBinding 前缀不同），按 §8「重复实现须删除」统一收拢于此；LocalAgent 的 bindInsets
 * 为左右 safe + cutout 全量 padding 形态（不同构），不纳入（见 4.5 口径修正）。
 */
internal interface ChatInsetsLayout {
    val topOverlay: View
    val titleBar: View
    val composerOverlay: View
    val inputThemeBar: View
    val messages: View
}

/** 聊天消息列表 insets 助手（阶段 134 从两个聊天 Fragment 抽离，逻辑逐字迁移）。 */
internal object ChatInsetsHelper {
    /**
     * 注册 insets 监听与 overlay 布局变化监听，并主动请求一次 insets 分发。
     * 返回的重排回调供外部在影响消息列表空间的布局变化后调用（如公告栏显隐）。
     */
    fun install(root: View, layout: ChatInsetsLayout, baseBottomPadding: Int): () -> Unit {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val context = root.context
            val topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            val systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            setOverlayMargins(layout.topOverlay, 0, 0)
            layout.titleBar.setPaddingRelative(
                LauncherTheme.dp(context, 13),
                topInset + LauncherTheme.dp(context, 12),
                LauncherTheme.dp(context, 13),
                LauncherTheme.dp(context, 15),
            )
            val keyboardVisible = imeBottom > systemBottom
            setOverlayMargins(layout.composerOverlay, 0, if (keyboardVisible) imeBottom else 0)
            val inputThemeBar = layout.inputThemeBar
            inputThemeBar.setPaddingRelative(
                inputThemeBar.paddingStart,
                LauncherTheme.dp(context, 13),
                inputThemeBar.paddingEnd,
                if (keyboardVisible) {
                    LauncherTheme.dp(context, 14)
                } else {
                    systemBottom + LauncherTheme.dp(context, 14)
                },
            )
            updateOverlayPadding(layout, baseBottomPadding)
            insets
        }
        listOf(layout.topOverlay, layout.titleBar, layout.composerOverlay).forEach { view ->
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverlayPadding(layout, baseBottomPadding) }
        }
        ViewCompat.requestApplyInsets(root)
        return { updateOverlayPadding(layout, baseBottomPadding) }
    }

    /** 按 overlay 实际位置重算消息列表的 top margin 与 bottom padding（原 updateMessageListOverlayPadding）。 */
    private fun updateOverlayPadding(layout: ChatInsetsLayout, baseBottomPadding: Int) {
        val messages = layout.messages
        val listTop = if (layout.topOverlay.visibility == View.GONE) {
            0
        } else {
            maxOf(0, layout.topOverlay.bottom)
        }
        setMessageListTopMargin(messages, listTop)
        val bottomSpace = if (layout.composerOverlay.visibility == View.GONE) {
            0
        } else {
            maxOf(0, messages.bottom - layout.composerOverlay.top) + LauncherTheme.dp(messages.context, 8)
        }
        messages.setPadding(
            messages.paddingLeft,
            messages.paddingTop,
            messages.paddingRight,
            baseBottomPadding + bottomSpace,
        )
    }

    private fun setOverlayMargins(view: View, top: Int, bottom: Int) {
        val params = view.layoutParams
        if (params !is ViewGroup.MarginLayoutParams) return
        if (params.topMargin == top && params.bottomMargin == bottom) return
        params.topMargin = top
        params.bottomMargin = bottom
        view.layoutParams = params
    }

    private fun setMessageListTopMargin(view: View, topMargin: Int) {
        val params = view.layoutParams
        if (params !is ViewGroup.MarginLayoutParams) return
        if (params.topMargin == topMargin) return
        params.topMargin = topMargin
        view.layoutParams = params
    }
}
