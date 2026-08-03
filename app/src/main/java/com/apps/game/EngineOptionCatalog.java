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
                new EngineOption(EngineType.KIRIKIRI, context.getString(R.string.game_engine_kirikiri), null),
                new EngineOption(EngineType.ONS, context.getString(R.string.game_engine_onscripter), null),
                new EngineOption(EngineType.TYRANO, context.getString(R.string.game_engine_tyrano), null),
                new EngineOption(EngineType.ARTEMIS, context.getString(R.string.game_engine_artemis), null),
                new EngineOption(EngineType.WINLATOR, context.getString(R.string.game_engine_winlator), null),
                new EngineOption(EngineType.GAMEHUB, context.getString(R.string.game_engine_gamehub), null),
                new EngineOption(EngineType.PSP, context.getString(R.string.game_engine_psp), null),
                new EngineOption(EngineType.NINTENDO_3DS, context.getString(R.string.game_engine_nintendo_3ds), null),
                new EngineOption(EngineType.NINTENDO_SWITCH, context.getString(R.string.game_engine_nintendo_switch), null),
                new EngineOption(EngineType.RPGMAKER, context.getString(R.string.game_engine_rpgmaker_xp), "rpgmxp"),
                new EngineOption(EngineType.RPGMAKER, context.getString(R.string.game_engine_rpgmaker_vx), "rpgmvx"),
                new EngineOption(EngineType.RPGMAKER, context.getString(R.string.game_engine_rpgmaker_vxace), "rpgmvxace"),
                new EngineOption(EngineType.RPGMAKER, context.getString(R.string.game_engine_rpgmaker_mkxp), "mkxp-z"),
                new EngineOption(EngineType.RENPY, context.getString(R.string.game_engine_renpy), "renpy"),
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
