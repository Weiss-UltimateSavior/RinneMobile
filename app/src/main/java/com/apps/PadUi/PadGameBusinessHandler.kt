package com.apps.PadUi

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.apps.game.GameActionMenuFactory
import com.apps.game.GameListController
import com.apps.game.GameMetadataFormatter
import com.apps.game.GamePasswordLock
import com.apps.game.GameSessionController
import com.apps.game.PinnedGameShortcut
import com.apps.settings.LauncherCustomVndbSearchDialog
import com.apps.settings.LauncherKrkrSettingsActivity
import com.core.R
import com.core.launcherbridge.LauncherMetadataBridge
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.EngineType
import com.core.model.Game
import com.core.util.AppExecutors
import com.core.util.DevLogger
import com.core.util.RxMainQueue

class PadGameBusinessHandler(
    private val context: Context,
    private val mainQueue: RxMainQueue,
    private val listController: GameListController?,
    private val sessionController: GameSessionController?,
    private val fragment: Fragment,
    private val onReloadSingleGame: (Long) -> Unit
) {
    companion object {
        private const val TAG = "PadGameBusinessHandler"
    }

    fun showGameActionMenu(
        game: Game,
        callbacks: GameActionMenuFactory.ActionMenuCallbacks,
        showcaseActionLabel: CharSequence,
    ) {
        val config = GameActionMenuFactory.ActionMenuConfig()
        config.includeEditAction = false
        config.showcaseActionLabel = showcaseActionLabel
        GameActionMenuFactory.showGameActionMenu(fragment, game, config, callbacks)
    }

    // ===== GameActionMenuFactory.ActionMenuCallbacks =====

    fun onShowGameDetail(game: Game) {
        GameActionMenuFactory.showGameDetailDialog(fragment, game)
    }

    fun onEditGame(game: Game) {
        // Pad 游戏库动作菜单不展示编辑项；该回调仅满足公共接口契约。
    }

    fun onShowPlayStatus(game: Game) {
        GameActionMenuFactory.showPlayStatusDialog(
            fragment, game,
            GameActionMenuFactory.SubDialogFactory { ctx, title, labels, checkedIndex, onChoice ->
                PadDialogFactory.showSingleChoice(ctx, title, labels, checkedIndex) { index -> onChoice.accept(index) }
            },
            GameActionMenuFactory.GameUpdateCallback { updated -> listController?.updateSingleGame(updated) }
        )
    }

    fun onEditPlayTime(game: Game) {
        GameActionMenuFactory.showEditPlayTimeDialog(
            fragment, game,
            GameActionMenuFactory.GameUpdateCallback { updated -> listController?.updateSingleGame(updated) }
        )
    }

    fun onToggleFavorite(game: Game) {
        toggleFavorite(game)
    }

    fun onTogglePassword(game: Game) {
        if (GamePasswordLock.hasPassword(game)) {
            GamePasswordLock.clearPassword(fragment, game, null)
        } else {
            GamePasswordLock.setPassword(fragment, game, null)
        }
    }

    fun onShowMoreOptions(game: Game) {
        showMoreOptionsDialog(game)
    }

    // ===== More options dialog =====

    private fun showMoreOptionsDialog(game: Game) {
        val ids = ArrayList<String>()
        val labels = ArrayList<CharSequence>()
        addMoreOption(ids, labels, "edit_play_time", context.getString(R.string.game_action_edit_duration))
        addMoreOption(ids, labels, "pin_shortcut", context.getString(R.string.game_action_pin_shortcut))
        addMoreOption(ids, labels, "rematch", context.getString(R.string.game_action_rematch_vndb))
        addMoreOption(ids, labels, "custom_vndb", context.getString(R.string.game_action_custom_vndb))
        addMoreOption(ids, labels, "sync", context.getString(R.string.game_action_sync_cover))
        if (game.engine == EngineType.ONS || game.engine == EngineType.KIRIKIRI || game.engine == EngineType.ARTEMIS) {
            addMoreOption(ids, labels, "engine_settings", context.getString(R.string.game_action_engine_settings))
        }
        addMoreOption(ids, labels, "delete", context.getString(R.string.game_action_delete))
        val deleteIndex = ids.indexOf("delete")
        PadDialogFactory.showActionChoices(
            context,
            context.getString(R.string.game_action_more),
            labels.toTypedArray(),
            deleteIndex,
        ) { index ->
            when (ids[index]) {
                "edit_play_time" -> GameActionMenuFactory.showEditPlayTimeDialog(
                    fragment, game,
                    GameActionMenuFactory.GameUpdateCallback { updated -> listController?.updateSingleGame(updated) }
                )
                "pin_shortcut" -> PinnedGameShortcut.requestPinShortcut(context, game)
                "rematch" -> rematchMetadata(game)
                "custom_vndb" -> LauncherCustomVndbSearchDialog.show(fragment, game) { onReloadSingleGame(game.id) }
                "sync" -> syncMetadataToCard(game)
                "engine_settings" -> openEngineSettings(game)
                "delete" -> confirmDeleteGame(game)
            }
        }
    }

    private fun addMoreOption(ids: MutableList<String>, labels: MutableList<CharSequence>, id: String, label: CharSequence) {
        ids.add(id)
        labels.add(label)
    }

    // ===== Favorite =====

    private fun toggleFavorite(game: Game) {
        val app = context.applicationContext
        AppExecutors.runOnSingle {
            var updated: Game? = null
            try {
                val latest = LauncherRepositoryBridge.findGameById(app, game.id)
                if (latest != null) {
                    latest.favorite = !latest.favorite
                    if (LauncherRepositoryBridge.updateGame(app, latest) > 0) {
                        updated = latest
                    }
                }
            } catch (e: Exception) {
                DevLogger.w(TAG, "Failed to toggle favorite", e)
            }
            val result = updated
            mainQueue.post {
                if (!fragment.isAdded) return@post
                if (result != null) listController?.updateSingleGame(result)
            }
        }
    }

    // ===== Metadata =====

    private fun rematchMetadata(game: Game) {
        Toast.makeText(context, R.string.game_vndb_searching, Toast.LENGTH_SHORT).show()
        LauncherMetadataBridge.fetchAndSaveMetadataAsync(context, game,
            object : LauncherMetadataBridge.Callback {
                override fun onResult(success: Boolean) {
                    mainQueue.post {
                        if (!fragment.isAdded) return@post
                        Toast.makeText(context,
                            if (success) R.string.game_metadata_updated else R.string.game_metadata_not_found,
                            Toast.LENGTH_SHORT).show()
                        if (success) onReloadSingleGame(game.id)
                    }
                }
            })
    }

    private fun syncMetadataToCard(game: Game) {
        Toast.makeText(context, R.string.game_cover_syncing, Toast.LENGTH_SHORT).show()
        LauncherMetadataBridge.syncCoverToGameAsync(context, game,
            object : LauncherMetadataBridge.Callback {
                override fun onResult(success: Boolean) {
                    mainQueue.post {
                        if (!fragment.isAdded) return@post
                        Toast.makeText(context,
                            if (success) R.string.game_cover_synced else R.string.game_cover_unavailable,
                            Toast.LENGTH_SHORT).show()
                        if (success) onReloadSingleGame(game.id)
                    }
                }
            })
    }

    private fun openEngineSettings(game: Game) {
        try {
            val intent = Intent(context, LauncherKrkrSettingsActivity::class.java)
            intent.putExtra(LauncherKrkrSettingsActivity.EXTRA_GAME_ID, game.id)
            fragment.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            DevLogger.w(TAG, "Failed to open ONS game settings", error)
            Toast.makeText(context, R.string.game_action_engine_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ===== Delete =====

    private fun confirmDeleteGame(game: Game) {
        PadDialogFactory.showDangerConfirm(
            context,
            context.getString(R.string.game_action_delete),
            context.getString(R.string.game_delete_message, GameMetadataFormatter.safeTitle(context, game)),
            context.getString(R.string.game_common_remove),
        ) { deleteGame(game) }
    }

    private fun deleteGame(game: Game) {
        val app = context.applicationContext
        AppExecutors.runOnSingle {
            val deleted = try {
                LauncherRepositoryBridge.deleteGame(app, game.id) > 0
            } catch (e: Exception) {
                DevLogger.w(TAG, "Failed to delete game", e)
                false
            }
            mainQueue.post {
                if (!fragment.isAdded) return@post
                if (!deleted) {
                    Toast.makeText(app, R.string.game_delete_failed, Toast.LENGTH_SHORT).show()
                    return@post
                }
                listController?.removeSingleGame(game.id)
                Toast.makeText(app, R.string.game_deleted, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== Launch =====

    fun confirmLaunchGame(game: Game) {
        PadDialogFactory.showConfirm(
            context,
            context.getString(R.string.pad_launch_game),
            context.getString(R.string.pad_launch_game_message, safeTitle(game)),
            context.getString(R.string.core_confirm),
        ) {
            GamePasswordLock.interceptLaunch(fragment, game) {
                sessionController?.launchGameDirectly(fragment, game)
            }
        }
    }

    private fun safeTitle(game: Game): String {
        val title = game.title
        return if (title.isNullOrBlank()) context.getString(R.string.pad_untitled_game) else title.trim()
    }
}
