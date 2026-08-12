package com.core.nativeplugin

import com.core.engine.EnginePrefs

/**
 * Constants shared by the launcher UI and the engine process for native engine plugins.
 *
 * The values in this object are machine-readable protocol data. User-visible labels must
 * stay in app string resources.
 */
object NativePluginConstants {
    const val ENGINE_KIRIKIROID2 = "kirikiroid2"
    const val ABI_ARM64 = "arm64-v8a"
    const val KIRIKIROID2_BRIDGE_ABI = 1
    const val META_KIRIKIROID2_EXPECTED_ZIP_SHA256 = "rinne.kirikiroid2.zip.sha256"
    const val PREFS_NAME = EnginePrefs.APP_PREFS

    const val LIB_SDL2 = "libSDL2.so"
    const val LIB_FFMPEG = "libffmpeg.so"
    const val LIB_GAME_139 = "libgame.so"
    const val LIB_GAME_134 = "libgame134.so"
    const val LIB_GAME_126 = "libgame126.so"

    val KIRIKIROID2_REQUIRED_LIBS: List<String> = listOf(
        LIB_SDL2,
        LIB_FFMPEG,
        LIB_GAME_139,
        LIB_GAME_134,
        LIB_GAME_126,
    )
}
