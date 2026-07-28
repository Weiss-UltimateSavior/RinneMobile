package com.apps.home

import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.apps.data.LauncherRepository
import com.apps.theme.LauncherTheme
import com.core.util.SafeImageLoader

/**
 * 可切换的第二套首页。
 *
 * 当前以默认首页作为完整基线，后续样式调整只在此 Fragment 中逐步覆盖，
 * 避免再次丢失默认首页已有的个人信息、设置菜单、快捷功能和最近记录行为。
 */
class LauncherFeaturedHomeFragment : LauncherHomeFragment() {

    override fun recentItemLayoutRes(): Int = com.core.R.layout.item_launcher_featured_recent

    override fun bindRecentItem(itemView: View, item: LauncherRepository.RecentItem) {
        val cover = itemView.findViewById<ImageView>(com.core.R.id.recentCover)
        val initial = itemView.findViewById<TextView>(com.core.R.id.recentInitial)
        val title = itemView.findViewById<TextView>(com.core.R.id.recentTitle)
        val dateTime = itemView.findViewById<TextView>(com.core.R.id.recentMeta)
        val emulator = itemView.findViewById<TextView>(com.core.R.id.recentStatus)
        initial.text = item.iconText
        title.text = item.title
        dateTime.text = item.dateTime
        emulator.text = LauncherRepository.launchTypeLabel(requireContext(), item.launchType)
            .ifEmpty { getString(com.core.R.string.repo_played) }
        if (!SafeImageLoader.loadUri(cover, item.coverUri, SafeImageLoader.Callback { success ->
                cover.visibility = if (success) View.VISIBLE else View.GONE
                initial.visibility = if (success) View.GONE else View.VISIBLE
            })) {
            cover.visibility = View.GONE
            initial.visibility = View.VISIBLE
        }
    }

    override fun onHomeLayoutReady() {
        renderHorizontalQuickActions()
    }

    override fun applyIconTone() {
        super.applyIconTone()
        if (view == null) return
        val primary = LauncherTheme.primary(requireContext())
        val grid = binding?.homeActionsGrid ?: return
        for (index in 0 until grid.childCount) {
            val action = grid.getChildAt(index) as? LinearLayout ?: continue
            action.findViewById<ImageView>(actionIconIds[index])?.setColorFilter(primary)
            action.findViewById<TextView>(actionTextIds[index])?.setTextColor(LauncherTheme.text(requireContext()))
        }
    }

    private fun renderHorizontalQuickActions() {
        val grid = binding?.homeActionsGrid ?: return
        val primary = LauncherTheme.primary(requireContext())
        grid.columnCount = 4
        grid.rowCount = 1
        grid.layoutParams = grid.layoutParams.apply { height = dp(70) }
        for (index in 0 until grid.childCount) {
            val action = grid.getChildAt(index) as? LinearLayout ?: continue
            action.orientation = LinearLayout.VERTICAL
            action.gravity = Gravity.CENTER
            // 默认首页的整行卡片背景在第二套首页中拆为独立的图标容器，文字保持在容器下方。
            action.background = null
            action.setPadding(0, dp(2), 0, 0)
            val params = (action.layoutParams as GridLayout.LayoutParams).apply {
                width = 0
                height = dp(70)
                columnSpec = GridLayout.spec(index, 1f)
                rowSpec = GridLayout.spec(0)
                setMargins(if (index == 0) 0 else dp(4), 0, if (index == 3) 0 else dp(4), 0)
            }
            action.layoutParams = params

            val icon = action.findViewById<ImageView>(actionIconIds[index])
            val label = action.findViewById<TextView>(actionTextIds[index])
            val content = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            icon?.apply {
                layoutParams = LinearLayout.LayoutParams(dp(49), dp(49))
                setBackgroundResource(com.core.R.drawable.launcher_featured_quick_action_bg)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setColorFilter(primary)
            }
            label?.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(5) }
                gravity = Gravity.CENTER
                setTextColor(LauncherTheme.text(requireContext()))
                textSize = 10.66f
            }
            // 内层容器统一内容宽度，保证图标与不同长度的文字作为整体居中对称。
            if (icon != null) action.removeView(icon)
            if (label != null) action.removeView(label)
            icon?.let(content::addView)
            label?.let(content::addView)
            action.addView(content)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val actionIconIds = intArrayOf(
            com.core.R.id.actionSaveSlotIcon,
            com.core.R.id.actionResourceStationIcon,
            com.core.R.id.actionToolboxIcon,
            com.core.R.id.actionAgentIcon,
        )
        val actionTextIds = intArrayOf(
            com.core.R.id.actionSaveSlotLabel,
            com.core.R.id.actionResourceStationLabel,
            com.core.R.id.actionToolboxLabel,
            com.core.R.id.actionAgentLabel,
        )
    }
}
