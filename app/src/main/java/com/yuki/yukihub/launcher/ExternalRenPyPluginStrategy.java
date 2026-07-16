package com.yuki.yukihub.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.yuki.yukihub.model.EngineType;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * 通过外部安装的 JoiPlay Ren'Py 插件启动 Ren'Py 游戏。
 *
 * <p>插件包名：{@code cyou.joiplay.runtime.renpy.v8d4d1}
 * 插件接收 {@code cyou.joiplay.runtime.renpy.run} intent action。
 *
 * <p>Game JSON 字段：{@code title, id, folder, execFile, type}（type 固定为 {@code "renpy"}）。
 * Settings 使用嵌套 JSON 格式（如 {@code {"app":{"cheats":{"boolean":false}}}}），
 * 但可传空 {@code {}} 让插件使用默认配置。
 *
 * <p>与 RPG Maker 不同：Ren'Py 没有 RTP 路径要求，也无需 configuration.json。
 * 虽然插件 APK 内有 loadConfig()，但 Ren'Py 不需要特殊配置文件。</p>
 */
public class ExternalRenPyPluginStrategy implements EngineLaunchStrategy {

    private static final String TAG = "RenPyStrategy";

    /** Ren'Py Plugin 的真实包名（来自逆向 smali）。 */
    public static final String PLUGIN_PACKAGE = "cyou.joiplay.runtime.renpy.v8d4d1";

    /** Ren'Py 插件接收的 intent action。 */
    private static final String ACTION_RUN = "cyou.joiplay.runtime.renpy.run";

    /** YukiHub 内部使用的别名——与 InternalKrkrStrategy 的命名风格保持一致。 */
    private static final String ALIAS_RENPY = "internal.renpy";
    private static final String ALIAS_RENPY8 = "internal.renpy8";

    @Override
    public EngineType getEngineType() {
        return EngineType.RENPY;
    }

    @Override
    public boolean supports(LaunchRequest request) {
        if (request == null || request.packageName == null) return false;
        String pkg = request.packageName.trim().toLowerCase(Locale.ROOT);
        if (pkg.isEmpty()) return false;
        if (PLUGIN_PACKAGE.equalsIgnoreCase(pkg)) return true;
        return pkg.equals(ALIAS_RENPY) || pkg.equals(ALIAS_RENPY8);
    }

    @Override
    public boolean launch(Context context, LaunchRequest request) {
        if (context == null || request == null) return false;
        if (!isRenPyPluginInstalled(context)) {
            Log.w(TAG, "Ren'Py Plugin (" + PLUGIN_PACKAGE + ") is not installed");
            return false;
        }
        String folder = resolveGameFolder(context, request);
        if (folder == null || folder.isEmpty()) {
            Log.w(TAG, "cannot resolve game folder from rootUri=" + request.rootUri);
            return false;
        }
        String title = resolveTitle(request, folder);
        String gameId = deriveGameId(folder, title);

        Intent intent = buildLaunchIntent(title, gameId, folder, request);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "startActivity failed action=" + intent.getAction() + " folder=" + folder, t);
            return false;
        }
    }

    /** 检查 Ren'Py Plugin 是否已安装。 */
    public static boolean isRenPyPluginInstalled(Context context) {
        if (context == null) return false;
        try {
            context.getPackageManager().getPackageInfo(PLUGIN_PACKAGE, PackageManager.GET_ACTIVITIES);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private static String resolveGameFolder(Context context, LaunchRequest request) {
        String rootUri = request.rootUri;
        if (rootUri == null || rootUri.isEmpty()) return null;
        String path = uriToFilePath(context, rootUri);
        if (path == null || path.trim().isEmpty()) return null;
        // 如果用户选了某个具体文件作为 launchTarget，则把 folder 落到它的父目录上。
        String target = request.launchTarget == null ? "" : request.launchTarget.trim();
        if (!target.isEmpty()
                && !target.startsWith("/")
                && !"[游戏目录]".equals(target)
                && !"DIR".equalsIgnoreCase(target)) {
            File candidate = new File(path, target);
            if (candidate.isFile()) {
                File parent = candidate.getParentFile();
                if (parent != null) return parent.getAbsolutePath();
            } else if (candidate.isDirectory()) {
                return candidate.getAbsolutePath();
            }
            // SAF 路径下，listFiles 可能失败，但 path 自身已是目录时仍可用。
            // Ren'Py 游戏通常以 .py 或 game/ 目录标识，不针对特定归档后缀做处理。
            return path.endsWith("/") ? path + target : path + "/" + target;
        }
        return path;
    }

    private static String resolveTitle(LaunchRequest request, String folder) {
        String target = request.launchTarget == null ? "" : request.launchTarget.trim();
        if (!target.isEmpty() && !"[游戏目录]".equals(target) && !"DIR".equalsIgnoreCase(target)) {
            return target;
        }
        if (folder != null && !folder.isEmpty()) {
            int slash = folder.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < folder.length()) return folder.substring(slash + 1);
            return folder;
        }
        return "Ren'Py Game";
    }

    private static String deriveGameId(String folder, String title) {
        try {
            String raw = folder == null ? title : folder;
            return Integer.toHexString(raw == null ? title.hashCode() : raw.hashCode());
        } catch (Throwable ignored) {
            return "yuki" + System.currentTimeMillis();
        }
    }

    private static Intent buildLaunchIntent(String title, String gameId,
                                             String folder, LaunchRequest request) {
        Intent intent = new Intent(ACTION_RUN);
        intent.setPackage(PLUGIN_PACKAGE);

        JSONObject game = new JSONObject();
        try {
            game.put("title", title);
            game.put("id", gameId);
            game.put("folder", folder);
            game.put("execFile", "");
            game.put("type", "renpy");
        } catch (Throwable ignored) { }
        intent.putExtra("game", game.toString());

        // settings 使用嵌套 JSON 格式（如 {"app":{"cheats":{"boolean":false}},
        // "renpy":{"renpy_hw_video":{"boolean":true}}}），但可以传空 {} 让插件使用默认配置。
        intent.putExtra("settings", "{}");

        // 6 = sensorLandscape，与 YukiHub 内置引擎 Activity 的 orientation 保持一致。
        intent.putExtra("orientation", 6);

        // 透传 rootUri 与 launchTarget，方便插件与 YukiHub 联调定位。
        if (request.rootUri != null) intent.putExtra("rootUri", request.rootUri);
        if (request.launchTarget != null) intent.putExtra("launchTarget", request.launchTarget);
        return intent;
    }

    /**
     * 与 {@link EmulatorLauncher#uriToFilePath} 行为对齐的本地实现，
     * 仅支持 file:// / content(SAF) / 直接路径三种常见形式。
     */
    private static String uriToFilePath(Context context, String uriText) {
        if (uriText == null || uriText.trim().isEmpty()) return uriText;
        if (uriText.startsWith("/")) return uriText;
        try {
            Uri uri = Uri.parse(uriText);
            String scheme = uri.getScheme();
            if (scheme == null) return uriText;
            if ("file".equalsIgnoreCase(scheme)) return uri.getPath();
            if (!"content".equalsIgnoreCase(scheme)) return uriText;
            String docId = null;
            String path = uri.getPath();
            boolean hasDocumentPart = path != null && path.contains("/document/");
            if (hasDocumentPart) {
                try { docId = DocumentsContract.getDocumentId(uri); } catch (Throwable ignored) { }
            }
            if (docId == null || docId.isEmpty()) {
                try { docId = DocumentsContract.getTreeDocumentId(uri); } catch (Throwable ignored) { }
            }
            if (docId == null || docId.isEmpty()) {
                try { docId = DocumentsContract.getDocumentId(uri); } catch (Throwable ignored) { }
            }
            if (docId != null) {
                int colon = docId.indexOf(':');
                String volume = colon >= 0 ? docId.substring(0, colon) : docId;
                String rel = colon >= 0 ? docId.substring(colon + 1) : "";
                if ("primary".equalsIgnoreCase(volume)) {
                    return rel.isEmpty() ? "/storage/emulated/0" : "/storage/emulated/0/" + rel;
                }
                if (!volume.isEmpty()) {
                    return rel.isEmpty() ? "/storage/" + volume : "/storage/" + volume + "/" + rel;
                }
            }
            // tree URI 无法解析为文件路径时，返回 null 让上层报错而不传错误路径给插件。
            return copyTreeToLocalPath(context, uri);
        } catch (Throwable ignored) {
            return uriText;
        }
    }

    /**
     * 极端兜底：对某些厂商 ROM 的 tree URI 无法解出 primary 路径时，
     * 仅探测是否能取到本地路径，不做实际拷贝以避免大文件复制副作用。
     */
    private static String copyTreeToLocalPath(Context context, Uri treeUri) {
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(context, treeUri);
            if (dir == null || !dir.isDirectory()) return null;
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
