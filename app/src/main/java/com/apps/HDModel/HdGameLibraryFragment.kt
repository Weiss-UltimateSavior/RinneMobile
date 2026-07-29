package com.apps.HDModel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.apps.game.LauncherGameAdapter
import com.apps.game.LauncherLibraryFragment
import com.core.R

/**
 * HD 游戏库：沿用手机游戏库的搜索、分类、同步、启动与长按菜单，
 * 仅替换为适合大屏容器的六列三行分页布局。
 */
class HdGameLibraryFragment : LauncherLibraryFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.fragment_hd_game_library, container, false)
        bindLibraryRoot(root)
        return root
    }

    override fun usePortraitLibraryScaler(): Boolean = false

    override fun applyLibrarySystemBarInsets(): Boolean = false

    override fun createLibraryAdapter(): LauncherGameAdapter =
        LauncherGameAdapter(applyPortraitScaling = false)

    override fun getPosterStylePreferenceKey(): String = "hd_poster_grid_style"

    override fun areCategoriesCollapsedByDefault(): Boolean = false

    override fun getGridColumns(): Int = 6

    override fun getPosterGridColumns(): Int = 6

    override fun getPageSize(): Int = 18

    override fun getFixedGridRows(): Int = 3

    override fun usesHorizontalPaging(): Boolean = true
}
