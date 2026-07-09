package com.apps;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.apps.UserData.LauncherUserData;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;

import java.util.Calendar;

/**
 * 每晚 00:00 自动备份用户配置到服务端。
 * 开关由 LauncherAccountSettingsActivity 的 sync_config 控制。
 */
public class LauncherSyncScheduler {

    private static final String TAG = "LauncherSync";
    private static final String PREFS_NAME = "launcher_account_settings";
    private static final String KEY_SYNC_CONFIG = "sync_config";

    /**
     * 根据开关状态注册或取消定时备份。
     */
    public static void updateSchedule(Context context) {
        boolean enabled = context.getSharedPreferences(PREFS_NAME, 0)
                .getBoolean(KEY_SYNC_CONFIG, false);
        if (enabled && LauncherAuthBridge.isLoggedIn(context)) {
            scheduleNextBackup(context);
        } else {
            cancelSchedule(context);
        }
    }

    /**
     * 注册下一个 00:00 的闹钟。
     */
    private static void scheduleNextBackup(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        // 如果当前已过 00:00，设为明天 00:00
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        PendingIntent pi = getPendingIntent(context);
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            } else if (Build.VERSION.SDK_INT >= 19) {
                am.setExact(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            }
        } catch (SecurityException e) {
            // Android 12+ 未授予精确闹钟权限时，降级为不精确闹钟
            Log.w(TAG, "无精确闹钟权限，使用不精确闹钟");
            am.set(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        }
    }

    private static void cancelSchedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(getPendingIntent(context));
    }

    private static PendingIntent getPendingIntent(Context context) {
        Intent intent = new Intent(context, LauncherSyncReceiver.class);
        intent.setAction("com.apps.ACTION_SYNC_BACKUP");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags);
    }

    /**
     * 执行一次备份：导出配置 + 游玩记录 → 上传到服务端。
     */
    static void performBackup(Context context) {
        if (!LauncherAuthBridge.isLoggedIn(context)) return;
        if (!context.getSharedPreferences(PREFS_NAME, 0).getBoolean(KEY_SYNC_CONFIG, false)) return;

        // 导出设置 JSON
        String settingsJson = LauncherUserData.exportSettingsJson(context);
        if (settingsJson != null) {
            LauncherAuthBridge.uploadConfig(context, settingsJson, new LauncherAuthBridge.ConfigCallback() {
                @Override
                public void onSuccess(String configJson) {
                    Log.d(TAG, "配置备份成功");
                }

                @Override
                public void onError(String message) {
                    Log.w(TAG, "配置备份失败: " + message);
                }
            });
        }

        // 导出游玩记录 SQL
        LauncherUserData.exportAll(context); // 先写入本地文件
        String playSql = LauncherUserData.readExportedSql(context);
        if (playSql != null && !playSql.trim().isEmpty()) {
            LauncherAuthBridge.uploadPlayData(context, playSql, new LauncherAuthBridge.PlayDataCallback() {
                @Override
                public void onSuccess(String playData) {
                    Log.d(TAG, "游玩记录备份成功");
                    // 备份成功后注册下一次
                    scheduleNextBackup(context);
                }

                @Override
                public void onError(String message) {
                    Log.w(TAG, "游玩记录备份失败: " + message);
                    scheduleNextBackup(context);
                }
            });
        } else {
            scheduleNextBackup(context);
        }
    }
}
