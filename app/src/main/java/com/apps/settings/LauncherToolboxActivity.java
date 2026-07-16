package com.apps.settings;

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
import com.apps.LauncherActivity;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherToolboxActivity extends AppCompatActivity {
    private static final String USEFULUNPACK_URL = "https://github.com/znso4pa/usefulunpack/releases";
    private static final String TERMUX_URL = "https://github.com/termux/termux-app/releases";
    private static final String SHIZUKU_URL = "https://github.com/RikkaApps/Shizuku/releases";
    private static final String WINLATOR_URL = "https://github.com/brunodev85/winlator/releases";
    private static final String GAISHI_URL = "https://hub.xiaoji.com/zh-cn";
    private static final String PPSSPP_URL = "https://www.ppsspp.org/";
    private static final String LUNABOX_URL = "https://github.com/Saramanda9988/LunaBox/releases";
    private static final String AZAHARPLUS_URL = "https://github.com/AzaharPlus/AzaharPlus/releases";
    private static final String RPGMPLUGIN_URL = "https://joiplay.net/";

    private ActivityLauncherToolboxBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherToolboxBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        applySystemBarInsets();
        applyThemeTone();

        binding.toolUsefulUnpack.setOnClickListener(view -> confirmOpenExternalTool("usefulunpack", USEFULUNPACK_URL));
        binding.toolTermux.setOnClickListener(view -> confirmOpenExternalTool("termux", TERMUX_URL));
        binding.toolShizuku.setOnClickListener(view -> confirmOpenExternalTool("shizuku", SHIZUKU_URL));
        binding.toolWinlator.setOnClickListener(view -> confirmOpenExternalTool("winlator", WINLATOR_URL));
        binding.toolGaishi.setOnClickListener(view -> confirmOpenExternalTool("盖世模拟器", GAISHI_URL));
        binding.toolPpsspp.setOnClickListener(view -> confirmOpenExternalTool("PPSSPP", PPSSPP_URL));
        binding.toolLunabox.setOnClickListener(view -> confirmOpenExternalTool("LunaBox", LUNABOX_URL));
        binding.toolAzahar.setOnClickListener(view -> confirmOpenExternalTool("AzaharPlus", AZAHARPLUS_URL));
        binding.toolRpgmPlugin.setOnClickListener(view -> confirmOpenExternalTool("RPGMPlugin", RPGMPLUGIN_URL));
        binding.toolboxBack.setOnClickListener(view -> LauncherMotion.finish(this));
    }

    private void confirmOpenExternalTool(String name, String url) {
        LauncherDialogFactory.showConfirm(
                this,
                "跳转下载",
                "即将跳转到浏览器下载 " + name + "，是否继续？",
                "确定",
                () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))));
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
        LauncherTheme.longActionButton(binding.toolboxBack);
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
