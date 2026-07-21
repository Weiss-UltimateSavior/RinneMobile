package com.yuki.yukihub.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yuki.yukihub.model.EngineType
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * 通过隐式 Intent 调用已安装的 RPG Maker Plugin (cyou.joiplay.runtime.rpgmaker)
 * 来运行 RPG Maker XP/VX/VX Ace/mkxp-z 游戏。
 *
 * 插件 APK 暴露了 4 个 `exported=true` 的 intent-filter 入口，
 * 由 PermissionActivity 接收，统一处理存储权限申请后启动 mkxp 主 Activity：
 * - `cyou.joiplay.runtime.rpgmxp.run`    → libmkxp18.so + Ruby 1.8 (RGSS1)
 * - `cyou.joiplay.runtime.rpgmvx.run`    → libmkxp19.so + Ruby 1.9 (RGSS2)
 * - `cyou.joiplay.runtime.rpgmvxace.run` → libmkxp30.so + Ruby 3.x (RGSS3)
 * - `cyou.joiplay.runtime.mkxp-z.run`    → libmkxp30.so + Ruby 3.x (mkxp-z 自定义)
 *
 * PermissionActivity 仅解析 `game` 这个 JSON extra，字段：
 * `title, id, folder, execFile, type`（参见 GameParser.parse）。
 * 其余字段如 useRuby18/archived 等仅由 MainActivity 在运行时读取，
 * PermissionActivity 接受省略。settings extra 可省略，省略时插件使用默认配置。
 */
class ExternalRpgMakerPluginStrategy : EngineLaunchStrategy {

    override fun getEngineType(): EngineType = EngineType.RPGMAKER

    override fun supports(request: LaunchRequest): Boolean {
        val pkg = request.packageName.trim().lowercase(Locale.ROOT)
        if (pkg.isEmpty()) return false
        if (PLUGIN_PACKAGE == pkg) return true
        return pkg == ALIAS_AUTO
            || pkg == ALIAS_PREFIX + "mxp"
            || pkg == ALIAS_PREFIX + "mvx"
            || pkg == ALIAS_PREFIX + "mvxace"
            || pkg == ALIAS_MKXPZ_DASH
            || pkg == ALIAS_MKXPZ_NODASH
    }

    override fun launch(context: Context, request: LaunchRequest): Boolean {
        Log.d(TAG, "launch: rootUri=${request.rootUri} launchTarget=${request.launchTarget}")
        if (!isRpgMakerPluginInstalled(context)) {
            Log.w(TAG, "RPG Maker Plugin ($PLUGIN_PACKAGE) is not installed")
            return false
        }
        val gameType = resolveGameType(request)
        val folder = resolveGameFolder(context, request)
        if (folder.isNullOrEmpty()) {
            Log.w(TAG, "cannot resolve game folder from rootUri=" + request.rootUri)
            return false
        }
        // 关键修复：RPGMPlugin 在 MainActivity 中会无条件把
        //   /sdcard/JoiPlay/RTP/<engineName>/app
        // 加入 mkxp 的 rtps[] 并以 fatalError=true 调用 PHYSFS_mount。
        // 该路径不存在时 mkxp 会抛 Exception 导致游戏闪退（错误：Failed to mount ... (notfound)）。
        // JoiPlay 主程序在首次运行时会下载 RTP 到此处，但 YukiHub 不做这事，
        // 所以必须由 YukiHub 主动创建该目录并放置一个 sf.sf2 占位 SoundFont。
        ensureRtpEnvironment(context, gameType)

        val title = resolveTitle(request, folder)
        val gameId = deriveGameId(folder, title)

        // 双重保险：在游戏目录创建 configuration.json（扁平 JSON 格式）。
        // 插件的 loadConfig() → MKXPConfigurationParser.loadFromFile() 读取此文件，
        // 格式是扁平的 {"useRuby18": true}（与 parse(String) 的嵌套格式不同）。
        // 如果文件已存在（由 JoiPlay 创建），不覆盖。
        ensureGameConfiguration(folder, gameId, gameType)

        val intent = buildLaunchIntent(gameType, title, gameId, folder, request)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                or Intent.FLAG_GRANT_READ_URI_PERMISSION
                or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "startActivity failed action=${intent.action} folder=$folder", t)
            false
        }
    }

    companion object {
        private const val TAG = "RpgMakerStrategy"

        /** RPG Maker Plugin 的真实包名。 */
        const val PLUGIN_PACKAGE = "cyou.joiplay.runtime.rpgmaker"

        /** YukiHub 内部使用的别名前缀；与 InternalKrkrStrategy 的命名风格保持一致。 */
        private const val ALIAS_PREFIX = "internal.rpg"

        /** 自动识别别名——具体引擎由 EngineDetector 决定。 */
        private const val ALIAS_AUTO = "internal.rpgmaker"

        /** mkxp-z 的别名（允许 dash 和无 dash 两种写法）。 */
        private const val ALIAS_MKXPZ_DASH = "internal.mkxp-z"
        private const val ALIAS_MKXPZ_NODASH = "internal.mkxpz"

        /**
         * RPGMPlugin 期望的 RTP 引擎目录名映射（来自 MainActivity.smali 的 switch 表）。
         * mkxp-z 在 RPGMPlugin 中没有特殊前缀，路径直接是 "mkxp-z"。
         */
        private fun rtpDirNameForGameType(gameType: String): String = when (gameType) {
            "rpgmxp" -> "RPGXP"
            "rpgmvx" -> "RPGVX"
            "mkxp-z" -> "mkxp-z"
            else -> "RPGVXACE" // rpgmvxace 与未识别默认都走 RPGVXACE
        }

        /**
         * 在 /sdcard/JoiPlay/RTP/<engineName>/app/ 下创建空目录并放入 sf.sf2，
         * 避免 mkxp 因 PHYSFS_mount 失败抛 Exception 退出。
         *
         * 注意：调用方必须已持有 MANAGE_EXTERNAL_STORAGE 或 WRITE_EXTERNAL_STORAGE 权限。
         * RPGMPlugin 的 PermissionActivity 会替 YukiHub 申请并授予该权限，但本策略在
         * startActivity 之前调用——此时权限可能尚未授予。因此本方法捕获所有异常并仅打印日志，
         * 不阻断启动流程；若 mkdirs 失败，RPGMPlugin 仍会被启动，由它自己的权限流程兜底。
         * 极端情况下用户可在文件管理器手动创建一次该目录即可永久解决。
         */
        private fun ensureRtpEnvironment(context: Context, gameType: String) {
            try {
                val engineName = rtpDirNameForGameType(gameType)
                val externalRoot = Environment.getExternalStorageDirectory()
                val rtpAppDir = File(
                    externalRoot,
                    "JoiPlay" + File.separator + "RTP" + File.separator + engineName + File.separator + "app"
                )
                if (!rtpAppDir.exists() && !rtpAppDir.mkdirs()) {
                    Log.w(
                        TAG,
                        "mkdirs failed for RTP dir: " + rtpAppDir.absolutePath +
                            "（多半缺少 MANAGE_EXTERNAL_STORAGE 权限，RPGMPlugin 后续会申请）"
                    )
                    return
                }
                // SoundFont：mkxp 把 midi_soundFont 设为 <rtpAppDir>/sf.sf2，若缺失 MIDI BGM 无法播放，
                // 但不会 fatal 退出。仍尝试从 assets 复制，让 MIDI 可用。
                val sfFile = File(rtpAppDir, "sf.sf2")
                if (sfFile.exists() && sfFile.length() > 0) return
                copyAssetToFile(context, "rtp/sf.sf2", sfFile)
            } catch (t: Throwable) {
                Log.w(TAG, "ensureRtpEnvironment failed (non-fatal)", t)
            }
        }

        /** 把 assets 中的资源文件复制到目标 File，覆盖已存在的空文件或损坏文件。 */
        private fun copyAssetToFile(context: Context, assetPath: String, dest: File) {
            try {
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "copy asset $assetPath → $dest failed (non-fatal)", t)
            }
        }

        /**
         * 在游戏目录或 /sdcard/JoiPlay/games/<gameId>/ 下创建 configuration.json。
         *
         * 插件的 loadConfig() 会查找 configuration.json：
         * - 如果 game.folder 以外部存储路径开头 → game.folder + "/configuration.json"
         * - 否则 → externalStorage + "/JoiPlay/games/" + game.id + "/configuration.json"
         *
         * loadFromFile() 使用扁平 JSON 格式（{"useRuby18": true}），与 parse(String) 的嵌套格式不同。
         * 如果文件已存在（由 JoiPlay 创建），不覆盖，尊重用户在 JoiPlay 中的设置。
         */
        private fun ensureGameConfiguration(gameFolder: String, gameId: String, gameType: String) {
            // 仅 rpgmxp 需要设置 useRuby18=true，其他子引擎不需要。
            if (gameType != "rpgmxp") return
            try {
                val externalRoot = Environment.getExternalStorageDirectory()
                val externalPath = externalRoot.absolutePath

                // 确定两个可能的 configuration.json 路径。
                val candidates: Array<File>
                if (gameFolder.startsWith(externalPath)) {
                    // 游戏目录在外部存储 → configuration.json 放在游戏目录里。
                    val f = File(gameFolder, "configuration.json")
                    // 同时也准备 JoiPlay/games/<id>/ 路径作为备选。
                    val alt = File(
                        externalRoot,
                        "JoiPlay" + File.separator + "games" + File.separator + gameId + File.separator + "configuration.json"
                    )
                    candidates = arrayOf(f, alt)
                } else {
                    // 游戏目录不在外部存储（SAF URI 等）→ 只能用 JoiPlay/games/<id>/ 路径。
                    val f = File(
                        externalRoot,
                        "JoiPlay" + File.separator + "games" + File.separator + gameId + File.separator + "configuration.json"
                    )
                    candidates = arrayOf(f)
                }

                // 扁平 JSON 格式：loadFromFile() 用 getBoolean("useRuby18") 直接读取。
                val configJson = "{\"useRuby18\":true}"

                for (configFile in candidates) {
                    if (configFile.exists()) {
                        // 文件已存在（可能由 JoiPlay 创建），不覆盖。
                        Log.d(TAG, "configuration.json already exists at " + configFile.absolutePath + ", skipping")
                        continue
                    }
                    val parent = configFile.parentFile
                    if (parent != null && !parent.exists()) parent.mkdirs()
                    configFile.writeText(configJson)
                    Log.d(TAG, "created configuration.json at " + configFile.absolutePath)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ensureGameConfiguration failed (non-fatal)", t)
            }
        }

        /** 检查 RPG Maker Plugin 是否已安装。 */
        @JvmStatic
        fun isRpgMakerPluginInstalled(context: Context?): Boolean {
            if (context == null) return false
            return try {
                context.packageManager.getPackageInfo(PLUGIN_PACKAGE, PackageManager.GET_ACTIVITIES)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

        private fun resolveGameType(request: LaunchRequest): String {
            val pkg = request.packageName.trim().lowercase(Locale.ROOT)
            if (pkg == ALIAS_PREFIX + "mxp") return "rpgmxp"
            if (pkg == ALIAS_PREFIX + "mvx") return "rpgmvx"
            if (pkg == ALIAS_PREFIX + "mvxace") return "rpgmvxace"
            if (pkg == ALIAS_MKXPZ_DASH || pkg == ALIAS_MKXPZ_NODASH) return "mkxp-z"
            // ALIAS_AUTO 或真实包名：根据扫描结果推断。
            return inferGameTypeFromRequest(request)
        }

        /** 当用户选 AUTO 时，依据 launchTarget 后缀或扫描特征推断子引擎。 */
        private fun inferGameTypeFromRequest(request: LaunchRequest): String {
            val target = request.launchTarget?.trim()?.lowercase(Locale.ROOT) ?: ""
            if (target.endsWith(".rgssad")) return "rpgmxp"
            if (target.endsWith(".rgss2a")) return "rpgmvx"
            if (target.endsWith(".rgss3a")) return "rpgmvxace"
            // 默认走 RPGXP（Ruby 1.8）：未识别归档的老游戏多为 RPGXP，
            // mkxp-z/Ruby3.x 对老 RGSS1 语法（如 ?(...) 三元运算符）兼容性最差。
            // buildLaunchIntent 会在 rpgmxp 时自动传 useRuby18=true 加载 libmkxp18.so。
            return "rpgmxp"
        }

        private fun resolveGameFolder(context: Context, request: LaunchRequest): String? {
            val rootUri = request.rootUri
            if (rootUri == null || rootUri.trim().isEmpty()) return null
            val path = uriToFilePath(context, rootUri)
            if (path == null || path.trim().isEmpty()) return null
            // 如果用户选了某个归档文件作为 launchTarget，则把 folder 落到它的父目录上。
            val target = request.launchTarget?.trim() ?: ""
            if (target.isNotEmpty()
                && !target.startsWith("/")
                && target != "[游戏目录]"
                && !"DIR".equals(target, ignoreCase = true)
            ) {
                val candidate = File(path, target)
                if (candidate.isFile) {
                    val parent = candidate.parentFile
                    if (parent != null) return parent.absolutePath
                } else if (candidate.isDirectory) {
                    return candidate.absolutePath
                }
                // SAF 路径下，listFiles 可能失败，但 path 自身已是目录时仍可用。
                val lowerTarget = target.lowercase(Locale.ROOT)
                if (!lowerTarget.endsWith(".rgssad")
                    && !lowerTarget.endsWith(".rgss2a")
                    && !lowerTarget.endsWith(".rgss3a")
                ) {
                    // 不是归档文件，按目录处理。
                    return if (path.endsWith("/")) "${path}${target}" else "$path/$target"
                }
            }
            return path
        }

        private fun resolveTitle(request: LaunchRequest, folder: String): String {
            val target = request.launchTarget?.trim() ?: ""
            if (target.isNotEmpty() && target != "[游戏目录]" && !"DIR".equals(target, ignoreCase = true)) {
                return target
            }
            val slash = folder.lastIndexOf('/')
            return if (slash >= 0 && slash + 1 < folder.length) folder.substring(slash + 1) else folder
        }

        private fun deriveGameId(folder: String, title: String): String {
            return try {
                val raw = folder.ifEmpty { title }
                Integer.toHexString(raw.hashCode())
            } catch (_: Throwable) {
                "yuki" + System.currentTimeMillis()
            }
        }

        private fun buildLaunchIntent(
            gameType: String, title: String, gameId: String,
            folder: String, request: LaunchRequest
        ): Intent {
            val action = actionForGameType(gameType)
            val intent = Intent(action)
            intent.setPackage(PLUGIN_PACKAGE)

            val game = JSONObject()
            try {
                game.put("title", title)
                game.put("id", gameId)
                game.put("folder", folder)
                game.put("execFile", "")
                game.put("type", gameType)
            } catch (_: Throwable) { }
            intent.putExtra("game", game.toString())

            // settings 里的 useRuby18 字段决定 rpgmxp/rpgmvx 加载哪个 .so：
            //   useRuby18=true  → libmkxp18.so (Ruby 1.8，RGSS1 原生版本)
            //   useRuby18=false → libmkxp19.so (Ruby 1.9)
            // 不传时默认 false，导致 RPGXP 游戏被 Ruby 1.9 解析，老语法（如 ?(...) 三元运算符）会报 SyntaxError。
            // 因此 RPGXP 必须显式传 useRuby18=true，让插件加载 libmkxp18.so。
            // rpgmvxace 和 mkxp-z 不受 useRuby18 影响（rpgmvxace→mkxp19，mkxp-z→mkxp30）。
            //
            // 重要：MKXPConfigurationParser.parse(String) 期望嵌套 JSON 格式，不是扁平的
            // {"useRuby18": true}，而是 {"rpg": {"useRuby18": {"boolean": true}}}。
            // 每个配置值都包裹在 {"boolean": value} 或 {"int": value} 类型对象中。
            // 传错格式会抛 JSONException，useRuby18 保持默认 false。
            val settings = JSONObject()
            try {
                if (gameType == "rpgmxp") {
                    val useRuby18Val = JSONObject()
                    useRuby18Val.put("boolean", true)
                    val rpgSection = JSONObject()
                    rpgSection.put("useRuby18", useRuby18Val)
                    settings.put("rpg", rpgSection)
                }
            } catch (_: Throwable) { }
            intent.putExtra("settings", settings.toString())

            // 6 = sensorLandscape，与 YukiHub 内置引擎 Activity 的 orientation 保持一致。
            intent.putExtra("orientation", 6)

            // 透传 rootUri 与 launchTarget，方便插件 LogActivity 与 YukiHub 联调定位。
            if (request.rootUri != null) intent.putExtra("rootUri", request.rootUri)
            if (request.launchTarget != null) intent.putExtra("launchTarget", request.launchTarget)
            return intent
        }

        private fun actionForGameType(gameType: String): String = when (gameType) {
            "rpgmxp" -> "cyou.joiplay.runtime.rpgmxp.run"
            "rpgmvx" -> "cyou.joiplay.runtime.rpgmvx.run"
            "mkxp-z" -> "cyou.joiplay.runtime.mkxp-z.run"
            else -> "cyou.joiplay.runtime.rpgmvxace.run" // rpgmvxace 与未识别默认都走 rpgmvxace
        }

        /**
         * 与 [EmulatorLauncher.uriToFilePath] 行为对齐的本地实现，
         * 仅支持 file:// / content(SAF) / 直接路径三种常见形式。
         */
        private fun uriToFilePath(context: Context, uriText: String?): String? {
            if (uriText == null || uriText.trim().isEmpty()) return uriText
            if (uriText.startsWith("/")) return uriText
            return try {
                val uri = Uri.parse(uriText)
                val scheme = uri.scheme ?: return uriText
                if ("file" == scheme) return uri.path
                if ("content" != scheme) return uriText
                var docId: String? = null
                val path = uri.path
                val hasDocumentPart = path != null && path.contains("/document/")
                if (hasDocumentPart) {
                    try { docId = DocumentsContract.getDocumentId(uri) } catch (_: Throwable) { }
                }
                if (docId == null || docId.trim().isEmpty()) {
                    try { docId = DocumentsContract.getTreeDocumentId(uri) } catch (_: Throwable) { }
                }
                if (docId == null || docId.trim().isEmpty()) {
                    try { docId = DocumentsContract.getDocumentId(uri) } catch (_: Throwable) { }
                }
                if (docId != null) {
                    val colon = docId.indexOf(':')
                    val volume = if (colon >= 0) docId.substring(0, colon) else docId
                    val rel = if (colon >= 0) docId.substring(colon + 1) else ""
                    if ("primary".equals(volume, ignoreCase = true)) {
                        return if (rel.isEmpty()) "/storage/emulated/0" else "/storage/emulated/0/$rel"
                    }
                    if (volume.isNotEmpty()) {
                        return if (rel.isEmpty()) "/storage/$volume" else "/storage/$volume/$rel"
                    }
                }
                // tree URI 无法解析为文件路径时，尝试复制到 internalFolder 再传过去。
                copyTreeToLocalPath(context, uri)
            } catch (_: Throwable) {
                uriText
            }
        }

        /**
         * 极端兜底：对某些厂商 ROM 的 tree URI 无法解出 primary 路径时，
         * 通过 DocumentFile 列出根目录内的文件名，但 mkxp 需要的是真实目录路径，
         * 这里只返回 null 让上层报错而不传错误路径给插件。
         */
        private fun copyTreeToLocalPath(context: Context, treeUri: Uri): String? {
            // 仅探测是否能取到本地路径，不做实际拷贝以避免大文件复制副作用。
            return try {
                val dir = DocumentFile.fromTreeUri(context, treeUri)
                if (dir == null || !dir.isDirectory) return null
                null
            } catch (_: Throwable) {
                null
            }
        }
    }
}
