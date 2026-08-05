package com.apps.game

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.core.launcherbridge.LauncherCoverBridge
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.launcherbridge.LauncherScanBridge
import com.core.model.EngineType
import com.core.model.Game
import com.core.util.DevLogger

/**
 * 添加游戏保存管线（重构计划阶段 119）：自 LauncherAddGameFragment.saveGame 的 IO 管线抽取。
 *
 * 纯逻辑管线（引擎检测 → Game 构建 → cover/emulatorPackage 决策 → 数据库插入 → 异步封面抓取），
 * 不持有 Fragment 引用；UI 校验、输入收集与结果回显留在 Fragment。
 * 线程要求：必须在 IO 线程调用（内部含文件 IO 与引擎检测）。
 */
internal object AddGameSavePipeline {

    /** saveGame 所需的全部输入，UI 层在 UI 线程收集后传入。 */
    data class Input(
        val title: String,
        val engine: EngineType,
        val rpgSubtype: String,
        val launchTarget: String?,
        val emulatorInput: String,
        val gameHubId: String,
        val description: String,
        val gameDir: Uri,
        val cover: Uri?,
    )

    /**
     * 执行保存管线。
     * @return 数据库 id；<=0 表示重复或失败。
     */
    fun save(appContext: Context, input: Input): Long {
        // AUTO 让扫描器决定引擎；RPGMAKER 也走一次扫描以拿到具体子类型（rpgmxp/rpgmvx/rpgmvxace/mkxp-z），
        // 子类型用于选择对应的 mkxp native 库，但不会覆盖用户选择的 EngineType。
        var detected: LauncherScanBridge.DetectionResult? = null
        if (input.engine == EngineType.AUTO || input.engine == EngineType.RPGMAKER) {
            try {
                val root = DocumentFile.fromTreeUri(appContext, input.gameDir)
                detected = LauncherScanBridge.detectEngine(root, 2)
            } catch (error: Exception) {
                DevLogger.w("LauncherAddGame", "Engine detection failed; using selected engine", error)
            }
        }
        var finalEngine = input.engine
        if (input.engine == EngineType.AUTO && detected != null &&
            detected.confidence > 0 && detected.engine != EngineType.UNKNOWN
        ) {
            finalEngine = detected.engine
        }

        val game = Game()
        game.title = input.title
        game.engine = finalEngine
        game.rootUri = input.gameDir.toString()
        // 走共享桥接实现：bounds 采样解码（内存友好）+ 720dp 封顶 + covers 目录落盘（§5.2 下沉）。
        val copiedCover = if (input.cover == null) {
            null
        } else {
            LauncherScanBridge.copyCoverToInternalStorage(appContext, input.cover.toString())
        }
        game.coverUri = copiedCover
        game.coverPersistUri = copiedCover
        game.coverSourceType = if (copiedCover == null) 0 else 1
        game.launchTarget = textOrDefault(
            input.launchTarget,
            if (detected != null && detected.launchTarget != null &&
                detected.launchTarget.trim().isNotEmpty()
            ) {
                detected.launchTarget
            } else {
                "[游戏目录]"
            },
        )
        // emulatorPackage 优先级：用户手动填的输入框 > 用户在选择器显式选的子类型
        // （RPGMAKER 的 rpgmxp/rpgmvx/rpgmvxace/mkxp-z 或 RENPY 的 renpy）
        // > 扫描器检测到的子类型 > 引擎默认包名。
        // 关键：用户显式选了 RPG Maker XP/VX/VX Ace/mkxp-z 时，必须用对应的 mkxp native 库
        // （libmkxp18/19/30.so），否则会出现 Ruby 1.8 语法在 Ruby 3.x 下报 SyntaxError 等问题。
        val emulatorFallback: String
        if ((finalEngine == EngineType.RPGMAKER || finalEngine == EngineType.RENPY) &&
            input.rpgSubtype.isNotEmpty()
        ) {
            emulatorFallback = EnginePackageResolver.internalPackage(input.rpgSubtype)
        } else {
            emulatorFallback = EnginePackageResolver.forDetection(finalEngine, detected)
        }
        game.emulatorPackage = textOrDefault(input.emulatorInput, emulatorFallback)
        game.description = input.description
        game.gamehubLocalGameId = input.gameHubId
        if (game.engine == EngineType.GAMEHUB && input.gameHubId.isEmpty()) {
            game.gamehubLaunchMode = "program"
        }

        val id = LauncherRepositoryBridge.insertGameIfNotExists(appContext, game)
        if (id > 0 && copiedCover == null) {
            game.id = id
            LauncherCoverBridge.fetchCoverForGameAsync(appContext, game)
        }
        return id
    }

    private fun textOrDefault(value: String?, fallback: String): String =
        if (value == null || value.trim().isEmpty()) fallback else value.trim()
}
