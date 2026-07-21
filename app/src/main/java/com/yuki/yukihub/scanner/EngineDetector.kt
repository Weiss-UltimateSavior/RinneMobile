package com.yuki.yukihub.scanner

import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yuki.yukihub.model.EngineType
import java.util.Locale

/**
 * Lightweight engine detector.
 *
 * 扫描器会控制"游戏目录搜索深度"，这里控制"候选游戏目录内部的特征探测深度"。
 * 为了兼容 Tyrano/Electron 壳包装游戏，默认允许查看候选目录下较浅层级的特征，
 * 例如 resources/app.asar、resources/app/package.json、tyrano/、data/ 等。
 */
object EngineDetector {

    private const val TAG = "EngineDetector"

    class Result {
        @JvmField var engine: EngineType = EngineType.UNKNOWN
        @JvmField var confidence: Int = 0
        @JvmField var launchTarget: String = ""

        /**
         * 当目录内没有 data.xp3 且存在多个 XP3 时，由调用方让用户选择的候选入口。
         */
        @JvmField var xp3Candidates: MutableList<String> = ArrayList()

        /**
         * 仅当 engine == RPGMAKER 时有意义。取值：
         * "rpgmxp" / "rpgmvx" / "rpgmvxace" / "mkxp-z"。空串表示需用户自行决定。
         */
        @JvmField var rpgMakerSubtype: String = ""

        /**
         * 仅当 engine == RENPY 时有意义。取值："renpy" 或 "renpy8"。
         */
        @JvmField var renpySubtype: String = ""

        /**
         * 仅当 engine == GODOT 时有意义。取值："godot4"。空串表示需用户自行决定。
         */
        @JvmField var godotSubtype: String = ""
    }

    @JvmStatic
    fun detect(dir: DocumentFile?): Result {
        return detect(dir, 2)
    }

    @JvmStatic
    fun detect(dir: DocumentFile?, featureDepth: Int): Result {
        return detect(dir, featureDepth, null)
    }

    @JvmStatic
    fun detect(dir: DocumentFile?, featureDepth: Int, rootFiles: Array<DocumentFile>?): Result {
        val r = Result()
        if (dir == null) return r
        val depth = maxOf(1, minOf(4, featureDepth))

        val s = FeatureState()
        collectFeatures(dir, "", "", 1, depth, s, rootFiles)
        if (s.empty) return r
        s.xp3Files.sortWith(String.CASE_INSENSITIVE_ORDER)
        s.gameNamedXp3Files.sortWith(String.CASE_INSENSITIVE_ORDER)
        // 游戏名中明确包含"游戏"的归档通常是 Kirikiri 的启动归档，优先于 data.xp3。
        // 同名规则命中多个时保留候选列表，由扫描页要求用户确认，不能按文件顺序猜测。
        s.firstXp3 = when {
            s.gameNamedXp3Files.size == 1 -> s.gameNamedXp3Files[0]
            s.dataXp3 != null -> s.dataXp3
            s.xp3Files.isNotEmpty() -> s.xp3Files[0]
            else -> null
        }

        // 只对 Artemis 使用原 Tyranor 的判定：
        // system.ini + system/first.iet、root.pfs、或目录内任意 .pfs 都视为 Artemis。
        val tyranoRuntime = s.hasTyranoDir || s.hasDataDir || s.names.contains("tyrano.css") || s.names.contains("tyrano.base.js")
            || s.relativeNames.contains("tyrano/tyrano.css") || s.relativeNames.contains("tyrano/tyrano.base.js")
            || s.relativeNames.contains("tyrano/libs/jquery-3.6.0.min.js") || s.relativeNames.contains("tyrano/libs/jquery-2.0.3.min.js")
        val electronWrapper = s.hasResourcesDir && (s.hasAppAsar || s.hasElectronPak || s.names.contains("icudtl.dat") || s.names.contains("libegl.dll") || s.names.contains("libglesv2.dll"))
        val artemisRuntime = (s.hasSystemIni && s.hasFirstIet) || s.hasRootPfs || s.hasAnyPfsFile

        if (s.hasIndex && tyranoRuntime) {
            score(r, EngineType.TYRANO, 96, "[游戏目录]")
        } else if (s.hasAppAsar && (s.hasPackageJson || electronWrapper)) {
            score(r, EngineType.TYRANO, 72, "[游戏目录]")
        } else if (s.hasIndex && !electronWrapper) {
            score(r, EngineType.TYRANO, 70, "[游戏目录]")
        } else if (artemisRuntime) {
            score(r, EngineType.ARTEMIS, if ((s.hasSystemIni && s.hasFirstIet) || s.hasRootPfs) 95 else 90, "[游戏目录]")
        } else if (s.firstXp3 != null || s.hasStartupTjs || s.hasConfigTjs) {
            score(r, EngineType.KIRIKIRI, if (s.firstXp3 != null) 95 else 80, s.firstXp3 ?: "[游戏目录]")
            if (s.gameNamedXp3Files.size > 1
                || (s.gameNamedXp3Files.isEmpty() && s.dataXp3 == null && s.xp3Files.size > 1)
            ) {
                r.xp3Candidates.addAll(s.xp3Files)
            }
        } else if (s.hasOnsScript || s.hasOnsArchive) {
            score(r, EngineType.ONS, if (s.hasOnsScript) 90 else 70, "[游戏目录]")
        } else if (s.firstDesktop != null) {
            score(r, EngineType.WINLATOR, 90, s.firstDesktop!!)
        } else if (s.firstPspFile != null) {
            score(r, EngineType.PSP, 95, s.firstPspFile!!)
        } else if (s.firstN3dsFile != null) {
            score(r, EngineType.NINTENDO_3DS, 95, s.firstN3dsFile!!)
        }
        // RPG Maker (RGSS) 识别：归档形式优先于散文件形式。
        // rgss3a → VX Ace(RGSS3)；rgss2a → VX(RGSS2)；rgssad → XP(RGSS1)。
        if (s.firstRgss3a != null) {
            scoreRpgMaker(r, "rpgmvxace", 96, s.firstRgss3a!!)
        } else if (s.firstRgss2a != null) {
            scoreRpgMaker(r, "rpgmvx", 96, s.firstRgss2a!!)
        } else if (s.firstRgssad != null) {
            scoreRpgMaker(r, "rpgmxp", 96, s.firstRgssad!!)
        } else if (s.hasGameIni && s.hasRvdata2) {
            scoreRpgMaker(r, "rpgmvxace", 92, "[游戏目录]")
        } else if (s.hasGameIni && s.hasRvdata) {
            scoreRpgMaker(r, "rpgmvx", 92, "[游戏目录]")
        } else if (s.hasGameIni && s.hasRxdata) {
            scoreRpgMaker(r, "rpgmxp", 92, "[游戏目录]")
        }
        // Ren'Py 识别：.rpa 归档优先，其次 game/script.rpy + game/options.rpy
        if (s.firstRpa != null) {
            scoreRenpy(r, "renpy", 96, s.firstRpa!!)
        } else if (s.hasGameScriptRpy || s.hasOptionsRpy) {
            scoreRenpy(r, "renpy", 94, "[游戏目录]")
        } else if (s.hasRenpyDir && (s.hasRpy || s.hasRpyc)) {
            scoreRenpy(r, "renpy", 90, "[游戏目录]")
        } else if (s.hasGameDir && s.hasRpy) {
            scoreRenpy(r, "renpy", 85, "[游戏目录]")
        }
        // Godot 识别：.pck 归档优先，其次 project.godot 文件。
        // 标准 Godot 项目根目录会有 project.godot 文本配置，或打包为 .pck。
        if (s.firstPck != null) {
            scoreGodot(r, "godot4", 96, s.firstPck!!)
        } else if (s.hasProjectGodot) {
            scoreGodot(r, "godot4", 94, "[游戏目录]")
        }
        return r
    }

    /**
     * 与 [score] 行为一致，但额外写入 [Result.rpgMakerSubtype]。
     * 仅当 confidence 高于当前值时覆盖，避免被弱特征覆盖强特征。
     */
    private fun scoreRpgMaker(r: Result?, subtype: String?, confidence: Int, launchTarget: String?) {
        if (r == null || subtype.isNullOrEmpty()) return
        if (confidence > r.confidence) {
            r.engine = EngineType.RPGMAKER
            r.confidence = confidence
            r.launchTarget = launchTarget ?: ""
            r.rpgMakerSubtype = subtype
        }
    }

    /**
     * 与 [score] 行为一致，但额外写入 [Result.renpySubtype]。
     */
    private fun scoreRenpy(r: Result?, subtype: String?, confidence: Int, launchTarget: String?) {
        if (r == null || subtype.isNullOrEmpty()) return
        if (confidence > r.confidence) {
            r.engine = EngineType.RENPY
            r.confidence = confidence
            r.launchTarget = launchTarget ?: ""
            r.renpySubtype = subtype
        }
    }

    /**
     * 与 [score] 行为一致，但额外写入 [Result.godotSubtype]。
     */
    private fun scoreGodot(r: Result?, subtype: String?, confidence: Int, launchTarget: String?) {
        if (r == null || subtype.isNullOrEmpty()) return
        if (confidence > r.confidence) {
            r.engine = EngineType.GODOT
            r.confidence = confidence
            r.launchTarget = launchTarget ?: ""
            r.godotSubtype = subtype
        }
    }

    private class FeatureState {
        var empty = true
        val names = HashSet<String>()
        val relativeNames = HashSet<String>()
        val xp3Files = ArrayList<String>()
        val gameNamedXp3Files = ArrayList<String>()
        var firstXp3: String? = null
        var dataXp3: String? = null
        var firstDesktop: String? = null
        var hasIndex = false
        var hasTyranoDir = false
        var hasDataDir = false
        var hasResourcesDir = false
        var hasScenarioDir = false
        var hasSystemDir = false
        var hasBgimageDir = false
        var hasFgimageDir = false
        var hasImageDir = false
        var hasSoundDir = false
        var hasBgmDir = false
        var hasVoiceDir = false
        var hasVideoDir = false
        var hasMovieDir = false
        var hasFontDir = false
        var hasOthersDir = false
        var hasStartupTjs = false
        var hasConfigTjs = false
        var hasKsScript = false
        var hasTjsScript = false
        var hasSystemIni = false
        var hasFirstIet = false
        var hasRootPfs = false
        var hasAnyPfsFile = false
        var hasOnsScript = false
        var hasOnsArchive = false
        var hasPfs = false
        var hasAppAsar = false
        var hasPackageJson = false
        var hasElectronPak = false
        var firstPspFile: String? = null
        var firstN3dsFile: String? = null
        // RPG Maker (RGSS) 检测字段。
        var firstRgssad: String? = null
        var firstRgss2a: String? = null
        var firstRgss3a: String? = null
        var hasGameIni = false
        var hasRxdata = false
        var hasRvdata = false
        var hasRvdata2 = false
        // Ren'Py 检测字段
        var firstRpa: String? = null
        var hasRpy = false
        var hasRpyc = false
        var hasRenpyDir = false
        var hasGameDir = false
        var hasGameScriptRpy = false
        var hasOptionsRpy = false
        // Godot 检测字段
        var firstPck: String? = null
        var hasProjectGodot = false
    }

    private fun collectFeatures(
        dir: DocumentFile?, lowerPrefix: String, originalPrefix: String,
        level: Int, maxLevel: Int, s: FeatureState, knownFiles: Array<DocumentFile>?
    ) {
        val files: Array<DocumentFile>?
        try {
            if (dir == null || !dir.isDirectory) return
            files = knownFiles ?: dir.listFiles()
        } catch (t: Throwable) {
            Log.w(TAG, "detect list failed uri=" + safeUri(dir), t)
            return
        }
        if (files == null || files.isEmpty()) return

        for (f in files) {
            if (f == null) continue
            val lower = safeLowerName(f)
            val original = safeName(f)
            if (lower.isEmpty()) continue
            val rel = if (lowerPrefix.isEmpty()) lower else "$lowerPrefix/$lower"
            val originalRel = if (originalPrefix.isEmpty()) original else "$originalPrefix/$original"
            s.empty = false
            s.names.add(lower)
            s.relativeNames.add(rel)

            var directory = false
            var file = false
            try { directory = f.isDirectory } catch (_: Throwable) { }
            try { file = f.isFile } catch (_: Throwable) { }

            if (directory) {
                if (lower == "tyrano") s.hasTyranoDir = true
                if (lower == "data") s.hasDataDir = true
                if (lower == "game") s.hasGameDir = true
                if (lower == "renpy") s.hasRenpyDir = true
                if (lower == "resources") s.hasResourcesDir = true
                if (lower == "scenario") s.hasScenarioDir = true
                if (lower == "system") s.hasSystemDir = true
                if (lower == "bgimage") s.hasBgimageDir = true
                if (lower == "fgimage") s.hasFgimageDir = true
                if (lower == "image") s.hasImageDir = true
                if (lower == "sound") s.hasSoundDir = true
                if (lower == "bgm") s.hasBgmDir = true
                if (lower == "voice") s.hasVoiceDir = true
                if (lower == "video") s.hasVideoDir = true
                if (lower == "movie") s.hasMovieDir = true
                if (lower == "font") s.hasFontDir = true
                if (lower == "others") s.hasOthersDir = true
                if (level < maxLevel && shouldDescendForFeature(lower)) {
                    collectFeatures(f, rel, originalRel, level + 1, maxLevel, s, null)
                }
                continue
            }
            if (!file) continue

            if (lower == "index.html" || lower == "index.htm") s.hasIndex = true
            if (lower == "startup.tjs") s.hasStartupTjs = true
            if (lower == "config.tjs") s.hasConfigTjs = true
            if (lower == "system.ini") s.hasSystemIni = true
            if (rel == "system/first.iet" || rel.endsWith("/system/first.iet")) s.hasFirstIet = true
            if (lower == "root.pfs") s.hasRootPfs = true
            if (lower.endsWith(".pfs")) s.hasAnyPfsFile = true
            if (lower.endsWith(".ks")) s.hasKsScript = true
            if (lower.endsWith(".tjs") && lower != "startup.tjs" && lower != "config.tjs") s.hasTjsScript = true
            if (lower == "0.txt" || lower == "00.txt" || lower == "nscr_sec.dat" || lower == "nscript.dat" || lower == "onscript.nt2" || lower == "onscript.nt3") s.hasOnsScript = true
            if (lower.endsWith(".nsa") || lower.endsWith(".sar")) s.hasOnsArchive = true
            if (lower.endsWith(".pfs")) { s.hasPfs = true; s.hasAnyPfsFile = true }
            if (lower == "app.asar" || rel.endsWith("/app.asar")) s.hasAppAsar = true
            if (lower == "package.json" || rel.endsWith("/package.json")) s.hasPackageJson = true
            if (lower.startsWith("chrome_") && lower.endsWith(".pak")) s.hasElectronPak = true
            if (lower.endsWith(".desktop") && s.firstDesktop == null) s.firstDesktop = originalRel
            if (lower.endsWith(".xp3")) {
                // Keep the provider's exact spelling for launch resolution while all feature
                // comparisons continue to use the lower-cased relative path above.
                val xp3Path = originalRel
                s.xp3Files.add(xp3Path)
                if (lower.contains("游戏")) s.gameNamedXp3Files.add(xp3Path)
                if (lower == "data.xp3" && s.dataXp3 == null) s.dataXp3 = xp3Path
            }
            // PSP游戏文件检测
            if (lower.endsWith(".iso") || lower.endsWith(".cso") || lower.endsWith(".chd") ||
                lower.endsWith(".elf") || lower.endsWith(".pbp")
            ) {
                if (s.firstPspFile == null) s.firstPspFile = originalRel
            }
            // Nintendo 3DS 游戏文件检测
            // 注意:不包含 .elf(PSP 已占用)和 .app(过于通用),避免歧义
            if (lower.endsWith(".3ds") || lower.endsWith(".cci") || lower.endsWith(".zcci") ||
                lower.endsWith(".cxi") || lower.endsWith(".zcxi") || lower.endsWith(".cia") ||
                lower.endsWith(".zcia") || lower.endsWith(".3dsx") || lower.endsWith(".z3dsx")
            ) {
                if (s.firstN3dsFile == null) s.firstN3dsFile = originalRel
            }
            // RPG Maker (RGSS) 归档与数据文件检测。
            if (lower == "game.ini") s.hasGameIni = true
            if (lower.endsWith(".rgssad") && s.firstRgssad == null) s.firstRgssad = originalRel
            if (lower.endsWith(".rgss2a") && s.firstRgss2a == null) s.firstRgss2a = originalRel
            if (lower.endsWith(".rgss3a") && s.firstRgss3a == null) s.firstRgss3a = originalRel
            // 散文件形式的 RPG Maker 数据文件（不在归档内）。
            if (lower.endsWith(".rxdata")) s.hasRxdata = true
            if (lower.endsWith(".rvdata") && !lower.endsWith(".rvdata2")) s.hasRvdata = true
            if (lower.endsWith(".rvdata2")) s.hasRvdata2 = true
            // Ren'Py 检测
            if (lower.endsWith(".rpa") && s.firstRpa == null) s.firstRpa = originalRel
            if (lower.endsWith(".rpy")) {
                s.hasRpy = true
                if (rel == "game/script.rpy" || rel.endsWith("/game/script.rpy")) s.hasGameScriptRpy = true
                if (rel == "game/options.rpy" || rel.endsWith("/game/options.rpy")) s.hasOptionsRpy = true
            }
            if (lower.endsWith(".rpyc")) s.hasRpyc = true
            // Godot 项目文件检测
            if (lower == "project.godot") s.hasProjectGodot = true
            if (lower.endsWith(".pck") && s.firstPck == null) s.firstPck = originalRel
        }
    }

    private fun shouldDescendForFeature(lowerName: String?): Boolean {
        if (lowerName == null) return false
        return lowerName == "resources" || lowerName == "app" || lowerName == "tyrano"
            || lowerName == "data" || lowerName == "scenario" || lowerName == "system"
            || lowerName == "game"
    }

    private fun safeName(file: DocumentFile?): String {
        return try {
            val name = file?.name
            name ?: ""
        } catch (t: Throwable) {
            Log.w(TAG, "getName failed uri=" + safeUri(file), t)
            ""
        }
    }

    private fun safeLowerName(file: DocumentFile?): String {
        val name = safeName(file)
        return if (name.isEmpty()) "" else name.lowercase(Locale.ROOT)
    }

    private fun safeUri(file: DocumentFile?): String {
        return try {
            if (file?.uri == null) "null" else file.uri.toString()
        } catch (_: Throwable) {
            "unknown"
        }
    }

    private fun score(r: Result?, engine: EngineType, confidence: Int, launchTarget: String?) {
        if (r == null) return
        if (confidence > r.confidence) {
            r.engine = engine
            r.confidence = confidence
            r.launchTarget = launchTarget ?: ""
        }
    }
}
