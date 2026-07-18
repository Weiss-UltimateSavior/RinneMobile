package bridge;

import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import org.tvp.kirikiri2.KR2Activity;

import java.io.File;
import java.util.Locale;

/**
 * Shared path normalization and SAF redirection helpers for KRKR engine bridging.
 * Extracted from NativeBridge and KR2Activity to eliminate duplication.
 */
public final class KrPathUtils {
    private static final String TAG = "KrPathUtils";

    private KrPathUtils() { }

    /**
     * Provides the current KR2Activity instance. Both getInstance() and GetInstance()
     * are tried for compatibility with legacy native code paths.
     */
    public static KR2Activity currentActivity() {
        KR2Activity activity = KR2Activity.getInstance();
        if (activity == null) activity = KR2Activity.GetInstance();
        return activity;
    }

    public static String normalizeFilePath(String path) {
        if (path == null) return path;
        String p = path.trim();
        if (p.startsWith("file://")) p = p.substring("file://".length());
        while (p.startsWith("./")) p = p.substring(2);
        if (p.startsWith("storage/")) p = "/" + p;
        while (p.contains("//")) p = p.replace("//", "/");
        return p;
    }

    public static String canonicalizeKrStoragePath(String path) {
        String p = normalizeFilePath(path);
        try {
            KR2Activity activity = currentActivity();
            if (activity == null || p == null || !p.startsWith("/")) return p;
            File appExternal = activity.getExternalFilesDir(null);
            if (appExternal != null) p = replacePrefixIgnoreCase(p, appExternal.getAbsolutePath());
            Intent intent = activity.getIntent();
            if (intent != null) {
                p = replacePrefixIgnoreCase(p, normalizeFilePath(intent.getStringExtra("projectRoot")));
                p = replacePrefixIgnoreCase(p, normalizeFilePath(intent.getStringExtra("gamedir")));
                p = replacePrefixIgnoreCase(p, normalizeFilePath(intent.getStringExtra("rootUri")));
                String gamePath = normalizeFilePath(intent.getStringExtra("gamePath"));
                if (gamePath != null && !gamePath.isEmpty()) {
                    File game = new File(gamePath);
                    File root = game.isFile() ? game.getParentFile() : game;
                    if (root != null) p = replacePrefixIgnoreCase(p, root.getAbsolutePath());
                }
            }
        } catch (Throwable ignored) { }
        return p;
    }

    public static String replacePrefixIgnoreCase(String path, String prefix) {
        if (path == null || prefix == null) return path;
        String clean = normalizeFilePath(prefix);
        if (clean == null || clean.length() <= 1 || !clean.startsWith("/")) return path;
        while (clean.endsWith("/") && clean.length() > 1) clean = clean.substring(0, clean.length() - 1);
        if (path.length() == clean.length() && path.regionMatches(true, 0, clean, 0, clean.length())) return clean;
        if (path.length() > clean.length()
                && path.regionMatches(true, 0, clean, 0, clean.length())
                && path.charAt(clean.length()) == '/') {
            return clean + path.substring(clean.length());
        }
        return path;
    }

    /**
     * Redirects /savedata/... paths to the scoped save directory configured via intent extras.
     * Returns the redirected absolute path, or null if no redirection applies.
     */
    public static String redirectScopedSavePath(String path) {
        try {
            KR2Activity activity = currentActivity();
            if (activity == null || activity.getIntent() == null) return null;
            if (!activity.getIntent().getBooleanExtra("scopedSaveDir", false)) return null;
            if (path == null || path.trim().isEmpty()) return null;
            String p = normalizeFilePath(path);
            String lower = p.toLowerCase(Locale.ROOT);
            int idx = lower.indexOf("/savedata/");
            int folderLen = "/savedata/".length();
            if (idx < 0) {
                if (lower.endsWith("/savedata")) {
                    idx = lower.length() - "/savedata".length();
                    folderLen = "/savedata".length();
                } else {
                    return null;
                }
            }
            String rel = p.length() > idx + folderLen ? p.substring(idx + folderLen) : "";
            String root = normalizeFilePath(activity.getIntent().getStringExtra("scopedSaveRoot"));
            if (root != null && !root.trim().isEmpty() && root.startsWith("/")) {
                File dir = new File(root);
                File out = rel.isEmpty() ? dir : new File(dir, rel);
                File parent = out.isDirectory() ? out : out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                Log.i(TAG, "redirect KR save " + p + " -> " + out.getAbsolutePath());
                return out.getAbsolutePath();
            }
            root = normalizeFilePath(activity.getIntent().getStringExtra("projectRoot"));
            if (root == null || root.trim().isEmpty()) root = normalizeFilePath(activity.getIntent().getStringExtra("gamedir"));
            if (root == null || root.trim().isEmpty() || !root.startsWith("/")) return null;
            File dir = new File(root, "savedata");
            File out = rel.isEmpty() ? dir : new File(dir, rel);
            File parent = out.isDirectory() ? out : out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Log.i(TAG, "redirect KR save " + p + " -> " + out.getAbsolutePath());
            return out.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "redirect KR save failed path=" + path, t);
            return null;
        }
    }
}
