package com.apps.game;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import com.apps.theme.LauncherDialogFactory;
import com.core.R;
import com.core.model.EngineType;
import com.core.util.AppExecutors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared startup-target picker for add-game and edit-game forms. */
final class LauncherLaunchTargetPicker {
    private static final String TAG = "LauncherLaunchTargetPicker";
    private static final String DIRECTORY_TARGET = "[游戏目录]";

    interface Callback {
        void onTargetSelected(String target);
    }

    private LauncherLaunchTargetPicker() {
    }

    static void show(AppCompatActivity activity, Uri directoryUri, EngineType engine, Callback callback) {
        if (directoryUri == null) {
            Toast.makeText(activity, R.string.game_directory_required, Toast.LENGTH_SHORT).show();
            return;
        }
        // 弹窗外壳（透明 window / card 背景 / 动效 / 宽度兜底）统一走 LauncherDialogFactory。
        // 第一阶段：loading 外壳（标题 + “正在扫描”提示），不可取消，生命周期由本方法管理。
        AlertDialog loading = LauncherDialogFactory.showLoading(
                activity,
                activity.getString(R.string.game_launch_choose_file),
                activity.getString(R.string.game_launch_scanning));
        Context appContext = activity.getApplicationContext();
        AppExecutors.runOnIo(() -> {
            List<Target> targets = scanTargets(appContext, directoryUri, engine);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (!loading.isShowing()) return;
                loading.dismiss();
                if (targets.isEmpty()) {
                    // 无可用目标：沿用“未找到游戏文件”提示语义。
                    LauncherDialogFactory.showInfo(
                            activity,
                            activity.getString(R.string.game_launch_choose_file),
                            activity.getString(R.string.game_launch_no_file));
                    return;
                }
                CharSequence[] labels = new CharSequence[targets.size()];
                for (int i = 0; i < targets.size(); i++) {
                    labels[i] = targets.get(i).label;
                }
                // 第二阶段：工厂单选列表外壳（checkedIndex 传 -1 表示全部未选中），
                // 选中索引回映射为目标值后走原回调，语义与原实现一致。
                LauncherDialogFactory.showSingleChoice(
                        activity,
                        activity.getString(R.string.game_launch_choose_file),
                        labels,
                        -1,
                        index -> {
                            if (callback != null) callback.onTargetSelected(targets.get(index).value);
                        });
            });
        });
    }

    private static List<Target> scanTargets(Context context, Uri directoryUri, EngineType engine) {
        List<Target> targets = new ArrayList<>();
        boolean[] hasRenpyEntry = {engine == EngineType.RENPY};
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, directoryUri);
            collectTargets(root, "", 1, 2, targets, hasRenpyEntry);
        } catch (Exception e) {
            Log.w(TAG, "scanLaunchTargets failed", e);
        }
        if (hasRenpyEntry[0]) {
            targets.add(0, new Target(
                    context.getString(R.string.game_launch_renpy_directory), DIRECTORY_TARGET));
        }
        return targets;
    }

    private static void collectTargets(DocumentFile directory, String prefix, int level, int maxLevel,
                                       List<Target> targets, boolean[] hasRenpyEntry) {
        if (directory == null || !directory.isDirectory()) return;
        DocumentFile[] files;
        try {
            files = directory.listFiles();
        } catch (Exception error) {
            Log.w(TAG, "list launch target files failed", error);
            return;
        }
        if (files == null) return;
        for (DocumentFile file : files) {
            if (file == null) continue;
            String name = safeName(file);
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.isEmpty()) continue;
            boolean isDirectory = false;
            try {
                isDirectory = file.isDirectory();
            } catch (Exception e) {
                Log.d(TAG, "isDirectory check failed: " + file, e);
            }
            String target = prefix.isEmpty() ? name : prefix + "/" + name;
            if (isDirectory) {
                if (level < maxLevel) collectTargets(file, target, level + 1, maxLevel, targets, hasRenpyEntry);
                continue;
            }
            if (isRenpyFile(lower)) hasRenpyEntry[0] = true;
            if (isGameFile(lower)) targets.add(new Target(target, target));
        }
    }

    private static boolean isGameFile(String lowerName) {
        if (lowerName.endsWith(".xp3") || lowerName.endsWith(".pfs")
                || lowerName.endsWith(".iso") || lowerName.endsWith(".cso")
                || lowerName.endsWith(".chd") || lowerName.endsWith(".elf")
                || lowerName.endsWith(".pbp") || lowerName.endsWith(".xci")
                || lowerName.endsWith(".nsp") || lowerName.endsWith(".nca")
                || lowerName.endsWith(".nro") || lowerName.endsWith(".desktop")
                || lowerName.endsWith(".exe") || isRenpyFile(lowerName)) return true;
        return lowerName.equals("0.txt") || lowerName.equals("00.txt")
                || lowerName.equals("nscript.dat") || lowerName.equals("nscr_sec.dat")
                || lowerName.equals("onscript.nt2") || lowerName.equals("onscript.nt3")
                || lowerName.equals("index.html") || lowerName.equals("startup.tjs");
    }

    private static boolean isRenpyFile(String lowerName) {
        return lowerName.endsWith(".rpa") || lowerName.endsWith(".rpy") || lowerName.endsWith(".rpyc");
    }

    private static String safeName(DocumentFile file) {
        try {
            String name = file.getName();
            return name == null ? "" : name;
        } catch (Exception error) {
            Log.d(TAG, "read launch target name failed", error);
            return "";
        }
    }

    private static final class Target {
        final String label;
        final String value;

        Target(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }
}
