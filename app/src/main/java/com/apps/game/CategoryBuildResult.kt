package com.apps.game

/**
 * 游戏分类构建结果。
 *
 * 来源：原 LauncherLibraryFragment 中的 private static final 内嵌类。
 * 提升为顶层 public 类，便于 GameCategoryBuilder 与 Fragment 共享。
 */
data class CategoryBuildResult(
    @JvmField val categories: List<CategoryOption>,
    @JvmField val developers: Map<Long, List<String>>
)
