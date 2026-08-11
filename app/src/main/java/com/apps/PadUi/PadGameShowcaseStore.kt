package com.apps.PadUi

import android.content.Context
import com.apps.LauncherPreferences

/** Pad 游戏页橱窗的本地持久化顺序，最多保存五个游戏。 */
class PadGameShowcaseStore(context: Context) {
    enum class AddResult { ADDED, ALREADY_ADDED, FULL }

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(LauncherPreferences.APP_PREFS, Context.MODE_PRIVATE)

    fun gameIds(): List<Long> = storedGameIds()
        .orEmpty()
        .split(',')
        .mapNotNull { it.toLongOrNull() }
        .distinct()
        .take(MAX_SHOWCASE_SIZE)

    fun contains(gameId: Long): Boolean = gameIds().contains(gameId)

    fun add(gameId: Long): AddResult {
        val ids = gameIds().toMutableList()
        if (ids.contains(gameId)) return AddResult.ALREADY_ADDED
        if (ids.size >= MAX_SHOWCASE_SIZE) return AddResult.FULL
        ids.add(gameId)
        save(ids)
        return AddResult.ADDED
    }

    fun remove(gameId: Long) {
        save(gameIds().filterNot { it == gameId })
    }

    fun retainExisting(gameIds: List<Long>) {
        save(gameIds.distinct().take(MAX_SHOWCASE_SIZE))
    }

    private fun save(ids: List<Long>) {
        preferences.edit()
            .putString(LauncherPreferences.KEY_PAD_GAME_SHOWCASE_IDS, ids.joinToString(","))
            .apply()
    }

    private fun storedGameIds(): String? {
        preferences.getString(LauncherPreferences.KEY_PAD_GAME_SHOWCASE_IDS, null)?.let { return it }
        val legacyPreferences = appContext.getSharedPreferences(
            LauncherPreferences.LEGACY_PAD_GAME_SHOWCASE_PREFS,
            Context.MODE_PRIVATE,
        )
        val legacyIds = legacyPreferences.getString(LauncherPreferences.LEGACY_KEY_PAD_GAME_SHOWCASE_IDS, null)
            ?: return null
        preferences.edit().putString(LauncherPreferences.KEY_PAD_GAME_SHOWCASE_IDS, legacyIds).apply()
        legacyPreferences.edit().remove(LauncherPreferences.LEGACY_KEY_PAD_GAME_SHOWCASE_IDS).apply()
        return legacyIds
    }

    companion object {
        const val MAX_SHOWCASE_SIZE = 5
    }
}
