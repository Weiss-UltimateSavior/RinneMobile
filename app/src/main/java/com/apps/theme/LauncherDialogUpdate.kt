package com.apps.theme

import android.content.Context
import com.apps.util.LauncherUrlOpener
import com.core.R
import com.core.launcherbridge.LauncherUpdateBridge

/** Update-flow dialog builders (result / available) for [LauncherDialogFactory]. */
internal object LauncherDialogUpdate {

    internal fun showUpdateResult(
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
            error != null -> LauncherDialogConfirm.showInfo(context, title, error, null)
            hasUpdate && info != null -> showUpdateAvailable(context, title, info, currentVersion)
            else -> LauncherDialogConfirm.showInfo(
                context,
                title,
                context.getString(
                    R.string.theme_already_latest,
                    LauncherUpdateFormatter.emptyOr(
                        currentVersion,
                        context.getString(R.string.settings_unknown)
                    )
                ),
                null
            )
        }
    }

    private fun showUpdateAvailable(
        context: Context,
        title: String,
        info: LauncherUpdateBridge.UpdateInfo,
        currentVersion: String?
    ) {
        // 消息拼接与 URL 解析统一复用 LauncherUpdateFormatter，避免与 PadDialogFactory 重复实现
        val message = LauncherUpdateFormatter.buildUpdateMessage(context, info, currentVersion)
        LauncherDialogChoice.showMessageActionChoices(
            context,
            title,
            message,
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
