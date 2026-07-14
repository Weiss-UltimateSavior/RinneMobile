package com.apps;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.Manifest;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import io.reactivex.disposables.Disposable;

import com.apps.PadUi.PadGameModeActivity;
import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherBinding;
import com.yuki.yukihub.launcherbridge.LauncherUpdateBridge;
import com.apps.account.LauncherAccountFragment;
import com.apps.data.LauncherViewModel;
import com.apps.game.LauncherLibraryFragment;
import com.apps.game.LauncherManageFragment;
import com.apps.home.LauncherHomeFragment;
import com.apps.home.LauncherPlaceholderFragment;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;

public class LauncherActivity extends AppCompatActivity {
    private static final long SPLASH_DELAY_MS = 1500L;
    public static final String EXTRA_OPEN_ACCOUNT_LOGIN = "open_account_login";
    static final String APP_PREFS = "yukihub_prefs";
    private static final String KEY_STORAGE_PERMISSION_ASKED = "launcher_storage_permission_asked";
    static final String KEY_LAUNCHER_DARK_MODE = "launcher_dark_mode";
    static final String KEY_LAUNCHER_THEME_STYLE = "launcher_theme_style";
    static final String KEY_LAUNCHER_PARTICLES_ENABLED = "launcher_particles_enabled";
    static final String KEY_LAUNCHER_PARTICLE_STYLE = "launcher_particle_style";
    public static final String PARTICLE_STYLE_FLOATING = "floating";
    public static final String PARTICLE_STYLE_RAIN = "rain";
    public static final String PARTICLE_STYLE_STAR = "star";
    public static final String THEME_STYLE_DEFAULT = "default";
    public static final String THEME_STYLE_RINNE = "rinne";
    public static final String THEME_STYLE_ANRI = "anri";
    public static final String THEME_STYLE_XINHAITIAN = "xinhaitian";
    public static final int RINNE_PRIMARY_COLOR = Color.rgb(216, 169, 201);
    public static final int ANRI_PRIMARY_COLOR = Color.rgb(77, 53, 89);
    public static final int XINHAITIAN_PRIMARY_COLOR = Color.rgb(122, 131, 203);
    public static final int XINHAITIAN_ACCENT_COLOR = Color.rgb(237, 173, 201);

    private ActivityLauncherBinding binding;
    private LauncherViewModel viewModel;
    private LauncherViewModel.NavItem currentNavItem;
    private boolean navIndicatorReady;
    private Disposable splashDelay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_YukiHub_Launcher);
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        // Android 12+ replaces a legacy window background with the system icon splash.
        // Draw the wallpaper as real Activity content so it is also visible on Honor/MagicOS.
        setContentView(R.layout.activity_launcher_splash);
        splashDelay = com.yuki.yukihub.util.RxMainScheduler.postDelayed(
                this::showLauncherContent, SPLASH_DELAY_MS);
    }

    private void showLauncherContent() {
        if (isFinishing() || isDestroyed()) return;

        binding = ActivityLauncherBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        viewModel = new ViewModelProvider(this).get(LauncherViewModel.class);

        renderParticles();
        requestStoragePermissionIfNeeded();
        bindActions();
        observeState();
        scheduleAutoUpdateCheck();
        openAccountLoginIfRequested(getIntent());
    }

    @Override
    protected void onDestroy() {
        if (splashDelay != null && !splashDelay.isDisposed()) splashDelay.dispose();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null) {
            renderSelectedNav(currentNavItem);
            renderParticles();
        }
        if (viewModel != null) viewModel.refreshStats();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openAccountLoginIfRequested(intent);
    }

    private void openAccountLoginIfRequested(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_OPEN_ACCOUNT_LOGIN, false) || viewModel == null) return;
        intent.removeExtra(EXTRA_OPEN_ACCOUNT_LOGIN);
        viewModel.selectNavItem(LauncherViewModel.NavItem.ACCOUNT);
    }

    private void configureEdgeToEdgeWindow() {
        boolean darkMode = isLauncherDarkMode();
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bottom_bar_color));
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!darkMode) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void scheduleAutoUpdateCheck() {
        com.yuki.yukihub.util.RxMainScheduler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            LauncherUpdateBridge.checkUpdate(this, new LauncherUpdateBridge.Callback() {
                @Override
                public void onResult(LauncherUpdateBridge.UpdateInfo info, String currentVersion, boolean hasUpdate) {
                    if (isFinishing() || isDestroyed()) return;
                    if (hasUpdate) showAutoUpdateResult(info, currentVersion);
                }

                @Override
                public void onError(String message) {
                    // 静默失败，不打扰用户
                }
            });
        }, 2000);
    }

    private void showAutoUpdateResult(LauncherUpdateBridge.UpdateInfo info, String currentVersion) {
        LauncherTheme.showUpdateResultDialog(this, info, currentVersion, true, null);
    }

    private void requestStoragePermissionIfNeeded() {
        if (getSharedPreferences(APP_PREFS, MODE_PRIVATE).getBoolean(KEY_STORAGE_PERMISSION_ASKED, false)) return;
        getSharedPreferences(APP_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_STORAGE_PERMISSION_ASKED, true).apply();

        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog dialog = new AlertDialog.Builder(this).create();
                dialog.show();
                android.view.Window window = dialog.getWindow();
                if (window == null) return;
                window.setBackgroundDrawableResource(android.R.color.transparent);

                LinearLayout root = new LinearLayout(this);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setPadding(dp(22), dp(20), dp(22), dp(16));
                root.setBackgroundResource(R.drawable.launcher_dialog_bg);

                TextView title = new TextView(this);
                title.setText("需要文件访问权限");
                title.setGravity(android.view.Gravity.CENTER);
                title.setSingleLine(true);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                title.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
                title.setTextSize(16);
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                TextView info = new TextView(this);
                info.setText("应用需要完全访问文件夹的权限来读取和管理游戏文件。请在系统页面允许\"管理所有文件\"。");
                info.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_muted_color));
                info.setTextSize(12);
                info.setLineSpacing(dp(4), 1f);
                LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                infoLp.setMargins(0, dp(13), 0, 0);
                root.addView(info, infoLp);

                TextView goBtn = new TextView(this);
                goBtn.setText("前往");
                goBtn.setGravity(android.view.Gravity.CENTER);
                goBtn.setTextSize(13);
                goBtn.setTypeface(null, android.graphics.Typeface.BOLD);
                LauncherTheme.primaryButton(goBtn);
                goBtn.setOnClickListener(v -> {
                    dialog.dismiss();
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Throwable t) {
                        try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); } catch (Throwable ignored) { }
                    }
                });
                LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
                goLp.setMargins(0, dp(9), 0, 0);
                root.addView(goBtn, goLp);

                TextView cancelBtn = new TextView(this);
                cancelBtn.setText("取消");
                cancelBtn.setGravity(android.view.Gravity.CENTER);
                cancelBtn.setTextColor(LauncherTheme.primary(this));
                cancelBtn.setTextSize(13);
                cancelBtn.setTypeface(null, android.graphics.Typeface.BOLD);
                LauncherTheme.menuItem(cancelBtn);
                cancelBtn.setOnClickListener(v -> dialog.dismiss());
                LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
                cancelLp.setMargins(0, dp(9), 0, 0);
                root.addView(cancelBtn, cancelLp);

                window.setContentView(root);
                window.setLayout(dp(288), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1001);
        }
    }

    private void bindActions() {
        binding.navHome.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            viewModel.selectNavItem(LauncherViewModel.NavItem.HOME);
        });
        binding.navSavings.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            viewModel.selectNavItem(LauncherViewModel.NavItem.LIBRARY);
        });
        binding.navCards.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            viewModel.selectNavItem(LauncherViewModel.NavItem.MANAGE);
        });
        binding.navAccount.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            viewModel.selectNavItem(LauncherViewModel.NavItem.ACCOUNT);
        });
        binding.navLaunchCenter.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            LauncherMotion.runAfterPulse(binding.navLaunchCenterCircle, this::confirmOpenPadGameModeActivity);
        });
    }

    private void observeState() {
        viewModel.getLauncherState().observe(this, state -> {
            LauncherViewModel.NavItem selectedItem = state.getSelectedItem();
            renderSelectedNav(selectedItem);
            showFragment(selectedItem);
        });
    }

    private void showFragment(LauncherViewModel.NavItem selectedItem) {
        LauncherViewModel.NavItem navItem = selectedItem == null ? LauncherViewModel.NavItem.HOME : selectedItem;
        if (currentNavItem == navItem && getSupportFragmentManager().findFragmentById(R.id.launcherFragmentContainer) != null) {
            return;
        }

        currentNavItem = navItem;
        Fragment fragment;
        if (navItem == LauncherViewModel.NavItem.HOME) {
            fragment = new LauncherHomeFragment();
        } else if (navItem == LauncherViewModel.NavItem.LIBRARY) {
            fragment = new LauncherLibraryFragment();
        } else if (navItem == LauncherViewModel.NavItem.MANAGE) {
            fragment = new LauncherManageFragment();
        } else if (navItem == LauncherViewModel.NavItem.ACCOUNT) {
            fragment = new LauncherAccountFragment();
        } else {
            fragment = LauncherPlaceholderFragment.newInstance(placeholderTitle(navItem));
        }

        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.launcher_fragment_enter,
                        R.anim.launcher_fragment_exit,
                        R.anim.launcher_fragment_enter,
                        R.anim.launcher_fragment_exit
                )
                .replace(R.id.launcherFragmentContainer, fragment, "launcher_" + navItem.name())
                .commit();
    }

    private String placeholderTitle(LauncherViewModel.NavItem navItem) {
        if (navItem == LauncherViewModel.NavItem.LIBRARY) return "游戏库";
        if (navItem == LauncherViewModel.NavItem.MANAGE) return "管理";
        if (navItem == LauncherViewModel.NavItem.ACCOUNT) return "账户占位";
        return "首页";
    }

    private void renderSelectedNav(LauncherViewModel.NavItem selectedItem) {
        LauncherViewModel.NavItem navItem = selectedItem == null ? LauncherViewModel.NavItem.HOME : selectedItem;
        applyLauncherThemeTone();
        setNavSelected(
                binding.navHome,
                binding.navHomeIcon,
                binding.navHomeLabel,
                navItem == LauncherViewModel.NavItem.HOME
        );
        setNavSelected(
                binding.navSavings,
                binding.navSavingsIcon,
                binding.navSavingsLabel,
                navItem == LauncherViewModel.NavItem.LIBRARY
        );
        setNavSelected(
                binding.navCards,
                binding.navCardsIcon,
                binding.navCardsLabel,
                navItem == LauncherViewModel.NavItem.MANAGE
        );
        setNavSelected(
                binding.navAccount,
                binding.navAccountIcon,
                binding.navAccountLabel,
                navItem == LauncherViewModel.NavItem.ACCOUNT
        );
        moveNavIndicator(navItem);
    }

    private void setNavSelected(LinearLayout container, TextView icon, TextView label, boolean selected) {
        container.setBackgroundResource(R.drawable.launcher_nav_unselected);
        int color = selected
                ? launcherPrimaryColor(this)
                : LauncherTheme.textMuted(this);
        icon.setTextColor(color);
        label.setTextColor(color);
        label.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void moveNavIndicator(LauncherViewModel.NavItem navItem) {
        if (binding == null) return;
        View target = navTarget(navItem);
        if (target == null) return;
        if (!binding.bottomNav.isLaidOut() || target.getWidth() <= 0) {
            binding.bottomNav.post(() -> moveNavIndicator(navItem));
            return;
        }

        // 指示器与 bottomNavItems 都是 bottomNav 的子 View，且默认水平 gravity 均为 start，
        // 二者 left 都等于 bottomNav 的 paddingLeft，所以只需用 target 在 bottomNavItems
        // 内部的 left 作为 translationX，避免重复叠加 paddingLeft 导致指示器整体右移。
        int left = target.getLeft();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) binding.navSelectionIndicator.getLayoutParams();
        if (params.width != target.getWidth()) {
            params.width = target.getWidth();
            binding.navSelectionIndicator.setLayoutParams(params);
        }
        binding.navSelectionIndicator.setBackgroundResource(R.drawable.launcher_nav_selected);
        if (!navIndicatorReady) {
            binding.navSelectionIndicator.setTranslationX(left);
            navIndicatorReady = true;
            return;
        }
        binding.navSelectionIndicator.animate()
                .cancel();
        binding.navSelectionIndicator.animate()
                .translationX(left)
                .setDuration(220L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withLayer()
                .start();
    }

    private View navTarget(LauncherViewModel.NavItem navItem) {
        if (navItem == LauncherViewModel.NavItem.LIBRARY) return binding.navSavings;
        if (navItem == LauncherViewModel.NavItem.MANAGE) return binding.navCards;
        if (navItem == LauncherViewModel.NavItem.ACCOUNT) return binding.navAccount;
        return binding.navHome;
    }

    private void applyLauncherThemeTone() {
        if (binding == null) return;
        binding.navLaunchCenterCircle.setBackground(LauncherTheme.circle(this));
        boolean rinneTheme = isRinneTheme(this);
        boolean anriTheme = isAnriTheme(this);
        boolean xinhaitianTheme = isXinhaitianTheme(this);
        boolean themedIcon = rinneTheme || anriTheme || xinhaitianTheme;
        binding.navLaunchCenterImage.setVisibility(themedIcon ? View.GONE : View.VISIBLE);
        binding.navLaunchCenterText.setVisibility(themedIcon ? View.VISIBLE : View.GONE);
        if (rinneTheme) {
            binding.navLaunchCenterText.setImageResource(R.drawable.launcher_theme_rinne_def);
            binding.navLaunchCenterImage.clearColorFilter();
            binding.navLaunchCenterText.setColorFilter(Color.WHITE);
        } else if (anriTheme) {
            binding.navLaunchCenterText.setImageResource(R.drawable.launcher_theme_anri_def);
            binding.navLaunchCenterImage.clearColorFilter();
            binding.navLaunchCenterText.setColorFilter(Color.WHITE);
        } else if (xinhaitianTheme) {
            binding.navLaunchCenterText.setImageResource(R.drawable.launcher_theme_xinhaitian_def);
            binding.navLaunchCenterImage.clearColorFilter();
            binding.navLaunchCenterText.setColorFilter(Color.WHITE);
        } else {
            binding.navLaunchCenterImage.setColorFilter(Color.WHITE);
        }
    }

    private void openPadGameModeActivity() {
        Intent intent = new Intent(this, PadGameModeActivity.class);
        startActivity(intent);
        LauncherMotion.applyActivityOpen(this);
    }

    private void confirmOpenPadGameModeActivity() {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(
                (int) (252 * getResources().getDisplayMetrics().density),
                WindowManager.LayoutParams.WRAP_CONTENT
        );
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_launcher_confirm, null);
        window.setContentView(dialogView);

        TextView titleView = dialogView.findViewById(R.id.dialogTitle);
        TextView messageView = dialogView.findViewById(R.id.dialogMessage);
        TextView btnCancel = dialogView.findViewById(R.id.dialogBtnCancel);
        TextView btnConfirm = dialogView.findViewById(R.id.dialogBtnConfirm);

        titleView.setText("横屏游戏模式");
        messageView.setText("要进入横屏游戏沉浸模式吗？");
        LauncherTheme.dialogButtons(btnCancel, btnConfirm);
        btnCancel.setOnClickListener(view -> dialog.dismiss());
        btnConfirm.setOnClickListener(view -> {
            dialog.dismiss();
            openPadGameModeActivity();
        });
    }

    public static void setLauncherDarkMode(android.content.Context context, boolean darkMode) {
        context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LAUNCHER_DARK_MODE, darkMode)
                .apply();
    }

    public static boolean isLauncherDarkMode(android.content.Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_LAUNCHER_DARK_MODE, false);
    }

    public static void setLauncherThemeStyle(android.content.Context context, String style) {
        String value;
        if (THEME_STYLE_RINNE.equals(style)
                || THEME_STYLE_ANRI.equals(style)
                || THEME_STYLE_XINHAITIAN.equals(style)) {
            value = style;
        } else {
            value = THEME_STYLE_DEFAULT;
        }
        context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAUNCHER_THEME_STYLE, value)
                .apply();
    }

    public static String getLauncherThemeStyle(android.content.Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .getString(KEY_LAUNCHER_THEME_STYLE, THEME_STYLE_DEFAULT);
    }

    public static boolean isRinneTheme(android.content.Context context) {
        return THEME_STYLE_RINNE.equals(getLauncherThemeStyle(context));
    }

    public static boolean isAnriTheme(android.content.Context context) {
        return THEME_STYLE_ANRI.equals(getLauncherThemeStyle(context));
    }

    public static boolean isXinhaitianTheme(android.content.Context context) {
        return THEME_STYLE_XINHAITIAN.equals(getLauncherThemeStyle(context));
    }

    public static void setLauncherParticlesEnabled(android.content.Context context, boolean enabled) {
        context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LAUNCHER_PARTICLES_ENABLED, enabled)
                .apply();
    }

    public static boolean isLauncherParticlesEnabled(android.content.Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_LAUNCHER_PARTICLES_ENABLED, true);
    }

    public static void setLauncherParticleStyle(android.content.Context context, String style) {
        String safeStyle = PARTICLE_STYLE_RAIN.equals(style)
                ? PARTICLE_STYLE_RAIN
                : PARTICLE_STYLE_STAR.equals(style) ? PARTICLE_STYLE_STAR : PARTICLE_STYLE_FLOATING;
        context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAUNCHER_PARTICLE_STYLE, safeStyle)
                .apply();
    }

    public static String getLauncherParticleStyle(android.content.Context context) {
        String style = context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .getString(KEY_LAUNCHER_PARTICLE_STYLE, PARTICLE_STYLE_FLOATING);
        if (PARTICLE_STYLE_RAIN.equals(style)) return PARTICLE_STYLE_RAIN;
        if (PARTICLE_STYLE_STAR.equals(style)) return PARTICLE_STYLE_STAR;
        return PARTICLE_STYLE_FLOATING;
    }

    private void renderParticles() {
        if (binding == null) return;
        boolean enabled = isLauncherParticlesEnabled(this);
        binding.launcherParticleView.setVisibility(enabled ? View.VISIBLE : View.GONE);
        binding.launcherParticleView.setParticleStyle(getLauncherParticleStyle(this));
        binding.launcherParticleView.setParticlesEnabled(enabled);
    }

    public static int launcherPrimaryColor(android.content.Context context) {
        if (isRinneTheme(context)) return RINNE_PRIMARY_COLOR;
        if (isAnriTheme(context)) return ANRI_PRIMARY_COLOR;
        if (isXinhaitianTheme(context)) return XINHAITIAN_PRIMARY_COLOR;
        return ContextCompat.getColor(wrapLauncherUiMode(context), R.color.launcher_primary_color);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private boolean isLauncherDarkMode() {
        return isLauncherDarkMode(this);
    }

    public static void applySavedToneMode(AppCompatActivity activity) {
        if (activity == null) return;
        activity.getDelegate().setLocalNightMode(isLauncherDarkMode(activity)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
    }

    public static android.content.Context wrapLauncherUiMode(android.content.Context base) {
        if (base == null) return null;
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        int targetNightMode = isLauncherDarkMode(base)
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | targetNightMode;
        return base.createConfigurationContext(configuration);
    }

    private void applySavedToneMode() {
        applySavedToneMode(this);
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(wrapLauncherUiMode(newBase));
    }
}
