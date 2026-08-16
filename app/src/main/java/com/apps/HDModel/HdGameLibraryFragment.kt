package com.apps.HDModel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.apps.game.LauncherGameAdapter
import com.apps.game.LauncherGameEditFragment
import com.apps.game.LauncherLibraryFragment
import com.apps.settings.LauncherKrkrSettingsFragment
import com.core.R
import com.core.model.Game

/**
 * HD 游戏库：沿用手机游戏库的搜索、分类、同步、启动与长按菜单，
 * 仅替换为适合大屏容器的六列三行分页布局。
 *
 * 长按「编辑游戏」/「引擎设置」不跳独立 Activity，而是压入主容器回退栈
 * （[HdModeActivity.showDetailFragment]），保留左侧导航与 HD 容器视觉。
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

    override fun enableLibraryPullRefresh(): Boolean = false

    override fun createLibraryAdapter(): LauncherGameAdapter =
        LauncherGameAdapter(applyPortraitScaling = false)

    override fun getPosterStylePreferenceKey(): String = "hd_poster_grid_style"

    override fun areCategoriesCollapsedByDefault(): Boolean = false

    override fun getGridColumns(): Int = 6

    override fun getPosterGridColumns(): Int = 6

    override fun getPageSize(): Int = 18

    override fun getFixedGridRows(): Int = 3

    override fun usesHorizontalPaging(): Boolean = true

    override fun startEditGameActivity(game: Game) {
        val host = activity
        if (host is HdModeActivity) {
            pendingEditGameId = game.id
            host.showDetailFragment(LauncherGameEditFragment.newInstance(game.id), "hd_edit_game")
            return
        }
        super.startEditGameActivity(game)
    }

    override fun openEngineSettings(game: Game) {
        val host = activity
        if (host is HdModeActivity) {
            host.showDetailFragment(LauncherKrkrSettingsFragment.newInstance(game.id), "hd_library_engine_settings")
            return
        }
        super.openEngineSettings(game)
    }
}
