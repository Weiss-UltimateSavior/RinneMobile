package com.yuki.yukihub.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.yuki.yukihub.model.EngineType
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale

/**
 * 通过外部安装的 JoiPlay Godot 插件启动 Godot 游戏。
 *
 * 支持 Godot 3 和 Godot 4 两个版本的插件：
 * - Godot 3 插件包名：[PLUGIN_PACKAGE_GODOT3]，action `cyou.joiplay.runtime.godot3.run`
 * - Godot 4 插件包名：[PLUGIN_PACKAGE_GODOT4]，action `cyou.joiplay.runtime.godot4.run`
 *
 * 版本自动检测：JoiPlay 主程序通过读取 .pck 文件头的第 9 字节（offset 8）判断 Godot 主版本号。
 * .pck 文件格式：
 * ```
 *   偏移 0-3: "GDPC" (magic)
 *   偏移 4-7: pack version (int32)
 *   偏移 8:   Godot major version (3 = Godot 3, 4 = Godot 4)
 * ```
 * YukiHub 复刻此逻辑：扫描游戏目录下的 .pck 文件，读取版本字节，
 * 自动选择对应的 Godot 插件（3 或 4）和 action。
 *
 * 如果没有 .pck 文件但存在 project.godot（源码项目），
 * 返回错误提示用户需要先在 Godot 编辑器中导出 .pck 文件。
 *
 * Game JSON 字段：`title, id, folder, execFile, type`，
 * 其中 type 设为 `"godot3"` 或 `"godot4"`。
 * Settings 使用 GamePad JSON 格式，传空 `{}` 使用默认配置。
 *
 * GodotApp.onCreate 读取 intent extras `"game"` 和 `"settings"`，
 * 然后 `genCommandLine()` 自动扫描游戏目录下的 .pck 文件，
 * 生成 `--path <folder> --main-pack <.pck> --fullscreen --immersive --xr-mode off` 命令行。
 */
class ExternalGodotPluginStrategy : EngineLaunchStrategy {

    override fun getEngineType(): EngineType = EngineType.GODOT

    override fun supports(request: LaunchRequest): Boolean {
        val pkg = request.packageName.trim().lowercase(Locale.ROOT)
        if (pkg.isEmpty()) return false
        if (PLUGIN_PACKAGE_GODOT4.equals(pkg, ignoreCase = true)) return true
        if (PLUGIN_PACKAGE_GODOT3.equals(pkg, ignoreCase = true)) return true
        return pkg == ALIAS_GODOT || pkg == ALIAS_GODOT3 || pkg == ALIAS_GODOT4
    }

    override fun launch(context: Context, request: LaunchRequest): Boolean {
        Log.i(TAG, "launch: rootUri=" + request.rootUri + " launchTarget=" + request.launchTarget)
        val folder = resolveGameFolder(context, request)
        Log.i(TAG, "launch: resolved folder=$folder")
        if (folder.isNullOrEmpty()) {
            Log.w(TAG, "cannot resolve game folder from rootUri=" + request.rootUri)
            return false
        }

        val folderFile = File(folder)
        if (!folderFile.exists() || !folderFile.isDirectory) {
            Log.w(TAG, "folder is not a valid directory: $folder" +
                " exists=" + folderFile.exists() +
                " isDir=" + (folderFile.exists() && folderFile.isDirectory))
            return false
        }

        // 扫描 .pck 文件并检测 Godot 版本
        val pckFile = findPckFile(folderFile)
        val godotType: String
        if (pckFile != null) {
            godotType = detectGodotVersion(pckFile)
            Log.i(TAG, "found .pck file: " + pckFile.name + " godotType=$godotType")
        } else {
            // 没有 .pck 文件，检查是否有 project.godot（源码项目）
            if (File(folderFile, "project.godot").exists()) {
                Log.e(TAG, "ERROR: project.godot found but no .pck file. " +
                    "Godot Android runtime requires a .pck file (exported from Godot Editor). " +
                    "Source projects (project.godot only) cannot run on Android.")
            } else {
                Log.w(TAG, "no .pck file and no project.godot found in folder: $folder")
            }
            // 仍然尝试启动（让插件给出更具体的错误信息）
            godotType = "godot4"
        }

        // 根据版本选择插件包名
        val pluginPackage: String
        val action: String
        if ("godot3" == godotType) {
            pluginPackage = PLUGIN_PACKAGE_GODOT3
            action = "cyou.joiplay.runtime.godot3.run"
        } else {
            pluginPackage = PLUGIN_PACKAGE_GODOT4
            action = "cyou.joiplay.runtime.godot4.run"
        }

        // 检查对应版本的插件是否已安装
        // 注意：不能 fallback 到另一版本——Godot 3 与 Godot 4 的 .pck 格式不兼容，
        // 用错版本插件会导致 "Unable to setup the Godot engine" 崩溃。
        if (!isPluginInstalled(context, pluginPackage)) {
            val majorVersion = godotType.replace("godot", "")
            val msg = "游戏是 Godot $majorVersion 项目，但未安装 Godot $majorVersion" +
                " 插件。Godot 3 与 Godot 4 的 .pck 格式不兼容，请安装对应版本插件。"
            Log.e(TAG, "ERROR: $msg (pluginPackage=$pluginPackage)")
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            return false
        }

        val title = resolveTitle(request, folder)
        val gameId = deriveGameId(folder, title)

        val intent = buildLaunchIntent(title, gameId, folder, godotType, action, pluginPackage, request)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                or Intent.FLAG_GRANT_READ_URI_PERMISSION
                or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "startActivity failed action=" + intent.action + " folder=$folder", t)
            false
        }
    }

    companion object {
        private const val TAG = "GodotStrategy"

        /** Godot 4 Plugin 的真实包名（来自逆向 smali）。 */
        const val PLUGIN_PACKAGE_GODOT4 = "cyou.joiplay.runtime.godot4"

        /** Godot 3 Plugin 的真实包名（按 JoiPlay 命名规律推断）。 */
        const val PLUGIN_PACKAGE_GODOT3 = "cyou.joiplay.runtime.godot3"

        /** YukiHub 内部使用的别名。 */
        private const val ALIAS_GODOT = "internal.godot"
        private const val ALIAS_GODOT3 = "internal.godot3"
        private const val ALIAS_GODOT4 = "internal.godot4"

        /** .pck 文件 magic header："GDPC"（4 字节）。 */
        private val PCK_MAGIC = byteArrayOf(0x47, 0x44, 0x50, 0x43) // 'G','D','P','C'

        /**
         * 扫描目录下的 .pck 文件，返回第一个找到的 .pck 文件。
         * 与 GodotApp.genCommandLine() 的扫描逻辑一致。
         */
        private fun findPckFile(folder: File?): File? {
            if (folder == null || !folder.isDirectory) return null
            val files = folder.listFiles() ?: return null
            for (f in files) {
                if (f.isFile && f.name.lowercase(Locale.ROOT).endsWith(".pck")) {
                    return f
                }
            }
            return null
        }

        /**
         * 读取 .pck 文件头的第 9 字节（offset 8）判断 Godot 主版本号。
         * .pck 格式：[0-3]="GDPC" [4-7]=pack_version [8]=godot_major_version
         *
         * @return "godot3" 或 "godot4"；读取失败时默认 "godot4"
         */
        private fun detectGodotVersion(pckFile: File?): String {
            if (pckFile == null || !pckFile.exists()) return "godot4"
            var fis: FileInputStream? = null
            return try {
                fis = FileInputStream(pckFile)
                val header = ByteArray(9)
                val read = fis.read(header)
                if (read < 9) {
                    Log.w(TAG, "pck file too small: " + pckFile.name + " read=$read")
                    return "godot4"
                }
                // 验证 magic "GDPC"
                for (i in 0..3) {
                    if (header[i] != PCK_MAGIC[i]) {
                        Log.w(TAG, "pck file magic mismatch: " + pckFile.name +
                            " expected GDPC but got 0x" +
                            String.format(Locale.ROOT, "%02x%02x%02x%02x",
                                header[0], header[1], header[2], header[3]))
                        return "godot4"
                    }
                }
                // 读取版本字节（offset 8）
                val versionByte = header[8].toInt() and 0xFF
                Log.i(TAG, "pck header: magic=GDPC pack_version=" +
                    ((header[4].toInt() and 0xFF) or ((header[5].toInt() and 0xFF) shl 8)
                        or ((header[6].toInt() and 0xFF) shl 16) or ((header[7].toInt() and 0xFF) shl 24)) +
                    " godot_major=$versionByte")
                if (versionByte == 3) return "godot3"
                if (versionByte == 4) return "godot4"
                Log.w(TAG, "unknown godot major version: $versionByte (defaulting to godot4)")
                "godot4"
            } catch (e: IOException) {
                Log.w(TAG, "failed to read pck header: " + pckFile.name, e)
                "godot4"
            } finally {
                try { fis?.close() } catch (_: IOException) { }
            }
        }

        /** 检查指定 Godot 插件是否已安装。 */
        @JvmStatic
        fun isPluginInstalled(context: Context?, packageName: String?): Boolean {
            if (context == null || packageName == null) return false
            return try {
                context.packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

        /** 检查 Godot 4 插件是否已安装（向后兼容）。 */
        @JvmStatic
        fun isGodotPluginInstalled(context: Context?): Boolean {
            return isPluginInstalled(context, PLUGIN_PACKAGE_GODOT4)
        }

        private fun resolveGameFolder(context: Context, request: LaunchRequest): String? {
            val rootUri = request.rootUri
            if (rootUri.isNullOrEmpty()) return null
            val path = uriToFilePath(context, rootUri)
            if (path.isNullOrBlank()) return null
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
                return if (path.endsWith("/")) path + target else "$path/$target"
            }
            return path
        }

        private fun resolveTitle(request: LaunchRequest, folder: String?): String {
            val target = request.launchTarget?.trim() ?: ""
            if (target.isNotEmpty() && target != "[游戏目录]" && !"DIR".equals(target, ignoreCase = true)) {
                var name = target
                val dot = name.lastIndexOf('.')
                if (dot > 0) name = name.substring(0, dot)
                return name
            }
            if (!folder.isNullOrEmpty()) {
                val slash = folder.lastIndexOf('/')
                if (slash >= 0 && slash + 1 < folder.length) return folder.substring(slash + 1)
                return folder
            }
            return "Godot Game"
        }

        private fun deriveGameId(folder: String?, title: String): String {
            return try {
                val raw = folder ?: title
                Integer.toHexString(raw.hashCode())
            } catch (_: Throwable) {
                "yuki" + System.currentTimeMillis()
            }
        }

        private fun buildLaunchIntent(
            title: String, gameId: String,
            folder: String, godotType: String,
            action: String, pluginPackage: String,
            request: LaunchRequest
        ): Intent {
            val intent = Intent(action)
            intent.setPackage(pluginPackage)

            val game = JSONObject()
            try {
                game.put("title", title)
                game.put("id", gameId)
                game.put("folder", folder)
                game.put("execFile", "")
                game.put("type", godotType)
            } catch (_: Throwable) { }
            intent.putExtra("game", game.toString())
            Log.i(TAG, "buildLaunchIntent: action=$action pkg=$pluginPackage game json=$game")

            // settings 是 GamePad 的 JSON，传空让插件使用默认 GamePad 配置。
            intent.putExtra("settings", "{}")

            // 透传 rootUri 与 launchTarget，方便插件与 YukiHub 联调定位。
            if (request.rootUri != null) intent.putExtra("rootUri", request.rootUri)
            if (request.launchTarget != null) intent.putExtra("launchTarget", request.launchTarget)
            return intent
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
                if ("file".equals(scheme, ignoreCase = true)) return uri.path
                if (!"content".equals(scheme, ignoreCase = true)) return uriText
                var docId: String? = null
                val path = uri.path
                val hasDocumentPart = path != null && path.contains("/document/")
                if (hasDocumentPart) {
                    try { docId = DocumentsContract.getDocumentId(uri) } catch (_: Throwable) { }
                }
                if (docId.isNullOrEmpty()) {
                    try { docId = DocumentsContract.getTreeDocumentId(uri) } catch (_: Throwable) { }
                }
                if (docId.isNullOrEmpty()) {
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
                copyTreeToLocalPath(context, uri)
            } catch (_: Throwable) {
                uriText
            }
        }

        private fun copyTreeToLocalPath(context: Context, treeUri: Uri): String? {
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
