package com.core.prefs

/**
 * Launcher 主偏好键单源（com_apps_refactor_plan.md §4.4）。
 * com.core 不得反向依赖 com.apps（§8 分层）。
 * 除 KEY_AUTH_SAVED_EMAIL 外，其余键镜像 com.apps.LauncherPreferences / LauncherThemeStyle
 * 的主偏好键，供 com.core 侧（LauncherUserData.MAIN_PREF_KEYS / OverlayTranslationService）统一引用；
 * KEY_AUTH_SAVED_EMAIL 在 com.apps 侧无主源，本常量即其主源。
 */
object LauncherMainKeys {
    const val KEY_LAUNCHER_DARK_MODE = "launcher_dark_mode"
    const val KEY_LAUNCHER_THEME_STYLE = "launcher_theme_style"
    const val KEY_LAUNCHER_PARTICLES_ENABLED = "launcher_particles_enabled"
    const val KEY_LAUNCHER_PARTICLE_STYLE = "launcher_particle_style"
    const val KEY_STORAGE_PERMISSION_ASKED = "launcher_storage_permission_asked"
    const val KEY_AUTH_SAVED_EMAIL = "auth_saved_email"
}
