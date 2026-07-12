package com.yuki.yukihub.launcherbridge;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import com.yuki.yukihub.sync.SyncManager;
import com.yuki.yukihub.util.RxMainScheduler;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public final class LauncherSyncBridge {
    private static final int MAX_LOCAL_BACKUP_BYTES = SyncManager.MAX_LOCAL_BACKUP_BYTES;
    private LauncherSyncBridge() {
    }

    public static boolean isConfigured(Context context) {
        return context != null && new SyncManager(context.getApplicationContext()).isConfigured();
    }

    public static long lastSyncTime(Context context) {
        if (context == null) return 0L;
        return new SyncManager(context.getApplicationContext()).getLastSyncTime();
    }

    public static SyncConfigSnapshot getConfig(Context context) {
        if (context == null) return new SyncConfigSnapshot("", "", "", false);
        SyncManager.SyncConfig source = new SyncManager(context.getApplicationContext()).getConfig();
        if (source == null) return new SyncConfigSnapshot("", "", "", false);
        return new SyncConfigSnapshot(source.serverUrl, source.username, source.password, source.autoSync);
    }

    /** Immutable snapshot of WebDAV sync configuration. Mirrors SyncManager.SyncConfig. */
    public static final class SyncConfigSnapshot {
        public final String serverUrl;
        public final String username;
        public final String password;
        public final boolean autoSync;

        public SyncConfigSnapshot(String serverUrl, String username, String password, boolean autoSync) {
            this.serverUrl = serverUrl;
            this.username = username;
            this.password = password;
            this.autoSync = autoSync;
        }
    }

    public static void saveConfig(Context context, String serverUrl, String username, String password, boolean autoSync) {
        if (context == null) return;
        new SyncManager(context.getApplicationContext()).saveConfig(serverUrl, username, password, autoSync);
    }

    public static JSONObject exportLocalBackup(Context context) throws Exception {
        if (context == null) throw new Exception("上下文不可用");
        JSONObject root = new SyncManager(context.getApplicationContext()).exportSnapshotForLocalBackup();
        root.put("created_at", System.currentTimeMillis());
        root.put("backup_type", "local_full");
        root.put("note", "Local backup uses the same schema as WebDAV sync, but keeps full play session history.");
        return root;
    }

    public static void importLocalBackup(Context context, JSONObject snapshot) throws Exception {
        if (context == null) throw new Exception("上下文不可用");
        if (snapshot == null) throw new Exception("备份内容为空");
        if (!"YukiHub".equals(snapshot.optString("app", ""))) {
            throw new Exception("不是有效的 YukiHub 备份");
        }
        new SyncManager(context.getApplicationContext()).importSnapshotFromLocalBackup(snapshot);
    }

    public static void importLocalBackupFromUri(Context context, Uri uri) throws Exception {
        if (context == null) throw new Exception("上下文不可用");
        if (uri == null) throw new Exception("备份文件不可用");
        String text = readTextFromUri(context, uri);
        JSONObject root = new JSONObject(text);
        importLocalBackup(context, root);
    }

    /**
     * 导出用于账户云备份的结构化快照。与 {@link #exportLocalBackup} 不同：
     * 不附加 created_at/note 字段，backup_type 标记为 launcher_cloud_play_v2，
     * 供 Launcher 账户系统作为可恢复的云备份 payload 使用。
     */
    public static JSONObject exportCloudSnapshot(Context context) {
        if (context == null) return null;
        try {
            JSONObject root = new SyncManager(context.getApplicationContext()).exportSnapshotForLocalBackup();
            root.put("backup_type", "launcher_cloud_play_v2");
            return root;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 导入账户云备份 JSON。仅接受 YukiHub schema 且包含 games/play_sessions 数组的快照。
     *
     * @return 成功导入返回 true；上下文无效、格式不符或导入异常返回 false
     */
    public static boolean importCloudSnapshot(Context context, JSONObject root) {
        if (context == null || root == null) return false;
        try {
            if (!"YukiHub".equals(root.optString("app", ""))) return false;
            if (root.optJSONArray("games") == null || root.optJSONArray("play_sessions") == null) return false;
            new SyncManager(context.getApplicationContext()).importSnapshotFromLocalBackup(root);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String readTextFromUri(Context context, Uri uri) throws Exception {
        long declaredLength = -1L;
        try (AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (descriptor != null) declaredLength = descriptor.getLength();
        } catch (Exception ignored) {
            // Some document providers cannot report a length; the stream limit below remains authoritative.
        }
        if (declaredLength > MAX_LOCAL_BACKUP_BYTES) {
            throw new Exception("本地备份文件过大（文件声明 " + declaredLength + " 字节，最大允许 " + MAX_LOCAL_BACKUP_BYTES + " 字节）");
        }
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("openInputStream failed");
            byte[] buf = new byte[8192];
            long total = 0;
            int len;
            while ((len = in.read(buf)) != -1) {
                total += len;
                if (total > MAX_LOCAL_BACKUP_BYTES) {
                    throw new Exception("本地备份文件过大（最大允许 " + MAX_LOCAL_BACKUP_BYTES + " 字节）");
                }
                bos.write(buf, 0, len);
            }
            return bos.toString("UTF-8");
        }
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
        SyncManager syncManager = new SyncManager(context.getApplicationContext());
        syncManager.sync(new SyncManager.SyncListener() {
            @Override
            public void onSyncStart() {
                post(() -> {
                    if (callback != null) callback.onStart();
                });
            }

            @Override
            public void onProgress(String item, boolean changed) {
                post(() -> {
                    if (callback != null) callback.onProgress(item, changed);
                });
            }

            @Override
            public int onConflict(SyncManager.Conflict conflict) {
                post(() -> {
                    if (callback != null) callback.onError("检测到同步冲突，请打开同步中心处理");
                });
                return SyncManager.RESOLVE_CANCEL;
            }

            @Override
            public void onSyncComplete(SyncManager.SyncResult result) {
                post(() -> {
                    if (callback != null) callback.onComplete(summary(result));
                });
            }

            @Override
            public void onError(String error) {
                post(() -> {
                    if (callback != null) callback.onError(error == null || error.trim().isEmpty() ? "同步失败" : error);
                });
            }
        });
    }

    private static void post(Runnable runnable) {
        if (runnable != null) RxMainScheduler.post(runnable);
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
