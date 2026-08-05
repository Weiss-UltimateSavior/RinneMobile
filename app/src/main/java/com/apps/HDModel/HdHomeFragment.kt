package com.apps.HDModel

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.apps.account.LauncherDisclaimerActivity
import com.apps.account.LauncherDisclaimerFragment
import com.apps.agent.LocalAgentFragment
import com.apps.data.LauncherRepository
import com.apps.game.LauncherSaveCategoryActivity
import com.apps.home.LauncherHomeFragment
import com.apps.settings.LauncherAppSettingsActivity
import com.apps.settings.LauncherAppSettingsFragment
import com.apps.settings.LauncherToolboxActivity
import com.apps.settings.LauncherToolboxFragment
import com.apps.settings.ResourceStationActivity
import com.apps.theme.LauncherTheme
import com.apps.theme.LauncherThemeMenuActivity
import com.apps.theme.LauncherThemeMenuFragment
import com.apps.widget.LauncherCoverLoader
import com.core.R
import com.core.databinding.FragmentLauncherHomeBinding

/**
 * HD 模式首页：复用 Launcher 首页的业务逻辑与交互，仅在布局完成后改为大屏横向排版。
 */
@Suppress("DEPRECATION")
class HdHomeFragment : LauncherHomeFragment(), HdEmbeddedActivityOwner {
    private var detailContainer: FrameLayout? = null
    private var embeddedHost: HdEmbeddedActivityHost? = null
    private var hdHeaderArranged = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_hd_home, container, false)
        val homeRoot = root.findViewById<View>(R.id.recentRefresh)
        binding = FragmentLauncherHomeBinding.bind(homeRoot)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        embeddedHost = HdEmbeddedActivityHost(requireActivity()).also { it.onCreate(savedInstanceState) }
        detailContainer = view.findViewById(R.id.hdHomeDetailContainer)
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        embeddedHost?.onResume()
    }

    override fun onPause() {
        embeddedHost?.onPause()
        super.onPause()
    }

    override fun onStop() {
        embeddedHost?.onStop()
        super.onStop()
    }

    override fun onDestroyView() {
        embeddedHost?.onDestroyView()
        embeddedHost = null
        detailContainer = null
        hdHeaderArranged = false
        super.onDestroyView()
    }

    override fun usePortraitTabletScaler(): Boolean = false

    override fun applyHomeSystemBarInsets(): Boolean = false

    override fun recentItemLayoutRes(): Int = R.layout.item_launcher_game_card

    override fun recentDisplayLimit(): Int = 18

    override fun recentGridColumns(): Int = 6

    override fun bindRecentItem(itemView: View, item: LauncherRepository.RecentItem) {
        val coverFrame = itemView.findViewById<FrameLayout>(R.id.launcherGameCoverFrame)
        val cover = itemView.findViewById<ImageView>(R.id.launcherGameCover)
        val initial = itemView.findViewById<TextView>(R.id.launcherGameInitial)
        val overlay = itemView.findViewById<View>(R.id.launcherGameTextOverlay)
        val posterInfo = itemView.findViewById<View>(R.id.launcherGamePosterInfo)
        val title = itemView.findViewById<TextView>(R.id.launcherGamePosterTitle)
        val status = itemView.findViewById<TextView>(R.id.launcherGamePosterStatus)

        itemView.background = null
        overlay.visibility = View.GONE
        posterInfo.visibility = View.VISIBLE
        initial.text = item.iconText
        initial.setTextColor(LauncherTheme.text(requireContext()))
        title.text = item.title
        title.setTextColor(LauncherTheme.text(requireContext()))
        status.text = item.timeAndDuration
        status.setTextColor(LauncherTheme.textMuted(requireContext()))
        coverFrame.clipToOutline = true
        cover.clipToOutline = true
        LauncherCoverLoader.clear(cover)
        cover.visibility = View.GONE
        initial.visibility = View.VISIBLE
        LauncherCoverLoader.loadInto(
            cover,
            item.coverUri,
            object : LauncherCoverLoader.Callback {
                override fun onLoaded(success: Boolean) {
                    cover.visibility = if (success) View.VISIBLE else View.GONE
                    initial.visibility = if (success) View.GONE else View.VISIBLE
                }
            },
        )
        itemView.post {
            val width = itemView.width
            if (width <= 0) return@post
            val coverHeight = (width * 1.42f).toInt()
            coverFrame.layoutParams = coverFrame.layoutParams.apply {
                height = coverHeight
            }
            itemView.layoutParams = itemView.layoutParams.apply {
                height = coverHeight + LauncherTheme.dp(requireContext(), 40)
            }
        }
    }

    override fun onHomeLayoutReady() {
        super.onHomeLayoutReady()
        val currentBinding = binding ?: return
        val grid = currentBinding.homeActionsGrid
        val actions = List(grid.childCount) { grid.getChildAt(it) as LinearLayout }
        actions.forEach(::styleIconAction)
        grid.removeAllViews()
        arrangeHdHeader(currentBinding, actions)
    }

    override fun startLauncherActivity(intent: Intent) {
        val className = intent.component?.className
        if (className == LauncherSaveCategoryActivity::class.java.name) {
            (activity as? HdModeActivity)?.showSaveManagerFragment()
            return
        }
        // 菜单目标迁子 Fragment（9.9 ②HdHome）：设置/主题/免责声明/工具箱/本地智能体
        when (className) {
            LauncherAppSettingsActivity::class.java.name -> {
                showChildFragment(CHILD_APP_SETTINGS_TAG, LauncherAppSettingsFragment())
                return
            }
            LauncherThemeMenuActivity::class.java.name -> {
                showChildFragment(CHILD_THEME_MENU_TAG, LauncherThemeMenuFragment())
                return
            }
            LauncherDisclaimerActivity::class.java.name -> {
                showChildFragment(CHILD_DISCLAIMER_TAG, LauncherDisclaimerFragment())
                return
            }
            LauncherToolboxActivity::class.java.name -> {
                showChildFragment(CHILD_TOOLBOX_TAG, LauncherToolboxFragment())
                return
            }
            com.apps.agent.LocalAgentActivity::class.java.name -> {
                showChildFragment(CHILD_LOCAL_AGENT_TAG, LocalAgentFragment())
                return
            }
        }
        // 其余目标（ResourceStation/未知 intent）保持嵌入路径
        if (className == ResourceStationActivity::class.java.name) {
            intent.putExtra(ResourceStationActivity.EXTRA_HD_EMBEDDED, true)
        }
        val host = embeddedHost
        val container = detailContainer
        if (host == null || container == null) {
            super.startLauncherActivity(intent)
            return
        }
        val activityName = className.orEmpty()
        val id = "hd_home_${activityName.substringAfterLast('.').ifEmpty { "detail" }}"
        val content = host.start(
            id,
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        ) ?: return
        container.visibility = View.VISIBLE
        HdPageMotion.showEmbedded(container, content)
        val hostActivity = requireActivity() as? HdModeActivity
        hostActivity?.refreshNavigationChrome()
        // I-2 守卫（阶段 120）：post 延迟执行期间宿主 Activity 可能已销毁，幂等刷新前校验状态。
        container.post {
            if (hostActivity != null && !hostActivity.isFinishing && !hostActivity.isDestroyed) {
                hostActivity.refreshNavigationChrome()
            }
        }
    }

    private fun showChildFragment(tag: String, fragment: Fragment) {
        if (!isAdded || detailContainer == null) return
        detailContainer?.visibility = View.VISIBLE
        childFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.launcher_fragment_enter,
                R.anim.launcher_fragment_exit,
            )
            .replace(R.id.hdHomeDetailContainer, fragment, tag)
            .commit()
    }

    override fun closeEmbeddedActivity(child: Activity?): Boolean {
        // 子 Fragment 路径（5 个菜单目标）
        val existing = childFragmentManager.findFragmentByTag(CHILD_APP_SETTINGS_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_THEME_MENU_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_DISCLAIMER_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_TOOLBOX_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_LOCAL_AGENT_TAG)
        if (existing != null) {
            childFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.launcher_fragment_enter, R.anim.launcher_fragment_exit)
                .remove(existing)
                .commit()
            detailContainer?.visibility = View.GONE
            return true
        }
        // embeddedHost 回退（ResourceStation/未知 intent）
        val host = embeddedHost ?: return false
        val id = host.beginClose(child) ?: return false
        detailContainer?.apply {
            HdPageMotion.closeEmbedded(this, hideContainer = true) {
                post { host.destroy(id) }
            }
        }
        return true
    }

    private fun arrangeHdHeader(
        currentBinding: FragmentLauncherHomeBinding,
        actions: List<LinearLayout>,
    ) {
        val content = currentBinding.contentScroll.getChildAt(0) as? LinearLayout ?: return
        if (content.childCount < 4) return
        val header = content.getChildAt(0) as? LinearLayout ?: return
        // 幂等守卫：同一 View 实例只组装一次，避免二次调用重复 addView/清空已组装的按钮。
        if (hdHeaderArranged) return
        hdHeaderArranged = true

        // HD 横屏：竖屏区块不再参与布局，改用 visibility 隐藏，保留 ViewBinding 引用不失效。
        currentBinding.homeStatsCard.visibility = View.GONE
        currentBinding.homeActionsGrid.visibility = View.GONE
        currentBinding.actionProfileMenu.visibility = View.GONE

        header.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            LauncherTheme.dp(requireContext(), 50),
        )

        // 动作按钮组装到 XML 预设容器（hdHomeActionBar），不动态创建/挂载新 View。
        val actionBar = currentBinding.hdHomeActionBar.apply {
            visibility = View.VISIBLE
            removeAllViews()
        }

        actions.forEach { action ->
            action.layoutParams = LinearLayout.LayoutParams(
                LauncherTheme.dp(requireContext(), 64),
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            actionBar.addView(action)
        }
    }

    private fun styleIconAction(action: LinearLayout) {
        action.orientation = LinearLayout.HORIZONTAL
        action.gravity = android.view.Gravity.CENTER
        action.background = null
        action.setPadding(LauncherTheme.dp(requireContext(), 16), 0, LauncherTheme.dp(requireContext(), 16), 0)
        val icon = action.getChildAt(0) as ImageView
        icon.layoutParams = LinearLayout.LayoutParams(LauncherTheme.dp(requireContext(), 32), LauncherTheme.dp(requireContext(), 32))
        action.getChildAt(1).visibility = View.GONE
    }

    companion object {
        private const val CHILD_APP_SETTINGS_TAG = "hd_home_app_settings"
        private const val CHILD_THEME_MENU_TAG = "hd_home_theme_settings"
        private const val CHILD_DISCLAIMER_TAG = "hd_home_disclaimer"
        private const val CHILD_TOOLBOX_TAG = "hd_home_toolbox"
        private const val CHILD_LOCAL_AGENT_TAG = "hd_home_local_agent"
    }
}
