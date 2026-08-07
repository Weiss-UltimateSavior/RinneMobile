package com.apps.HDModel

import android.content.Context
import android.content.ContextWrapper
import androidx.appcompat.app.AlertDialog
import com.apps.PadUi.PadDialogFactory
import com.apps.theme.LauncherDialogFactory
import com.core.launcherbridge.LauncherUpdateBridge

/**
 * 弹窗上下文路由器（W8，阶段 117/118）：HD 大屏壳（[HdModeActivity]）内承载的子 Fragment
 * 弹窗路由到 Pad 大屏弹窗工厂，竖屏保持 Launcher 弹窗工厂，解决新业务 Fragment
 * HD 嵌入时仍用竖屏弹窗上下文的视觉不一致（com_apps_refactor_plan.md 4.5 W8）。
 *
 * 全部 API（含 showLongMessageConfirm 审批 onCancel、R5 showInfo 4 参 onAcknowledge、
 * R3 扫描深度 / R4 跳过选择等关键路径）均已路由 Pad，无 HD 回退 Launcher 的残留重载。
 */
object LauncherDialogRouter {
    /** 判断弹窗上下文是否处于 HD 大屏壳（HdModeActivity），沿 ContextWrapper 链向上查找。 */
    private fun isHd(context: Context): Boolean {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is HdModeActivity) return true
            current = current.baseContext
        }
        return false
    }

    @JvmStatic
    fun showConfirm(
        context: Context,
        title: String?,
        message: String?,
        confirmText: String?,
        onConfirm: Runnable?,
    ) {
        if (isHd(context)) {
            PadDialogFactory.showConfirm(context, title, message, confirmText, onConfirm)
        } else {
            LauncherDialogFactory.showConfirm(context, title, message, confirmText, onConfirm)
        }
    }

    /** 双按钮确认（带取消文案与关闭回调）：会话过期等需区分「稍后」与「关闭」的场景。 */
    @JvmStatic
    fun showConfirm(
        context: Context,
        title: String?,
        message: String?,
        confirmText: String?,
        onConfirm: Runnable?,
        cancelText: CharSequence?,
        onDismiss: Runnable?,
    ): AlertDialog {
        return if (isHd(context)) {
            PadDialogFactory.showConfirm(context, title, message, confirmText, onConfirm, cancelText, onDismiss)
        } else {
            LauncherDialogFactory.showConfirm(context, title, message, confirmText, onConfirm, cancelText, onDismiss)
        }
    }

    @JvmStatic
    fun showStandardConfirm(
        context: Context,
        title: String?,
        message: String?,
        confirmText: String?,
        onConfirm: Runnable?,
    ) {
        if (isHd(context)) {
            PadDialogFactory.showStandardConfirm(context, title, message, confirmText, onConfirm)
        } else {
            LauncherDialogFactory.showStandardConfirm(context, title, message, confirmText, onConfirm)
        }
    }

    /** 信息提示：HD 路由 Pad。 */
    @JvmStatic
    fun showInfo(context: Context, title: String?, message: String?) {
        if (isHd(context)) {
            PadDialogFactory.showInfo(context, title, message)
        } else {
            LauncherDialogFactory.showInfo(context, title, message)
        }
    }

    /** 信息提示 + 确认回调：HD 路由 Pad。 */
    @JvmStatic
    fun showInfo(context: Context, title: String?, message: String?, onAcknowledge: Runnable?) {
        if (isHd(context)) {
            PadDialogFactory.showInfo(context, title, message, onAcknowledge)
        } else {
            LauncherDialogFactory.showInfo(context, title, message, onAcknowledge)
        }
    }

    @JvmStatic
    fun showLoading(context: Context, title: String?, hint: String?): AlertDialog {
        return if (isHd(context)) {
            PadDialogFactory.showLoading(context, title, hint)
        } else {
            LauncherDialogFactory.showLoading(context, title, hint)
        }
    }

    /** 非可取消加载壳 + 带 tag 的进度文本（同步流程更新 tagged view 用）；HD 路由 Pad 对应实现。 */
    @JvmStatic
    fun showProgressLoading(
        context: Context,
        title: String?,
        progressText: String?,
        hint: String?,
        progressTag: String?,
    ): AlertDialog {
        return if (isHd(context)) {
            PadDialogFactory.showProgressLoading(context, title, progressText, hint, progressTag)
        } else {
            LauncherDialogFactory.showProgressLoading(context, title, progressText, hint, progressTag)
        }
    }

    /** 滚动长消息确认（无取消回调）：HD 路由 Pad 滚动版。 */
    @JvmStatic
    fun showLongMessageConfirm(
        context: Context,
        title: String?,
        message: String?,
        confirmText: String?,
        onConfirm: Runnable?,
    ): AlertDialog {
        return if (isHd(context)) {
            PadDialogFactory.showLongMessageConfirm(context, title, message, confirmText, onConfirm, null)
        } else {
            LauncherDialogFactory.showLongMessageConfirm(context, title, message, confirmText, onConfirm)
        }
    }

    /** 滚动长消息确认（带取消回调）：审批关键路径（responder.resolve(false)），HD 路由 Pad 滚动版（onCancel 语义对齐）。 */
    @JvmStatic
    fun showLongMessageConfirm(
        context: Context,
        title: String?,
        message: String?,
        confirmText: String?,
        onConfirm: Runnable?,
        onCancel: Runnable?,
    ): AlertDialog {
        return if (isHd(context)) {
            PadDialogFactory.showLongMessageConfirm(context, title, message, confirmText, onConfirm, onCancel)
        } else {
            LauncherDialogFactory.showLongMessageConfirm(context, title, message, confirmText, onConfirm, onCancel)
        }
    }

    @JvmStatic
    fun showDangerConfirm(
        context: Context,
        title: String?,
        message: String?,
        dangerText: String?,
        onConfirm: Runnable?,
    ) {
        if (isHd(context)) {
            PadDialogFactory.showDangerConfirm(context, title, message, dangerText, onConfirm)
        } else {
            LauncherDialogFactory.showDangerConfirm(context, title, message, dangerText, onConfirm)
        }
    }

    /** 动作选项（无 danger 标记）：HD 路由 Pad（等价 dangerIndex=-1）。 */
    @JvmStatic
    fun showActionChoices(
        context: Context,
        title: String?,
        choices: Array<CharSequence>?,
        listener: ((Int) -> Unit)?,
    ) {
        if (isHd(context)) {
            PadDialogFactory.showActionChoices(context, title, choices, listener?.let { PadDialogFactory.ChoiceListener(it) })
        } else {
            LauncherDialogFactory.showActionChoices(context, title, choices, listener?.let { LauncherDialogFactory.ChoiceListener(it) })
        }
    }

    @JvmStatic
    fun showActionChoices(
        context: Context,
        title: String?,
        choices: Array<CharSequence>?,
        dangerIndex: Int,
        listener: ((Int) -> Unit)?,
    ) {
        if (isHd(context)) {
            PadDialogFactory.showActionChoices(context, title, choices, dangerIndex, listener?.let { PadDialogFactory.ChoiceListener(it) })
        } else {
            LauncherDialogFactory.showActionChoices(context, title, choices, dangerIndex, listener?.let { LauncherDialogFactory.ChoiceListener(it) })
        }
    }

    /** 消息 + 动作选择菜单（无自定义取消文案）。 */
    @JvmStatic
    fun showMessageActionChoices(
        context: Context,
        title: String?,
        message: String?,
        choices: Array<CharSequence>?,
        listener: ((Int) -> Unit)?,
    ) {
        if (isHd(context)) {
            PadDialogFactory.showMessageActionChoices(
                context, title, message, choices, null,
                listener?.let { PadDialogFactory.ChoiceListener(it) },
            )
        } else {
            LauncherDialogFactory.showMessageActionChoices(
                context, title, message, choices,
                listener?.let { LauncherDialogFactory.ChoiceListener(it) },
            )
        }
    }

    /** 消息 + 动作选择菜单（带自定义取消文案）。 */
    @JvmStatic
    fun showMessageActionChoices(
        context: Context,
        title: String?,
        message: String?,
        choices: Array<CharSequence>?,
        cancelText: CharSequence?,
        listener: ((Int) -> Unit)?,
    ) {
        if (isHd(context)) {
            PadDialogFactory.showMessageActionChoices(
                context, title, message, choices, cancelText,
                listener?.let { PadDialogFactory.ChoiceListener(it) },
            )
        } else {
            LauncherDialogFactory.showMessageActionChoices(
                context, title, message, choices, cancelText,
                listener?.let { LauncherDialogFactory.ChoiceListener(it) },
            )
        }
    }

    /** 扫描深度选择（快速/完整切换 + 深度选项）：HD 路由 Pad。 */
    @JvmStatic
    fun showScanDepthChoices(
        context: Context,
        title: String?,
        quickModeText: String?,
        fullModeText: String?,
        labels: Array<CharSequence>?,
        depthValues: IntArray?,
        currentDepth: Int,
        listener: ((depth: Int, fullRefresh: Boolean) -> Unit)?,
    ) {
        if (isHd(context)) {
            PadDialogFactory.showScanDepthChoices(
                context, title, quickModeText, fullModeText, labels, depthValues, currentDepth,
                listener?.let { LauncherDialogFactory.ScanDepthListener(it) },
            )
        } else {
            LauncherDialogFactory.showScanDepthChoices(
                context, title, quickModeText, fullModeText, labels, depthValues, currentDepth,
                listener?.let { LauncherDialogFactory.ScanDepthListener(it) },
            )
        }
    }

    /** 文本选择 + 跳过/取消（xp3 目标解析等）：HD 路由 Pad。 */
    @JvmStatic
    fun showTextChoicesWithSkip(
        context: Context,
        title: String?,
        message: String?,
        choices: List<String>?,
        skipText: String?,
        cancelText: String?,
        listener: ((String) -> Unit)?,
        onSkip: Runnable?,
        onCancel: Runnable?,
    ) {
        if (isHd(context)) {
            PadDialogFactory.showTextChoicesWithSkip(
                context, title, message, choices, skipText, cancelText,
                listener?.let { LauncherDialogFactory.TextChoiceListener(it) },
                onSkip, onCancel,
            )
        } else {
            LauncherDialogFactory.showTextChoicesWithSkip(
                context, title, message, choices, skipText, cancelText,
                listener?.let { LauncherDialogFactory.TextChoiceListener(it) },
                onSkip, onCancel,
            )
        }
    }

    /** 更新结果弹窗：HD 路由 Pad。 */
    @JvmStatic
    fun showUpdateResult(
        context: Context,
        info: LauncherUpdateBridge.UpdateInfo?,
        currentVersion: String?,
        hasUpdate: Boolean,
        error: String?,
    ) {
        if (isHd(context)) {
            PadDialogFactory.showUpdateResult(context, info, currentVersion, hasUpdate, error)
        } else {
            LauncherDialogFactory.showUpdateResult(context, info, currentVersion, hasUpdate, error)
        }
    }

    /** 标准动作选项菜单：Pad 侧用 showActionChoices 视觉近似（宽度/样式差异说明性接受）。 */
    @JvmStatic
    fun showStandardActionChoices(
        context: Context,
        title: String?,
        choices: Array<CharSequence>?,
        listener: ((Int) -> Unit)?,
    ) {
        if (isHd(context)) {
            PadDialogFactory.showActionChoices(context, title, choices, -1, listener?.let { PadDialogFactory.ChoiceListener(it) })
        } else {
            LauncherDialogFactory.showStandardActionChoices(context, title, choices, listener?.let { LauncherDialogFactory.ChoiceListener(it) })
        }
    }

    @JvmStatic
    fun showStandardActionChoices(
        context: Context,
        title: String?,
        choices: Array<CharSequence>?,
        dangerIndex: Int,
        listener: ((Int) -> Unit)?,
    ) {
        if (isHd(context)) {
            PadDialogFactory.showActionChoices(context, title, choices, dangerIndex, listener?.let { PadDialogFactory.ChoiceListener(it) })
        } else {
            LauncherDialogFactory.showStandardActionChoices(context, title, choices, dangerIndex, listener?.let { LauncherDialogFactory.ChoiceListener(it) })
        }
    }

    @JvmStatic
    fun showSingleChoice(
        context: Context,
        title: String?,
        choices: Array<CharSequence>?,
        checkedIndex: Int,
        listener: ((Int) -> Unit)?,
    ) {
        if (isHd(context)) {
            PadDialogFactory.showSingleChoice(context, title, choices, checkedIndex, listener?.let { PadDialogFactory.ChoiceListener(it) })
        } else {
            LauncherDialogFactory.showSingleChoice(context, title, choices, checkedIndex, listener?.let { LauncherDialogFactory.ChoiceListener(it) })
        }
    }
}
