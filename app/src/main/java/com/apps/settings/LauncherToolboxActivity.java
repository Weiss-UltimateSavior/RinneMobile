package com.apps.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.core.R;
import com.core.databinding.ActivityLauncherToolboxBinding;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.util.LauncherUrlOpener;
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

    private ActivityLauncherToolboxBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        com.apps.LauncherEdgeToEdgeHelper.apply(this);

        binding = ActivityLauncherToolboxBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        applySystemBarInsets();
        applyThemeTone();

        binding.toolUsefulUnpack.setOnClickListener(view -> confirmOpenExternalTool(ToolboxTool.USEFULUNPACK, USEFULUNPACK_URL));
        binding.toolTermux.setOnClickListener(view -> confirmOpenExternalTool(ToolboxTool.TERMUX, TERMUX_URL));
        binding.toolShizuku.setOnClickListener(view -> confirmOpenExternalTool(ToolboxTool.SHIZUKU, SHIZUKU_URL));
        binding.toolWinlator.setOnClickListener(view -> confirmOpenExternalTool(ToolboxTool.WINLATOR, WINLATOR_URL));
        binding.toolGaishi.setOnClickListener(view ->
                confirmOpenExternalTool(getString(R.string.settings_tool_gaishi), GAISHI_URL));
        binding.toolPpsspp.setOnClickListener(view -> confirmOpenExternalTool(ToolboxTool.PPSSPP, PPSSPP_URL));
        binding.toolLunabox.setOnClickListener(view -> confirmOpenExternalTool(ToolboxTool.LUNABOX, LUNABOX_URL));
        binding.toolAzahar.setOnClickListener(view -> confirmOpenExternalTool(ToolboxTool.AZAHARPLUS, AZAHARPLUS_URL));
        binding.toolboxBack.setOnClickListener(view -> LauncherMotion.finish(this));
    }

    private void confirmOpenExternalTool(String name, String url) {
        LauncherDialogFactory.showConfirm(
                this,
                getString(R.string.settings_open_download_title),
                getString(R.string.settings_open_download_message, name),
                getString(R.string.settings_confirm),
                () -> {
                    // 打开失败时提示用户，避免静默无响应
                    if (!LauncherUrlOpener.open(this, url)) {
                        Toast.makeText(this, R.string.home_cannot_open_link, Toast.LENGTH_SHORT).show();
                    }
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
        LauncherTheme.longActionButton(binding.toolboxBack);
    }

    private void applySavedToneMode() {
        LauncherActivity.applySavedToneMode(this);
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
