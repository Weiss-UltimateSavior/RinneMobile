package com.yuki.yukihub.launcherbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.data.MetadataRepository;
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
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(success));
        });
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
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(success));
        });
    }
}
