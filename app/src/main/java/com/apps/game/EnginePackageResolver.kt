package com.apps.game

import com.core.launcher.EnginePackages
import com.core.launcherbridge.LauncherScanBridge
import com.core.model.EngineType
import java.util.Locale

/** Single source of truth for default emulator package/subtype routing. */
internal object EnginePackageResolver {
    @JvmStatic
    fun defaultPackage(engine: EngineType): String {
        if (engine == EngineType.KIRIKIRI) return EnginePackages.INTERNAL_KRKR
        if (engine == EngineType.ONS) return EnginePackages.INTERNAL_ONS
        if (engine == EngineType.TYRANO) return EnginePackages.INTERNAL_TYRANO
        if (engine == EngineType.ARTEMIS) return EnginePackages.INTERNAL_ARTEMIS
        if (engine == EngineType.PSP) return EnginePackages.EXTERNAL_PPSSPP
        if (engine == EngineType.NINTENDO_3DS) return EnginePackages.EXTERNAL_AZAHAR
        if (engine == EngineType.NINTENDO_SWITCH) return EnginePackages.EXTERNAL_EDEN
        if (engine == EngineType.GAMEHUB) return EnginePackages.EXTERNAL_GAMEHUB
        if (engine == EngineType.RPGMAKER) return EnginePackages.INTERNAL_RPGMAKER_XP
        if (engine == EngineType.RENPY) return EnginePackages.INTERNAL_RENPY
        if (engine == EngineType.GODOT) return EnginePackages.INTERNAL_GODOT
        return ""
    }

    @JvmStatic
    fun forDetection(engine: EngineType, detected: LauncherScanBridge.DetectionResult?): String {
        val fallback = defaultPackage(engine)
        if (detected == null) return fallback
        var subtype: String? = null
        if (engine == EngineType.RPGMAKER) {
            subtype = detected.rpgMakerSubtype
        } else if (engine == EngineType.RENPY) {
            subtype = detected.renpySubtype
        } else if (engine == EngineType.GODOT) {
            subtype = detected.godotSubtype
        }
        if (subtype == null || subtype.trim().isEmpty()) return fallback
        return "internal." + subtype.trim()
    }

    @JvmStatic
    fun forOption(option: EngineOption?): String {
        if (option == null) return ""
        val subtype = subtypeForOption(option)
        if (subtype.isNotEmpty()) return "internal." + subtype
        return defaultPackage(option.engine)
    }

    @JvmStatic
    fun subtypeForOption(option: EngineOption?): String {
        if (option == null) return ""
        if (option.engine != EngineType.RPGMAKER
            && option.engine != EngineType.RENPY
            && option.engine != EngineType.GODOT) return ""
        return option.rpgMakerSubtype ?: ""
    }

    @JvmStatic
    fun findOption(
        options: Array<EngineOption>?,
        engine: EngineType?,
        emulatorPackage: String?
    ): EngineOption? {
        if (options == null || options.isEmpty()) return null
        if (engine == null) return options[0]
        val pkg = if (emulatorPackage == null) "" else emulatorPackage.trim().lowercase(Locale.ROOT)
        var fallback: EngineOption? = null
        for (option in options) {
            if (option.engine != engine) continue
            if (engine == EngineType.RPGMAKER || engine == EngineType.RENPY
                || engine == EngineType.GODOT) {
                val subtype = option.rpgMakerSubtype
                if (subtype == null || subtype.isEmpty()) {
                    if (fallback == null) fallback = option
                    continue
                }
                val alias = "internal." + subtype
                if (alias == pkg || "internal." + subtype.replace("-", "")
                    == pkg.replace("-", "")) {
                    return option
                }
                if (fallback == null) fallback = option
            } else {
                return option
            }
        }
        return fallback ?: options[0]
    }
}
