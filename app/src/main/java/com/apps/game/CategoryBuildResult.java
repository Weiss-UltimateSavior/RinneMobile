package com.apps.game;

import java.util.List;
import java.util.Map;

/**
 * 游戏分类构建结果。
 *
 * 来源：原 LauncherLibraryFragment / PadManageFragment 中的 private static final 内嵌类。
 * 提升为顶层 public 类，便于 GameCategoryBuilder 与 Fragment 共享。
 */
public final class CategoryBuildResult {
    public final List<CategoryOption> categories;
    public final Map<Long, List<String>> developers;

    public CategoryBuildResult(List<CategoryOption> categories, Map<Long, List<String>> developers) {
        this.categories = categories;
        this.developers = developers;
    }
}
