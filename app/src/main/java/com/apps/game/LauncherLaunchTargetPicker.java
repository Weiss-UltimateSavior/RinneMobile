package com.apps.game;

import android.content.Context;
import android.net.Uri;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.yuki.yukihub.R;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.util.AppExecutors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared startup-target picker for add-game and edit-game forms. */
final class LauncherLaunchTargetPicker {
    private static final String DIRECTORY_TARGET = "[游戏目录]";

    interface Callback {
        void onTargetSelected(String target);
    }

    private LauncherLaunchTargetPicker() {
    }

    static void show(AppCompatActivity activity, Uri directoryUri, EngineType engine, Callback callback) {
        if (directoryUri == null) {
            Toast.makeText(activity, "请先选择游戏目录", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(activity, 270), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 22), dp(activity, 20), dp(activity, 22), dp(activity, 16));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);

        TextView title = text(activity, "选择启动文件", 16, true);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView status = text(activity, "正在扫描游戏文件...", 13, false);
        status.setTextColor(ContextCompat.getColor(activity, R.color.launcher_text_muted_color));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(activity, 13), 0, 0);
        root.addView(status, statusParams);
        window.setContentView(root);

        Context appContext = activity.getApplicationContext();
        AppExecutors.runOnIo(() -> {
            List<Target> targets = scanTargets(appContext, directoryUri, engine);
            activity.runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                root.removeView(status);
                if (targets.isEmpty()) {
                    status.setText("未找到游戏文件");
                    root.addView(status, statusParams);
                } else {
                    ScrollView scroll = new ScrollView(activity);
                    LinearLayout list = new LinearLayout(activity);
                    list.setOrientation(LinearLayout.VERTICAL);
                    for (Target target : targets) {
                        TextView item = text(activity, target.label, 13, false);
                        item.setSingleLine(true);
                        item.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                        item.setBackground(LauncherTheme.cancelChip(activity));
                        LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 38));
                        itemParams.setMargins(0, dp(activity, 7), 0, 0);
                        item.setOnClickListener(view -> {
                            if (callback != null) callback.onTargetSelected(target.value);
                            dialog.dismiss();
                        });
                        list.addView(item, itemParams);
                    }
                    scroll.addView(list);
                    int listHeight = dp(activity, 7) + targets.size() * (dp(activity, 38) + dp(activity, 7));
                    LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, Math.min(listHeight, dp(activity, 280)));
                    scrollParams.setMargins(0, dp(activity, 7), 0, 0);
                    root.addView(scroll, scrollParams);
                }
                TextView cancel = text(activity, "取消", 13, true);
                cancel.setTextColor(LauncherTheme.primary(activity));
                cancel.setBackground(LauncherTheme.cancelChip(activity));
                LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 36));
                cancelParams.setMargins(0, dp(activity, 9), 0, 0);
                cancel.setOnClickListener(view -> dialog.dismiss());
                root.addView(cancel, cancelParams);
            });
        });
    }

    private static List<Target> scanTargets(Context context, Uri directoryUri, EngineType engine) {
        List<Target> targets = new ArrayList<>();
        boolean[] hasRenpyEntry = {engine == EngineType.RENPY};
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, directoryUri);
            collectTargets(root, "", 1, 2, targets, hasRenpyEntry);
        } catch (Throwable ignored) {
        }
        if (hasRenpyEntry[0]) {
            targets.add(0, new Target("Ren'Py 游戏目录", DIRECTORY_TARGET));
        }
        return targets;
    }

    private static void collectTargets(DocumentFile directory, String prefix, int level, int maxLevel,
                                       List<Target> targets, boolean[] hasRenpyEntry) {
        if (directory == null || !directory.isDirectory()) return;
        DocumentFile[] files;
        try {
            files = directory.listFiles();
        } catch (Throwable ignored) {
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
            } catch (Throwable ignored) {
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
                || lowerName.endsWith(".pbp") || lowerName.endsWith(".desktop")
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
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static TextView text(Context context, String value, int sizeSp, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(ContextCompat.getColor(context, R.color.launcher_text_color));
        view.setTextSize(sizeSp);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
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
