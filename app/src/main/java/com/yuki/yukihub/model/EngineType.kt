package com.yuki.yukihub.model

enum class EngineType(val displayName: String) {
    AUTO("Auto"),
    KIRIKIRI("Kirikiri"),
    ONS("ONScripter"),
    TYRANO("Tyrano"),
    ARTEMIS("Artemis"),
    WINLATOR("Winlator"),
    GAMEHUB("GameHub"),
    PSP("PSP"),
    NINTENDO_3DS("Nintendo 3DS"),
    RPGMAKER("RPG Maker"),
    RENPY("Ren'Py"),
    GODOT("Godot"),
    UNKNOWN("Unknown");

    companion object {
        @JvmStatic
        fun fromString(value: String?): EngineType {
            if (value == null) return UNKNOWN
            return entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) ||
                    it.displayName.equals(value, ignoreCase = true)
            } ?: UNKNOWN
        }
    }
}
