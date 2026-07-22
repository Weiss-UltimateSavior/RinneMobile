package com.yuki.yukihub.launcher

import android.content.Context
import android.content.Intent
import com.yuki.yukihub.model.EngineType
import java.io.File

/**
 * 稳定的 Kotlin 启动门面。
 *
 * 公共 JVM 静态签名保持不变；各引擎实现可以在门面后独立拆分和迁移，不再要求调用方
 * 跟随巨型调度类变化。
 */
class EmulatorLauncher {
    class ActualSaveLocation internal constructor(
        @JvmField val directory: File?,
        @JvmField val description: String,
        @JvmField val available: Boolean,
    )

    companion object {
        @JvmStatic
        fun launchGame(
            context: Context?,
            packageName: String?,
            rootUri: String?,
            launchTarget: String?,
        ): Boolean = ExternalGameLaunchers.launchGame(
            context, EngineType.UNKNOWN, packageName, rootUri, launchTarget, "game", "game", null,
        )

        @JvmStatic
        fun launchGame(
            context: Context?,
            packageName: String?,
            rootUri: String?,
            launchTarget: String?,
            winlatorLaunchMode: String?,
        ): Boolean = ExternalGameLaunchers.launchGame(
            context, EngineType.UNKNOWN, packageName, rootUri, launchTarget,
            winlatorLaunchMode, "game", null,
        )

        @JvmStatic
        fun launchGame(
            context: Context?,
            packageName: String?,
            rootUri: String?,
            launchTarget: String?,
            winlatorLaunchMode: String?,
            gamehubLocalGameId: String?,
        ): Boolean = ExternalGameLaunchers.launchGame(
            context, EngineType.UNKNOWN, packageName, rootUri, launchTarget,
            winlatorLaunchMode, "game", gamehubLocalGameId,
        )

        @JvmStatic
        fun launchGame(
            context: Context?,
            packageName: String?,
            rootUri: String?,
            launchTarget: String?,
            winlatorLaunchMode: String?,
            gamehubLaunchMode: String?,
            gamehubLocalGameId: String?,
        ): Boolean = ExternalGameLaunchers.launchGame(
            context,
            EngineType.UNKNOWN,
            packageName,
            rootUri,
            launchTarget,
            winlatorLaunchMode,
            gamehubLaunchMode,
            gamehubLocalGameId,
        )

        @JvmStatic
        fun launchGame(
            context: Context?,
            engineType: EngineType?,
            packageName: String?,
            rootUri: String?,
            launchTarget: String?,
            winlatorLaunchMode: String?,
            gamehubLaunchMode: String?,
            gamehubLocalGameId: String?,
        ): Boolean = ExternalGameLaunchers.launchGame(
            context,
            engineType,
            packageName,
            rootUri,
            launchTarget,
            winlatorLaunchMode,
            gamehubLaunchMode,
            gamehubLocalGameId,
        )

        @JvmStatic
        fun registerEngineLaunchStrategy(strategy: EngineLaunchStrategy?) =
            ExternalGameLaunchers.registerStrategy(strategy)

        @JvmStatic
        fun getRegisteredEngineTypes(): List<EngineType> =
            ExternalGameLaunchers.registeredEngineTypes()

        @JvmStatic
        fun launch(context: Context?, packageName: String?): Boolean =
            ExternalGameLaunchers.launchPackage(context, packageName)

        @JvmStatic
        fun buildInternalTyranoIntent(
            context: Context?,
            gamePath: String?,
            launchTarget: String?,
        ): Intent = ScriptEngineLaunchers.buildTyranoIntent(requireNotNull(context), gamePath, launchTarget)

        @JvmStatic
        fun resolveInternalTyranoGameDirectory(rootUri: String?, launchTarget: String?): String? =
            ScriptEngineLaunchers.resolveTyranoGameDirectory(rootUri, launchTarget)

        @JvmStatic
        fun buildInternalOnsIntent(
            context: Context?,
            gamePath: String?,
            launchTarget: String?,
        ): Intent = ScriptEngineLaunchers.buildOnsIntent(requireNotNull(context), gamePath, launchTarget)

        @JvmStatic
        fun buildInternalOnsIntent(
            context: Context?,
            gamePath: String?,
            launchTarget: String?,
            gameId: Long,
        ): Intent = ScriptEngineLaunchers.buildOnsIntent(requireNotNull(context), gamePath, launchTarget, gameId)

        @JvmStatic
        fun buildInternalArtemisIntent(
            context: Context?,
            packageName: String?,
            gamePath: String?,
            launchTarget: String?,
        ): Intent = ArtemisLauncher.buildIntent(
            requireNotNull(context), packageName, gamePath, launchTarget,
        )

        @JvmStatic
        fun buildInternalArtemisIntent(
            context: Context?,
            gamePath: String?,
            launchTarget: String?,
        ): Intent = ArtemisLauncher.buildIntent(
            requireNotNull(context), "internal.artemis", gamePath, launchTarget,
        )

        @JvmStatic
        fun resolveActualSaveLocation(
            context: Context?,
            engine: EngineType?,
            rootUri: String?,
            launchTarget: String?,
        ): ActualSaveLocation = EngineSaveLocations.resolve(
            context, engine, rootUri, launchTarget,
        ).toPublicLocation()

        @JvmStatic
        fun resolveActualSaveLocation(
            context: Context?,
            engine: EngineType?,
            rootUri: String?,
            launchTarget: String?,
            gameId: Long,
        ): ActualSaveLocation = EngineSaveLocations.resolve(
            context, engine, rootUri, launchTarget, gameId,
        ).toPublicLocation()

        @JvmStatic
        fun resolveActualSaveDirectories(
            context: Context?,
            engine: EngineType?,
            rootUri: String?,
            launchTarget: String?,
        ): List<File> = EngineSaveLocations.resolveDirectories(
            context, engine, rootUri, launchTarget,
        )

        @JvmStatic
        fun resolveActualSaveDirectories(
            context: Context?,
            engine: EngineType?,
            rootUri: String?,
            launchTarget: String?,
            gameId: Long,
        ): List<File> = EngineSaveLocations.resolveDirectories(
            context, engine, rootUri, launchTarget, gameId,
        )

        @JvmStatic
        fun buildInternalKrkrIntent(
            context: Context?,
            gamePath: String?,
            launchTarget: String?,
        ): Intent = KrkrLauncher.buildIntent(
            requireNotNull(context), gamePath, launchTarget, false, "auto", false,
        )

        @JvmStatic
        fun buildInternalKrkrIntent(
            context: Context?,
            gamePath: String?,
            launchTarget: String?,
            originMode: Boolean,
        ): Intent = KrkrLauncher.buildIntent(
            requireNotNull(context), gamePath, launchTarget, originMode, "auto", false,
        )

        @JvmStatic
        fun buildInternalKrkrIntent(
            context: Context?,
            gamePath: String?,
            launchTarget: String?,
            originMode: Boolean,
            engineVersion: String?,
            safFileFallback: Boolean,
        ): Intent = KrkrLauncher.buildIntent(
            requireNotNull(context), gamePath, launchTarget, originMode, engineVersion, safFileFallback,
        )

        @JvmStatic
        fun buildInternalPspIntent(
            context: Context,
            gameUri: String?,
            launchTarget: String?,
        ): Intent = HandheldLaunchers.buildPspIntent(context, gameUri, launchTarget)

        @JvmStatic
        fun launchPspGame(context: Context, gameUri: String?, launchTarget: String?): Boolean =
            HandheldLaunchers.launchPsp(context, gameUri, launchTarget)

        @JvmStatic
        fun isPPSSPPInstalled(context: Context): Boolean =
            HandheldLaunchers.isPpssppInstalled(context)

        @JvmStatic
        fun buildInternalCitraIntent(
            context: Context,
            gameUri: String?,
            launchTarget: String?,
        ): Intent = HandheldLaunchers.buildCitraIntent(context, gameUri, launchTarget)

        @JvmStatic
        fun launchCitraGame(context: Context, gameUri: String?, launchTarget: String?): Boolean =
            HandheldLaunchers.launchCitra(context, gameUri, launchTarget)

        @JvmStatic
        fun isCitraInstalled(context: Context): Boolean =
            HandheldLaunchers.isCitraInstalled(context)

        @JvmStatic
        fun getPPSSPPDownloadIntent(): Intent = HandheldLaunchers.ppssppDownloadIntent()

        private fun EngineSaveLocations.Location.toPublicLocation() =
            ActualSaveLocation(directory, description, available)
    }
}
