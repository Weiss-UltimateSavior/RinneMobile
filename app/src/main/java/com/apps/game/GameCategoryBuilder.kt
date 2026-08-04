package com.apps.game

import android.content.Context
import com.core.R
import com.core.launcherbridge.LauncherMetadataBridge
import com.core.model.Game
import java.util.HashMap
import java.util.Locale
import java.util.TreeMap

/**
 * 游戏分类构建器（数据层，无 UI）。
 *
 * 来源：LauncherLibraryFragment / PadManageFragment 重复的 rebuildCategories /
 * buildCategoriesInBackground / matchesCategory / containsCategoryValue / sortGamesByTitle
 * 五处方法。仅 buildCategoriesInBackground / matchesCategory / containsCategoryValue 在调用链中被
 * 实际使用；rebuildCategories / sortGamesByTitle 是死代码，迁移时不再保留。
 *
 * 纯函数式调用：所有方法均为 static，输入输出明确，无 Fragment 状态依赖。
 */
object GameCategoryBuilder {

    const val CATEGORY_RECENT = "status:recent"
    const val CATEGORY_PLAYING = "status:playing"
    const val CATEGORY_COMPLETED = "status:completed"
    const val CATEGORY_UNPLAYED = "status:unplayed"
    const val CATEGORY_FAVORITE = "status:favorite"
    const val CATEGORY_DEVELOPER_PREFIX = "developer:"

    /**
     * 在后台线程构建分类列表与开发商映射。
     * 输入全量游戏列表，输出 CategoryBuildResult（categories + developers）。
     */
    @JvmStatic
    fun build(appContext: Context, games: List<Game>?): CategoryBuildResult {
        val cats = ArrayList<CategoryOption>()
        val devs = HashMap<Long, List<String>>()

        var recentCount = 0
        var playingCount = 0
        var completedCount = 0
        var unplayedCount = 0
        var favoriteCount = 0

        val developerCounts = TreeMap<String, Int>(String.CASE_INSENSITIVE_ORDER)

        if (games != null) {
            for (game in games) {
                if (game.lastPlayedAt > 0L) {
                    recentCount++
                }

                val status = GameMetadataFormatter.normalizePlayStatus(game.playStatus)
                if ("playing" == status) {
                    playingCount++
                } else if ("completed" == status) {
                    completedCount++
                } else {
                    unplayedCount++
                }
                if (game.favorite) {
                    favoriteCount++
                }

                val developers = GameMetadataFormatter.parseDevelopers(
                    LauncherMetadataBridge.getDeveloperOf(appContext, game.id))
                devs[game.id] = developers

                for (developer in developers) {
                    developerCounts[developer] = (developerCounts[developer] ?: 0) + 1
                }
            }
        }

        if (favoriteCount > 0) {
            cats += CategoryOption(appContext.getString(R.string.game_category_favorite),
                CATEGORY_FAVORITE)
        }
        if (recentCount > 0) {
            cats += CategoryOption(appContext.getString(R.string.game_category_recent),
                CATEGORY_RECENT)
        }
        if (playingCount > 0) {
            cats += CategoryOption(appContext.getString(R.string.game_status_playing),
                CATEGORY_PLAYING)
        }
        if (completedCount > 0) {
            cats += CategoryOption(appContext.getString(R.string.game_status_completed),
                CATEGORY_COMPLETED)
        }
        if (unplayedCount > 0) {
            cats += CategoryOption(appContext.getString(R.string.game_status_unplayed),
                CATEGORY_UNPLAYED)
        }

        for ((key, value) in developerCounts) {
            if (value > 0) {
                cats += CategoryOption(
                    appContext.getString(R.string.game_category_developer,
                        key, value),
                    CATEGORY_DEVELOPER_PREFIX + key
                )
            }
        }

        return CategoryBuildResult(cats, devs)
    }

    /**
     * 判断游戏是否匹配指定分类。
     * 需要 developers 映射以支持 developer:xxx 前缀的分类。
     */
    @JvmStatic
    fun matches(game: Game?, category: String?, developers: Map<Long, List<String>>?): Boolean {
        if (game == null || category.isNullOrEmpty()) return true
        if (CATEGORY_RECENT == category) return game.lastPlayedAt > 0L
        if (CATEGORY_PLAYING == category)
            return "playing" == GameMetadataFormatter.normalizePlayStatus(game.playStatus)
        if (CATEGORY_COMPLETED == category)
            return "completed" == GameMetadataFormatter.normalizePlayStatus(game.playStatus)
        if (CATEGORY_UNPLAYED == category)
            return "unplayed" == GameMetadataFormatter.normalizePlayStatus(game.playStatus)
        if (CATEGORY_FAVORITE == category) return game.favorite
        if (category.startsWith(CATEGORY_DEVELOPER_PREFIX)) {
            val selectedDeveloper = category.substring(CATEGORY_DEVELOPER_PREFIX.length).lowercase(Locale.ROOT)
            val gameDevelopers = developers?.get(game.id) ?: emptyList()
            for (developer in gameDevelopers) {
                if (developer.lowercase(Locale.ROOT).contains(selectedDeveloper)) return true
            }
        }
        return false
    }

    /** 判断指定分类值是否存在于分类列表中。 */
    @JvmStatic
    fun containsCategoryValue(categories: List<CategoryOption>?, value: String?): Boolean {
        if (categories == null || value == null) return false
        for (category in categories) {
            if (category.value == value) return true
        }
        return false
    }
}
