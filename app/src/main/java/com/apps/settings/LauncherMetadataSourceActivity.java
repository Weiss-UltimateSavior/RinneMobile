package com.apps.settings;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherMetadataSourceBinding;
import com.yuki.yukihub.launcherbridge.LauncherMetadataBridge;
import com.yuki.yukihub.metadata.MetadataController;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherMetadataSourceActivity extends AppCompatActivity {
    private static final String[] METADATA_SOURCE_LABELS = {
            "VNDB（默认）", "Bangumi（需要 Token）", "Bangumi 镜像（需要 Token）", "月幕 Gal（公开 API）"
    };
    private static final String STATE_METADATA_SOURCE_INDEX = "metadata_source_index";
    private ActivityLauncherMetadataSourceBinding binding;
    private int selectedMetadataSourceIndex;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherMetadataSourceBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        applySystemBarInsets();
        bindActions();
        applyThemeTone();
        loadConfig(savedInstanceState);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_METADATA_SOURCE_INDEX, selectedMetadataSourceIndex);
    }

    private void applySystemBarInsets() {
        int left = binding.sourceScroll.getPaddingLeft();
        int top = binding.sourceScroll.getPaddingTop();
        int right = binding.sourceScroll.getPaddingRight();
        int bottom = binding.sourceScroll.getPaddingBottom();
        binding.sourceScroll.setOnApplyWindowInsetsListener((view, insets) -> {
            binding.sourceScroll.setPadding(left, top + insets.getSystemWindowInsetTop(), right, bottom);
            return insets;
        });
        binding.sourceScroll.requestApplyInsets();
    }

    private void bindActions() {
        binding.btnSave.setOnClickListener(v -> save());
        binding.btnCancel.setOnClickListener(v -> finish());
        binding.tokenLink.setOnClickListener(v -> openTokenUrl());
        binding.sourceText.setOnClickListener(v -> showMetadataSourcePicker());
    }

    private void applyThemeTone() {
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        LauncherTheme.styleTextInput(binding.tokenInput);
        LauncherTheme.longActionButton(binding.btnSave);
        LauncherTheme.longActionButton(binding.btnCancel);
    }

    private void loadConfig(@Nullable Bundle savedInstanceState) {
        String current = LauncherMetadataBridge.getMetadataSource(this);
        int selection = 0;
        if (MetadataController.SOURCE_BANGUMI.equals(current)) selection = 1;
        else if (MetadataController.SOURCE_BANGUMI_MIRROR.equals(current)) selection = 2;
        else if (MetadataController.SOURCE_YMGAL.equals(current)) selection = 3;
        setMetadataSourceSelection(savedInstanceState != null
                && savedInstanceState.containsKey(STATE_METADATA_SOURCE_INDEX)
                ? savedInstanceState.getInt(STATE_METADATA_SOURCE_INDEX, 0) : selection);
        binding.tokenInput.setText(LauncherMetadataBridge.getBangumiToken(this));
    }

    private void save() {
        int pos = selectedMetadataSourceIndex;
        String source = MetadataController.SOURCE_VNDB;
        if (pos == 1) source = MetadataController.SOURCE_BANGUMI;
        else if (pos == 2) source = MetadataController.SOURCE_BANGUMI_MIRROR;
        else if (pos == 3) source = MetadataController.SOURCE_YMGAL;

        String token = binding.tokenInput.getText().toString().trim();
        if ((pos == 1 || pos == 2) && token.isEmpty()) {
            Toast.makeText(this, "选择 Bangumi 时需要填写 Token", Toast.LENGTH_SHORT).show();
            return;
        }
        LauncherMetadataBridge.setMetadataSource(this, source);
        LauncherMetadataBridge.setBangumiToken(this, token);
        Toast.makeText(this, "已保存资料源：" + LauncherMetadataBridge.sourceLabel(source), Toast.LENGTH_SHORT).show();
        finish();
    }

    private void showMetadataSourcePicker() {
        com.apps.theme.LauncherDialogFactory.showSingleChoice(this, "选择资料源",
                METADATA_SOURCE_LABELS, selectedMetadataSourceIndex, this::setMetadataSourceSelection);
    }

    private void setMetadataSourceSelection(int index) {
        selectedMetadataSourceIndex = index >= 0 && index < METADATA_SOURCE_LABELS.length ? index : 0;
        binding.sourceText.setText(METADATA_SOURCE_LABELS[selectedMetadataSourceIndex]);
    }

    private void openTokenUrl() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://next.bgm.tv/demo/access-token/create")));
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    private void configureEdgeToEdgeWindow() {
        boolean darkMode = LauncherActivity.isLauncherDarkMode(this);
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bg_color));
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!darkMode) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
