package com.core.launcherbridge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.core.CorePreferences
import com.core.launcher.ArtemisLauncher
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * Artemis 引擎应用级/游戏级设置：应用级默认（引擎版本、画面反转）存 yukihub_prefs，
 * per-game 覆盖以独立 prefs 文件按 gameId 存 JSON 快照。
 * 没有快照时使用应用级默认；清除快照后重新跟随应用级默认。
 *
 * 与 [LauncherKrkrGameSettingsBridge] 同模式：独立 prefs 文件 + JSON 快照，
 * 不依赖数据库迁移；游戏删除/清库/快照恢复时须同步清理，防止旧 gameId 串到新游戏。
 *
 * 参考 tyranor 启动器：引擎版本在启动侧确定性选择（不做试错），方向支持 180° 反转。
 */
object LauncherArtemisGameSettingsBridge {

    private const val TAG = "ArtemisGameSettingsBridge"
    private const val PREF_NAME = "artemis_game_overrides"
    private const val KEY_PREFIX = "game_"
    private const val PREFS_NAME = CorePreferences.APP_PREFS

    /** 引擎版本取值（主源 com.core.launcher.ArtemisLauncher）：自动（V1 起 + 试错回退）/ 固定 1/2/3。 */
    const val ENGINE_VERSION_AUTO = ArtemisLauncher.ENGINE_VERSION_AUTO
    const val ENGINE_VERSION_V1 = ArtemisLauncher.ENGINE_VERSION_V1
    const val ENGINE_VERSION_V2 = ArtemisLauncher.ENGINE_VERSION_V2
    const val ENGINE_VERSION_V3 = ArtemisLauncher.ENGINE_VERSION_V3

    /** 单款 Artemis 游戏最终生效配置（应用级默认叠加游戏覆盖）。 */
    class ArtemisEngineSettings(
        @JvmField var engineVersion: String = ENGINE_VERSION_AUTO,
        @JvmField var rotateScreen: Boolean = false,
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun appPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------- 应用级默认 ----------

    /** 应用级默认引擎版本（auto/1/2/3）。 */
    @JvmStatic
    fun getDefaultEngineVersion(context: Context?): String {
        if (context == null) return ENGINE_VERSION_AUTO
        return normalizeEngineVersion(appPrefs(context).getString(CorePreferences.KEY_ARTEMIS_ENGINE_VERSION, null))
    }

    /** 写入应用级默认引擎版本。 */
    @JvmStatic
    fun setDefaultEngineVersion(context: Context?, version: String) {
        if (context == null) return
        appPrefs(context).edit().putString(CorePreferences.KEY_ARTEMIS_ENGINE_VERSION, normalizeEngineVersion(version)).apply()
    }

    /** 应用级默认画面反转开关。 */
    @JvmStatic
    fun getDefaultRotateScreen(context: Context?): Boolean {
        if (context == null) return false
        return appPrefs(context).getBoolean(CorePreferences.KEY_ARTEMIS_ROTATE_SCREEN, false)
    }

    /** 写入应用级默认画面反转开关。 */
    @JvmStatic
    fun setDefaultRotateScreen(context: Context?, rotate: Boolean) {
        if (context == null) return
        appPrefs(context).edit().putBoolean(CorePreferences.KEY_ARTEMIS_ROTATE_SCREEN, rotate).apply()
    }

    // ---------- 游戏级覆盖 ----------

    /** 游戏是否存在自己的覆盖配置。 */
    @JvmStatic
    fun hasOverride(context: Context?, gameId: Long): Boolean {
        if (context == null || gameId <= 0) return false
        return prefs(context).contains(key(gameId))
    }

    /**
     * 返回该游戏最终生效的 Artemis 引擎设置：应用级默认叠加该游戏的覆盖项。
     * gameId <= 0 时退化为应用级默认。
     */
    @JvmStatic
    fun load(context: Context?, gameId: Long): ArtemisEngineSettings {
        val settings = ArtemisEngineSettings(
            engineVersion = getDefaultEngineVersion(context),
            rotateScreen = getDefaultRotateScreen(context),
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
    fun save(context: Context?, gameId: Long, settings: ArtemisEngineSettings) {
        if (context == null || gameId <= 0) return
        try {
            prefs(context).edit()
                .putString(key(gameId), toJson(settings).toString())
                .apply()
        } catch (t: Exception) {
            Log.w(TAG, "save override failed gameId=$gameId", t)
        }
    }

    /** 删除该游戏的覆盖，回退到应用级默认。 */
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

    /** 该游戏最终生效的引擎版本；gameId <= 0 时回退应用级默认。 */
    @JvmStatic
    fun resolveEngineVersion(context: Context?, gameId: Long): String {
        if (gameId <= 0) return getDefaultEngineVersion(context)
        return load(context, gameId).engineVersion
    }

    /** 该游戏最终生效的画面反转开关；gameId <= 0 时回退应用级默认。 */
    @JvmStatic
    fun resolveRotateScreen(context: Context?, gameId: Long): Boolean {
        if (gameId <= 0) return getDefaultRotateScreen(context)
        return load(context, gameId).rotateScreen
    }

    /** 归一化引擎版本取值：auto/1/2/3（兼容历史包名与别名写法）。 */
    @JvmStatic
    fun normalizeEngineVersion(value: String?): String = when (value?.trim()?.lowercase(Locale.ROOT)) {
        "1", "v1", "internal.artemis" -> ENGINE_VERSION_V1
        "2", "v2", "compat", "internal.artemis.compat", "internal.artemis.compatible" -> ENGINE_VERSION_V2
        "3", "v3", "compat.v2", "compatible_v2", "internal.artemis.compat.v2", "internal.artemis.compatible.v2" -> ENGINE_VERSION_V3
        else -> ENGINE_VERSION_AUTO
    }

    private fun key(gameId: Long): String = "$KEY_PREFIX$gameId"

    @Throws(JSONException::class)
    private fun toJson(settings: ArtemisEngineSettings): JSONObject {
        val o = JSONObject()
        o.put("engine_version", normalizeEngineVersion(settings.engineVersion))
        o.put("rotate_screen", settings.rotateScreen)
        return o
    }

    private fun applyOverride(settings: ArtemisEngineSettings, o: JSONObject?) {
        if (o == null) return
        // optString/optBoolean 在 key 缺失时返回默认值，会误覆盖；先 has() 再读，
        // 保证只覆盖实际写过的字段。
        if (o.has("engine_version")) {
            settings.engineVersion = normalizeEngineVersion(o.optString("engine_version"))
        }
        if (o.has("rotate_screen")) settings.rotateScreen = o.optBoolean("rotate_screen")
    }
}
