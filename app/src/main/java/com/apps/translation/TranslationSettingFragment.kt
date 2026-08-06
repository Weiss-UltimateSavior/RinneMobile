package com.apps.translation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.apps.common.LauncherInsetsHelper
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityTranslationSettingBinding
import com.core.translation.OverlayTranslationService
import com.core.translation.TranslationConfigStore
import com.core.translation.VisionTranslationClient
import com.core.util.AppExecutors

/**
 * 智能翻译配置页（重构计划 9.9 阶段 111 自 TranslationSettingActivity 抽取）。
 *
 * 竖屏由 [TranslationSettingActivity] 薄宿主承载，HD 由
 * [com.apps.HDModel.HdProfileFragment] 作为子 Fragment 承载；
 * 截屏授权/通知权限经 Fragment 自身 ActivityResultRegistry 接收，不再委托宿主代理。
 */
class TranslationSettingFragment : Fragment() {
    private var binding: ActivityTranslationSettingBinding? = null
    private var awaitingOverlayPermissionResult = false

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onProjectionPermissionResult(result.resultCode, result.data)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 无论是否授予都继续尝试启动 Service，通知权限缺失不会阻止前台 Service
            startOverlayService()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityTranslationSettingBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        LauncherInsetsHelper.applyTopInset(currentBinding.root, currentBinding.translationScroll)

        // 冷启动时 projectionData 静态变量丢失，截屏权限已失效，
        // 自动关闭启用开关并停止可能残留的 Service，让用户手动重新授权开启。
        if (OverlayTranslationService.projectionData == null &&
            TranslationConfigStore.get(requireContext()).enabled
        ) {
            TranslationConfigStore.setEnabled(requireContext(), false)
            // 停止可能仍在运行的孤儿 Service，避免悬浮按钮无法关闭
            requireContext().stopService(Intent(requireContext(), OverlayTranslationService::class.java))
        }

        initViews()
        renderConfig()
        bindActions()
        refreshPermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        if (awaitingOverlayPermissionResult) {
            awaitingOverlayPermissionResult = false
            if (hasOverlayPermission() && OverlayTranslationService.projectionData == null) {
                // 悬浮窗授权成功后，顺序申请截屏权限；点"授权"文字和开启开关都适用。
                requestProjectionPermission()
            } else if (!hasOverlayPermission() && binding?.translationEnabledSwitch?.isChecked == true) {
                setEnabledSwitchChecked(false)
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun initViews() {
        val currentBinding = binding ?: return
        LauncherTheme.applyPrimaryTone(currentBinding.translationSettingRoot)
        LauncherTheme.styleMaterialSwitch(currentBinding.translationEnabledSwitch)
        LauncherTheme.formInputs(
            currentBinding.translationBaseUrlInput,
            currentBinding.translationModelInput,
            currentBinding.translationApiKeyInput,
        )
        // 显式应用主题色按钮样式（applyPrimaryTone 按 id 白名单匹配，自定义 id 不会被处理）
        LauncherTheme.primaryButton(currentBinding.translationSaveButton)
        LauncherTheme.secondaryButton(currentBinding.translationTestButton)
        currentBinding.translationOverlayButton.background = null
        currentBinding.translationOverlayButton.setTextColor(LauncherTheme.primary(requireContext()))
    }

    private fun renderConfig() {
        val currentBinding = binding ?: return
        val config = TranslationConfigStore.get(requireContext())
        currentBinding.translationEnabledSwitch.isChecked = config.enabled
        currentBinding.translationBaseUrlInput.setText(config.baseUrl)
        currentBinding.translationModelInput.setText(config.model)
        currentBinding.translationApiKeyInput.hint = getString(
            if (config.hasApiKey) R.string.translation_api_key_saved_hint
            else R.string.translation_api_key,
        )
    }

    private fun bindActions() {
        val currentBinding = binding ?: return
        currentBinding.translationEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            TranslationConfigStore.setEnabled(requireContext(), isChecked)
            if (isChecked) {
                // 启动失败时回滚开关，用 setEnabledSwitchChecked 避免递归
                if (!tryStartServiceIfReady()) {
                    setEnabledSwitchChecked(false)
                }
            } else {
                requireContext().stopService(Intent(requireContext(), OverlayTranslationService::class.java))
            }
        }
        currentBinding.translationSaveButton.setOnClickListener { saveConfig() }
        currentBinding.translationTestButton.setOnClickListener { testConnection() }
        currentBinding.translationOverlayButton.setOnClickListener { requestOverlayPermission() }
    }

    /**
     * 安全地修改开关状态，避免触发 [translationEnabledSwitch] 的 OnCheckedChangeListener 递归。
     */
    private fun setEnabledSwitchChecked(checked: Boolean) {
        val currentBinding = binding ?: return
        currentBinding.translationEnabledSwitch.setOnCheckedChangeListener(null)
        currentBinding.translationEnabledSwitch.isChecked = checked
        TranslationConfigStore.setEnabled(requireContext(), checked)
        // 重新绑定 listener
        currentBinding.translationEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            TranslationConfigStore.setEnabled(requireContext(), isChecked)
            if (isChecked) {
                if (!tryStartServiceIfReady()) {
                    setEnabledSwitchChecked(false)
                }
            } else {
                requireContext().stopService(Intent(requireContext(), OverlayTranslationService::class.java))
            }
        }
    }

    private fun saveConfig() {
        val currentBinding = binding ?: return
        val baseUrl = currentBinding.translationBaseUrlInput.text?.toString() ?: ""
        val model = currentBinding.translationModelInput.text?.toString() ?: ""
        val apiKey = currentBinding.translationApiKeyInput.text?.toString() ?: ""
        val replaceKey = apiKey.isNotEmpty()
        try {
            TranslationConfigStore.save(requireContext(), baseUrl, model, apiKey, replaceKey)
            currentBinding.translationApiKeyInput.setText("")
            currentBinding.translationApiKeyInput.setHint(R.string.translation_api_key_saved_hint)
            Toast.makeText(requireContext(), R.string.translation_configuration_saved, Toast.LENGTH_SHORT).show()
            renderConfig()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.translation_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 测试当前输入的 API 配置是否可用、模型是否支持图像识别。
     *
     * 使用输入框中的地址和模型（未输入则回退到已保存配置），
     * 发送一张 2x2 测试图片，根据响应弹统一信息窗提示结果。
     */
    private fun testConnection() {
        val currentBinding = binding ?: return
        val baseUrl = currentBinding.translationBaseUrlInput.text?.toString()?.trim() ?: ""
        val model = currentBinding.translationModelInput.text?.toString()?.trim() ?: ""
        // 未填写时回退到已保存配置
        val effectiveUrl = baseUrl.takeIf { it.isNotEmpty() }
            ?: TranslationConfigStore.get(requireContext()).baseUrl
        val effectiveModel = model.takeIf { it.isNotEmpty() }
            ?: TranslationConfigStore.get(requireContext()).model
        if (effectiveUrl.isEmpty() || effectiveModel.isEmpty()) {
            LauncherDialogFactory.showInfo(
                requireContext(),
                getString(R.string.translation_test_failed),
                getString(R.string.translation_enter_api_and_model),
            )
            return
        }
        if (!TranslationConfigStore.get(requireContext()).hasApiKey) {
            LauncherDialogFactory.showInfo(
                requireContext(),
                getString(R.string.translation_test_failed),
                getString(R.string.translation_save_api_key_first),
            )
            return
        }
        // 校验 API 地址格式（复用保存配置时的校验逻辑）
        try {
            TranslationConfigStore.validateBaseUrl(effectiveUrl)
        } catch (e: Exception) {
            LauncherDialogFactory.showInfo(
                requireContext(),
                getString(R.string.translation_test_failed),
                getString(R.string.translation_invalid_api_address),
            )
            return
        }

        val loadingDialog = LauncherDialogFactory.showLoading(
            requireContext(),
            getString(R.string.translation_testing),
            getString(R.string.translation_sending_test_image),
        )
        currentBinding.translationTestButton.isEnabled = false
        // IO 线程入队前缓存 applicationContext，避免 Fragment detach 后 requireContext() 崩溃。
        val appContext = requireContext().applicationContext
        AppExecutors.runOnSingle {
            val result = VisionTranslationClient.testVision(appContext, effectiveUrl, effectiveModel)
            activity?.runOnUiThread {
                loadingDialog.dismiss()
                if (!isAdded) return@runOnUiThread
                val current = binding ?: return@runOnUiThread
                current.translationTestButton.isEnabled = true
                if (result.success) {
                    LauncherDialogFactory.showInfo(
                        requireContext(),
                        getString(R.string.translation_test_success),
                        getString(R.string.translation_test_success_message, result.text),
                    )
                } else {
                    LauncherDialogFactory.showInfo(
                        requireContext(),
                        getString(R.string.translation_test_failed),
                        result.text,
                    )
                }
            }
        }
    }

    private fun refreshPermissionStatus() {
        val currentBinding = binding ?: return
        if (hasOverlayPermission()) {
            currentBinding.translationOverlayStatus.setText(R.string.translation_authorized)
            currentBinding.translationOverlayStatus.setTextColor(LauncherTheme.primary(requireContext()))
            currentBinding.translationOverlayButton.visibility = View.GONE
        } else {
            currentBinding.translationOverlayStatus.setText(R.string.translation_not_authorized)
            currentBinding.translationOverlayStatus.setTextColor(LauncherTheme.textMuted(requireContext()))
            currentBinding.translationOverlayButton.visibility = View.VISIBLE
        }
        if (OverlayTranslationService.projectionData != null) {
            currentBinding.translationProjectionStatus.setText(R.string.translation_authorized)
            currentBinding.translationProjectionStatus.setTextColor(LauncherTheme.primary(requireContext()))
        } else {
            currentBinding.translationProjectionStatus.setText(R.string.translation_authorize_when_enabled)
            currentBinding.translationProjectionStatus.setTextColor(LauncherTheme.textMuted(requireContext()))
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(requireContext())
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(requireContext())) {
            awaitingOverlayPermissionResult = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}"),
            )
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), R.string.translation_overlay_already_authorized, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 尝试启动悬浮翻译 Service。
     *
     * @return true 表示已启动或正在申请权限中（开关保持开启）；
     *         false 表示因条件不齐启动失败（调用方应回滚开关）。
     */
    private fun tryStartServiceIfReady(): Boolean {
        val config = TranslationConfigStore.get(requireContext())
        if (!config.isReady()) {
            Toast.makeText(requireContext(), R.string.translation_enter_api_configuration, Toast.LENGTH_SHORT).show()
            return false
        }
        if (!hasOverlayPermission()) {
            Toast.makeText(requireContext(), R.string.translation_authorize_overlay_first, Toast.LENGTH_SHORT).show()
            requestOverlayPermission()
            // 系统授权页返回后会接着申请截屏权限，开关在这段流程中应保持开启。
            return true
        }
        if (OverlayTranslationService.projectionData == null) {
            requestProjectionPermission()
            // 正在申请权限，保持开关开启等待回调
            return true
        }
        // Android 13+ 需要运行时申请通知权限，确保前台 Service 通知可见
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Fragment 自身 ActivityResultRegistry 可靠接收通知权限回调（HD 嵌入环境无需宿主代理）。
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return true
        }
        startOverlayService()
        return true
    }

    private fun startOverlayService() {
        requireContext().startService(Intent(requireContext(), OverlayTranslationService::class.java))
    }

    private fun requestProjectionPermission() {
        // Fragment 自身 ActivityResultRegistry 可靠接收截屏授权回调（HD 嵌入环境无需宿主代理）。
        val mediaProjectionManager = requireContext()
            .getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun onProjectionPermissionResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            OverlayTranslationService.projectionData = data
            OverlayTranslationService.projectionResultCode = resultCode
            refreshPermissionStatus()
            tryStartServiceIfReady()
        } else {
            // 用户拒绝截屏授权，回滚开关状态
            setEnabledSwitchChecked(false)
            Toast.makeText(requireContext(), R.string.translation_capture_denied_disabled, Toast.LENGTH_SHORT).show()
        }
    }

}
