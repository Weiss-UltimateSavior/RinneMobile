package com.apps.game;

import android.net.Uri;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;

import com.apps.theme.LauncherDialogFactory;
import com.core.R;
import com.core.importer.ImportGameData;
import com.core.importer.ImportResult;
import com.core.importer.ImporterService;
import com.core.importer.LunaBoxImporter;
import com.core.importer.PlayniteImporter;
import com.core.importer.PotatoVnImporter;
import com.core.importer.VniteImporter;
import com.core.util.AppExecutors;

import java.util.List;

/**
 * 从 LauncherManageFragment 抽取的跨端同步导入控制器。
 *
 * 负责从 Playnite / PotatoVN / Vnite / LunaBox 各平台解析数据、预览候选列表、
 * 用户勾选后写入库。所有 Fragment 相关能力通过 {@link ManageHost} 桥接，
 * 各平台 ActivityResultLauncher 由 Fragment 注册后通过构造器注入。
 */
public final class ExternalImportController {

    private final ManageHost host;
    private final ActivityResultLauncher<String[]> playniteLauncher;
    private final ActivityResultLauncher<String[]> potatovnLauncher;
    private final ActivityResultLauncher<Uri> vniteLauncher;
    private final ActivityResultLauncher<String[]> lunaboxLauncher;
    private AlertDialog importLoadingDialog;

    public ExternalImportController(ManageHost host,
                                    ActivityResultLauncher<String[]> playniteLauncher,
                                    ActivityResultLauncher<String[]> potatovnLauncher,
                                    ActivityResultLauncher<Uri> vniteLauncher,
                                    ActivityResultLauncher<String[]> lunaboxLauncher) {
        this.host = host;
        this.playniteLauncher = playniteLauncher;
        this.potatovnLauncher = potatovnLauncher;
        this.vniteLauncher = vniteLauncher;
        this.lunaboxLauncher = lunaboxLauncher;
    }

    public void showExternalImportDialog() {
        if (host.isImportInProgress()) return;
        LauncherDialogFactory.showMessageActionChoices(
                host.requireContext(),
                host.getString(R.string.game_import_cross_platform),
                host.getString(R.string.game_import_source_message),
                new CharSequence[] {
                        "Playnite（JSON）",
                        "PotatoVN（ZIP）",
                        host.getString(R.string.game_import_vnite_directory),
                        "LunaBox（ZIP）"
                },
                index -> {
                    switch (index) {
                        case 0: playniteLauncher.launch(new String[]{"application/json", "text/*", "*/*"}); break;
                        case 1: potatovnLauncher.launch(new String[]{"application/zip", "application/*zip*", "*/*"}); break;
                        case 2: vniteLauncher.launch(null); break;
                        case 3: lunaboxLauncher.launch(new String[]{"application/zip", "application/*zip*", "*/*"}); break;
                    }
                });
    }

    public void doImportFromPlaynite(Uri uri) {
        android.content.Context appContext = host.getAppContext();
        parseAndPreview(appContext, () -> PlayniteImporter.parse(appContext, uri));
    }

    public void doImportFromPotatoVn(Uri uri) {
        android.content.Context appContext = host.getAppContext();
        parseAndPreview(appContext, () -> PotatoVnImporter.parse(appContext, uri));
    }

    public void doImportFromVnite(Uri uri) {
        android.content.Context appContext = host.getAppContext();
        parseAndPreview(appContext, () -> VniteImporter.parse(appContext, uri));
    }

    public void doImportFromLunaBox(Uri uri) {
        android.content.Context appContext = host.getAppContext();
        parseAndPreview(appContext, () -> LunaBoxImporter.parse(appContext, uri));
    }

    @FunctionalInterface
    private interface ParseTask {
        List<ImportGameData> parse() throws Exception;
    }

    private void parseAndPreview(android.content.Context appContext, ParseTask task) {
        host.setImportInProgress(true);
        showImportLoading(host.getString(R.string.game_import_parsing));
        AppExecutors.runOnSingle(() -> {
            try {
                List<ImportGameData> games = task.parse();
                new ImporterService(appContext).markExisting(games);
                host.getMainQueue().post(() -> {
                    if (!host.isAdded()) return;
                    dismissImportLoading();
                    showImportPreviewDialog(games);
                });
            } catch (Error error) {
                // OOM/VirtualMachineError 必须传播，避免在已损坏的 JVM 状态下继续运行
                throw error;
            } catch (Exception e) {
                Log.e("LauncherManage", "external import parse failed", e);
                host.getMainQueue().post(() -> {
                    if (!host.isAdded()) return;
                    dismissImportLoading();
                    host.setImportInProgress(false);
                    host.showConfirmDialog(host.getString(R.string.game_import_parse_failed),
                            e.getMessage() != null ? e.getMessage()
                                    : host.getString(R.string.game_common_unknown_error),
                            host.getString(R.string.game_common_got_it), () -> {});
                });
            }
        });
    }

    private void showImportLoading(String hint) {
        dismissImportLoading();
        importLoadingDialog = LauncherDialogFactory.showLoading(
                host.requireContext(),
                host.getString(R.string.game_import_importing),
                hint);
        importLoadingDialog.setCancelable(false);
        importLoadingDialog.setCanceledOnTouchOutside(false);
    }

    private void dismissImportLoading() {
        if (importLoadingDialog != null && importLoadingDialog.isShowing()) {
            importLoadingDialog.dismiss();
        }
        importLoadingDialog = null;
    }

    private void showImportPreviewDialog(List<ImportGameData> games) {
        if (games == null || games.isEmpty()) {
            host.setImportInProgress(false);
            host.showConfirmDialog(host.getString(R.string.game_import_none_title),
                    host.getString(R.string.game_import_none_message),
                    host.getString(R.string.game_common_got_it), () -> {});
            return;
        }

        ExternalImportPreviewDialog.show(host, games, new ExternalImportPreviewDialog.Callback() {
            @Override
            public void onImport() {
                executeExternalImport(games);
            }

            @Override
            public void onCancel() {
                host.setImportInProgress(false);
                ImporterService.cancelImport();
            }
        });
    }

    private void executeExternalImport(List<ImportGameData> games) {
        android.content.Context appContext = host.getAppContext();
        showImportLoading(host.getString(R.string.game_import_writing));
        AppExecutors.runOnSingle(() -> {
            try {
                ImportResult result = new ImporterService(appContext).importSelected(games);
                host.getMainQueue().post(() -> {
                    if (!host.isAdded()) return;
                    dismissImportLoading();
                    host.setImportInProgress(false);
                    afterExternalImport(result);
                });
            } catch (Error error) {
                // OOM/VirtualMachineError 必须传播，避免在已损坏的 JVM 状态下继续运行
                throw error;
            } catch (Exception e) {
                Log.e("LauncherManage", "external import write failed", e);
                host.getMainQueue().post(() -> {
                    if (!host.isAdded()) return;
                    dismissImportLoading();
                    host.setImportInProgress(false);
                    host.showConfirmDialog(host.getString(R.string.game_import_failed),
                            e.getMessage() != null ? e.getMessage()
                                    : host.getString(R.string.game_common_unknown_error),
                            host.getString(R.string.game_common_got_it), () -> {});
                });
            }
        });
    }

    private void afterExternalImport(ImportResult result) {
        if (result == null) {
            host.showConfirmDialog(host.getString(R.string.game_import_complete),
                    host.getString(R.string.game_import_not_performed),
                    host.getString(R.string.game_common_got_it), () -> {});
            return;
        }
        StringBuilder msg = new StringBuilder(result.summary());
        if (!result.skippedNames.isEmpty()) {
            msg.append(host.getString(R.string.game_import_skipped_items));
            for (String n : result.skippedNames) msg.append("\n• ").append(n);
        }
        if (!result.failedNames.isEmpty()) {
            msg.append(host.getString(R.string.game_import_failed_items));
            for (String n : result.failedNames) msg.append("\n• ").append(n);
        }
        host.showConfirmDialog(host.getString(R.string.game_import_complete), msg.toString(),
                host.getString(R.string.game_common_got_it), () -> {});
    }

    public void cleanup() {
        dismissImportLoading();
        host.setImportInProgress(false);
        ImporterService.cancelImport();
    }
}
