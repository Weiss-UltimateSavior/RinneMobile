package com.core.launcher

/**
 * 内置引擎包名路由常量单源（com_apps_refactor_plan.md §9.12）。
 * 各模块的引擎包判断（startsWith / 历史别名）统一收敛于此，避免字面量漂移。
 */
object EnginePackages {
    const val INTERNAL_KRKR = "internal.krkr"
    const val INTERNAL_KRKR_ORIGIN = "internal.krkr.origin"
    const val LEGACY_KRKR = "org.tvp.kirikiri2.internal"

    const val INTERNAL_ONS = "internal.ons"
    const val INTERNAL_ONSCRIPTER = "internal.onscripter"
    const val LEGACY_ONS = "com.core.ons"
    const val LEGACY_YUKI_ONS = "com.yuki.yukihub.ons"

    const val INTERNAL_TYRANO = "internal.tyrano"
    const val LEGACY_TYRANO = "com.core.tyrano"
    const val LEGACY_YUKI_TYRANO = "com.yuki.yukihub.tyrano"

    const val INTERNAL_ARTEMIS = "internal.artemis"
    const val LEGACY_ARTEMIS = "com.core.artemis"
    const val ARTEMIS_COMPAT = "internal.artemis.compat"
    const val ARTEMIS_COMPATIBLE = "internal.artemis.compatible"
    const val ARTEMIS_COMPAT_V2 = "internal.artemis.compat.v2"
    const val ARTEMIS_COMPATIBLE_V2 = "internal.artemis.compatible.v2"

    const val INTERNAL_RENPY = "internal.renpy"
    const val INTERNAL_RENPY8 = "internal.renpy8"

    const val INTERNAL_PSP = "internal.psp"
    const val EXTERNAL_PPSSPP = "org.ppsspp.ppsspp"
    const val INTERNAL_CITRA = "internal.citra"
    const val EXTERNAL_AZAHAR = "io.github.azaharplus.android"
    const val EXTERNAL_EDEN = "dev.eden.eden_emulator"
    const val EXTERNAL_GAMEHUB = "com.xiaoji.egggame"
    const val INTERNAL_RPGMAKER_XP = "internal.rpgmxp"
    const val INTERNAL_GODOT = "internal.godot"

    /** internal.krkr（含 .origin 后缀）或历史 tvp 包名。 */
    @JvmStatic
    fun isInternalKrkr(pkg: String?): Boolean =
        pkg?.startsWith(INTERNAL_KRKR) == true || pkg == LEGACY_KRKR

    /** internal.tyrano 或 com.core.tyrano / com.yuki.yukihub.tyrano 历史别名。 */
    @JvmStatic
    fun isInternalTyrano(pkg: String?): Boolean =
        pkg?.startsWith(INTERNAL_TYRANO) == true || pkg == LEGACY_TYRANO || pkg == LEGACY_YUKI_TYRANO

    /** internal.ons 或 com.core.ons / com.yuki.yukihub.ons 历史别名。 */
    @JvmStatic
    fun isInternalOns(pkg: String?): Boolean =
        pkg?.startsWith(INTERNAL_ONS) == true || pkg == LEGACY_ONS || pkg == LEGACY_YUKI_ONS

    /** internal.artemis 及全部兼容后缀变体。 */
    @JvmStatic
    fun isInternalArtemis(pkg: String?): Boolean = pkg?.startsWith(INTERNAL_ARTEMIS) == true
}
