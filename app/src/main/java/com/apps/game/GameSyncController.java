package com.apps.game;

import android.content.Context;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.core.launcherbridge.LauncherCoverBridge;
import com.core.launcherbridge.LauncherMetadataBridge;
import com.core.launcherbridge.LauncherRepositoryBridge;
import com.core.metadata.VnMetadata;
import com.core.model.Game;
import com.core.util.AppExecutors;
import com.core.util.RxMainQueue;

import java.util.Collections;
import java.util.List;

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
public final class GameSyncController {

    public interface Listener {
        /** 提供应用级 Context，用于 Bridge 调用，避免持有 Activity Context。 */
        Context getAppContext();

        /** Fragment 是否仍附加，用于异步回调中的生命周期守卫。 */
        boolean isAdded();

        /**
         * 批量同步完成后的列表/分类刷新回调，在主线程执行。
         * Fragment 应在此方法内更新 listController、gameDevelopers、categories 等状态。
         */
        void onBatchSyncComplete(List<Game> loadedGames, CategoryBuildResult categoryResult);

        /** 单卡元数据/封面同步成功后，就地刷新该卡片。 */
        void reloadSingleGame(long gameId);
    }

    /** 平台差异化对话框工厂：Library 自绘对话框，Pad 复用 PadDialogFactory。 */
    public interface DialogFactory {
        /** 显示同步确认对话框，用户点击确认后调用 onConfirm。 */
        void showSyncConfirmDialog(Runnable onConfirm);

        /**
         * 创建非可取消的加载对话框，包含一个 tag 为 "sync_progress" 的进度文本。
         * 调用方持有对话框生命周期，通过返回值更新进度。
         */
        AlertDialog createSyncLoadingDialog(String title, String hint);

        /** 显示同步结果对话框。 */
        void showSyncResultDialog(int synced, int failed);
    }

    private final RxMainQueue mainQueue;
    private final Listener listener;
    private final DialogFactory dialogFactory;
    private AlertDialog syncLoadingDialog;

    public GameSyncController(RxMainQueue mainQueue, Listener listener, DialogFactory dialogFactory) {
        this.mainQueue = mainQueue;
        this.listener = listener;
        this.dialogFactory = dialogFactory;
    }

    /** 入口：显示同步确认对话框。 */
    public void showSyncDataConfirmDialog() {
        dialogFactory.showSyncConfirmDialog(this::performBatchSync);
    }

    /** 执行批量同步：遍历所有游戏，依次刷新 VNDB 元数据与封面。 */
    public void performBatchSync() {
        syncLoadingDialog = dialogFactory.createSyncLoadingDialog(
                "正在同步数据...", "请不要关闭应用及网络，否则可能导致数据出错");

        Context appContext = listener.getAppContext();
        AppExecutors.io().execute(() -> {
            final long syncBatchVersion = System.currentTimeMillis();
            List<Game> syncGames;
            try {
                syncGames = LauncherRepositoryBridge.getAllGames(appContext);
            } catch (Throwable e) {
                syncGames = Collections.emptyList();
            }

            int total = syncGames.size();
            int synced = 0;
            int failed = 0;

            for (int i = 0; i < total; i++) {
                Game game = syncGames.get(i);
                if (game.title == null || game.title.trim().isEmpty()) {
                    failed++;
                    continue;
                }
                try {
                    // 1. 重新匹配 VNDB 元数据（通过 Bridge 调用，内部封装 VndbClient + MetadataRepository）
                    VnMetadata meta = LauncherMetadataBridge.fetchAndSaveVndbSync(appContext, game);
                    if (meta != null) {
                        // 2. 同步封面到卡片
                        if (meta.coverUrl != null && !meta.coverUrl.trim().isEmpty()) {
                            String cover = LauncherCoverBridge.downloadCover(
                                    appContext,
                                    meta.coverUrl,
                                    "sync_cover_" + game.id + "_" + syncBatchVersion
                            );
                            if (cover != null) {
                                Game latest = LauncherRepositoryBridge.findGameById(appContext, game.id);
                                if (latest != null) {
                                    latest.coverUri = cover;
                                    latest.coverPersistUri = cover;
                                    latest.coverSourceType = 1;
                                    LauncherRepositoryBridge.updateGame(appContext, latest);
                                }
                            }
                        }
                        synced++;
                    } else {
                        failed++;
                    }
                } catch (Throwable e) {
                    failed++;
                }

                // 更新加载弹窗进度
                final int progress = i + 1;
                final int totalGames = total;
                mainQueue.post(() -> {
                    if (syncLoadingDialog != null && syncLoadingDialog.isShowing()) {
                        Window w = syncLoadingDialog.getWindow();
                        if (w != null) {
                            TextView progressView = w.getDecorView().findViewWithTag("sync_progress");
                            if (progressView != null) {
                                progressView.setText(progress + "/" + totalGames + " 已完成");
                            }
                        }
                    }
                });
            }

            // 同步完成后：在 IO 线程直接重新加载游戏列表，然后一次性刷新 UI
            List<Game> finalGames;
            try {
                finalGames = LauncherRepositoryBridge.getAllGames(appContext);
            } catch (Throwable e) {
                finalGames = Collections.emptyList();
            }

            final int syncedCount = synced;
            final int failedCount = failed;
            List<Game> loadedGames = finalGames;
            CategoryBuildResult categoryResult;
            try {
                categoryResult = GameCategoryBuilder.build(appContext, loadedGames);
            } catch (Throwable throwable) {
                categoryResult = new CategoryBuildResult(Collections.emptyList(), Collections.emptyMap());
            }

            CategoryBuildResult loadedCategoryResult = categoryResult;

            mainQueue.post(() -> {
                if (!listener.isAdded()) return;

                listener.onBatchSyncComplete(loadedGames, loadedCategoryResult);

                dismissSyncLoadingDialog();
                dialogFactory.showSyncResultDialog(syncedCount, failedCount);
            });
        });
    }

    private void dismissSyncLoadingDialog() {
        if (syncLoadingDialog != null && syncLoadingDialog.isShowing()) {
            syncLoadingDialog.dismiss();
            syncLoadingDialog = null;
        }
    }

    /** 重新匹配单个游戏的 VNDB 元数据。 */
    public void rematchMetadata(Game game) {
        Context appContext = listener.getAppContext();
        Toast.makeText(appContext, "正在搜索 VNDB...", Toast.LENGTH_SHORT).show();
        LauncherMetadataBridge.fetchAndSaveMetadataAsync(appContext, game, success -> {
            mainQueue.post(() -> {
                if (!listener.isAdded()) return;
                Toast.makeText(appContext, success ? "元数据已更新" : "未找到匹配的元数据", Toast.LENGTH_SHORT).show();
                if (success) listener.reloadSingleGame(game.id);
            });
        });
    }

    /** 同步单个游戏的封面到卡片。 */
    public void syncMetadataToCard(Game game) {
        Context appContext = listener.getAppContext();
        Toast.makeText(appContext, "正在同步封面...", Toast.LENGTH_SHORT).show();
        LauncherMetadataBridge.syncCoverToGameAsync(appContext, game, success -> {
            mainQueue.post(() -> {
                if (!listener.isAdded()) return;
                Toast.makeText(appContext, success ? "封面已同步" : "无可用封面", Toast.LENGTH_SHORT).show();
                if (success) listener.reloadSingleGame(game.id);
            });
        });
    }

    /** Fragment onDestroyView 时调用，清理未关闭的加载对话框。 */
    public void cleanup() {
        dismissSyncLoadingDialog();
    }
}
