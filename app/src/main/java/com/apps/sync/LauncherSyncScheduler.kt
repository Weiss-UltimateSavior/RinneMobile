package com.apps.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.apps.UserData.LauncherUserData
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge
import java.util.Calendar

/** Schedules the nightly Launcher configuration and play-data backup. */
object LauncherSyncScheduler {
    private const val TAG = "LauncherSync"
    private const val PREFS_NAME = "launcher_account_settings"
    private const val KEY_SYNC_CONFIG = "sync_config"
    private const val ACTION_SYNC_BACKUP = "com.apps.ACTION_SYNC_BACKUP"

    @JvmStatic
    fun updateSchedule(context: Context) {
        val enabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SYNC_CONFIG, false)
        if (enabled && LauncherAuthBridge.isLoggedIn(context)) scheduleNextBackup(context) else cancelSchedule(context)
    }

    private fun scheduleNextBackup(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val pendingIntent = pendingIntent(context)
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT -> alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
                )
                else -> alarmManager.set(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            }
        } catch (_: SecurityException) {
            Log.w(TAG, "无精确闹钟权限，使用不精确闹钟")
            alarmManager.set(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        }
    }

    private fun cancelSchedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(
            context, 0, Intent(context, LauncherSyncReceiver::class.java).setAction(ACTION_SYNC_BACKUP), flags
        )
    }

    /** Executes one backup. Exposed statically for [LauncherSyncReceiver]. */
    @JvmStatic
    fun performBackup(context: Context) {
        if (!LauncherAuthBridge.isLoggedIn(context)) return
        if (!context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SYNC_CONFIG, false)) return

        LauncherUserData.exportSettingsJson(context)?.let { settingsJson ->
            LauncherAuthBridge.uploadConfig(context, settingsJson, object : LauncherAuthBridge.ConfigCallback {
                override fun onSuccess(configJson: String) {
                    Log.d(TAG, "配置备份成功")
                }

                override fun onError(message: String) {
                    Log.w(TAG, "配置备份失败: $message")
                }
            })
        }

        val playData = LauncherUserData.exportCloudPlayData(context)
        if (playData.isNullOrBlank()) {
            Log.w(TAG, "本地数据导出失败，跳过游玩记录备份")
            scheduleNextBackup(context)
            return
        }
        LauncherAuthBridge.uploadPlayData(context, playData, object : LauncherAuthBridge.PlayDataCallback {
            override fun onSuccess(playSql: String) {
                Log.d(TAG, "游玩记录备份成功")
                scheduleNextBackup(context)
            }

            override fun onError(message: String) {
                Log.w(TAG, "游玩记录备份失败: $message")
                scheduleNextBackup(context)
            }
        })
    }
}
