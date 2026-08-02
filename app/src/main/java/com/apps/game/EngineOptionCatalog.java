package com.apps.game;

import android.content.Context;

import com.core.R;
import com.core.model.EngineType;

/** Single source of truth for add/edit game engine picker options. */
final class EngineOptionCatalog {
    private EngineOptionCatalog() {
    }

    static EngineOption[] create(Context context, boolean includeUnknown) {
        EngineOption[] base = new EngineOption[]{
                new EngineOption(EngineType.AUTO, context.getString(R.string.game_engine_auto), null),
                new EngineOption(EngineType.KIRIKIRI, "Kirikiri", null),
                new EngineOption(EngineType.ONS, "ONScripter", null),
                new EngineOption(EngineType.TYRANO, "Tyrano", null),
                new EngineOption(EngineType.ARTEMIS, "Artemis", null),
                new EngineOption(EngineType.WINLATOR, "Winlator", null),
                new EngineOption(EngineType.GAMEHUB, "GameHub", null),
                new EngineOption(EngineType.PSP, "PSP", null),
                new EngineOption(EngineType.NINTENDO_3DS, "Nintendo 3DS", null),
                new EngineOption(EngineType.NINTENDO_SWITCH, "Nintendo Switch (Eden)", null),
                new EngineOption(EngineType.RPGMAKER, "RPG Maker XP (RGSS1, Ruby 1.8)", "rpgmxp"),
                new EngineOption(EngineType.RPGMAKER, "RPG Maker VX (RGSS2, Ruby 1.9)", "rpgmvx"),
                new EngineOption(EngineType.RPGMAKER, "RPG Maker VX Ace (RGSS3, Ruby 1.9)", "rpgmvxace"),
                new EngineOption(EngineType.RPGMAKER, context.getString(R.string.game_engine_rpgmaker_mkxp), "mkxp-z"),
                new EngineOption(EngineType.RENPY, "Ren'Py", "renpy"),
                new EngineOption(EngineType.GODOT, context.getString(R.string.game_engine_godot_auto), "godot4")
        };
        if (!includeUnknown) return base;
        EngineOption[] options = new EngineOption[base.length + 1];
        System.arraycopy(base, 0, options, 0, base.length);
        options[base.length] = new EngineOption(
                EngineType.UNKNOWN,
                context.getString(R.string.game_common_unknown),
                null);
        return options;
    }
}
