package com.core.ons;

import android.annotation.SuppressLint;
import android.content.Context;
import android.system.Os;
import android.util.Log;

import com.core.nativeplugin.NativeLibraryLoader;
import com.core.nativeplugin.NativePluginConstants;
import com.core.nativeplugin.NativePluginManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 加载 ONS(Yuri runtime) 外置 native 插件。
 *
 * 自 0.9.9.9.9.8.x 起，ONS 引擎 so 不再内置在 assets，而是通过「模块兼容页」以 zip 插件形式安装：
 * 校验 SHA-256 后解压到 app 私有目录 engine_plugins/ons/current，由 NativeLibraryLoader 按依赖顺序
 * System.load 加载。ONSPatch 与内置 DroidSansFallback.ttf 仍随 APK 打包。
 */
public final class OnsLibLoader {
    private static final String TAG = "OnsLibLoader";
    private static boolean loaded;

    private OnsLibLoader() { }

    @SuppressLint("UnsafeDynamicallyLoadedCode") // 插件 so 已通过 SHA-256 校验并解压到 app 私有目录。
    public static synchronized void load(Context context) {
        if (loaded) return;
        Context app = context.getApplicationContext();
        copyAssetFile(app, "DroidSansFallback.ttf", new File(app.getFilesDir(), "DroidSansFallback.ttf"));
        if (NativeLibraryLoader.loadOns(app) == null) {
            throw new IllegalStateException("ONS 外置插件未就绪或 native 库缺失，无法加载");
        }
        try {
            System.loadLibrary("ONSPatch");
        } catch (Throwable t) {
            Log.w(TAG, "load ONSPatch failed, continue", t);
        }
        loaded = true;
    }

    public static File getMainSharedObject(Context context) {
        String libPath = NativePluginManager.onsLibPath(context, NativePluginConstants.LIB_ONSYURI);
        if (libPath == null) {
            throw new IllegalStateException("ONS 外置插件缺少 libonsyuri.so，请重新导入插件");
        }
        File main = new File(libPath);
        if (!main.isFile()) {
            throw new IllegalStateException("ONS 外置插件缺少 libonsyuri.so，请重新导入插件");
        }
        return main;
    }

    private static File copyAssetFile(Context context, String asset, File out) {
        try {
            File parent = out.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.w(TAG, "mkdir failed: " + parent);
            }
            if (!out.exists() || out.length() <= 0) {
                try (InputStream in = context.getAssets().open(asset); FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
            }
            // Re-apply the private mode even for files created by an older build.
            Os.chmod(out.getAbsolutePath(), 0600);
        } catch (Throwable t) {
            throw new RuntimeException("copy asset failed: " + asset, t);
        }
        return out;
    }
}
