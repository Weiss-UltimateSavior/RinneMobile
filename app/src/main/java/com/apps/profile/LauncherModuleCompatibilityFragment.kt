package com.apps.profile

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.apps.HDModel.HdModeActivity
import com.apps.HDModel.LauncherDialogRouter
import com.apps.common.LauncherInsetsHelper
import com.apps.theme.LauncherTheme
import com.apps.util.LauncherUrlOpener
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherModuleCompatibilityBinding
import com.core.launcherbridge.LauncherModuleBridge
import com.core.util.AppExecutors

/** 模块兼容页面：展示并管理 Rinne 所兼容的第三方 JoiPlay 插件（RPGM / RenPy / Godot）。 */
class LauncherModuleCompatibilityFragment : Fragment() {
    private var binding: ActivityLauncherModuleCompatibilityBinding? = null
    private var rpgmModuleInstalled = false
    private var renpyModuleInstalled = false
    private var godotModuleInstalled = false
    private var rpgmModuleEnabled = false
    private var renpyModuleEnabled = false
    private var godotModuleEnabled = false
    private var kirikiroid2ModuleStateCode = "not_installed"
    private var onsModuleStateCode = "not_installed"

    private val kirikiroid2ImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) importKirikiroid2Plugin(uri)
    }

    private val onsImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) importOnsPlugin(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherModuleCompatibilityBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (activity !is HdModeActivity) LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        LauncherInsetsHelper.applyTopInset(currentBinding.root, currentBinding.moduleCompatibilityScroll)
        LauncherTheme.applyPrimaryTone(view)
        ModuleType.entries.forEach { module ->
            // 行点击：已安装提示状态，未安装前往安装页。
            getModuleRowView(module).setOnClickListener { openModule(module) }
            // 长按列表项：弹窗提醒跳转浏览器下载。
            getModuleRowView(module).setOnLongClickListener {
                if (module == ModuleType.KIRIKIROID2) {
                    launchKirikiroid2ImportPicker()
                } else if (module == ModuleType.ONS) {
                    launchOnsImportPicker()
                } else {
                    promptDownload(module)
                }
                true
            }
            // 右侧图标：已安装时点击切换启用/禁用；未安装时点击等价于行点击（前往安装）。
            getModuleIconView(module).setOnClickListener { handleModuleIconClick(module) }
        }
        refreshInstalledModules()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun refreshInstalledModules() {
        val currentBinding = binding ?: return
        ModuleType.entries.forEach { module ->
            getModuleRowView(module).isEnabled = false
            getModuleRowView(module).alpha = 1f
            getModuleIconView(module).setImageTintList(
                ColorStateList.valueOf(LauncherTheme.textMuted(requireContext())),
            )
        }
        // IO 线程入队前缓存 applicationContext，避免 Fragment detach 后 requireContext() 崩溃。
        val appContext = requireContext().applicationContext
        AppExecutors.runOnIo {
            val rpgmInstalled = LauncherModuleBridge.isRpgMakerModuleInstalled(appContext)
            val renpyInstalled = LauncherModuleBridge.isRenPyModuleInstalled(appContext)
            val godotInstalled = LauncherModuleBridge.isGodotModuleInstalled(appContext)
            val rpgmEnabled = LauncherModuleBridge.isRpgMakerModuleEnabled(appContext)
            val renpyEnabled = LauncherModuleBridge.isRenPyModuleEnabled(appContext)
            val godotEnabled = LauncherModuleBridge.isGodotModuleEnabled(appContext)
            val kirikiroid2State = LauncherModuleBridge.kirikiroid2ModuleStateCode(appContext)
            val onsState = LauncherModuleBridge.onsModuleStateCode(appContext)
            activity?.runOnUiThread {
                if (!isAdded || binding == null) return@runOnUiThread
                rpgmModuleInstalled = rpgmInstalled
                renpyModuleInstalled = renpyInstalled
                godotModuleInstalled = godotInstalled
                rpgmModuleEnabled = rpgmEnabled
                renpyModuleEnabled = renpyEnabled
                godotModuleEnabled = godotEnabled
                kirikiroid2ModuleStateCode = kirikiroid2State
                onsModuleStateCode = onsState
                ModuleType.entries.forEach { module ->
                    getModuleRowView(module).isEnabled = true
                    getModuleRowView(module).alpha = 1f
                    val installed = isModuleInstalled(module)
                    val enabled = isModuleEnabled(module)
                    val invalid = isModuleInvalid(module)
                    applyModuleIconTint(getModuleIconView(module), installed, enabled, invalid)
                    updateModuleDescription(getModuleDescriptionView(module), installed, enabled, invalid, module.detailRes)
                }
            }
        }
    }

    // ----- 模块状态 / 视图 helper（统一封装三个模块的差异） -----

    private fun isModuleInstalled(module: ModuleType): Boolean = when (module) {
        ModuleType.RPGM -> rpgmModuleInstalled
        ModuleType.RENPY -> renpyModuleInstalled
        ModuleType.GODOT -> godotModuleInstalled
        ModuleType.KIRIKIROID2 -> kirikiroid2ModuleStateCode != "not_installed"
        ModuleType.ONS -> onsModuleStateCode != "not_installed"
    }

    private fun isModuleEnabled(module: ModuleType): Boolean = when (module) {
        ModuleType.RPGM -> rpgmModuleEnabled
        ModuleType.RENPY -> renpyModuleEnabled
        ModuleType.GODOT -> godotModuleEnabled
        ModuleType.KIRIKIROID2 -> kirikiroid2ModuleStateCode == "installed_enabled"
        ModuleType.ONS -> onsModuleStateCode == "installed_enabled"
    }

    private fun isModuleInvalid(module: ModuleType): Boolean = when (module) {
        ModuleType.KIRIKIROID2 -> kirikiroid2ModuleStateCode == "invalid"
        ModuleType.ONS -> onsModuleStateCode == "invalid"
        else -> false
    }

    private fun setModuleEnabled(module: ModuleType, enabled: Boolean) {
        when (module) {
            ModuleType.RPGM -> {
                LauncherModuleBridge.setRpgMakerModuleEnabled(requireContext(), enabled)
                rpgmModuleEnabled = enabled
            }
            ModuleType.RENPY -> {
                LauncherModuleBridge.setRenPyModuleEnabled(requireContext(), enabled)
                renpyModuleEnabled = enabled
            }
            ModuleType.GODOT -> {
                LauncherModuleBridge.setGodotModuleEnabled(requireContext(), enabled)
                godotModuleEnabled = enabled
            }
            ModuleType.KIRIKIROID2 -> {
                LauncherModuleBridge.setKirikiroid2ModuleEnabled(requireContext(), enabled)
                kirikiroid2ModuleStateCode = if (enabled) "installed_enabled" else "installed_disabled"
            }
            ModuleType.ONS -> {
                LauncherModuleBridge.setOnsModuleEnabled(requireContext(), enabled)
                onsModuleStateCode = if (enabled) "installed_enabled" else "installed_disabled"
            }
        }
    }

    private fun getModuleRowView(module: ModuleType): View {
        val currentBinding = requireBinding()
        return when (module) {
            ModuleType.RPGM -> currentBinding.moduleRpgmRow
            ModuleType.RENPY -> currentBinding.moduleRenpyRow
            ModuleType.GODOT -> currentBinding.moduleGodotRow
            ModuleType.KIRIKIROID2 -> currentBinding.moduleKirikiroid2Row
            ModuleType.ONS -> currentBinding.moduleOnsRow
        }
    }

    private fun getModuleIconView(module: ModuleType): ImageView {
        val currentBinding = requireBinding()
        return when (module) {
            ModuleType.RPGM -> currentBinding.moduleRpgmIcon
            ModuleType.RENPY -> currentBinding.moduleRenpyIcon
            ModuleType.GODOT -> currentBinding.moduleGodotIcon
            ModuleType.KIRIKIROID2 -> currentBinding.moduleKirikiroid2Icon
            ModuleType.ONS -> currentBinding.moduleOnsIcon
        }
    }

    private fun getModuleDescriptionView(module: ModuleType): TextView {
        val currentBinding = requireBinding()
        return when (module) {
            ModuleType.RPGM -> currentBinding.moduleRpgmDescription
            ModuleType.RENPY -> currentBinding.moduleRenpyDescription
            ModuleType.GODOT -> currentBinding.moduleGodotDescription
            ModuleType.KIRIKIROID2 -> currentBinding.moduleKirikiroid2Description
            ModuleType.ONS -> currentBinding.moduleOnsDescription
        }
    }

    private fun requireBinding(): ActivityLauncherModuleCompatibilityBinding =
        binding ?: throw IllegalStateException("LauncherModuleCompatibilityFragment binding is null")

    /**
     * 图标着色规则：
     * - 未安装 → danger 红
     * - 已安装 + 已启用 → primary 主题色
     * - 已安装 + 未启用 → textMuted 灰，表示「关闭」状态
     */
    private fun applyModuleIconTint(icon: ImageView, installed: Boolean, enabled: Boolean, invalid: Boolean) {
        val color = if (!installed || invalid) {
            LauncherTheme.danger(requireContext())
        } else if (enabled) {
            LauncherTheme.primary(requireContext())
        } else {
            LauncherTheme.textMuted(requireContext())
        }
        icon.setImageTintList(ColorStateList.valueOf(color))
    }

    /**
     * 左侧状态描述格式：
     * - 未安装：{@code 未安装 - <detail>}（danger 红）
     * - 已安装 · 已启用：{@code 已安装 · 已启用 - <detail>}（primary 主题色）
     * - 已安装 · 未启用：{@code 已安装 · 未启用 - <detail>}（textMuted 灰）
     */
    private fun updateModuleDescription(
        description: TextView,
        installed: Boolean,
        enabled: Boolean,
        invalid: Boolean,
        detailRes: Int,
    ) {
        val detail = getString(detailRes)
        val text: String
        val color: Int
        if (invalid) {
            text = getString(R.string.module_status_invalid, detail)
            color = LauncherTheme.danger(requireContext())
        } else if (!installed) {
            text = getString(R.string.module_status_not_installed, detail)
            color = LauncherTheme.danger(requireContext())
        } else if (enabled) {
            text = getString(R.string.module_status_installed_enabled, detail)
            color = LauncherTheme.primary(requireContext())
        } else {
            text = getString(R.string.module_status_installed_disabled, detail)
            color = LauncherTheme.textMuted(requireContext())
        }
        description.text = text
        description.setTextColor(color)
    }

    // ----- 长按：跳转浏览器下载 -----

    private fun promptDownload(module: ModuleType) {
        if (module == ModuleType.KIRIKIROID2) {
            launchKirikiroid2ImportPicker()
            return
        }
        if (module == ModuleType.ONS) {
            launchOnsImportPicker()
            return
        }
        LauncherDialogRouter.showStandardConfirm(
            requireContext(),
            getString(R.string.module_download_title, module.shortName),
            getString(R.string.module_download_message),
            getString(R.string.theme_go_to_download),
        ) { openInstallPage(module.installUrl) }
    }

    // ----- 行点击 -----

    private fun openModule(module: ModuleType) {
        if (module == ModuleType.KIRIKIROID2) {
            showKirikiroid2Actions()
            return
        }
        if (module == ModuleType.ONS) {
            showOnsActions()
            return
        }
        if (isModuleInstalled(module)) {
            LauncherDialogRouter.showStandardConfirm(
                requireContext(),
                getString(module.nameRes),
                if (isModuleEnabled(module)) {
                    getString(R.string.module_installed_enabled_hint)
                } else {
                    getString(R.string.module_installed_disabled_hint)
                },
                getString(R.string.settings_got_it),
                null,
            )
            return
        }
        LauncherDialogRouter.showStandardConfirm(
            requireContext(),
            getString(R.string.module_install_title, module.shortName),
            getString(R.string.module_install_message),
            getString(R.string.module_go_to_install),
        ) { openInstallPage(module.installUrl) }
    }

    // ----- 图标点击：启停切换 -----

    private fun handleModuleIconClick(module: ModuleType) {
        if (module == ModuleType.KIRIKIROID2) {
            showKirikiroid2Actions()
            return
        }
        if (module == ModuleType.ONS) {
            showOnsActions()
            return
        }
        if (!isModuleInstalled(module)) {
            openModule(module)
            return
        }
        if (isModuleEnabled(module)) {
            LauncherDialogRouter.showStandardConfirm(
                requireContext(),
                getString(R.string.module_disable_title, module.shortName),
                getString(R.string.module_disable_message, module.shortName),
                getString(R.string.module_disable),
            ) {
                setModuleEnabled(module, false)
                applyModuleIconTint(
                    getModuleIconView(module),
                    isModuleInstalled(module),
                    isModuleEnabled(module),
                    isModuleInvalid(module),
                )
                updateModuleDescription(
                    getModuleDescriptionView(module),
                    isModuleInstalled(module),
                    isModuleEnabled(module),
                    isModuleInvalid(module),
                    module.detailRes,
                )
            }
        } else {
            LauncherDialogRouter.showStandardConfirm(
                requireContext(),
                getString(R.string.module_enable_title, module.shortName),
                getString(R.string.module_enable_message, module.shortName),
                getString(R.string.module_enable),
            ) {
                setModuleEnabled(module, true)
                applyModuleIconTint(
                    getModuleIconView(module),
                    isModuleInstalled(module),
                    isModuleEnabled(module),
                    isModuleInvalid(module),
                )
                updateModuleDescription(
                    getModuleDescriptionView(module),
                    isModuleInstalled(module),
                    isModuleEnabled(module),
                    isModuleInvalid(module),
                    module.detailRes,
                )
            }
        }
    }

    // ----- Kirikiroid2 native zip 插件操作 -----

    private fun showKirikiroid2Actions() {
        val labels = mutableListOf<CharSequence>()
        val actions = mutableListOf<() -> Unit>()
        val installed = isModuleInstalled(ModuleType.KIRIKIROID2)
        val invalid = isModuleInvalid(ModuleType.KIRIKIROID2)
        if (!installed || invalid) {
            labels += getString(R.string.module_native_import)
            actions += { launchKirikiroid2ImportPicker() }
        } else if (isModuleEnabled(ModuleType.KIRIKIROID2)) {
            labels += getString(R.string.module_disable)
            actions += {
                setModuleEnabled(ModuleType.KIRIKIROID2, false)
                refreshInstalledModules()
            }
        } else {
            labels += getString(R.string.module_enable)
            actions += {
                setModuleEnabled(ModuleType.KIRIKIROID2, true)
                refreshInstalledModules()
            }
        }
        if (installed) {
            labels += getString(R.string.module_native_delete)
            actions += { confirmDeleteKirikiroid2Plugin() }
        }
        if (installed && !invalid) {
            labels += getString(R.string.module_native_import)
            actions += { launchKirikiroid2ImportPicker() }
        }
        val dangerIndex = labels.indexOf(getString(R.string.module_native_delete))
        LauncherDialogRouter.showStandardActionChoices(
            requireContext(),
            getString(R.string.module_kirikiroid2_name),
            labels.toTypedArray(),
            dangerIndex,
        ) { index ->
            actions.getOrNull(index)?.invoke()
        }
    }

    private fun launchKirikiroid2ImportPicker() {
        kirikiroid2ImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    private fun importKirikiroid2Plugin(uri: Uri) {
        val context = requireContext()
        val loading = LauncherDialogRouter.showLoading(
            context,
            getString(R.string.module_native_importing_title),
            getString(R.string.module_native_importing_message),
        )
        val appContext = context.applicationContext
        AppExecutors.runOnIo {
            val result = LauncherModuleBridge.importKirikiroid2Module(appContext, uri)
            activity?.runOnUiThread {
                loading.dismiss()
                if (!isAdded || binding == null) return@runOnUiThread
                val title = if (result.success) {
                    getString(R.string.module_native_import_success_title)
                } else {
                    getString(R.string.module_native_import_failed_title)
                }
                LauncherDialogRouter.showInfo(
                    requireContext(),
                    title,
                    nativeImportMessage(result.code, result.zipSha256),
                )
                refreshInstalledModules()
            }
        }
    }

    private fun nativeImportMessage(code: String, zipSha256: String?): String = when (code) {
        "ok" -> getString(R.string.module_native_import_success_message)
        "expected_sha256_missing" -> getString(R.string.module_native_import_expected_sha_missing)
        "sha256_mismatch" -> getString(R.string.module_native_import_sha_mismatch, zipSha256 ?: "")
        "invalid_structure" -> getString(R.string.module_native_import_invalid_structure)
        "zip_rejected" -> getString(R.string.module_native_import_zip_rejected)
        "read_failed" -> getString(R.string.module_native_import_read_failed)
        else -> getString(R.string.module_native_import_failed_message)
    }

    private fun confirmDeleteKirikiroid2Plugin() {
        LauncherDialogRouter.showDangerConfirm(
            requireContext(),
            getString(R.string.module_native_delete_title),
            getString(R.string.module_native_delete_message),
            getString(R.string.module_native_delete),
        ) {
            val context = requireContext()
            val loading = LauncherDialogRouter.showLoading(
                context,
                getString(R.string.module_native_deleting_title),
                getString(R.string.module_native_deleting_message),
            )
            val appContext = context.applicationContext
            AppExecutors.runOnIo {
                val deleted = LauncherModuleBridge.deleteKirikiroid2Module(appContext)
                activity?.runOnUiThread {
                    loading.dismiss()
                    if (!isAdded || binding == null) return@runOnUiThread
                    LauncherDialogRouter.showInfo(
                        requireContext(),
                        getString(
                            if (deleted) R.string.module_native_delete_success_title
                            else R.string.module_native_delete_failed_title,
                        ),
                        getString(
                            if (deleted) R.string.module_native_delete_success_message
                            else R.string.module_native_delete_failed_message,
                        ),
                    )
                    refreshInstalledModules()
                }
            }
        }
    }

    // ----- ONS native zip 插件操作 -----

    private fun showOnsActions() {
        val labels = mutableListOf<CharSequence>()
        val actions = mutableListOf<() -> Unit>()
        val installed = isModuleInstalled(ModuleType.ONS)
        val invalid = isModuleInvalid(ModuleType.ONS)
        if (!installed || invalid) {
            labels += getString(R.string.module_native_import)
            actions += { launchOnsImportPicker() }
        } else if (isModuleEnabled(ModuleType.ONS)) {
            labels += getString(R.string.module_disable)
            actions += {
                setModuleEnabled(ModuleType.ONS, false)
                refreshInstalledModules()
            }
        } else {
            labels += getString(R.string.module_enable)
            actions += {
                setModuleEnabled(ModuleType.ONS, true)
                refreshInstalledModules()
            }
        }
        if (installed) {
            labels += getString(R.string.module_native_delete)
            actions += { confirmDeleteOnsPlugin() }
        }
        if (installed && !invalid) {
            labels += getString(R.string.module_native_import)
            actions += { launchOnsImportPicker() }
        }
        val dangerIndex = labels.indexOf(getString(R.string.module_native_delete))
        LauncherDialogRouter.showStandardActionChoices(
            requireContext(),
            getString(R.string.module_ons_name),
            labels.toTypedArray(),
            dangerIndex,
        ) { index ->
            actions.getOrNull(index)?.invoke()
        }
    }

    private fun launchOnsImportPicker() {
        onsImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    private fun importOnsPlugin(uri: Uri) {
        val context = requireContext()
        val loading = LauncherDialogRouter.showLoading(
            context,
            getString(R.string.module_native_importing_title),
            getString(R.string.module_native_importing_message),
        )
        val appContext = context.applicationContext
        AppExecutors.runOnIo {
            val result = LauncherModuleBridge.importOnsModule(appContext, uri)
            activity?.runOnUiThread {
                loading.dismiss()
                if (!isAdded || binding == null) return@runOnUiThread
                val title = if (result.success) {
                    getString(R.string.module_native_import_success_title)
                } else {
                    getString(R.string.module_native_import_failed_title)
                }
                LauncherDialogRouter.showInfo(
                    requireContext(),
                    title,
                    nativeImportMessage(result.code, result.zipSha256),
                )
                refreshInstalledModules()
            }
        }
    }

    private fun confirmDeleteOnsPlugin() {
        LauncherDialogRouter.showDangerConfirm(
            requireContext(),
            getString(R.string.module_ons_delete_title),
            getString(R.string.module_ons_delete_message),
            getString(R.string.module_native_delete),
        ) {
            val context = requireContext()
            val loading = LauncherDialogRouter.showLoading(
                context,
                getString(R.string.module_native_deleting_title),
                getString(R.string.module_native_deleting_message),
            )
            val appContext = context.applicationContext
            AppExecutors.runOnIo {
                val deleted = LauncherModuleBridge.deleteOnsModule(appContext)
                activity?.runOnUiThread {
                    loading.dismiss()
                    if (!isAdded || binding == null) return@runOnUiThread
                    LauncherDialogRouter.showInfo(
                        requireContext(),
                        getString(
                            if (deleted) R.string.module_ons_delete_success_title
                            else R.string.module_ons_delete_failed_title,
                        ),
                        getString(
                            if (deleted) R.string.module_ons_delete_success_message
                            else R.string.module_ons_delete_failed_message,
                        ),
                    )
                    refreshInstalledModules()
                }
            }
        }
    }

    // ----- 安装页跳转 -----

    private fun openInstallPage(installUrl: String) {
        val opened: Boolean = try {
            // 统一走共享 LauncherUrlOpener：scheme 白名单校验 + ActivityNotFoundException 捕获。
            LauncherUrlOpener.open(requireContext(), installUrl)
        } catch (error: SecurityException) {
            // 受限设备/异常浏览器组件下 startActivity 可能抛 SecurityException，按打开失败统一兜底。
            false
        }
        if (!opened) {
            // 打开失败弹窗提示同前（成功打开同前）。
            LauncherDialogRouter.showInfo(
                requireContext(),
                getString(R.string.module_cannot_open_browser),
                getString(R.string.module_try_again_later),
            )
        }
    }

    /** 各模块的静态差异：名称/详情资源、安装页 URL、用于弹窗标题的简称。 */
    private enum class ModuleType(
        val nameRes: Int,
        val detailRes: Int,
        val installUrl: String,
        val shortName: String,
    ) {
        RPGM(R.string.module_rpgm_name, R.string.module_rpgm_detail, RPGM_INSTALL_URL, "RPGM"),
        RENPY(R.string.module_renpy_name, R.string.module_renpy_detail, RENPY_INSTALL_URL, "RenPy"),
        GODOT(R.string.module_godot_name, R.string.module_godot_detail, GODOT_INSTALL_URL, "Godot"),
        KIRIKIROID2(R.string.module_kirikiroid2_name, R.string.module_kirikiroid2_detail, "", "Kirikiroid2"),
        ONS(R.string.module_ons_name, R.string.module_ons_detail, "", "ONS"),
    }

    companion object {
        private const val RPGM_INSTALL_URL =
            "https://github.com/Weiss-UltimateSavior/RinneMobile/releases/download/test/RPGMPlugin-1.22.00-patreon-release.apk"
        private const val RENPY_INSTALL_URL =
            "https://github.com/Weiss-UltimateSavior/RinneMobile/releases/download/test/RenPyPlugin-8.5.0-1.01.00.apk"
        private const val GODOT_INSTALL_URL =
            "https://github.com/Weiss-UltimateSavior/RinneMobile/releases/download/test/Godot.4.3-Plugin-1.00.60.apk"
    }
}
