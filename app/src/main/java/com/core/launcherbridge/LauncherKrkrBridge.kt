package com.core.launcherbridge

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.core.CorePreferences
import com.core.launcher.EngineSaveKeys
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONObject

/**
 * KRKR 引擎设置桥接：负责读取/保存主项目 yukihub_prefs 中的 KRKR 引擎相关配置。
 * 涉及键值与 MainActivity / SyncManager 完全一致，保证 Launcher 修改后主项目立即可见。
 */
object LauncherKrkrBridge {

    private const val TAG = "LauncherKrkrBridge"

    /**
     * 单个字体文件拷贝上限 50MB（与 ImporterIO.MAX_ENTRY_BYTES 同口径）：合法字体
     * 远小于此值，限制用于防御误选大文件/非字体流在主线程外全量写盘导致磁盘膨胀。
     */
    private const val MAX_FONT_FILE_BYTES = 50L * 1024 * 1024

    /** 引擎 FreeType 实际支持的字形容器扩展名（大小写不敏感）。 */
    private val FONT_FILE_EXTENSIONS = arrayOf(".ttf", ".ttc", ".otf", ".otc")

    const val ENGINE_VERSION_AUTO = "auto"
    const val ENGINE_VERSION_139 = "1.3.9"
    const val ENGINE_VERSION_134 = "1.3.4"
    const val ENGINE_VERSION_126 = "1.2.6"

    /** KRKR 引擎内核常量。 */
    const val KERNEL_AUTO = "auto"
    const val KERNEL_KIRIKIRI2 = "kirikiri2"
    const val KERNEL_KRKRSDL3 = "krkrsdl3"

    // Keep this bridge independently compilable in every build variant.  Some release source
    // sets do not expose the launcher extension helpers, while this preference file is shared
    // by all launcher and engine components.
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(CorePreferences.APP_PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun getEngineVersion(context: Context?): String {
        if (context == null) return ENGINE_VERSION_AUTO
        val v = prefs(context).getString(CorePreferences.KEY_KR_ENGINE_VERSION, ENGINE_VERSION_AUTO)
        return normalizeEngineVersion(v)
    }

    @JvmStatic
    fun setEngineVersion(context: Context?, version: String?) {
        if (context == null) return
        prefs(context).edit()
            .putString(CorePreferences.KEY_KR_ENGINE_VERSION, normalizeEngineVersion(version))
            .apply()
    }

    @JvmStatic
    fun isKrScopedSaveDir(context: Context?): Boolean {
        if (context == null) return true
        // Keep the new app-scoped mode as the default, while allowing a game
        // with stricter filesystem assumptions to use its original directory.
        return prefs(context).getBoolean(EngineSaveKeys.KEY_KR_SCOPED_SAVE_DIR, true)
    }

    @JvmStatic
    fun setKrScopedSaveDir(context: Context?, enabled: Boolean) {
        if (context == null) return
        prefs(context).edit().putBoolean(EngineSaveKeys.KEY_KR_SCOPED_SAVE_DIR, enabled).apply()
    }

    @JvmStatic
    fun isTyranoScopedSaveDir(context: Context?): Boolean {
        if (context == null) return true
        return prefs(context).getBoolean(EngineSaveKeys.KEY_TYRANO_SCOPED_SAVE_DIR, true)
    }

    @JvmStatic
    fun setTyranoScopedSaveDir(context: Context?, enabled: Boolean) {
        if (context == null) return
        prefs(context).edit().putBoolean(EngineSaveKeys.KEY_TYRANO_SCOPED_SAVE_DIR, enabled).apply()
    }

    /** Enables remote HTTP(S) subresources for Tyrano games that require a CDN or online API. */
    @JvmStatic
    fun isTyranoExternalNetworkEnabled(context: Context?): Boolean {
        if (context == null) return false
        return prefs(context).getBoolean(EngineSaveKeys.KEY_TYRANO_EXTERNAL_NETWORK, false)
    }

    @JvmStatic
    fun setTyranoExternalNetworkEnabled(context: Context?, enabled: Boolean) {
        if (context == null) return
        prefs(context).edit().putBoolean(EngineSaveKeys.KEY_TYRANO_EXTERNAL_NETWORK, enabled).apply()
    }

    @JvmStatic
    fun normalizeEngineVersion(value: String?): String {
        val v = value?.trim()?.lowercase() ?: ENGINE_VERSION_AUTO
        return when (v) {
            ENGINE_VERSION_139 -> ENGINE_VERSION_139
            ENGINE_VERSION_134 -> ENGINE_VERSION_134
            ENGINE_VERSION_126 -> ENGINE_VERSION_126
            else -> ENGINE_VERSION_AUTO
        }
    }

    @JvmStatic
    fun engineVersionLabel(value: String?): String {
        return when (normalizeEngineVersion(value)) {
            ENGINE_VERSION_139 -> "1.3.9"
            ENGINE_VERSION_134 -> "1.3.4"
            ENGINE_VERSION_126 -> "1.2.6"
            else -> "自动"
        }
    }

    // ──────────────────────────────────────────────
    // KRKR 引擎内核
    // ──────────────────────────────────────────────

    @JvmStatic
    fun getEngineKernel(context: Context?): String {
        if (context == null) return KERNEL_AUTO
        val v = prefs(context).getString(CorePreferences.KEY_KR_ENGINE_KERNEL, KERNEL_AUTO)
        return normalizeEngineKernel(v)
    }

    @JvmStatic
    fun setEngineKernel(context: Context?, kernel: String?) {
        if (context == null) return
        prefs(context).edit()
            .putString(CorePreferences.KEY_KR_ENGINE_KERNEL, normalizeEngineKernel(kernel))
            .apply()
    }

    @JvmStatic
    fun normalizeEngineKernel(value: String?): String {
        val v = value?.trim()?.lowercase() ?: KERNEL_AUTO
        return when (v) {
            KERNEL_KIRIKIRI2 -> KERNEL_KIRIKIRI2
            KERNEL_KRKRSDL3 -> KERNEL_KRKRSDL3
            else -> KERNEL_AUTO
        }
    }

    @JvmStatic
    fun engineKernelLabel(value: String?): String {
        return when (normalizeEngineKernel(value)) {
            KERNEL_KIRIKIRI2 -> "吉里吉里2"
            KERNEL_KRKRSDL3 -> "krkrsdl3"
            else -> "自动"
        }
    }

    // ──────────────────────────────────────────────
    // KRKR 字体配置（Kirikiroid2 引擎）
    // ──────────────────────────────────────────────

    /** 默认字体文件路径；空串表示使用引擎内置字体。 */
    @JvmStatic
    fun getDefaultFont(context: Context?): String {
        if (context == null) return ""
        return prefs(context).getString(CorePreferences.KEY_KR_DEFAULT_FONT, "") ?: ""
    }

    /** 写入默认字体路径；传空串恢复引擎内置字体。 */
    @JvmStatic
    fun setDefaultFont(context: Context?, fontPath: String?) {
        if (context == null) return
        prefs(context).edit()
            .putString(CorePreferences.KEY_KR_DEFAULT_FONT, fontPath?.trim().orEmpty())
            .apply()
    }

    /** 强制使用默认字体（忽略游戏自带的字体资源）。 */
    @JvmStatic
    fun isForceDefaultFont(context: Context?): Boolean {
        if (context == null) return false
        return prefs(context).getBoolean(CorePreferences.KEY_KR_FORCE_DEFAULT_FONT, false)
    }

    /** 写入强制默认字体开关。 */
    @JvmStatic
    fun setForceDefaultFont(context: Context?, enabled: Boolean) {
        if (context == null) return
        prefs(context).edit().putBoolean(CorePreferences.KEY_KR_FORCE_DEFAULT_FONT, enabled).apply()
    }

    /** 归一化字体路径：去首尾空白；空串表示使用内置字体（注入层据此跳过覆盖）。 */
    @JvmStatic
    fun normalizeFontPath(value: String?): String {
        return value?.trim().orEmpty()
    }

    // ──────────────────────────────────────────────
    // KRKR 图形渲染 / 内存配置（Kirikiroid2 引擎）
    //
    // 全部键经引擎 IndividualConfigManager 读取（游戏目录 XML 优先，回退全局 XML），
    // 与字体键同一注入通道。每个 normalize 做白名单校验：非法值回退空串 = launcher
    // 不管理该键（保持引擎内置默认）。null=跟随全局只在 per-game 层表达，全局层
    // 空串即表示未显式设置。
    // ──────────────────────────────────────────────

    const val RENDERER_SOFTWARE = "software"
    const val RENDERER_OPENGL = "opengl"
    const val MEMUSAGE_UNLIMITED = "unlimited"
    const val MEMUSAGE_HIGH = "high"
    const val MEMUSAGE_MEDIUM = "medium"
    const val MEMUSAGE_LOW = "low"

    // 渲染/内存引擎偏好键（与引擎 XML key 同名），单一来源：UI 层与组装链路
    // 一律引用这些常量，避免键名字面量双源漂移（KRKR_ENGINE_PREF_KEYS 由它们派生）。
    const val PREF_RENDERER = "renderer"
    const val PREF_SOFTWARE_DRAW_THREAD = "software_draw_thread"
    const val PREF_SOFTWARE_COMPRESS_TEX = "software_compress_tex"
    const val PREF_OGL_COMPRESS_TEX = "ogl_compress_tex"
    const val PREF_MEMUSAGE = "memusage"
    const val PREF_OGL_MAX_TEXSIZE = "ogl_max_texsize"
    const val PREF_OGL_ACCURATE_RENDER = "ogl_accurate_render"
    const val PREF_FPS_LIMIT = "fps_limit"

    /** 渲染/内存引擎偏好键，启动链路组装 JSON 依此枚举。 */
    val KRKR_ENGINE_PREF_KEYS: List<String> = listOf(
        PREF_RENDERER, PREF_SOFTWARE_DRAW_THREAD, PREF_SOFTWARE_COMPRESS_TEX,
        PREF_OGL_COMPRESS_TEX, PREF_MEMUSAGE, PREF_OGL_MAX_TEXSIZE,
        PREF_OGL_ACCURATE_RENDER, PREF_FPS_LIMIT,
    )

    @JvmStatic
    fun normalizeRenderer(value: String?): String {
        val v = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return if (v == RENDERER_SOFTWARE || v == RENDERER_OPENGL) v else ""
    }

    @JvmStatic
    fun normalizeSoftwareDrawThread(value: String?): String {
        val n = value?.trim()?.toIntOrNull() ?: return ""
        return if (n in 0..8) n.toString() else ""
    }

    @JvmStatic
    fun normalizeSoftwareCompressTex(value: String?): String {
        val v = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return if (v == "none" || v == "halfline" || v == "lz4" || v == "lz4+tlg5") v else ""
    }

    @JvmStatic
    fun normalizeOglCompressTex(value: String?): String {
        val v = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return if (v == "none" || v == "half" || v == "etc2" || v == "pvrtc") v else ""
    }

    @JvmStatic
    fun normalizeMemUsage(value: String?): String {
        val v = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return if (
            v == MEMUSAGE_UNLIMITED || v == MEMUSAGE_HIGH || v == MEMUSAGE_MEDIUM || v == MEMUSAGE_LOW
        ) v else ""
    }

    @JvmStatic
    fun normalizeOglMaxTexsize(value: String?): String {
        val n = value?.trim()?.toIntOrNull() ?: return ""
        return if (n == 0 || n in 1024..16384) n.toString() else ""
    }

    @JvmStatic
    fun normalizeOglAccurateRender(value: String?): String {
        return when (value?.trim()?.lowercase(Locale.ROOT).orEmpty()) {
            "1", "true" -> "1"
            "0", "false" -> "0"
            else -> ""
        }
    }

    @JvmStatic
    fun normalizeFpsLimit(value: String?): String {
        val v = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return if (v == "60" || v == "45" || v == "30" || v == "15") v else ""
    }

    private fun readEnginePref(context: Context?, prefKey: String): String =
        if (context == null) "" else (prefs(context).getString(prefKey, null) ?: "")

    private fun writeEnginePref(context: Context?, prefKey: String, value: String?) {
        if (context != null) {
            prefs(context).edit().putString(prefKey, value?.trim().orEmpty()).apply()
        }
    }

    @JvmStatic
    fun getRenderer(context: Context?): String =
        normalizeRenderer(readEnginePref(context, CorePreferences.KEY_KR_RENDERER))

    @JvmStatic
    fun setRenderer(context: Context?, value: String?) =
        writeEnginePref(context, CorePreferences.KEY_KR_RENDERER, normalizeRenderer(value))

    @JvmStatic
    fun getSoftwareDrawThread(context: Context?): String =
        normalizeSoftwareDrawThread(readEnginePref(context, CorePreferences.KEY_KR_SOFTWARE_DRAW_THREAD))

    @JvmStatic
    fun setSoftwareDrawThread(context: Context?, value: String?) =
        writeEnginePref(context, CorePreferences.KEY_KR_SOFTWARE_DRAW_THREAD, normalizeSoftwareDrawThread(value))

    @JvmStatic
    fun getSoftwareCompressTex(context: Context?): String =
        normalizeSoftwareCompressTex(readEnginePref(context, CorePreferences.KEY_KR_SOFTWARE_COMPRESS_TEX))

    @JvmStatic
    fun setSoftwareCompressTex(context: Context?, value: String?) =
        writeEnginePref(context, CorePreferences.KEY_KR_SOFTWARE_COMPRESS_TEX, normalizeSoftwareCompressTex(value))

    @JvmStatic
    fun getOglCompressTex(context: Context?): String =
        normalizeOglCompressTex(readEnginePref(context, CorePreferences.KEY_KR_OGL_COMPRESS_TEX))

    @JvmStatic
    fun setOglCompressTex(context: Context?, value: String?) =
        writeEnginePref(context, CorePreferences.KEY_KR_OGL_COMPRESS_TEX, normalizeOglCompressTex(value))

    @JvmStatic
    fun getMemUsage(context: Context?): String =
        normalizeMemUsage(readEnginePref(context, CorePreferences.KEY_KR_MEM_USAGE))

    @JvmStatic
    fun setMemUsage(context: Context?, value: String?) =
        writeEnginePref(context, CorePreferences.KEY_KR_MEM_USAGE, normalizeMemUsage(value))

    @JvmStatic
    fun getOglMaxTexsize(context: Context?): String =
        normalizeOglMaxTexsize(readEnginePref(context, CorePreferences.KEY_KR_OGL_MAX_TEXSIZE))

    @JvmStatic
    fun setOglMaxTexsize(context: Context?, value: String?) =
        writeEnginePref(context, CorePreferences.KEY_KR_OGL_MAX_TEXSIZE, normalizeOglMaxTexsize(value))

    @JvmStatic
    fun getOglAccurateRender(context: Context?): String =
        normalizeOglAccurateRender(readEnginePref(context, CorePreferences.KEY_KR_OGL_ACCURATE_RENDER))

    @JvmStatic
    fun setOglAccurateRender(context: Context?, value: String?) =
        writeEnginePref(context, CorePreferences.KEY_KR_OGL_ACCURATE_RENDER, normalizeOglAccurateRender(value))

    @JvmStatic
    fun getFpsLimit(context: Context?): String =
        normalizeFpsLimit(readEnginePref(context, CorePreferences.KEY_KR_FPS_LIMIT))

    @JvmStatic
    fun setFpsLimit(context: Context?, value: String?) =
        writeEnginePref(context, CorePreferences.KEY_KR_FPS_LIMIT, normalizeFpsLimit(value))

    private fun readEnginePrefNormalized(context: Context, engineKey: String): String = when (engineKey) {
        "renderer" -> getRenderer(context)
        "software_draw_thread" -> getSoftwareDrawThread(context)
        "software_compress_tex" -> getSoftwareCompressTex(context)
        "ogl_compress_tex" -> getOglCompressTex(context)
        "memusage" -> getMemUsage(context)
        "ogl_max_texsize" -> getOglMaxTexsize(context)
        "ogl_accurate_render" -> getOglAccurateRender(context)
        "fps_limit" -> getFpsLimit(context)
        else -> ""
    }

    /**
     * 组装渲染/内存引擎偏好的 JSON（Intent extra: krkr_engine_prefs），结构
     * {"<engineKey>":{"v":"值","s":"game|global"}}。s=global 表示该键仅由全局偏好决定
     * （engine 侧写全局 XML 并清游戏目录残留键，保证跟随全局）；game 表示该游戏有覆盖
     * （写入游戏目录 XML）。8 个键全部无条件写入——v 为空（两者皆空）时由引擎端
     * value.isEmpty() ? null : value 的 unset 分支清理对应键的旧注入残留，杜绝
     * "覆盖被清除但引擎 XML 残留旧值"的场景。因此返回值恒非空。
     *
     * @param gameOverride 取某引擎键的游戏级覆盖值，null = 跟随全局。
     */
    @JvmStatic
    fun buildEnginePrefsJson(context: Context?, gameOverride: (engineKey: String) -> String?): String {
        if (context == null) return ""
        val json = JSONObject()
        KRKR_ENGINE_PREF_KEYS.forEach { engineKey ->
            val override = gameOverride(engineKey)
            val global = readEnginePrefNormalized(context, engineKey)
            val value = override ?: global
            json.put(engineKey, JSONObject().put("v", value).put("s", if (override != null) "game" else "global"))
        }
        return json.toString()
    }

    /**
     * 查询 content URI 的显示名并做路径穿越消毒（取末段，两分支口径一致）。
     * 返回 null 表示无法取得有效文件名，调用方应放弃导入而非回退其它原始段。
     * 涉及 ContentResolver 查询，须在后台线程调用。
     */
    @JvmStatic
    fun resolveFontFileName(context: Context?, uri: Uri): String? {
        if (context == null) return null
        var raw: String? = null
        try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) raw = cursor.getString(index)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "query font display name failed", error)
        }
        val source = raw?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment
        if (source.isNullOrBlank()) return null
        // 恶意 provider 可能返回 "../evil.ttf"，取末段封闭逃逸。
        val safe = source.substringAfterLast('/').substringAfterLast('\\').trim()
        return safe.ifEmpty { null }
    }

    /** 字体文件名扩展名校验（ttf/ttc/otf/otc，大小写不敏感）。 */
    @JvmStatic
    fun isFontFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return FONT_FILE_EXTENSIONS.any { lower.endsWith(it) }
    }

    /**
     * 把字体 content URI 流式写入 filesDir/fonts/，返回可被原生引擎读取的绝对路径。
     * 拷贝带 [MAX_FONT_FILE_BYTES] 字节上限，任何失败（含超上限）返回 null 并删除半截
     * 文件。调用方须先经 [resolveFontFileName] + [isFontFileName] 预检文件名与扩展名，
     * 避免把非字体大文件完整拷入后再丢弃。必须在后台线程调用。
     *
     * 备份恢复注意：filesDir/fonts 不在 Android 自动备份范围，跨设备恢复后保存的私有
     * 路径可能悬空，引擎会静默回退内置字体（降级安全但用户无感知）。保存后用
     * [getDefaultFont] 回读可判断路径是否仍指向现存文件。
     */
    @JvmStatic
    fun importFontFile(context: Context?, uri: Uri, fileName: String): String? {
        if (context == null) return null
        val dir = File(context.filesDir, "fonts")
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "create fonts dir failed: $dir")
            return null
        }
        val target = File(dir, fileName)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { src ->
                FileOutputStream(target).use { out ->
                    var total = 0L
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = src.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FONT_FILE_BYTES) {
                            throw IOException("font file over limit ${MAX_FONT_FILE_BYTES / 1024 / 1024}MB")
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
            return if (target.length() > 0L) target.absolutePath else {
                target.delete()
                null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: OutOfMemoryError) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "import font file failed", error)
            target.delete()
            return null
        }
    }
}
