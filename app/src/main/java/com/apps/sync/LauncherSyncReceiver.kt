package com.apps.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 接收每晚 00:00 的备份闹钟，触发同步。 */
class LauncherSyncReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "LauncherSync"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.apps.ACTION_SYNC_BACKUP") return
        Log.d(TAG, "收到定时备份闹钟，开始执行备份")
        LauncherSyncScheduler.performBackup(context.applicationContext)
    }
}
