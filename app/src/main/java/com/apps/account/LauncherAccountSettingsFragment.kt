package com.apps.account

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.apps.LauncherPreferences
import com.apps.sync.LauncherSyncScheduler
import com.apps.HDModel.HdModeActivity
import com.apps.HDModel.LauncherDialogRouter
import com.apps.common.LauncherInsetsHelper
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherAccountSettingsBinding
import com.core.launcherbridge.ConfigCallback
import com.core.launcherbridge.LauncherAuthBridge
import com.core.launcherbridge.PlayDataCallback
import com.core.launcherbridge.SubscriptionCallback
import com.core.userdata.LauncherUserData

/**
 * 账号设置页（重构计划 9.9 阶段 111 自 LauncherAccountSettingsActivity 抽取）。
 *
 * 竖屏由 [LauncherAccountSettingsActivity] 薄宿主承载，HD 由
 * [com.apps.HDModel.HdProfileFragment] 作为子 Fragment 承载。
 */
class LauncherAccountSettingsFragment : Fragment() {
    private var binding: ActivityLauncherAccountSettingsBinding? = null
    private var loadingDialog: AlertDialog? = null
    private var emailSubscriptionUpdating = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherAccountSettingsBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (activity !is HdModeActivity) LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        LauncherInsetsHelper.applyTopInset(currentBinding.root, currentBinding.accountSettingsScroll)
        LauncherTheme.applyPrimaryTone(view)
        LauncherTheme.styleMaterialSwitch(currentBinding.chipSyncConfig)
        LauncherTheme.styleMaterialSwitch(currentBinding.chipRealtimePlaytime)
        LauncherTheme.styleMaterialSwitch(currentBinding.chipEmailSubscribe)

        bindActions()
        renderAllChips()
        refreshEmailSubscription()
        LauncherSyncScheduler.updateSchedule(requireContext())
    }

    override fun onDestroyView() {
        loadingDialog?.let { if (it.isShowing) it.dismiss() }
        loadingDialog = null
        binding = null
        super.onDestroyView()
    }

    private fun bindActions() {
        val currentBinding = binding ?: return
        currentBinding.rowSyncConfig.setOnClickListener { onSyncConfigClick() }
        currentBinding.rowRealtimePlaytime.setOnClickListener { onRealtimePlaytimeClick() }
        currentBinding.rowEmailSubscribe.setOnClickListener { onEmailSubscriptionClick() }
    }

    private fun refreshEmailSubscription() {
        if (!LauncherAuthBridge.isLoggedIn(requireContext())) return
        LauncherAuthBridge.fetchEmailSubscription(
            requireContext(),
            object : SubscriptionCallback {
                override fun onSuccess(subscribed: Boolean) {
                    if (!isAdded) return
                    saveEmailSubscription(subscribed)
                    binding?.let { renderChip(it.chipEmailSubscribe, subscribed) }
                }

                override fun onError(message: String) {
                    // 保留本地缓存状态；网络错误不打断账号设置页的其他操作。
                }
            },
        )
    }

    private fun onEmailSubscriptionClick() {
        if (emailSubscriptionUpdating) return
        if (!LauncherAuthBridge.isLoggedIn(requireContext())) {
            showResultDialog(
                getString(R.string.social_login_required),
                getString(R.string.social_subscription_login_required),
            )
            return
        }
        val subscribed = requireContext()
            .getSharedPreferences(LauncherPreferences.ACCOUNT_SETTINGS_PREFS, 0)
            .getBoolean("email_subscribe", false)
        if (subscribed) {
            updateEmailSubscription(false)
        } else {
            showEmailSubscriptionConfirmDialog()
        }
    }

    private fun showEmailSubscriptionConfirmDialog() {
        LauncherDialogRouter.showConfirm(
            requireContext(),
            getString(R.string.social_enable_subscription),
            getString(R.string.social_enable_subscription_message),
            getString(R.string.social_enable_subscription),
        ) { updateEmailSubscription(true) }
    }

    private fun updateEmailSubscription(subscribed: Boolean) {
        val currentBinding = binding ?: return
        emailSubscriptionUpdating = true
        currentBinding.rowEmailSubscribe.isEnabled = false
        LauncherAuthBridge.updateEmailSubscription(
            requireContext(),
            subscribed,
            object : SubscriptionCallback {
                override fun onSuccess(actualSubscribed: Boolean) {
                    if (!isAdded) return
                    emailSubscriptionUpdating = false
                    val current = binding ?: return
                    current.rowEmailSubscribe.isEnabled = true
                    saveEmailSubscription(actualSubscribed)
                    renderChip(current.chipEmailSubscribe, actualSubscribed)
                    Toast.makeText(
                        requireContext(),
                        if (actualSubscribed) R.string.social_subscription_enabled
                        else R.string.social_subscription_disabled,
                        Toast.LENGTH_SHORT,
                    ).show()
                }

                override fun onError(message: String) {
                    if (!isAdded) return
                    emailSubscriptionUpdating = false
                    val current = binding ?: return
                    current.rowEmailSubscribe.isEnabled = true
                    showResultDialog(getString(R.string.social_subscription_update_failed), message)
                }
            },
        )
    }

    private fun saveEmailSubscription(subscribed: Boolean) {
        requireContext().getSharedPreferences(LauncherPreferences.ACCOUNT_SETTINGS_PREFS, 0)
            .edit().putBoolean("email_subscribe", subscribed).apply()
    }

    private fun onSyncConfigClick() {
        val currentBinding = binding ?: return
        val prefs = requireContext().getSharedPreferences(LauncherPreferences.ACCOUNT_SETTINGS_PREFS, 0)
        val currentEnabled = prefs.getBoolean("sync_config", false)
        if (currentEnabled) {
            // 关闭：直接关闭并取消定时备份
            prefs.edit().putBoolean("sync_config", false).apply()
            renderChip(currentBinding.chipSyncConfig, false)
            LauncherSyncScheduler.updateSchedule(requireContext())
            return
        }
        // 开启：弹窗确认是否上传当前配置
        showSyncConfirmDialog()
    }

    private fun onRealtimePlaytimeClick() {
        val currentBinding = binding ?: return
        val prefs = requireContext().getSharedPreferences(LauncherPreferences.ACCOUNT_SETTINGS_PREFS, 0)
        val currentEnabled = prefs.getBoolean("realtime_playtime", getDefault("realtime_playtime"))
        if (currentEnabled) {
            // 关闭：直接关闭
            prefs.edit().putBoolean("realtime_playtime", false).apply()
            renderChip(currentBinding.chipRealtimePlaytime, false)
            return
        }
        // 开启：弹窗确认
        showRealtimePlaytimeConfirmDialog()
    }

    private fun showRealtimePlaytimeConfirmDialog() {
        LauncherDialogRouter.showStandardConfirm(
            requireContext(),
            getString(R.string.social_realtime_play_time),
            getString(R.string.social_realtime_play_time_message),
            getString(R.string.social_confirm_enable),
        ) {
            requireContext().getSharedPreferences(LauncherPreferences.ACCOUNT_SETTINGS_PREFS, 0)
                .edit().putBoolean("realtime_playtime", true).apply()
            binding?.let { renderChip(it.chipRealtimePlaytime, true) }
        }
    }

    private fun showSyncConfirmDialog() {
        LauncherDialogRouter.showStandardConfirm(
            requireContext(),
            getString(R.string.social_config_sync),
            getString(R.string.social_config_sync_message),
            getString(R.string.social_confirm_upload),
            ::enableSyncAndUpload,
        )
    }

    private fun enableSyncAndUpload() {
        val currentBinding = binding ?: return
        // 先开启开关
        requireContext().getSharedPreferences(LauncherPreferences.ACCOUNT_SETTINGS_PREFS, 0)
            .edit().putBoolean("sync_config", true).apply()
        renderChip(currentBinding.chipSyncConfig, true)
        LauncherSyncScheduler.updateSchedule(requireContext())

        // 显示加载弹窗
        loadingDialog = showLoadingDialog(
            getString(R.string.social_uploading_config),
            getString(R.string.social_uploading_config_note),
        )

        // 导出并上传
        val settingsJson = LauncherUserData.exportSettingsJson(requireContext()) ?: ""
        LauncherAuthBridge.uploadConfig(
            requireContext(),
            settingsJson,
            object : ConfigCallback {
                override fun onSuccess(configJson: String) {
                    if (!isAdded) return
                    // 上传游玩记录
                    val playData = LauncherUserData.exportCloudPlayData(requireContext())
                    if (playData == null || playData.trim().isEmpty()) {
                        // 导出失败，仅配置上传成功
                        dismissLoading()
                        showResultDialog(
                            getString(R.string.social_partial_upload_failed),
                            getString(R.string.social_export_failed),
                        )
                        return
                    }
                    LauncherAuthBridge.uploadPlayData(
                        requireContext(),
                        playData,
                        object : PlayDataCallback {
                            override fun onSuccess(playData: String) {
                                if (!isAdded) return
                                dismissLoading()
                                showResultDialog(
                                    getString(R.string.social_upload_success),
                                    getString(R.string.social_upload_success_all),
                                )
                            }

                            override fun onError(message: String) {
                                if (!isAdded) return
                                dismissLoading()
                                // 服务端对未变化的游玩数据会以 USER_NOT_FOUND 返回；
                                // 前一步配置上传已成功，故将其视为无需重复上传。
                                if (isUnchangedPlayDataError(message)) {
                                    showResultDialog(
                                        getString(R.string.social_upload_success),
                                        getString(R.string.social_upload_no_changes),
                                    )
                                    return
                                }
                                showResultDialog(
                                    getString(R.string.social_partial_upload_failed),
                                    getString(R.string.social_play_record_upload_failed, message),
                                )
                            }
                        },
                    )
                }

                override fun onError(message: String) {
                    if (!isAdded) return
                    dismissLoading()
                    showResultDialog(getString(R.string.social_upload_failed), message)
                }
            },
        )
    }

    private fun isUnchangedPlayDataError(message: String?): Boolean {
        // 仅匹配服务端错误码；删除中文文案分支，避免客户端文案依赖
        return message != null && message.contains("USER_NOT_FOUND")
    }

    private fun showLoadingDialog(titleText: String, hintText: String): AlertDialog {
        return LauncherDialogRouter.showLoading(requireContext(), titleText, hintText)
    }

    private fun dismissLoading() {
        loadingDialog?.let {
            if (it.isShowing) it.dismiss()
        }
        loadingDialog = null
    }

    private fun showResultDialog(title: String, message: String) {
        LauncherDialogRouter.showInfo(requireContext(), title, message)
    }

    private fun renderAllChips() {
        val currentBinding = binding ?: return
        val prefs = requireContext().getSharedPreferences(LauncherPreferences.ACCOUNT_SETTINGS_PREFS, 0)
        renderChip(currentBinding.chipSyncConfig, prefs.getBoolean("sync_config", getDefault("sync_config")))
        renderChip(
            currentBinding.chipRealtimePlaytime,
            prefs.getBoolean("realtime_playtime", getDefault("realtime_playtime")),
        )
        renderChip(currentBinding.chipEmailSubscribe, prefs.getBoolean("email_subscribe", getDefault("email_subscribe")))
    }

    private fun renderChip(chip: androidx.appcompat.widget.SwitchCompat, enabled: Boolean) {
        chip.isChecked = enabled
    }

    private fun getDefault(key: String): Boolean = when (key) {
        "realtime_playtime" -> true
        else -> false
    }

}
