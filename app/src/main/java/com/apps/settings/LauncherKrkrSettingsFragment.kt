package com.apps.settings

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.apps.HDModel.HdEmbeddedActivityOwner
import com.apps.HDModel.HdModeActivity
import com.apps.HDModel.LauncherDialogRouter
import com.apps.common.LauncherInsetsHelper
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherKrkrSettingsBinding
import com.core.launcherbridge.LauncherGameLaunchBridge
import com.core.launcherbridge.LauncherArtemisGameSettingsBridge
import com.core.launcherbridge.LauncherKrkrBridge
import com.core.launcherbridge.LauncherKrkrGameSettingsBridge
import com.core.launcherbridge.LauncherOnsGameSettingsBridge
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.EngineType
import com.core.ons.OnsSettings
import com.core.util.DevLogger

/**
 * 引擎设置页（重构计划 9.9 阶段 110 自 LauncherKrkrSettingsActivity 抽取）。
 *
 * 竖屏由 [LauncherKrkrSettingsActivity] 薄宿主承载，HD 由
 * [com.apps.HDModel.HdManageFragment] 作为子 Fragment 承载；
 * per-game 模式经 [EXTRA_GAME_ID] 参数进入；Artemis 应用级页面经 [EXTRA_ARTEMIS_ONLY] 进入。
 */
class LauncherKrkrSettingsFragment : Fragment() {
    private var binding: ActivityLauncherKrkrSettingsBinding? = null
    private var selectedEngineVersionIndex = 0
    private var selectedArtemisEngineVersionIndex = 0
    private var selectedArtemisAutoPatchIndex = 0
    private var selectedOnsEncodingIndex = 0
    private var gameId = 0L
    /** per-game 模式下目标游戏的引擎类型；全局模式为 null。 */
    private var perGameEngine: EngineType? = null
    /** Artemis 应用级设置页（仅显示 Artemis 区段，配置全局默认）。 */
    private var artemisOnly = false

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
        LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        gameId = arguments?.getLong(EXTRA_GAME_ID, 0L) ?: 0L
        artemisOnly = arguments?.getBoolean(EXTRA_ARTEMIS_ONLY, false) ?: false
        if (isPerGameMode()) {
            perGameEngine = LauncherRepositoryBridge.findGameById(requireContext(), gameId)?.engine
        }
        LauncherInsetsHelper.applyTopInset(currentBinding.root, currentBinding.krkrScroll)
        bindActions()
        applyThemeTone()
        when {
            artemisOnly -> applyArtemisOnlyLayout()
            isPerGameMode() -> applyPerGameLayout()
        }
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

    /** Artemis 应用级设置页：仅显示 Artemis 区段，配置全局默认。 */
    private fun applyArtemisOnlyLayout() {
        val currentBinding = binding ?: return
        currentBinding.krVersionSection.visibility = View.GONE
        currentBinding.krScopedSection.visibility = View.GONE
        currentBinding.tyranoScopedSection.visibility = View.GONE
        currentBinding.tyranoExternalNetworkSection.visibility = View.GONE
        currentBinding.onsSection.visibility = View.GONE
        currentBinding.artemisSection.visibility = View.VISIBLE
        currentBinding.krkrSectionTitle.setText(R.string.settings_engine_title)
        currentBinding.krkrSectionDescription.setText(R.string.settings_engine_summary)
        currentBinding.btnNativeKrkr.setText(R.string.settings_restore_global_defaults)
        currentBinding.btnNativeKrkr.setOnClickListener { clearArtemisDefaults() }
    }

    /** 恢复 Artemis 应用级默认（自动引擎 + 不反转 + 每次询问补丁）。 */
    private fun clearArtemisDefaults() {
        LauncherArtemisGameSettingsBridge.setDefaultEngineVersion(
            requireContext(),
            LauncherArtemisGameSettingsBridge.ENGINE_VERSION_AUTO,
        )
        LauncherArtemisGameSettingsBridge.setDefaultRotateScreen(requireContext(), false)
        LauncherArtemisGameSettingsBridge.setDefaultAutoPatch(
            requireContext(),
            LauncherArtemisGameSettingsBridge.AUTO_PATCH_ASK,
        )
        Toast.makeText(requireContext(), R.string.settings_ons_global_restored, Toast.LENGTH_SHORT).show()
        requestClose()
    }

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
    }

    private fun applyThemeTone() {
        val currentBinding = binding ?: return
        LauncherTheme.styleMaterialSwitch(currentBinding.krScopedSwitch)
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

        if (isPerGameMode()) {
            // Per-game 模式：仅写入该游戏的引擎覆盖；其他引擎与全局项保持原值。
            when (perGameEngine) {
                EngineType.KIRIKIRI -> {
                    val perGame = LauncherKrkrGameSettingsBridge.load(requireContext(), gameId)
                    perGame.engineVersion = version
                    perGame.scopedSaveDir = currentBinding.krScopedSwitch.isChecked
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

        if (artemisOnly) {
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
                getString(R.string.settings_engine_saved, artemisEngineVersionLabels()[selectedArtemisEngineVersionIndex]),
                Toast.LENGTH_SHORT,
            ).show()
            requestClose()
            return
        }

        LauncherKrkrBridge.setEngineVersion(requireContext(), version)
        LauncherKrkrBridge.setKrScopedSaveDir(requireContext(), currentBinding.krScopedSwitch.isChecked)
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
    }

    /** 加载 Artemis 区段配置（per-game 覆盖或应用级默认）。 */
    private fun loadArtemisConfig(savedInstanceState: Bundle?) {
        val currentBinding = binding ?: return
        val isArtemisPage = artemisOnly || (isPerGameMode() && perGameEngine == EngineType.ARTEMIS)
        if (!isArtemisPage) return
        val settings = if (artemisOnly) {
            null
        } else {
            LauncherArtemisGameSettingsBridge.load(requireContext(), gameId)
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
            startActivity(LauncherGameLaunchBridge.buildInternalKrkrOriginIntent(requireContext()))
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

    /** 按承载宿主分派关闭：竖屏薄宿主 finish，HD 由父 Fragment 关闭子 Fragment。 */
    private fun requestClose() {
        when (val host = activity) {
            is LauncherKrkrSettingsActivity -> host.finishKrkrSettings()
            is HdModeActivity -> (parentFragment as? HdEmbeddedActivityOwner)?.closeEmbeddedActivity()
            else -> Unit
        }
    }

    companion object {
        // artemis_auto_patch_options 数组顺序：ask/auto/off（三语文案一致，见 strings_settings.xml）。
        private const val INDEX_AUTO_PATCH_ASK = 0
        private const val INDEX_AUTO_PATCH_AUTO = 1
        private const val INDEX_AUTO_PATCH_OFF = 2
        const val EXTRA_GAME_ID = "extra_game_id"
        const val EXTRA_ARTEMIS_ONLY = "extra_artemis_only"
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

        @JvmStatic
        fun newArtemisOnlyInstance(): LauncherKrkrSettingsFragment =
            LauncherKrkrSettingsFragment().apply {
                arguments = Bundle().apply { putBoolean(EXTRA_ARTEMIS_ONLY, true) }
            }
    }
}
