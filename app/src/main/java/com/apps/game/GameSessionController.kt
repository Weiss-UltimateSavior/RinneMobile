package com.apps.game

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.apps.HDModel.LauncherDialogRouter
import com.apps.LauncherPreferences
import com.core.R
import com.core.launcher.EnginePackages
import com.core.launcherbridge.LauncherAuthBridge
import com.core.launcherbridge.LauncherGameLaunchBridge
import com.core.launcherbridge.PlaySession
import com.core.launcherbridge.PlaySessionCallback
import com.core.model.Game
import com.core.userdata.LauncherUserData
import com.core.util.AppExecutors
import com.core.util.RxMainQueue
import java.util.Locale

/**
 * 游戏会话控制器：管理本地 session 与服务端 session 的启动、心跳、收尾。
 *
 * 来源：LauncherLibraryFragment 的 launchGameDirectly /
 * finishDirectPlaySessionIfNeeded / startServerPlaySession / heartbeatServerPlaySession /
 * finishServerPlaySession / resolveLaunchTypeForRecord / playSessionHeartbeat 共 7 处方法。
 *
 * 状态归属：原 Fragment 持有的 runningSessionId / runningGameId 等运行态字段全部迁移至本控制器。
 * Fragment 通过 Listener 接口接收 UI 刷新回调。
 */
class GameSessionController(
    context: Context,
    private val mainQueue: RxMainQueue,
    private val listener: Listener
) {

    interface Listener {
        /** 就地刷新单张卡片，避免重置分页与滑动位置。 */
        fun reloadGame(gameId: Long)

        /** 整体重载游戏列表。 */
        fun reloadAllGames()
    }

    /**
     * 用于没有 Fragment 宿主的启动入口（例如桌面快捷方式）。回调在主线程执行。
     */
    fun interface LaunchListener {
        fun onResult(result: LauncherGameLaunchBridge.LaunchResult)
    }

    private val appContext: Context = context.applicationContext

    private var runningSessionId = -1L
    private var runningGameId = -1L
    private var runningGameTitle = ""
    private var runningSessionStart = 0L
    private var runningServerSessionId = ""
    private var runningLaunchType = "external"

    private val playSessionHeartbeat = object : Runnable {
        override fun run() {
            heartbeatServerPlaySession()
            mainQueue.postDelayed(this, PLAY_SESSION_HEARTBEAT_MS)
        }
    }

    /** 是否有正在进行的游玩会话（用于 onResume 判断是否需要收尾）。 */
    fun hasActiveSession(): Boolean {
        return runningSessionId > 0L
    }

    /** 启动游戏：调用 LauncherGameLaunchBridge.launchAsync，成功后开启服务端会话。 */
    fun launchGameDirectly(fragment: Fragment, game: Game?) {
        if (game == null) return
        val context = fragment.context
        if (context == null) return
        launchGame(context, game) { result ->
            if (!fragment.isAdded) return@launchGame
            if (result.activeGameConflict) {
                GameActionMenuFactory.showActiveGameInfo(fragment.requireContext(), result.activeGameTitle)
            } else if (!result.success && !result.message.trim().isEmpty()) {
                Toast.makeText(fragment.requireContext(), result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 统一的无 Fragment 启动入口。成功后始终接管本地与服务端会话，
     * 以便首页、横屏旧页和桌面快捷方式不会绕开实时游玩时间链路。
     *
     * Artemis 自动补丁策略为 ask 时，先弹「是否立即解包」确认对话框：确认后解包再启动，
     * 取消则不解包直接启动；找不到 Activity 宿主（如桌面快捷方式）时无法展示对话框，
     * 回退为静默解包后启动，避免功能回退。
     */
    fun launchGame(context: Context, game: Game?, callback: LaunchListener?) {
        if (game == null) return
        AppExecutors.runOnIo {
            val needsConfirm = LauncherGameLaunchBridge.needsArtemisBasePatchConfirmation(context, game)
            mainQueue.post {
                if (isContextDestroyed(context)) return@post
                if (needsConfirm) {
                    val activity = findActivityContext(context)
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        showArtemisPatchConfirm(activity, game, callback)
                    } else {
                        applyArtemisPatchThenLaunch(context, game, callback)
                    }
                } else {
                    doLaunchGame(context, game, callback)
                }
            }
        }
    }

    /** 沿 ContextWrapper 链向上查找最近的 Activity 宿主；无则返回 null。 */
    private fun findActivityContext(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    /** 回主线程守卫：宿主 Activity 已 finishing/destroyed 时返回 true，不再执行 UI 联动。 */
    private fun isContextDestroyed(context: Context): Boolean {
        val activity = findActivityContext(context) ?: return false
        return activity.isFinishing || activity.isDestroyed
    }

    /** ask 策略弹「是否立即解包」确认；取消则不解包直接启动。 */
    private fun showArtemisPatchConfirm(activity: Activity, game: Game, callback: LaunchListener?) {
        LauncherDialogRouter.showLongMessageConfirm(
            activity,
            activity.getString(R.string.artemis_patch_confirm_title),
            activity.getString(R.string.artemis_patch_confirm_message),
            activity.getString(R.string.artemis_patch_confirm_unpack),
            Runnable { applyArtemisPatchThenLaunch(activity, game, callback) },
            Runnable { doLaunchGame(activity, game, callback) },
        )
    }

    /** 在 IO 线程解包基础补丁后回到主线程启动游戏。 */
    private fun applyArtemisPatchThenLaunch(context: Context, game: Game, callback: LaunchListener?) {
        AppExecutors.runOnIo {
            LauncherGameLaunchBridge.applyArtemisBasePatch(game)
            mainQueue.post {
                if (isContextDestroyed(context)) return@post
                doLaunchGame(context, game, callback)
            }
        }
    }

    /** 实际发起启动（原 launchGame 主流程）。 */
    private fun doLaunchGame(context: Context, game: Game, callback: LaunchListener?) {
        LauncherGameLaunchBridge.launchAsync(context, game, object : LauncherGameLaunchBridge.LaunchCallback {
            override fun onResult(result: LauncherGameLaunchBridge.LaunchResult) {
                if (result.success) {
                    runningSessionId = result.sessionId
                    runningGameId = game.id
                    runningGameTitle = GameMetadataFormatter.safeTitle(game)
                    runningSessionStart = System.currentTimeMillis()
                    runningLaunchType = resolveLaunchTypeForRecord(game)
                    startServerPlaySession(game, result.sessionId)
                }
                callback?.onResult(result)
            }
        })
    }

    /** 收尾当前游玩会话：写入本地 play_sessions + 结束服务端 session + 通知 UI 刷新。 */
    fun finishDirectPlaySessionIfNeeded(fragment: Fragment) {
        if (runningSessionId <= 0L) return
        val context = fragment.context
        if (context == null) return
        finishDirectPlaySessionIfNeeded(context)
    }

    /**
     * 无 Fragment 的会话收尾入口，供 Activity/快捷方式宿主在回到前台时调用。
     */
    fun finishDirectPlaySessionIfNeeded(context: Context) {
        if (runningSessionId <= 0L) return
        // 1) 主项目会话收尾（写入 play_sessions 表 + 累加 total_play_time）
        LauncherGameLaunchBridge.finishSession(context, runningSessionId, MIN_PLAY_SESSION_MS, MAX_PLAY_SESSION_MS)
        // 2) 线上实际游玩时长只结束服务端 session，不提交本地 duration。
        finishServerPlaySession(runningSessionId)
        // 捕获刚结束会话的游戏 id，用于就地刷新单张卡片；
        // 不能调用 loadGames()，否则会重置分页并丢失滑动位置。
        val finishedGameId = runningGameId
        runningSessionId = -1L
        runningGameId = -1L
        runningGameTitle = ""
        runningSessionStart = 0L
        runningServerSessionId = ""
        runningLaunchType = "external"
        if (finishedGameId > 0L) {
            listener.reloadGame(finishedGameId)
        } else {
            listener.reloadAllGames()
        }
    }

    private fun startServerPlaySession(game: Game?, localSessionId: Long) {
        if (game == null || localSessionId <= 0L) return
        if (!LauncherAuthBridge.isLoggedIn(appContext)) return
        val prefs = appContext.getSharedPreferences(LauncherPreferences.ACCOUNT_SETTINGS_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("realtime_playtime", true)) return
        val deviceId = LauncherUserData.getRealtimePlaytimeDeviceId(appContext)
        LauncherAuthBridge.startPlayTimeSession(
            appContext, game.id, GameMetadataFormatter.safeTitle(game), deviceId,
            object : PlaySessionCallback {
                override fun onSuccess(session: PlaySession) {
                    // 网络回调线程与主线程并发访问 runningSessionId/runningServerSessionId
                    // 时存在 check-then-act 竞态：若主线程在检查与赋值之间将
                    // runningSessionId 置为 -1（finishDirectPlaySessionIfNeeded），
                    // 仍可能写入已结束会话的 serverSessionId，导致后续心跳发往无效会话。
                    // 切回主线程执行可保证状态读写的串行化与原子性。
                    mainQueue.post {
                        if (session.sessionId.trim().isEmpty()) return@post
                        if (runningSessionId != localSessionId) return@post
                        runningServerSessionId = session.sessionId
                        LauncherUserData.rememberServerPlaySession(
                            appContext, localSessionId, game.id,
                            GameMetadataFormatter.safeTitle(game), session.sessionId
                        )
                        scheduleServerPlayHeartbeat()
                    }
                }

                override fun onError(message: String) {
                    // 静默失败：不能回退到本地 duration 上传。
                }
            }
        )
    }

    private fun scheduleServerPlayHeartbeat() {
        mainQueue.removeCallbacks(playSessionHeartbeat)
        mainQueue.postDelayed(playSessionHeartbeat, PLAY_SESSION_HEARTBEAT_MS)
    }

    private fun heartbeatServerPlaySession() {
        if (runningServerSessionId.isNullOrEmpty() || runningServerSessionId.trim().isEmpty()) return
        LauncherAuthBridge.heartbeatPlayTimeSession(
            appContext, runningServerSessionId,
            object : PlaySessionCallback {
                override fun onSuccess(session: PlaySession) {
                    // 心跳 onSuccess 无需操作。
                }

                override fun onError(message: String) {
                    // 心跳 onError 失败等待下次心跳重试，静默忽略。
                }
            }
        )
    }

    private fun finishServerPlaySession(localSessionId: Long) {
        mainQueue.removeCallbacks(playSessionHeartbeat)
        val serverSessionId = if (runningServerSessionId.isNullOrEmpty() || runningServerSessionId.trim().isEmpty()) {
            LauncherUserData.findServerPlaySessionId(appContext, localSessionId)
        } else {
            runningServerSessionId
        }
        if (serverSessionId.isNullOrEmpty() || serverSessionId.trim().isEmpty()) return
        LauncherAuthBridge.finishPlayTimeSession(
            appContext, serverSessionId,
            object : PlaySessionCallback {
                override fun onSuccess(session: PlaySession) {
                    // 该回调已回主线程，removeServerPlaySession 内部 synchronized + 读写 JSON 文件，
                    // 属主线程阻塞 IO；回调仅做文件移除、无 UI 更新，下沉到单线程池执行安全。
                    AppExecutors.runOnSingle { LauncherUserData.removeServerPlaySession(appContext, localSessionId) }
                }

                override fun onError(message: String) {
                    // finish 可重试；失败时保留 session_id 映射，等待后续恢复流程处理。
                }
            }
        )
    }

    /** 在 Fragment.onDestroyView 中调用，移除心跳回调避免泄漏。 */
    fun cleanup() {
        mainQueue.removeCallbacks(playSessionHeartbeat)
    }

    companion object {
        /** 会话收尾与心跳间隔（与原 Fragment 常量保持一致）。 */
        private const val MIN_PLAY_SESSION_MS = 0L
        private const val MAX_PLAY_SESSION_MS = 12L * 60L * 60L * 1000L
        private const val PLAY_SESSION_HEARTBEAT_MS = 60L * 1000L

        /**
         * 与 LauncherGameLaunchBridge.resolveLaunchType 保持一致的启动类型推导，
         * 仅用于实际游玩记录的 launchType 字段标记，便于后续上传区分启动方式。
         */
        @JvmStatic
        fun resolveLaunchTypeForRecord(game: Game?): String {
            if (game == null) return "external"
            val emulatorPackage = game.emulatorPackage ?: return "external"
            val pkg = emulatorPackage.trim().lowercase(Locale.ROOT)
            if (EnginePackages.isInternalKrkr(pkg)) return EnginePackages.INTERNAL_KRKR
            // ons/tyrano 有意不用共享谓词（isInternalOns/Tyrano 含 com.yuki.yukihub.* 历史别名）：
            // 本方法只标记游玩记录 launchType，保持与既有记录格式一致，避免引入历史别名匹配。
            if (pkg.startsWith(EnginePackages.INTERNAL_ONS) || pkg == EnginePackages.LEGACY_ONS) return EnginePackages.INTERNAL_ONS
            if (pkg.startsWith(EnginePackages.INTERNAL_TYRANO) || pkg == EnginePackages.LEGACY_TYRANO) return EnginePackages.INTERNAL_TYRANO
            if (EnginePackages.isInternalArtemis(pkg)) return pkg
            if (pkg.startsWith(EnginePackages.INTERNAL_PSP) || pkg == EnginePackages.EXTERNAL_PPSSPP) return EnginePackages.INTERNAL_PSP
            if (pkg.startsWith(EnginePackages.INTERNAL_CITRA) || pkg == EnginePackages.EXTERNAL_AZAHAR) return EnginePackages.INTERNAL_CITRA
            return "external"
        }
    }
}
