package com.core.launcherbridge

import android.content.Context
import com.core.R
import com.core.util.AppExecutors
import com.core.util.RxMainScheduler
import org.json.JSONArray
import org.json.JSONObject

/**
 * 游玩时长统计 API 桥接层。
 *
 * 从 [LauncherAuthBridge] 拆出，负责：
 * - 服务端游玩会话生命周期（startPlayTimeSession / heartbeatPlayTimeSession / finishPlayTimeSession）
 * - 旧版游玩时长上传与拉取（uploadPlayTime / fetchPlayTime，仅保留兼容）
 * - 全站排行榜与个人排名（fetchPlayTimeLeaderboard / fetchMyPlayTimeRank）
 */
object LauncherPlayTimeBridge {

    @JvmStatic
    fun startPlayTimeSession(context: Context, gameId: Long, gameTitle: String?, deviceId: String?,
                             callback: PlaySessionCallback) {
        AppExecutors.runOnIo {
            try {
                val token = LauncherAuthBridge.getToken(context)
                if (token.isEmpty()) throw RuntimeException(context.getString(R.string.core_not_signed_in))
                val body = JSONObject()
                body.put("gameId", gameId)
                body.put("gameTitle", gameTitle ?: "")
                body.put("deviceId", deviceId ?: "")
                val session = parsePlaySession(JSONObject(LauncherAuthHttpClient.post("/auth/play-time/sessions/start", body, token)))
                RxMainScheduler.post { callback.onSuccess(session) }
            } catch (t: Throwable) {
                var msg = LauncherAuthHttpClient.parseErrorMessage(
                    t,
                    context.getString(R.string.core_play_session_start_failed),
                )
                if (msg.contains("401")) {
                    LauncherAuthBridge.expireSession(context)
                    msg = context.getString(R.string.core_session_expired)
                }
                val errMsg = msg
                RxMainScheduler.post { callback.onError(errMsg) }
            }
        }
    }

    @JvmStatic
    fun heartbeatPlayTimeSession(context: Context, sessionId: String, callback: PlaySessionCallback) {
        postPlayTimeSessionEvent(
            context,
            sessionId,
            "heartbeat",
            context.getString(R.string.core_play_session_heartbeat_failed),
            callback,
        )
    }

    @JvmStatic
    fun finishPlayTimeSession(context: Context, sessionId: String, callback: PlaySessionCallback) {
        postPlayTimeSessionEvent(
            context,
            sessionId,
            "finish",
            context.getString(R.string.core_play_session_finish_failed),
            callback,
        )
    }

    private fun postPlayTimeSessionEvent(context: Context, sessionId: String?, action: String,
                                         fallback: String, callback: PlaySessionCallback) {
        AppExecutors.runOnIo {
            try {
                val token = LauncherAuthBridge.getToken(context)
                if (token.isEmpty()) throw RuntimeException(context.getString(R.string.core_not_signed_in))
                if (sessionId.isNullOrBlank()) {
                    throw RuntimeException(context.getString(R.string.core_session_not_found))
                }
                val session = parsePlaySession(JSONObject(LauncherAuthHttpClient.postNoBody(
                    "/auth/play-time/sessions/${sessionId.trim()}/$action",
                    token)))
                RxMainScheduler.post { callback.onSuccess(session) }
            } catch (t: Throwable) {
                var msg = LauncherAuthHttpClient.parseErrorMessage(t, fallback)
                if (msg.contains("401")) {
                    LauncherAuthBridge.expireSession(context)
                    msg = context.getString(R.string.core_session_expired)
                }
                val errMsg = msg
                RxMainScheduler.post { callback.onError(errMsg) }
            }
        }
    }

    private fun parsePlaySession(json: JSONObject): PlaySession = PlaySession(
        json.optString("session_id", ""),
        json.optLong("game_id"),
        json.optString("status", ""),
        json.optLong("started_at"),
        json.optLong("last_heartbeat_at"),
        json.optLong("ended_at"),
        json.optLong("duration_ms")
    )

    /**
     * 旧版实际游玩记录上传入口，仅保留兼容。
     * 新流程必须使用 startPlayTimeSession/heartbeatPlayTimeSession/finishPlayTimeSession，
     * 不再向 /auth/play-time 提交前端计算的 duration。
     *
     * @param records 游玩记录列表（每条为 JSONObject，字段与 LauncherUserData.appendPlayRecord 一致）
     * @param callback 回调：onSuccess 返回服务端累计统计 JSON 数组字符串
     */
    @JvmStatic
    fun uploadPlayTime(context: Context, records: List<JSONObject>?, callback: PlayTimeCallback) {
        AppExecutors.runOnIo {
            try {
                val token = LauncherAuthBridge.getToken(context)
                if (token.isEmpty()) throw RuntimeException(context.getString(R.string.core_not_signed_in))
                if (records.isNullOrEmpty()) {
                    throw RuntimeException(context.getString(R.string.core_records_empty))
                }
                val body = JSONObject()
                val arr = JSONArray()
                for (r in records) arr.put(r)
                body.put("records", arr)
                val response = LauncherAuthHttpClient.post("/auth/play-time", body, token)
                RxMainScheduler.post { callback.onSuccess(response) }
            } catch (t: Throwable) {
                var msg = LauncherAuthHttpClient.parseErrorMessage(
                    t,
                    context.getString(R.string.core_play_time_upload_failed),
                )
                if (msg.contains("401")) {
                    LauncherAuthBridge.expireSession(context)
                    msg = context.getString(R.string.core_session_expired)
                }
                val errMsg = msg
                RxMainScheduler.post { callback.onError(errMsg) }
            }
        }
    }

    /**
     * 获取当前用户所有游戏的累计游玩时长统计（按时长降序）。
     *
     * @param callback 回调：onSuccess 返回 JSON 数组字符串，每项含 game_id/game_title/total_duration_ms/play_count/last_played_at
     */
    @JvmStatic
    fun fetchPlayTime(context: Context, callback: PlayTimeCallback) {
        AppExecutors.runOnIo {
            try {
                val token = LauncherAuthBridge.getToken(context)
                if (token.isEmpty()) throw RuntimeException(context.getString(R.string.core_not_signed_in))
                val response = LauncherAuthHttpClient.get("/auth/play-time", token)
                RxMainScheduler.post { callback.onSuccess(response) }
            } catch (t: Throwable) {
                var msg = LauncherAuthHttpClient.parseErrorMessage(
                    t,
                    context.getString(R.string.core_play_time_fetch_failed),
                )
                if (msg.contains("401")) {
                    LauncherAuthBridge.expireSession(context)
                    msg = context.getString(R.string.core_session_expired)
                }
                val errMsg = msg
                RxMainScheduler.post { callback.onError(errMsg) }
            }
        }
    }

    /** 获取全站游玩时长前 15 名，仅使用普通用户鉴权。 */
    @JvmStatic
    fun fetchPlayTimeLeaderboard(context: Context, callback: LeaderboardCallback) {
        AppExecutors.runOnIo {
            try {
                val token = LauncherAuthBridge.getToken(context)
                if (token.isEmpty()) throw RuntimeException(context.getString(R.string.core_not_signed_in))
                val array = JSONArray(LauncherAuthHttpClient.get("/auth/play-time/leaderboard", token))
                val entries = ArrayList<LeaderboardEntry>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    entries.add(LeaderboardEntry(item.optInt("rank"), item.optString("username"), item.optLong("total_duration_ms")))
                }
                RxMainScheduler.post { callback.onSuccess(entries) }
            } catch (t: Throwable) {
                var msg = LauncherAuthHttpClient.parseErrorMessage(
                    t,
                    context.getString(R.string.core_play_time_leaderboard_failed),
                )
                if (msg.contains("401")) {
                    LauncherAuthBridge.expireSession(context)
                    msg = context.getString(R.string.core_session_expired)
                }
                val errMsg = msg
                RxMainScheduler.post { callback.onError(errMsg) }
            }
        }
    }

    /** 获取当前用户的全站游玩时长排名。 */
    @JvmStatic
    fun fetchMyPlayTimeRank(context: Context, callback: MyRankCallback) {
        AppExecutors.runOnIo {
            try {
                val token = LauncherAuthBridge.getToken(context)
                if (token.isEmpty()) throw RuntimeException(context.getString(R.string.core_not_signed_in))
                val item = JSONObject(LauncherAuthHttpClient.get("/auth/play-time/rank", token))
                val rank = MyRank(item.optInt("rank"), item.optLong("total_duration_ms"))
                RxMainScheduler.post { callback.onSuccess(rank) }
            } catch (t: Throwable) {
                var msg = LauncherAuthHttpClient.parseErrorMessage(
                    t,
                    context.getString(R.string.core_play_time_rank_failed),
                )
                if (msg.contains("401")) {
                    LauncherAuthBridge.expireSession(context)
                    msg = context.getString(R.string.core_session_expired)
                }
                val errMsg = msg
                RxMainScheduler.post { callback.onError(errMsg) }
            }
        }
    }
}
