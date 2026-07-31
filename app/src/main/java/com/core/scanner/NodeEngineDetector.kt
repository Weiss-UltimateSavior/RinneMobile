package com.core.scanner

import com.core.model.EngineType
import java.util.Locale

/** Engine detection over cached [ScanNode] metadata; it performs no per-entry provider query. */
internal object NodeEngineDetector {
    fun detect(
        rootFiles: List<ScanNode>,
        featureDepth: Int = 2,
        loadChildren: (ScanNode) -> List<ScanNode>
    ): EngineDetector.Result {
        val state = FeatureState()
        collect(rootFiles, "", "", 1, featureDepth.coerceIn(1, 4), state, loadChildren)
        return score(state)
    }

    private fun collect(
        files: List<ScanNode>,
        lowerPrefix: String,
        originalPrefix: String,
        level: Int,
        maxLevel: Int,
        state: FeatureState,
        loadChildren: (ScanNode) -> List<ScanNode>
    ) {
        if (files.isEmpty()) return
        val directories = ArrayList<Candidate>()
        for (node in files) {
            val original = node.name
            val lower = original.lowercase(Locale.ROOT)
            val relative = if (lowerPrefix.isEmpty()) lower else "$lowerPrefix/$lower"
            val originalRelative = if (originalPrefix.isEmpty()) original else "$originalPrefix/$original"
            state.empty = false
            state.names.add(lower)
            state.relativeNames.add(relative)
            if (node.isDirectory) {
                state.directories.add(lower)
                directories.add(Candidate(node, lower, relative, originalRelative))
                continue
            }
            state.recordFile(lower, relative, originalRelative)
        }
        if (level >= maxLevel) return
        for (candidate in directories) {
            if (!shouldDescend(candidate.name, state)) continue
            collect(
                loadChildren(candidate.node),
                candidate.relative,
                candidate.originalRelative,
                level + 1,
                maxLevel,
                state,
                loadChildren
            )
        }
    }

    private fun shouldDescend(name: String, state: FeatureState): Boolean = when (name) {
        "resources" -> true
        "app" -> "resources" in state.directories
        "tyrano" -> state.hasIndex
        "data" -> EngineFeatureTraversal.shouldDescendIntoData(
            hasIndex = state.hasIndex,
            hasGameIni = state.hasGameIni
        )
        "system" -> state.hasSystemIni
        "game" -> true
        else -> false
    }

    private fun score(s: FeatureState): EngineDetector.Result {
        val result = EngineDetector.Result()
        if (s.empty) return result
        s.xp3Files.sortWith(String.CASE_INSENSITIVE_ORDER)
        s.gameNamedXp3Files.sortWith(String.CASE_INSENSITIVE_ORDER)
        val firstXp3 = when {
            s.gameNamedXp3Files.size == 1 -> s.gameNamedXp3Files.first()
            s.dataXp3 != null -> s.dataXp3
            s.xp3Files.isNotEmpty() -> s.xp3Files.first()
            else -> null
        }
        val tyranoRuntime = "tyrano" in s.directories || "data" in s.directories ||
            "tyrano.css" in s.names || "tyrano.base.js" in s.names ||
            "tyrano/tyrano.css" in s.relativeNames || "tyrano/tyrano.base.js" in s.relativeNames ||
            "tyrano/libs/jquery-3.6.0.min.js" in s.relativeNames ||
            "tyrano/libs/jquery-2.0.3.min.js" in s.relativeNames
        val electronWrapper = "resources" in s.directories &&
            (s.hasAppAsar || s.hasElectronPak || "icudtl.dat" in s.names ||
                "libegl.dll" in s.names || "libglesv2.dll" in s.names)
        val artemisRuntime = (s.hasSystemIni && s.hasFirstIet) || s.hasRootPfs || s.hasAnyPfs

        when {
            s.hasIndex && tyranoRuntime -> set(result, EngineType.TYRANO, 96, "[游戏目录]")
            s.hasAppAsar && (s.hasPackageJson || electronWrapper) ->
                set(result, EngineType.TYRANO, 72, "[游戏目录]")
            s.hasIndex && !electronWrapper -> set(result, EngineType.TYRANO, 70, "[游戏目录]")
            artemisRuntime -> set(
                result, EngineType.ARTEMIS,
                if ((s.hasSystemIni && s.hasFirstIet) || s.hasRootPfs) 95 else 90,
                "[游戏目录]"
            )
            firstXp3 != null || s.hasStartupTjs || s.hasConfigTjs -> {
                set(result, EngineType.KIRIKIRI, if (firstXp3 != null) 95 else 80, firstXp3 ?: "[游戏目录]")
                if (s.gameNamedXp3Files.size > 1 ||
                    (s.gameNamedXp3Files.isEmpty() && s.dataXp3 == null && s.xp3Files.size > 1)
                ) {
                    result.xp3Candidates.addAll(s.xp3Files)
                }
            }
            s.hasOnsScript || s.hasOnsArchive ->
                set(result, EngineType.ONS, if (s.hasOnsScript) 90 else 70, "[游戏目录]")
        }
        when {
            s.firstRgss3a != null -> setSubtype(result, EngineType.RPGMAKER, "rpgmvxace", 96, s.firstRgss3a!!)
            s.firstRgss2a != null -> setSubtype(result, EngineType.RPGMAKER, "rpgmvx", 96, s.firstRgss2a!!)
            s.firstRgssad != null -> setSubtype(result, EngineType.RPGMAKER, "rpgmxp", 96, s.firstRgssad!!)
            s.hasGameIni && s.hasRvdata2 -> setSubtype(result, EngineType.RPGMAKER, "rpgmvxace", 92, "[游戏目录]")
            s.hasGameIni && s.hasRvdata -> setSubtype(result, EngineType.RPGMAKER, "rpgmvx", 92, "[游戏目录]")
            s.hasGameIni && s.hasRxdata -> setSubtype(result, EngineType.RPGMAKER, "rpgmxp", 92, "[游戏目录]")
        }
        when {
            s.firstRpa != null -> setSubtype(result, EngineType.RENPY, "renpy", 96, s.firstRpa!!)
            s.hasGameScriptRpy || s.hasOptionsRpy ->
                setSubtype(result, EngineType.RENPY, "renpy", 94, "[游戏目录]")
            "renpy" in s.directories && (s.hasRpy || s.hasRpyc) ->
                setSubtype(result, EngineType.RENPY, "renpy", 90, "[游戏目录]")
            "game" in s.directories && s.hasRpy ->
                setSubtype(result, EngineType.RENPY, "renpy", 85, "[游戏目录]")
        }
        when {
            s.firstPck != null -> setSubtype(result, EngineType.GODOT, "godot4", 96, s.firstPck!!)
            s.hasProjectGodot -> setSubtype(result, EngineType.GODOT, "godot4", 94, "[游戏目录]")
        }
        return result
    }

    private fun set(result: EngineDetector.Result, engine: EngineType, confidence: Int, target: String) {
        if (confidence <= result.confidence) return
        result.engine = engine
        result.confidence = confidence
        result.launchTarget = target
    }

    private fun setSubtype(
        result: EngineDetector.Result,
        engine: EngineType,
        subtype: String,
        confidence: Int,
        target: String
    ) {
        if (confidence <= result.confidence) return
        set(result, engine, confidence, target)
        when (engine) {
            EngineType.RPGMAKER -> result.rpgMakerSubtype = subtype
            EngineType.RENPY -> result.renpySubtype = subtype
            EngineType.GODOT -> result.godotSubtype = subtype
            else -> Unit
        }
    }

    private data class Candidate(
        val node: ScanNode,
        val name: String,
        val relative: String,
        val originalRelative: String
    )

    private class FeatureState {
        var empty = true
        val names = HashSet<String>()
        val relativeNames = HashSet<String>()
        val directories = HashSet<String>()
        val xp3Files = ArrayList<String>()
        val gameNamedXp3Files = ArrayList<String>()
        var dataXp3: String? = null
        var hasIndex = false
        var hasStartupTjs = false
        var hasConfigTjs = false
        var hasSystemIni = false
        var hasFirstIet = false
        var hasRootPfs = false
        var hasAnyPfs = false
        var hasOnsScript = false
        var hasOnsArchive = false
        var hasAppAsar = false
        var hasPackageJson = false
        var hasElectronPak = false
        var firstRgssad: String? = null
        var firstRgss2a: String? = null
        var firstRgss3a: String? = null
        var hasGameIni = false
        var hasRxdata = false
        var hasRvdata = false
        var hasRvdata2 = false
        var firstRpa: String? = null
        var hasRpy = false
        var hasRpyc = false
        var hasGameScriptRpy = false
        var hasOptionsRpy = false
        var firstPck: String? = null
        var hasProjectGodot = false

        fun recordFile(lower: String, relative: String, originalRelative: String) {
            if (lower == "index.html" || lower == "index.htm") hasIndex = true
            if (lower == "startup.tjs") hasStartupTjs = true
            if (lower == "config.tjs") hasConfigTjs = true
            if (lower == "system.ini") hasSystemIni = true
            if (relative == "system/first.iet" || relative.endsWith("/system/first.iet")) hasFirstIet = true
            if (lower == "root.pfs") hasRootPfs = true
            if (lower.endsWith(".pfs")) hasAnyPfs = true
            if (lower == "0.txt" || lower == "00.txt" || lower == "nscr_sec.dat" ||
                lower == "nscript.dat" || lower == "onscript.nt2" || lower == "onscript.nt3"
            ) hasOnsScript = true
            if (lower.endsWith(".nsa") || lower.endsWith(".sar")) hasOnsArchive = true
            if (lower == "app.asar" || relative.endsWith("/app.asar")) hasAppAsar = true
            if (lower == "package.json" || relative.endsWith("/package.json")) hasPackageJson = true
            if (lower.startsWith("chrome_") && lower.endsWith(".pak")) hasElectronPak = true
            if (lower.endsWith(".xp3")) {
                xp3Files.add(originalRelative)
                if (lower.contains("游戏")) gameNamedXp3Files.add(originalRelative)
                if (lower == "data.xp3" && dataXp3 == null) dataXp3 = originalRelative
            }
            if (lower == "game.ini") hasGameIni = true
            if (lower.endsWith(".rgssad") && firstRgssad == null) firstRgssad = originalRelative
            if (lower.endsWith(".rgss2a") && firstRgss2a == null) firstRgss2a = originalRelative
            if (lower.endsWith(".rgss3a") && firstRgss3a == null) firstRgss3a = originalRelative
            if (lower.endsWith(".rxdata")) hasRxdata = true
            if (lower.endsWith(".rvdata") && !lower.endsWith(".rvdata2")) hasRvdata = true
            if (lower.endsWith(".rvdata2")) hasRvdata2 = true
            if (lower.endsWith(".rpa") && firstRpa == null) firstRpa = originalRelative
            if (lower.endsWith(".rpy")) {
                hasRpy = true
                if (relative == "game/script.rpy" || relative.endsWith("/game/script.rpy")) hasGameScriptRpy = true
                if (relative == "game/options.rpy" || relative.endsWith("/game/options.rpy")) hasOptionsRpy = true
            }
            if (lower.endsWith(".rpyc")) hasRpyc = true
            if (lower == "project.godot") hasProjectGodot = true
            if (lower.endsWith(".pck") && firstPck == null) firstPck = originalRelative
        }
    }
}
