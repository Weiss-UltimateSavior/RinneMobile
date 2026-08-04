package com.apps.game

import android.content.Context
import com.core.R
import com.core.model.EngineType

/** Single source of truth for add/edit game engine picker options. */
internal object EngineOptionCatalog {
    @JvmStatic
    fun create(context: Context, includeUnknown: Boolean): Array<EngineOption> {
        val base = arrayOf(
            EngineOption(EngineType.AUTO, context.getString(R.string.game_engine_auto), null),
            EngineOption(EngineType.KIRIKIRI, context.getString(R.string.game_engine_kirikiri), null),
            EngineOption(EngineType.ONS, context.getString(R.string.game_engine_onscripter), null),
            EngineOption(EngineType.TYRANO, context.getString(R.string.game_engine_tyrano), null),
            EngineOption(EngineType.ARTEMIS, context.getString(R.string.game_engine_artemis), null),
            EngineOption(EngineType.WINLATOR, context.getString(R.string.game_engine_winlator), null),
            EngineOption(EngineType.GAMEHUB, context.getString(R.string.game_engine_gamehub), null),
            EngineOption(EngineType.PSP, context.getString(R.string.game_engine_psp), null),
            EngineOption(EngineType.NINTENDO_3DS, context.getString(R.string.game_engine_nintendo_3ds), null),
            EngineOption(EngineType.NINTENDO_SWITCH, context.getString(R.string.game_engine_nintendo_switch), null),
            EngineOption(EngineType.RPGMAKER, context.getString(R.string.game_engine_rpgmaker_xp), "rpgmxp"),
            EngineOption(EngineType.RPGMAKER, context.getString(R.string.game_engine_rpgmaker_vx), "rpgmvx"),
            EngineOption(EngineType.RPGMAKER, context.getString(R.string.game_engine_rpgmaker_vxace), "rpgmvxace"),
            EngineOption(EngineType.RPGMAKER, context.getString(R.string.game_engine_rpgmaker_mkxp), "mkxp-z"),
            EngineOption(EngineType.RENPY, context.getString(R.string.game_engine_renpy), "renpy"),
            EngineOption(EngineType.GODOT, context.getString(R.string.game_engine_godot_auto), "godot4")
        )
        if (!includeUnknown) return base
        return base + arrayOf(
            EngineOption(EngineType.UNKNOWN, context.getString(R.string.game_common_unknown), null)
        )
    }
}
