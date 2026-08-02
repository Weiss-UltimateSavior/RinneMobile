package com.apps.game;

import com.core.launcherbridge.LauncherScanBridge;
import com.core.model.EngineType;

import java.util.Locale;

/** Single source of truth for default emulator package/subtype routing. */
final class EnginePackageResolver {
    private EnginePackageResolver() {
    }

    static String defaultPackage(EngineType engine) {
        if (engine == EngineType.KIRIKIRI) return "internal.krkr";
        if (engine == EngineType.ONS) return "internal.ons";
        if (engine == EngineType.TYRANO) return "internal.tyrano";
        if (engine == EngineType.ARTEMIS) return "internal.artemis";
        if (engine == EngineType.PSP) return "org.ppsspp.ppsspp";
        if (engine == EngineType.NINTENDO_3DS) return "io.github.azaharplus.android";
        if (engine == EngineType.NINTENDO_SWITCH) return "dev.eden.eden_emulator";
        if (engine == EngineType.GAMEHUB) return "com.xiaoji.egggame";
        if (engine == EngineType.RPGMAKER) return "internal.rpgmxp";
        if (engine == EngineType.RENPY) return "internal.renpy";
        if (engine == EngineType.GODOT) return "internal.godot";
        return "";
    }

    static String forDetection(EngineType engine, LauncherScanBridge.DetectionResult detected) {
        String fallback = defaultPackage(engine);
        if (detected == null) return fallback;
        String subtype = null;
        if (engine == EngineType.RPGMAKER) {
            subtype = detected.rpgMakerSubtype;
        } else if (engine == EngineType.RENPY) {
            subtype = detected.renpySubtype;
        } else if (engine == EngineType.GODOT) {
            subtype = detected.godotSubtype;
        }
        if (subtype == null || subtype.trim().isEmpty()) return fallback;
        return "internal." + subtype.trim();
    }

    static String forOption(EngineOption option) {
        if (option == null) return "";
        String subtype = subtypeForOption(option);
        if (!subtype.isEmpty()) return "internal." + subtype;
        return defaultPackage(option.engine);
    }

    static String subtypeForOption(EngineOption option) {
        if (option == null) return "";
        if (option.engine != EngineType.RPGMAKER
                && option.engine != EngineType.RENPY
                && option.engine != EngineType.GODOT) return "";
        return option.rpgMakerSubtype == null ? "" : option.rpgMakerSubtype;
    }

    static EngineOption findOption(EngineOption[] options, EngineType engine, String emulatorPackage) {
        if (options == null || options.length == 0) return null;
        if (engine == null) return options[0];
        String pkg = emulatorPackage == null ? "" : emulatorPackage.trim().toLowerCase(Locale.ROOT);
        EngineOption fallback = null;
        for (EngineOption option : options) {
            if (option.engine != engine) continue;
            if (engine == EngineType.RPGMAKER || engine == EngineType.RENPY
                    || engine == EngineType.GODOT) {
                String subtype = option.rpgMakerSubtype;
                if (subtype == null || subtype.isEmpty()) {
                    if (fallback == null) fallback = option;
                    continue;
                }
                String alias = "internal." + subtype;
                if (alias.equals(pkg) || ("internal." + subtype.replace("-", ""))
                        .equals(pkg.replace("-", ""))) {
                    return option;
                }
                if (fallback == null) fallback = option;
            } else {
                return option;
            }
        }
        return fallback != null ? fallback : options[0];
    }
}
