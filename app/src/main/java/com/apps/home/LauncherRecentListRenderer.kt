package com.apps.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.apps.data.LauncherRepository
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.databinding.FragmentLauncherHomeBinding

/**
 * 最近游玩列表渲染器：纯函数式列表构建。
 *
 * 布局/数量/列数/缩放/绑定/点击回调由 [LauncherHomeFragment] 的 protected open 扩展点
 * 以参数传入，渲染逻辑与 Fragment 解耦（§8 职责分离；子类契约保持不变）。
 */
internal object LauncherRecentListRenderer {

    fun render(
        binding: FragmentLauncherHomeBinding,
        context: Context,
        items: List<LauncherRepository.RecentItem>?,
        layoutRes: Int,
        displayLimit: Int,
        columns: Int,
        useScaler: Boolean,
        bindItem: (View, LauncherRepository.RecentItem) -> Unit,
        onClick: (LauncherRepository.RecentItem) -> Unit,
        onLongClick: (LauncherRepository.RecentItem) -> Unit,
    ) {
        if (items.isNullOrEmpty()) {
            binding.recentEmpty.visibility = View.VISIBLE
            binding.recentList.visibility = View.GONE
            binding.recentList.removeAllViews()
            return
        }
        binding.recentEmpty.visibility = View.GONE
        binding.recentList.visibility = View.VISIBLE
        binding.recentList.removeAllViews()
        val inflater = LayoutInflater.from(context)
        val columnCount = columns.coerceAtLeast(1)
        val visibleItems = items.take(displayLimit.coerceAtLeast(0))
        var currentRow: LinearLayout? = null
        for ((index, item) in visibleItems.withIndex()) {
            if (columnCount > 1 && index % columnCount == 0) {
                currentRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }
                binding.recentList.addView(currentRow)
            }
            val itemView = inflater.inflate(layoutRes, binding.recentList, false)
            if (useScaler) {
                LauncherTabletPortraitScaler.apply(itemView)
            }
            bindItem(itemView, item)
            LauncherTheme.applyPrimaryTone(itemView)
            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
            if (columnCount == 1) {
                binding.recentList.addView(itemView)
            } else {
                itemView.layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    setMargins(
                        LauncherTheme.dp(context, 5), LauncherTheme.dp(context, 2),
                        LauncherTheme.dp(context, 5), LauncherTheme.dp(context, 3)
                    )
                }
                currentRow?.addView(itemView)
            }
        }
        if (columnCount > 1 && visibleItems.isNotEmpty()) {
            val missing = (columnCount - visibleItems.size % columnCount) % columnCount
            repeat(missing) {
                currentRow?.addView(
                    View(context),
                    LinearLayout.LayoutParams(0, 0, 1f),
                )
            }
        }
    }
}
