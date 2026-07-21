package com.yuki.yukihub.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yuki.yukihub.model.EngineType
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 通过外部安装的 JoiPlay Ren'Py 插件启动 Ren'Py 游戏。
 *
 * 插件包名：[PLUGIN_PACKAGE]
 * 插件接收 `cyou.joiplay.runtime.renpy.run` intent action。
 *
 * Game JSON 字段：`title, id, folder, execFile, type`（type 固定为 `"renpy"`）。
 * Settings 使用嵌套 JSON 格式（如 `{"app":{"cheats":{"boolean":false}}}`），
 * 但可传空 `{}` 让插件使用默认配置。
 *
 * 与 RPG Maker 不同：Ren'Py 没有 RTP 路径要求，也无需 configuration.json。
 * 虽然插件 APK 内有 loadConfig()，但 Ren'Py 不需要特殊配置文件。
 */
class ExternalRenPyPluginStrategy : EngineLaunchStrategy {

    override fun getEngineType(): EngineType = EngineType.RENPY

    override fun supports(request: LaunchRequest): Boolean {
        val pkg = request.packageName.trim().lowercase(Locale.ROOT)
        if (pkg.isEmpty()) return false
        if (PLUGIN_PACKAGE.equals(pkg, ignoreCase = true)) return true
        return pkg == ALIAS_RENPY || pkg == ALIAS_RENPY8
    }

    override fun launch(context: Context, request: LaunchRequest): Boolean {
        if (!isRenPyPluginInstalled(context)) {
            Log.w(TAG, "Ren'Py Plugin ($PLUGIN_PACKAGE) is not installed")
            return false
        }
        val folder = resolveGameFolder(context, request)
        if (folder.isNullOrEmpty()) {
            Log.w(TAG, "cannot resolve game folder from rootUri=" + request.rootUri)
            return false
        }
        val title = resolveTitle(request, folder)
        val gameId = deriveGameId(folder, title)

        val intent = buildLaunchIntent(title, gameId, folder, request)
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
        private const val TAG = "RenPyStrategy"

        /** Ren'Py Plugin 的真实包名（来自逆向 smali）。 */
        const val PLUGIN_PACKAGE = "cyou.joiplay.runtime.renpy.v8d4d1"

        /** Ren'Py 插件接收的 intent action。 */
        private const val ACTION_RUN = "cyou.joiplay.runtime.renpy.run"

        /** YukiHub 内部使用的别名——与 InternalKrkrStrategy 的命名风格保持一致。 */
        private const val ALIAS_RENPY = "internal.renpy"
        private const val ALIAS_RENPY8 = "internal.renpy8"

        /** 检查 Ren'Py Plugin 是否已安装。 */
        @JvmStatic
        fun isRenPyPluginInstalled(context: Context?): Boolean {
            if (context == null) return false
            return try {
                context.packageManager.getPackageInfo(PLUGIN_PACKAGE, PackageManager.GET_ACTIVITIES)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

        private fun resolveGameFolder(context: Context, request: LaunchRequest): String? {
            val rootUri = request.rootUri
            if (rootUri.isNullOrEmpty()) return null
            val path = uriToFilePath(context, rootUri)
            if (path.isNullOrBlank()) return null
            // 如果用户选了某个具体文件作为 launchTarget，则把 folder 落到它的父目录上。
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
                // Ren'Py 游戏通常以 .py 或 game/ 目录标识，不针对特定归档后缀做处理。
                return if (path.endsWith("/")) path + target else "$path/$target"
            }
            return path
        }

        private fun resolveTitle(request: LaunchRequest, folder: String?): String {
            val target = request.launchTarget?.trim() ?: ""
            if (target.isNotEmpty() && target != "[游戏目录]" && !"DIR".equals(target, ignoreCase = true)) {
                return target
            }
            if (!folder.isNullOrEmpty()) {
                val slash = folder.lastIndexOf('/')
                if (slash >= 0 && slash + 1 < folder.length) return folder.substring(slash + 1)
                return folder
            }
            return "Ren'Py Game"
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
            folder: String, request: LaunchRequest
        ): Intent {
            val intent = Intent(ACTION_RUN)
            intent.setPackage(PLUGIN_PACKAGE)

            val game = JSONObject()
            try {
                game.put("title", title)
                game.put("id", gameId)
                game.put("folder", folder)
                game.put("execFile", "")
                game.put("type", "renpy")
            } catch (_: Throwable) { }
            intent.putExtra("game", game.toString())

            // settings 使用嵌套 JSON 格式（如 {"app":{"cheats":{"boolean":false}},
            // "renpy":{"renpy_hw_video":{"boolean":true}}}），但可以传空 {} 让插件使用默认配置。
            intent.putExtra("settings", "{}")

            // 6 = sensorLandscape，与 YukiHub 内置引擎 Activity 的 orientation 保持一致。
            intent.putExtra("orientation", 6)

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
                // tree URI 无法解析为文件路径时，返回 null 让上层报错而不传错误路径给插件。
                copyTreeToLocalPath(context, uri)
            } catch (_: Throwable) {
                uriText
            }
        }

        /**
         * 极端兜底：对某些厂商 ROM 的 tree URI 无法解出 primary 路径时，
         * 仅探测是否能取到本地路径，不做实际拷贝以避免大文件复制副作用。
         */
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
