package com.apps.PadUi;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.apps.LauncherActivity;
import com.apps.LauncherMotion;
import com.apps.LauncherTheme;
import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityPadGameModeBinding;

/** 横屏游戏模式外壳；具体的游戏和管理内容后续由两个占位 Fragment 承载。 */
public class PadGameModeActivity extends AppCompatActivity {
    private enum Page { GAME, MANAGE }

    private ActivityPadGameModeBinding binding;
    private Page currentPage;
    private boolean navIndicatorReady;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureLandscapeWindow();

        binding = ActivityPadGameModeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        renderParticles();
        bindActions();

        Page initialPage = savedInstanceState == null
                ? Page.GAME
                : Page.valueOf(savedInstanceState.getString("pad_page", Page.GAME.name()));
        selectPage(initialPage);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderParticles();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("pad_page", (currentPage == null ? Page.GAME : currentPage).name());
    }

    private void configureLandscapeWindow() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        int backgroundColor = ContextCompat.getColor(this, R.color.launcher_bg_color);
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

        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!LauncherActivity.isLauncherDarkMode(this)) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void bindActions() {
        binding.navGame.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            selectPage(Page.GAME);
        });
        binding.navManage.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            selectPage(Page.MANAGE);
        });
        binding.navLaunchCenter.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            LauncherMotion.runAfterPulse(binding.navLaunchCenterCircle, this::confirmReturnToPortrait);
        });
    }

    private void renderParticles() {
        if (binding == null) return;
        boolean enabled = LauncherActivity.isLauncherParticlesEnabled(this);
        binding.padLauncherParticleView.setVisibility(enabled ? View.VISIBLE : View.GONE);
        binding.padLauncherParticleView.setParticlesEnabled(enabled);
    }

    private void confirmReturnToPortrait() {
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

        titleView.setText("竖屏管理模式");
        messageView.setText("要返回竖屏管理模式吗？");
        LauncherTheme.dialogButtons(btnCancel, btnConfirm);
        btnCancel.setOnClickListener(view -> dialog.dismiss());
        btnConfirm.setOnClickListener(view -> {
            dialog.dismiss();
            finish();
        });
    }

    private void selectPage(Page page) {
        Page targetPage = page == null ? Page.GAME : page;
        renderSelectedNav(targetPage);
        if (currentPage == targetPage
                && getSupportFragmentManager().findFragmentById(R.id.padFragmentContainer) != null) {
            return;
        }
        currentPage = targetPage;
        Fragment fragment = targetPage == Page.GAME
                ? new PadGameFragment()
                : new PadManageFragment();
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.launcher_fragment_enter,
                        R.anim.launcher_fragment_exit,
                        R.anim.launcher_fragment_enter,
                        R.anim.launcher_fragment_exit)
                .replace(R.id.padFragmentContainer, fragment, "pad_" + targetPage.name())
                .commit();
    }

    private void renderSelectedNav(Page page) {
        int primary = LauncherActivity.launcherPrimaryColor(this);
        int muted = ContextCompat.getColor(this, R.color.launcher_nav_muted_color);
        setNavSelected(binding.navGame, binding.navGameIcon, binding.navGameLabel,
                page == Page.GAME, primary, muted);
        setNavSelected(binding.navManage, binding.navManageIcon, binding.navManageLabel,
                page == Page.MANAGE, primary, muted);

        GradientDrawable centerBackground = new GradientDrawable();
        centerBackground.setShape(GradientDrawable.OVAL);
        centerBackground.setColor(primary);
        binding.navLaunchCenterCircle.setBackground(centerBackground);
        moveNavIndicator(page == Page.GAME ? binding.navGame : binding.navManage);
    }

    private void setNavSelected(LinearLayout container, TextView icon, TextView label,
                                boolean selected, int primary, int muted) {
        container.setBackgroundResource(R.drawable.launcher_nav_unselected);
        int color = selected ? primary : muted;
        icon.setTextColor(color);
        label.setTextColor(color);
        label.setTypeface(null, selected
                ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
    }

    private void moveNavIndicator(View target) {
        binding.bottomNav.post(() -> {
            if (binding == null || target.getWidth() <= 0) return;
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) binding.navSelectionIndicator.getLayoutParams();
            if (params.width != target.getWidth()) {
                params.width = target.getWidth();
                binding.navSelectionIndicator.setLayoutParams(params);
            }
            // 指示器和 bottomNavItems 都以 bottomNav 的 paddingStart 为起点。
            float targetX = target.getLeft();
            if (!navIndicatorReady) {
                binding.navSelectionIndicator.setTranslationX(targetX);
                navIndicatorReady = true;
            } else {
                binding.navSelectionIndicator.animate()
                        .translationX(targetX)
                        .setDuration(220L)
                        .start();
            }
        });
    }

}
