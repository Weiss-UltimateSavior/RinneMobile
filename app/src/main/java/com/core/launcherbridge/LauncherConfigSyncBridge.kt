package com.core.launcherbridge

import android.content.Context
import com.core.util.AppExecutors
import com.core.util.RxMainScheduler
import org.json.JSONObject

/**
 * 配置与游玩数据同步 API 桥接层。
 *
 * 从 [LauncherAuthBridge] 拆出，负责：
 * - Launcher 配置 JSON 同步（fetchConfig / uploadConfig）
 * - 账户游玩记录 SQL 云备份/恢复（fetchPlayData / uploadPlayData）
 *
 * 大数据量场景使用 [LauncherAuthHttpClient.getLarge] / [LauncherAuthHttpClient.putLarge]
 * 以获得更长的超时与更大的响应缓冲，并通过 [LauncherAuthHttpClient.MAX_PLAY_DATA_RESPONSE_BYTES]
 * 强制上限，防止异常服务端耗尽内存。
 */
object LauncherConfigSyncBridge {

    /**
     * 从服务端获取 Launcher 配置 JSON。
     */
    @JvmStatic
    fun fetchConfig(context: Context, callback: ConfigCallback) {
        AppExecutors.runOnIo {
            try {
                val token = LauncherAuthBridge.getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val response = LauncherAuthHttpClient.get("/auth/config", token)
                RxMainScheduler.post { callback.onSuccess(response) }
            } catch (t: Exception) {
                var msg = LauncherAuthHttpClient.parseErrorMessage(t, "获取配置失败")
                if (msg.contains("401")) { LauncherAuthBridge.expireSession(context); msg = "登录已过期，请重新登录" }
                val errMsg = msg
                RxMainScheduler.post { callback.onError(errMsg) }
            }
        }
    }

    /**
     * 上传 Launcher 配置到服务端。
     */
    @JvmStatic
    fun uploadConfig(context: Context, configJson: String, callback: ConfigCallback) {
        AppExecutors.runOnIo {
            try {
                val token = LauncherAuthBridge.getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val body = JSONObject(configJson)
                LauncherAuthHttpClient.put("/auth/config", body, token)
                RxMainScheduler.post { callback.onSuccess(configJson) }
            } catch (t: Exception) {
                var msg = LauncherAuthHttpClient.parseErrorMessage(t, "上传配置失败")
                if (msg.contains("401")) { LauncherAuthBridge.expireSession(context); msg = "登录已过期，请重新登录" }
                val errMsg = msg
                RxMainScheduler.post { callback.onError(errMsg) }
            }
        }
    }

    /**
     * 从服务端获取游玩记录 SQL。
     * 使用更长的超时和更大的响应缓冲，适配大数据量场景。
     */
    @JvmStatic
    fun fetchPlayData(context: Context, callback: PlayDataCallback) {
        AppExecutors.runOnIo {
            try {
                val token = LauncherAuthBridge.getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val response = LauncherAuthHttpClient.getLarge("/auth/config/play-data", token)
                val json = JSONObject(response ?: "{}")
                val playData = json.optString("play_data", "")
                RxMainScheduler.post { callback.onSuccess(playData) }
            } catch (t: Exception) {
                var msg = LauncherAuthHttpClient.parseErrorMessage(t, "获取游玩记录失败")
                if (msg.contains("401")) { LauncherAuthBridge.expireSession(context); msg = "登录已过期，请重新登录" }
                val errMsg = msg
                RxMainScheduler.post { callback.onError(errMsg) }
            }
        }
    }

    /**
     * 上传游玩记录 SQL 到服务端。
     * 使用更长的超时和更大的响应缓冲，适配大数据量场景。
     */
    @JvmStatic
    fun uploadPlayData(context: Context, playSql: String, callback: PlayDataCallback) {
        AppExecutors.runOnIo {
            try {
                val token = LauncherAuthBridge.getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                if (LauncherAuthHttpClient.utf8Length(playSql) > LauncherAuthHttpClient.MAX_PLAY_DATA_RESPONSE_BYTES) {
                    throw RuntimeException("游玩数据过大（最大允许 ${LauncherAuthHttpClient.MAX_PLAY_DATA_RESPONSE_BYTES} 字节）")
                }
                val body = JSONObject()
                body.put("play_data", playSql)
                LauncherAuthHttpClient.putLarge("/auth/config/play-data", body, token)
                RxMainScheduler.post { callback.onSuccess(playSql) }
            } catch (t: Exception) {
                var msg = LauncherAuthHttpClient.parseErrorMessage(t, "上传游玩记录失败")
                if (msg.contains("401")) { LauncherAuthBridge.expireSession(context); msg = "登录已过期，请重新登录" }
                val errMsg = msg
                RxMainScheduler.post { callback.onError(errMsg) }
            }
        }
    }
}
