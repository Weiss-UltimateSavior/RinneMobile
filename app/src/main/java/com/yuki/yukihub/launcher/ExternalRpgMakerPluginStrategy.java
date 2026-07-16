package com.yuki.yukihub.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.yuki.yukihub.model.EngineType;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * 通过隐式 Intent 调用已安装的 RPG Maker Plugin (cyou.joiplay.runtime.rpgmaker)
 * 来运行 RPG Maker XP/VX/VX Ace/mkxp-z 游戏。
 *
 * <p>插件 APK 暴露了 4 个 {@code exported=true} 的 intent-filter 入口，
 * 由 PermissionActivity 接收，统一处理存储权限申请后启动 mkxp 主 Activity：
 * <ul>
 *   <li>{@code cyou.joiplay.runtime.rpgmxp.run}    → libmkxp18.so + Ruby 1.8 (RGSS1)</li>
 *   <li>{@code cyou.joiplay.runtime.rpgmvx.run}    → libmkxp19.so + Ruby 1.9 (RGSS2)</li>
 *   <li>{@code cyou.joiplay.runtime.rpgmvxace.run}  → libmkxp30.so + Ruby 3.x (RGSS3)</li>
 *   <li>{@code cyou.joiplay.runtime.mkxp-z.run}    → libmkxp30.so + Ruby 3.x (mkxp-z 自定义)</li>
 * </ul>
 *
 * <p>PermissionActivity 仅解析 {@code game} 这个 JSON extra，字段：
 * {@code title, id, folder, execFile, type}（参见 GameParser.parse）。
 * 其余字段如 useRuby18/archived 等仅由 MainActivity 在运行时读取，
 * PermissionActivity 接受省略。settings extra 可省略，省略时插件使用默认配置。</p>
 */
public class ExternalRpgMakerPluginStrategy implements EngineLaunchStrategy {

    private static final String TAG = "RpgMakerStrategy";

    /** RPG Maker Plugin 的真实包名。 */
    public static final String PLUGIN_PACKAGE = "cyou.joiplay.runtime.rpgmaker";

    /** YukiHub 内部使用的别名前缀；与 InternalKrkrStrategy 的命名风格保持一致。 */
    private static final String ALIAS_PREFIX = "internal.rpg";

    /** 自动识别别名——具体引擎由 EngineDetector 决定。 */
    private static final String ALIAS_AUTO = "internal.rpgmaker";

    /** mkxp-z 的别名（允许 dash 和无 dash 两种写法）。 */
    private static final String ALIAS_MKXPZ_DASH = "internal.mkxp-z";
    private static final String ALIAS_MKXPZ_NODASH = "internal.mkxpz";

    @Override
    public EngineType getEngineType() {
        return EngineType.RPGMAKER;
    }

    @Override
    public boolean supports(LaunchRequest request) {
        if (request == null || request.packageName == null) return false;
        String pkg = request.packageName.trim().toLowerCase(Locale.ROOT);
        if (pkg.isEmpty()) return false;
        if (PLUGIN_PACKAGE.equalsIgnoreCase(pkg)) return true;
        return pkg.equals(ALIAS_AUTO)
                || pkg.equals(ALIAS_PREFIX + "mxp")
                || pkg.equals(ALIAS_PREFIX + "mvx")
                || pkg.equals(ALIAS_PREFIX + "mvxace")
                || pkg.equals(ALIAS_MKXPZ_DASH)
                || pkg.equals(ALIAS_MKXPZ_NODASH);
    }

    @Override
    public boolean launch(Context context, LaunchRequest request) {
        if (context == null || request == null) return false;
        if (!isRpgMakerPluginInstalled(context)) {
            Log.w(TAG, "RPG Maker Plugin (" + PLUGIN_PACKAGE + ") is not installed");
            return false;
        }
        String gameType = resolveGameType(request);
        if (gameType == null) {
            Log.w(TAG, "cannot resolve RPG Maker subtype from packageName=" + request.packageName);
            return false;
        }
        String folder = resolveGameFolder(context, request);
        if (folder == null || folder.isEmpty()) {
            Log.w(TAG, "cannot resolve game folder from rootUri=" + request.rootUri);
            return false;
        }
        // 关键修复：RPGMPlugin 在 MainActivity 中会无条件把
        //   /sdcard/JoiPlay/RTP/<engineName>/app
        // 加入 mkxp 的 rtps[] 并以 fatalError=true 调用 PHYSFS_mount。
        // 该路径不存在时 mkxp 会抛 Exception 导致游戏闪退（错误：Failed to mount ... (notfound)）。
        // JoiPlay 主程序在首次运行时会下载 RTP 到此处，但 YukiHub 不做这事，
        // 所以必须由 YukiHub 主动创建该目录并放置一个 sf.sf2 占位 SoundFont。
        ensureRtpEnvironment(context, gameType);

        String title = resolveTitle(request, folder);
        String gameId = deriveGameId(folder, title);

        // 双重保险：在游戏目录创建 configuration.json（扁平 JSON 格式）。
        // 插件的 loadConfig() → MKXPConfigurationParser.loadFromFile() 读取此文件，
        // 格式是扁平的 {"useRuby18": true}（与 parse(String) 的嵌套格式不同）。
        // 如果文件已存在（由 JoiPlay 创建），不覆盖。
        ensureGameConfiguration(folder, gameId, gameType);

        Intent intent = buildLaunchIntent(gameType, title, gameId, folder, request);
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
     * RPGMPlugin 期望的 RTP 引擎目录名映射（来自 MainActivity.smali 的 switch 表）。
     * mkxp-z 在 RPGMPlugin 中没有特殊前缀，路径直接是 "mkxp-z"。
     */
    private static String rtpDirNameForGameType(String gameType) {
        switch (gameType) {
            case "rpgmxp": return "RPGXP";
            case "rpgmvx": return "RPGVX";
            case "mkxp-z": return "mkxp-z";
            case "rpgmvxace":
            default: return "RPGVXACE";
        }
    }

    /**
     * 在 /sdcard/JoiPlay/RTP/<engineName>/app/ 下创建空目录并放入 sf.sf2，
     * 避免 mkxp 因 PHYSFS_mount 失败抛 Exception 退出。
     *
     * <p>注意：调用方必须已持有 MANAGE_EXTERNAL_STORAGE 或 WRITE_EXTERNAL_STORAGE 权限。
     * RPGMPlugin 的 PermissionActivity 会替 YukiHub 申请并授予该权限，但本策略在
     * startActivity 之前调用——此时权限可能尚未授予。因此本方法捕获所有异常并仅打印日志，
     * 不阻断启动流程；若 mkdirs 失败，RPGMPlugin 仍会被启动，由它自己的权限流程兜底。
     * 极端情况下用户可在文件管理器手动创建一次该目录即可永久解决。</p>
     */
    private static void ensureRtpEnvironment(Context context, String gameType) {
        try {
            String engineName = rtpDirNameForGameType(gameType);
            File externalRoot = Environment.getExternalStorageDirectory();
            File rtpAppDir = new File(externalRoot,
                    "JoiPlay" + File.separator + "RTP" + File.separator + engineName + File.separator + "app");
            if (!rtpAppDir.exists() && !rtpAppDir.mkdirs()) {
                Log.w(TAG, "mkdirs failed for RTP dir: " + rtpAppDir.getAbsolutePath()
                        + "（多半缺少 MANAGE_EXTERNAL_STORAGE 权限，RPGMPlugin 后续会申请）");
                return;
            }
            // SoundFont：mkxp 把 midi_soundFont 设为 <rtpAppDir>/sf.sf2，若缺失 MIDI BGM 无法播放，
            // 但不会 fatal 退出。仍尝试从 assets 复制，让 MIDI 可用。
            File sfFile = new File(rtpAppDir, "sf.sf2");
            if (sfFile.exists() && sfFile.length() > 0) return;
            copyAssetToFile(context, "rtp/sf.sf2", sfFile);
        } catch (Throwable t) {
            Log.w(TAG, "ensureRtpEnvironment failed (non-fatal)", t);
        }
    }

    /** 把 assets 中的资源文件复制到目标 File，覆盖已存在的空文件或损坏文件。 */
    private static void copyAssetToFile(Context context, String assetPath, File dest) {
        AssetManager am = context.getAssets();
        try (InputStream in = am.open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "copy asset " + assetPath + " → " + dest + " failed (non-fatal)", t);
        }
    }

    /**
     * 在游戏目录或 /sdcard/JoiPlay/games/<gameId>/ 下创建 configuration.json。
     *
     * <p>插件的 loadConfig() 会查找 configuration.json：
     * <ul>
     *   <li>如果 game.folder 以外部存储路径开头 → game.folder + "/configuration.json"</li>
     *   <li>否则 → externalStorage + "/JoiPlay/games/" + game.id + "/configuration.json"</li>
     * </ul>
     * loadFromFile() 使用扁平 JSON 格式（{"useRuby18": true}），与 parse(String) 的嵌套格式不同。
     * 如果文件已存在（由 JoiPlay 创建），不覆盖，尊重用户在 JoiPlay 中的设置。</p>
     */
    private static void ensureGameConfiguration(String gameFolder, String gameId, String gameType) {
        // 仅 rpgmxp 需要设置 useRuby18=true，其他子引擎不需要。
        if (!"rpgmxp".equals(gameType)) return;
        try {
            File externalRoot = Environment.getExternalStorageDirectory();
            String externalPath = externalRoot.getAbsolutePath();

            // 确定两个可能的 configuration.json 路径。
            File[] candidates;
            if (gameFolder != null && gameFolder.startsWith(externalPath)) {
                // 游戏目录在外部存储 → configuration.json 放在游戏目录里。
                File f = new File(gameFolder, "configuration.json");
                // 同时也准备 JoiPlay/games/<id>/ 路径作为备选。
                File alt = new File(externalRoot,
                        "JoiPlay" + File.separator + "games" + File.separator + gameId + File.separator + "configuration.json");
                candidates = new File[]{f, alt};
            } else {
                // 游戏目录不在外部存储（SAF URI 等）→ 只能用 JoiPlay/games/<id>/ 路径。
                File f = new File(externalRoot,
                        "JoiPlay" + File.separator + "games" + File.separator + gameId + File.separator + "configuration.json");
                candidates = new File[]{f};
            }

            // 扁平 JSON 格式：loadFromFile() 用 getBoolean("useRuby18") 直接读取。
            String configJson = "{\"useRuby18\":true}";

            for (File configFile : candidates) {
                if (configFile.exists()) {
                    // 文件已存在（可能由 JoiPlay 创建），不覆盖。
                    Log.d(TAG, "configuration.json already exists at " + configFile.getAbsolutePath() + ", skipping");
                    continue;
                }
                File parent = configFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (java.io.FileWriter w = new java.io.FileWriter(configFile)) {
                    w.write(configJson);
                    w.flush();
                    Log.d(TAG, "created configuration.json at " + configFile.getAbsolutePath());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureGameConfiguration failed (non-fatal)", t);
        }
    }

    /** 检查 RPG Maker Plugin 是否已安装。 */
    public static boolean isRpgMakerPluginInstalled(Context context) {
        if (context == null) return false;
        try {
            context.getPackageManager().getPackageInfo(PLUGIN_PACKAGE, PackageManager.GET_ACTIVITIES);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private static String resolveGameType(LaunchRequest request) {
        String pkg = request.packageName == null ? "" : request.packageName.trim().toLowerCase(Locale.ROOT);
        if (pkg.equals(ALIAS_PREFIX + "mxp")) return "rpgmxp";
        if (pkg.equals(ALIAS_PREFIX + "mvx")) return "rpgmvx";
        if (pkg.equals(ALIAS_PREFIX + "mvxace")) return "rpgmvxace";
        if (pkg.equals(ALIAS_MKXPZ_DASH) || pkg.equals(ALIAS_MKXPZ_NODASH)) return "mkxp-z";
        // ALIAS_AUTO 或真实包名：根据扫描结果推断。
        return inferGameTypeFromRequest(request);
    }

    /** 当用户选 AUTO 时，依据 launchTarget 后缀或扫描特征推断子引擎。 */
    private static String inferGameTypeFromRequest(LaunchRequest request) {
        String target = request.launchTarget == null ? "" : request.launchTarget.trim().toLowerCase(Locale.ROOT);
        if (target.endsWith(".rgssad")) return "rpgmxp";
        if (target.endsWith(".rgss2a")) return "rpgmvx";
        if (target.endsWith(".rgss3a")) return "rpgmvxace";
        // 默认走 RPGXP（Ruby 1.8）：未识别归档的老游戏多为 RPGXP，
        // mkxp-z/Ruby3.x 对老 RGSS1 语法（如 ?(...) 三元运算符）兼容性最差。
        // buildLaunchIntent 会在 rpgmxp 时自动传 useRuby18=true 加载 libmkxp18.so。
        return "rpgmxp";
    }

    private static String resolveGameFolder(Context context, LaunchRequest request) {
        String rootUri = request.rootUri;
        if (rootUri == null || rootUri.isEmpty()) return null;
        String path = uriToFilePath(context, rootUri);
        if (path == null || path.trim().isEmpty()) return null;
        // 如果用户选了某个归档文件作为 launchTarget，则把 folder 落到它的父目录上。
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
            if (!target.toLowerCase(Locale.ROOT).endsWith(".rgssad")
                    && !target.toLowerCase(Locale.ROOT).endsWith(".rgss2a")
                    && !target.toLowerCase(Locale.ROOT).endsWith(".rgss3a")) {
                // 不是归档文件，按目录处理。
                return path.endsWith("/") ? path + target : path + "/" + target;
            }
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
        return "RPG Maker Game";
    }

    private static String deriveGameId(String folder, String title) {
        try {
            String raw = folder == null ? title : folder;
            return Integer.toHexString(raw == null ? title.hashCode() : raw.hashCode());
        } catch (Throwable ignored) {
            return "yuki" + System.currentTimeMillis();
        }
    }

    private static Intent buildLaunchIntent(String gameType, String title, String gameId,
                                             String folder, LaunchRequest request) {
        String action = actionForGameType(gameType);
        Intent intent = new Intent(action);
        intent.setPackage(PLUGIN_PACKAGE);

        JSONObject game = new JSONObject();
        try {
            game.put("title", title);
            game.put("id", gameId);
            game.put("folder", folder);
            game.put("execFile", "");
            game.put("type", gameType);
        } catch (Throwable ignored) { }
        intent.putExtra("game", game.toString());

        // settings 里的 useRuby18 字段决定 rpgmxp/rpgmvx 加载哪个 .so：
        //   useRuby18=true  → libmkxp18.so (Ruby 1.8，RGSS1 原生版本)
        //   useRuby18=false → libmkxp19.so (Ruby 1.9)
        // 不传时默认 false，导致 RPGXP 游戏被 Ruby 1.9 解析，老语法（如 ?(...) 三元运算符）会报 SyntaxError。
        // 因此 RPGXP 必须显式传 useRuby18=true，让插件加载 libmkxp18.so。
        // rpgmvxace 和 mkxp-z 不受 useRuby18 影响（rpgmvxace→mkxp19，mkxp-z→mkxp30）。
        //
        // 重要：MKXPConfigurationParser.parse(String) 期望嵌套 JSON 格式，不是扁平的
        // {"useRuby18": true}，而是 {"rpg": {"useRuby18": {"boolean": true}}}}。
        // 每个配置值都包裹在 {"boolean": value} 或 {"int": value} 类型对象中。
        // 传错格式会抛 JSONException，useRuby18 保持默认 false。
        JSONObject settings = new JSONObject();
        try {
            if ("rpgmxp".equals(gameType)) {
                JSONObject rpgSection = new JSONObject();
                JSONObject useRuby18Val = new JSONObject();
                useRuby18Val.put("boolean", true);
                rpgSection.put("useRuby18", useRuby18Val);
                settings.put("rpg", rpgSection);
            }
        } catch (Throwable ignored) { }
        intent.putExtra("settings", settings.toString());

        // 6 = sensorLandscape，与 YukiHub 内置引擎 Activity 的 orientation 保持一致。
        intent.putExtra("orientation", 6);

        // 透传 rootUri 与 launchTarget，方便插件 LogActivity 与 YukiHub 联调定位。
        if (request.rootUri != null) intent.putExtra("rootUri", request.rootUri);
        if (request.launchTarget != null) intent.putExtra("launchTarget", request.launchTarget);
        return intent;
    }

    private static String actionForGameType(String gameType) {
        switch (gameType) {
            case "rpgmxp": return "cyou.joiplay.runtime.rpgmxp.run";
            case "rpgmvx": return "cyou.joiplay.runtime.rpgmvx.run";
            case "mkxp-z": return "cyou.joiplay.runtime.mkxp-z.run";
            case "rpgmvxace":
            default: return "cyou.joiplay.runtime.rpgmvxace.run";
        }
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
            // tree URI 无法解析为文件路径时，尝试复制到 internalFolder 再传过去。
            return copyTreeToLocalPath(context, uri);
        } catch (Throwable ignored) {
            return uriText;
        }
    }

    /**
     * 极端兜底：对某些厂商 ROM 的 tree URI 无法解出 primary 路径时，
     * 通过 DocumentFile 列出根目录内的文件名，但 mkxp 需要的是真实目录路径，
     * 这里只返回 null 让上层报错而不传错误路径给插件。
     */
    private static String copyTreeToLocalPath(Context context, Uri treeUri) {
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(context, treeUri);
            if (dir == null || !dir.isDirectory()) return null;
            // 仅探测是否能取到本地路径，不做实际拷贝以避免大文件复制副作用。
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
