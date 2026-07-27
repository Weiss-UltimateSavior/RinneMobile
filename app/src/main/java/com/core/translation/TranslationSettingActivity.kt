package com.core.translation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.apps.LauncherActivity
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherTheme
import com.core.R
import com.core.databinding.ActivityTranslationSettingBinding
import com.core.util.AppExecutors

/**
 * 智能翻译配置页。
 *
 * 布局风格与 [com.apps.settings.LauncherKrkrSettingsActivity] 一致：
 * FrameLayout + ScrollView，区段式排版，输入框使用 LauncherFormInput 样式，
 * 按钮使用 LauncherLongActionButton 样式。
 *
 * 开关打开时若权限未齐会引导用户授权；权限齐备后启动 [OverlayTranslationService]。
 */
class TranslationSettingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTranslationSettingBinding
    private var awaitingOverlayPermissionResult = false

    private val projectionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                OverlayTranslationService.projectionData = result.data
                OverlayTranslationService.projectionResultCode = result.resultCode
                refreshPermissionStatus()
                tryStartServiceIfReady()
            } else {
                // 用户拒绝截屏授权，回滚开关状态
                setEnabledSwitchChecked(false)
                Toast.makeText(this, "未授予截屏权限，已关闭悬浮翻译", Toast.LENGTH_SHORT).show()
            }
        }

    private val notificationPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 无论是否授予都继续尝试启动 Service，通知权限缺失不会阻止前台 Service
            tryStartServiceIfReady()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherActivity.applySavedToneMode(this)
        super.onCreate(savedInstanceState)
        configureEdgeToEdgeWindow()

        binding = ActivityTranslationSettingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        // 冷启动时 projectionData 静态变量丢失，截屏权限已失效，
        // 自动关闭启用开关并停止可能残留的 Service，让用户手动重新授权开启。
        if (OverlayTranslationService.projectionData == null &&
            TranslationConfigStore.get(this).enabled
        ) {
            TranslationConfigStore.setEnabled(this, false)
            // 停止可能仍在运行的孤儿 Service，避免悬浮按钮无法关闭
            stopService(Intent(this, OverlayTranslationService::class.java))
        }

        initViews()
        renderConfig()
        bindActions()
        refreshPermissionStatus()
    }

    private fun initViews() {
        LauncherTheme.applyPrimaryTone(binding.translationSettingRoot)
        LauncherTheme.styleSwitch(binding.translationEnabledSwitch)
        LauncherTheme.formInputs(binding.translationBaseUrlInput, binding.translationModelInput, binding.translationApiKeyInput)
        // 显式应用主题色按钮样式（applyPrimaryTone 按 id 白名单匹配，自定义 id 不会被处理）
        LauncherTheme.primaryButton(binding.translationSaveButton)
        LauncherTheme.secondaryButton(binding.translationTestButton)
        binding.translationOverlayButton.background = null
        binding.translationOverlayButton.setTextColor(LauncherTheme.primary(this))
    }

    private fun renderConfig() {
        val config = TranslationConfigStore.get(this)
        binding.translationEnabledSwitch.isChecked = config.enabled
        binding.translationBaseUrlInput.setText(config.baseUrl)
        binding.translationModelInput.setText(config.model)
        binding.translationApiKeyInput.hint = if (config.hasApiKey) "已保存（留空表示不修改）" else "API Key"
    }

    private fun bindActions() {
        binding.translationEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            TranslationConfigStore.setEnabled(this, isChecked)
            if (isChecked) {
                // 启动失败时回滚开关，用 setEnabledSwitchChecked 避免递归
                if (!tryStartServiceIfReady()) {
                    setEnabledSwitchChecked(false)
                }
            } else {
                stopService(Intent(this, OverlayTranslationService::class.java))
            }
        }
        binding.translationSaveButton.setOnClickListener { saveConfig() }
        binding.translationTestButton.setOnClickListener { testConnection() }
        binding.translationOverlayButton.setOnClickListener { requestOverlayPermission() }
    }

    /**
     * 安全地修改开关状态，避免触发 [enabledSwitch] 的 OnCheckedChangeListener 递归。
     */
    private fun setEnabledSwitchChecked(checked: Boolean) {
        binding.translationEnabledSwitch.setOnCheckedChangeListener(null)
        binding.translationEnabledSwitch.isChecked = checked
        TranslationConfigStore.setEnabled(this, checked)
        // 重新绑定 listener
        binding.translationEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            TranslationConfigStore.setEnabled(this, isChecked)
            if (isChecked) {
                if (!tryStartServiceIfReady()) {
                    setEnabledSwitchChecked(false)
                }
            } else {
                stopService(Intent(this, OverlayTranslationService::class.java))
            }
        }
    }

    private fun saveConfig() {
        val baseUrl = binding.translationBaseUrlInput.text?.toString() ?: ""
        val model = binding.translationModelInput.text?.toString() ?: ""
        val apiKey = binding.translationApiKeyInput.text?.toString() ?: ""
        val replaceKey = apiKey.isNotEmpty()
        try {
            TranslationConfigStore.save(this, baseUrl, model, apiKey, replaceKey)
            binding.translationApiKeyInput.setText("")
            binding.translationApiKeyInput.hint = "已保存（留空表示不修改）"
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
            renderConfig()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 测试当前输入的 API 配置是否可用、模型是否支持图像识别。
     *
     * 使用输入框中的地址和模型（未输入则回退到已保存配置），
     * 发送一张 2x2 测试图片，根据响应弹统一信息窗提示结果。
     */
    private fun testConnection() {
        val baseUrl = binding.translationBaseUrlInput.text?.toString()?.trim() ?: ""
        val model = binding.translationModelInput.text?.toString()?.trim() ?: ""
        // 未填写时回退到已保存配置
        val effectiveUrl = baseUrl.takeIf { it.isNotEmpty() }
            ?: TranslationConfigStore.get(this).baseUrl
        val effectiveModel = model.takeIf { it.isNotEmpty() }
            ?: TranslationConfigStore.get(this).model
        if (effectiveUrl.isEmpty() || effectiveModel.isEmpty()) {
            LauncherDialogFactory.showInfo(this, "测试失败", "请先填写 API 地址和模型名称")
            return
        }
        if (!TranslationConfigStore.get(this).hasApiKey) {
            LauncherDialogFactory.showInfo(this, "测试失败", "请先保存 API Key")
            return
        }
        // 校验 API 地址格式（复用保存配置时的校验逻辑）
        try {
            TranslationConfigStore.validateBaseUrl(effectiveUrl)
        } catch (e: Exception) {
            LauncherDialogFactory.showInfo(this, "测试失败", e.message ?: "API 地址格式不正确")
            return
        }

        val loadingDialog = LauncherDialogFactory.showLoading(this, "正在测试", "发送测试图片到模型...")
        binding.translationTestButton.isEnabled = false
        AppExecutors.runOnSingle {
            val result = VisionTranslationClient.testVision(this, effectiveUrl, effectiveModel)
            runOnUiThread {
                loadingDialog.dismiss()
                binding.translationTestButton.isEnabled = true
                if (result.success) {
                    LauncherDialogFactory.showInfo(
                        this,
                        "测试成功",
                        "模型支持图像识别。\n\n模型回复：\n${result.text}"
                    )
                } else {
                    LauncherDialogFactory.showInfo(
                        this,
                        "测试失败",
                        result.text
                    )
                }
            }
        }
    }

    private fun refreshPermissionStatus() {
        if (hasOverlayPermission()) {
            binding.translationOverlayStatus.text = "已授权"
            binding.translationOverlayStatus.setTextColor(LauncherTheme.primary(this))
            binding.translationOverlayButton.visibility = View.GONE
        } else {
            binding.translationOverlayStatus.text = "未授权"
            binding.translationOverlayStatus.setTextColor(getColor(R.color.launcher_text_muted_color))
            binding.translationOverlayButton.visibility = View.VISIBLE
        }
        if (OverlayTranslationService.projectionData != null) {
            binding.translationProjectionStatus.text = "已授权"
            binding.translationProjectionStatus.setTextColor(LauncherTheme.primary(this))
        } else {
            binding.translationProjectionStatus.text = "未授权（开启开关时申请）"
            binding.translationProjectionStatus.setTextColor(getColor(R.color.launcher_text_muted_color))
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            awaitingOverlayPermissionResult = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, "悬浮窗权限已授权", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 尝试启动悬浮翻译 Service。
     *
     * @return true 表示已启动或正在申请权限中（开关保持开启）；
     *         false 表示因条件不齐启动失败（调用方应回滚开关）。
     */
    private fun tryStartServiceIfReady(): Boolean {
        val config = TranslationConfigStore.get(this)
        if (!config.isReady()) {
            Toast.makeText(this, "请先填写 API 配置", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!hasOverlayPermission()) {
            Toast.makeText(this, "请先授权悬浮窗权限", Toast.LENGTH_SHORT).show()
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
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return true
        }
        val intent = Intent(this, OverlayTranslationService::class.java)
        startService(intent)
        return true
    }

    private fun requestProjectionPermission() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        if (awaitingOverlayPermissionResult) {
            awaitingOverlayPermissionResult = false
            if (hasOverlayPermission() && OverlayTranslationService.projectionData == null) {
                // 悬浮窗授权成功后，顺序申请截屏权限；点“授权”文字和开启开关都适用。
                requestProjectionPermission()
            } else if (!hasOverlayPermission() && binding.translationEnabledSwitch.isChecked) {
                setEnabledSwitchChecked(false)
            }
        }
    }

    /**
     * 配置 edge-to-edge 窗口：透明状态栏，状态栏图标根据深色模式切换。
     * 与 LauncherKrkrSettingsActivity 保持一致。
     */
    private fun configureEdgeToEdgeWindow() {
        val darkMode = LauncherActivity.isLauncherDarkMode(this)
        val window: Window = window
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = ContextCompat.getColor(this, R.color.launcher_bg_color)
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        if (!darkMode) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    /**
     * 将系统状态栏高度应用为 ScrollView 的顶部 padding，避免内容被状态栏遮挡。
     */
    private fun applySystemBarInsets() {
        val left = binding.translationScroll.paddingLeft
        val top = binding.translationScroll.paddingTop
        val right = binding.translationScroll.paddingRight
        val bottom = binding.translationScroll.paddingBottom
        binding.translationScroll.setOnApplyWindowInsetsListener { view, insets ->
            binding.translationScroll.setPadding(left, top + insets.systemWindowInsetTop, right, bottom)
            insets
        }
        binding.translationScroll.requestApplyInsets()
    }
}
