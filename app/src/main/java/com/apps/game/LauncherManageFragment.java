package com.apps.game;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.core.R;
import com.core.databinding.FragmentLauncherManageBinding;
import com.core.util.RxMainQueue;

import com.apps.LauncherPreferences;
import com.apps.settings.LauncherKrkrSettingsActivity;
import com.apps.LauncherNavigationMetricsKt;
import com.apps.settings.LauncherMetadataSourceActivity;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

/**
 * 管理页 Fragment：仅保留生命周期、UI 缩放和共享确认弹窗入口。
 * 业务逻辑委托给 6 个 Controller：DiagnosticsController / SyncSettingsController /
 * LocalBackupController / ScanDirectoryController / Xp3TargetResolver / ExternalImportController。
 */
public class LauncherManageFragment extends Fragment implements ManageHost {
    private FragmentLauncherManageBinding binding;
    private final RxMainQueue mainQueue = new RxMainQueue();
    private boolean importInProgress;

    // ===== Controller 实例 =====
    private DiagnosticsController diagnosticsController;
    private SyncSettingsController syncSettingsController;
    private LocalBackupController localBackupController;
    private ScanDirectoryController scanDirectoryController;
    private Xp3TargetResolver xp3TargetResolver;
    private ExternalImportController externalImportController;

    // ===== ActivityResultLaunchers（必须在 Fragment 中注册） =====
    private final ActivityResultLauncher<Uri> scanDirectoryPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                scanDirectoryController.persistAndSaveScanDirectory(uri);
            });

    private final ActivityResultLauncher<String[]> backupOpenLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                localBackupController.importLocalBackup(uri);
            });

    private final ActivityResultLauncher<String> backupCreateLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
                if (uri == null) return;
                localBackupController.exportLocalBackup(uri);
            });

    private final ActivityResultLauncher<String[]> playniteImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                externalImportController.doImportFromPlaynite(uri);
            });

    private final ActivityResultLauncher<String[]> potatovnImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                externalImportController.doImportFromPotatoVn(uri);
            });

    private final ActivityResultLauncher<String[]> lunaboxImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                externalImportController.doImportFromLunaBox(uri);
            });

    private final ActivityResultLauncher<Uri> vniteImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                externalImportController.doImportFromVnite(uri);
            });

    // ==================== 生命周期 ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLauncherManageBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    protected boolean usePortraitManageScaler() {
        return true;
    }

    protected boolean applyManageSystemBarInsets() {
        return true;
    }

    protected final void bindManageRoot(@NonNull View root) {
        binding = FragmentLauncherManageBinding.bind(root);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (usePortraitManageScaler()) {
            applyTabletPortraitLayout();
        }
        if (applyManageSystemBarInsets()) {
            applySystemBarInsets();
        }

        // 创建 Controllers
        diagnosticsController = new DiagnosticsController(this);
        syncSettingsController = new SyncSettingsController(this, new SyncSettingsController.BackupActions() {
            @Override
            public void onExportLocalBackup() { localBackupController.exportLocalBackupToFile(); }
            @Override
            public void onConfirmImportLocalBackup() { localBackupController.confirmImportLocalBackup(); }
            @Override
            public void openSyncCenter() { LauncherManageFragment.this.openSyncCenter(); }
        });
        localBackupController = new LocalBackupController(this, backupOpenLauncher, backupCreateLauncher);
        xp3TargetResolver = new Xp3TargetResolver(this);
        scanDirectoryController = new ScanDirectoryController(
                this, scanDirectoryPicker, xp3TargetResolver::executeScan,
                binding.scanDirectoryList, binding.scanDirectoryEmpty);
        externalImportController = new ExternalImportController(
                this, playniteImportLauncher, potatovnImportLauncher,
                vniteImportLauncher, lunaboxImportLauncher);

        bindActions();
        applyThemeTone();
        scanDirectoryController.renderScanDirectories();
    }

    @Override
    public void onDestroyView() {
        if (xp3TargetResolver != null) xp3TargetResolver.cleanup();
        if (externalImportController != null) externalImportController.cleanup();
        importInProgress = false;
        if (binding != null) {
            binding.getRoot().setOnApplyWindowInsetsListener(null);
        }
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        LauncherNavigationMetricsKt.refreshNavigationOverlayInsets(this);
    }

    // ==================== UI 布局 ====================

    private void applySystemBarInsets() {
        FragmentLauncherManageBinding currentBinding = binding;
        int originalLeft = currentBinding.manageScroll.getPaddingLeft();
        int originalTop = currentBinding.manageScroll.getPaddingTop();
        int originalRight = currentBinding.manageScroll.getPaddingRight();
        int originalBottom = currentBinding.manageScroll.getPaddingBottom();

        currentBinding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            currentBinding.manageScroll.setPadding(
                    originalLeft,
                    originalTop + insets.getSystemWindowInsetTop(),
                    originalRight,
                    LauncherNavigationMetricsKt.navigationOverlayBottomPadding(this, originalBottom)
            );
            return insets;
        });
        currentBinding.getRoot().requestApplyInsets();
    }

    private void bindActions() {
        binding.actionAddDirectory.setOnClickListener(view -> scanDirectoryController.confirmAddDirectory());
        binding.actionScanGame.setOnClickListener(view -> scanDirectoryController.scanConfiguredDirectories());
        binding.actionAddGame.setOnClickListener(view -> openAddGame());
        binding.actionCloudSync.setOnClickListener(view -> syncSettingsController.showSyncOptions());
        binding.actionCrossSync.setOnClickListener(view -> externalImportController.showExternalImportDialog());
        binding.actionDiagnostics.setOnClickListener(view -> diagnosticsController.showDiagnosticsPrivacyDialog());
        binding.actionMetadataSource.setOnClickListener(view -> openMetadataSource());
        binding.actionKrkrSettings.setOnClickListener(view -> openKrkrSettings());
    }

    protected void openAddGame() {
        startActivity(new Intent(requireContext(), LauncherAddGameActivity.class));
    }

    protected void openMetadataSource() {
        startActivity(new Intent(requireContext(), LauncherMetadataSourceActivity.class));
    }

    protected void openKrkrSettings() {
        startActivity(new Intent(requireContext(), LauncherKrkrSettingsActivity.class));
    }

    protected void openSyncCenter() {
        startActivity(new Intent(requireContext(), com.apps.sync.LauncherSyncCenterActivity.class));
    }

    private void applyThemeTone() {
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        for (int i = 0; i < binding.manageActionList.getChildCount(); i++) {
            View row = binding.manageActionList.getChildAt(i);
            LauncherTheme.styleManageRow(row);
        }
    }

    // ==================== 平板竖屏缩放 ====================

    private float tabletPortraitScale() {
        return usePortraitManageScaler()
                ? LauncherTabletPortraitScaler.scaleFor(binding == null ? null : binding.getRoot())
                : 1f;
    }

    private void applyTabletPortraitLayout() {
        if (binding == null) return;
        LauncherTabletPortraitScaler.apply(binding.getRoot());
    }

    @Override
    public void setResponsiveTextSize(TextView view, float baseSp) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp * tabletPortraitScale());
    }

    @Override
    public int dp(int value) {
        return LauncherTheme.dp(requireContext(), value * tabletPortraitScale());
    }

    @Override
    public boolean isUiAvailable() {
        return isAdded() && binding != null;
    }

    // ==================== ManageHost 实现 ====================
    // requireContext() / isAdded() / startActivity() 由 Fragment 父类 final 方法隐式满足接口契约

    @Override
    public android.content.Context getAppContext() {
        return requireContext().getApplicationContext();
    }

    @Override
    public RxMainQueue getMainQueue() {
        return mainQueue;
    }

    @Override
    public SharedPreferences getPrefs() {
        return requireContext().getSharedPreferences(LauncherPreferences.APP_PREFS, android.content.Context.MODE_PRIVATE);
    }

    @Override
    public void showConfirmDialog(String title, String message, String confirmText, Runnable onConfirm) {
        LauncherDialogFactory.showConfirm(requireContext(), title, message, confirmText, onConfirm);
    }

    @Override
    public boolean isImportInProgress() {
        return importInProgress;
    }

    @Override
    public void setImportInProgress(boolean inProgress) {
        importInProgress = inProgress;
    }
}
