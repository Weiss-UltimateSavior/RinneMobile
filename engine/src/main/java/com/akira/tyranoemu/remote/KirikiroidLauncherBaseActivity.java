package com.akira.tyranoemu.remote;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;
import bridge.NativeBridge;
import org.tvp.kirikiri2.KR2Activity;

/**
 * Base activity for Kirikiroid134/139 KRKR engine launches.
 *
 * ARCHITECTURE NOTE:
 * This class historically used reflection to call com.apps.LauncherActivity for theme
 * colors (primary color, dark mode). This creates a reverse dependency from the engine
 * library module to the app application module, which violates Gradle module dependency
 * direction. We are migrating to passing these values via Intent extras:
 *   - "primaryColor" (int): theme primary color
 *   - "darkMode" (boolean): whether dark mode is active
 *
 * The reflection calls are retained as deprecated fallbacks for backward compatibility
 * with com.apps callers that have not yet been updated to pass these extras. Once all
 * callers are migrated, the reflection paths will be removed.
 */
public abstract class KirikiroidLauncherBaseActivity extends KR2Activity {
    private static final String TAG = "Kirikiroid2";
    private static final long SAFE_FALLBACK_REVEAL_MS = 20_000L;
    @SuppressLint("StaticFieldLeak") // Dedicated process activity; cleared immediately before process termination.
    public static Context app;
    private FrameLayout mask;
    private TextView maskMessage;
    private TextView maskHint;
    private ProgressBar loadingSpinner;
    private volatile boolean nativeBridgeInitialized;
    private volatile boolean destroyed;
    private volatile boolean firstFrameRendered;
    private volatile boolean launchDispatched;
    private volatile boolean launchSucceeded;
    private volatile boolean maskRevealRequested;
    private int launchReadinessFrames;
    private String pendingGamePath;
    private boolean pendingMaps;

    @Override
    public void onCreate(Bundle bundle) {
        doSetSystemUiVisibility();
        super.onCreate(bundle);
        app = this;
        if (getIntent().getBooleanExtra("originMode", false)) {
            return;
        }
        int primaryColor = launcherPrimaryColor();
        int backgroundColor = launcherColor("launcher_bg_color", Color.rgb(244, 245, 245));
        int textColor = launcherColor("launcher_text_color", Color.rgb(20, 34, 27));
        int mutedTextColor = launcherColor("launcher_text_muted_color", Color.rgb(130, 144, 138));

        FrameLayout launchMask = new FrameLayout(this);
        launchMask.setBackgroundColor(backgroundColor);
        configureLandscapeLoadingWindow(backgroundColor);
        // Never expose the KRKR shell scene while the selected game is being
        // started. The overlay is removed only after the game's first frames.
        FrameLayout safeContent = new FrameLayout(this);
        launchMask.addView(safeContent, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout loadingPanel = new LinearLayout(this);
        loadingPanel.setOrientation(LinearLayout.VERTICAL);
        loadingPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        loadingPanel.setPadding(dp(22), dp(20), dp(22), dp(16));

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(primaryColor));
        spinner.getIndeterminateDrawable().setColorFilter(primaryColor, PorterDuff.Mode.SRC_IN);
        spinner.setContentDescription("游戏加载中");

        TextView title = new TextView(this);
        title.setText("正在启动游戏");
        title.setTextColor(textColor);
        title.setTextSize(16.0f);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(0);
        loadingPanel.addView(title, titleParams);

        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        spinnerParams.gravity = Gravity.CENTER_HORIZONTAL;
        spinnerParams.topMargin = dp(14);
        loadingPanel.addView(spinner, spinnerParams);

        TextView hint = new TextView(this);
        hint.setText("请稍候，正在准备游戏内容");
        hint.setTextColor(mutedTextColor);
        hint.setTextSize(11.0f);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(10);
        loadingPanel.addView(hint, hintParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        safeContent.addView(loadingPanel, panelParams);
        safeContent.setOnApplyWindowInsetsListener((view, insets) -> {
            // Match PadUi: the background can occupy the whole display, while
            // interactive/readable content stays clear of cutouts and bars.
            safeContent.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        safeContent.requestApplyInsets();
        launchMask.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        this.mask = launchMask;
        this.maskMessage = title;
        this.maskHint = hint;
        this.loadingSpinner = spinner;
        this.mFrameLayout.addView(launchMask);
        NativeBridge.setKrkrGameReadyListener(this::revealGame);
        String path = getIntent().getStringExtra("path");
        if (path != null && path.length() != 0) {
            requestGameLaunch(path, false);
        } else {
            finish();
        }
    }
    @Override
    public void onLoadNativeLibraries() {
        boolean initialized = NativeBridge.initialize(soName());
        nativeBridgeInitialized = initialized;
        Log.i(TAG, "native initialize result=" + initialized + " so=" + soName());
        if (!initialized) {
            Log.e(TAG, "native bridge initialization failed; skip KRKR hook setup");
            return;
        }
        // Do not bypass TVPMainScene's internal delay. It serializes teardown of
        // the selector UI before the game UI is created; forcing it to zero can
        // leave the KRKR shell above an otherwise running game.
        Log.i(TAG, "direct game launch waits for native scene transition so=" + soName());
        Intent intent = getIntent();
        boolean scopedSaveDir = intent != null && intent.getBooleanExtra("scopedSaveDir", false);
        boolean safFileFallback = intent != null && intent.getBooleanExtra("safFileFallback", false);
        if (intent == null) {
            Log.i(TAG, "native interceptor skipped: no launch intent");
            return;
        }

        // The scoped launcher keeps game assets at their original path and
        // provides a real app-private savedata directory through the Java
        // write bridge. Keep native filesystem calls intact: krkr_bridge
        // only hooks open/open64(path, flags), while games also use other write APIs.
        if (scopedSaveDir) {
            Log.i(TAG, "KRKR scoped save uses direct savedata directory; native open interceptor disabled");
            return;
        }

        if (!safFileFallback) {
            Log.i(TAG, "native interceptor skipped: SAF fallback disabled");
            return;
        }
        String prefix = null;
        try {
            String rawPath = intent.getStringExtra("path");
            if (rawPath != null && !rawPath.trim().isEmpty()) {
                String resolved = normalizeKrPath(rawPath);
                File root = new File(resolved);
                if (root.isFile()) root = root.getParentFile();
                if (root != null) {
                    File saveRoot = new File(new File(getExternalFilesDir(null), "save"), safeSaveName(root.getAbsolutePath()));
                    if (saveRoot.exists() || saveRoot.mkdirs()) {
                        Log.i(TAG, "KRKR SAF file fallback hook enabled");
                        prefix = storagePrefix(root.getAbsolutePath());
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolve scoped hook prefix failed", t);
        }
        if (prefix != null) {
            try {
                NativeBridge.interceptor(prefix);
                NativeBridge.relocate();
                Log.i(TAG, "native interceptor enabled prefix=" + prefix);
            } catch (Throwable t) {
                Log.e(TAG, "enable native interceptor failed", t);
            }
        } else {
            Log.w(TAG, "native interceptor skipped: empty prefix");
        }
    }

    private synchronized void requestGameLaunch(String path, boolean maps) {
        if (!nativeBridgeInitialized) {
            Log.e(TAG, "skip launch because native bridge was not initialized");
            showLaunchFailure("KRKR 引擎初始化失败");
            return;
        }
        pendingGamePath = path;
        pendingMaps = maps;
        dispatchPendingLaunchOnGlThread();
    }

    @Override
    protected void onCocosRendererReady() {
        // nativeInit only constructs the scene.  Its initial UI tasks are still
        // queued until the first render pass, so starting a game here lets the
        // shell initialise after (and above) the game scene.
    }

    @Override
    protected void onCocosFrameRendered() {
        if (!firstFrameRendered) firstFrameRendered = true;
        // The file selector is registered a few frames after the renderer. Keep
        // checking until startupFrom can actually dismiss it.
        if (!launchDispatched) dispatchPendingLaunchOnGlThread();
    }

    private synchronized void dispatchPendingLaunchOnGlThread() {
        if (!firstFrameRendered || launchDispatched || pendingGamePath == null || destroyed || isFinishing()) return;
        if (!NativeBridge.isLaunchSceneReady(soName())) {
            launchReadinessFrames++;
            return;
        }
        launchDispatched = true;
        try {
            launchSucceeded = NativeBridge.launch(soName(), pendingGamePath, pendingMaps);
            Log.i(TAG, "renderer-ready launch result=" + launchSucceeded + " path=" + pendingGamePath
                    + " frames=" + launchReadinessFrames);
        } catch (Throwable t) {
            Log.e(TAG, "renderer-ready launch failed", t);
        }
        if (!launchSucceeded) {
            showLaunchFailure("启动失败");
        } else if (mask != null) {
            // Keep the KRKR shell hidden until the native update hook reports
            // that doStartup and the menu transition completed. The fallback
            // includes the engine's original ten-second scene handoff.
            mask.postDelayed(this::revealGame, SAFE_FALLBACK_REVEAL_MS);
        }
    }

    private void revealGame() {
        if (destroyed || mask == null || maskRevealRequested) return;
        // 弹窗未确认时不隐藏启动遮罩，防止引擎在用户确认前就显示游戏画面
        if (KR2Activity.isDialogShowing()) {
            mask.postDelayed(this::revealGame, 500);
            return;
        }
        maskRevealRequested = true;
        mask.post(() -> {
            if (!destroyed && mask != null) {
                Log.i(TAG, "hide KRKR launch mask after game-ready signal");
                mask.animate().alpha(0.0f).setDuration(150L).withEndAction(() -> {
                    if (mask != null) mask.setVisibility(android.view.View.GONE);
                }).start();
            }
        });
    }

    private void showLaunchFailure(String message) {
        runOnUiThread(() -> {
            if (destroyed || isFinishing() || mask == null) return;
            if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
            if (maskMessage != null) maskMessage.setText(message);
            if (maskHint != null) maskHint.setText("请返回后重试");
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void configureLandscapeLoadingWindow(int backgroundColor) {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(backgroundColor);
        window.setNavigationBarColor(backgroundColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!isLauncherDarkMode()) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private int launcherPrimaryColor() {
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("primaryColor")) {
            return intent.getIntExtra("primaryColor", Color.rgb(24, 185, 120));
        }
        // Deprecated reflection fallback: com.apps should pass "primaryColor" via Intent extra.
        // TODO: remove reflection once com.apps migration is complete.
        try {
            Object value = Class.forName("com.apps.LauncherActivity")
                    .getMethod("launcherPrimaryColor", Context.class)
                    .invoke(null, this);
            if (value instanceof Integer) return (Integer) value;
        } catch (Throwable ignored) { }
        return launcherColor("launcher_primary_color", Color.rgb(24, 185, 120));
    }

    @SuppressLint("DiscouragedApi") // Engine cannot compile against the app module's generated R class.
    private int launcherColor(String name, int fallback) {
        Context uiContext = launcherUiContext();
        int id = uiContext.getResources().getIdentifier(name, "color", getPackageName());
        return id == 0 ? fallback : uiContext.getColor(id);
    }

    private Context launcherUiContext() {
        // Deprecated reflection fallback: com.apps should pass "darkMode" via Intent extra.
        // Note: this Context does NOT automatically resolve values-night resources based on
        // the "darkMode" Intent extra; wrapLauncherUiMode in com.apps wraps the Context with
        // a UiModeManager override to force day/night resource resolution. When the reflection
        // fallback is removed, callers passing "darkMode" extra must also apply the night mode
        // override via AppCompatDelegate.setDefaultNightMode() or similar before retrieving colors.
        try {
            Object value = Class.forName("com.apps.LauncherActivity")
                    .getMethod("wrapLauncherUiMode", Context.class)
                    .invoke(null, this);
            if (value instanceof Context) return (Context) value;
        } catch (Throwable ignored) { }
        return this;
    }

    private boolean isLauncherDarkMode() {
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("darkMode")) {
            return intent.getBooleanExtra("darkMode", false);
        }
        // Deprecated reflection fallback: com.apps should pass "darkMode" via Intent extra.
        try {
            Object value = Class.forName("com.apps.LauncherActivity")
                    .getMethod("isLauncherDarkMode", Context.class)
                    .invoke(null, this);
            if (value instanceof Boolean) return (Boolean) value;
        } catch (Throwable ignored) { }
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static String normalizeKrPath(String path) {
        if (path == null) return "";
        String p = path.trim();
        if (p.startsWith("file://")) p = p.substring("file://".length());
        while (p.startsWith("./")) p = p.substring(2);
        if (p.startsWith("storage/")) p = "/" + p;
        return p;
    }

    @SuppressLint("SdCardPath") // Normalizes legacy KR paths before redirecting them through scoped storage.
    private static String storagePrefix(String path) {
        String p = normalizeKrPath(path);
        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.startsWith("/storage/emulated/0/")) return "/storage/emulated/0";
        if (lower.startsWith("/sdcard/")) return "/sdcard";
        if (lower.startsWith("/storage/")) {
            String rest = p.substring("/storage/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) return "/storage/" + rest.substring(0, slash);
        }
        return p;
    }

    private static String safeSaveName(String rootPath) {
        try {
            String path = normalizeKrPath(rootPath);
            File f = new File(path);
            String name = f.getName();
            if (name == null || name.trim().isEmpty()) {
                File parent = f.getParentFile();
                name = parent == null ? "default" : parent.getName();
            }
            name = name == null ? "default" : name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
            return name.isEmpty() ? "default" : name;
        } catch (Throwable ignored) {
            return "default";
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Intent oldIntent = getIntent();
        if (oldIntent == null || intent == null) return;
        String oldPath = oldIntent.getStringExtra("path");
        String newPath = intent.getStringExtra("path");
        if (newPath != null && !newPath.equals(oldPath)) {
            Toast.makeText(this, "已有游戏在运行，请先存档并退出游戏后再启动新游戏", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        setRequestedOrientation(getIntent().getIntExtra("orientation", 6));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        String focus = getIntent().getStringExtra("focus");
        boolean forceFocus = focus != null && Boolean.parseBoolean(focus);
        super.onWindowFocusChanged(hasFocus || forceFocus);
        if (hasFocus || forceFocus) doSetSystemUiVisibility();
    }

    @Override
    @SuppressLint("MissingSuperCall")
    public void onDestroy() {
        try {
            destroyed = true;
            mask = null;
            maskMessage = null;
            maskHint = null;
            loadingSpinner = null;
            NativeBridge.setKrkrGameReadyListener(null);
            if (app == this) app = null;
        } catch (Throwable ignored) { }
        // This Activity has a dedicated process. Do not enter Cocos teardown first:
        // its RenderThread can still lock native state after that state is destroyed.
        Log.i(TAG, "terminate dedicated KR process before Cocos teardown");
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    public abstract String soName();
}
