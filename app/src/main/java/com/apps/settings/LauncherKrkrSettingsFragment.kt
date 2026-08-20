package com.apps.settings

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.apps.HDModel.HdEmbeddedActivityOwner
import com.apps.HDModel.HdModeActivity
import com.apps.HDModel.LauncherDialogRouter
import com.apps.common.LauncherInsetsHelper
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherKrkrSettingsBinding
import com.core.launcher.ScriptEngineLaunchers
import com.core.launcherbridge.LauncherGameLaunchBridge
import com.core.launcherbridge.LauncherArtemisGameSettingsBridge
import com.core.launcherbridge.LauncherKrkrBridge
import com.core.launcherbridge.LauncherKrkrGameSettingsBridge
import com.core.launcherbridge.LauncherOnsGameSettingsBridge
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.EngineType
import com.core.ons.OnsSettings
import com.core.util.DevLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 引擎设置页（重构计划 9.9 阶段 110 自 LauncherKrkrSettingsActivity 抽取）。
 *
 * 竖屏由 [LauncherKrkrSettingsActivity] 薄宿主承载，HD 由
 * [com.apps.HDModel.HdManageFragment] 作为子 Fragment 承载；
 * per-game 模式经 [EXTRA_GAME_ID] 参数进入；全局模式（gameId=0）统一配置各引擎
 * （含 Artemis）应用级默认。
 */
class LauncherKrkrSettingsFragment : Fragment() {
    private var binding: ActivityLauncherKrkrSettingsBinding? = null
    private var selectedEngineVersionIndex = 0
    private var selectedArtemisEngineVersionIndex = 0
    private var selectedArtemisAutoPatchIndex = 0
    private var selectedOnsEncodingIndex = 0
    /** 内核开关确认弹窗回调中程序化置位开关时抑制重复弹窗。 */
    private var kernelSwitchConfirming = false
    private var gameId = 0L
    /** per-game 模式下目标游戏的引擎类型；全局模式为 null。 */
    private var perGameEngine: EngineType? = null
    /** 进入页面时的强制字体生效值（全局叠加覆盖）；save() 据此判断用户是否实际拨动开关。 */
    private var initialForceDefaultFont = false
    /** 默认字体文件选择器：取到 content/file Uri 后转真实路径并回填（空结果不处理）。 */
    private val fontPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) onFontPicked(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherKrkrSettingsBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // HD 容器内嵌（管理页明细容器或库直压主容器）：清除布局自带不透明背景，露出宿主圆角白卡。
        if (activity is HdModeActivity) view.background = null
        if (activity !is HdModeActivity) LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        gameId = arguments?.getLong(EXTRA_GAME_ID, 0L) ?: 0L
        if (isPerGameMode()) {
            perGameEngine = LauncherRepositoryBridge.findGameById(requireContext(), gameId)?.engine
        }
        LauncherInsetsHelper.applyTopInset(currentBinding.root, currentBinding.krkrScroll)
        bindActions()
        applyThemeTone()
        if (isPerGameMode()) applyPerGameLayout()
        loadConfig(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_ENGINE_VERSION_INDEX, selectedEngineVersionIndex)
        outState.putInt(STATE_ARTEMIS_ENGINE_VERSION_INDEX, selectedArtemisEngineVersionIndex)
        outState.putInt(STATE_ARTEMIS_AUTO_PATCH_INDEX, selectedArtemisAutoPatchIndex)
        outState.putInt(STATE_ONS_ENCODING_INDEX, selectedOnsEncodingIndex)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun isPerGameMode(): Boolean = gameId > 0L

    /** 全局模式（非 per-game）：统一配置各引擎应用级全局默认。 */
    private fun isGlobalMode(): Boolean = gameId <= 0L

    /**
     * Per-game 模式下按目标游戏引擎显示对应区段：KRKR 显示引擎版本 + 独立存档，
     * ONS 显示 ONS 配置，ARTEMIS 显示引擎版本 + 画面反转；其余区段一律隐藏。
     */
    private fun applyPerGameLayout() {
        val currentBinding = binding ?: return
        val isKrkr = perGameEngine == EngineType.KIRIKIRI
        val isOns = perGameEngine == EngineType.ONS
        val isArtemis = perGameEngine == EngineType.ARTEMIS
        currentBinding.krVersionSection.visibility = if (isKrkr) View.VISIBLE else View.GONE
        currentBinding.krKernelSection.visibility = if (isKrkr) View.VISIBLE else View.GONE
        currentBinding.krScopedSection.visibility = if (isKrkr) View.VISIBLE else View.GONE
        currentBinding.artemisSection.visibility = if (isArtemis) View.VISIBLE else View.GONE
        currentBinding.tyranoScopedSection.visibility = View.GONE
        currentBinding.tyranoExternalNetworkSection.visibility = View.GONE
        currentBinding.onsSection.visibility = if (isOns) View.VISIBLE else View.GONE
        currentBinding.btnNativeKrkr.setText(R.string.settings_restore_global_defaults)
        currentBinding.btnNativeKrkr.setOnClickListener { clearPerGameSettings() }

        val game = LauncherRepositoryBridge.findGameById(requireContext(), gameId)
        val rawTitle = game?.title
        val title = if (rawTitle != null && rawTitle.trim().isNotEmpty()) {
            rawTitle.trim()
        } else {
            getString(R.string.settings_engine_title)
        }
        currentBinding.krkrSectionTitle.text = title
        currentBinding.krkrSectionDescription.setText(R.string.settings_ons_game_override_summary)
    }

    private fun clearPerGameSettings() {
        when (perGameEngine) {
            EngineType.KIRIKIRI -> LauncherKrkrGameSettingsBridge.clearOverride(requireContext(), gameId)
            EngineType.ONS -> LauncherOnsGameSettingsBridge.clearOverride(requireContext(), gameId)
            EngineType.ARTEMIS -> LauncherArtemisGameSettingsBridge.clearOverride(requireContext(), gameId)
            else -> Unit
        }
        Toast.makeText(requireContext(), R.string.settings_ons_global_restored, Toast.LENGTH_SHORT).show()
        requestClose()
    }

    private fun bindActions() {
        val currentBinding = binding ?: return
        currentBinding.btnSave.setOnClickListener { save() }
        currentBinding.btnCancel.setOnClickListener { requestClose() }
        currentBinding.btnNativeKrkr.setOnClickListener { enterNativeKrkr() }
        currentBinding.engineVersionText.setOnClickListener { showEngineVersionPicker() }
        currentBinding.artemisEngineVersionText.setOnClickListener { showArtemisEngineVersionPicker() }
        currentBinding.artemisAutoPatchText.setOnClickListener { showArtemisAutoPatchPicker() }
        currentBinding.onsEncodingText.setOnClickListener { showOnsEncodingPicker() }
        currentBinding.krDefaultFontText.setOnClickListener { showDefaultFontChoices() }
        // krkrsdl3 内核开关：开启需确认（全新引擎内核，稳定性不可预测）。
        currentBinding.krEngineKernelSwitch.setOnCheckedChangeListener { _, checked ->
            // 可见性重估必须先于 confirming guard：确认回调程序化置位 isChecked=true 时
            // 会被 guard 拦截提前返回，若重估放在 guard 后，krkrsdl3 生效后字体区段
            // 会残留显示（取消路径反而正常）。
            updateKrFontSectionVisibility()
            if (kernelSwitchConfirming) return@setOnCheckedChangeListener
            if (!checked || !isAdded) return@setOnCheckedChangeListener
            currentBinding.krEngineKernelSwitch.isChecked = false
            LauncherDialogRouter.showStandardConfirm(
                requireContext(),
                getString(R.string.settings_kr_kernel_switch_title),
                getString(R.string.settings_kr_kernel_switch_message),
                getString(R.string.settings_kr_kernel_switch_confirm),
            ) {
                kernelSwitchConfirming = true
                currentBinding.krEngineKernelSwitch.isChecked = true
                kernelSwitchConfirming = false
            }
        }
    }

    private fun applyThemeTone() {
        val currentBinding = binding ?: return
        LauncherTheme.styleMaterialSwitch(currentBinding.krScopedSwitch)
        LauncherTheme.styleMaterialSwitch(currentBinding.krEngineKernelSwitch)
        LauncherTheme.styleMaterialSwitch(currentBinding.krForceDefaultFontSwitch)
        LauncherTheme.styleMaterialSwitch(currentBinding.artemisRotateSwitch)
        LauncherTheme.styleMaterialSwitch(currentBinding.onsScopedSwitch)
        LauncherTheme.styleMaterialSwitch(currentBinding.onsStretchSwitch)
        LauncherTheme.styleMaterialSwitch(currentBinding.onsCutoutSwitch)
        LauncherTheme.styleMaterialSwitch(currentBinding.onsDisableVideoSwitch)
        LauncherTheme.styleMaterialSwitch(currentBinding.onsSharpnessSwitch)
        LauncherTheme.styleMaterialSwitch(currentBinding.tyranoScopedSwitch)
        LauncherTheme.styleMaterialSwitch(currentBinding.tyranoExternalNetworkSwitch)
        LauncherTheme.formInputs(currentBinding.onsSharpnessValueInput)
        LauncherTheme.applyPrimaryTone(requireView())
        LauncherTheme.longActionButton(currentBinding.btnNativeKrkr)
        LauncherTheme.longActionButton(currentBinding.btnSave)
        LauncherTheme.longActionButton(currentBinding.btnCancel)
    }

    private fun loadConfig(savedInstanceState: Bundle?) {
        val currentBinding = binding ?: return
        val isKrkrGame = isPerGameMode() && perGameEngine == EngineType.KIRIKIRI
        val version = if (isKrkrGame) {
            LauncherKrkrGameSettingsBridge.load(requireContext(), gameId).engineVersion
        } else {
            LauncherKrkrBridge.getEngineVersion(requireContext())
        }
        var selection = 0
        if (LauncherKrkrBridge.ENGINE_VERSION_139 == version) selection = 1
        else if (LauncherKrkrBridge.ENGINE_VERSION_134 == version) selection = 2
        else if (LauncherKrkrBridge.ENGINE_VERSION_126 == version) selection = 3
        setEngineVersionSelection(
            if (savedInstanceState != null && savedInstanceState.containsKey(STATE_ENGINE_VERSION_INDEX)) {
                savedInstanceState.getInt(STATE_ENGINE_VERSION_INDEX, 0)
            } else {
                selection
            },
        )
        currentBinding.krScopedSwitch.isChecked = if (isKrkrGame) {
            LauncherKrkrGameSettingsBridge.load(requireContext(), gameId).scopedSaveDir
        } else {
            LauncherKrkrBridge.isKrScopedSaveDir(requireContext())
        }
        // KRKR 引擎内核：开关开启 = krkrsdl3，关闭 = 自动（吉里吉里2）。
        // 初始状态回填不触发确认弹窗（仅在用户点击开关时询问）。
        val kernel = if (isKrkrGame) {
            LauncherKrkrGameSettingsBridge.load(requireContext(), gameId).engineKernel
        } else {
            LauncherKrkrBridge.getEngineKernel(requireContext())
        }
        kernelSwitchConfirming = true
        currentBinding.krEngineKernelSwitch.isChecked =
            LauncherKrkrBridge.KERNEL_KRKRSDL3 == kernel
        kernelSwitchConfirming = false
        updateKrFontSectionVisibility()
        loadKrkrFontConfig()
        loadArtemisConfig(savedInstanceState)
        val onsSettings = if (isPerGameMode()) {
            LauncherOnsGameSettingsBridge.load(requireContext(), gameId)
        } else {
            OnsSettings.load(requireContext())
        }
        currentBinding.onsScopedSwitch.isChecked = onsSettings.scopedSaveDir
        currentBinding.onsStretchSwitch.isChecked = onsSettings.stretchFull
        currentBinding.onsCutoutSwitch.isChecked = onsSettings.ignoreCutout
        currentBinding.onsDisableVideoSwitch.isChecked = onsSettings.disableVideo
        currentBinding.onsSharpnessSwitch.isChecked = onsSettings.sharpness
        currentBinding.onsSharpnessValueInput.setText(onsSettings.sharpnessValue)
        var onsEncodingIndex = onsEncodingIndex(onsSettings.encoding)
        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_ONS_ENCODING_INDEX)) {
            onsEncodingIndex = savedInstanceState.getInt(STATE_ONS_ENCODING_INDEX, onsEncodingIndex)
        }
        setOnsEncodingSelection(onsEncodingIndex)
        currentBinding.tyranoScopedSwitch.isChecked = LauncherKrkrBridge.isTyranoScopedSaveDir(requireContext())
        currentBinding.tyranoExternalNetworkSwitch.isChecked =
            LauncherKrkrBridge.isTyranoExternalNetworkEnabled(requireContext())
    }

    private fun save() {
        val currentBinding = binding ?: return
        val pos = selectedEngineVersionIndex
        var version = LauncherKrkrBridge.ENGINE_VERSION_AUTO
        if (pos == 1) version = LauncherKrkrBridge.ENGINE_VERSION_139
        else if (pos == 2) version = LauncherKrkrBridge.ENGINE_VERSION_134
        else if (pos == 3) version = LauncherKrkrBridge.ENGINE_VERSION_126
        val kernel = if (currentBinding.krEngineKernelSwitch.isChecked) {
            LauncherKrkrBridge.KERNEL_KRKRSDL3
        } else {
            LauncherKrkrBridge.KERNEL_AUTO
        }

        if (isPerGameMode()) {
            // Per-game 模式：仅写入该游戏的引擎覆盖；其他引擎与全局项保持原值。
            when (perGameEngine) {
                EngineType.KIRIKIRI -> {
                    val perGame = LauncherKrkrGameSettingsBridge.load(requireContext(), gameId)
                    perGame.engineVersion = version
                    perGame.engineKernel = kernel
                    perGame.scopedSaveDir = currentBinding.krScopedSwitch.isChecked
                    // 默认字体在选取/恢复时已即时写回，此处不覆盖（load 已带回最新覆盖状态）。
                    // 强制开关仅在用户相对进入页面时的生效值实际拨动过才落覆盖（null=跟随全局），
                    // 未拨动时保留 load() 带回的原覆盖状态，避免把全局回退值固化进游戏 JSON。
                    val currentForce = currentBinding.krForceDefaultFontSwitch.isChecked
                    if (currentForce != initialForceDefaultFont) {
                        perGame.forceDefaultFont = currentForce
                    }
                    LauncherKrkrGameSettingsBridge.save(requireContext(), gameId, perGame)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.settings_engine_saved, engineVersionLabels()[selectedEngineVersionIndex]),
                        Toast.LENGTH_SHORT,
                    ).show()
                }

                EngineType.ONS -> {
                    val perGame = LauncherOnsGameSettingsBridge.load(requireContext(), gameId)
                    perGame.scopedSaveDir = currentBinding.onsScopedSwitch.isChecked
                    perGame.stretchFull = currentBinding.onsStretchSwitch.isChecked
                    perGame.ignoreCutout = currentBinding.onsCutoutSwitch.isChecked
                    perGame.disableVideo = currentBinding.onsDisableVideoSwitch.isChecked
                    perGame.sharpness = currentBinding.onsSharpnessSwitch.isChecked
                    perGame.sharpnessValue = currentBinding.onsSharpnessValueInput.text?.toString()?.trim().orEmpty()
                    perGame.encoding = ONS_ENCODING_LABELS[selectedOnsEncodingIndex]
                    LauncherOnsGameSettingsBridge.save(requireContext(), gameId, perGame)
                    Toast.makeText(requireContext(), R.string.settings_ons_game_saved, Toast.LENGTH_SHORT).show()
                }

                EngineType.ARTEMIS -> {
                    val perGame = LauncherArtemisGameSettingsBridge.load(requireContext(), gameId)
                    perGame.engineVersion = artemisVersionForIndex(selectedArtemisEngineVersionIndex)
                    perGame.rotateScreen = currentBinding.artemisRotateSwitch.isChecked
                    perGame.autoPatch = artemisAutoPatchForIndex(selectedArtemisAutoPatchIndex)
                    LauncherArtemisGameSettingsBridge.save(requireContext(), gameId, perGame)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.settings_engine_saved, artemisEngineVersionLabels()[selectedArtemisEngineVersionIndex]),
                        Toast.LENGTH_SHORT,
                    ).show()
                }

                else -> Unit
            }
            requestClose()
            return
        }

        LauncherKrkrBridge.setEngineVersion(requireContext(), version)
        LauncherKrkrBridge.setEngineKernel(requireContext(), kernel)
        LauncherKrkrBridge.setKrScopedSaveDir(requireContext(), currentBinding.krScopedSwitch.isChecked)
        // 默认字体在选取/恢复时已即时写回，此处不覆盖。
        LauncherKrkrBridge.setForceDefaultFont(
            requireContext(),
            currentBinding.krForceDefaultFontSwitch.isChecked,
        )
        val onsSettings = OnsSettings.load(requireContext())
        onsSettings.scopedSaveDir = currentBinding.onsScopedSwitch.isChecked
        onsSettings.stretchFull = currentBinding.onsStretchSwitch.isChecked
        onsSettings.ignoreCutout = currentBinding.onsCutoutSwitch.isChecked
        onsSettings.disableVideo = currentBinding.onsDisableVideoSwitch.isChecked
        onsSettings.sharpness = currentBinding.onsSharpnessSwitch.isChecked
        onsSettings.sharpnessValue = currentBinding.onsSharpnessValueInput.text?.toString()?.trim().orEmpty()
        onsSettings.encoding = ONS_ENCODING_LABELS[selectedOnsEncodingIndex]
        onsSettings.save(requireContext())
        LauncherKrkrBridge.setTyranoScopedSaveDir(requireContext(), currentBinding.tyranoScopedSwitch.isChecked)
        LauncherKrkrBridge.setTyranoExternalNetworkEnabled(
            requireContext(),
            currentBinding.tyranoExternalNetworkSwitch.isChecked,
        )
        // Artemis 全局默认并入引擎设置页。
        LauncherArtemisGameSettingsBridge.setDefaultEngineVersion(
            requireContext(),
            artemisVersionForIndex(selectedArtemisEngineVersionIndex),
        )
        LauncherArtemisGameSettingsBridge.setDefaultRotateScreen(
            requireContext(),
            currentBinding.artemisRotateSwitch.isChecked,
        )
        LauncherArtemisGameSettingsBridge.setDefaultAutoPatch(
            requireContext(),
            artemisAutoPatchForIndex(selectedArtemisAutoPatchIndex),
        )

        Toast.makeText(
            requireContext(),
            getString(R.string.settings_engine_saved, engineVersionLabels()[selectedEngineVersionIndex]),
            Toast.LENGTH_SHORT,
        ).show()
        requestClose()
    }

    private fun showEngineVersionPicker() {
        LauncherDialogRouter.showSingleChoice(
            requireContext(),
            getString(R.string.settings_choose_kr_engine_version),
            engineVersionLabels(),
            selectedEngineVersionIndex,
            ::setEngineVersionSelection,
        )
    }

    private fun setEngineVersionSelection(index: Int) {
        val currentBinding = binding ?: return
        val labels = engineVersionLabels()
        selectedEngineVersionIndex = if (index in labels.indices) index else 0
        currentBinding.engineVersionText.setText(labels[selectedEngineVersionIndex])
        updateKrForceFontRowVisibility()
    }

    private fun loadKrkrFontConfig() {
        val currentBinding = binding ?: return
        val isKrkrGame = isPerGameMode() && perGameEngine == EngineType.KIRIKIRI
        val setting = if (isKrkrGame) {
            LauncherKrkrGameSettingsBridge.load(requireContext(), gameId)
        } else {
            null
        }
        // setting?.defaultFont 为 null 即跟随全局，取全局值；非空为游戏覆盖值。
        val defaultFont = setting?.defaultFont ?: LauncherKrkrBridge.getDefaultFont(requireContext())
        val forceDefault = setting?.forceDefaultFont
            ?: LauncherKrkrBridge.isForceDefaultFont(requireContext())
        initialForceDefaultFont = forceDefault
        renderDefaultFontField(defaultFont)
        currentBinding.krForceDefaultFontSwitch.isChecked = forceDefault
    }

    /**
     * 字体区段可见性：krkrsdl3 内核走 buildKrkrsdl3Intent 路由不注入字体参数，
     * 设置无效，按当前内核开关状态隐藏避免误导（全局与 per-game 模式一致）。
     */
    private fun updateKrFontSectionVisibility() {
        val currentBinding = binding ?: return
        val isKrkr = !isPerGameMode() || perGameEngine == EngineType.KIRIKIRI
        val kernelSdl3 = currentBinding.krEngineKernelSwitch.isChecked
        currentBinding.krFontSection.visibility =
            if (isKrkr && !kernelSdl3) View.VISIBLE else View.GONE
    }

    /** 1.2.6 引擎无 force_default_font 消费逻辑（libgame126.so 无此键），隐藏开关避免误导。 */
    private fun updateKrForceFontRowVisibility() {
        val currentBinding = binding ?: return
        val isKrkr = !isPerGameMode() || perGameEngine == EngineType.KIRIKIRI
        currentBinding.krForceFontRow.visibility =
            if (isKrkr && selectedEngineVersionIndex == INDEX_ENGINE_VERSION_126) {
                View.GONE
            } else {
                View.VISIBLE
            }
    }

    /** 字体字段展示：已选字体显示其文件名，未选（内置）显示占位提示。 */
    private fun renderDefaultFontField(fontPath: String?) {
        val currentBinding = binding ?: return
        val path = fontPath?.trim().orEmpty()
        if (path.isEmpty()) {
            currentBinding.krDefaultFontText.text = null
            return
        }
        currentBinding.krDefaultFontText.text = path
    }

    /** 点击默认字体字段：恢复（per-game=跟随全局，全局=使用内置）或经文件选择器挑选字体。 */
    private fun showDefaultFontChoices() {
        // per-game 的"恢复"语义是清除字体区段全部覆盖（字体路径 + 强制开关）、
        // 回退全局值（全局设了字体仍会用全局字体），与全局模式的"恢复内置字体"
        // 不同，文案需区分以免误导。
        val restoreLabel = if (isPerGameMode() && perGameEngine == EngineType.KIRIKIRI) {
            getString(R.string.settings_kr_default_font_restore_follow_global)
        } else {
            getString(R.string.settings_kr_default_font_restore)
        }
        LauncherDialogRouter.showStandardActionChoices(
            requireContext(),
            getString(R.string.settings_kr_default_font),
            arrayOf(
                restoreLabel,
                getString(R.string.settings_kr_default_font_choose),
            ),
        ) { index ->
            when (index) {
                0 -> restoreDefaultFont()
                1 -> pickDefaultFontFile()
            }
        }
    }

    private fun restoreDefaultFont() {
        if (isPerGameMode() && perGameEngine == EngineType.KIRIKIRI) {
            // per-game：清除字体区段两键覆盖（null=跟随全局）。这是强制开关覆盖
            // 唯一的手动退出通道（否则只能整页清空 per-game 设置）。
            val perGame = LauncherKrkrGameSettingsBridge.load(requireContext(), gameId)
            perGame.defaultFont = null
            perGame.forceDefaultFont = null
            LauncherKrkrGameSettingsBridge.save(requireContext(), gameId, perGame)
            // 回显跟随全局后的开关状态并重置拨动基线，避免 save() 误判为用户拨动。
            val globalForce = LauncherKrkrBridge.isForceDefaultFont(requireContext())
            binding?.krForceDefaultFontSwitch?.isChecked = globalForce
            initialForceDefaultFont = globalForce
        } else {
            LauncherKrkrBridge.setDefaultFont(requireContext(), "")
        }
        renderDefaultFontField("")
    }

    /**
     * 启动系统文件选择器挑选字体。不按 font MIME 过滤：多数文件管理器把 ttf/otf
     * 上报为 octet-stream，font 形态过滤后列表为空；改用通配类型，由选取后的
     * 扩展名校验兜底。
     */
    private fun pickDefaultFontFile() {
        fontPicker.launch("*/*")
    }

    private fun onFontPicked(uri: Uri) {
        // 快速路径：已映射为真实文件路径的 URI 只做轻量 stat（isFile/canRead 不触发流式 IO，
        // 允许保留在主线程），命中即回填。
        val direct = ScriptEngineLaunchers.uriToFilePath(uri.toString())
        if (!direct.isNullOrEmpty()) {
            val file = File(direct)
            if (file.isFile && file.canRead()) {
                if (!LauncherKrkrBridge.isFontFileName(direct)) {
                    Toast.makeText(requireContext(), R.string.settings_kr_font_invalid_file, Toast.LENGTH_SHORT).show()
                } else {
                    applyFontPath(direct)
                }
                return
            }
        }
        // 慢路径：MediaStore/Downloads 形态 content URI 无法解析真实路径，需经 provider 流式
        // 拷贝进私有目录。查询与写盘下沉到 Dispatchers.IO（§8 UI 层不持有流），回主线程
        // 先做生命周期守卫。
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val displayName = withContext(Dispatchers.IO) {
                LauncherKrkrBridge.resolveFontFileName(appContext, uri)
            }
            if (displayName == null) {
                Toast.makeText(requireContext(), R.string.settings_kr_font_pick_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 先按扩展名预检再拷贝，避免把 zip/视频等非字体大文件完整拷入后再丢弃。
            if (!LauncherKrkrBridge.isFontFileName(displayName)) {
                Toast.makeText(requireContext(), R.string.settings_kr_font_invalid_file, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val imported = withContext(Dispatchers.IO) {
                LauncherKrkrBridge.importFontFile(appContext, uri, displayName)
            }
            if (!isAdded || binding == null) return@launch
            if (imported == null) {
                Toast.makeText(requireContext(), R.string.settings_kr_font_pick_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            applyFontPath(imported)
        }
    }

    /** 将已解析成功的字体路径写入全局或 per-game 偏好并回填字段。 */
    private fun applyFontPath(path: String) {
        if (isPerGameMode() && perGameEngine == EngineType.KIRIKIRI) {
            val perGame = LauncherKrkrGameSettingsBridge.load(requireContext(), gameId)
            perGame.defaultFont = path
            LauncherKrkrGameSettingsBridge.save(requireContext(), gameId, perGame)
        } else {
            LauncherKrkrBridge.setDefaultFont(requireContext(), path)
        }
        renderDefaultFontField(path)
    }

    /** 加载 Artemis 区段配置（per-game 覆盖或应用级全局默认）。 */
    private fun loadArtemisConfig(savedInstanceState: Bundle?) {
        val currentBinding = binding ?: return
        // 仅 per-game Artemis 或全局模式需加载 Artemis 区段；per-game KRKR/ONS 时该区段已隐藏。
        val isPerGameArtemis = isPerGameMode() && perGameEngine == EngineType.ARTEMIS
        if (!isGlobalMode() && !isPerGameArtemis) return
        val settings = if (isPerGameArtemis) {
            LauncherArtemisGameSettingsBridge.load(requireContext(), gameId)
        } else {
            null
        }
        val version = settings?.engineVersion ?: LauncherArtemisGameSettingsBridge.getDefaultEngineVersion(requireContext())
        val rotate = settings?.rotateScreen ?: LauncherArtemisGameSettingsBridge.getDefaultRotateScreen(requireContext())
        val autoPatch = settings?.autoPatch ?: LauncherArtemisGameSettingsBridge.getDefaultAutoPatch(requireContext())
        var sel = artemisVersionIndex(version)
        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_ARTEMIS_ENGINE_VERSION_INDEX)) {
            sel = savedInstanceState.getInt(STATE_ARTEMIS_ENGINE_VERSION_INDEX, sel)
        }
        setArtemisEngineVersionSelection(sel)
        currentBinding.artemisRotateSwitch.isChecked = rotate
        var patchSel = artemisAutoPatchIndex(autoPatch)
        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_ARTEMIS_AUTO_PATCH_INDEX)) {
            patchSel = savedInstanceState.getInt(STATE_ARTEMIS_AUTO_PATCH_INDEX, patchSel)
        }
        setArtemisAutoPatchSelection(patchSel)
    }

    private fun showArtemisEngineVersionPicker() {
        LauncherDialogRouter.showSingleChoice(
            requireContext(),
            getString(R.string.settings_artemis_engine_version),
            artemisEngineVersionLabels(),
            selectedArtemisEngineVersionIndex,
            ::setArtemisEngineVersionSelection,
        )
    }

    private fun setArtemisEngineVersionSelection(index: Int) {
        val currentBinding = binding ?: return
        val labels = artemisEngineVersionLabels()
        selectedArtemisEngineVersionIndex = if (index in labels.indices) index else 0
        currentBinding.artemisEngineVersionText.setText(labels[selectedArtemisEngineVersionIndex])
    }

    private fun artemisVersionIndex(version: String): Int = when (version) {
        LauncherArtemisGameSettingsBridge.ENGINE_VERSION_V1 -> 1
        LauncherArtemisGameSettingsBridge.ENGINE_VERSION_V2 -> 2
        LauncherArtemisGameSettingsBridge.ENGINE_VERSION_V3 -> 3
        else -> 0
    }

    private fun artemisVersionForIndex(index: Int): String = when (index) {
        1 -> LauncherArtemisGameSettingsBridge.ENGINE_VERSION_V1
        2 -> LauncherArtemisGameSettingsBridge.ENGINE_VERSION_V2
        3 -> LauncherArtemisGameSettingsBridge.ENGINE_VERSION_V3
        else -> LauncherArtemisGameSettingsBridge.ENGINE_VERSION_AUTO
    }

    private fun artemisEngineVersionLabels(): Array<CharSequence> {
        val values = resources.getStringArray(R.array.artemis_engine_version_options)
        return Array(values.size) { values[it] }
    }

    private fun showArtemisAutoPatchPicker() {
        LauncherDialogRouter.showSingleChoice(
            requireContext(),
            getString(R.string.settings_artemis_auto_patch),
            artemisAutoPatchLabels(),
            selectedArtemisAutoPatchIndex,
            ::setArtemisAutoPatchSelection,
        )
    }

    private fun setArtemisAutoPatchSelection(index: Int) {
        val currentBinding = binding ?: return
        val labels = artemisAutoPatchLabels()
        selectedArtemisAutoPatchIndex = if (index in labels.indices) index else INDEX_AUTO_PATCH_ASK
        currentBinding.artemisAutoPatchText.setText(labels[selectedArtemisAutoPatchIndex])
    }

    private fun artemisAutoPatchLabels(): Array<CharSequence> {
        val values = resources.getStringArray(R.array.artemis_auto_patch_options)
        return Array(values.size) { values[it] }
    }

    private fun artemisAutoPatchIndex(strategy: String): Int = when (strategy) {
        LauncherArtemisGameSettingsBridge.AUTO_PATCH_AUTO -> INDEX_AUTO_PATCH_AUTO
        LauncherArtemisGameSettingsBridge.AUTO_PATCH_OFF -> INDEX_AUTO_PATCH_OFF
        else -> INDEX_AUTO_PATCH_ASK
    }

    private fun artemisAutoPatchForIndex(index: Int): String = when (index) {
        INDEX_AUTO_PATCH_AUTO -> LauncherArtemisGameSettingsBridge.AUTO_PATCH_AUTO
        INDEX_AUTO_PATCH_OFF -> LauncherArtemisGameSettingsBridge.AUTO_PATCH_OFF
        else -> LauncherArtemisGameSettingsBridge.AUTO_PATCH_ASK
    }

    private fun showOnsEncodingPicker() {
        LauncherDialogRouter.showSingleChoice(
            requireContext(),
            getString(R.string.settings_ons_text_encoding),
            Array<CharSequence>(ONS_ENCODING_LABELS.size) { ONS_ENCODING_LABELS[it] },
            selectedOnsEncodingIndex,
            ::setOnsEncodingSelection,
        )
    }

    private fun setOnsEncodingSelection(index: Int) {
        val currentBinding = binding ?: return
        selectedOnsEncodingIndex = if (index in ONS_ENCODING_LABELS.indices) index else 0
        currentBinding.onsEncodingText.setText(ONS_ENCODING_LABELS[selectedOnsEncodingIndex])
    }

    private fun onsEncodingIndex(encoding: String): Int {
        val normalized = OnsSettings.normalizeEncoding(encoding)
        for (i in ONS_ENCODING_LABELS.indices) {
            if (ONS_ENCODING_LABELS[i] == normalized) return i
        }
        return 0
    }

    private fun enterNativeKrkr() {
        try {
            val intent = LauncherGameLaunchBridge.buildInternalKrkrOriginIntent(requireContext())
                ?: throw IllegalArgumentException("Kirikiroid2 plugin is not ready")
            startActivity(intent)
        } catch (error: Exception) {
            // Kotlin 无 multi-catch：守卫收窄为预期的两个具体异常（ActivityNotFoundException|IllegalArgumentException），其余继续抛（§8:313）
            if (error !is ActivityNotFoundException && error !is IllegalArgumentException) throw error
            DevLogger.w("LauncherKrkrSettings", "Failed to open native KRKR settings", error)
            Toast.makeText(requireContext(), R.string.settings_native_krkr_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun engineVersionLabels(): Array<CharSequence> {
        val values = resources.getStringArray(R.array.engine_version_options)
        return Array(values.size) { values[it] }
    }

    /** 按承载宿主分派关闭：竖屏薄宿主 finish，HD 由父 Fragment 关闭子 Fragment；直压主容器回退栈则弹栈。 */
    private fun requestClose() {
        when (val host = activity) {
            is LauncherKrkrSettingsActivity -> host.finishKrkrSettings()
            is HdModeActivity -> {
                val owner = parentFragment as? HdEmbeddedActivityOwner
                if (owner == null) {
                    // 游戏库直压主容器回退栈（parentFragment==null）：弹回退栈返回 Library。
                    host.onBackPressedDispatcher.onBackPressed()
                } else if (!owner.closeEmbeddedActivity()) {
                    // HdManageFragment 子 Fragment 承载：owner 关闭失败（竞态）时 no-op，
                    // 避免回退到 onBackPressed 误弹空主栈。
                    Unit
                }
            }
            else -> Unit
        }
    }

    companion object {
        // artemis_auto_patch_options 数组顺序：ask/auto/off（三语文案一致，见 strings_settings.xml）。
        private const val INDEX_AUTO_PATCH_ASK = 0
        private const val INDEX_AUTO_PATCH_AUTO = 1
        private const val INDEX_AUTO_PATCH_OFF = 2
        // engine_version_options 数组顺序：auto/1.3.9/1.3.4/1.2.6。
        private const val INDEX_ENGINE_VERSION_126 = 3
        const val EXTRA_GAME_ID = "extra_game_id"
        private const val STATE_ENGINE_VERSION_INDEX = "engine_version_index"
        private const val STATE_ARTEMIS_ENGINE_VERSION_INDEX = "artemis_engine_version_index"
        private const val STATE_ARTEMIS_AUTO_PATCH_INDEX = "artemis_auto_patch_index"
        private const val STATE_ONS_ENCODING_INDEX = "ons_encoding_index"
        private val ONS_ENCODING_LABELS = arrayOf("gbk", "sjis", "utf8")

        @JvmStatic
        fun newInstance(gameId: Long): LauncherKrkrSettingsFragment =
            LauncherKrkrSettingsFragment().apply {
                arguments = Bundle().apply { putLong(EXTRA_GAME_ID, gameId) }
            }
    }
}
