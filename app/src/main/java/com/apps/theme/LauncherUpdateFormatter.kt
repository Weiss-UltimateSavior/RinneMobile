package com.apps.theme

import android.content.Context
import com.core.R
import com.core.launcherbridge.LauncherUpdateBridge

/**
 * 更新提示相关的共享格式化工具。
 *
 * 原先 LauncherDialogFactory 与 PadDialogFactory 各自维护一份几乎相同的
 * showUpdateAvailable / emptyOr / trimUpdateBody 实现，按规范 §1「重复实现优先删除」
 * 统一收敛到此处，两个工厂只负责各自的对话框渲染。
 */
object LauncherUpdateFormatter {

    /** 发布页兜底链接，与 LauncherModuleCompatibilityActivity 等处使用的 "test" 标签保持一致，不可擅自修改。 */
    private const val FALLBACK_RELEASE_URL =
        "https://github.com/Weiss-UltimateSavior/RinneMobile/releases/tag/test"

    /**
     * 拼接更新提示消息：当前版本、最新版本，以及发布说明（正文为空时使用新版本摘要）。
     */
    fun buildUpdateMessage(
        context: Context,
        info: LauncherUpdateBridge.UpdateInfo,
        currentVersion: String?
    ): String {
        val unknown = context.getString(R.string.settings_unknown)
        val body = trimUpdateBody(info.body, 1600).trim { it <= ' ' }
        return StringBuilder()
            .append(context.getString(R.string.theme_current_version, emptyOr(currentVersion, unknown)))
            .append("\n")
            .append(context.getString(R.string.theme_latest_version, emptyOr(info.tagName, info.version)))
            .append("\n\n")
            .apply {
                if (body.isNotEmpty()) {
                    append(context.getString(R.string.theme_release_notes)).append("\n").append(body)
                } else {
                    append(context.getString(R.string.theme_new_release_summary))
                }
            }
            .toString()
    }

    /** 值为空或全空白时返回兜底字符串，否则原样返回。 */
    fun emptyOr(value: String?, fallback: String): String {
        return if (value == null || value.trim { it <= ' ' }.isEmpty()) fallback else value
    }

    /** 裁剪更新正文：超长时截断到 max 字符并追加换行省略号，空输入返回空串。 */
    fun trimUpdateBody(text: String?, max: Int): String {
        if (text == null) return ""
        val trimmed = text.trim { it <= ' ' }
        if (max <= 0 || trimmed.length <= max) return trimmed
        return trimmed.substring(0, max) + "\n..."
    }

    /** 解析更新动作对应的跳转链接：index 0 优先下载链接，其余优先发布页链接。 */
    fun resolveUpdateUrl(info: LauncherUpdateBridge.UpdateInfo, index: Int): String {
        return if (index == 0) {
            emptyOr(info.apkUrl, info.releaseUrl)
        } else {
            emptyOr(info.releaseUrl, FALLBACK_RELEASE_URL)
        }
    }
}
