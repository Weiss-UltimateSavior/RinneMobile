package com.apps;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.yuki.yukihub.MainActivity;
import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherBinding;

public class LauncherActivity extends AppCompatActivity {
    static final String APP_PREFS = "yukihub_prefs";
    static final String KEY_LAUNCHER_DARK_MODE = "launcher_dark_mode";
    static final String KEY_LAUNCHER_THEME_STYLE = "launcher_theme_style";
    static final String KEY_LAUNCHER_PARTICLES_ENABLED = "launcher_particles_enabled";
    static final String THEME_STYLE_DEFAULT = "default";
    static final String THEME_STYLE_RINNE = "rinne";
    static final int RINNE_PRIMARY_COLOR = Color.rgb(216, 169, 201);

    private ActivityLauncherBinding binding;
    private LauncherViewModel viewModel;
    private LauncherViewModel.NavItem currentNavItem;
    private boolean navIndicatorReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        viewModel = new ViewModelProvider(this).get(LauncherViewModel.class);

        renderParticles();
        bindActions();
        observeState();
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

    private void bindActions() {
        binding.navHome.setOnClickListener(view -> viewModel.selectNavItem(LauncherViewModel.NavItem.HOME));
        binding.navSavings.setOnClickListener(view -> viewModel.selectNavItem(LauncherViewModel.NavItem.LIBRARY));
        binding.navCards.setOnClickListener(view -> viewModel.selectNavItem(LauncherViewModel.NavItem.MANAGE));
        binding.navAccount.setOnClickListener(view -> viewModel.selectNavItem(LauncherViewModel.NavItem.ACCOUNT));
        binding.navLaunchCenter.setOnClickListener(view ->
                LauncherMotion.runAfterPulse(binding.navLaunchCenterCircle, this::confirmOpenMainActivity));
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
        binding.bottomNav.post(() -> {
            if (binding == null || target.getWidth() <= 0) return;
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
                    .translationX(left)
                    .setDuration(220L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        });
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
        binding.navLaunchCenterImage.setVisibility(rinneTheme ? View.GONE : View.VISIBLE);
        binding.navLaunchCenterText.setVisibility(rinneTheme ? View.VISIBLE : View.GONE);
        if (rinneTheme) {
            binding.navLaunchCenterImage.clearColorFilter();
        } else {
            binding.navLaunchCenterImage.setColorFilter(Color.WHITE);
        }
        binding.navLaunchCenterText.setText("凛");
    }

    private void openMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        LauncherMotion.applyActivityOpen(this);
    }

    private void confirmOpenMainActivity() {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(
                (int) (280 * getResources().getDisplayMetrics().density),
                WindowManager.LayoutParams.WRAP_CONTENT
        );
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_launcher_confirm, null);
        window.setContentView(dialogView);

        TextView titleView = dialogView.findViewById(R.id.dialogTitle);
        TextView messageView = dialogView.findViewById(R.id.dialogMessage);
        TextView btnCancel = dialogView.findViewById(R.id.dialogBtnCancel);
        TextView btnConfirm = dialogView.findViewById(R.id.dialogBtnConfirm);

        titleView.setText("进入游戏中心");
        messageView.setText("确定打开主项目游戏中心吗？");
        LauncherTheme.dialogButtons(btnCancel, btnConfirm);
        btnCancel.setOnClickListener(view -> dialog.dismiss());
        btnConfirm.setOnClickListener(view -> {
            dialog.dismiss();
            openMainActivity();
        });
    }

    static void setLauncherDarkMode(android.content.Context context, boolean darkMode) {
        context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LAUNCHER_DARK_MODE, darkMode)
                .apply();
    }

    static boolean isLauncherDarkMode(android.content.Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_LAUNCHER_DARK_MODE, false);
    }

    static void setLauncherThemeStyle(android.content.Context context, String style) {
        String value = THEME_STYLE_RINNE.equals(style) ? THEME_STYLE_RINNE : THEME_STYLE_DEFAULT;
        context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAUNCHER_THEME_STYLE, value)
                .apply();
    }

    static String getLauncherThemeStyle(android.content.Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .getString(KEY_LAUNCHER_THEME_STYLE, THEME_STYLE_DEFAULT);
    }

    static boolean isRinneTheme(android.content.Context context) {
        return THEME_STYLE_RINNE.equals(getLauncherThemeStyle(context));
    }

    static void setLauncherParticlesEnabled(android.content.Context context, boolean enabled) {
        context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LAUNCHER_PARTICLES_ENABLED, enabled)
                .apply();
    }

    static boolean isLauncherParticlesEnabled(android.content.Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_LAUNCHER_PARTICLES_ENABLED, true);
    }

    private void renderParticles() {
        if (binding == null) return;
        boolean enabled = isLauncherParticlesEnabled(this);
        binding.launcherParticleView.setVisibility(enabled ? View.VISIBLE : View.GONE);
        binding.launcherParticleView.setParticlesEnabled(enabled);
    }

    static int launcherPrimaryColor(android.content.Context context) {
        return isRinneTheme(context)
                ? RINNE_PRIMARY_COLOR
                : ContextCompat.getColor(wrapLauncherUiMode(context), R.color.launcher_primary_color);
    }

    private boolean isLauncherDarkMode() {
        return isLauncherDarkMode(this);
    }

    static void applySavedToneMode(AppCompatActivity activity) {
        if (activity == null) return;
        activity.getDelegate().setLocalNightMode(isLauncherDarkMode(activity)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
    }

    static android.content.Context wrapLauncherUiMode(android.content.Context base) {
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
