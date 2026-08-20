package com.core

/**
 * 跨模块共享偏好文件名常量。
 * 主源在 com.apps.LauncherPreferences.APP_PREFS（com.core 不得反向依赖 com.apps，故镜像常量）。
 */
object CorePreferences {
    const val APP_PREFS = "yukihub_prefs"

    /** KRKR 引擎版本偏好键（原 LauncherGameLaunchBridge/LauncherKrkrBridge/SyncManager 各持字面量副本，§9.12 单源化）。 */
    const val KEY_KR_ENGINE_VERSION = "kr_engine_version"

    /** KRKR 引擎内核偏好键（krkrsdl3 集成，值 = auto/kirikiri2/krkrsdl3，默认 auto）。 */
    const val KEY_KR_ENGINE_KERNEL = "kr_engine_kernel"

    /** KRKR（Kirikiroid2）默认字体偏好键：字体文件路径，空串表示使用内置字体（主源）。 */
    const val KEY_KR_DEFAULT_FONT = "kr_default_font"

    /** KRKR（Kirikiroid2）强制使用默认字体偏好键（主源）。 */
    const val KEY_KR_FORCE_DEFAULT_FONT = "kr_force_default_font"

    /** Artemis 引擎版本偏好键（应用级默认，取值 auto/1/2/3，主源）。 */
    const val KEY_ARTEMIS_ENGINE_VERSION = "artemis_engine_version"

    /** Artemis 游戏画面反转偏好键（应用级默认，true=旋转 180°，主源）。 */
    const val KEY_ARTEMIS_ROTATE_SCREEN = "artemis_rotate_screen"

    /** Artemis 自动应用基础补丁偏好键（应用级默认，取值 ask/auto/off，主源）。 */
    const val KEY_ARTEMIS_AUTO_PATCH = "artemis_auto_patch"

    /** 主页头像偏好键主源（原 com.core.sync.SyncManager 与 com.apps.util.LauncherAvatarPersistence 两处并存，4.4 单源化）。 */
    const val KEY_PROFILE_AVATAR = "profile_avatar"

    /** 本地玩家昵称偏好键（镜像 com.apps.LauncherPreferences.KEY_PROFILE_NAME，com.core 不得反向依赖 com.apps）。 */
    const val KEY_PROFILE_NAME = "profile_name"

    /** 本地玩家昵称默认值（镜像 com.apps.LauncherPreferences.DEFAULT_PROFILE_NAME）。 */
    const val DEFAULT_PROFILE_NAME = "Rinne"
}
