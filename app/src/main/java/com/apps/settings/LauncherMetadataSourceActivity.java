package com.apps.settings;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.core.R;
import com.core.databinding.ActivityLauncherMetadataSourceBinding;
import com.core.launcherbridge.LauncherMetadataBridge;
import com.core.metadata.MetadataController;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherTheme;
import com.apps.util.LauncherUrlOpener;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherMetadataSourceActivity extends AppCompatActivity {
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
            Toast.makeText(this, R.string.settings_bangumi_token_required, Toast.LENGTH_SHORT).show();
            return;
        }
        LauncherMetadataBridge.setMetadataSource(this, source);
        LauncherMetadataBridge.setBangumiToken(this, token);
        Toast.makeText(this, getString(R.string.settings_metadata_source_saved,
                metadataSourceLabels()[selectedMetadataSourceIndex]), Toast.LENGTH_SHORT).show();
        finish();
    }

    private void showMetadataSourcePicker() {
        com.apps.theme.LauncherDialogFactory.showSingleChoice(this,
                getString(R.string.settings_choose_metadata_source),
                metadataSourceLabels(), selectedMetadataSourceIndex, this::setMetadataSourceSelection);
    }

    private void setMetadataSourceSelection(int index) {
        String[] labels = metadataSourceLabels();
        selectedMetadataSourceIndex = index >= 0 && index < labels.length ? index : 0;
        binding.sourceText.setText(labels[selectedMetadataSourceIndex]);
    }

    private void openTokenUrl() {
        if (!LauncherUrlOpener.open(this, "https://next.bgm.tv/demo/access-token/create")) {
            Toast.makeText(this, R.string.settings_cannot_open_link, Toast.LENGTH_SHORT).show();
        }
    }

    private String[] metadataSourceLabels() {
        return new String[]{
                getString(R.string.settings_metadata_vndb_default),
                getString(R.string.settings_metadata_bangumi_token),
                getString(R.string.settings_metadata_bangumi_mirror_token),
                getString(R.string.settings_metadata_ymgal_public)
        };
    }

    private void configureEdgeToEdgeWindow() {
        com.apps.LauncherEdgeToEdgeHelper.apply(this);
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
