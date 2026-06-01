package com.yuki.yukihub.krkr;

import android.util.Log;

public final class KrkrSaveHook {
    private static final String TAG = "KrkrSaveHook";
    private static boolean loaded;

    static {
        try {
            System.loadLibrary("yukihub_krkr_save_hook");
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
            Log.e(TAG, "load native hook library failed", t);
        }
    }

    private KrkrSaveHook() { }

    public static boolean enable(String originalSavedataDir, String privateSaveDir) {
        if (!loaded) return false;
        if (originalSavedataDir == null || originalSavedataDir.trim().isEmpty()) return false;
        if (privateSaveDir == null || privateSaveDir.trim().isEmpty()) return false;
        try {
            boolean ok = nativeEnable(originalSavedataDir, privateSaveDir);
            Log.i(TAG, "enable original=" + originalSavedataDir + " private=" + privateSaveDir + " ok=" + ok);
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "enable failed", t);
            return false;
        }
    }

    public static String redirect(String path) {
        if (!loaded || path == null) return path;
        try {
            return nativeRedirect(path);
        } catch (Throwable ignored) {
            return path;
        }
    }

    private static native boolean nativeEnable(String originalSavedataDir, String privateSaveDir);
    private static native String nativeRedirect(String path);
}
