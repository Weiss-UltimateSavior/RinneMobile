package com.apps.game

import android.content.Context
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.core.R
import com.core.launcherbridge.LauncherCoverBridge
import com.core.launcherbridge.LauncherMetadataBridge
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.Game
import com.core.util.AppExecutors
import com.core.util.RxMainQueue

/**
 * 游戏同步控制器：统一封装 Library / Pad 两个 Fragment 中重复的批量同步与单卡元数据/封面同步逻辑。
 *
 * 来源：LauncherLibraryFragment / PadManageFragment 重复的 showSyncDataConfirmDialog /
 * performBatchSync / showSyncLoadingDialog / dismissSyncLoadingDialog / showSyncResultDialog /
 * rematchMetadata / syncMetadataToCard 共 7 处方法。
 *
 * 状态归属：原 Fragment 持有的 syncLoadingDialog 字段迁移至本控制器。
 * Fragment 通过 Listener 接口接收同步完成后的列表/分类刷新回调，
 * 通过 DialogFactory 接口注入平台差异化的对话框实现（Library 自绘 / Pad 复用 PadDialogFactory）。
 */
class GameSyncController(
    private val mainQueue: RxMainQueue,
    private val listener: Listener,
    private val dialogFactory: DialogFactory
) {

    interface Listener {
        /** 提供应用级 Context，用于 Bridge 调用，避免持有 Activity Context。 */
        fun getAppContext(): Context

        /** Fragment 是否仍附加，用于异步回调中的生命周期守卫。 */
        fun isAdded(): Boolean

        /**
         * 批量同步完成后的列表/分类刷新回调，在主线程执行。
         * Fragment 应在此方法内更新 listController、gameDevelopers、categories 等状态。
         */
        fun onBatchSyncComplete(loadedGames: List<Game>, categoryResult: CategoryBuildResult)

        /** 单卡元数据/封面同步成功后，就地刷新该卡片。 */
        fun reloadSingleGame(gameId: Long)
    }

    /** 平台差异化对话框工厂：Library 自绘对话框，Pad 复用 PadDialogFactory。 */
    interface DialogFactory {
        /** 显示同步确认对话框，用户点击确认后调用 onConfirm。 */
        fun showSyncConfirmDialog(onConfirm: Runnable)

        /**
         * 创建非可取消的加载对话框，包含一个 tag 为 "sync_progress" 的进度文本。
         * 调用方持有对话框生命周期，通过返回值更新进度。
         */
        fun createSyncLoadingDialog(title: String?, hint: String?): AlertDialog

        /** 显示同步结果对话框。 */
        fun showSyncResultDialog(synced: Int, failed: Int)
    }

    private var syncLoadingDialog: AlertDialog? = null

    /** 批量同步防重复触发标志：仅主线程访问，同步进行中忽略再次点击。 */
    private var syncInProgress = false

    /** 入口：显示同步确认对话框。 */
    fun showSyncDataConfirmDialog() {
        dialogFactory.showSyncConfirmDialog(::performBatchSync)
    }

    /** 执行批量同步：遍历所有游戏，依次刷新 VNDB 元数据与封面。 */
    fun performBatchSync() {
        if (syncInProgress) return
        syncInProgress = true
        val appContext = listener.getAppContext()
        syncLoadingDialog = dialogFactory.createSyncLoadingDialog(
            appContext.getString(R.string.game_sync_data_progress),
            appContext.getString(R.string.game_sync_keep_app_open)
        )

        AppExecutors.io().execute {
            val syncBatchVersion = System.currentTimeMillis()
            var syncGames: List<Game>
            try {
                syncGames = LauncherRepositoryBridge.getAllGames(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "getAllGames failed", e)
                syncGames = emptyList()
            }

            val total = syncGames.size
            var synced = 0
            var failed = 0

            for (i in 0 until total) {
                val game = syncGames[i]
                val title = game.title
                if (title == null || title.trim().isEmpty()) {
                    failed++
                    continue
                }
                try {
                    // 1. 重新匹配 VNDB 元数据（通过 Bridge 调用，内部封装 VndbClient + MetadataRepository）
                    val meta = LauncherMetadataBridge.fetchAndSaveVndbSync(appContext, game)
                    if (meta != null) {
                        // 2. 同步封面到卡片
                        if (meta.coverUrl.trim().isNotEmpty()) {
                            val cover = LauncherCoverBridge.downloadCover(
                                appContext,
                                meta.coverUrl,
                                "sync_cover_" + game.id + "_" + syncBatchVersion
                            )
                            if (cover != null) {
                                val latest = LauncherRepositoryBridge.findGameById(appContext, game.id)
                                if (latest != null) {
                                    latest.coverUri = cover
                                    latest.coverPersistUri = cover
                                    latest.coverSourceType = 1
                                    if (LauncherRepositoryBridge.updateGame(appContext, latest) <= 0) {
                                        throw IllegalStateException(
                                            "Failed to persist synced cover for game ${game.id}"
                                        )
                                    }
                                } else {
                                    throw IllegalStateException(
                                        "Game disappeared during sync: " + game.id
                                    )
                                }
                            }
                        }
                        synced++
                    } else {
                        failed++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "sync single game failed: " + game.id, e)
                    failed++
                }

                // 更新加载弹窗进度
                val progress = i + 1
                val totalGames = total
                mainQueue.post {
                    if (!listener.isAdded()) return@post
                    val dialog = syncLoadingDialog
                    if (dialog != null && dialog.isShowing) {
                        val w = dialog.getWindow()
                        if (w != null) {
                            val progressView = w.decorView.findViewWithTag("sync_progress") as? TextView
                            if (progressView != null) {
                                progressView.text = appContext.getString(
                                    R.string.game_sync_progress, progress, totalGames
                                )
                            }
                        }
                    }
                }
            }

            // 同步完成后：在 IO 线程直接重新加载游戏列表，然后一次性刷新 UI
            var finalGames: List<Game>
            try {
                finalGames = LauncherRepositoryBridge.getAllGames(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "getAllGames after sync failed", e)
                finalGames = emptyList()
            }

            val syncedCount = synced
            val failedCount = failed
            val loadedGames = finalGames
            val categoryResult = try {
                GameCategoryBuilder.build(appContext, loadedGames)
            } catch (e: Exception) {
                Log.w(TAG, "GameCategoryBuilder.build failed", e)
                CategoryBuildResult(emptyList(), emptyMap())
            }
            val loadedCategoryResult = categoryResult

            mainQueue.post {
                syncInProgress = false
                if (!listener.isAdded()) return@post

                listener.onBatchSyncComplete(loadedGames, loadedCategoryResult)

                dismissSyncLoadingDialog()
                dialogFactory.showSyncResultDialog(syncedCount, failedCount)
            }
        }
    }

    private fun dismissSyncLoadingDialog() {
        val dialog = syncLoadingDialog
        if (dialog != null && dialog.isShowing) {
            dialog.dismiss()
            syncLoadingDialog = null
        }
    }

    /** 重新匹配单个游戏的 VNDB 元数据。 */
    fun rematchMetadata(game: Game) {
        val appContext = listener.getAppContext()
        Toast.makeText(appContext, R.string.game_vndb_searching, Toast.LENGTH_SHORT).show()
        LauncherMetadataBridge.fetchAndSaveMetadataAsync(
            appContext,
            game,
            object : LauncherMetadataBridge.Callback {
                override fun onResult(success: Boolean) {
                    mainQueue.post {
                        if (!listener.isAdded()) return@post
                        Toast.makeText(
                            appContext,
                            if (success) R.string.game_metadata_updated else R.string.game_metadata_not_found,
                            Toast.LENGTH_SHORT
                        ).show()
                        if (success) listener.reloadSingleGame(game.id)
                    }
                }
            }
        )
    }

    /** 同步单个游戏的封面到卡片。 */
    fun syncMetadataToCard(game: Game) {
        val appContext = listener.getAppContext()
        Toast.makeText(appContext, R.string.game_cover_syncing, Toast.LENGTH_SHORT).show()
        LauncherMetadataBridge.syncCoverToGameAsync(
            appContext,
            game,
            object : LauncherMetadataBridge.Callback {
                override fun onResult(success: Boolean) {
                    mainQueue.post {
                        if (!listener.isAdded()) return@post
                        Toast.makeText(
                            appContext,
                            if (success) R.string.game_cover_synced else R.string.game_cover_unavailable,
                            Toast.LENGTH_SHORT
                        ).show()
                        if (success) listener.reloadSingleGame(game.id)
                    }
                }
            }
        )
    }

    /** Fragment onDestroyView 时调用，清理未关闭的加载对话框。 */
    fun cleanup() {
        dismissSyncLoadingDialog()
    }

    companion object {
        private const val TAG = "GameSyncController"
    }
}
