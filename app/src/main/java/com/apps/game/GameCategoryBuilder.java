package com.apps.game;

import android.content.Context;

import com.core.launcherbridge.LauncherMetadataBridge;
import com.core.model.Game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

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
public final class GameCategoryBuilder {

    public static final String CATEGORY_RECENT = "status:recent";
    public static final String CATEGORY_PLAYING = "status:playing";
    public static final String CATEGORY_COMPLETED = "status:completed";
    public static final String CATEGORY_UNPLAYED = "status:unplayed";
    public static final String CATEGORY_FAVORITE = "status:favorite";
    public static final String CATEGORY_DEVELOPER_PREFIX = "developer:";

    private GameCategoryBuilder() {
    }

    /**
     * 在后台线程构建分类列表与开发商映射。
     * 输入全量游戏列表，输出 CategoryBuildResult（categories + developers）。
     */
    public static CategoryBuildResult build(Context appContext, List<Game> games) {
        List<CategoryOption> cats = new ArrayList<>();
        Map<Long, List<String>> devs = new HashMap<>();

        int recentCount = 0;
        int playingCount = 0;
        int completedCount = 0;
        int unplayedCount = 0;
        int favoriteCount = 0;

        Map<String, Integer> developerCounts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        if (games != null) {
            for (Game game : games) {
                if (game == null) continue;

                if (game.lastPlayedAt > 0L) {
                    recentCount++;
                }

                String status = GameMetadataFormatter.normalizePlayStatus(game.playStatus);
                if ("playing".equals(status)) {
                    playingCount++;
                } else if ("completed".equals(status)) {
                    completedCount++;
                } else {
                    unplayedCount++;
                }
                if (game.favorite) {
                    favoriteCount++;
                }

                List<String> developers = GameMetadataFormatter.parseDevelopers(
                        LauncherMetadataBridge.getDeveloperOf(appContext, game.id));
                devs.put(game.id, developers);

                for (String developer : developers) {
                    developerCounts.put(
                            developer,
                            developerCounts.containsKey(developer) ? developerCounts.get(developer) + 1 : 1
                    );
                }
            }
        }

        if (favoriteCount > 0) {
            cats.add(new CategoryOption("收藏", CATEGORY_FAVORITE));
        }
        if (recentCount > 0) {
            cats.add(new CategoryOption("最近游玩", CATEGORY_RECENT));
        }
        if (playingCount > 0) {
            cats.add(new CategoryOption("在玩", CATEGORY_PLAYING));
        }
        if (completedCount > 0) {
            cats.add(new CategoryOption("玩过", CATEGORY_COMPLETED));
        }
        if (unplayedCount > 0) {
            cats.add(new CategoryOption("未玩", CATEGORY_UNPLAYED));
        }

        for (Map.Entry<String, Integer> entry : developerCounts.entrySet()) {
            if (entry.getValue() > 0) {
                cats.add(new CategoryOption(
                        "开发商 · " + entry.getKey() + " (" + entry.getValue() + ")",
                        CATEGORY_DEVELOPER_PREFIX + entry.getKey()
                ));
            }
        }

        return new CategoryBuildResult(cats, devs);
    }

    /**
     * 判断游戏是否匹配指定分类。
     * 需要 developers 映射以支持 developer:xxx 前缀的分类。
     */
    public static boolean matches(Game game, String category, Map<Long, List<String>> developers) {
        if (game == null || category == null || category.isEmpty()) return true;
        if (CATEGORY_RECENT.equals(category)) return game.lastPlayedAt > 0L;
        if (CATEGORY_PLAYING.equals(category))
            return "playing".equals(GameMetadataFormatter.normalizePlayStatus(game.playStatus));
        if (CATEGORY_COMPLETED.equals(category))
            return "completed".equals(GameMetadataFormatter.normalizePlayStatus(game.playStatus));
        if (CATEGORY_UNPLAYED.equals(category))
            return "unplayed".equals(GameMetadataFormatter.normalizePlayStatus(game.playStatus));
        if (CATEGORY_FAVORITE.equals(category)) return game.favorite;
        if (category.startsWith(CATEGORY_DEVELOPER_PREFIX)) {
            String selectedDeveloper = category.substring(CATEGORY_DEVELOPER_PREFIX.length()).toLowerCase(Locale.ROOT);
            List<String> gameDevelopers = developers == null ? null : developers.get(game.id);
            if (gameDevelopers == null) gameDevelopers = Collections.emptyList();
            for (String developer : gameDevelopers) {
                if (developer.toLowerCase(Locale.ROOT).contains(selectedDeveloper)) return true;
            }
        }
        return false;
    }

    /** 判断指定分类值是否存在于分类列表中。 */
    public static boolean containsCategoryValue(List<CategoryOption> categories, String value) {
        if (categories == null || value == null) return false;
        for (CategoryOption category : categories) {
            if (category.value.equals(value)) return true;
        }
        return false;
    }
}
