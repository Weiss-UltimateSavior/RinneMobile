package com.yuki.yukihub.launcherbridge

import android.content.Context
import com.yuki.yukihub.util.AppExecutors
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern

/**
 * 启动器检查更新桥接层。
 * 复用主项目的检查更新逻辑（GitHub Release API），由 Launcher 直接调用，
 * 避免跳转到 MainActivity 即可完成版本检查并展示启动器风格弹窗。
 */
object LauncherUpdateBridge {

    private const val UPDATE_API_URL =
        "https://api.github.com/repos/Weiss-UltimateSavior/RinneMobile/releases/tags/test"
    private const val UPDATE_REPO_URL =
        "https://github.com/Weiss-UltimateSavior/RinneMobile"
    private const val MAX_RESPONSE_BYTES = 256L * 1024L

    @JvmStatic
    fun checkUpdate(context: Context?, callback: Callback?) {
        if (context == null) {
            callback?.onError("上下文不可用")
            return
        }
        AppExecutors.runOnIo {
            try {
                val current = getCurrentVersionName(context.applicationContext)
                val info = fetchLatestRelease(current)
                val newer = info != null && isNewerVersion(info.version, current)
                postToMain { callback?.onResult(info, current, newer) }
            } catch (t: Throwable) {
                postToMain {
                    val msg = if (t.message.isNullOrBlank()) "请稍后重试" else t.message!!
                    callback?.onError("检查更新失败：$msg")
                }
            }
        }
    }

    @Throws(Exception::class)
    private fun fetchLatestRelease(currentVersion: String): UpdateInfo {
        val text = LauncherBridgeHttp.request(
            url = UPDATE_API_URL, method = "GET", connectTimeoutMs = 12_000,
            maxResponseBytes = MAX_RESPONSE_BYTES, accept = "application/vnd.github+json",
            extraHeaders = mapOf("User-Agent" to "YukiHub-Android/$currentVersion")
        )
        val o = JSONObject(text)
        val info = UpdateInfo()
        info.tagName = o.optString("tag_name", "")
        info.version = normalizeVersion(info.tagName)
        info.name = o.optString("name", info.tagName)
        info.body = o.optString("body", "")
        info.releaseUrl = o.optString("html_url", "$UPDATE_REPO_URL/releases")
        val assets: JSONArray? = o.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val assetName = a.optString("name", "")
                val url = a.optString("browser_download_url", "")
                if (url.isBlank()) continue
                if (info.downloadUrl.isNullOrEmpty()) info.downloadUrl = url
                val lowerName = assetName.lowercase(Locale.ROOT)
                val lowerUrl = url.lowercase(Locale.ROOT)
                if (lowerName.endsWith(".apk") || lowerUrl.contains(".apk")) {
                    info.apkUrl = url
                    break
                }
            }
        }
        if (info.version.isNullOrEmpty()) info.version = normalizeVersion(info.name)
        if (info.downloadUrl.isNullOrEmpty()) info.downloadUrl = info.releaseUrl
        if (info.apkUrl.isNullOrEmpty()) info.apkUrl = info.releaseUrl
        return info
    }

    private fun getCurrentVersionName(context: Context): String {
        return try {
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            if (version.isNullOrBlank()) "0.0.0" else version.trim()
        } catch (_: Throwable) {
            "0.0.0"
        }
    }

    private fun isNewerVersion(latest: String?, current: String?): Boolean {
        val l = normalizeVersion(latest)
        val c = normalizeVersion(current)
        if (l.isEmpty() || c.isEmpty()) return l != c
        val la = l.split(".")
        val ca = c.split(".")
        val n = maxOf(la.size, ca.size)
        for (i in 0 until n) {
            val lv = if (i < la.size) parseVersionPart(la[i]) else 0L
            val cv = if (i < ca.size) parseVersionPart(ca[i]) else 0L
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }

    private fun parseVersionPart(part: String?): Long {
        return try {
            if (part == null) return 0L
            val digits = part.replace(Regex("[^0-9]"), "")
            if (digits.isEmpty()) 0L else digits.toLong()
        } catch (_: Throwable) {
            0L
        }
    }

    private fun normalizeVersion(value: String?): String {
        if (value == null) return ""
        var v = value.trim()
        val m = Pattern.compile("(\\d+(?:\\.\\d+){1,5})").matcher(v)
        if (m.find()) return m.group(1)!!
        v = v.replaceFirst(Regex("^[vV]"), "").replace(Regex("[^0-9.]"), "")
        while (v.startsWith(".")) v = v.substring(1)
        while (v.endsWith(".")) v = v.substring(0, v.length - 1)
        return v
    }

    interface Callback {
        fun onResult(info: UpdateInfo?, currentVersion: String, hasUpdate: Boolean)
        fun onError(message: String)
    }

    class UpdateInfo {
        @JvmField var tagName: String = ""
        @JvmField var version: String = ""
        @JvmField var name: String = ""
        @JvmField var body: String = ""
        @JvmField var releaseUrl: String = ""
        @JvmField var downloadUrl: String? = null
        @JvmField var apkUrl: String? = null
    }
}
