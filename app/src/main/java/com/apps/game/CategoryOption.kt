package com.apps.game

/**
 * 游戏分类筛选项。
 *
 * 来源：原 LauncherLibraryFragment / PadManageFragment 中的 private static final 内嵌类。
 * 提升为顶层 public 类，便于 GameCategoryBuilder 与 Fragment 共享。
 */
data class CategoryOption(@JvmField val label: String, @JvmField val value: String)
