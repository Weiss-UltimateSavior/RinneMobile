package com.apps;

import android.graphics.Color;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.MainActivity;
import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherSyncCenterBinding;
import com.yuki.yukihub.launcherbridge.LauncherSyncBridge;
import com.yuki.yukihub.launcherbridge.YukiHubBridge;
import com.yuki.yukihub.sync.SyncManager;
import com.yuki.yukihub.util.AppExecutors;

public class LauncherSyncCenterActivity extends AppCompatActivity {
    private ActivityLauncherSyncCenterBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherSyncCenterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarInsets();
        bindActions();
        applyThemeTone();
        loadConfig();
    }

    private void applySystemBarInsets() {
        int left = binding.syncScroll.getPaddingLeft();
        int top = binding.syncScroll.getPaddingTop();
        int right = binding.syncScroll.getPaddingRight();
        int bottom = binding.syncScroll.getPaddingBottom();
        binding.syncScroll.setOnApplyWindowInsetsListener((view, insets) -> {
            binding.syncScroll.setPadding(left, top + insets.getSystemWindowInsetTop(), right, bottom);
            return insets;
        });
        binding.syncScroll.requestApplyInsets();
    }

    private void bindActions() {
        binding.btnSave.setOnClickListener(v -> saveConfig());
        binding.btnTest.setOnClickListener(v -> testConnection());
        binding.btnSyncNow.setOnClickListener(v -> syncNow());
        binding.btnExport.setOnClickListener(v -> YukiHubBridge.openAction(this, MainActivity.ACTION_LOCAL_BACKUP_EXPORT));
        binding.btnImport.setOnClickListener(v -> YukiHubBridge.openAction(this, MainActivity.ACTION_LOCAL_BACKUP_IMPORT));
    }

    private void applyThemeTone() {
        LauncherTheme.applyPrimaryTone(binding.getRoot());
    }

    private void loadConfig() {
        SyncManager.SyncConfig config = LauncherSyncBridge.getConfig(this);
        binding.syncServerInput.setText(config.serverUrl);
        binding.syncUserInput.setText(config.username);
        binding.syncPasswordInput.setText(config.password);
        binding.syncAutoSwitch.setChecked(config.autoSync);
        renderStatus();
    }

    private void renderStatus() {
        boolean configured = LauncherSyncBridge.isConfigured(this);
        long last = LauncherSyncBridge.lastSyncTime(this);
        StringBuilder sb = new StringBuilder();
        sb.append("状态：").append(configured ? "已配置" : "未配置");
        if (configured) {
            sb.append("\n上次同步：");
            sb.append(last > 0 ? DateFormat.format("yyyy-MM-dd HH:mm", last) : "尚未同步");
            if (LauncherSyncBridge.isAutoSyncEnabled(this)) sb.append("（自动同步已开启）");
        }
        binding.syncStatusText.setText(sb.toString());
    }

    private void saveConfig() {
        String url = binding.syncServerInput.getText().toString().trim();
        String user = binding.syncUserInput.getText().toString().trim();
        String pass = binding.syncPasswordInput.getText().toString();
        boolean auto = binding.syncAutoSwitch.isChecked();
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "请填写完整配置", Toast.LENGTH_SHORT).show();
            return;
        }
        LauncherSyncBridge.saveConfig(this, url, user, pass, auto);
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        renderStatus();
    }

    private void testConnection() {
        String url = binding.syncServerInput.getText().toString().trim();
        String user = binding.syncUserInput.getText().toString().trim();
        String pass = binding.syncPasswordInput.getText().toString();
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "请填写完整配置", Toast.LENGTH_SHORT).show();
            return;
        }
        // 先临时保存再测试，避免 SyncManager 内部使用旧配置
        LauncherSyncBridge.saveConfig(this, url, user, pass, binding.syncAutoSwitch.isChecked());
        Toast.makeText(this, "正在测试连接...", Toast.LENGTH_SHORT).show();
        AppExecutors.runOnSingle(() -> {
            boolean ok = LauncherSyncBridge.testConnection(this);
            runOnUiThread(() -> {
                Toast.makeText(this, ok ? "连接成功" : "连接失败，请检查配置", Toast.LENGTH_SHORT).show();
                renderStatus();
            });
        });
    }

    private void syncNow() {
        if (!LauncherSyncBridge.isConfigured(this)) {
            Toast.makeText(this, "请先填写并保存 WebDAV 配置", Toast.LENGTH_SHORT).show();
            return;
        }
        // 保存当前输入的配置再同步
        String url = binding.syncServerInput.getText().toString().trim();
        String user = binding.syncUserInput.getText().toString().trim();
        String pass = binding.syncPasswordInput.getText().toString();
        LauncherSyncBridge.saveConfig(this, url, user, pass, binding.syncAutoSwitch.isChecked());

        Toast.makeText(this, "正在同步...", Toast.LENGTH_SHORT).show();
        LauncherSyncBridge.syncNow(this, new LauncherSyncBridge.Callback() {
            @Override public void onStart() {}
            @Override public void onProgress(String item, boolean changed) {}
            @Override
            public void onComplete(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(LauncherSyncCenterActivity.this, message, Toast.LENGTH_SHORT).show();
                    renderStatus();
                });
            }
            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(LauncherSyncCenterActivity.this, error, Toast.LENGTH_LONG).show());
            }
        });
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
