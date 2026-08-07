package com.apps.game

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.apps.HDModel.HdModeActivity
import com.apps.HDModel.LauncherDialogRouter
import com.apps.common.LauncherInsetsHelper
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.data.GameSaveFileManager
import com.core.databinding.ActivityLauncherSaveGameListBinding
import com.core.diagnostics.GameDiagnostics
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.EngineType
import com.core.model.Game
import com.core.util.AppExecutors
import com.core.util.TimeFormatUtil

/**
 * 内置引擎存档游戏列表（重构计划 9.9 阶段 107 自 LauncherSaveGameListActivity 抽取）。
 *
 * 竖屏由 [LauncherSaveGameListActivity] 薄宿主承载，HD 由 [com.apps.HDModel.HdSaveManagerFragment]
 * 作为子 Fragment 承载；ActivityResult 由 Fragment 自身注册，双上下文均可靠。
 */
class LauncherSaveGameListFragment : Fragment() {
    private var binding: ActivityLauncherSaveGameListBinding? = null
    private var engineName: String? = null
    private var saveManager: GameSaveFileManager? = null
    private var selectedSaveGame: Game? = null

    private val exportZipPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val game = selectedSaveGame ?: return@registerForActivityResult
            if (uri != null) exportSaveToZip(game, uri)
        }
    private val overwriteZipPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val game = selectedSaveGame ?: return@registerForActivityResult
            if (uri != null) importSaveFromZip(game, uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherSaveGameListBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (activity !is HdModeActivity) LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        saveManager = GameSaveFileManager(requireContext())
        engineName = arguments?.getString(EXTRA_ENGINE)
        val engine = EngineType.fromString(engineName)
        currentBinding.saveGameListTitle.text = getString(
            R.string.game_save_engine_games,
            LauncherSaveCategoryActivity.engineLabel(requireContext(), engine),
        )
        LauncherInsetsHelper.applyTopInset(currentBinding.root, currentBinding.saveGameListScroll)
        LauncherTheme.applyPrimaryTone(view)
        loadGames()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun loadGames() {
        val appContext = requireContext().applicationContext
        val saveManager = saveManager ?: return
        val requestedEngine = EngineType.fromString(engineName)
        AppExecutors.runOnSingle {
            val games = LauncherRepositoryBridge.getAllGames(appContext)
            val managedGames = mutableListOf<Game>()
            val saveStates = mutableListOf<Boolean>()
            if (LauncherSaveCategoryActivity.isSupportedBuiltInEngine(requestedEngine)) {
                for (game in games) {
                    val gameEngine = game?.engine?.name ?: EngineType.UNKNOWN.name
                    if (gameEngine != engineName || !LauncherSaveCategoryActivity.isSupportedBuiltInGame(game)) continue
                    managedGames.add(game)
                    var hasSave = false
                    try {
                        hasSave = saveManager.listInternalSaveFiles(game).isNotEmpty()
                    } catch (_: Exception) {
                        // 不可读位置按「无存档」展示而不是阻塞列表。
                    }
                    saveStates.add(hasSave)
                }
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val currentBinding = binding ?: return@runOnUiThread
                currentBinding.saveGameList.removeAllViews()
                if (!LauncherSaveCategoryActivity.isSupportedBuiltInEngine(requestedEngine)) {
                    currentBinding.saveGameListStatus.setText(R.string.game_save_not_internal)
                    return@runOnUiThread
                }
                for (index in managedGames.indices) {
                    addGame(currentBinding, managedGames[index], saveStates[index])
                }
                val count = managedGames.size
                currentBinding.saveGameListStatus.text = if (count == 0) {
                    getString(R.string.game_save_engine_empty)
                } else {
                    getString(R.string.game_save_engine_count, count)
                }
            }
        }
    }

    private fun addGame(currentBinding: ActivityLauncherSaveGameListBinding, game: Game, hasSave: Boolean) {
        // 复用首页最近活动卡片，保持与启动器信息流视觉一致。
        val itemView = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_launcher_recent, currentBinding.saveGameList, false)
        LauncherTabletPortraitScaler.apply(itemView)
        val icon = itemView.findViewById<TextView>(R.id.recentIcon)
        val title = itemView.findViewById<TextView>(R.id.recentTitle)
        val meta = itemView.findViewById<TextView>(R.id.recentMeta)
        val status = itemView.findViewById<TextView>(R.id.recentStatus)
        val gameTitle = safeTitle(game)
        icon.text = firstTitleChar(gameTitle)
        title.text = gameTitle
        meta.text = recentMeta(game)
        status.text = "●"
        status.setTextColor(
            if (hasSave) LauncherTheme.primary(requireContext()) else LauncherTheme.danger(requireContext()),
        )
        itemView.isClickable = true
        itemView.isFocusable = true
        itemView.setOnClickListener {
            if (hasSave) showSaveActionsDialog(game) else showNoSaveImportDialog(game)
        }
        LauncherTheme.applyPrimaryTone(itemView)
        currentBinding.saveGameList.addView(itemView)
    }

    private fun showNoSaveImportDialog(game: Game) {
        LauncherDialogRouter.showStandardConfirm(
            requireContext(),
            getString(R.string.game_save_none),
            getString(R.string.game_save_none_message, safeTitle(game)),
            getString(R.string.game_save_import_zip),
        ) {
            selectedSaveGame = game
            overwriteZipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed"))
        }
    }

    private fun showSaveActionsDialog(game: Game) {
        LauncherDialogRouter.showStandardActionChoices(
            requireContext(),
            getString(R.string.game_save_game_title, abbreviateGameTitle(game)),
            arrayOf(
                getString(R.string.game_save_export_zip),
                getString(R.string.game_save_import_zip),
                getString(R.string.game_save_delete),
            ),
        ) { index ->
            when (index) {
                0 -> {
                    selectedSaveGame = game
                    exportZipPicker.launch(buildArchiveFileName(game))
                }
                1 -> showOverwriteConfirmDialog(game)
                else -> showDeleteSaveConfirmDialog(game)
            }
        }
    }

    private fun showDeleteSaveConfirmDialog(game: Game) {
        LauncherDialogRouter.showDangerConfirm(
            requireContext(),
            getString(R.string.game_save_delete),
            getString(R.string.game_save_delete_message, safeTitle(game)),
            getString(R.string.game_save_delete),
        ) { deleteSaves(game) }
    }

    private fun deleteSaves(game: Game) {
        val appContext = requireContext().applicationContext
        val saveManager = saveManager ?: return
        AppExecutors.runOnSingle {
            try {
                val count = saveManager.deleteInternalSave(game)
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.game_save_deleted, count),
                        Toast.LENGTH_LONG,
                    ).show()
                    loadGames()
                }
            } catch (error: Exception) {
                GameDiagnostics.record(
                    appContext,
                    "save_exception",
                    game,
                    getString(
                        R.string.game_save_delete_failed_detail,
                        error.message ?: getString(R.string.game_common_unknown_error),
                    ),
                )
                showError(getString(R.string.game_save_delete_failed), error)
            }
        }
    }

    private fun showOverwriteConfirmDialog(game: Game) {
        LauncherDialogRouter.showStandardConfirm(
            requireContext(),
            getString(R.string.game_save_overwrite_import),
            getString(R.string.game_save_overwrite_short_message),
            getString(R.string.game_save_choose_zip),
        ) {
            selectedSaveGame = game
            overwriteZipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed"))
        }
    }

    private fun exportSaveToZip(game: Game, destinationUri: Uri) {
        val appContext = requireContext().applicationContext
        val saveManager = saveManager ?: return
        AppExecutors.runOnSingle {
            try {
                val count = saveManager.exportInternalSaveToZip(game, destinationUri)
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.game_save_exported_count, count),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (error: Exception) {
                GameDiagnostics.record(
                    appContext,
                    "save_exception",
                    game,
                    getString(
                        R.string.game_save_export_failed_detail,
                        error.message ?: getString(R.string.game_common_unknown_error),
                    ),
                )
                showError(getString(R.string.game_save_export_failed), error)
            }
        }
    }

    private fun importSaveFromZip(game: Game, sourceUri: Uri) {
        val appContext = requireContext().applicationContext
        val saveManager = saveManager ?: return
        takeReadPermission(sourceUri)
        AppExecutors.runOnSingle {
            try {
                val count = saveManager.importInternalSaveFromZip(game, sourceUri, true)
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.game_save_imported_count, count),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (error: Exception) {
                GameDiagnostics.record(
                    appContext,
                    "save_exception",
                    game,
                    getString(
                        R.string.game_save_import_failed_detail,
                        error.message ?: getString(R.string.game_common_unknown_error),
                    ),
                )
                showError(getString(R.string.game_save_overwrite_failed), error)
            }
        }
    }

    private fun takeReadPermission(uri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // 没有持久化权限的 Provider 在授予期间内仍有效。
        }
    }

    private fun showError(title: String, error: Exception) {
        activity?.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            LauncherDialogRouter.showInfo(
                requireContext(),
                title,
                error.message ?: getString(R.string.game_common_unknown_error),
            )
        }
    }

    private fun buildArchiveFileName(game: Game): String {
        var title = safeTitle(game).replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (title.isEmpty()) title = getString(R.string.game_save_default_archive_title)
        return title + getString(R.string.game_save_archive_suffix)
    }

    private fun safeTitle(game: Game?): String {
        val title = game?.title?.trim()
        return if (title.isNullOrEmpty()) getString(R.string.game_unnamed) else title
    }

    private fun abbreviateGameTitle(game: Game?): String {
        val title = safeTitle(game)
        if (title.codePointCount(0, title.length) <= 6) return title
        return title.substring(0, title.offsetByCodePoints(0, 6)) + "..."
    }

    private fun firstTitleChar(title: String): String {
        if (title.isEmpty()) return getString(R.string.game_default_initial)
        val end = title.offsetByCodePoints(0, 1)
        return title.substring(0, end)
    }

    private fun recentMeta(game: Game?): String {
        if (game != null && game.lastPlayedAt > 0L) {
            val time = TimeFormatUtil.shortDate(game.lastPlayedAt)
            return "$time · ${TimeFormatUtil.playTime(game.totalPlayTime)}"
        }
        return getString(R.string.game_save_never_played)
    }

    companion object {
        const val EXTRA_ENGINE = "save_engine"

        @JvmStatic
        fun newInstance(engineName: String?): LauncherSaveGameListFragment =
            LauncherSaveGameListFragment().apply {
                arguments = Bundle().apply { putString(EXTRA_ENGINE, engineName) }
            }
    }
}
