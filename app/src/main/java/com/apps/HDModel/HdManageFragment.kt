package com.apps.HDModel

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.apps.game.LauncherAddGameFragment
import com.apps.game.LauncherManageFragment
import com.apps.settings.LauncherKrkrSettingsFragment
import com.apps.settings.LauncherMetadataSourceFragment
import com.apps.sync.LauncherSyncCenterFragment
import com.core.R

/**
 * HD 管理页：复用管理页全部控制器和交互，以左右分栏 XML 适配大屏内容容器。
 *
 * 重构计划 9.9 阶段 110：嵌入 Activity 迁子 Fragment（添加游戏/元数据源/引擎设置/同步中心），
 * 不再使用 LocalActivityManager；ActivityResult 由子 Fragment 自身注册。
 */
class HdManageFragment : LauncherManageFragment(), HdEmbeddedActivityOwner {
    private var detailContainer: FrameLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.fragment_hd_manage, container, false)
        bindManageRoot(root)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        detailContainer = view.findViewById(R.id.hdManageDetailContainer)
        super.onViewCreated(view, savedInstanceState)
        openAddGame()
    }

    override fun onDestroyView() {
        detailContainer = null
        super.onDestroyView()
    }

    override fun usePortraitManageScaler(): Boolean = false

    override fun applyManageSystemBarInsets(): Boolean = false

    override fun openAddGame() {
        showChildFragment(CHILD_ADD_GAME_TAG, LauncherAddGameFragment())
    }

    override fun openMetadataSource() {
        showChildFragment(CHILD_METADATA_SOURCE_TAG, LauncherMetadataSourceFragment())
    }

    override fun openKrkrSettings() {
        showChildFragment(CHILD_ENGINE_SETTINGS_TAG, LauncherKrkrSettingsFragment.newInstance(0L))
    }

    override fun openArtemisSettings() {
        showChildFragment(CHILD_ENGINE_SETTINGS_TAG, LauncherKrkrSettingsFragment.newArtemisOnlyInstance())
    }

    override fun openSyncCenter() {
        showChildFragment(CHILD_SYNC_CENTER_TAG, LauncherSyncCenterFragment())
    }

    private fun showChildFragment(tag: String, fragment: Fragment) {
        if (!isAdded || detailContainer == null) return
        childFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.launcher_fragment_enter,
                R.anim.launcher_fragment_exit,
            )
            .replace(R.id.hdManageDetailContainer, fragment, tag)
            .commit()
    }

    override fun closeEmbeddedActivity(child: Activity?): Boolean {
        val existing = childFragmentManager.findFragmentByTag(CHILD_ADD_GAME_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_METADATA_SOURCE_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_ENGINE_SETTINGS_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_SYNC_CENTER_TAG)
            ?: return false
        childFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.launcher_fragment_enter, R.anim.launcher_fragment_exit)
            .remove(existing)
            .commit()
        return true
    }

    companion object {
        private const val CHILD_ADD_GAME_TAG = "hd_add_game"
        private const val CHILD_METADATA_SOURCE_TAG = "hd_metadata_source"
        private const val CHILD_ENGINE_SETTINGS_TAG = "hd_engine_settings"
        private const val CHILD_SYNC_CENTER_TAG = "hd_sync_center"
    }
}
