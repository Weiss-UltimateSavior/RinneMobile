package com.apps.HDModel

import android.app.LocalActivityManager
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
import com.apps.data.LauncherRepository
import com.apps.game.LauncherSaveCategoryActivity
import com.apps.home.LauncherHomeFragment
import com.apps.settings.ResourceStationActivity
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherCoverLoader
import com.core.R
import com.core.databinding.FragmentLauncherHomeBinding

/**
 * HD 模式首页：复用 Launcher 首页的业务逻辑与交互，仅在布局完成后改为大屏横向排版。
 */
@Suppress("DEPRECATION")
class HdHomeFragment : LauncherHomeFragment(), HdEmbeddedActivityOwner {
    private var detailContainer: FrameLayout? = null
    private var localActivityManager: LocalActivityManager? = null
    private var embeddedActivityId: String? = null

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
        localActivityManager = LocalActivityManager(requireActivity(), false).apply {
            dispatchCreate(savedInstanceState)
        }
        detailContainer = view.findViewById(R.id.hdHomeDetailContainer)
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        localActivityManager?.dispatchResume()
    }

    override fun onPause() {
        localActivityManager?.dispatchPause(requireActivity().isFinishing)
        super.onPause()
    }

    override fun onStop() {
        localActivityManager?.dispatchStop()
        super.onStop()
    }

    override fun onDestroyView() {
        localActivityManager?.dispatchDestroy(requireActivity().isFinishing)
        localActivityManager = null
        detailContainer = null
        embeddedActivityId = null
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
                height = coverHeight + dp(40)
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
        if (intent.component?.className == LauncherSaveCategoryActivity::class.java.name) {
            (activity as? HdModeActivity)?.showSaveManagerFragment()
            return
        }
        if (intent.component?.className == ResourceStationActivity::class.java.name) {
            intent.putExtra(ResourceStationActivity.EXTRA_HD_EMBEDDED, true)
        }
        val manager = localActivityManager
        val container = detailContainer
        if (manager == null || container == null) {
            super.startLauncherActivity(intent)
            return
        }
        val activityName = intent.component?.className.orEmpty()
        val id = "hd_home_${activityName.substringAfterLast('.').ifEmpty { "detail" }}"
        embeddedActivityId = id
        val window = manager.startActivity(
            id,
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        ) ?: return
        val content = window.decorView
        container.visibility = View.VISIBLE
        HdPageMotion.showEmbedded(container, content)
        val host = requireActivity() as? HdModeActivity
        host?.refreshNavigationChrome()
        container.post { host?.refreshNavigationChrome() }
    }

    override fun closeEmbeddedActivity(child: Activity?): Boolean {
        val manager = localActivityManager ?: return false
        val id = embeddedActivityId ?: return false
        val current = manager.currentActivity
        if (child != null && current != null && current !== child) return false
        embeddedActivityId = null
        detailContainer?.apply {
            HdPageMotion.closeEmbedded(this, hideContainer = true) {
                post { manager.destroyActivity(id, true) }
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
        val statsCard = currentBinding.homeStatsCard
        val actionGrid = currentBinding.homeActionsGrid

        content.removeView(statsCard)
        content.removeView(actionGrid)
        header.removeView(currentBinding.actionProfileMenu)

        header.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50),
        )

        val actionBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        actions.forEach { action ->
            action.layoutParams = LinearLayout.LayoutParams(
                dp(64),
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            actionBar.addView(action)
        }
        header.addView(actionBar)
    }

    private fun styleIconAction(action: LinearLayout) {
        action.orientation = LinearLayout.HORIZONTAL
        action.gravity = android.view.Gravity.CENTER
        action.background = null
        action.setPadding(dp(16), 0, dp(16), 0)
        val icon = action.getChildAt(0) as ImageView
        icon.layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        action.getChildAt(1).visibility = View.GONE
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
