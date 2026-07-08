package com.apps;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherToolboxBinding;

public class LauncherToolboxActivity extends AppCompatActivity {
    private static final String USEFULUNPACK_URL = "https://github.com/znso4pa/usefulunpack/releases";
    private static final String TERMUX_URL = "https://github.com/termux/termux-app/releases";
    private static final String SHIZUKU_URL = "https://github.com/RikkaApps/Shizuku/releases";
    private static final String WINLATOR_URL = "https://github.com/brunodev85/winlator/releases";
    private static final String GAISHI_URL = "https://hub.xiaoji.com/zh-cn";
    private static final String PPSSPP_URL = "https://www.ppsspp.org/";

    private ActivityLauncherToolboxBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherToolboxBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarInsets();
        applyThemeTone();

        binding.toolUsefulUnpack.setOnClickListener(view -> confirmOpenExternalTool("usefulunpack", USEFULUNPACK_URL));
        binding.toolTermux.setOnClickListener(view -> confirmOpenExternalTool("termux", TERMUX_URL));
        binding.toolShizuku.setOnClickListener(view -> confirmOpenExternalTool("shizuku", SHIZUKU_URL));
        binding.toolWinlator.setOnClickListener(view -> confirmOpenExternalTool("winlator", WINLATOR_URL));
        binding.toolGaishi.setOnClickListener(view -> confirmOpenExternalTool("盖世模拟器", GAISHI_URL));
        binding.toolPpsspp.setOnClickListener(view -> confirmOpenExternalTool("PPSSPP", PPSSPP_URL));
    }

    private void confirmOpenExternalTool(String name, String url) {
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
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_launcher_confirm, null);
        window.setContentView(dialogView);

        TextView titleView = dialogView.findViewById(R.id.dialogTitle);
        TextView messageView = dialogView.findViewById(R.id.dialogMessage);
        TextView btnCancel = dialogView.findViewById(R.id.dialogBtnCancel);
        TextView btnConfirm = dialogView.findViewById(R.id.dialogBtnConfirm);

        titleView.setText("跳转下载");
        messageView.setText("即将跳转到浏览器下载 " + name + "，是否继续？");
        LauncherTheme.dialogButtons(btnCancel, btnConfirm);
        btnCancel.setOnClickListener(view -> dialog.dismiss());
        btnConfirm.setOnClickListener(view -> {
            dialog.dismiss();
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        });
    }

    private void applySystemBarInsets() {
        int originalLeft = binding.toolboxScroll.getPaddingLeft();
        int originalTop = binding.toolboxScroll.getPaddingTop();
        int originalRight = binding.toolboxScroll.getPaddingRight();
        int originalBottom = binding.toolboxScroll.getPaddingBottom();

        binding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            binding.toolboxScroll.setPadding(
                    originalLeft,
                    originalTop + insets.getSystemWindowInsetTop(),
                    originalRight,
                    originalBottom
            );
            return insets;
        });
        binding.getRoot().requestApplyInsets();
    }

    private void applyThemeTone() {
        LauncherTheme.applyPrimaryTone(binding.getRoot());
    }

    private void configureEdgeToEdgeWindow() {
        boolean darkMode = LauncherActivity.isLauncherDarkMode(this);
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bg_color));
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!darkMode) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void applySavedToneMode() {
        LauncherActivity.applySavedToneMode(this);
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
