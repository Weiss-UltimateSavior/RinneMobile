package com.yuki.yukihub.launcherbridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.FileProvider;

import com.yuki.yukihub.util.DevLogger;

import java.io.File;

public final class LauncherDiagnosticsBridge {
    private LauncherDiagnosticsBridge() {
    }

    public static long cacheSize(Context context) {
        if (context == null) return 0L;
        return fileSize(context.getCacheDir()) + fileSize(context.getExternalCacheDir());
    }

    public static void clearCache(Context context) {
        if (context == null) return;
        deleteChildren(context.getCacheDir());
        deleteChildren(context.getExternalCacheDir());
    }

    public static boolean isLogEnabled() {
        return DevLogger.isEnabled();
    }

    public static void setLogEnabled(Context context, boolean enabled) {
        if (context == null) return;
        DevLogger.setEnabled(context.getApplicationContext(), enabled);
    }

    public static long logSize() {
        return DevLogger.getLogSize();
    }

    public static File logFile() {
        return DevLogger.getLogFile();
    }

    public static boolean clearLog() {
        return DevLogger.clearLog();
    }

    public static boolean exportLog(Context context) {
        if (context == null) return false;
        File logFile = logFile();
        if (logFile == null || !logFile.exists()) return false;
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(
                Intent.EXTRA_STREAM,
                FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", logFile)
        );
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "YukiHub Logcat");
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(shareIntent, "导出日志");
        if (!(context instanceof Activity)) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(chooser);
        return true;
    }

    private static long fileSize(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return file.length();
        long size = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) size += fileSize(child);
        }
        return size;
    }

    private static void deleteChildren(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) deleteRecursively(child);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        try {
            file.delete();
        } catch (Throwable ignored) {
        }
    }
}
