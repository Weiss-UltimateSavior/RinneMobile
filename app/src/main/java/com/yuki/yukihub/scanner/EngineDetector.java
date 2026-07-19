package com.yuki.yukihub.scanner;

import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.yuki.yukihub.model.EngineType;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight engine detector.
 *
 * 扫描器会控制“游戏目录搜索深度”，这里控制“候选游戏目录内部的特征探测深度”。
 * 为了兼容 Tyrano/Electron 壳包装游戏，默认允许查看候选目录下较浅层级的特征，
 * 例如 resources/app.asar、resources/app/package.json、tyrano/、data/ 等。
 */
public class EngineDetector {
    private static final String TAG = "EngineDetector";

    public static class Result {
        public EngineType engine = EngineType.UNKNOWN;
        public int confidence = 0;
        public String launchTarget = "";
        /**
         * 当目录内没有 data.xp3 且存在多个 XP3 时，由调用方让用户选择的候选入口。
         */
        public List<String> xp3Candidates = new ArrayList<>();
        /**
         * 仅当 engine == RPGMAKER 时有意义。取值：
         * "rpgmxp" / "rpgmvx" / "rpgmvxace" / "mkxp-z"。空串表示需用户自行决定。
         */
        public String rpgMakerSubtype = "";
        /**
         * 仅当 engine == RENPY 时有意义。取值："renpy" 或 "renpy8"。
         */
        public String renpySubtype = "";
        /**
         * 仅当 engine == GODOT 时有意义。取值："godot4"。空串表示需用户自行决定。
         */
        public String godotSubtype = "";
    }

    public static Result detect(DocumentFile dir) {
        return detect(dir, 2);
    }

    public static Result detect(DocumentFile dir, int featureDepth) {
        return detect(dir, featureDepth, null);
    }

    public static Result detect(DocumentFile dir, int featureDepth, DocumentFile[] rootFiles) {
        Result r = new Result();
        if (dir == null) return r;
        int depth = Math.max(1, Math.min(4, featureDepth));

        FeatureState s = new FeatureState();
        collectFeatures(dir, "", "", 1, depth, s, rootFiles);
        if (s.empty) return r;
        Collections.sort(s.xp3Files, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(s.gameNamedXp3Files, String.CASE_INSENSITIVE_ORDER);
        // 游戏名中明确包含“游戏”的归档通常是 Kirikiri 的启动归档，优先于 data.xp3。
        // 同名规则命中多个时保留候选列表，由扫描页要求用户确认，不能按文件顺序猜测。
        s.firstXp3 = s.gameNamedXp3Files.size() == 1 ? s.gameNamedXp3Files.get(0)
                : s.dataXp3 != null ? s.dataXp3
                : (s.xp3Files.isEmpty() ? null : s.xp3Files.get(0));

        // 只对 Artemis 使用原 Tyranor 的判定：
        // system.ini + system/first.iet、root.pfs、或目录内任意 .pfs 都视为 Artemis。
        boolean tyranoRuntime = s.hasTyranoDir || s.hasDataDir || s.names.contains("tyrano.css") || s.names.contains("tyrano.base.js")
                || s.relativeNames.contains("tyrano/tyrano.css") || s.relativeNames.contains("tyrano/tyrano.base.js")
                || s.relativeNames.contains("tyrano/libs/jquery-3.6.0.min.js") || s.relativeNames.contains("tyrano/libs/jquery-2.0.3.min.js");
        boolean electronWrapper = s.hasResourcesDir && (s.hasAppAsar || s.hasElectronPak || s.names.contains("icudtl.dat") || s.names.contains("libegl.dll") || s.names.contains("libglesv2.dll"));
        boolean artemisRuntime = (s.hasSystemIni && s.hasFirstIet) || s.hasRootPfs || s.hasAnyPfsFile;

        if (s.hasIndex && tyranoRuntime) {
            score(r, EngineType.TYRANO, 96, "[游戏目录]");
        } else if (s.hasAppAsar && (s.hasPackageJson || electronWrapper)) {
            score(r, EngineType.TYRANO, 72, "[游戏目录]");
        } else if (s.hasIndex && !electronWrapper) {
            score(r, EngineType.TYRANO, 70, "[游戏目录]");
        } else if (artemisRuntime) {
            score(r, EngineType.ARTEMIS, (s.hasSystemIni && s.hasFirstIet) || s.hasRootPfs ? 95 : 90, "[游戏目录]");
        } else if (s.firstXp3 != null || s.hasStartupTjs || s.hasConfigTjs) {
            score(r, EngineType.KIRIKIRI, s.firstXp3 != null ? 95 : 80, s.firstXp3 != null ? s.firstXp3 : "[游戏目录]");
            if (s.gameNamedXp3Files.size() > 1
                    || (s.gameNamedXp3Files.isEmpty() && s.dataXp3 == null && s.xp3Files.size() > 1)) {
                r.xp3Candidates.addAll(s.xp3Files);
            }
        } else if (s.hasOnsScript || s.hasOnsArchive) {
            score(r, EngineType.ONS, s.hasOnsScript ? 90 : 70, "[游戏目录]");
        } else if (s.firstDesktop != null) {
            score(r, EngineType.WINLATOR, 90, s.firstDesktop);
        } else if (s.firstPspFile != null) {
            score(r, EngineType.PSP, 95, s.firstPspFile);
        } else if (s.firstN3dsFile != null) {
            score(r, EngineType.NINTENDO_3DS, 95, s.firstN3dsFile);
        }
        // RPG Maker (RGSS) 识别：归档形式优先于散文件形式。
        // rgss3a → VX Ace(RGSS3)；rgss2a → VX(RGSS2)；rgssad → XP(RGSS1)。
        if (s.firstRgss3a != null) {
            scoreRpgMaker(r, "rpgmvxace", 96, s.firstRgss3a);
        } else if (s.firstRgss2a != null) {
            scoreRpgMaker(r, "rpgmvx", 96, s.firstRgss2a);
        } else if (s.firstRgssad != null) {
            scoreRpgMaker(r, "rpgmxp", 96, s.firstRgssad);
        } else if (s.hasGameIni && s.hasRvdata2) {
            scoreRpgMaker(r, "rpgmvxace", 92, "[游戏目录]");
        } else if (s.hasGameIni && s.hasRvdata) {
            scoreRpgMaker(r, "rpgmvx", 92, "[游戏目录]");
        } else if (s.hasGameIni && s.hasRxdata) {
            scoreRpgMaker(r, "rpgmxp", 92, "[游戏目录]");
        }
        // Ren'Py 识别：.rpa 归档优先，其次 game/script.rpy + game/options.rpy
        if (s.firstRpa != null) {
            scoreRenpy(r, "renpy", 96, s.firstRpa);
        } else if (s.hasGameScriptRpy || s.hasOptionsRpy) {
            scoreRenpy(r, "renpy", 94, "[游戏目录]");
        } else if (s.hasRenpyDir && (s.hasRpy || s.hasRpyc)) {
            scoreRenpy(r, "renpy", 90, "[游戏目录]");
        } else if (s.hasGameDir && s.hasRpy) {
            scoreRenpy(r, "renpy", 85, "[游戏目录]");
        }
        // Godot 识别：.pck 归档优先，其次 project.godot 文件。
        // 标准 Godot 项目根目录会有 project.godot 文本配置，或打包为 .pck。
        if (s.firstPck != null) {
            scoreGodot(r, "godot4", 96, s.firstPck);
        } else if (s.hasProjectGodot) {
            scoreGodot(r, "godot4", 94, "[游戏目录]");
        }
        return r;
    }

    /**
     * 与 {@link #score} 行为一致，但额外写入 {@link Result#rpgMakerSubtype}。
     * 仅当 confidence 高于当前值时覆盖，避免被弱特征覆盖强特征。
     */
    private static void scoreRpgMaker(Result r, String subtype, int confidence, String launchTarget) {
        if (r == null || subtype == null || subtype.isEmpty()) return;
        if (confidence > r.confidence) {
            r.engine = EngineType.RPGMAKER;
            r.confidence = confidence;
            r.launchTarget = launchTarget == null ? "" : launchTarget;
            r.rpgMakerSubtype = subtype;
        }
    }

    /**
     * 与 {@link #score} 行为一致，但额外写入 {@link Result#renpySubtype}。
     */
    private static void scoreRenpy(Result r, String subtype, int confidence, String launchTarget) {
        if (r == null || subtype == null || subtype.isEmpty()) return;
        if (confidence > r.confidence) {
            r.engine = EngineType.RENPY;
            r.confidence = confidence;
            r.launchTarget = launchTarget == null ? "" : launchTarget;
            r.renpySubtype = subtype;
        }
    }

    /**
     * 与 {@link #score} 行为一致，但额外写入 {@link Result#godotSubtype}。
     */
    private static void scoreGodot(Result r, String subtype, int confidence, String launchTarget) {
        if (r == null || subtype == null || subtype.isEmpty()) return;
        if (confidence > r.confidence) {
            r.engine = EngineType.GODOT;
            r.confidence = confidence;
            r.launchTarget = launchTarget == null ? "" : launchTarget;
            r.godotSubtype = subtype;
        }
    }

    private static class FeatureState {
        boolean empty = true;
        Set<String> names = new HashSet<>();
        Set<String> relativeNames = new HashSet<>();
        List<String> xp3Files = new ArrayList<>();
        List<String> gameNamedXp3Files = new ArrayList<>();
        String firstXp3 = null;
        String dataXp3 = null;
        String firstDesktop = null;
        boolean hasIndex = false;
        boolean hasTyranoDir = false;
        boolean hasDataDir = false;
        boolean hasResourcesDir = false;
        boolean hasScenarioDir = false;
        boolean hasSystemDir = false;
        boolean hasBgimageDir = false;
        boolean hasFgimageDir = false;
        boolean hasImageDir = false;
        boolean hasSoundDir = false;
        boolean hasBgmDir = false;
        boolean hasVoiceDir = false;
        boolean hasVideoDir = false;
        boolean hasMovieDir = false;
        boolean hasFontDir = false;
        boolean hasOthersDir = false;
        boolean hasStartupTjs = false;
        boolean hasConfigTjs = false;
        boolean hasKsScript = false;
        boolean hasTjsScript = false;
        boolean hasSystemIni = false;
        boolean hasFirstIet = false;
        boolean hasRootPfs = false;
        boolean hasAnyPfsFile = false;
        boolean hasOnsScript = false;
        boolean hasOnsArchive = false;
        boolean hasPfs = false;
        boolean hasAppAsar = false;
        boolean hasPackageJson = false;
        boolean hasElectronPak = false;
        String firstPspFile = null;
        String firstN3dsFile = null;
        // RPG Maker (RGSS) 检测字段。
        String firstRgssad = null;
        String firstRgss2a = null;
        String firstRgss3a = null;
        boolean hasGameIni = false;
        boolean hasRxdata = false;
        boolean hasRvdata = false;
        boolean hasRvdata2 = false;
        // Ren'Py 检测字段
        String firstRpa = null;       // .rpa 归档文件
        boolean hasRpy = false;       // .rpy 脚本文件
        boolean hasRpyc = false;      // .rpyc 编译脚本
        boolean hasRenpyDir = false;  // renpy/ 目录
        boolean hasGameDir = false;   // game/ 目录（Ren'Py 标准结构）
        boolean hasGameScriptRpy = false; // game/script.rpy
        boolean hasOptionsRpy = false;    // game/options.rpy
        // Godot 检测字段
        String firstPck = null;         // .pck 打包文件
        boolean hasProjectGodot = false; // project.godot 项目配置文件
    }

    private static void collectFeatures(DocumentFile dir, String lowerPrefix, String originalPrefix,
                                        int level, int maxLevel, FeatureState s, DocumentFile[] knownFiles) {
        DocumentFile[] files;
        try {
            if (dir == null || !dir.isDirectory()) return;
            files = knownFiles == null ? dir.listFiles() : knownFiles;
        } catch (Throwable t) {
            Log.w(TAG, "detect list failed uri=" + safeUri(dir), t);
            return;
        }
        if (files == null || files.length == 0) return;

        for (DocumentFile f : files) {
            if (f == null) continue;
            String lower = safeLowerName(f);
            String original = safeName(f);
            if (lower.length() == 0) continue;
            String rel = lowerPrefix.length() == 0 ? lower : lowerPrefix + "/" + lower;
            String originalRel = originalPrefix.length() == 0 ? original : originalPrefix + "/" + original;
            s.empty = false;
            s.names.add(lower);
            s.relativeNames.add(rel);

            boolean directory = false;
            boolean file = false;
            try { directory = f.isDirectory(); } catch (Throwable ignored) { }
            try { file = f.isFile(); } catch (Throwable ignored) { }

            if (directory) {
                if (lower.equals("tyrano")) s.hasTyranoDir = true;
                if (lower.equals("data")) s.hasDataDir = true;
                if (lower.equals("game")) s.hasGameDir = true;  // 注意不要和 Tyrano 的 hasDataDir 冲突
                if (lower.equals("renpy")) s.hasRenpyDir = true;
                if (lower.equals("resources")) s.hasResourcesDir = true;
                if (lower.equals("scenario")) s.hasScenarioDir = true;
                if (lower.equals("system")) s.hasSystemDir = true;
                if (lower.equals("bgimage")) s.hasBgimageDir = true;
                if (lower.equals("fgimage")) s.hasFgimageDir = true;
                if (lower.equals("image")) s.hasImageDir = true;
                if (lower.equals("sound")) s.hasSoundDir = true;
                if (lower.equals("bgm")) s.hasBgmDir = true;
                if (lower.equals("voice")) s.hasVoiceDir = true;
                if (lower.equals("video")) s.hasVideoDir = true;
                if (lower.equals("movie")) s.hasMovieDir = true;
                if (lower.equals("font")) s.hasFontDir = true;
                if (lower.equals("others")) s.hasOthersDir = true;
                if (level < maxLevel && shouldDescendForFeature(lower)) {
                    collectFeatures(f, rel, originalRel, level + 1, maxLevel, s, null);
                }
                continue;
            }
            if (!file) continue;

            if (lower.equals("index.html") || lower.equals("index.htm")) s.hasIndex = true;
            if (lower.equals("startup.tjs")) s.hasStartupTjs = true;
            if (lower.equals("config.tjs")) s.hasConfigTjs = true;
            if (lower.equals("system.ini")) s.hasSystemIni = true;
            if (rel.equals("system/first.iet") || rel.endsWith("/system/first.iet")) s.hasFirstIet = true;
            if (lower.equals("root.pfs")) s.hasRootPfs = true;
            if (lower.endsWith(".pfs")) s.hasAnyPfsFile = true;
            if (lower.endsWith(".ks")) s.hasKsScript = true;
            if (lower.endsWith(".tjs") && !lower.equals("startup.tjs") && !lower.equals("config.tjs")) s.hasTjsScript = true;
            if (lower.equals("0.txt") || lower.equals("00.txt") || lower.equals("nscr_sec.dat") || lower.equals("nscript.dat") || lower.equals("onscript.nt2") || lower.equals("onscript.nt3")) s.hasOnsScript = true;
            if (lower.endsWith(".nsa") || lower.endsWith(".sar")) s.hasOnsArchive = true;
            if (lower.endsWith(".pfs")) { s.hasPfs = true; s.hasAnyPfsFile = true; }
            if (lower.equals("app.asar") || rel.endsWith("/app.asar")) s.hasAppAsar = true;
            if (lower.equals("package.json") || rel.endsWith("/package.json")) s.hasPackageJson = true;
            if (lower.startsWith("chrome_") && lower.endsWith(".pak")) s.hasElectronPak = true;
            if (lower.endsWith(".desktop") && s.firstDesktop == null) s.firstDesktop = originalRel;
            if (lower.endsWith(".xp3")) {
                // Keep the provider's exact spelling for launch resolution while all feature
                // comparisons continue to use the lower-cased relative path above.
                String xp3Path = originalRel;
                s.xp3Files.add(xp3Path);
                if (lower.contains("游戏")) s.gameNamedXp3Files.add(xp3Path);
                if (lower.equals("data.xp3") && s.dataXp3 == null) s.dataXp3 = xp3Path;
            }
            // PSP游戏文件检测
            if (lower.endsWith(".iso") || lower.endsWith(".cso") || lower.endsWith(".chd") ||
                lower.endsWith(".elf") || lower.endsWith(".pbp")) {
                if (s.firstPspFile == null) s.firstPspFile = originalRel;
            }
            // Nintendo 3DS 游戏文件检测
            // 注意:不包含 .elf(PSP 已占用)和 .app(过于通用),避免歧义
            if (lower.endsWith(".3ds") || lower.endsWith(".cci") || lower.endsWith(".zcci") ||
                lower.endsWith(".cxi") || lower.endsWith(".zcxi") || lower.endsWith(".cia") ||
                lower.endsWith(".zcia") || lower.endsWith(".3dsx") || lower.endsWith(".z3dsx")) {
                if (s.firstN3dsFile == null) s.firstN3dsFile = originalRel;
            }
            // RPG Maker (RGSS) 归档与数据文件检测。
            if (lower.equals("game.ini")) s.hasGameIni = true;
            if (lower.endsWith(".rgssad") && s.firstRgssad == null) s.firstRgssad = originalRel;
            if (lower.endsWith(".rgss2a") && s.firstRgss2a == null) s.firstRgss2a = originalRel;
            if (lower.endsWith(".rgss3a") && s.firstRgss3a == null) s.firstRgss3a = originalRel;
            // 散文件形式的 RPG Maker 数据文件（不在归档内）。
            if (lower.endsWith(".rxdata")) s.hasRxdata = true;
            if (lower.endsWith(".rvdata") && !lower.endsWith(".rvdata2")) s.hasRvdata = true;
            if (lower.endsWith(".rvdata2")) s.hasRvdata2 = true;
            // Ren'Py 检测
            if (lower.endsWith(".rpa") && s.firstRpa == null) s.firstRpa = originalRel;
            if (lower.endsWith(".rpy")) {
                s.hasRpy = true;
                if (rel.equals("game/script.rpy") || rel.endsWith("/game/script.rpy")) s.hasGameScriptRpy = true;
                if (rel.equals("game/options.rpy") || rel.endsWith("/game/options.rpy")) s.hasOptionsRpy = true;
            }
            if (lower.endsWith(".rpyc")) s.hasRpyc = true;
            // Godot 项目文件检测
            if (lower.equals("project.godot")) s.hasProjectGodot = true;
            if (lower.endsWith(".pck") && s.firstPck == null) s.firstPck = originalRel;
        }
    }

    private static boolean shouldDescendForFeature(String lowerName) {
        if (lowerName == null) return false;
        return lowerName.equals("resources") || lowerName.equals("app") || lowerName.equals("tyrano")
            || lowerName.equals("data") || lowerName.equals("scenario") || lowerName.equals("system")
            || lowerName.equals("game");  // 添加 game 目录
    }

    private static String safeName(DocumentFile file) {
        try {
            String name = file == null ? null : file.getName();
            return name == null ? "" : name;
        } catch (Throwable t) {
            Log.w(TAG, "getName failed uri=" + safeUri(file), t);
            return "";
        }
    }

    private static String safeLowerName(DocumentFile file) {
        String name = safeName(file);
        return name.length() == 0 ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static String safeUri(DocumentFile file) {
        try {
            return file == null || file.getUri() == null ? "null" : file.getUri().toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static void score(Result r, EngineType engine, int confidence, String launchTarget) {
        if (r == null) return;
        if (confidence > r.confidence) {
            r.engine = engine;
            r.confidence = confidence;
            r.launchTarget = launchTarget == null ? "" : launchTarget;
        }
    }
}
