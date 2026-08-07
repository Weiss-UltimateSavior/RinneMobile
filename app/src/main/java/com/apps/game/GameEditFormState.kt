package com.apps.game

import android.net.Uri
import android.os.Bundle
import com.core.databinding.ActivityLauncherGameEditBinding
import com.core.util.DevLogger

/**
 * 编辑游戏页表单状态：Bundle 保存/恢复（Activity 重建）与 STATE_* 常量
 * （阶段 142 从 [LauncherGameEditFragment] 抽离）。
 */
internal class GameEditFormState {
    /** 仅当用户在本会话内显式选择了另一个引擎时为 true。 */
    var engineChanged = false
    /** 目录在本会话内被重新绑定（rootUri 变更）时为 true。 */
    var directoryRebound = false
    /** 目录 SAF 持久化授权降级为只读或彻底失败，saveGame 时提示用户。 */
    var directoryPermissionDegraded = false
    var lastEngineDefaultPackage = ""
    var restoreEngineSelection = false
    var restoreDirectorySelection = false
    var restoreCoverSelection = false
    var restoreFormState = false
    var selectedGameDirectoryUri: Uri? = null
    var selectedCoverUri: Uri? = null

    fun save(outState: Bundle, binding: ActivityLauncherGameEditBinding, engineOptionIndex: Int) {
        outState.putInt(STATE_ENGINE_OPTION_INDEX, engineOptionIndex)
        outState.putString(STATE_LAST_ENGINE_DEFAULT_PACKAGE, lastEngineDefaultPackage)
        val dir = selectedGameDirectoryUri
        if (dir != null) outState.putString(STATE_GAME_DIRECTORY_URI, dir.toString())
        val cover = selectedCoverUri
        if (cover != null) outState.putString(STATE_COVER_URI, cover.toString())
        outState.putString(STATE_TITLE, binding.editTitle.text?.toString().orEmpty())
        outState.putString(STATE_EMULATOR_PACKAGE, binding.editEmulator.text?.toString().orEmpty())
        outState.putString(STATE_LAUNCH_TARGET, binding.editLaunchTarget.text?.toString().orEmpty())
        outState.putString(STATE_GAMEHUB_LOCAL_GAME_ID, binding.editGameHubLocalGameId.text?.toString().orEmpty())
        outState.putString(STATE_DESCRIPTION, binding.editDescription.text?.toString().orEmpty())
        outState.putBoolean(STATE_DIRECTORY_REBOUND, directoryRebound)
        outState.putBoolean(STATE_ENGINE_CHANGED, engineChanged)
        outState.putBoolean(STATE_DIRECTORY_PERMISSION_DEGRADED, directoryPermissionDegraded)
    }

    /**
     * 恢复瞬态状态。表单文本与目录/封面选择标记在此恢复；引擎选项经 [applyEngine]
     * 回调写回（调用方负责设置 currentEngineOption 与 UI 文本）。
     */
    fun restore(
        savedInstanceState: Bundle,
        binding: ActivityLauncherGameEditBinding,
        engineOptions: Array<EngineOption>,
        applyEngine: (EngineOption?) -> Unit,
    ) {
        restoreFormState = savedInstanceState.containsKey(STATE_TITLE)
        directoryRebound = savedInstanceState.getBoolean(STATE_DIRECTORY_REBOUND, false)
        engineChanged = savedInstanceState.getBoolean(STATE_ENGINE_CHANGED, false)
        directoryPermissionDegraded =
            savedInstanceState.getBoolean(STATE_DIRECTORY_PERMISSION_DEGRADED, false)
        if (restoreFormState) {
            binding.editTitle.setText(savedInstanceState.getString(STATE_TITLE, ""))
            binding.editEmulator.setText(savedInstanceState.getString(STATE_EMULATOR_PACKAGE, ""))
            binding.editLaunchTarget.setText(savedInstanceState.getString(STATE_LAUNCH_TARGET, ""))
            binding.editGameHubLocalGameId.setText(savedInstanceState.getString(STATE_GAMEHUB_LOCAL_GAME_ID, ""))
            binding.editDescription.setText(savedInstanceState.getString(STATE_DESCRIPTION, ""))
        }
        restoreEngineSelection = savedInstanceState.containsKey(STATE_ENGINE_OPTION_INDEX)
        if (restoreEngineSelection) {
            lastEngineDefaultPackage =
                savedInstanceState.getString(STATE_LAST_ENGINE_DEFAULT_PACKAGE, "")
            applyEngine(engineOptions[boundedEngineOptionIndex(
                savedInstanceState.getInt(STATE_ENGINE_OPTION_INDEX, 0),
                engineOptions,
            )])
        }
        selectedGameDirectoryUri = uriFromState(savedInstanceState.getString(STATE_GAME_DIRECTORY_URI))
        restoreDirectorySelection = selectedGameDirectoryUri != null
        selectedCoverUri = uriFromState(savedInstanceState.getString(STATE_COVER_URI))
        restoreCoverSelection = selectedCoverUri != null
    }

    fun uriFromState(value: String?): Uri? {
        if (value.isNullOrBlank()) return null
        return try {
            Uri.parse(value)
        } catch (error: IllegalArgumentException) {
            DevLogger.w("LauncherGameEdit", "Invalid saved URI state", error)
            null
        }
    }

    private fun boundedEngineOptionIndex(index: Int, engineOptions: Array<EngineOption>): Int =
        if (index in engineOptions.indices) index else 0

    companion object {
        const val STATE_ENGINE_OPTION_INDEX = "engine_option_index"
        const val STATE_LAST_ENGINE_DEFAULT_PACKAGE = "last_engine_default_package"
        const val STATE_GAME_DIRECTORY_URI = "game_directory_uri"
        const val STATE_COVER_URI = "cover_uri"
        const val STATE_TITLE = "title"
        const val STATE_EMULATOR_PACKAGE = "emulator_package"
        const val STATE_LAUNCH_TARGET = "launch_target"
        const val STATE_GAMEHUB_LOCAL_GAME_ID = "gamehub_local_game_id"
        const val STATE_DESCRIPTION = "description"
        const val STATE_DIRECTORY_REBOUND = "directory_rebound"
        const val STATE_ENGINE_CHANGED = "engine_changed"
        const val STATE_DIRECTORY_PERMISSION_DEGRADED = "directory_permission_degraded"
    }
}
