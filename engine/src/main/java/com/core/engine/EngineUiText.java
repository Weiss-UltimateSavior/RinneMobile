package com.core.engine;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;

import java.util.Locale;

/** Resolves launcher-facing engine text without changing the native engine's compatibility locale. */
public final class EngineUiText {
    private EngineUiText() {}

    public static String get(Context context, int resourceId) {
        if (context == null) return "";
        String languageTag = null;
        try {
            if (context instanceof Activity) {
                Intent intent = ((Activity) context).getIntent();
                if (intent != null) languageTag = intent.getStringExtra("uiLanguageTag");
            }
            if (languageTag == null || languageTag.trim().isEmpty()) {
                return context.getString(resourceId);
            }
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            configuration.setLocale(Locale.forLanguageTag(languageTag));
            return context.createConfigurationContext(configuration).getString(resourceId);
        } catch (Throwable ignored) {
            return context.getString(resourceId);
        }
    }

    /**
     * The KRKR shell must run with its bundled Chinese locale, but common dialog chrome belongs
     * to the launcher UI. Translate only exact system labels and leave game-authored text intact.
     */
    public static String localizeCommonDialogText(Context context, String text) {
        if (text == null) return "";
        switch (text.trim()) {
            case "确定":
            case "确认":
            case "好":
                return get(context, R.string.engine_ok);
            case "取消":
                return get(context, R.string.engine_cancel);
            case "是":
                return get(context, R.string.engine_yes);
            case "否":
                return get(context, R.string.engine_no);
            case "提示":
                return get(context, R.string.engine_prompt);
            case "错误":
                return get(context, R.string.engine_error);
            default:
                return text;
        }
    }
}
