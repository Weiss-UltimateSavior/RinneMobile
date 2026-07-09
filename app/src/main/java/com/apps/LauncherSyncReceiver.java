package com.apps;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 接收每晚 00:00 的备份闹钟，触发同步。
 */
public class LauncherSyncReceiver extends android.content.BroadcastReceiver {
    private static final String TAG = "LauncherSync";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"com.apps.ACTION_SYNC_BACKUP".equals(intent.getAction())) return;
        Log.d(TAG, "收到定时备份闹钟，开始执行备份");
        LauncherSyncScheduler.performBackup(context.getApplicationContext());
    }
}
