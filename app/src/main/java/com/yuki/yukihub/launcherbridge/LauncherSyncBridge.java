package com.yuki.yukihub.launcherbridge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.yuki.yukihub.sync.SyncManager;

public final class LauncherSyncBridge {
    private LauncherSyncBridge() {
    }

    public static boolean isConfigured(Context context) {
        return context != null && new SyncManager(context.getApplicationContext()).isConfigured();
    }

    public static long lastSyncTime(Context context) {
        if (context == null) return 0L;
        return new SyncManager(context.getApplicationContext()).getLastSyncTime();
    }

    public static SyncManager.SyncConfig getConfig(Context context) {
        if (context == null) return new SyncManager.SyncConfig("", "", "", false);
        return new SyncManager(context.getApplicationContext()).getConfig();
    }

    public static void saveConfig(Context context, String serverUrl, String username, String password, boolean autoSync) {
        if (context == null) return;
        new SyncManager(context.getApplicationContext()).saveConfig(serverUrl, username, password, autoSync);
    }

    public static boolean testConnection(Context context) {
        if (context == null) return false;
        return new SyncManager(context.getApplicationContext()).testConnection();
    }

    public static boolean isAutoSyncEnabled(Context context) {
        if (context == null) return false;
        return new SyncManager(context.getApplicationContext()).isAutoSyncEnabled();
    }

    public static void syncNow(Context context, Callback callback) {
        if (context == null) {
            if (callback != null) callback.onError("上下文不可用");
            return;
        }
        Handler mainHandler = new Handler(Looper.getMainLooper());
        SyncManager syncManager = new SyncManager(context.getApplicationContext());
        syncManager.sync(new SyncManager.SyncListener() {
            @Override
            public void onSyncStart() {
                post(mainHandler, () -> {
                    if (callback != null) callback.onStart();
                });
            }

            @Override
            public void onProgress(String item, boolean changed) {
                post(mainHandler, () -> {
                    if (callback != null) callback.onProgress(item, changed);
                });
            }

            @Override
            public int onConflict(SyncManager.Conflict conflict) {
                post(mainHandler, () -> {
                    if (callback != null) callback.onError("检测到同步冲突，请打开同步中心处理");
                });
                return SyncManager.RESOLVE_CANCEL;
            }

            @Override
            public void onSyncComplete(SyncManager.SyncResult result) {
                post(mainHandler, () -> {
                    if (callback != null) callback.onComplete(summary(result));
                });
            }

            @Override
            public void onError(String error) {
                post(mainHandler, () -> {
                    if (callback != null) callback.onError(error == null || error.trim().isEmpty() ? "同步失败" : error);
                });
            }
        });
    }

    private static void post(Handler handler, Runnable runnable) {
        if (handler == null || runnable == null) return;
        handler.post(runnable);
    }

    private static String summary(SyncManager.SyncResult result) {
        if (result == null) return "同步完成";
        if (result.cancelled) return "同步已取消";
        if (result.uploaded) return "已上传本地修改";
        if (result.downloaded) return "已下载云端修改";
        if (result.merged) return "已合并同步数据";
        if (result.noChanges) return "云端与本地已是最新";
        return "同步完成";
    }

    public interface Callback {
        void onStart();
        void onProgress(String item, boolean changed);
        void onComplete(String message);
        void onError(String error);
    }
}
