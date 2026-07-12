package com.yuki.yukihub.launcherbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.util.RxMainScheduler;
import com.yuki.yukihub.data.MetadataRepository;
import com.yuki.yukihub.metadata.BangumiClient;
import com.yuki.yukihub.metadata.MetadataController;
import com.yuki.yukihub.metadata.VnMetadata;
import com.yuki.yukihub.metadata.VndbClient;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;

import java.util.List;

public final class LauncherMetadataBridge {
    private static final String APP_PREFS = "yukihub_prefs";

    private LauncherMetadataBridge() {}

    public interface Callback {
        void onResult(boolean success);
    }

    public interface CandidatesCallback {
        void onResult(List<VnMetadata> candidates, String errorMessage);
    }

    // 资料源配置桥接
    public static String getMetadataSource(Context context) {
        if (context == null) return MetadataController.SOURCE_VNDB;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
        return prefs.getString(MetadataController.KEY_METADATA_SOURCE, MetadataController.SOURCE_VNDB);
    }

    public static void setMetadataSource(Context context, String source) {
        if (context == null) return;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(MetadataController.KEY_METADATA_SOURCE, source).apply();
    }

    public static String getBangumiToken(Context context) {
        if (context == null) return "";
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
        return prefs.getString(MetadataController.KEY_BANGUMI_TOKEN, "");
    }

    public static void setBangumiToken(Context context, String token) {
        if (context == null) return;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(MetadataController.KEY_BANGUMI_TOKEN, token == null ? "" : token.trim()).apply();
    }

    public static String sourceLabel(String source) {
        if (MetadataController.SOURCE_BANGUMI.equals(source)) return "Bangumi";
        if (MetadataController.SOURCE_BANGUMI_MIRROR.equals(source)) return "Bangumi 镜像";
        if (MetadataController.SOURCE_YMGAL.equals(source)) return "月幕 Gal";
        return "VNDB";
    }

    /**
     * Searches VNDB for candidates matching the given title, saves the top
     * candidate's metadata locally, and returns the fetched metadata
     * (or null if no match or an error occurred). Runs on the calling thread;
     * callers should dispatch off the main thread.
     */
    public static VnMetadata fetchAndSaveVndbSync(Context context, Game game) {
        if (context == null || game == null || game.title == null || game.title.trim().isEmpty()) {
            return null;
        }
        Context app = context.getApplicationContext();
        try {
            List<VnMetadata> candidates = VndbClient.searchCandidates(game.title, 1);
            if (candidates == null || candidates.isEmpty()) return null;
            VnMetadata meta = candidates.get(0);
            MetadataRepository metaRepo = new MetadataRepository(app);
            metaRepo.saveVndb(game.id, meta);
            return meta;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void fetchAndSaveMetadataAsync(Context context, Game game, Callback callback) {
        if (game == null || game.title == null || game.title.trim().isEmpty()) {
            callback.onResult(false);
            return;
        }
        Context app = context.getApplicationContext();
        AppExecutors.io().execute(() -> {
            boolean ok = false;
            try {
                List<VnMetadata> candidates = VndbClient.searchCandidates(game.title, 1);
                if (candidates != null && !candidates.isEmpty()) {
                    VnMetadata meta = candidates.get(0);
                    MetadataRepository metaRepo = new MetadataRepository(app);
                    metaRepo.saveVndb(game.id, meta);
                    if (meta.coverUrl != null && !meta.coverUrl.trim().isEmpty()) {
                        String cover = LauncherCoverBridge.downloadCover(app, meta.coverUrl, "meta_cover_" + game.id);
                        if (cover != null) {
                            GameRepository repo = new GameRepository(app);
                            Game latest = repo.findById(game.id);
                            if (latest != null && (latest.coverUri == null || latest.coverUri.trim().isEmpty())) {
                                latest.coverUri = cover;
                                latest.coverPersistUri = cover;
                                latest.coverSourceType = 1;
                                repo.update(latest);
                            }
                        }
                    }
                    ok = true;
                }
            } catch (Throwable ignored) {}
            final boolean success = ok;
            RxMainScheduler.post(() -> callback.onResult(success));
        });
    }

    /** 按用户输入的关键词搜索多个 VNDB 候选，结果始终回调到主线程。 */
    public static void searchVndbCandidatesAsync(Context context, String keyword, int limit,
                                                  CandidatesCallback callback) {
        if (callback == null) return;
        String query = keyword == null ? "" : keyword.trim();
        if (context == null || query.isEmpty()) {
            callback.onResult(java.util.Collections.emptyList(), "请输入搜索关键词");
            return;
        }
        AppExecutors.io().execute(() -> {
            List<VnMetadata> candidates = java.util.Collections.emptyList();
            String error = null;
            try {
                candidates = VndbClient.searchCandidates(query, Math.max(1, Math.min(10, limit)));
            } catch (Throwable t) {
                error = t.getMessage() == null || t.getMessage().trim().isEmpty() ? "VNDB 搜索失败" : t.getMessage();
            }
            List<VnMetadata> result = candidates == null
                    ? java.util.Collections.emptyList() : new java.util.ArrayList<>(candidates);
            String finalError = error;
            RxMainScheduler.post(() -> callback.onResult(result, finalError));
        });
    }

    /** 保存用户明确选择的 VNDB 候选；不自动覆盖游戏卡片，仍由“同步封面到卡片”控制。 */
    public static void saveSelectedVndbMetadataAsync(Context context, Game game, VnMetadata metadata,
                                                      Callback callback) {
        if (callback == null) return;
        if (context == null || game == null || game.id <= 0 || metadata == null) {
            callback.onResult(false);
            return;
        }
        Context app = context.getApplicationContext();
        AppExecutors.io().execute(() -> {
            boolean success = false;
            try {
                new MetadataRepository(app).saveVndb(game.id, metadata);
                success = true;
            } catch (Throwable ignored) {
            }
            boolean finalSuccess = success;
            RxMainScheduler.post(() -> callback.onResult(finalSuccess));
        });
    }

    /** 按用户输入的关键词搜索 Bangumi 候选，结果始终回调到主线程。 */
    public static void searchBangumiCandidatesAsync(Context context, String keyword, int limit,
                                                      CandidatesCallback callback) {
        if (callback == null) return;
        String query = keyword == null ? "" : keyword.trim();
        if (context == null || query.isEmpty()) {
            callback.onResult(java.util.Collections.emptyList(), "请输入搜索关键词");
            return;
        }
        String token = getBangumiToken(context);
        if (token == null || token.trim().isEmpty()) {
            callback.onResult(java.util.Collections.emptyList(), "请先在设置中配置 Bangumi Token");
            return;
        }
        boolean useMirror = MetadataController.SOURCE_BANGUMI_MIRROR.equals(getMetadataSource(context));
        AppExecutors.io().execute(() -> {
            List<VnMetadata> candidates = java.util.Collections.emptyList();
            String error = null;
            try {
                candidates = BangumiClient.searchCandidates(query, token, Math.max(1, Math.min(10, limit)), useMirror);
            } catch (Throwable t) {
                error = t.getMessage() == null || t.getMessage().trim().isEmpty() ? "Bangumi 搜索失败" : t.getMessage();
            }
            List<VnMetadata> result = candidates == null
                    ? java.util.Collections.emptyList() : new java.util.ArrayList<>(candidates);
            String finalError = error;
            RxMainScheduler.post(() -> callback.onResult(result, finalError));
        });
    }

    /** 保存用户明确选择的 Bangumi 候选。 */
    public static void saveSelectedBangumiMetadataAsync(Context context, Game game, VnMetadata metadata,
                                                         Callback callback) {
        if (callback == null) return;
        if (context == null || game == null || game.id <= 0 || metadata == null) {
            callback.onResult(false);
            return;
        }
        Context app = context.getApplicationContext();
        AppExecutors.io().execute(() -> {
            boolean success = false;
            try {
                new MetadataRepository(app).saveBangumi(game.id, metadata);
                success = true;
            } catch (Throwable ignored) {
            }
            boolean finalSuccess = success;
            RxMainScheduler.post(() -> callback.onResult(finalSuccess));
        });
    }

    /**
     * Returns the developer string for a game by consulting the available
     * metadata sources in order: VNDB, Bangumi, Ymgal. Returns empty string
     * when no metadata or developer is available.
     */
    public static String getDeveloperOf(Context context, long gameId) {
        if (context == null || gameId <= 0) return "";
        Context app = context.getApplicationContext();
        MetadataRepository metaRepo = new MetadataRepository(app);
        VnMetadata meta = metaRepo.getVndb(gameId);
        if (meta == null || meta.developer == null || meta.developer.trim().isEmpty()) {
            meta = metaRepo.getBangumi(gameId);
        }
        if (meta == null || meta.developer == null || meta.developer.trim().isEmpty()) {
            meta = metaRepo.getYmgal(gameId);
        }
        return meta == null || meta.developer == null ? "" : meta.developer.trim();
    }

    public static void syncCoverToGameAsync(Context context, Game game, Callback callback) {
        if (game == null) {
            callback.onResult(false);
            return;
        }
        Context app = context.getApplicationContext();
        AppExecutors.io().execute(() -> {
            boolean ok = false;
            try {
                MetadataRepository metaRepo = new MetadataRepository(app);
                VnMetadata meta = metaRepo.getVndb(game.id);
                if (meta != null && meta.coverUrl != null && !meta.coverUrl.trim().isEmpty()) {
                    String cover = LauncherCoverBridge.downloadCover(app, meta.coverUrl, "sync_cover_" + game.id);
                    if (cover != null) {
                        GameRepository repo = new GameRepository(app);
                        Game latest = repo.findById(game.id);
                        if (latest != null) {
                            latest.coverUri = cover;
                            latest.coverPersistUri = cover;
                            latest.coverSourceType = 1;
                            if (meta.chineseTitle != null && !meta.chineseTitle.isEmpty())
                                latest.title = meta.chineseTitle;
                            else if (meta.originalTitle != null && !meta.originalTitle.isEmpty())
                                latest.title = meta.originalTitle;
                            repo.update(latest);
                            ok = true;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            final boolean success = ok;
            RxMainScheduler.post(() -> callback.onResult(success));
        });
    }
}
