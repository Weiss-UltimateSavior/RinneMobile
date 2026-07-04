package com.yuki.yukihub.launcherbridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.launcher.EmulatorLauncher;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;

import java.util.Locale;

public final class LauncherGameLaunchBridge {
    private static final String APP_PREFS = "yukihub_prefs";
    private static final String KEY_KR_COMPAT_MODE = "kr_compat_mode";
    private static final String KEY_KR_ENGINE_VERSION = "kr_engine_version";

    private LauncherGameLaunchBridge() {
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
                boolean compatMode = prefs.getBoolean(KEY_KR_COMPAT_MODE, false);
                String krEngineVersion = prefs.getString(KEY_KR_ENGINE_VERSION, "auto");
                return startActivitySafely(context, EmulatorLauncher.buildInternalKrkrIntent(context, game.rootUri, launchTarget, false, compatMode, krEngineVersion, false));
            }
            if (pkg.startsWith("internal.tyrano") || pkg.equals("com.yuki.yukihub.tyrano")) {
                return startActivitySafely(context, EmulatorLauncher.buildInternalTyranoIntent(context, game.rootUri, launchTarget));
            }
            if (pkg.startsWith("internal.ons") || pkg.equals("com.yuki.yukihub.ons")) {
                return startActivitySafely(context, EmulatorLauncher.buildInternalOnsIntent(context, game.rootUri, launchTarget));
            }
            if (pkg.startsWith("internal.artemis")) {
                return startActivitySafely(context, EmulatorLauncher.buildInternalArtemisIntent(context, pkg, game.rootUri, launchTarget));
            }
            if (pkg.startsWith("internal.psp") || pkg.equals("org.ppsspp.ppsspp")) {
                if (!EmulatorLauncher.isPPSSPPInstalled(context)) {
                    return false;
                }
                return startActivitySafely(context, EmulatorLauncher.buildInternalPspIntent(context, game.rootUri, launchTarget));
            }
            return EmulatorLauncher.launchGame(context, pkg, game.rootUri, launchTarget, game.winlatorLaunchMode, game.gamehubLaunchMode, game.gamehubLocalGameId);
        } catch (Throwable ignored) {
            return false;
        }
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
