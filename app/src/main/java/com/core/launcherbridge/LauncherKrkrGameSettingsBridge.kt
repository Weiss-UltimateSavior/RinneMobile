package com.core.launcherbridge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * KRKR 引擎游戏级设置覆盖：以独立 prefs 文件按 gameId 存储每款 KRKR 游戏的
 * 引擎参数快照（引擎版本、独立存档目录）。
 * 没有快照时使用全局设置；清除快照后重新跟随全局设置。
 *
 * 与 [LauncherOnsGameSettingsBridge] 同模式：独立 prefs 文件 + JSON 快照，
 * 不依赖数据库迁移；游戏删除/清库/快照恢复时须同步清理，防止旧 gameId 串到新游戏。
 */
object LauncherKrkrGameSettingsBridge {

    private const val TAG = "KrkrGameSettingsBridge"
    private const val PREF_NAME = "krkr_game_overrides"
    private const val KEY_PREFIX = "game_"

    /** 单款游戏的 KRKR 引擎最终生效配置（全局默认叠加游戏覆盖）。 */
    class KrkrEngineSettings(
        @JvmField var engineVersion: String = LauncherKrkrBridge.ENGINE_VERSION_AUTO,
        @JvmField var scopedSaveDir: Boolean = true,
        @JvmField var engineKernel: String = LauncherKrkrBridge.KERNEL_AUTO,
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 游戏是否存在自己的覆盖配置。 */
    @JvmStatic
    fun hasOverride(context: Context?, gameId: Long): Boolean {
        if (context == null || gameId <= 0) return false
        return prefs(context).contains(key(gameId))
    }

    /**
     * 返回该游戏最终生效的 KRKR 引擎设置：全局默认叠加该游戏的覆盖项。
     * gameId <= 0 时退化为全局设置。
     */
    @JvmStatic
    fun load(context: Context?, gameId: Long): KrkrEngineSettings {
        val settings = KrkrEngineSettings(
            engineVersion = LauncherKrkrBridge.getEngineVersion(context),
            scopedSaveDir = LauncherKrkrBridge.isKrScopedSaveDir(context),
            engineKernel = LauncherKrkrBridge.getEngineKernel(context),
        )
        if (context == null || gameId <= 0) return settings
        try {
            val json = prefs(context).getString(key(gameId), null)
            if (!json.isNullOrBlank()) {
                applyOverride(settings, JSONObject(json))
            }
        } catch (t: Exception) {
            Log.w(TAG, "load override failed gameId=$gameId", t)
        }
        return settings
    }

    /** 将传入设置作为该游戏的覆盖保存。 */
    @JvmStatic
    fun save(context: Context?, gameId: Long, settings: KrkrEngineSettings) {
        if (context == null || gameId <= 0) return
        try {
            prefs(context).edit()
                .putString(key(gameId), toJson(settings).toString())
                .apply()
        } catch (t: Exception) {
            Log.w(TAG, "save override failed gameId=$gameId", t)
        }
    }

    /** 删除该游戏的覆盖，回退到全局默认。 */
    @JvmStatic
    fun clearOverride(context: Context?, gameId: Long) {
        if (context == null || gameId <= 0) return
        prefs(context).edit().remove(key(gameId)).apply()
    }

    /** 清除全部游戏级覆盖；用于数据库完整快照恢复，防止旧 gameId 串到新游戏。 */
    @JvmStatic
    fun clearAllOverrides(context: Context?) {
        if (context == null) return
        prefs(context).edit().clear().apply()
    }

    /** 该游戏最终生效的引擎版本；gameId <= 0 时回退全局。 */
    @JvmStatic
    fun resolveEngineVersion(context: Context?, gameId: Long): String {
        if (gameId <= 0) return LauncherKrkrBridge.getEngineVersion(context)
        return load(context, gameId).engineVersion
    }

    /** 该游戏最终生效的独立存档开关；gameId <= 0 时回退全局。 */
    @JvmStatic
    fun resolveScopedSaveDir(context: Context?, gameId: Long): Boolean {
        if (gameId <= 0) return LauncherKrkrBridge.isKrScopedSaveDir(context)
        return load(context, gameId).scopedSaveDir
    }

    /** 该游戏最终生效的 KRKR 引擎内核；gameId <= 0 时回退全局。 */
    @JvmStatic
    fun resolveEngineKernel(context: Context?, gameId: Long): String {
        if (gameId <= 0) return LauncherKrkrBridge.getEngineKernel(context)
        return load(context, gameId).engineKernel
    }

    private fun key(gameId: Long): String = "$KEY_PREFIX$gameId"

    @Throws(JSONException::class)
    private fun toJson(settings: KrkrEngineSettings): JSONObject {
        val o = JSONObject()
        o.put("engine_version", LauncherKrkrBridge.normalizeEngineVersion(settings.engineVersion))
        o.put("scoped_save_dir", settings.scopedSaveDir)
        o.put("engine_kernel", LauncherKrkrBridge.normalizeEngineKernel(settings.engineKernel))
        return o
    }

    private fun applyOverride(settings: KrkrEngineSettings, o: JSONObject?) {
        if (o == null) return
        // optString/optBoolean 在 key 缺失时返回默认值，会误覆盖；先 has() 再读，
        // 保证只覆盖实际写过的字段。
        if (o.has("engine_version")) {
            settings.engineVersion = LauncherKrkrBridge.normalizeEngineVersion(o.optString("engine_version"))
        }
        if (o.has("scoped_save_dir")) settings.scopedSaveDir = o.optBoolean("scoped_save_dir")
        if (o.has("engine_kernel")) {
            settings.engineKernel = LauncherKrkrBridge.normalizeEngineKernel(o.optString("engine_kernel"))
        }
    }
}
