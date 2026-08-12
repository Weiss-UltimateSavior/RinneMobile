package com.core.nativeplugin

import android.content.Context

/**
 * Loads Kirikiroid2 native libraries from the installed zip plugin directory.
 *
 * Return values are absolute `libgame*.so` paths and must be passed unchanged to
 * NativeBridge so the C++ bridge can dlopen the same file.
 */
object NativeLibraryLoader {
    private val loadedPaths = LinkedHashSet<String>()

    @JvmStatic
    fun loadKirikiroid139(context: Context): String? {
        val sdl2 = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_SDL2) ?: return null
        val ffmpeg = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_FFMPEG) ?: return null
        val game = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_GAME_139) ?: return null
        loadPath(sdl2)
        loadPath(ffmpeg)
        loadPath(game)
        return game
    }

    @JvmStatic
    fun loadKirikiroid134(context: Context): String? {
        val ffmpeg = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_FFMPEG) ?: return null
        val game = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_GAME_134) ?: return null
        loadPath(ffmpeg)
        loadPath(game)
        return game
    }

    @JvmStatic
    fun loadKirikiroid126(context: Context): String? {
        val ffmpeg = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_FFMPEG) ?: return null
        val game = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_GAME_126) ?: return null
        loadPath(ffmpeg)
        loadPath(game)
        return game
    }

    @Synchronized
    private fun loadPath(path: String) {
        if (path !in loadedPaths) {
            System.load(path)
            loadedPaths.add(path)
        }
    }
}
