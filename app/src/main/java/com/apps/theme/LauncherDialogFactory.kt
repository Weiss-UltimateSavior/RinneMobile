package com.apps.theme

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.core.launcherbridge.LauncherUpdateBridge

/** Shared non-engine Launcher dialog shell. */
object LauncherDialogFactory {
    /** Visual baseline from the Launcher center-navigation confirmation dialog. */
    const val WIDTH_COMPACT_DP: Int = 252
    const val WIDTH_STANDARD_DP: Int = WIDTH_COMPACT_DP
    const val WIDTH_FORM_DP: Int = 288
    const val WIDTH_ACTION_MENU_DP: Int = 340

    fun interface ChoiceListener {
        fun onChoice(index: Int)
    }

    fun interface ScanDepthListener {
        fun onChoice(depth: Int, fullRefresh: Boolean)
    }

    fun interface TextChoiceListener {
        fun onChoice(value: String)
    }

    @JvmStatic
    fun showInfo(context: Context, title: String?, message: String?) {
        LauncherDialogConfirm.showInfo(context, title, message, null)
    }

    /** Standard-width information prompt with an optional acknowledgement callback. */
    @JvmStatic
    fun showInfo(context: Context, title: String?, message: String?,
                 onAcknowledge: Runnable?) {
        LauncherDialogConfirm.showInfo(context, title, message, onAcknowledge)
    }

    @JvmStatic
    fun showConfirm(context: Context, title: String?, message: String?,
                    confirmText: String?, onConfirm: Runnable?) {
        LauncherDialogConfirm.showConfirm(context, title, message, confirmText, onConfirm, null, null)
    }

    @JvmStatic
    fun showConfirm(context: Context, title: String?, message: String?,
                    confirmText: String?, onConfirm: Runnable?,
                    onDismiss: Runnable?): AlertDialog {
        return LauncherDialogConfirm.showConfirm(context, title, message, confirmText, onConfirm, null, onDismiss)
    }

    @JvmStatic
    fun showConfirm(context: Context, title: String?, message: String?,
                    confirmText: String?, onConfirm: Runnable?,
                    cancelText: CharSequence?, onDismiss: Runnable?): AlertDialog {
        return LauncherDialogConfirm.showConfirm(context, title, message, confirmText, onConfirm, cancelText, onDismiss)
    }

    /** Compact confirmation rendered with the standard Launcher shell in an overlay window. */
    @JvmStatic
    fun showOverlayConfirm(context: Context, title: String?, message: String?,
                           confirmText: String?, onConfirm: Runnable?,
                           windowType: Int): AlertDialog {
        return LauncherDialogConfirm.showOverlayConfirm(context, title, message, confirmText, onConfirm, windowType)
    }

    /** Standard-width confirmation used by settings and account flows. */
    @JvmStatic
    fun showStandardConfirm(context: Context, title: String?, message: String?,
                            confirmText: String?, onConfirm: Runnable?) {
        LauncherDialogConfirm.showStandardConfirm(context, title, message, confirmText, onConfirm)
    }

    /** Scrollable long-message confirmation for content that cannot safely fit the compact shell. */
    @JvmStatic
    fun showLongMessageConfirm(context: Context, title: String?, message: String?,
                               confirmText: String?, onConfirm: Runnable?): AlertDialog {
        return LauncherDialogConfirm.showLongMessageConfirm(context, title, message, confirmText, onConfirm, null)
    }

    @JvmStatic
    fun showLongMessageConfirm(context: Context, title: String?, message: String?,
                               confirmText: String?, onConfirm: Runnable?,
                               onCancel: Runnable?): AlertDialog {
        return LauncherDialogConfirm.showLongMessageConfirm(context, title, message, confirmText, onConfirm, onCancel)
    }

    /** Standard-width destructive confirmation with a horizontal action row. */
    @JvmStatic
    fun showDangerConfirm(context: Context, title: String?, message: String?,
                          dangerText: String?, onConfirm: Runnable?) {
        LauncherDialogConfirm.showDangerConfirm(context, title, message, dangerText, onConfirm)
    }

    /** Non-cancelable indeterminate loading shell. The caller owns its lifecycle. */
    @JvmStatic
    fun showLoading(context: Context, title: String?, hint: String?): AlertDialog {
        return LauncherDialogLoading.showLoading(context, title, hint)
    }

    /**
     * Non-cancelable loading shell with an additional progress TextView tagged as [progressTag].
     * Sync flows update that tagged view while keeping all dialog construction inside the factory.
     */
    @JvmStatic
    fun showProgressLoading(
        context: Context,
        title: String?,
        progressText: String?,
        hint: String?,
        progressTag: String?
    ): AlertDialog {
        return LauncherDialogLoading.showProgressLoading(context, title, progressText, hint, progressTag)
    }

    @JvmStatic
    fun showActionChoices(context: Context, title: String?, choices: Array<CharSequence>?,
                          listener: ChoiceListener?) {
        LauncherDialogChoice.showActionChoices(context, title, choices, -1, listener)
    }

    @JvmStatic
    fun showActionChoices(context: Context, title: String?, choices: Array<CharSequence>?,
                          dangerIndex: Int, listener: ChoiceListener?) {
        LauncherDialogChoice.showActionChoices(context, title, choices, dangerIndex, listener)
    }

    /** Standard-width compact action menu for a small number of short operations. */
    @JvmStatic
    fun showStandardActionChoices(context: Context, title: String?, choices: Array<CharSequence>?,
                                  listener: ChoiceListener?) {
        LauncherDialogChoice.showStandardActionChoices(context, title, choices, -1, listener)
    }

    @JvmStatic
    fun showStandardActionChoices(context: Context, title: String?, choices: Array<CharSequence>?,
                                  dangerIndex: Int, listener: ChoiceListener?) {
        LauncherDialogChoice.showStandardActionChoices(context, title, choices, dangerIndex, listener)
    }

    /** Standard action choices with a short explanatory message above the actions. */
    @JvmStatic
    fun showMessageActionChoices(
        context: Context,
        title: String?,
        message: String?,
        choices: Array<CharSequence>?,
        listener: ChoiceListener?
    ) {
        LauncherDialogChoice.showMessageActionChoices(context, title, message, choices, null, listener)
    }

    @JvmStatic
    fun showMessageActionChoices(
        context: Context,
        title: String?,
        message: String?,
        choices: Array<CharSequence>?,
        cancelText: CharSequence?,
        listener: ChoiceListener?
    ) {
        LauncherDialogChoice.showMessageActionChoices(context, title, message, choices, cancelText, listener)
    }

    @JvmStatic
    fun showUpdateResult(
        context: Context,
        info: LauncherUpdateBridge.UpdateInfo?,
        currentVersion: String?,
        hasUpdate: Boolean,
        error: String?
    ) {
        LauncherDialogUpdate.showUpdateResult(context, info, currentVersion, hasUpdate, error)
    }

    /** Compact single-choice picker matching the add-game launch-target selector. */
    @JvmStatic
    fun showSingleChoice(context: Context, title: String?, choices: Array<CharSequence>?,
                         checkedIndex: Int, listener: ChoiceListener?) {
        LauncherDialogChoice.showSingleChoice(context, title, choices, checkedIndex, listener)
    }

    @JvmStatic
    fun showScanDepthChoices(
        context: Context,
        title: String?,
        quickModeText: String?,
        fullModeText: String?,
        labels: Array<CharSequence>?,
        depthValues: IntArray?,
        currentDepth: Int,
        listener: ScanDepthListener?
    ) {
        LauncherDialogChoice.showScanDepthChoices(context, title, quickModeText, fullModeText, labels, depthValues, currentDepth, listener)
    }

    @JvmStatic
    fun showTextChoicesWithSkip(
        context: Context,
        title: String?,
        message: String?,
        choices: List<String>?,
        skipText: String?,
        cancelText: String?,
        listener: TextChoiceListener?,
        onSkip: Runnable?,
        onCancel: Runnable?
    ) {
        LauncherDialogChoice.showTextChoicesWithSkip(context, title, message, choices, skipText, cancelText, listener, onSkip, onCancel)
    }

    /** Android 11+ 全文件访问权限引导对话框，GO 按钮由调用方处理跳转。 */
    @JvmStatic
    fun showStoragePermissionRequest(
        context: Context,
        onGo: Runnable,
        onCancel: Runnable,
    ) {
        LauncherDialogConfirm.showStoragePermissionRequest(context, onGo, onCancel)
    }

    /**
     * 弹窗宽度兜底（px）：期望宽度不超过屏幕宽度减去两侧 16dp 边距，平板竖屏走 `LauncherTabletPortraitScaler.dp` 缩放。
     *
     * 提升为公开 @JvmStatic 以消除各处私有副本（如 AgentLlmConfigDialog），统一弹窗宽度算法单一来源。
     */
    @JvmStatic
    fun dialogWidthPx(context: Context, widthDp: Int): Int {
        return LauncherDialogParts.dialogWidthPx(context, widthDp)
    }
}
