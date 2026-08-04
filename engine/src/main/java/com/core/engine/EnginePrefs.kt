package com.core.engine

/**
 * engine 模块共享偏好文件名/键常量。
 * 主源在 app 模块（APP_PREFS → com.apps.LauncherPreferences.APP_PREFS；
 * KEY_TYRANO_EXTERNAL_NETWORK → com.core.launcher.EngineSaveKeys.KEY_TYRANO_EXTERNAL_NETWORK）。
 * engine 不得反向依赖 app，故在 engine 侧镜像常量作为单一来源，避免各引擎类各自持有字面量副本。
 */
object EnginePrefs {
    const val APP_PREFS = "yukihub_prefs"

    /** Tyrano 外部网络开关偏好键（镜像 app 模块 EngineSaveKeys.KEY_TYRANO_EXTERNAL_NETWORK）。 */
    const val KEY_TYRANO_EXTERNAL_NETWORK = "tyrano_external_network"
}
