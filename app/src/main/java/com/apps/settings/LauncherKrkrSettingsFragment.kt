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
import com.core.launcherbridge.LauncherKrkrBridge
import com.core.launcherbridge.LauncherOnsGameSettingsBridge
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.ons.OnsSettings
import com.core.util.DevLogger

/**
 * 引擎设置页（重构计划 9.9 阶段 110 自 LauncherKrkrSettingsActivity 抽取）。
 *
 * 竖屏由 [LauncherKrkrSettingsActivity] 薄宿主承载，HD 由
 * [com.apps.HDModel.HdManageFragment] 作为子 Fragment 承载；
 * per-game 模式经 [EXTRA_GAME_ID] 参数进入。
 */
class LauncherKrkrSettingsFragment : Fragment() {
    private var binding: ActivityLauncherKrkrSettingsBinding? = null
    private var selectedEngineVersionIndex = 0
    private var selectedOnsEncodingIndex = 0
    private var gameId = 0L

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
        LauncherInsetsHelper.applyTopInset(currentBinding.root, currentBinding.krkrScroll)
        bindActions()
        applyThemeTone()
        if (isPerGameMode()) {
            applyPerGameLayout()
        }
        loadConfig(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_ENGINE_VERSION_INDEX, selectedEngineVersionIndex)
        outState.putInt(STATE_ONS_ENCODING_INDEX, selectedOnsEncodingIndex)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun isPerGameMode(): Boolean = gameId > 0L

    /** Per-game 模式下隐藏与 ONS 无关的全局区段，仅保留 ONS 配置。 */
    private fun applyPerGameLayout() {
        val currentBinding = binding ?: return
        currentBinding.krVersionSection.visibility = View.GONE
        currentBinding.krScopedSection.visibility = View.GONE
        currentBinding.artemisScopedSection.visibility = View.GONE
        currentBinding.tyranoScopedSection.visibility = View.GONE
        currentBinding.tyranoExternalNetworkSection.visibility = View.GONE
        currentBinding.btnNativeKrkr.setText(R.string.settings_restore_global_defaults)
        currentBinding.btnNativeKrkr.setOnClickListener { clearPerGameSettings() }

        val game = LauncherRepositoryBridge.findGameById(requireContext(), gameId)
        val rawTitle = game?.title
        val title = if (rawTitle != null && rawTitle.trim().isNotEmpty()) {
            rawTitle.trim()
        } else {
            getString(R.string.settings_ons_engine_title)
        }
        currentBinding.krkrSectionTitle.text = title
        currentBinding.krkrSectionDescription.setText(R.string.settings_ons_game_override_summary)
    }

    private fun clearPerGameSettings() {
        LauncherOnsGameSettingsBridge.clearOverride(requireContext(), gameId)
        Toast.makeText(requireContext(), R.string.settings_ons_global_restored, Toast.LENGTH_SHORT).show()
        requestClose()
    }

    private fun bindActions() {
        val currentBinding = binding ?: return
        currentBinding.btnSave.setOnClickListener { save() }
        currentBinding.btnCancel.setOnClickListener { requestClose() }
        currentBinding.btnNativeKrkr.setOnClickListener { enterNativeKrkr() }
        currentBinding.engineVersionText.setOnClickListener { showEngineVersionPicker() }
        currentBinding.onsEncodingText.setOnClickListener { showOnsEncodingPicker() }
    }

    private fun applyThemeTone() {
        val currentBinding = binding ?: return
        LauncherTheme.styleMaterialSwitch(currentBinding.krScopedSwitch)
        LauncherTheme.styleMaterialSwitch(currentBinding.artemisScopedSwitch)
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
        val version = LauncherKrkrBridge.getEngineVersion(requireContext())
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
        currentBinding.krScopedSwitch.isChecked = LauncherKrkrBridge.isKrScopedSaveDir(requireContext())
        currentBinding.artemisScopedSwitch.isChecked = LauncherKrkrBridge.isArtemisScopedSaveDir(requireContext())
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
            // Per-game 模式：仅写入该游戏的 ONS 覆盖；KR/Tyrano/Artemis 等全局项保持原值。
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
            requestClose()
            return
        }

        LauncherKrkrBridge.setEngineVersion(requireContext(), version)
        LauncherKrkrBridge.setKrScopedSaveDir(requireContext(), currentBinding.krScopedSwitch.isChecked)
        LauncherKrkrBridge.setArtemisScopedSaveDir(requireContext(), currentBinding.artemisScopedSwitch.isChecked)
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
        const val EXTRA_GAME_ID = "extra_game_id"
        private const val STATE_ENGINE_VERSION_INDEX = "engine_version_index"
        private const val STATE_ONS_ENCODING_INDEX = "ons_encoding_index"
        private val ONS_ENCODING_LABELS = arrayOf("gbk", "sjis", "utf8")

        @JvmStatic
        fun newInstance(gameId: Long): LauncherKrkrSettingsFragment =
            LauncherKrkrSettingsFragment().apply {
                arguments = Bundle().apply { putLong(EXTRA_GAME_ID, gameId) }
            }
    }
}
