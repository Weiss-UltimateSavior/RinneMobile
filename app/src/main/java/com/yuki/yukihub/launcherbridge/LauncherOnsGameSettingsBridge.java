package com.yuki.yukihub.launcherbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yuki.yukihub.ons.OnsSettings;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * ONS 引擎游戏级设置覆盖：以独立 prefs 文件按 gameId 存储每款 ONS 游戏的
 * 完整引擎参数快照（编码、拉伸、刘海、视频、锐化、独立存档目录等）。
 * 没有快照时使用全局 {@link OnsSettings}；清除快照后重新跟随全局设置。
 */
public final class LauncherOnsGameSettingsBridge {
    private static final String TAG = "OnsGameSettingsBridge";
    private static final String PREF_NAME = "onsyuri_game_overrides";
    private static final String KEY_PREFIX = "game_";

    private LauncherOnsGameSettingsBridge() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 游戏是否存在自己的覆盖配置。 */
    public static boolean hasOverride(@Nullable Context context, long gameId) {
        if (context == null || gameId <= 0) return false;
        return prefs(context).contains(key(gameId));
    }

    /**
     * 返回该游戏最终生效的 ONS 设置：全局默认叠加该游戏的覆盖项。
     * gameId <= 0 时退化为全局设置。
     */
    @NonNull
    public static OnsSettings load(@Nullable Context context, long gameId) {
        OnsSettings settings = OnsSettings.load(context);
        if (context == null || gameId <= 0) return settings;
        try {
            String json = prefs(context).getString(key(gameId), null);
            if (json != null && !json.trim().isEmpty()) {
                applyOverride(settings, new JSONObject(json));
            }
        } catch (Throwable t) {
            Log.w(TAG, "load override failed gameId=" + gameId, t);
        }
        return settings;
    }

    /** 将传入设置作为该游戏的覆盖保存。 */
    public static void save(@Nullable Context context, long gameId, @NonNull OnsSettings settings) {
        if (context == null || gameId <= 0) return;
        try {
            prefs(context).edit()
                    .putString(key(gameId), toJson(settings).toString())
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "save override failed gameId=" + gameId, t);
        }
    }

    /** 删除该游戏的覆盖，回退到全局默认。 */
    public static void clearOverride(@Nullable Context context, long gameId) {
        if (context == null || gameId <= 0) return;
        prefs(context).edit().remove(key(gameId)).apply();
    }

    /** 清除全部游戏级覆盖；用于数据库完整快照恢复，防止旧 gameId 串到新游戏。 */
    public static void clearAllOverrides(@Nullable Context context) {
        if (context == null) return;
        prefs(context).edit().clear().apply();
    }

    private static String key(long gameId) {
        return KEY_PREFIX + gameId;
    }

    private static JSONObject toJson(OnsSettings settings) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("scopedsavedir", settings.scopedSaveDir);
        o.put("strechfull", settings.stretchFull);
        o.put("ignorecutout", settings.ignoreCutout);
        o.put("disablevideo", settings.disableVideo);
        o.put("sharpness", settings.sharpness);
        o.put("sharpness_value", settings.sharpnessValue == null ? "" : settings.sharpnessValue);
        o.put("encoding", OnsSettings.normalizeEncoding(settings.encoding));
        o.put("alloweditargs", settings.allowEditArgs);
        return o;
    }

    private static void applyOverride(OnsSettings settings, JSONObject o) {
        if (o == null) return;
        // optBoolean/optString 在 key 缺失时返回默认值（false/""），会误覆盖；
        // 因此先 has() 再读，保证只覆盖实际写过的字段。
        if (o.has("scopedsavedir")) settings.scopedSaveDir = o.optBoolean("scopedsavedir");
        if (o.has("strechfull")) settings.stretchFull = o.optBoolean("strechfull");
        if (o.has("ignorecutout")) settings.ignoreCutout = o.optBoolean("ignorecutout");
        if (o.has("disablevideo")) settings.disableVideo = o.optBoolean("disablevideo");
        if (o.has("sharpness")) settings.sharpness = o.optBoolean("sharpness");
        if (o.has("sharpness_value")) settings.sharpnessValue = o.optString("sharpness_value");
        if (o.has("encoding")) settings.encoding = OnsSettings.normalizeEncoding(o.optString("encoding"));
        if (o.has("alloweditargs")) settings.allowEditArgs = o.optBoolean("alloweditargs");
    }
}
