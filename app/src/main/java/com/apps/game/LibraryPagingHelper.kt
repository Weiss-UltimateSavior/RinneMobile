package com.apps.game

import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.apps.theme.LauncherTheme

/**
 * 游戏库分页/卡片高度辅助类：横向翻页、首屏填充、纵向拖拽加载与自适应卡片高度。
 *
 * 生命周期由 [LauncherLibraryFragment] 包裹；binding/listController/adapter 等经
 * fragment 的 internal 访问器获取，View 销毁后返回 null 作为守卫（§8 协调类模式）。
 */
internal class LibraryPagingHelper(private val fragment: LauncherLibraryFragment) {

    // 纵向加载手势状态（原 Fragment 私有字段迁移）
    private var loadMoreDragStartY: Float = 0f
    private var loadMoreDragCandidate: Boolean = false

    fun showNextPage(): Boolean {
        if (!fragment.libraryListController.showNextPage()) return false
        animatePageChange(true)
        return true
    }

    fun showPreviousPage(): Boolean {
        if (!fragment.libraryListController.showPreviousPage()) return false
        animatePageChange(false)
        return true
    }

    private fun animatePageChange(forward: Boolean) {
        val currentBinding = fragment.libraryBinding ?: return
        val distance = LauncherTheme.dp(fragment.requireContext(), 36) * (if (forward) 1f else -1f)
        currentBinding.libraryRecycler.animate().cancel()
        currentBinding.libraryRecycler.translationX = distance
        currentBinding.libraryRecycler.alpha = 0.72f
        currentBinding.libraryRecycler.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(220L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    fun loadNextPage() {
        fragment.libraryListController.loadNextPage()
    }

    fun renderState() {
        val currentBinding = fragment.libraryBinding ?: return
        val lc = fragment.libraryListController
        val hasGames = lc.getVisibleGames().isNotEmpty()
        currentBinding.libraryRecycler.visibility = if (hasGames) View.VISIBLE else View.GONE
        if (hasGames && fragment.usesHorizontalPaging()) {
            currentBinding.libraryRecycler.post { updateFixedGridCardHeight() }
        } else if (hasGames && fragment.libraryUsesTabletPortraitCardSizing()) {
            currentBinding.libraryRecycler.post { updateTabletPortraitCardHeight() }
        }
        currentBinding.libraryEmpty.text =
            fragment.getString(if (lc.getAllGames().isEmpty()) com.core.R.string.game_empty else com.core.R.string.game_empty_search)
        currentBinding.libraryEmpty.visibility = if (hasGames) View.GONE else View.VISIBLE
        if (hasGames) scheduleLoadUntilViewportFilled()
    }

    fun scheduleLoadUntilViewportFilled() {
        val currentBinding = fragment.libraryBinding ?: return
        val lc = fragment.libraryListController
        if (lc.isViewportFillCheckPending() || fragment.usesHorizontalPaging()
            || lc.isLoading() || lc.isFullyLoaded()
            || lc.getVisibleGames().size >= lc.getFilteredGames().size
        ) {
            return
        }
        lc.setViewportFillCheckPending(true)
        val recyclerView = currentBinding.libraryRecycler
        val observer = recyclerView.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val vto = recyclerView.viewTreeObserver
                vto.removeOnPreDrawListener(this)
                val currentLc = fragment.libraryListController
                val currentBinding = fragment.libraryBinding
                currentLc.setViewportFillCheckPending(false)
                if (currentBinding == null || currentLc.isLoading() || currentLc.isFullyLoaded()
                    || currentLc.getVisibleGames().size >= currentLc.getFilteredGames().size
                ) {
                    return true
                }
                // 列表无法向下滚动时，说明内容未填满容器，加载下一页
                if (!recyclerView.canScrollVertically(1)) {
                    currentLc.loadNextPage()
                }
                return true
            }
        }
        observer.addOnPreDrawListener(listener)
    }

    fun handleLoadMoreDragWhenNotScrollable(recyclerView: RecyclerView, event: MotionEvent) {
        val lc = fragment.libraryListController
        if (lc.isLoading() || lc.isFullyLoaded()
            || lc.getFilteredGames().isEmpty()
            || lc.getVisibleGames().size >= lc.getFilteredGames().size
        ) {
            loadMoreDragCandidate = false
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                loadMoreDragStartY = event.y
                loadMoreDragCandidate = !recyclerView.canScrollVertically(1)
            }
            MotionEvent.ACTION_MOVE -> {
                if (loadMoreDragCandidate && loadMoreDragStartY - event.y > LauncherTheme.dp(fragment.requireContext(), 48)) {
                    loadMoreDragCandidate = false
                    loadNextPage()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                loadMoreDragCandidate = false
            }
        }
    }

    fun updateFixedGridCardHeight() {
        if (fragment.libraryPosterGridStyle) return
        val currentBinding = fragment.libraryBinding ?: return
        val currentAdapter = fragment.libraryAdapter ?: return
        val rows = fragment.libraryFixedGridRows()
        val height = currentBinding.libraryRecycler.height
        if (rows <= 0 || height <= 0) return
        val usableHeight = height
            - currentBinding.libraryRecycler.paddingTop
            - currentBinding.libraryRecycler.paddingBottom
        currentAdapter.setFixedCardHeight(Math.max(
            LauncherTheme.dp(fragment.requireContext(), 34),
            usableHeight / rows - LauncherTheme.dp(fragment.requireContext(), 10)
        ))
    }

    fun updateTabletPortraitCardHeight() {
        if (fragment.libraryPosterGridStyle || !fragment.libraryUsesTabletPortraitCardSizing()) return
        val currentBinding = fragment.libraryBinding ?: return
        val currentAdapter = fragment.libraryAdapter ?: return
        val recyclerView = currentBinding.libraryRecycler
        val recyclerWidth = recyclerView.width
        val columns = Math.max(1, fragment.libraryGridColumns())
        if (recyclerWidth <= 0) return
        val usableWidth = recyclerWidth - recyclerView.paddingLeft - recyclerView.paddingRight
        val totalHorizontalMargins = LauncherTheme.dp(fragment.requireContext(), 10) * columns
        val cardWidth = Math.max(1, (usableWidth - totalHorizontalMargins) / columns)
        val cardHeight = Math.round(cardWidth * 5f / 3f)
        currentAdapter.setFixedCardHeight(Math.max(LauncherTheme.dp(fragment.requireContext(), 34), cardHeight))
    }
}
