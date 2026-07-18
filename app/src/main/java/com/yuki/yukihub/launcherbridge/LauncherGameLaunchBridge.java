package com.yuki.yukihub.launcherbridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.documentfile.provider.DocumentFile;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.launcher.EmulatorLauncher;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;
import com.yuki.yukihub.util.RxMainScheduler;

import java.util.Locale;

public final class LauncherGameLaunchBridge {
    private static final String APP_PREFS = "yukihub_prefs";
    private static final String KEY_KR_ENGINE_VERSION = "kr_engine_version";

    private LauncherGameLaunchBridge() {
    }

    public interface LaunchCallback { void onResult(LaunchResult result); }

    /** Performs database, SAF and file preparation away from the UI thread. */
    public static void launchAsync(Context context, Game game, LaunchCallback callback) {
        if (callback == null) return;
        Context app = context == null ? null : context.getApplicationContext();
        AppExecutors.runOnIo(() -> {
            LaunchResult result = launch(app, game);
            RxMainScheduler.post(() -> callback.onResult(result));
        });
    }

    public static LaunchResult launch(Context context, Game game) {
        if (context == null) return LaunchResult.failure("上下文不可用");
        if (game == null) return LaunchResult.failure("游戏不存在");
        Context appContext = context.getApplicationContext();
        GameRepository repository = new GameRepository(appContext);
        String emulatorPackage = resolveEmulatorPackage(game);
        String launchTarget = resolveLaunchTarget(game);
        String validationError = validate(context, game, emulatorPackage);
        if (validationError != null) return LaunchResult.failure(validationError);

        long sessionId = repository.startPlaySession(game.id, System.currentTimeMillis(), resolveLaunchType(emulatorPackage));
        if (startGameActivity(context, game, emulatorPackage, launchTarget)) {
            return LaunchResult.success(sessionId);
        }
        repository.cancelPlaySession(sessionId);
        return LaunchResult.failure("启动失败：未找到该模拟器，或该模拟器不接受当前启动目标");
    }

    public static void finishSession(Context context, long sessionId, long minDuration, long maxDuration) {
        if (context == null || sessionId <= 0L) return;
        new GameRepository(context.getApplicationContext()).finishPlaySession(
                sessionId,
                System.currentTimeMillis(),
                minDuration,
                maxDuration
        );
    }

    /**
     * 构建进入原生 KRKR 引擎（origin 模式、无具体游戏路径）的 Intent。
     * 供设置页面"进入原生 KRKR"入口使用，避免 com.apps 直接依赖 EmulatorLauncher。
     *
     * @return 可用于 {@link android.app.Activity#startActivity(Intent)} 的 Intent；上下文无效时返回 null
     */
    public static Intent buildInternalKrkrOriginIntent(Context context) {
        if (context == null) return null;
        return EmulatorLauncher.buildInternalKrkrIntent(context, "", "", true);
    }

    private static String validate(Context context, Game game, String emulatorPackage) {
        if (game.engine == EngineType.GAMEHUB) {
            String ghMode = game.gamehubLaunchMode == null ? "game" : game.gamehubLaunchMode.trim().toLowerCase(Locale.ROOT);
            if (!("program".equals(ghMode) || "normal".equals(ghMode))
                    && (game.gamehubLocalGameId == null || game.gamehubLocalGameId.trim().isEmpty())) {
                return "请先在游戏中心编辑游戏，导入 GameHub localGameId。";
            }
        }
        if (emulatorPackage == null || emulatorPackage.isEmpty()) {
            return "请先在游戏中心编辑游戏，填写模拟器包名。";
        }
        if ((emulatorPackage.startsWith("internal.psp") || emulatorPackage.equals("org.ppsspp.ppsspp"))
                && !EmulatorLauncher.isPPSSPPInstalled(context)) {
            return "启动 PSP 游戏需要安装 PPSSPP 模拟器。";
        }
        // 外部插件启用状态拦截：模块被禁用时拒绝启动，引导用户去模块兼容页启用。
        if (LauncherModuleBridge.isRpgMakerPluginPackage(emulatorPackage)
                && LauncherModuleBridge.isRpgMakerModuleInstalled(context)
                && !LauncherModuleBridge.isRpgMakerModuleEnabled(context)) {
            return "RPGM 模块未启用，请在「模块兼容」页面启用后再试。";
        }
        if (LauncherModuleBridge.isRenPyPluginPackage(emulatorPackage)
                && LauncherModuleBridge.isRenPyModuleInstalled(context)
                && !LauncherModuleBridge.isRenPyModuleEnabled(context)) {
            return "RenPy 模块未启用，请在「模块兼容」页面启用后再试。";
        }
        return null;
    }

    private static String resolveEmulatorPackage(Game game) {
        String emulatorPackage = game.emulatorPackage == null ? "" : game.emulatorPackage.trim();
        if (emulatorPackage.isEmpty() && game.engine == EngineType.KIRIKIRI) return "internal.krkr";
        if (emulatorPackage.isEmpty() && game.engine == EngineType.ONS) return "internal.ons";
        if (emulatorPackage.isEmpty() && game.engine == EngineType.TYRANO) return "internal.tyrano";
        if (emulatorPackage.isEmpty() && game.engine == EngineType.PSP) return "org.ppsspp.ppsspp";
        if (game.engine == EngineType.ARTEMIS && emulatorPackage.isEmpty()) return "internal.artemis";
        return emulatorPackage;
    }

    private static String resolveLaunchTarget(Game game) {
        if (game.engine == EngineType.ARTEMIS || game.engine == EngineType.TYRANO) return "[游戏目录]";
        if (game.engine == EngineType.GAMEHUB) return safeTitle(game);
        return game.launchTarget;
    }

    private static boolean startGameActivity(Context context, Game game, String emulatorPackage, String launchTarget) {
        String pkg = emulatorPackage == null ? "" : emulatorPackage.trim();
        try {
            if (pkg.startsWith("internal.krkr") || pkg.equals("org.tvp.kirikiri2.internal")) {
                SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
                String krEngineVersion = prefs.getString(KEY_KR_ENGINE_VERSION, "auto");
                return startActivitySafely(context, EmulatorLauncher.buildInternalKrkrIntent(context, game.rootUri, launchTarget, false, krEngineVersion, false));
            }
            if (pkg.startsWith("internal.tyrano") || pkg.equals("com.yuki.yukihub.tyrano")) {
                return startActivitySafely(context, EmulatorLauncher.buildInternalTyranoIntent(context, game.rootUri, launchTarget));
            }
            if (pkg.startsWith("internal.ons") || pkg.equals("com.yuki.yukihub.ons")) {
                return startActivitySafely(context, EmulatorLauncher.buildInternalOnsIntent(context, game.rootUri, launchTarget, game.id));
            }
            if (pkg.startsWith("internal.artemis")) {
                return startActivitySafely(context, EmulatorLauncher.buildInternalArtemisIntent(context, pkg, game.rootUri, launchTarget));
            }
            if (pkg.startsWith("internal.psp") || pkg.equals("org.ppsspp.ppsspp")) {
                if (!EmulatorLauncher.isPPSSPPInstalled(context)) {
                    return false;
                }
                return startActivitySafely(context, EmulatorLauncher.buildInternalPspIntent(
                        context, resolvePspLaunchUri(context, game.rootUri, launchTarget), launchTarget));
            }
            return EmulatorLauncher.launchGame(context, pkg, game.rootUri, launchTarget, game.winlatorLaunchMode, game.gamehubLaunchMode, game.gamehubLocalGameId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** PPSSPP must receive the selected disc file rather than its containing SAF tree. */
    private static String resolvePspLaunchUri(Context context, String rootUri, String launchTarget) {
        if (rootUri == null || rootUri.trim().isEmpty() || launchTarget == null || launchTarget.trim().isEmpty()
                || "[游戏目录]".equals(launchTarget)) return rootUri;
        try {
            DocumentFile current = DocumentFile.fromTreeUri(context, android.net.Uri.parse(rootUri));
            for (String segment : launchTarget.split("/")) {
                if (current == null || segment.isEmpty()) continue;
                current = current.findFile(segment);
            }
            if (current != null && current.isFile()) return current.getUri().toString();
        } catch (Throwable ignored) {
        }
        return rootUri;
    }

    private static boolean startActivitySafely(Context context, Intent intent) {
        if (context == null || intent == null) return false;
        try {
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String resolveLaunchType(String emulatorPackage) {
        String pkg = emulatorPackage == null ? "" : emulatorPackage.trim().toLowerCase(Locale.ROOT);
        if (pkg.startsWith("internal.krkr") || pkg.equals("org.tvp.kirikiri2.internal")) return "internal.krkr";
        if (pkg.startsWith("internal.ons") || pkg.equals("com.yuki.yukihub.ons")) return "internal.ons";
        if (pkg.startsWith("internal.tyrano") || pkg.equals("com.yuki.yukihub.tyrano")) return "internal.tyrano";
        if (pkg.startsWith("internal.artemis")) return pkg;
        return "external";
    }

    private static String safeTitle(Game game) {
        if (game == null || game.title == null || game.title.trim().isEmpty()) return "未命名游戏";
        return game.title.trim();
    }

    public static final class LaunchResult {
        public final boolean success;
        public final long sessionId;
        public final String message;

        private LaunchResult(boolean success, long sessionId, String message) {
            this.success = success;
            this.sessionId = sessionId;
            this.message = message;
        }

        static LaunchResult success(long sessionId) {
            return new LaunchResult(true, sessionId, "");
        }

        static LaunchResult failure(String message) {
            return new LaunchResult(false, -1L, message == null || message.trim().isEmpty() ? "启动失败" : message);
        }
    }
}
