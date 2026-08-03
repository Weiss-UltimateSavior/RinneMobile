package com.apps.sync;

import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.core.CoreBackup;
import com.core.R;
import com.core.databinding.ActivityLauncherSyncCenterBinding;
import com.core.launcherbridge.LauncherSyncBridge;
import com.core.util.AppExecutors;

import java.io.IOException;
import java.io.OutputStream;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherSyncCenterActivity extends AppCompatActivity {
    /**
     * 本地备份文件名前缀：机器可读值，单源在 com.core.CoreBackup.FILE_PREFIX，
     * 不放入可翻译 string 资源以免被翻译污染文件名（yukihub_backup_ + 时间戳 + .ykbak）
     */
    private static final String BACKUP_FILE_PREFIX = CoreBackup.FILE_PREFIX;

    private ActivityResultLauncher<String> backupCreateLauncher;
    private ActivityResultLauncher<String[]> backupOpenLauncher;
    private ActivityLauncherSyncCenterBinding binding;

    /**
     * 导入防重复触发标志：与 LauncherManageFragment 保持一致，
     * 避免极端时序下用户连续点击触发并发导入。
     */
    private boolean importInProgress;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        com.apps.LauncherEdgeToEdgeHelper.apply(this);
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
        backupCreateLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
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
        binding.btnExport.setOnClickListener(v -> backupCreateLauncher.launch(BACKUP_FILE_PREFIX + System.currentTimeMillis() + ".ykbak"));
        binding.btnImport.setOnClickListener(v -> showImportConfirmDialog());
    }

    private void applyThemeTone() {
        LauncherTheme.styleMaterialSwitch(binding.syncAutoSwitch);
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        LauncherTheme.formInputs(binding.syncServerInput, binding.syncUserInput, binding.syncPasswordInput);
        LauncherTheme.shortActionButton(binding.btnTest);
        LauncherTheme.shortActionButton(binding.btnSyncNow);
        LauncherTheme.longActionButton(binding.btnSave);
        LauncherTheme.shortActionButton(binding.btnExport);
        LauncherTheme.shortActionButton(binding.btnImport);
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
        sb.append(getString(R.string.sync_game_status,
                getString(configured ? R.string.sync_configured : R.string.sync_not_configured)));
        if (configured) {
            sb.append("\n").append(getString(R.string.sync_last_sync,
                    last > 0 ? DateFormat.format("yyyy-MM-dd HH:mm", last)
                            : getString(R.string.sync_never_synced)));
            if (LauncherSyncBridge.isAutoSyncEnabled(this)) {
                sb.append(getString(R.string.sync_auto_enabled_suffix));
            }
        }
        binding.syncStatusText.setText(sb.toString());
    }

    private void saveConfig() {
        String url = binding.syncServerInput.getText().toString().trim();
        String user = binding.syncUserInput.getText().toString().trim();
        String pass = binding.syncPasswordInput.getText().toString();
        boolean auto = binding.syncAutoSwitch.isChecked();
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, R.string.sync_complete_configuration_required, Toast.LENGTH_SHORT).show();
            return;
        }
        LauncherSyncBridge.saveConfig(this, url, user, pass, auto);
        Toast.makeText(this, R.string.sync_configuration_saved, Toast.LENGTH_SHORT).show();
        renderStatus();
    }

    private void testConnection() {
        String url = binding.syncServerInput.getText().toString().trim();
        String user = binding.syncUserInput.getText().toString().trim();
        String pass = binding.syncPasswordInput.getText().toString();
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, R.string.sync_complete_configuration_required, Toast.LENGTH_SHORT).show();
            return;
        }
        // 先临时保存再测试，避免 Bridge 内部使用旧配置
        LauncherSyncBridge.saveConfig(this, url, user, pass, binding.syncAutoSwitch.isChecked());
        Toast.makeText(this, R.string.sync_testing_connection, Toast.LENGTH_SHORT).show();
        AppExecutors.runOnSingle(() -> {
            boolean ok = LauncherSyncBridge.testConnection(this);
            runOnUiThread(() -> {
                if (isUiUnavailable()) return;
                Toast.makeText(this, ok ? R.string.sync_connection_success
                        : R.string.sync_connection_failed, Toast.LENGTH_SHORT).show();
                renderStatus();
            });
        });
    }

    private void syncNow() {
        if (!LauncherSyncBridge.isConfigured(this)) {
            Toast.makeText(this, R.string.sync_save_webdav_first, Toast.LENGTH_SHORT).show();
            return;
        }
        // 保存当前输入的配置再同步
        String url = binding.syncServerInput.getText().toString().trim();
        String user = binding.syncUserInput.getText().toString().trim();
        String pass = binding.syncPasswordInput.getText().toString();
        LauncherSyncBridge.saveConfig(this, url, user, pass, binding.syncAutoSwitch.isChecked());

        Toast.makeText(this, R.string.sync_in_progress, Toast.LENGTH_SHORT).show();
        LauncherSyncBridge.syncNow(this, new LauncherSyncBridge.Callback() {
            @Override public void onStart() {}
            @Override public void onProgress(String item, boolean changed) {}
            @Override
            public void onComplete(String message) {
                runOnUiThread(() -> {
                    if (isUiUnavailable()) return;
                    Toast.makeText(LauncherSyncCenterActivity.this, message, Toast.LENGTH_SHORT).show();
                    renderStatus();
                });
            }
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (isUiUnavailable()) return;
                    Toast.makeText(LauncherSyncCenterActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showImportConfirmDialog() {
        LauncherDialogFactory.showLongMessageConfirm(
                this,
                getString(R.string.sync_local_import_title),
                getString(R.string.sync_local_import_message),
                getString(R.string.sync_choose_file),
                () -> backupOpenLauncher.launch(new String[]{"application/octet-stream", "application/json", "text/*", "*/*"})
        );
    }

    private void exportLocalBackup(Uri uri) {
        Toast.makeText(this, R.string.sync_exporting_backup, Toast.LENGTH_SHORT).show();
        AppExecutors.runOnSingle(() -> {
            try {
                LauncherSyncBridge.GzipBackup backup = LauncherSyncBridge.exportLocalBackupAsGzip(this);
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("openOutputStream failed");
                    out.write(backup.bytes);
                    out.flush();
                }
                runOnUiThread(() -> {
                    if (isUiUnavailable()) return;
                    Toast.makeText(this,
                            getString(R.string.sync_backup_completed,
                                    backup.bytes.length / 1024, backup.originalSize / 1024),
                            Toast.LENGTH_LONG).show();
                });
            } catch (Error error) {
                // OOM/VirtualMachineError 必须传播，避免在已损坏的 JVM 状态下继续运行
                throw error;
            } catch (Exception e) {
                Log.e("LauncherSync", "export backup failed", e);
                runOnUiThread(() -> {
                    if (isUiUnavailable()) return;
                    Toast.makeText(this,
                            getString(R.string.sync_backup_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void importLocalBackup(Uri uri) {
        if (importInProgress) return;
        importInProgress = true;
        Toast.makeText(this, R.string.sync_importing_backup, Toast.LENGTH_SHORT).show();
        AppExecutors.runOnSingle(() -> {
            try {
                LauncherSyncBridge.importLocalBackupFromUri(this, uri);
                runOnUiThread(() -> {
                    if (isUiUnavailable()) return;
                    importInProgress = false;
                    Toast.makeText(this, R.string.sync_import_completed, Toast.LENGTH_LONG).show();
                });
            } catch (Error error) {
                // OOM/VirtualMachineError 必须传播，避免在已损坏的 JVM 状态下继续运行
                throw error;
            } catch (Exception e) {
                Log.e("LauncherSync", "import backup failed", e);
                runOnUiThread(() -> {
                    if (isUiUnavailable()) return;
                    importInProgress = false;
                    Toast.makeText(this, getString(R.string.sync_import_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private boolean isUiUnavailable() {
        return isFinishing() || isDestroyed() || binding == null;
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
