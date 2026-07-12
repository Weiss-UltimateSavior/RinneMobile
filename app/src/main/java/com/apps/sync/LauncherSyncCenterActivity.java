package com.apps.sync;

import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherSyncCenterBinding;
import com.yuki.yukihub.launcherbridge.LauncherSyncBridge;
import com.yuki.yukihub.util.AppExecutors;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherSyncCenterActivity extends AppCompatActivity {
    private ActivityResultLauncher<String> backupCreateLauncher;
    private ActivityResultLauncher<String[]> backupOpenLauncher;
    private ActivityLauncherSyncCenterBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        registerBackupLaunchers();

        binding = ActivityLauncherSyncCenterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        applySystemBarInsets();
        bindActions();
        applyThemeTone();
        loadConfig();
    }

    private void registerBackupLaunchers() {
        backupCreateLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
            if (uri != null) exportLocalBackup(uri);
        });
        backupOpenLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) importLocalBackup(uri);
        });
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
        binding.btnExport.setOnClickListener(v -> backupCreateLauncher.launch("yukihub_backup_" + System.currentTimeMillis() + ".json"));
        binding.btnImport.setOnClickListener(v -> showImportConfirmDialog());
    }

    private void applyThemeTone() {
        LauncherTheme.styleSwitch(binding.syncAutoSwitch);
        LauncherTheme.applyPrimaryTone(binding.getRoot());
    }

    private void loadConfig() {
        LauncherSyncBridge.SyncConfigSnapshot config = LauncherSyncBridge.getConfig(this);
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
        // 先临时保存再测试，避免 Bridge 内部使用旧配置
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

    private void showImportConfirmDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(280), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);

        TextView title = new TextView(this);
        title.setText("本地导入");
        title.setGravity(Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(this);
        message.setText("将从备份 JSON 导入个人资料、游戏库、游玩记录和元数据。\n\n导入策略：\n- 游戏按 rootUri 去重合并\n- 游玩记录按 session_uuid 去重\n- 图片只恢复 URI/URL，不复制图片文件\n\n是否继续？");
        message.setGravity(Gravity.CENTER);
        message.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_muted_color));
        message.setTextSize(12);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dp(13), 0, 0);
        root.addView(message, msgLp);

        TextView confirmBtn = new TextView(this);
        confirmBtn.setText("选择文件");
        confirmBtn.setGravity(Gravity.CENTER);
        LauncherTheme.primaryButton(confirmBtn);
        confirmBtn.setOnClickListener(v -> {
            dialog.dismiss();
            backupOpenLauncher.launch(new String[]{"application/json", "text/*", "*/*"});
        });
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        confirmLp.setMargins(0, dp(11), 0, 0);
        root.addView(confirmBtn, confirmLp);

        TextView cancelBtn = new TextView(this);
        cancelBtn.setText("取消");
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setTextColor(LauncherTheme.primary(this));
        cancelBtn.setTextSize(13);
        cancelBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        cancelBtn.setBackground(LauncherTheme.cancelChip(this));
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        cancelLp.setMargins(0, dp(9), 0, 0);
        root.addView(cancelBtn, cancelLp);

        window.setContentView(root);
    }

    private void exportLocalBackup(Uri uri) {
        Toast.makeText(this, "正在导出备份...", Toast.LENGTH_SHORT).show();
        AppExecutors.runOnSingle(() -> {
            try {
                JSONObject root = LauncherSyncBridge.exportLocalBackup(this);
                byte[] bytes = root.toString(2).getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new Exception("openOutputStream failed");
                    out.write(bytes);
                    out.flush();
                }
                runOnUiThread(() -> Toast.makeText(this, "备份完成：" + (bytes.length / 1024) + "KB", Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                Log.e("YukiHub", "export backup failed", t);
                runOnUiThread(() -> Toast.makeText(this, "备份失败：" + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void importLocalBackup(Uri uri) {
        Toast.makeText(this, "正在导入备份...", Toast.LENGTH_SHORT).show();
        AppExecutors.runOnSingle(() -> {
            try {
                LauncherSyncBridge.importLocalBackupFromUri(this, uri);
                runOnUiThread(() -> Toast.makeText(this, "导入完成", Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                Log.e("YukiHub", "import backup failed", t);
                runOnUiThread(() -> Toast.makeText(this, "导入失败：" + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
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
