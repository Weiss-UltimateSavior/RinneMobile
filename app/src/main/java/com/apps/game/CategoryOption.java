package com.apps.game;

/**
 * 游戏分类筛选项。
 *
 * 来源：原 LauncherLibraryFragment / PadManageFragment 中的 private static final 内嵌类。
 * 提升为顶层 public 类，便于 GameCategoryBuilder 与 Fragment 共享。
 */
public final class CategoryOption {
    public final String label;
    public final String value;

    public CategoryOption(String label, String value) {
        this.label = label;
        this.value = value;
    }
}
