package com.core

/**
 * 跨模块共享偏好文件名常量。
 * 主源在 com.apps.LauncherPreferences.APP_PREFS（com.core 不得反向依赖 com.apps，故镜像常量）。
 */
object CorePreferences {
    const val APP_PREFS = "yukihub_prefs"

    /** KRKR 引擎版本偏好键（原 LauncherGameLaunchBridge/LauncherKrkrBridge/SyncManager 各持字面量副本，§9.12 单源化）。 */
    const val KEY_KR_ENGINE_VERSION = "kr_engine_version"

    /** Artemis 引擎版本偏好键（应用级默认，取值 auto/1/2/3，主源）。 */
    const val KEY_ARTEMIS_ENGINE_VERSION = "artemis_engine_version"

    /** Artemis 游戏画面反转偏好键（应用级默认，true=旋转 180°，主源）。 */
    const val KEY_ARTEMIS_ROTATE_SCREEN = "artemis_rotate_screen"

    /** 主页头像偏好键主源（原 com.core.sync.SyncManager 与 com.apps.util.LauncherAvatarPersistence 两处并存，4.4 单源化）。 */
    const val KEY_PROFILE_AVATAR = "profile_avatar"
}
