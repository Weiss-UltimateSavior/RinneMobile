package com.core.scanner

import com.core.model.EngineType

/**
 * 扫描阶段产出的游戏候选信息。由 [com.core.scanner.GameScanner] 产出，
 * 在 [com.core.data.GameRepository] 中合并到 [com.core.model.Game]。
 *
 * - `@JvmField var` 保留 Java 直接字段访问（如 `result.launchTarget = candidate`）。
 * - `@JvmOverloads` 让 Java 调用方继续使用原 Java 的 6 个 telescoping 重载。
 * - `init` 块复刻原 10 参构造器的 null 归一化与 `xp3Candidates` 防御性拷贝。
 */
data class ScanResult @JvmOverloads constructor(
    @JvmField var title: String? = null,
    @JvmField var uri: String? = null,
    @JvmField var engine: EngineType? = null,
    @JvmField var confidence: Int = 0,
    @JvmField var launchTarget: String? = "",
    @JvmField var coverUri: String? = "",
    /** Multiple non-data XP3 candidates which require a user choice before import. */
    @JvmField var xp3Candidates: List<String>? = null,
    /** Engine candidates when one file may run on multiple engines (e.g. .iso: PSP/ARMSX3).
     *  Non-null & size>=2 triggers an engine-selection dialog before import. */
    @JvmField var engineCandidates: List<EngineType>? = null,
    /** RPG Maker runtime alias: rpgmxp / rpgmvx / rpgmvxace / mkxp-z. */
    @JvmField var rpgMakerSubtype: String? = "",
    /** Ren'Py runtime alias: renpy / renpy8. */
    @JvmField var renpySubtype: String? = "",
    /** Godot runtime alias: godot4. */
    @JvmField var godotSubtype: String? = ""
) {
    init {
        if (launchTarget == null) launchTarget = ""
        if (coverUri == null) coverUri = ""
        // 防御性拷贝：null → 空 ArrayList，非空 → 新 ArrayList 拷贝原列表元素。
        // 使用 `?: emptyList()` 统一处理可空类型，避免对 `var` 属性 smart-cast 失败的告警。
        xp3Candidates = ArrayList(xp3Candidates ?: emptyList())
        // 与 xp3Candidates 一致做防御性拷贝，防止调用方把同一 ArrayList 挂到多个 result 时
        // 相互引用导致一改俱改（选择弹窗依赖 size 判定）。
        engineCandidates = if (engineCandidates == null) {
            ArrayList()
        } else {
            ArrayList(engineCandidates)
        }
        if (rpgMakerSubtype == null) rpgMakerSubtype = ""
        if (renpySubtype == null) renpySubtype = ""
        if (godotSubtype == null) godotSubtype = ""
    }
}
