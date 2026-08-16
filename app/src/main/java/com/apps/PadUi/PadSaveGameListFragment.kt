package com.apps.PadUi

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.apps.game.GameMetadataFormatter
import com.apps.game.LauncherSaveCategoryActivity
import com.apps.theme.LauncherTheme
import com.core.R
import com.core.data.GameSaveFileManager
import com.core.databinding.FragmentPadSaveGameListBinding
import com.core.diagnostics.GameDiagnostics
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.EngineType
import com.core.model.Game
import com.core.util.TimeFormatUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Pad 横屏存档二级列表：列出某内置引擎下的游戏，选择后管理真实存档文件。
 *
 * 复刻竖屏 [com.apps.game.LauncherSaveGameListFragment] 的业务逻辑，但：
 * - 布局用横屏单列（fragment_pad_save_game_list.xml），由 [PadSaveCategoryActivity] 内嵌到右侧明细容器
 * - 弹窗直接使用 [PadDialogFactory]（竖屏出厂在 HD/Pad 壳外，router 会回退竖屏工厂）
 * - 异步用结构化协程（lifecycleScope + Dispatchers.IO），不用 AppExecutors
 */
class PadSaveGameListFragment : Fragment() {

    private var binding: FragmentPadSaveGameListBinding? = null
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
        val currentBinding = FragmentPadSaveGameListBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val currentBinding = binding ?: return
        // 嵌入 Pad 存档宿主时露出宿主明细容器圆角白卡背景（agent.md §6 模式）。
        if (activity is PadSaveCategoryActivity) view.background = null
        saveManager = GameSaveFileManager(requireContext())
        engineName = arguments?.getString(EXTRA_ENGINE)
        restoreSelectedSaveGame(savedInstanceState)
        val engine = EngineType.fromString(engineName)
        currentBinding.padSaveGameListTitle.text = getString(
            R.string.game_save_engine_games,
            LauncherSaveCategoryActivity.engineLabel(requireContext(), engine),
        )
        LauncherTheme.applyPrimaryTone(view)
        loadGames()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_SAVE_GAME_ID, selectedSaveGame?.id ?: -1L)
    }

    /** 恢复旋转/重建前选中的待操作游戏，避免 SAF 回调静默放弃。 */
    private fun restoreSelectedSaveGame(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        val gameId = savedInstanceState.getLong(STATE_SAVE_GAME_ID, -1L)
        if (gameId < 0L) return
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val game = LauncherRepositoryBridge.findGameById(app, gameId)
            withContext(Dispatchers.Main) {
                if (isAdded) selectedSaveGame = game
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        saveManager = null
        super.onDestroyView()
    }

    private fun loadGames() {
        val currentBinding = binding ?: return
        val app = requireContext().applicationContext
        val mgr = saveManager ?: return
        val requestedEngine = EngineType.fromString(engineName)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val managedGames = mutableListOf<Game>()
            val saveStates = mutableListOf<Boolean>()
            if (LauncherSaveCategoryActivity.isSupportedBuiltInEngine(requestedEngine)) {
                LauncherRepositoryBridge.getAllGames(app).forEach { game ->
                    val gameEngine = game?.engine?.name ?: EngineType.UNKNOWN.name
                    if (gameEngine != engineName || !LauncherSaveCategoryActivity.isSupportedBuiltInGame(game)) {
                        return@forEach
                    }
                    managedGames.add(game)
                    var hasSave = false
                    try {
                        hasSave = mgr.listInternalSaveFiles(game).isNotEmpty()
                    } catch (_: Exception) {
                        // 不可读位置按「无存档」展示而不是阻塞列表。
                    }
                    saveStates.add(hasSave)
                }
            }
            withContext(Dispatchers.Main) {
                if (!isAdded || binding == null) return@withContext
                val current = binding ?: return@withContext
                current.padSaveGameList.removeAllViews()
                if (!LauncherSaveCategoryActivity.isSupportedBuiltInEngine(requestedEngine)) {
                    current.padSaveGameListStatus.setText(R.string.game_save_not_internal)
                    return@withContext
                }
                for (index in managedGames.indices) {
                    addGame(current, managedGames[index], saveStates[index])
                }
                val count = managedGames.size
                current.padSaveGameListStatus.text = if (count == 0) {
                    getString(R.string.game_save_engine_empty)
                } else {
                    getString(R.string.game_save_engine_count, count)
                }
            }
        }
    }

    private fun isUiAvailable(): Boolean = isAdded && binding != null

    private fun addGame(currentBinding: FragmentPadSaveGameListBinding, game: Game, hasSave: Boolean) {
        val itemView = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_launcher_recent, currentBinding.padSaveGameList, false)
        val icon = itemView.findViewById<TextView>(R.id.recentIcon)
        val title = itemView.findViewById<TextView>(R.id.recentTitle)
        val meta = itemView.findViewById<TextView>(R.id.recentMeta)
        val status = itemView.findViewById<TextView>(R.id.recentStatus)
        val gameTitle = GameMetadataFormatter.safeTitle(requireContext(), game)
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
        currentBinding.padSaveGameList.addView(itemView)
    }

    private fun showNoSaveImportDialog(game: Game) {
        PadDialogFactory.showStandardConfirm(
            requireContext(),
            getString(R.string.game_save_none),
            getString(R.string.game_save_none_message, GameMetadataFormatter.safeTitle(requireContext(), game)),
            getString(R.string.game_save_import_zip),
        ) {
            selectedSaveGame = game
            overwriteZipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed"))
        }
    }

    private fun showSaveActionsDialog(game: Game) {
        PadDialogFactory.showActionChoices(
            requireContext(),
            getString(R.string.game_save_game_title, abbreviateGameTitle(game)),
            arrayOf(
                getString(R.string.game_save_export_zip),
                getString(R.string.game_save_import_zip),
                getString(R.string.game_save_delete),
            ),
            -1,
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
        PadDialogFactory.showDangerConfirm(
            requireContext(),
            getString(R.string.game_save_delete),
            getString(R.string.game_save_delete_message, GameMetadataFormatter.safeTitle(requireContext(), game)),
            getString(R.string.game_save_delete),
        ) { deleteSaves(game) }
    }

    private fun deleteSaves(game: Game) {
        val app = requireContext().applicationContext
        val mgr = saveManager ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val count = mgr.deleteInternalSave(game)
                withContext(Dispatchers.Main) {
                    if (!isUiAvailable()) return@withContext
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.game_save_deleted, count),
                        Toast.LENGTH_LONG,
                    ).show()
                    loadGames()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: IOException) {
                handleSaveError(app, game, error, getString(R.string.game_save_delete_failed))
            } catch (error: SecurityException) {
                handleSaveError(app, game, error, getString(R.string.game_save_delete_failed))
            }
        }
    }

    private fun showOverwriteConfirmDialog(game: Game) {
        PadDialogFactory.showStandardConfirm(
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
        val app = requireContext().applicationContext
        val mgr = saveManager ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val count = mgr.exportInternalSaveToZip(game, destinationUri)
                withContext(Dispatchers.Main) {
                    if (!isUiAvailable()) return@withContext
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.game_save_exported_count, count),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: IOException) {
                handleSaveError(app, game, error, getString(R.string.game_save_export_failed))
            } catch (error: SecurityException) {
                handleSaveError(app, game, error, getString(R.string.game_save_export_failed))
            }
        }
    }

    private fun importSaveFromZip(game: Game, sourceUri: Uri) {
        val app = requireContext().applicationContext
        val mgr = saveManager ?: return
        takeReadPermission(sourceUri)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val count = mgr.importInternalSaveFromZip(game, sourceUri, true)
                withContext(Dispatchers.Main) {
                    if (!isUiAvailable()) return@withContext
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.game_save_imported_count, count),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: IOException) {
                handleSaveError(app, game, error, getString(R.string.game_save_overwrite_failed))
            } catch (error: SecurityException) {
                handleSaveError(app, game, error, getString(R.string.game_save_overwrite_failed))
            }
        }
    }

    private fun takeReadPermission(uri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // 没有持久化权限的 Provider 在授予期间内仍有效。
        }
    }

    private fun showError(title: String, error: Exception) {
        if (!isUiAvailable()) return
        PadDialogFactory.showInfo(
            requireContext(),
            title,
            error.message ?: getString(R.string.game_common_unknown_error),
        )
    }

    /** 记录存档操作异常并提示用户（IOException / SecurityException 共用）。 */
    private suspend fun handleSaveError(app: android.content.Context, game: Game, error: Exception, title: String) {
        GameDiagnostics.record(
            app,
            "save_exception",
            game,
            error.message ?: getString(R.string.game_common_unknown_error),
        )
        // 弹窗是 UI 操作，必须在主线程执行。
        withContext(Dispatchers.Main) { showError(title, error) }
    }

    private fun buildArchiveFileName(game: Game): String {
        var title = GameMetadataFormatter.safeTitle(requireContext(), game)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (title.isEmpty()) title = getString(R.string.game_save_default_archive_title)
        return title + getString(R.string.game_save_archive_suffix)
    }

    private fun abbreviateGameTitle(game: Game?): String {
        val title = GameMetadataFormatter.safeTitle(requireContext(), game)
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
        const val EXTRA_ENGINE = "pad_save_engine"
        private const val STATE_SAVE_GAME_ID = "state_save_game_id"

        @JvmStatic
        fun newInstance(engineName: String?): PadSaveGameListFragment =
            PadSaveGameListFragment().apply {
                arguments = Bundle().apply { putString(EXTRA_ENGINE, engineName) }
            }
    }
}
