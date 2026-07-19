package com.yuki.yukihub.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import com.yuki.yukihub.model.EngineType;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * 通过外部安装的 JoiPlay Godot 插件启动 Godot 游戏。
 *
 * <p>支持 Godot 3 和 Godot 4 两个版本的插件：
 * <ul>
 *   <li>Godot 3 插件包名：{@code cyou.joiplay.runtime.godot3}，action {@code cyou.joiplay.runtime.godot3.run}</li>
 *   <li>Godot 4 插件包名：{@code cyou.joiplay.runtime.godot4}，action {@code cyou.joiplay.runtime.godot4.run}</li>
 * </ul>
 *
 * <p>版本自动检测：JoiPlay 主程序通过读取 .pck 文件头的第 9 字节（offset 8）判断 Godot 主版本号。
 * .pck 文件格式：
 * <pre>
 *   偏移 0-3: "GDPC" (magic)
 *   偏移 4-7: pack version (int32)
 *   偏移 8:   Godot major version (3 = Godot 3, 4 = Godot 4)
 * </pre>
 * YukiHub 复刻此逻辑：扫描游戏目录下的 .pck 文件，读取版本字节，
 * 自动选择对应的 Godot 插件（3 或 4）和 action。
 *
 * <p>如果没有 .pck 文件但存在 project.godot（源码项目），
 * 返回错误提示用户需要先在 Godot 编辑器中导出 .pck 文件。
 *
 * <p>Game JSON 字段：{@code title, id, folder, execFile, type}，
 * 其中 type 设为 {@code "godot3"} 或 {@code "godot4"}。
 * Settings 使用 GamePad JSON 格式，传空 {@code {}} 使用默认配置。
 *
 * <p>GodotApp.onCreate 读取 intent extras {@code "game"} 和 {@code "settings"}，
 * 然后 {@code genCommandLine()} 自动扫描游戏目录下的 .pck 文件，
 * 生成 {@code --path <folder> --main-pack <.pck> --fullscreen --immersive --xr-mode off} 命令行。
 */
public class ExternalGodotPluginStrategy implements EngineLaunchStrategy {

    private static final String TAG = "GodotStrategy";

    /** Godot 4 Plugin 的真实包名（来自逆向 smali）。 */
    public static final String PLUGIN_PACKAGE_GODOT4 = "cyou.joiplay.runtime.godot4";
    /** Godot 3 Plugin 的真实包名（按 JoiPlay 命名规律推断）。 */
    public static final String PLUGIN_PACKAGE_GODOT3 = "cyou.joiplay.runtime.godot3";

    /** YukiHub 内部使用的别名。 */
    private static final String ALIAS_GODOT = "internal.godot";
    private static final String ALIAS_GODOT3 = "internal.godot3";
    private static final String ALIAS_GODOT4 = "internal.godot4";

    /** .pck 文件 magic header："GDPC"（4 字节）。 */
    private static final byte[] PCK_MAGIC = {0x47, 0x44, 0x50, 0x43}; // 'G','D','P','C'

    @Override
    public EngineType getEngineType() {
        return EngineType.GODOT;
    }

    @Override
    public boolean supports(LaunchRequest request) {
        if (request == null || request.packageName == null) return false;
        String pkg = request.packageName.trim().toLowerCase(Locale.ROOT);
        if (pkg.isEmpty()) return false;
        if (PLUGIN_PACKAGE_GODOT4.equalsIgnoreCase(pkg)) return true;
        if (PLUGIN_PACKAGE_GODOT3.equalsIgnoreCase(pkg)) return true;
        return pkg.equals(ALIAS_GODOT) || pkg.equals(ALIAS_GODOT3) || pkg.equals(ALIAS_GODOT4);
    }

    @Override
    public boolean launch(Context context, LaunchRequest request) {
        if (context == null || request == null) return false;
        Log.i(TAG, "launch: rootUri=" + request.rootUri + " launchTarget=" + request.launchTarget);
        String folder = resolveGameFolder(context, request);
        Log.i(TAG, "launch: resolved folder=" + folder);
        if (folder == null || folder.isEmpty()) {
            Log.w(TAG, "cannot resolve game folder from rootUri=" + request.rootUri);
            return false;
        }

        File folderFile = new File(folder);
        if (!folderFile.exists() || !folderFile.isDirectory()) {
            Log.w(TAG, "folder is not a valid directory: " + folder
                    + " exists=" + folderFile.exists()
                    + " isDir=" + (folderFile.exists() && folderFile.isDirectory()));
            return false;
        }

        // 扫描 .pck 文件并检测 Godot 版本
        File pckFile = findPckFile(folderFile);
        String godotType;
        if (pckFile != null) {
            godotType = detectGodotVersion(pckFile);
            Log.i(TAG, "found .pck file: " + pckFile.getName() + " godotType=" + godotType);
        } else {
            // 没有 .pck 文件，检查是否有 project.godot（源码项目）
            if (new File(folderFile, "project.godot").exists()) {
                Log.e(TAG, "ERROR: project.godot found but no .pck file. "
                        + "Godot Android runtime requires a .pck file (exported from Godot Editor). "
                        + "Source projects (project.godot only) cannot run on Android.");
            } else {
                Log.w(TAG, "no .pck file and no project.godot found in folder: " + folder);
            }
            // 仍然尝试启动（让插件给出更具体的错误信息）
            godotType = "godot4";
        }

        // 根据版本选择插件包名
        String pluginPackage;
        String action;
        if ("godot3".equals(godotType)) {
            pluginPackage = PLUGIN_PACKAGE_GODOT3;
            action = "cyou.joiplay.runtime.godot3.run";
        } else {
            pluginPackage = PLUGIN_PACKAGE_GODOT4;
            action = "cyou.joiplay.runtime.godot4.run";
        }

        // 检查对应版本的插件是否已安装
        // 注意：不能 fallback 到另一版本——Godot 3 与 Godot 4 的 .pck 格式不兼容，
        // 用错版本插件会导致 "Unable to setup the Godot engine" 崩溃。
        if (!isPluginInstalled(context, pluginPackage)) {
            String majorVersion = godotType.replace("godot", "");
            String msg = "游戏是 Godot " + majorVersion + " 项目，但未安装 Godot " + majorVersion
                    + " 插件。Godot 3 与 Godot 4 的 .pck 格式不兼容，请安装对应版本插件。";
            Log.e(TAG, "ERROR: " + msg + " (pluginPackage=" + pluginPackage + ")");
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
            return false;
        }

        String title = resolveTitle(request, folder);
        String gameId = deriveGameId(folder, title);

        Intent intent = buildLaunchIntent(title, gameId, folder, godotType, action, pluginPackage, request);
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

    /**
     * 扫描目录下的 .pck 文件，返回第一个找到的 .pck 文件。
     * 与 GodotApp.genCommandLine() 的扫描逻辑一致。
     */
    private static File findPckFile(File folder) {
        if (folder == null || !folder.isDirectory()) return null;
        File[] files = folder.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".pck")) {
                return f;
            }
        }
        return null;
    }

    /**
     * 读取 .pck 文件头的第 9 字节（offset 8）判断 Godot 主版本号。
     * .pck 格式：[0-3]="GDPC" [4-7]=pack_version [8]=godot_major_version
     *
     * @return "godot3" 或 "godot4"；读取失败时默认 "godot4"
     */
    private static String detectGodotVersion(File pckFile) {
        if (pckFile == null || !pckFile.exists()) return "godot4";
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(pckFile);
            byte[] header = new byte[9];
            int read = fis.read(header);
            if (read < 9) {
                Log.w(TAG, "pck file too small: " + pckFile.getName() + " read=" + read);
                return "godot4";
            }
            // 验证 magic "GDPC"
            for (int i = 0; i < 4; i++) {
                if (header[i] != PCK_MAGIC[i]) {
                    Log.w(TAG, "pck file magic mismatch: " + pckFile.getName()
                            + " expected GDPC but got 0x"
                            + String.format(Locale.ROOT, "%02x%02x%02x%02x",
                                    header[0], header[1], header[2], header[3]));
                    return "godot4";
                }
            }
            // 读取版本字节（offset 8）
            int versionByte = header[8] & 0xFF;
            Log.i(TAG, "pck header: magic=GDPC pack_version="
                    + ((header[4] & 0xFF) | ((header[5] & 0xFF) << 8)
                       | ((header[6] & 0xFF) << 16) | ((header[7] & 0xFF) << 24))
                    + " godot_major=" + versionByte);
            if (versionByte == 3) return "godot3";
            if (versionByte == 4) return "godot4";
            Log.w(TAG, "unknown godot major version: " + versionByte + " (defaulting to godot4)");
            return "godot4";
        } catch (IOException e) {
            Log.w(TAG, "failed to read pck header: " + pckFile.getName(), e);
            return "godot4";
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (IOException ignored) { }
            }
        }
    }

    /** 检查指定 Godot 插件是否已安装。 */
    public static boolean isPluginInstalled(Context context, String packageName) {
        if (context == null || packageName == null) return false;
        try {
            context.getPackageManager().getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    /** 检查 Godot 4 插件是否已安装（向后兼容）。 */
    public static boolean isGodotPluginInstalled(Context context) {
        return isPluginInstalled(context, PLUGIN_PACKAGE_GODOT4);
    }

    private static String resolveGameFolder(Context context, LaunchRequest request) {
        String rootUri = request.rootUri;
        if (rootUri == null || rootUri.isEmpty()) return null;
        String path = uriToFilePath(context, rootUri);
        if (path == null || path.trim().isEmpty()) return null;
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
            return path.endsWith("/") ? path + target : path + "/" + target;
        }
        return path;
    }

    private static String resolveTitle(LaunchRequest request, String folder) {
        String target = request.launchTarget == null ? "" : request.launchTarget.trim();
        if (!target.isEmpty() && !"[游戏目录]".equals(target) && !"DIR".equalsIgnoreCase(target)) {
            String name = target;
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            return name;
        }
        if (folder != null && !folder.isEmpty()) {
            int slash = folder.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < folder.length()) return folder.substring(slash + 1);
            return folder;
        }
        return "Godot Game";
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
                                            String folder, String godotType,
                                            String action, String pluginPackage,
                                            LaunchRequest request) {
        Intent intent = new Intent(action);
        intent.setPackage(pluginPackage);

        JSONObject game = new JSONObject();
        try {
            game.put("title", title);
            game.put("id", gameId);
            game.put("folder", folder);
            game.put("execFile", "");
            game.put("type", godotType);
        } catch (Throwable ignored) { }
        intent.putExtra("game", game.toString());
        Log.i(TAG, "buildLaunchIntent: action=" + action + " pkg=" + pluginPackage
                + " game json=" + game.toString());

        // settings 是 GamePad 的 JSON，传空让插件使用默认 GamePad 配置。
        intent.putExtra("settings", "{}");

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
            return copyTreeToLocalPath(context, uri);
        } catch (Throwable ignored) {
            return uriText;
        }
    }

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
