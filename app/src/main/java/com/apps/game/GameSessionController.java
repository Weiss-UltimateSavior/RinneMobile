package com.apps.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.core.userdata.LauncherUserData;
import com.core.launcherbridge.LauncherAuthBridge;
import com.core.launcherbridge.LauncherGameLaunchBridge;
import com.core.launcherbridge.PlaySession;
import com.core.launcherbridge.PlaySessionCallback;
import com.core.model.Game;
import com.core.util.RxMainQueue;

import java.util.Locale;

/**
 * 游戏会话控制器：管理本地 session 与服务端 session 的启动、心跳、收尾。
 *
 * 来源：LauncherLibraryFragment / PadManageFragment 重复的 launchGameDirectly /
 * finishDirectPlaySessionIfNeeded / startServerPlaySession / heartbeatServerPlaySession /
 * finishServerPlaySession / resolveLaunchTypeForRecord / playSessionHeartbeat 共 7 处方法。
 *
 * 状态归属：原 Fragment 持有的 runningSessionId / runningGameId 等运行态字段全部迁移至本控制器。
 * Fragment 通过 Listener 接口接收 UI 刷新回调。
 */
public final class GameSessionController {

    /** 会话收尾与心跳间隔（与原 Fragment 常量保持一致）。 */
    private static final long MIN_PLAY_SESSION_MS = 0L;
    private static final long MAX_PLAY_SESSION_MS = 12L * 60L * 60L * 1000L;
    private static final long PLAY_SESSION_HEARTBEAT_MS = 60L * 1000L;

    public interface Listener {
        /** 就地刷新单张卡片，避免重置分页与滑动位置。 */
        void reloadGame(long gameId);

        /** 整体重载游戏列表。 */
        void reloadAllGames();
    }

    /**
     * 用于没有 Fragment 宿主的启动入口（例如桌面快捷方式）。回调在主线程执行。
     */
    public interface LaunchListener {
        void onResult(LauncherGameLaunchBridge.LaunchResult result);
    }

    private final Context appContext;
    private final RxMainQueue mainQueue;
    private final Listener listener;

    private long runningSessionId = -1L;
    private long runningGameId = -1L;
    private String runningGameTitle = "";
    private long runningSessionStart = 0L;
    private String runningServerSessionId = "";
    private String runningLaunchType = "external";

    private final Runnable playSessionHeartbeat = new Runnable() {
        @Override
        public void run() {
            heartbeatServerPlaySession();
            mainQueue.postDelayed(this, PLAY_SESSION_HEARTBEAT_MS);
        }
    };

    public GameSessionController(@NonNull Context context, @NonNull RxMainQueue mainQueue, @NonNull Listener listener) {
        this.appContext = context.getApplicationContext();
        this.mainQueue = mainQueue;
        this.listener = listener;
    }

    /** 是否有正在进行的游玩会话（用于 onResume 判断是否需要收尾）。 */
    public boolean hasActiveSession() {
        return runningSessionId > 0L;
    }

    /** 启动游戏：调用 LauncherGameLaunchBridge.launchAsync，成功后开启服务端会话。 */
    public void launchGameDirectly(@NonNull Fragment fragment, @NonNull Game game) {
        if (game == null) return;
        Context context = fragment.getContext();
        if (context == null) return;
        launchGame(context, game, result -> {
            if (!fragment.isAdded()) return;
            if (result.activeGameConflict) {
                LauncherGameLaunchBridge.showActiveGameDialog(fragment.requireContext(), result.activeGameTitle);
            } else if (!result.success && result.message != null && !result.message.trim().isEmpty()) {
                Toast.makeText(fragment.requireContext(), result.message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * 统一的无 Fragment 启动入口。成功后始终接管本地与服务端会话，
     * 以便首页、横屏旧页和桌面快捷方式不会绕开实时游玩时间链路。
     */
    public void launchGame(@NonNull Context context, @NonNull Game game, LaunchListener callback) {
        if (game == null) return;
        LauncherGameLaunchBridge.launchAsync(context, game, result -> {
            if (result.success) {
                runningSessionId = result.sessionId;
                runningGameId = game.id;
                runningGameTitle = GameMetadataFormatter.safeTitle(game);
                runningSessionStart = System.currentTimeMillis();
                runningLaunchType = resolveLaunchTypeForRecord(game);
                startServerPlaySession(game, result.sessionId);
            }
            if (callback != null) callback.onResult(result);
        });
    }

    /** 收尾当前游玩会话：写入本地 play_sessions + 结束服务端 session + 通知 UI 刷新。 */
    public void finishDirectPlaySessionIfNeeded(@NonNull Fragment fragment) {
        if (runningSessionId <= 0L) return;
        Context context = fragment.getContext();
        if (context == null) return;
        finishDirectPlaySessionIfNeeded(context);
    }

    /**
     * 无 Fragment 的会话收尾入口，供 Activity/快捷方式宿主在回到前台时调用。
     */
    public void finishDirectPlaySessionIfNeeded(@NonNull Context context) {
        if (runningSessionId <= 0L) return;
        // 1) 主项目会话收尾（写入 play_sessions 表 + 累加 total_play_time）
        LauncherGameLaunchBridge.finishSession(context, runningSessionId, MIN_PLAY_SESSION_MS, MAX_PLAY_SESSION_MS);
        // 2) 线上实际游玩时长只结束服务端 session，不提交本地 duration。
        finishServerPlaySession(runningSessionId);
        // 捕获刚结束会话的游戏 id，用于就地刷新单张卡片；
        // 不能调用 loadGames()，否则会重置分页并丢失滑动位置。
        long finishedGameId = runningGameId;
        runningSessionId = -1L;
        runningGameId = -1L;
        runningGameTitle = "";
        runningSessionStart = 0L;
        runningServerSessionId = "";
        runningLaunchType = "external";
        if (finishedGameId > 0L) {
            listener.reloadGame(finishedGameId);
        } else {
            listener.reloadAllGames();
        }
    }

    private void startServerPlaySession(Game game, long localSessionId) {
        if (game == null || localSessionId <= 0L) return;
        if (!LauncherAuthBridge.isLoggedIn(appContext)) return;
        SharedPreferences prefs = appContext.getSharedPreferences("launcher_account_settings", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("realtime_playtime", true)) return;
        String deviceId = LauncherUserData.getRealtimePlaytimeDeviceId(appContext);
        LauncherAuthBridge.startPlayTimeSession(appContext, game.id, GameMetadataFormatter.safeTitle(game), deviceId,
                new PlaySessionCallback() {
                    @Override
                    public void onSuccess(PlaySession session) {
                        // 网络回调线程与主线程并发访问 runningSessionId/runningServerSessionId
                        // 时存在 check-then-act 竞态：若主线程在检查与赋值之间将
                        // runningSessionId 置为 -1（finishDirectPlaySessionIfNeeded），
                        // 仍可能写入已结束会话的 serverSessionId，导致后续心跳发往无效会话。
                        // 切回主线程执行可保证状态读写的串行化与原子性。
                        mainQueue.post(() -> {
                            if (session == null || session.sessionId == null
                                    || session.sessionId.trim().isEmpty()) return;
                            if (runningSessionId != localSessionId) return;
                            runningServerSessionId = session.sessionId;
                            LauncherUserData.rememberServerPlaySession(appContext, localSessionId, game.id,
                                    GameMetadataFormatter.safeTitle(game), session.sessionId);
                            scheduleServerPlayHeartbeat();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        // 静默失败：不能回退到本地 duration 上传。
                    }
                });
    }

    private void scheduleServerPlayHeartbeat() {
        mainQueue.removeCallbacks(playSessionHeartbeat);
        mainQueue.postDelayed(playSessionHeartbeat, PLAY_SESSION_HEARTBEAT_MS);
    }

    private void heartbeatServerPlaySession() {
        if (runningServerSessionId == null || runningServerSessionId.trim().isEmpty()) return;
        LauncherAuthBridge.heartbeatPlayTimeSession(appContext, runningServerSessionId, new PlaySessionCallback() {
            @Override
            public void onSuccess(PlaySession session) {
            }

            @Override
            public void onError(String message) {
            }
        });
    }

    private void finishServerPlaySession(long localSessionId) {
        mainQueue.removeCallbacks(playSessionHeartbeat);
        String serverSessionId = runningServerSessionId == null || runningServerSessionId.trim().isEmpty()
                ? LauncherUserData.findServerPlaySessionId(appContext, localSessionId)
                : runningServerSessionId;
        if (serverSessionId == null || serverSessionId.trim().isEmpty()) return;
        LauncherAuthBridge.finishPlayTimeSession(appContext, serverSessionId, new PlaySessionCallback() {
            @Override
            public void onSuccess(PlaySession session) {
                LauncherUserData.removeServerPlaySession(appContext, localSessionId);
            }

            @Override
            public void onError(String message) {
                // finish 可重试；失败时保留 session_id 映射，等待后续恢复流程处理。
            }
        });
    }

    /**
     * 与 LauncherGameLaunchBridge.resolveLaunchType 保持一致的启动类型推导，
     * 仅用于实际游玩记录的 launchType 字段标记，便于后续上传区分启动方式。
     */
    public static String resolveLaunchTypeForRecord(Game game) {
        if (game == null || game.emulatorPackage == null) return "external";
        String pkg = game.emulatorPackage.trim().toLowerCase(Locale.ROOT);
        if (pkg.startsWith("internal.krkr") || pkg.equals("org.tvp.kirikiri2.internal")) return "internal.krkr";
        if (pkg.startsWith("internal.ons") || pkg.equals("com.core.ons")) return "internal.ons";
        if (pkg.startsWith("internal.tyrano") || pkg.equals("com.core.tyrano")) return "internal.tyrano";
        if (pkg.startsWith("internal.artemis")) return pkg;
        if (pkg.startsWith("internal.psp") || pkg.equals("org.ppsspp.ppsspp")) return "internal.psp";
        if (pkg.startsWith("internal.citra") || pkg.equals("io.github.azaharplus.android")) return "internal.citra";
        return "external";
    }

    /** 在 Fragment.onDestroyView 中调用，移除心跳回调避免泄漏。 */
    public void cleanup() {
        mainQueue.removeCallbacks(playSessionHeartbeat);
    }
}
