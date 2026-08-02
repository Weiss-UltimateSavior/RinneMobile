package com.apps.PadUi

import android.content.Context
import com.apps.theme.LauncherUpdateFormatter
import com.apps.util.LauncherUrlOpener
import com.core.R
import com.core.launcherbridge.LauncherUpdateBridge

/**
 * Pad 更新相关对话框：更新结果提示与更新可用提示。
 *
 * 为控制 PadDialogFactory 单文件行数（规范 §4「代码行数限制」）而从其中拆分，
 * 更新消息拼接与 URL 解析统一复用 LauncherUpdateFormatter，避免重复实现。
 */
object PadUpdateDialog {

    @JvmStatic
    fun showUpdateResult(
        context: Context,
        info: LauncherUpdateBridge.UpdateInfo?,
        currentVersion: String?,
        hasUpdate: Boolean,
        error: String?
    ) {
        val title = context.getString(
            if (hasUpdate) R.string.theme_update_available else R.string.theme_check_for_updates
        )
        when {
            error != null -> PadDialogFactory.showInfo(context, title, error)
            hasUpdate && info != null -> showUpdateAvailable(context, title, info, currentVersion)
            else -> PadDialogFactory.showInfo(
                context,
                title,
                context.getString(
                    R.string.theme_already_latest,
                    LauncherUpdateFormatter.emptyOr(
                        currentVersion,
                        context.getString(R.string.settings_unknown)
                    )
                )
            )
        }
    }

    private fun showUpdateAvailable(
        context: Context,
        title: String,
        info: LauncherUpdateBridge.UpdateInfo,
        currentVersion: String?
    ) {
        // 消息拼接与 URL 解析统一复用 LauncherUpdateFormatter，避免与 LauncherDialogFactory 重复实现
        val updateMessage = LauncherUpdateFormatter.buildUpdateMessage(context, info, currentVersion)
        PadDialogFactory.showMessageActionChoices(
            context,
            title,
            updateMessage,
            arrayOf(
                context.getString(R.string.theme_go_to_download),
                context.getString(R.string.theme_release_page),
            ),
            context.getString(R.string.theme_later)
        ) { index ->
            LauncherUrlOpener.open(context, LauncherUpdateFormatter.resolveUpdateUrl(info, index))
        }
    }
}
