package com.yuki.yukihub.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.FileObserver;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import com.apps.theme.LauncherTheme;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.ons.OnsSettings;

public class EmulatorLauncher {
    private static final List<FileObserver> ARTEMIS_SAVE_OBSERVERS = new ArrayList<>();
    private static final List<EngineLaunchStrategy> ENGINE_STRATEGIES = new CopyOnWriteArrayList<>();
    private static final String PREFS_NAME = "yukihub_prefs";
    private static final String KEY_ARTEMIS_ENGINE_PREFIX = "artemis_engine.";

    /** 将 Launcher 主题色附加到 Intent extras，供引擎弹窗（KrDialogStyle）读取。 */
    private static void appendThemeColors(Intent i, Context context) {
        if (i == null || context == null) return;
        try {
            i.putExtra("themeColorPrimary", LauncherTheme.primary(context));
            i.putExtra("themeColorOnPrimary", LauncherTheme.onPrimary(context));
            i.putExtra("themeColorCard", LauncherTheme.card(context));
            i.putExtra("themeColorText", LauncherTheme.text(context));
            i.putExtra("themeColorTextMuted", LauncherTheme.textMuted(context));
        } catch (Throwable t) {
            Log.w("EmulatorLauncher", "appendThemeColors failed", t);
        }
    }

    /** Actual built-in-engine save directory shared by launch and save-management flows. */
    public static final class ActualSaveLocation {
        public final File directory;
        public final String description;
        public final boolean available;

        private ActualSaveLocation(File directory, String description, boolean available) {
            this.directory = directory;
            this.description = description == null ? "" : description;
            this.available = available;
        }

        private static ActualSaveLocation available(File directory, String description) {
            return new ActualSaveLocation(directory, description, true);
        }

        private static ActualSaveLocation unavailable(String description) {
            return new ActualSaveLocation(null, description, false);
        }
    }

    static {
        // Keep this order aligned with the legacy condition chain.  Every strategy
        // only claims its own package aliases, so unknown external packages still
        // use the generic Kirikiri-compatible intent fallback below.
        addBuiltInStrategy(new InternalKrkrStrategy());
        addBuiltInStrategy(new InternalTyranoStrategy());
        addBuiltInStrategy(new InternalOnsStrategy());
        addBuiltInStrategy(new InternalArtemisStrategy());
        addBuiltInStrategy(new PspStrategy());
        addBuiltInStrategy(new CitraStrategy());
        addBuiltInStrategy(new GameHubStrategy());
        addBuiltInStrategy(new WinlatorDesktopStrategy());
        // RPG Maker：通过外部安装的 cyou.joiplay.runtime.rpgmaker 插件启动 RGSS 游戏。
        addBuiltInStrategy(new ExternalRpgMakerPluginStrategy());
        // Ren'Py：通过外部安装的 cyou.joiplay.runtime.renpy 插件启动。
        addBuiltInStrategy(new ExternalRenPyPluginStrategy());
    }

    public static boolean launchGame(Context context, String packageName, String rootUri, String launchTarget) {
        return launchGame(context, EngineType.UNKNOWN, packageName, rootUri, launchTarget, "game", "game", null);
    }

    public static boolean launchGame(Context context, String packageName, String rootUri, String launchTarget, String winlatorLaunchMode) {
        return launchGame(context, EngineType.UNKNOWN, packageName, rootUri, launchTarget, winlatorLaunchMode, "game", null);
    }

    public static boolean launchGame(Context context, String packageName, String rootUri, String launchTarget, String winlatorLaunchMode, String gamehubLocalGameId) {
        return launchGame(context, EngineType.UNKNOWN, packageName, rootUri, launchTarget, winlatorLaunchMode, "game", gamehubLocalGameId);
    }

    public static boolean launchGame(Context context, String packageName, String rootUri, String launchTarget, String winlatorLaunchMode, String gamehubLaunchMode, String gamehubLocalGameId) {
        return launchGame(context, EngineType.UNKNOWN, packageName, rootUri, launchTarget,
                winlatorLaunchMode, gamehubLaunchMode, gamehubLocalGameId);
    }

    /**
     * Engine-aware extension point. Existing callers may keep using the package-only
     * overloads; new integrations can identify their engine without changing the
     * central dispatch chain.
     */
    public static boolean launchGame(Context context, EngineType engineType, String packageName,
                                     String rootUri, String launchTarget, String winlatorLaunchMode,
                                     String gamehubLaunchMode, String gamehubLocalGameId) {
        LaunchRequest request = new LaunchRequest(engineType, packageName, rootUri, launchTarget,
                winlatorLaunchMode, gamehubLaunchMode, gamehubLocalGameId);
        if (context == null || request.packageName.isEmpty()) return false;
        for (EngineLaunchStrategy strategy : ENGINE_STRATEGIES) {
            if (!strategy.supports(request)) continue;
            try {
                return strategy.launch(context, request);
            } catch (Exception e) {
                Log.w("EmulatorLauncher", "Launch strategy failed: " + strategy.getClass().getSimpleName(), e);
                return false;
            }
        }
        return launchGenericKirikiriCompatible(context, request);
    }

    /** Registers a new integration before built-ins; callers need not edit this class. */
    public static void registerEngineLaunchStrategy(EngineLaunchStrategy strategy) {
        if (strategy != null) ENGINE_STRATEGIES.add(0, strategy);
    }

    /** Exposes registered engine types for configuration/diagnostics UIs. */
    public static List<EngineType> getRegisteredEngineTypes() {
        List<EngineType> types = new ArrayList<>();
        for (EngineLaunchStrategy strategy : ENGINE_STRATEGIES) {
            if (strategy != null && strategy.getEngineType() != null && !types.contains(strategy.getEngineType())) {
                types.add(strategy.getEngineType());
            }
        }
        return Collections.unmodifiableList(types);
    }

    private static void addBuiltInStrategy(EngineLaunchStrategy strategy) {
        ENGINE_STRATEGIES.add(strategy);
    }

    private static boolean launchGenericKirikiriCompatible(Context context, LaunchRequest request) {
        String pkg = request.packageName;
        if (request.rootUri != null && !request.rootUri.trim().isEmpty()) {
            List<Uri> launchUris = buildKirikiriLaunchUris(context, request.rootUri, request.launchTarget);
            for (Uri uri : launchUris) {
                Intent[] intents = buildLaunchIntents(pkg, uri, request.rootUri, request.launchTarget);
                for (Intent intent : intents) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    try {
                        context.startActivity(intent);
                        return true;
                    } catch (Exception ignored) { }
                }
            }
        }
        return launch(context, pkg);
    }

    private abstract static class BuiltInStrategy implements EngineLaunchStrategy {
        private final EngineType engineType;

        BuiltInStrategy(EngineType engineType) {
            this.engineType = engineType;
        }

        @Override
        public EngineType getEngineType() {
            return engineType;
        }

        boolean start(Context context, Intent intent) {
            try {
                context.startActivity(intent);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    private static final class InternalKrkrStrategy extends BuiltInStrategy {
        InternalKrkrStrategy() { super(EngineType.KIRIKIRI); }

        @Override public boolean supports(LaunchRequest request) {
            return equalsAnyIgnoreCase(request.packageName, "internal.krkr", "org.tvp.kirikiri2.internal");
        }

        @Override public boolean launch(Context context, LaunchRequest request) {
            return start(context, buildInternalKrkrIntent(context, request.rootUri, request.launchTarget, false));
        }
    }

    private static final class InternalTyranoStrategy extends BuiltInStrategy {
        InternalTyranoStrategy() { super(EngineType.TYRANO); }

        @Override public boolean supports(LaunchRequest request) {
            return equalsAnyIgnoreCase(request.packageName, "internal.tyrano", "com.yuki.yukihub.tyrano");
        }

        @Override public boolean launch(Context context, LaunchRequest request) {
            return start(context, buildInternalTyranoIntent(context, request.rootUri, request.launchTarget));
        }
    }

    private static final class InternalOnsStrategy extends BuiltInStrategy {
        InternalOnsStrategy() { super(EngineType.ONS); }

        @Override public boolean supports(LaunchRequest request) {
            return equalsAnyIgnoreCase(request.packageName, "internal.ons", "internal.onscripter", "com.yuki.yukihub.ons");
        }

        @Override public boolean launch(Context context, LaunchRequest request) {
            return start(context, buildInternalOnsIntent(context, request.rootUri, request.launchTarget));
        }
    }

    private static final class InternalArtemisStrategy extends BuiltInStrategy {
        InternalArtemisStrategy() { super(EngineType.ARTEMIS); }

        @Override public boolean supports(LaunchRequest request) {
            return equalsAnyIgnoreCase(request.packageName, "internal.artemis", "com.yuki.yukihub.artemis",
                    "internal.artemis.compat", "internal.artemis.compatible", "internal.artemis.compat.v2",
                    "internal.artemis.compatible.v2");
        }

        @Override public boolean launch(Context context, LaunchRequest request) {
            return start(context, buildInternalArtemisIntent(context, request.packageName, request.rootUri, request.launchTarget));
        }
    }

    private static final class PspStrategy extends BuiltInStrategy {
        PspStrategy() { super(EngineType.PSP); }

        @Override public boolean supports(LaunchRequest request) {
            return equalsAnyIgnoreCase(request.packageName, "internal.psp", "org.ppsspp.ppsspp")
                    || isPPSSPPPackage(request.packageName);
        }

        @Override public boolean launch(Context context, LaunchRequest request) {
            if (!isPPSSPPInstalled(context)) {
                Log.w("EmulatorLauncher", "PPSSPP is not installed, cannot launch PSP game");
                return false;
            }
            return start(context, buildInternalPspIntent(context, request.rootUri, request.launchTarget));
        }
    }

    private static final class CitraStrategy extends BuiltInStrategy {
        CitraStrategy() { super(EngineType.NINTENDO_3DS); }

        @Override public boolean supports(LaunchRequest request) {
            return equalsAnyIgnoreCase(request.packageName, "internal.citra",
                    "io.github.azaharplus.android", "org.citra.citra_emu", "org.azahar_emu.azahar")
                    || isCitraPackage(request.packageName);
        }

        @Override public boolean launch(Context context, LaunchRequest request) {
            if (!isCitraInstalled(context)) {
                Log.w("EmulatorLauncher", "Citra/Azahar is not installed, cannot launch Nintendo 3DS game");
                return false;
            }
            return start(context, buildInternalCitraIntent(context, request.rootUri, request.launchTarget));
        }
    }

    private static final class GameHubStrategy extends BuiltInStrategy {
        GameHubStrategy() { super(EngineType.GAMEHUB); }

        @Override public boolean supports(LaunchRequest request) {
            return isGameHubPackage(request.packageName);
        }

        @Override public boolean launch(Context context, LaunchRequest request) {
            String mode = request.gameHubLaunchMode == null ? "game" : request.gameHubLaunchMode.trim().toLowerCase(Locale.ROOT);
            if ("program".equals(mode) || "normal".equals(mode)) return EmulatorLauncher.launch(context, request.packageName);
            if (request.gameHubLocalGameId == null || request.gameHubLocalGameId.trim().isEmpty()) return false;
            String gameId = request.gameHubLocalGameId.trim();
            String appName = guessGameHubAppName(request.launchTarget);
            if (start(context, buildGameHubDetailIntent(request.packageName, gameId, appName))) return true;
            return start(context, buildGameHubRouterIntent(request.packageName, gameId, appName));
        }
    }

    private static final class WinlatorDesktopStrategy extends BuiltInStrategy {
        WinlatorDesktopStrategy() { super(EngineType.WINLATOR); }

        @Override public boolean supports(LaunchRequest request) {
            return isWinlatorPackage(request.packageName) && isWinlatorTarget(request.launchTarget);
        }

        @Override public boolean launch(Context context, LaunchRequest request) {
            return launchWinlatorDesktop(context, request.packageName, request.rootUri,
                    request.launchTarget, request.winlatorLaunchMode);
        }
    }

    private static boolean equalsAnyIgnoreCase(String value, String... candidates) {
        if (value == null || candidates == null) return false;
        for (String candidate : candidates) {
            if (candidate != null && candidate.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    private static Intent[] buildLaunchIntents(String pkg, Uri uri, String rootUri, String launchTarget) {
        String uriText = uri.toString();
        String rootText = rootUri == null ? uriText : rootUri;
        String target = launchTarget == null ? "" : launchTarget;
        if ("com.akira.tyranoemu".equals(pkg)) {
            return new Intent[]{
                    // Some external players expose this action for web/Tyrano games.
                    explicit("com.akira.tyranoemu", "com.akira.tyranoemu.remote.WebActivity", "android.intent.action.WebGame", uri)
                            .putExtra("path", uriText).putExtra("uri", uriText).putExtra("projectRoot", rootText)
                            .putExtra("launchFile", target).putExtra("filename", target).putExtra("game", uriText)
                            .putExtra("gamedir", rootText).putExtra("gamename", guessName(target, rootText)).putExtra("gametitle", guessName(target, rootText)).putExtra("gameargs", target),
                    // Some builds may route KR2 through the launcher activity.
                    explicit("com.akira.tyranoemu", "com.akira.tyranoemu.app.TyActivity", Intent.ACTION_MAIN, uri)
                            .putExtra("path", uriText).putExtra("uri", uriText).putExtra("projectRoot", rootText)
                            .putExtra("launchFile", target).putExtra("filename", target).putExtra("game", uriText)
                            .putExtra("gamedir", rootText).putExtra("gamename", guessName(target, rootText)).putExtra("gametitle", guessName(target, rootText)).putExtra("gameargs", target),
                    new Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "text/html")
                            .putExtra("path", uriText).putExtra("projectRoot", rootText).putExtra("launchFile", target).putExtra("gameargs", target),
                    new Intent(Intent.ACTION_VIEW).setPackage(pkg).setData(uri)
                            .putExtra("path", uriText).putExtra("projectRoot", rootText).putExtra("launchFile", target).putExtra("gameargs", target)
            };
        }
        return new Intent[]{
                new Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "application/x-kirikiri"),
                new Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "application/octet-stream"),
                new Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "resource/folder"),
                new Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "inode/directory"),
                new Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "application/x-directory"),
                new Intent(Intent.ACTION_VIEW).setPackage(pkg).setData(uri),
                new Intent(Intent.ACTION_MAIN).setPackage(pkg)
                        .putExtra("path", uriText)
                        .putExtra("uri", uriText)
                        .putExtra("game", uriText)
                        .putExtra("startup", uriText)
                        .putExtra("projectRoot", rootText)
                        .putExtra("launchFile", target)
        };
    }

    private static String guessName(String target, String rootText) {
        if (target != null && !target.trim().isEmpty() && !"[游戏目录]".equals(target)) return target;
        if (rootText == null || rootText.isEmpty()) return "YukiHubGame";
        int slash = Math.max(rootText.lastIndexOf('/'), rootText.lastIndexOf('%'));
        return slash >= 0 && slash + 1 < rootText.length() ? rootText.substring(slash + 1) : "YukiHubGame";
    }

    private static Intent explicit(String pkg, String cls, String action, Uri uri) {
        Intent i = new Intent(action);
        i.setClassName(pkg, cls);
        if (uri != null) i.setData(uri);
        return i;
    }

    private static boolean isWinlatorPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.contains("winlator") || p.contains("glibc") || p.contains("proot") || p.contains("mobox") || p.contains("winalator");
    }

    private static boolean isDesktopTarget(String launchTarget) {
        return launchTarget != null && launchTarget.trim().toLowerCase(Locale.ROOT).endsWith(".desktop");
    }

    private static boolean isWinlatorTarget(String launchTarget) {
        if (launchTarget == null) return false;
        String target = launchTarget.trim().toLowerCase(Locale.ROOT);
        return target.endsWith(".desktop") || target.endsWith(".exe");
    }

    private static boolean launchWinlatorDesktop(Context context, String pkg, String rootUri, String launchTarget, String mode) {
        String desktopPath = resolveDesktopPath(context, rootUri, launchTarget);
        if (desktopPath == null || desktopPath.trim().isEmpty()) return false;
        int containerId = parseWinlatorContainerId(desktopPath);
        String execPath = resolveWinlatorExecPathFromDesktop(desktopPath, pkg);
        boolean legacyRootfsShortcut = isWinlatorLegacyRootfsShortcut(desktopPath);
        // Winlator/WinlatorCN/glibc/proot/mobox 等改包直启通常需要 container_id；解析不到时默认使用第一个容器。
        if (containerId <= 0 && shouldUseShellWinlatorLaunch(pkg)) containerId = 1;
        PackageManager pm = context.getPackageManager();
        String launchMode = mode == null ? "game" : mode.trim().toLowerCase(Locale.ROOT);
        if ("program".equals(launchMode) || "normal".equals(launchMode)) return launch(context, pkg);
        List<Intent> intents = new ArrayList<>();

        // 官版 v11 源码：Shortcut 直启需要 container_id + shortcut_path。
        // 改包可能仍保留 com.winlator.* 类名，也可能重打包成 当前包名.*，两种都尝试。
        addWinlatorActivityCandidates(intents, pkg, "XServerDisplayActivity", desktopPath, execPath, containerId);
        addWinlatorActivityCandidates(intents, pkg, "XrActivity", desktopPath, execPath, containerId);

        intents.add(addWinlatorExtras(new Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(Uri.fromFile(new File(desktopPath)), "application/x-desktop"), desktopPath, containerId));

        Intent normal = pm.getLaunchIntentForPackage(pkg);
        if (normal != null) intents.add(addWinlatorExtras(normal, desktopPath, containerId));

        intents.add(addWinlatorExtras(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), desktopPath, containerId));
        intents.add(addWinlatorExtras(explicit(pkg, pkg + ".MainActivity", Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER), desktopPath, containerId));
        intents.add(addWinlatorExtras(explicit(pkg, pkg + ".activities.MainActivity", Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER), desktopPath, containerId));

        for (Intent i : intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                context.startActivity(i);
                return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private static void addWinlatorActivityCandidates(List<Intent> intents, String pkg, String simpleActivityName, String desktopPath, String execPath, int containerId) {
        if (intents == null || pkg == null || simpleActivityName == null) return;
        String p = pkg.trim();
        if (p.isEmpty()) return;
        // 严格跟随用户选择的包名，只尝试当前包名下的 Activity，不再补固定的 com.winlator 兜底。
        List<String> classes = new ArrayList<>();
        classes.add(p + "." + simpleActivityName);
        classes.add(p + ".activities." + simpleActivityName);
        for (String cls : classes) {
            intents.add(addWinlatorExtras(explicit(p, cls, Intent.ACTION_MAIN, null), desktopPath, execPath, containerId));
        }
    }

    private static boolean shouldUseShellWinlatorLaunch(String pkg) {
        return isWinlatorPackage(pkg);
    }

    private static boolean isGameHubPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.trim().toLowerCase(Locale.ROOT);
        return "com.xiaoji.egggame".equals(p) || "com.xiaoji.egggamz".equals(p);
    }

    private static Intent buildGameHubDetailIntent(String pkg, String localGameId, String appName) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setPackage(pkg);
        i.setClassName(pkg, "com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity");
        String storedId = localGameId == null ? "" : localGameId.trim();
        boolean isSteam = storedId.toLowerCase(Locale.ROOT).startsWith("steam:");
        String steamAppId = isSteam ? storedId.substring("steam:".length()).trim() : "";
        String realLocalGameId = isSteam ? "" : storedId;
        i.putExtra("gameType", 0);
        i.putExtra("steamAppId", steamAppId);
        i.putExtra("id", 0);
        i.putExtra("type", 1);
        i.putExtra("localMobileAppId", "");
        i.putExtra("localGameId", realLocalGameId);
        i.putExtra("autoStartGame", true);
        i.putExtra("localPkg", "");
        i.putExtra("localAppName", appName == null || appName.trim().isEmpty() ? storedId : appName.trim());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return i;
    }

    private static Intent buildGameHubRouterIntent(String pkg, String localGameId, String appName) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setPackage(pkg);
        i.setClassName(pkg, "com.xj.app.DeepLinkRouterActivity");
        String storedId = localGameId == null ? "" : localGameId.trim();
        boolean isSteam = storedId.toLowerCase(Locale.ROOT).startsWith("steam:");
        String steamAppId = isSteam ? storedId.substring("steam:".length()).trim() : "";
        String realLocalGameId = isSteam ? "" : storedId;
        i.putExtra("gameType", 0);
        i.putExtra("steamAppId", steamAppId);
        i.putExtra("id", 0);
        i.putExtra("type", 1);
        i.putExtra("localMobileAppId", "");
        i.putExtra("localGameId", realLocalGameId);
        i.putExtra("autoStartGame", true);
        i.putExtra("localPkg", "");
        i.putExtra("localAppName", appName == null || appName.trim().isEmpty() ? storedId : appName.trim());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return i;
    }

    private static boolean startGameHubPinnedShortcut(Context context, String pkg, String localGameId) {
        try {
            LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (launcherApps == null) return false;
            LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery();
            query.setPackage(pkg);
            query.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED | LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            List<ShortcutInfo> shortcuts = launcherApps.getShortcuts(query, android.os.Process.myUserHandle());
            if (shortcuts == null || shortcuts.isEmpty()) return false;
            for (ShortcutInfo si : shortcuts) {
                if (si == null) continue;
                String shortcutGameId = extractGameHubLocalGameId(si);
                if (shortcutGameId == null || !localGameId.equalsIgnoreCase(shortcutGameId)) continue;
                try {
                    Bundle opts = null;
                    launcherApps.startShortcut(pkg, si.getId(), null, opts, android.os.Process.myUserHandle());
                    return true;
                } catch (Throwable t) {
                    Log.w("YukiHub", "startShortcut failed for GameHub", t);
                }
            }
        } catch (Throwable t) {
            Log.w("YukiHub", "startGameHubPinnedShortcut failed", t);
        }
        return false;
    }

    private static String extractGameHubLocalGameId(ShortcutInfo si) {
        if (si == null) return null;
        try {
            Intent[] intents = si.getIntents();
            if (intents != null) {
                for (int i = intents.length - 1; i >= 0; i--) {
                    Intent intent = intents[i];
                    if (intent == null) continue;
                    Bundle extras = intent.getExtras();
                    if (extras != null) {
                        String id = extras.getString("localGameId");
                        if (id != null && !id.trim().isEmpty()) return id.trim();
                    }
                }
            }
        } catch (Throwable ignored) { }
        try {
            Bundle extras = si.getExtras() != null ? new Bundle(si.getExtras()) : null;
            if (extras != null) {
                String id = extras.getString("localGameId");
                if (id != null && !id.trim().isEmpty()) return id.trim();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String guessGameHubAppName(String launchTarget) {
        if (launchTarget == null) return "";
        String t = launchTarget.trim();
        if (t.isEmpty() || t.startsWith("[")) return "";
        return t;
    }
    private static Intent addWinlatorExtras(Intent i, String desktopPath, String execPath, int containerId) {
        String actualExecPath = (execPath == null || execPath.trim().isEmpty()) ? desktopPath : execPath;
        String startPath = dirname(actualExecPath);
        addWinlatorExtras(i, desktopPath, containerId);
        i.putExtra("exec_path", actualExecPath);
        i.putExtra("path", actualExecPath);
        if (startPath != null) i.putExtra("start_path", startPath);
        return i;
    }


    private static Intent addWinlatorExtras(Intent i, String desktopPath, int containerId) {
        // 官版 Winlator 直启关键参数。
        if (containerId > 0) i.putExtra("container_id", containerId);
        i.putExtra("shortcut_path", desktopPath);
        // 其余键仅用于兼容部分改版，不影响官版。
        i.putExtra("desktop_path", desktopPath);
        i.putExtra("path", desktopPath);
        i.putExtra("file", desktopPath);
        i.putExtra("rom", desktopPath);
        return i;
    }

    private static String resolveWinlatorExecPathFromDesktop(String desktopPath, String pkg) {
        try {
            File f = new File(desktopPath);
            if (!f.isFile()) return null;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f)));
            String line;
            String exec = null;
            String path = null;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("Exec=")) exec = t.substring(5).trim();
                else if (t.startsWith("Path=")) path = t.substring(5).trim();
            }
            try { br.close(); } catch (Throwable ignored) { }
            if (exec == null || exec.isEmpty()) return null;
            String exe = extractExeFromDesktopExec(exec);
            if (exe == null || exe.isEmpty()) return null;
            exe = exe.replace('\\', '/');
            if (exe.matches("^[A-Za-z]:/.*")) {
                if (path != null && !path.trim().isEmpty()) {
                    String fileName = exe.substring(exe.lastIndexOf('/') + 1);
                    String unixPath = path.replace('\\', '/');
                    return unixPath + (unixPath.endsWith("/") ? "" : "/") + fileName;
                }
                char drive = Character.toLowerCase(exe.charAt(0));
                String packageForPath = (pkg == null || pkg.trim().isEmpty()) ? "com.winlator" : pkg.trim();
                return "/data/user/0/" + packageForPath + "/files/rootfs/home/xuser/.wine/dosdevices/" + drive + ":" + exe.substring(2);
            }
            return exe;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String extractExeFromDesktopExec(String exec) {
        if (exec == null) return null;
        String s = exec.trim();
        int wineIdx = s.toLowerCase(Locale.ROOT).lastIndexOf("wine ");
        if (wineIdx >= 0) s = s.substring(wineIdx + 5).trim();
        if (s.startsWith("\"")) {
            int end = s.indexOf('"', 1);
            if (end > 1) return s.substring(1, end);
        }
        int exeIdx = s.toLowerCase(Locale.ROOT).indexOf(".exe");
        if (exeIdx >= 0) return s.substring(0, exeIdx + 4).trim();
        return s;
    }

    private static String dirname(String path) {
        if (path == null) return null;
        int idx = path.lastIndexOf('/');
        return idx > 0 ? path.substring(0, idx) : null;
    }

    private static boolean isWinlatorLegacyRootfsShortcut(String desktopPath) {
        if (desktopPath == null || desktopPath.trim().isEmpty()) return false;
        String p = desktopPath.replace('\\', '/');
        if (p.contains("/files/rootfs/home/xuser/")) return true;
        try {
            File f = new File(desktopPath);
            if (!f.isFile() || f.length() > 1024 * 1024) return false;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
            try {
                String line;
                int count = 0;
                while ((line = br.readLine()) != null && count++ < 80) {
                    if (line.contains("/files/rootfs/home/xuser/") || line.contains("/files/rootfs/")) return true;
                }
            } finally {
                try { br.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static int parseWinlatorContainerId(String desktopPath) {
        if (desktopPath == null) return 0;
        String marker = "/xuser-";
        int idx = desktopPath.indexOf(marker);
        if (idx < 0) {
            marker = "xuser-";
            idx = desktopPath.indexOf(marker);
            if (idx < 0) return 0;
        }
        int start = idx + marker.length();
        int end = start;
        while (end < desktopPath.length() && Character.isDigit(desktopPath.charAt(end))) end++;
        if (end <= start) return 0;
        try { return Integer.parseInt(desktopPath.substring(start, end)); } catch (Throwable ignored) { return 0; }
    }

    private static String resolveDesktopPath(Context context, String rootUri, String launchTarget) {
        String target = launchTarget == null ? "" : launchTarget.trim();
        if (target.startsWith("/") || target.startsWith("file://")) return stripFileScheme(target);
        String rootPath = uriToFilePath(rootUri);
        if (rootPath == null || rootPath.trim().isEmpty()) return target;
        if (rootPath.toLowerCase(Locale.ROOT).endsWith(".desktop")) return stripFileScheme(rootPath);
        if (rootPath.startsWith("content://")) {
            try {
                DocumentFile dir = DocumentFile.fromTreeUri(context, Uri.parse(rootUri));
                DocumentFile child = findTreeChild(dir, target);
                if (child != null) {
                    String childPath = uriToFilePath(child.getUri().toString());
                    if (childPath != null && !childPath.startsWith("content://")) return childPath;
                }
            } catch (Throwable ignored) { }
            return rootPath;
        }
        return rootPath.endsWith("/") ? rootPath + target : rootPath + "/" + target;
    }

    private static DocumentFile findTreeChild(DocumentFile root, String relativePath) {
        if (root == null || relativePath == null) return null;
        DocumentFile current = root;
        for (String segment : relativePath.split("/")) {
            if (segment.isEmpty()) continue;
            current = current == null ? null : current.findFile(segment);
        }
        return current;
    }

    private static List<Uri> buildKirikiriLaunchUris(Context context, String rootUri, String launchTarget) {
        List<Uri> uris = new ArrayList<>();
        Uri root = Uri.parse(rootUri);
        DocumentFile dir = DocumentFile.fromTreeUri(context, root);
        String target = launchTarget == null || launchTarget.isEmpty() ? "data.xp3" : launchTarget;
        if (dir != null && dir.isDirectory()) {
            if ("[游戏目录]".equals(target) || "DIR".equalsIgnoreCase(target)) {
                uris.add(root);
            } else if ("XP3_FIRST".equalsIgnoreCase(target)) {
                addFirstXp3(uris, dir);
            } else {
                addChildIfExists(uris, dir, target);
            }
            if (!uris.contains(root)) uris.add(root);
        }

        if (!uris.contains(root)) uris.add(root);
        return uris;
    }

    private static void addFirstXp3(List<Uri> uris, DocumentFile dir) {
        DocumentFile[] files = dir.listFiles();
        if (files == null) return;
        for (DocumentFile file : files) {
            String name = file.getName() == null ? "" : file.getName().toLowerCase();
            if (file.isFile() && name.endsWith(".xp3")) {
                Uri u = file.getUri();
                if (!uris.contains(u)) uris.add(u);
                return;
            }
        }
    }

    private static void addChildIfExists(List<Uri> uris, DocumentFile dir, String name) {
        DocumentFile child = dir.findFile(name);
        if (child != null && child.exists() && child.isFile() && !uris.contains(child.getUri())) {
            uris.add(child.getUri());
        }
    }

    public static boolean launch(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        String pkg = packageName.trim();
        PackageManager pm = context.getPackageManager();

        Intent intent = pm.getLaunchIntentForPackage(pkg);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        }

        // Some emulators do not expose a normal launcher intent to PackageManager.
        // Try common entry activity names as a fallback.
        String[] candidates = new String[]{
                pkg + ".MainActivity",
                pkg + ".AppActivity",
                pkg + ".TyranoActivity",
                pkg + ".PlayerActivity",
                pkg + ".activity.MainActivity"
        };
        for (String cls : candidates) {
            Intent explicit = new Intent(Intent.ACTION_MAIN);
            explicit.addCategory(Intent.CATEGORY_LAUNCHER);
            explicit.setClassName(pkg, cls);
            explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(explicit);
                return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    public static Intent buildInternalTyranoIntent(Context context, String gamePath, String launchTarget) {
        Intent i = new Intent(context, com.yuki.yukihub.tyrano.TyranoActivity.class);
        String resolvedPath = resolveInternalTyranoGameDirectory(gamePath, launchTarget);
        String path = stripFileScheme(resolvedPath);
        boolean scopedSaveDir = isTyranoScopedSaveDirEnabled(context);
        ActualSaveLocation saveLocation = resolveTyranoSaveLocation(context, path, scopedSaveDir);
        if (!saveLocation.available || saveLocation.directory == null) {
            throw new IllegalStateException(saveLocation.description);
        }
        if (!saveLocation.directory.exists() && !saveLocation.directory.mkdirs()) {
            throw new IllegalStateException("无法创建 Tyrano 存档目录：" + saveLocation.directory.getAbsolutePath());
        }
        if (!saveLocation.directory.isDirectory() || !saveLocation.directory.canWrite()) {
            throw new IllegalStateException("Tyrano 存档目录不可写：" + saveLocation.directory.getAbsolutePath());
        }
        Log.i("EmulatorLauncher", "internal Tyrano root=" + gamePath + " target=" + launchTarget
                + " resolved=" + resolvedPath + " scopedSave=" + scopedSaveDir
                + " save=" + saveLocation.directory.getAbsolutePath());
        if (resolvedPath != null && !resolvedPath.isEmpty()) {
            i.putExtra("path", path);
            i.putExtra("gamePath", path);
            i.putExtra("projectRoot", path);
            i.putExtra("gamedir", path);
        }
        i.putExtra("rootUri", gamePath);
        i.putExtra("launchTarget", launchTarget);
        i.putExtra("type", "Tyrano");
        i.putExtra("launchMode", "internal.tyrano");
        i.putExtra("orientation", 6);
        i.putExtra("scopedSaveDir", scopedSaveDir);
        i.putExtra("scopedSaveRoot", saveLocation.directory.getAbsolutePath());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        appendThemeColors(i, context);
        return i;
    }

    /**
     * Returns the exact directory passed to {@link com.yuki.yukihub.tyrano.TyranoActivity}.
     * Keep save-related features on this resolver so SAF document URIs cannot resolve to
     * their selected parent tree instead of the scanned game directory.
     */
    public static String resolveInternalTyranoGameDirectory(String rootUri, String launchTarget) {
        String rootPath = uriToFilePath(rootUri);
        if (rootPath == null || rootPath.isEmpty()) return rootUri;
        String target = launchTarget == null ? "" : launchTarget.trim();
        if (target.isEmpty() || "[游戏目录]".equals(target) || "DIR".equalsIgnoreCase(target)) return rootPath;
        if (target.startsWith("/") || target.startsWith("file://")) {
            File f = new File(stripFileScheme(target));
            return f.isFile() ? f.getParent() : f.getAbsolutePath();
        }
        File f = new File(rootPath, target);
        return f.isFile() ? f.getParent() : f.getAbsolutePath();
    }

    public static Intent buildInternalOnsIntent(Context context, String gamePath, String launchTarget) {
        Intent i = new Intent(context, com.yuri.onscripter.ONScripter.class);
        String requestedRootPath = stripFileScheme(uriToFilePath(gamePath));
        String rootPath = resolveInternalOnsGameDirectory(requestedRootPath);
        if (rootPath == null || rootPath.isEmpty()) {
            String message = "未在所选目录、上一级或直属子目录中找到 ONS 启动文件";
            Log.e("EmulatorLauncher", "internal ONS rejected: " + message + " root=" + requestedRootPath);
            throw new IllegalStateException(message);
        }
        String archiveError = validateOnsArchiveLayout(rootPath);
        if (archiveError != null) {
            Log.e("EmulatorLauncher", "internal ONS rejected: " + archiveError + " root=" + rootPath);
            throw new IllegalStateException(archiveError);
        }
        OnsSettings settings = OnsSettings.load(context);
        ActualSaveLocation saveLocation = resolveOnsSaveLocation(context, rootPath, settings.scopedSaveDir);
        if (!saveLocation.available || saveLocation.directory == null) {
            throw new IllegalStateException(saveLocation.description);
        }
        if (!saveLocation.directory.exists() && !saveLocation.directory.mkdirs()) {
            throw new IllegalStateException("无法创建 ONS 存档目录：" + saveLocation.directory.getAbsolutePath());
        }
        if (!saveLocation.directory.isDirectory() || !saveLocation.directory.canWrite()) {
            throw new IllegalStateException("ONS 存档目录不可写：" + saveLocation.directory.getAbsolutePath());
        }
        Log.i("EmulatorLauncher", "internal ONS root=" + gamePath + " target=" + launchTarget
                + " requested=" + requestedRootPath + " resolved=" + rootPath
                + " scopedSave=" + settings.scopedSaveDir
                + " save=" + saveLocation.directory.getAbsolutePath());
        ArrayList<String> args = settings.buildArgs(context, rootPath, saveLocation.directory);
        i.putStringArrayListExtra(OnsSettings.EXTRA_GAME_ARGS, args);
        i.putExtra(OnsSettings.EXTRA_IGNORE_CUTOUT, settings.ignoreCutout);
        i.putExtra("path", rootPath);
        i.putExtra("gamePath", rootPath);
        i.putExtra("rootUri", gamePath);
        i.putExtra("launchTarget", launchTarget);
        i.putExtra("launchMode", "internal.ons");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return i;
    }

    /**
     * A present but empty extra NSA is not equivalent to an absent archive: Yuri
     * opens it and parses past EOF.  Reject it before SDL starts so the launcher
     * can report an actionable import error rather than showing a black screen
     * or crashing in the native archive reader.
     */
    private static String validateOnsArchiveLayout(String rootPath) {
        if (rootPath == null || rootPath.isEmpty()) return null;
        File root = new File(rootPath);
        File base = new File(root, "arc.nsa");
        File extra = new File(root, "arc1.nsa");
        if (base.isFile() && base.length() > 0 && extra.isFile() && extra.length() == 0) {
            return "游戏资源不完整：arc1.nsa 为 0 字节。请重新解压或重新导入完整游戏目录。";
        }
        return null;
    }

    /**
     * ONS games are sometimes imported from one level above or below their real
     * directory. Only accept the selected directory, its parent, or one direct
     * child when it contains an ONS script/archive. Deliberately do not recurse
     * further: a broad search can select a neighbouring game in a shared library.
     */
    private static String resolveInternalOnsGameDirectory(String requestedRootPath) {
        if (requestedRootPath == null || requestedRootPath.trim().isEmpty()) return null;
        try {
            File root = new File(requestedRootPath);
            if (!root.isDirectory()) return null;
            String rootEntry = findOnsBootEntry(root);
            if (rootEntry != null) {
                Log.i("EmulatorLauncher", "internal ONS accepted root=" + root.getAbsolutePath()
                        + " entry=" + rootEntry);
                return root.getAbsolutePath();
            }

            File parent = root.getParentFile();
            String parentEntry = findOnsBootEntry(parent);
            if (parentEntry != null) {
                Log.i("EmulatorLauncher", "internal ONS auto-resolved parent "
                        + root.getAbsolutePath() + " -> " + parent.getAbsolutePath()
                        + " entry=" + parentEntry);
                return parent.getAbsolutePath();
            }

            File[] children = root.listFiles();
            if (children == null) return null;
            for (File child : children) {
                String childEntry = findOnsBootEntry(child);
                if (childEntry != null) {
                    Log.i("EmulatorLauncher", "internal ONS auto-resolved child "
                            + root.getAbsolutePath() + " -> " + child.getAbsolutePath()
                            + " entry=" + childEntry);
                    return child.getAbsolutePath();
                }
            }
            Log.w("EmulatorLauncher", "internal ONS has no boot entry root=" + root.getAbsolutePath()
                    + " files=" + describeDirectory(root, 40));
            return null;
        } catch (Throwable t) {
            Log.w("EmulatorLauncher", "internal ONS root resolve failed: " + requestedRootPath, t);
            return null;
        }
    }

    /**
     * A loose '*.nsa' match is enough for scanner classification, but not for
     * booting: ONSYuri probes a script or the fixed arc archive names first.
     */
    private static String findOnsBootEntry(File directory) {
        if (directory == null || !directory.isDirectory()) return null;
        File[] files = directory.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName();
            if (name == null) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if ("0.txt".equals(lower) || "00.txt".equals(lower)
                    || "nscr_sec.dat".equals(lower) || "nscript.dat".equals(lower)
                    || "onscript.nt2".equals(lower) || "onscript.nt3".equals(lower)
                    || "arc.nsa".equals(lower) || "arc.sar".equals(lower)) {
                return name;
            }
        }
        return null;
    }

    private static String describeDirectory(File directory, int maxEntries) {
        if (directory == null) return "<null>";
        File[] files = directory.listFiles();
        if (files == null) return "<unavailable>";
        StringBuilder result = new StringBuilder("[");
        int limit = Math.max(1, maxEntries);
        for (int i = 0; i < files.length && i < limit; i++) {
            if (i > 0) result.append(", ");
            File file = files[i];
            result.append(file == null ? "<null>" : file.getName());
            if (file != null && file.isDirectory()) result.append('/');
        }
        if (files.length > limit) result.append(", … total=").append(files.length);
        return result.append(']').toString();
    }

public static Intent buildInternalArtemisIntent(Context context, String packageName, String gamePath, String launchTarget) {
String resolvedPath = resolveInternalArtemisPath(gamePath, launchTarget);
String rootPath = stripFileScheme(resolvedPath);
String path = rootPath;
boolean scopedSaveDir = isArtemisScopedSaveDirEnabled(context);
String saveName = safeSaveName(rootPath);
if (scopedSaveDir) {
ActualSaveLocation saveLocation = resolveActualSaveLocation(context, EngineType.ARTEMIS, gamePath, launchTarget);
if (!saveLocation.available || saveLocation.directory == null) throw new IllegalStateException(saveLocation.description);
saveName = saveLocation.directory.getName();
ArtemisMirror mirror = prepareArtemisScopedMirror(context, rootPath, saveName);
if (mirror != null) {
Log.i("EmulatorLauncher", "internal Artemis scoped mirror root=" + rootPath + " -> " + mirror.rootPath);
path = mirror.rootPath;
} else {
throw new IllegalStateException("无法创建 Artemis 应用独立存档目录");
}
}
String requestedPackage = packageName == null ? "" : packageName.trim();
boolean autoFallback = "internal.artemis".equalsIgnoreCase(requestedPackage);
String effectivePackage = autoFallback ? preferredArtemisPackage(context, requestedPackage, rootPath) : requestedPackage;
Class<?> activityClass = chooseArtemisActivity(effectivePackage, path);
Intent i = new Intent(context, activityClass);
Log.i("EmulatorLauncher", "ARTEMIS_SCOPED_V2 pkg=" + requestedPackage + " effectivePkg=" + effectivePackage + " activity=" + activityClass.getSimpleName() + " root=" + gamePath + " target=" + launchTarget + " resolved=" + resolvedPath + " path=" + path + " scoped=" + scopedSaveDir + " saveName=" + saveName);
if (path != null && !path.isEmpty()) {
// The embedded Artemis activity reads getIntent().getStringExtra("path") directly
// and returns it from getExternalFilesDir(). It expects a normal filesystem path.
i.putExtra("path", path);
i.putExtra("gamePath", path);
}
i.putExtra("rootUri", gamePath);
i.putExtra("launchTarget", launchTarget);
i.putExtra("launchMode", "internal.artemis");
i.putExtra("orientation", 6);
i.putExtra("scopedSaveDir", scopedSaveDir);
i.putExtra("scopedSaveName", saveName);
i.putExtra("artemisAutoFallback", autoFallback);
i.putExtra("artemisFallbackStage", artemisFallbackStage(effectivePackage));
i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
appendThemeColors(i, context);
return i;
}

    private static String preferredArtemisPackage(Context context, String requestedPackage, String rootPath) {
        if (context == null || rootPath == null || rootPath.trim().isEmpty()) return requestedPackage;
        String saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(artemisEnginePreferenceKey(rootPath), null);
        return saved == null || saved.trim().isEmpty() ? requestedPackage : saved;
    }

    private static String artemisEnginePreferenceKey(String rootPath) {
        return KEY_ARTEMIS_ENGINE_PREFIX + Integer.toHexString(rootPath.hashCode());
    }

    private static int artemisFallbackStage(String packageName) {
        String pkg = packageName == null ? "" : packageName.trim().toLowerCase(Locale.ROOT);
        if (pkg.contains("compat.v2") || pkg.contains("compatible_v2") || pkg.endsWith(".2")) return 2;
        if (pkg.contains("compat")) return 1;
        return 0;
    }

    public static Intent buildInternalArtemisIntent(Context context, String gamePath, String launchTarget) {
        return buildInternalArtemisIntent(context, "internal.artemis", gamePath, launchTarget);
    }

    private static class ArtemisMirror {
final String rootPath;
ArtemisMirror(String rootPath) {
this.rootPath = rootPath;
}
}

private static ArtemisMirror prepareArtemisScopedMirror(Context context, String rootPath, String saveName) {
if (context == null || rootPath == null || rootPath.trim().isEmpty()) return null;
try {
File sourceRoot = new File(rootPath);
if (!sourceRoot.isDirectory()) return null;
File internal = context.getFilesDir();
File external = context.getExternalFilesDir(null);
if (internal == null || external == null) return null;
String name = (saveName == null || saveName.trim().isEmpty()) ? safeSaveName(rootPath) : saveName;
File mirrorRoot = new File(new File(internal, "artemis_mirror"), name);
File saveRoot = new File(new File(external, "save"), name);
if (!mirrorRoot.exists() && !mirrorRoot.mkdirs()) return null;
if (!saveRoot.exists() && !saveRoot.mkdirs()) return null;
importArtemisExternalSaves(saveRoot, mirrorRoot);
File[] children = sourceRoot.listFiles();
int linkCount = 0;
int skippedSaveCount = 0;
if (children != null) {
for (File child : children) {
if (child == null) continue;
String childName = child.getName();
if (childName == null || childName.isEmpty()) continue;
File link = new File(mirrorRoot, childName);
if (!isArtemisResourceName(childName)) {
if (isSymlink(link)) deleteRecursively(link);
skippedSaveCount++;
continue;
}
if (ensureSymlink(link, child)) linkCount++;
}
}
if (children != null && children.length > 0 && linkCount == 0) return null;
startArtemisSaveObserver(mirrorRoot, saveRoot);
Log.i("EmulatorLauncher", "Artemis scoped mirror ready source=" + rootPath + " mirror=" + mirrorRoot.getAbsolutePath() + " save=" + saveRoot.getAbsolutePath() + " links=" + linkCount + " skippedSave=" + skippedSaveCount);
return new ArtemisMirror(mirrorRoot.getAbsolutePath());
} catch (Throwable t) {
Log.w("EmulatorLauncher", "prepare Artemis scoped mirror failed root=" + rootPath, t);
return null;
}
}

private static void importArtemisExternalSaves(File saveRoot, File mirrorRoot) {
int count = copyRegularFiles(saveRoot, mirrorRoot, false);
if (count > 0) Log.i("EmulatorLauncher", "Artemis imported external saves count=" + count + " from=" + saveRoot + " to=" + mirrorRoot);
}

private static void startArtemisSaveObserver(File mirrorRoot, File saveRoot) {
if (mirrorRoot == null || saveRoot == null) return;
try {
final String mirrorPath = mirrorRoot.getAbsolutePath();
final String savePath = saveRoot.getAbsolutePath();
FileObserver observer = new FileObserver(mirrorPath, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.CREATE) {
@Override
public void onEvent(int event, String path) {
try {
copyRegularFiles(new File(mirrorPath), new File(savePath), true);
} catch (Throwable t) {
Log.w("EmulatorLauncher", "Artemis realtime save export failed", t);
}
}
};
observer.startWatching();
ARTEMIS_SAVE_OBSERVERS.add(observer);
Log.i("EmulatorLauncher", "Artemis save observer started mirror=" + mirrorPath + " save=" + savePath);
} catch (Throwable t) {
Log.w("EmulatorLauncher", "Artemis save observer start failed mirror=" + mirrorRoot + " save=" + saveRoot, t);
}
}

private static int copyRegularFiles(File fromDir, File toDir, boolean onlyNewer) {
if (fromDir == null || toDir == null || !fromDir.isDirectory()) return 0;
if (!toDir.exists() && !toDir.mkdirs()) return 0;
File[] files = fromDir.listFiles();
if (files == null) return 0;
int count = 0;
for (File src : files) {
if (src == null || !src.isFile() || isSymlink(src)) continue;
File dst = new File(toDir, src.getName());
if (onlyNewer && dst.exists() && dst.lastModified() >= src.lastModified() && dst.length() == src.length()) continue;
if (copyFile(src, dst)) count++;
}
return count;
}

private static boolean copyFile(File src, File dst) {
try {
File parent = dst.getParentFile();
if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
byte[] buffer = new byte[64 * 1024];
try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst, false)) {
int read;
while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
}
dst.setLastModified(src.lastModified());
return true;
} catch (Throwable t) {
Log.w("EmulatorLauncher", "copy file failed " + src + " -> " + dst, t);
return false;
}
}

private static int copyRegularFilesRecursively(File fromDir, File toDir, boolean onlyNewer) {
if (fromDir == null || toDir == null || !fromDir.isDirectory()) return 0;
if (!toDir.exists() && !toDir.mkdirs()) return 0;
File[] children = fromDir.listFiles();
if (children == null) return 0;
int count = 0;
for (File child : children) {
if (child == null || isSymlink(child)) continue;
File target = new File(toDir, child.getName());
if (child.isDirectory()) {
count += copyRegularFilesRecursively(child, target, onlyNewer);
} else if (child.isFile()) {
if (onlyNewer && target.exists() && target.lastModified() >= child.lastModified() && target.length() == child.length()) continue;
if (copyFile(child, target)) count++;
}
}
return count;
}

private static boolean isArtemisResourceName(String name) {
if (name == null) return false;
String n = name.trim().toLowerCase(Locale.ROOT);
if (n.equals("system") || n.equals("movie")) return true;
if (n.equals("artemisengine.exe") || n.equals("system.ini")) return true;
if (n.startsWith("root.pfs")) return true;
return n.endsWith(".pfs") || n.endsWith(".xp3") || n.endsWith(".arc") || n.endsWith(".pak") || n.endsWith(".dat.arc");
}

private static String safeSaveName(String rootPath) {
        try {
            if (rootPath == null || rootPath.trim().isEmpty()) return "default";
            File f = new File(rootPath);
            String name = f.getName();
            if (name == null || name.trim().isEmpty()) name = String.valueOf(Math.abs(rootPath.hashCode()));
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            return name.isEmpty() ? "default" : name;
        } catch (Throwable ignored) {
            return "default";
        }
    }

    private static Class<?> chooseArtemisActivity(String packageName, String rootPath) {
String pkg = packageName == null ? "" : packageName.trim().toLowerCase(Locale.ROOT);
if (pkg.contains("compat.v2") || pkg.contains("compatible_v2") || pkg.endsWith(".2")) return com.akira.tyranoemu.remote.ArtemisActivityV3.class;
if (pkg.contains("compat")) return com.akira.tyranoemu.remote.ArtemisActivityV2.class;
return com.akira.tyranoemu.remote.ArtemisActivityV1.class;
}

    /**
     * Resolves the same save directory used by the built-in engine launch path.
     * KRKR, Artemis and ONS are intentionally fixed to app-scoped saves; their
     * existing directory-name rules are preserved for compatibility.
     */
    public static ActualSaveLocation resolveActualSaveLocation(Context context, EngineType engine,
                                                                 String rootUri, String launchTarget) {
        if (context == null) return ActualSaveLocation.unavailable("应用上下文不可用");
        if (engine == null) return ActualSaveLocation.unavailable("游戏引擎信息不可用");
        try {
            switch (engine) {
                case KIRIKIRI: {
                    String resolvedPath = resolveInternalKrkrPath(context, rootUri, launchTarget);
                    String rawRootPath = stripFileScheme(uriToFilePath(rootUri));
                    String rootPath = krkrRootForPath(rawRootPath, stripFileScheme(resolvedPath));
                    if (!isKrScopedSaveDirEnabled(context)) {
                        if (rootPath == null || rootPath.trim().isEmpty() || rootPath.startsWith("content://")) {
                            return ActualSaveLocation.unavailable("无法解析 KRKR 实际游戏目录");
                        }
                        return ActualSaveLocation.available(new File(rootPath, "savedata"), "KRKR 游戏目录存档");
                    }
                    return krkrScopedSaveLocation(context, rootPath);
                }
                case ARTEMIS: {
                    String rootPath = stripFileScheme(resolveInternalArtemisPath(rootUri, launchTarget));
                    if (!isArtemisScopedSaveDirEnabled(context)) {
                        // In original-directory mode Artemis receives the game root as
                        // getExternalFilesDir(). Its save files cannot be separated
                        // safely from game resources, so do not let bulk save
                        // import/export operate on that whole directory.
                        return ActualSaveLocation.unavailable("Artemis 已使用游戏原始目录，无法安全识别存档文件");
                    }
                    return appScopedSaveLocation(context, rootPath, "Artemis 独立存档目录");
                }
                case ONS: {
                    String rootPath = resolveInternalOnsGameDirectory(
                            stripFileScheme(uriToFilePath(rootUri)));
                    return resolveOnsSaveLocation(context, rootPath, OnsSettings.load(context).scopedSaveDir);
                }
                case TYRANO: {
                    String gameDirectory = resolveInternalTyranoGameDirectory(rootUri, launchTarget);
                    return resolveTyranoSaveLocation(context, stripFileScheme(gameDirectory),
                            isTyranoScopedSaveDirEnabled(context));
                }
                default:
                    return ActualSaveLocation.unavailable("该内置引擎未提供可管理的存档目录");
            }
        } catch (Throwable t) {
            Log.w("EmulatorLauncher", "resolve actual save location failed engine=" + engine + " root=" + rootUri, t);
            return ActualSaveLocation.unavailable("无法解析实际存档目录");
        }
    }

    /**
     * Returns every directory currently consulted by a built-in engine for one
     * game's saves. KRKR keeps its callback-written files in app storage, but
     * some native games still access <game-root>/savedata directly. Save
     * import/export must operate on both locations as one save set.
     */
    public static List<File> resolveActualSaveDirectories(Context context, EngineType engine,
                                                           String rootUri, String launchTarget) {
        java.util.LinkedHashMap<String, File> directories = new java.util.LinkedHashMap<>();
        ActualSaveLocation primary = resolveActualSaveLocation(context, engine, rootUri, launchTarget);
        addActualSaveDirectory(directories, primary == null ? null : primary.directory);
        if (engine == EngineType.KIRIKIRI && isKrScopedSaveDirEnabled(context)) {
            try {
                String resolved = resolveInternalKrkrPath(context, rootUri, launchTarget);
                String rawRoot = stripFileScheme(uriToFilePath(rootUri));
                String root = krkrRootForPath(rawRoot, stripFileScheme(resolved));
                if (root != null && !root.trim().isEmpty() && !root.startsWith("content://")) {
                    addActualSaveDirectory(directories, new File(root, "savedata"));
                }
            } catch (Throwable t) {
                Log.w("EmulatorLauncher", "resolve KRKR native save directory failed root=" + rootUri, t);
            }
        }
        return new ArrayList<>(directories.values());
    }

    private static void addActualSaveDirectory(java.util.LinkedHashMap<String, File> output, File directory) {
        if (output == null || directory == null) return;
        try {
            File canonical = directory.getCanonicalFile();
            output.put(canonical.getPath(), canonical);
        } catch (IOException ignored) {
            output.put(directory.getAbsolutePath(), directory.getAbsoluteFile());
        }
    }

    private static ActualSaveLocation appScopedSaveLocation(Context context, String rootPath, String description) {
        if (rootPath == null || rootPath.trim().isEmpty() || rootPath.startsWith("content://")) {
            return ActualSaveLocation.unavailable("无法解析游戏本地目录");
        }
        File external = context.getExternalFilesDir(null);
        if (external == null) return ActualSaveLocation.unavailable("应用独立存储目录不可用");
        return ActualSaveLocation.available(new File(new File(external, "save"), safeSaveName(rootPath)), description);
    }

    private static ActualSaveLocation resolveOnsSaveLocation(Context context, String rootPath,
                                                              boolean scopedSaveDir) {
        File directory = scopedSaveDir
                ? OnsSettings.resolveScopedSaveDirectory(context, rootPath)
                : OnsSettings.resolveGameSaveDirectory(rootPath);
        if (directory == null) {
            return ActualSaveLocation.unavailable(scopedSaveDir
                    ? "ONS 应用独立存储目录不可用"
                    : "ONS 游戏目录不可用或不是可写的本地目录");
        }
        return ActualSaveLocation.available(directory,
                scopedSaveDir ? "ONS 应用独立存档目录" : "ONS 游戏内存档目录");
    }

    private static ActualSaveLocation resolveTyranoSaveLocation(Context context, String gameDirectory,
                                                                 boolean scopedSaveDir) {
        if (gameDirectory == null || gameDirectory.trim().isEmpty()
                || gameDirectory.startsWith("content://")) {
            return ActualSaveLocation.unavailable("无法解析 Tyrano 实际游戏目录");
        }
        try {
            File root = new File(stripFileScheme(gameDirectory)).getCanonicalFile();
            if (!root.isDirectory()) {
                return ActualSaveLocation.unavailable("Tyrano 游戏目录不可用");
            }
            if (scopedSaveDir) {
                File external = context == null ? null : context.getExternalFilesDir(null);
                if (external == null) return ActualSaveLocation.unavailable("Tyrano 应用独立存储目录不可用");
                File directory = new File(new File(new File(external, "save"), "tyrano"),
                        safeSaveName(root.getAbsolutePath()));
                return ActualSaveLocation.available(directory, "Tyrano 应用独立存档目录");
            }
            File directory = new File(root, "savedata").getCanonicalFile();
            if (!directory.getPath().startsWith(root.getPath() + File.separator)) {
                return ActualSaveLocation.unavailable("Tyrano 游戏内存档目录无效");
            }
            return ActualSaveLocation.available(directory, "Tyrano 游戏内存档目录");
        } catch (Throwable t) {
            return ActualSaveLocation.unavailable("无法解析 Tyrano 实际游戏目录");
        }
    }

    /** KRKR keeps its original game root and redirects only savedata to this private directory. */
    private static ActualSaveLocation krkrScopedSaveLocation(Context context, String rootPath) {
        if (rootPath == null || rootPath.trim().isEmpty() || rootPath.startsWith("content://")) {
            return ActualSaveLocation.unavailable("无法解析游戏本地目录");
        }
        File internal = context.getFilesDir();
        if (internal == null) return ActualSaveLocation.unavailable("应用内部存储目录不可用");
        File mirrorRoot = new File(new File(internal, "krkr_mirror"), safeSaveName(rootPath));
        return ActualSaveLocation.available(new File(mirrorRoot, "savedata"), "KRKR 独立存档目录");
    }

    private static boolean isKrScopedSaveDirEnabled(Context context) {
        return context == null || context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("kr_scoped_save_dir", true);
    }

    private static boolean isArtemisScopedSaveDirEnabled(Context context) {
        return context == null || context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("artemis_scoped_save_dir", true);
    }

    private static boolean isTyranoScopedSaveDirEnabled(Context context) {
        return context == null || context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("tyrano_scoped_save_dir", true);
    }

private static String resolveInternalArtemisPath(String rootUri, String launchTarget) {
        // Artemis launches by game directory. The .pfs file is only used for detection.
        String rootPath = uriToFilePath(rootUri);
        if (rootPath == null || rootPath.isEmpty()) return rootUri;
        return rootPath;
    }

    private static String stripFileScheme(String path) {
        if (path == null) return null;
        return path.startsWith("file://") ? path.substring("file://".length()) : path;
    }

    private static String toFileUrlIfNeeded(String path) {
        if (path == null || path.isEmpty()) return path;
        if (path.startsWith("file://")) return path;
        if (path.startsWith("/")) return "file://" + path;
        return path;
    }

    public static Intent buildInternalKrkrIntent(Context context, String gamePath, String launchTarget) {
        return buildInternalKrkrIntent(context, gamePath, launchTarget, false, "auto", false);
    }

    public static Intent buildInternalKrkrIntent(Context context, String gamePath, String launchTarget, boolean originMode) {
        return buildInternalKrkrIntent(context, gamePath, launchTarget, originMode, "auto", false);
    }

    public static Intent buildInternalKrkrIntent(Context context, String gamePath, String launchTarget, boolean originMode, String engineVersion, boolean safFileFallback) {
        String resolvedPath = originMode ? null : resolveInternalKrkrPath(context, gamePath, launchTarget);
        String rawRootPath = stripFileScheme(uriToFilePath(gamePath));
        String path = resolvedPath == null ? null : stripFileScheme(resolvedPath);
        String rootPath = krkrRootForPath(rawRootPath, path);
        boolean globalScopedSaveDir = isKrScopedSaveDirEnabled(context);
        boolean autoSdCardMirror = false;
        boolean scopedSaveDir = globalScopedSaveDir;
        ActualSaveLocation saveLocation = originMode ? null
                : resolveActualSaveLocation(context, EngineType.KIRIKIRI, gamePath, launchTarget);
        if (!originMode && (saveLocation == null || !saveLocation.available || saveLocation.directory == null)) {
            throw new IllegalStateException(saveLocation == null ? "无法解析实际存档目录" : saveLocation.description);
        }
        // Do not derive this from the final directory name: for KRKR it is always
        // "savedata". The game identifier remains the original root directory name.
        String saveName = safeSaveName(rootPath);
        String scopedSaveRoot = null;
        if (!originMode && scopedSaveDir) {
            if (!prepareKrkrScopedSaveDirectory(context, saveLocation.directory, saveName)) {
                throw new IllegalStateException("无法创建 KRKR 应用独立存档目录");
            }
            // Keep the executable and every read-only asset on the original
            // game path. Some titles inspect their own root with native
            // Storages APIs, which do not consistently follow app-private
            // symlinks. Only savedata is redirected by the engine bridge.
            scopedSaveRoot = saveLocation.directory.getAbsolutePath();
            Log.i("EmulatorLauncher", "KRKR direct save redirect root=" + rootPath
                    + " path=" + path + " save=" + scopedSaveRoot
                    + " globalScoped=" + globalScopedSaveDir);
        }
        boolean use134 = !originMode && shouldUseKrkr134(rootPath, engineVersion);
        Intent i = new Intent(context, originMode ? org.tvp.kirikiri2.KR2Activity.class : (use134 ? com.akira.tyranoemu.remote.Kirikiroid134.class : com.akira.tyranoemu.remote.Kirikiroid139.class));
        Log.i("EmulatorLauncher", "internal KRKR originMode=" + originMode + " engineVersion=" + normalizeKrkrEngineVersion(engineVersion) + " use134=" + use134 + " root=" + gamePath + " target=" + launchTarget + " resolved=" + resolvedPath + " rootPath=" + rootPath + " globalScoped=" + globalScopedSaveDir + " scoped=" + scopedSaveDir + " autoSdMirror=" + autoSdCardMirror);
        if (path != null && !path.isEmpty()) {
            // 普通模式也使用普通文件路径，让默认启动链更接近原生 KRKR / TY 的读取方式。
            i.putExtra("path", path);
            i.putExtra("gamePath", path);
        }
        if (rootPath != null && !rootPath.isEmpty()) {
            i.putExtra("projectRoot", rootPath);
            i.putExtra("gamedir", rootPath);
        }
        i.putExtra("rootUri", gamePath);
        i.putExtra("launchTarget", launchTarget);
        i.putExtra("originMode", originMode);
        i.putExtra("focus", "true");
        i.putExtra("krEngineVersion", use134 ? "1.3.4" : "1.3.9");
        i.putExtra("orientation", 6);
        i.putExtra("launchMode", originMode ? "internal.krkr.origin" : "internal.krkr");
        i.putExtra("scopedSaveDir", scopedSaveDir);
        i.putExtra("globalScopedSaveDir", globalScopedSaveDir);
        i.putExtra("autoKrMirror", autoSdCardMirror);
        i.putExtra("terminateKrProcessOnDestroy", scopedSaveDir || safFileFallback || autoSdCardMirror);
        i.putExtra("scopedSaveName", saveName);
        if (scopedSaveRoot != null) i.putExtra("scopedSaveRoot", scopedSaveRoot);
        i.putExtra("safFileFallback", safFileFallback);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        appendThemeColors(i, context);
        return i;
    }

    private static boolean prepareKrkrScopedSaveDirectory(Context context, File saveDirectory, String saveName) {
        if (context == null || saveDirectory == null) return false;
        try {
            File internal = context.getFilesDir();
            File external = context.getExternalFilesDir(null);
            if (internal == null) return false;
            String name = saveName == null ? "" : saveName.trim();
            if (name.isEmpty()) return false;
            if (isSymlink(saveDirectory) && !saveDirectory.delete()) return false;
            if (saveDirectory.exists() && !saveDirectory.isDirectory()) return false;
            if (!saveDirectory.exists() && !saveDirectory.mkdirs()) return false;
            if (external != null) {
                File legacySaveRoot = new File(new File(external, "save"), name);
                int migrated = copyRegularFilesRecursively(legacySaveRoot, saveDirectory, true);
                if (migrated > 0) Log.i("EmulatorLauncher", "migrated KRKR external saves count=" + migrated + " from=" + legacySaveRoot + " to=" + saveDirectory);
            }
            File previousInternalSaveRoot = new File(new File(internal, "save"), name);
            int migrated = copyRegularFilesRecursively(previousInternalSaveRoot, saveDirectory, true);
            if (migrated > 0) Log.i("EmulatorLauncher", "migrated KRKR internal saves count=" + migrated + " from=" + previousInternalSaveRoot + " to=" + saveDirectory);
            return true;
        } catch (Throwable t) {
            Log.w("EmulatorLauncher", "prepare KRKR scoped save directory failed save=" + saveDirectory, t);
            return false;
        }
    }

    private static boolean ensureSymlink(File link, File target) {
        try {
            if (link.exists()) {
                if (isSymlinkTo(link, target)) return true;
                deleteRecursively(link);
            }
            Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
            return isSymlinkTo(link, target);
        } catch (ErrnoException e) {
            Log.w("EmulatorLauncher", "symlink failed " + link + " -> " + target, e);
            return false;
        }
    }

    private static boolean isSymlinkTo(File link, File target) {
        try {
            String current = Os.readlink(link.getAbsolutePath());
            return target.getAbsolutePath().equals(current);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory() && !isSymlink(file)) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) Log.w("EmulatorLauncher", "delete failed " + file.getAbsolutePath());
    }

    private static boolean isSymlink(File file) {
        try {
            Os.readlink(file.getAbsolutePath());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean shouldUseKrkr134(String rootPath, String engineVersion) {
        String mode = normalizeKrkrEngineVersion(engineVersion);
        return "1.3.4".equals(mode);
    }

    private static String normalizeKrkrEngineVersion(String engineVersion) {
        String mode = engineVersion == null ? "auto" : engineVersion.trim().toLowerCase(Locale.ROOT);
        if (mode.equals("134") || mode.equals("1.3.4") || mode.equals("kr134") || mode.equals("kirikiroid134")) return "1.3.4";
        if (mode.equals("139") || mode.equals("1.3.9") || mode.equals("kr139") || mode.equals("kirikiroid139")) return "1.3.9";
        return "auto";
    }

    // KR 引擎版本只由全局设置决定，不再读取单个游戏目录标记。

    private static String resolveInternalKrkrPath(Context context, String rootUri, String launchTarget) {
        // 对齐 Kirikiroid 的思路：
        // 1) 优先解析为真正的入口文件
        // 2) 其次才退回目录本身
        String rootPath = stripFileScheme(uriToFilePath(rootUri));
        if (rootPath == null || rootPath.isEmpty()) return rootUri;
        String target = launchTarget == null ? "" : launchTarget.trim();
        if (target.isEmpty() || "[游戏目录]".equals(target) || "DIR".equalsIgnoreCase(target)) {
            return rootPath;
        }
        if ("XP3_FIRST".equalsIgnoreCase(target)) {
            String firstXp3 = findFirstChildBySuffix(rootPath, ".xp3");
            if (firstXp3 != null) return firstXp3;
            String safFirstXp3 = findKrkrPreferredEntryFromTree(context, rootUri, rootPath);
            return safFirstXp3 == null ? rootPath : safFirstXp3;
        }
        if (target.startsWith("/")) {
            File f = new File(target);
            return f.isFile() ? f.getAbsolutePath() : target;
        }
        File root = new File(rootPath);
        File targetFile = new File(root, target);
        if (targetFile.isFile()) return targetFile.getAbsolutePath();
        if (targetFile.isDirectory()) return targetFile.getAbsolutePath();
        String safTarget = findKrkrTargetFromTree(context, rootUri, rootPath, target);
        if (safTarget != null) return safTarget;
        if (target.endsWith(".xp3") || target.endsWith(".tjs") || target.endsWith(".exe") || target.endsWith(".dll")) {
            return targetFile.getAbsolutePath();
        }
        return rootPath;
    }

    private static String findKrkrPreferredEntry(String rootPath) {
        if (rootPath == null || rootPath.trim().isEmpty()) return null;
        try {
            File root = new File(rootPath);
            if (!root.isDirectory()) return null;
            String[] names = new String[]{"data.xp3", "startup.tjs", "patch.xp3"};
            for (String name : names) {
                File f = new File(root, name);
                if (f.isFile()) return f.getAbsolutePath();
            }
        } catch (Throwable ignored) { }
        return findFirstChildBySuffix(rootPath, ".xp3");
    }

    private static String findKrkrPreferredEntryFromTree(Context context, String rootUri, String rootPath) {
        String[] names = new String[]{"data.xp3", "startup.tjs", "patch.xp3"};
        for (String name : names) {
            String p = findKrkrTargetFromTree(context, rootUri, rootPath, name);
            if (p != null) return p;
        }
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(context, Uri.parse(rootUri));
            if (dir == null || !dir.isDirectory()) return null;
            DocumentFile[] files = dir.listFiles();
            if (files == null) return null;
            for (DocumentFile file : files) {
                if (file == null || !file.isFile()) continue;
                String name = file.getName();
                if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".xp3")) {
                    return rootPath.endsWith("/") ? rootPath + name : rootPath + "/" + name;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String findKrkrTargetFromTree(Context context, String rootUri, String rootPath, String target) {
        if (context == null || rootUri == null || target == null || target.trim().isEmpty()) return null;
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(context, Uri.parse(rootUri));
            if (dir == null || !dir.isDirectory()) return null;
            String[] parts = target.split("/");
            DocumentFile current = dir;
            for (String part : parts) {
                if (part == null || part.isEmpty() || ".".equals(part)) continue;
                current = current == null ? null : current.findFile(part);
                if (current == null) return null;
            }
            if (current != null && current.exists() && current.isFile()) {
                String cleanTarget = target;
                while (cleanTarget.startsWith("/")) cleanTarget = cleanTarget.substring(1);
                return rootPath.endsWith("/") ? rootPath + cleanTarget : rootPath + "/" + cleanTarget;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String krkrRootForPath(String rawRootPath, String launchPath) {
        try {
            if (rawRootPath != null && !rawRootPath.trim().isEmpty()) {
                File raw = new File(rawRootPath);
                if (raw.isDirectory()) return raw.getAbsolutePath();
                File parent = raw.getParentFile();
                if (parent != null) return parent.getAbsolutePath();
            }
            if (launchPath != null && !launchPath.trim().isEmpty()) {
                File launch = new File(launchPath);
                if (launch.isDirectory()) return launch.getAbsolutePath();
                File parent = launch.getParentFile();
                if (parent != null) return parent.getAbsolutePath();
            }
        } catch (Throwable ignored) { }
        return rawRootPath;
    }

    private static boolean isExternalSdCardKrPath(String path) {
        try {
            if (path == null || path.trim().isEmpty()) return false;
            String p = stripFileScheme(path.trim());
            if (p == null || !p.startsWith("/storage/")) return false;
            String lower = p.toLowerCase(Locale.ROOT);
            if (lower.equals("/storage/emulated") || lower.startsWith("/storage/emulated/")) return false;
            String rest = p.substring("/storage/".length());
            int slash = rest.indexOf('/');
            String volume = slash >= 0 ? rest.substring(0, slash) : rest;
            if (volume == null || volume.trim().isEmpty()) return false;
            String v = volume.toLowerCase(Locale.ROOT);
            if ("self".equals(v) || "emulated".equals(v)) return false;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String findFirstChildBySuffix(String rootPath, String suffix) {
        if (rootPath == null || rootPath.trim().isEmpty() || suffix == null || suffix.trim().isEmpty()) return null;
        try {
            File root = new File(rootPath);
            File[] children = root.listFiles();
            if (children == null) return null;
            String lowerSuffix = suffix.toLowerCase(Locale.ROOT);
            for (File child : children) {
                if (child == null || !child.isFile()) continue;
                String name = child.getName();
                if (name != null && name.toLowerCase(Locale.ROOT).endsWith(lowerSuffix)) return child.getAbsolutePath();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String toKrkrFileUrl(String path) {
        if (path == null || path.isEmpty()) return path;
        if (path.startsWith("file://")) return path;
        if (path.startsWith("/")) return "file://" + path;
        return path;
    }

    private static String uriToFilePath(String uriText) {
        if (uriText == null || uriText.trim().isEmpty()) return uriText;
        if (uriText.startsWith("/")) return uriText;
        try {
            Uri uri = Uri.parse(uriText);
            if ("file".equalsIgnoreCase(uri.getScheme())) return uri.getPath();
            if ("content".equalsIgnoreCase(uri.getScheme())) {
                String docId = null;
                String path = uri.getPath();
                boolean hasDocumentPart = path != null && path.contains("/document/");
                if (hasDocumentPart) {
                    try { docId = DocumentsContract.getDocumentId(uri); } catch (Throwable ignored) { }
                }
                if (docId == null || docId.isEmpty()) {
                    try { docId = DocumentsContract.getTreeDocumentId(uri); } catch (Throwable ignored) { }
                }
                if (docId == null || docId.isEmpty()) {
                    try { docId = DocumentsContract.getDocumentId(uri); } catch (Throwable ignored) { }
                }
                if (docId != null) {
                    int colon = docId.indexOf(':');
                    String volume = colon >= 0 ? docId.substring(0, colon) : docId;
                    String rel = colon >= 0 ? docId.substring(colon + 1) : "";
                    if ("primary".equalsIgnoreCase(volume)) return rel.isEmpty() ? "/storage/emulated/0" : "/storage/emulated/0/" + rel;
                    if (volume != null && !volume.isEmpty()) return rel.isEmpty() ? "/storage/" + volume : "/storage/" + volume + "/" + rel;
                }
            }
        } catch (Throwable ignored) { }
        return uriText;
    }

    /**
     * 构建启动PSP游戏的Intent
     * 使用PPSSPP的PpssppActivity来启动PSP游戏
     */
    public static Intent buildInternalPspIntent(Context context, String gameUri, String launchTarget) {
        // 直接使用gameUri，它可能是file://或content://格式
        Uri gameUriParsed = Uri.parse(gameUri);
        
        // 如果是文件路径（以/开头），转换为file:// URI
        if (gameUri.startsWith("/")) {
            gameUriParsed = Uri.parse("file://" + gameUri);
        }
        
        // 创建Intent，使用VIEW action和mimeType
        // PPSSPP支持file和content scheme，mimeType为*/*
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(gameUriParsed, "*/*");
        
        // 设置PPSSPP的包名和Activity
        intent.setClassName("org.ppsspp.ppsspp", "org.ppsspp.ppsspp.PpssppActivity");
        
        // 添加必要的flags
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                       Intent.FLAG_GRANT_READ_URI_PERMISSION | 
                       Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        
        Log.i("EmulatorLauncher", "Built PSP intent uri=" + gameUriParsed);
        return intent;
    }
    
    /**
     * 检查是否是PPSSPP包
     */
    private static boolean isPPSSPPPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.trim().toLowerCase(Locale.ROOT);
        return p.contains("ppsspp") || p.equals("org.ppsspp.ppsspp");
    }
    
    /**
     * 启动PSP游戏
     */
    public static boolean launchPspGame(Context context, String gameUri, String launchTarget) {
        // 先检查PPSSPP是否安装
        if (!isPPSSPPInstalled(context)) {
            Log.w("EmulatorLauncher", "PPSSPP is not installed");
            return false;
        }
        
        try {
            Intent intent = buildInternalPspIntent(context, gameUri, launchTarget);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.e("EmulatorLauncher", "Failed to launch PSP game", e);
            return false;
        }
    }
    
    /**
     * 检查PPSSPP是否已安装
     */
    public static boolean isPPSSPPInstalled(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            pm.getPackageInfo("org.ppsspp.ppsspp", PackageManager.GET_ACTIVITIES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * 构建启动 Nintendo 3DS 游戏的 Intent
     * 使用 Citra/Azahar/Lime3DS 的 EmulationActivity 来启动 3DS 游戏。
     * 注意:AzaharPlus 的 Manifest 限定 mimeType 为 application/octet-stream,
     * scheme 为 content,因此必须使用 content:// URI 和该 MIME 类型。
     */
    public static Intent buildInternalCitraIntent(Context context, String gameUri, String launchTarget) {
        Uri gameUriParsed = Uri.parse(gameUri);
        // 如果是文件路径(以/开头),转换为 file:// URI
        if (gameUri.startsWith("/")) {
            gameUriParsed = Uri.parse("file://" + gameUri);
        }
        // 创建 Intent,AzaharPlus 的 Intent Filter 限定 mimeType=application/octet-stream
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(gameUriParsed, "application/octet-stream");
        // 显式指定 Citra/Azahar/Lime3DS 的 EmulationActivity
        intent.setClassName("io.github.azaharplus.android",
                "org.citra.citra_emu.activities.EmulationActivity");
        // 添加必要的 flags(只需读权限,3DS 游戏以只读方式加载)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                       Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Log.i("EmulatorLauncher", "Built Citra intent uri=" + gameUriParsed);
        return intent;
    }

    /**
     * 检查是否是 Citra/Azahar/Lime3DS 包
     */
    private static boolean isCitraPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.trim().toLowerCase(Locale.ROOT);
        return p.contains("lime3ds") || p.contains("citra") || p.contains("azahar");
    }

    /**
     * 启动 Nintendo 3DS 游戏
     */
    public static boolean launchCitraGame(Context context, String gameUri, String launchTarget) {
        if (!isCitraInstalled(context)) {
            Log.w("EmulatorLauncher", "Citra/Azahar is not installed");
            return false;
        }
        try {
            Intent intent = buildInternalCitraIntent(context, gameUri, launchTarget);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.e("EmulatorLauncher", "Failed to launch Nintendo 3DS game", e);
            return false;
        }
    }

    /**
     * 检查 Citra/Azahar/Lime3DS 是否已安装。
     * 依次探测 release 包名和 debug 变体包名。
     */
    public static boolean isCitraInstalled(Context context) {
        String[] candidates = new String[]{
                "io.github.azaharplus.android",
                "io.github.azaharplus.android.debug",
                "org.citra.citra_emu",
                "org.azahar_emu.azahar"
        };
        PackageManager pm = context.getPackageManager();
        for (String pkg : candidates) {
            try {
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
                return true;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return false;
    }
    
    /**
     * 获取PPSSPP的下载Intent（跳转到应用商店）
     */
    public static Intent getPPSSPPDownloadIntent() {
        try {
            // 尝试打开Google Play商店
            return new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=org.ppsspp.ppsspp"));
        } catch (Exception e) {
            // 如果无法打开商店，打开浏览器
            return new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=org.ppsspp.ppsspp"));
        }
    }
}
