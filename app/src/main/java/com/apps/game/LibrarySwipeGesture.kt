package com.apps.game

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.core.R
import kotlin.math.abs

/**
 * 游戏库横滑手势：分类左右切换（非横向分页）或横向翻页（横向分页），
 * 附带分类栏滚动到选中项与列表淡入动画。
 *
 * 生命周期由 [LauncherLibraryFragment] 包裹；binding/分类状态经 fragment 的
 * internal 访问器获取，View 销毁后返回 null 作为守卫（§8 协调类模式）。
 */
internal class LibrarySwipeGesture(private val fragment: LauncherLibraryFragment) {

    private var swipeGestureDetector: GestureDetector? = null
    private var swipeConsumed: Boolean = false

    /** RecyclerView 触摸监听引用，供 cleanup() 解除（§8 生命周期清理）。 */
    private var recyclerTouchListener: RecyclerView.OnItemTouchListener? = null

    /** 消费一次已处理的横滑（供卡片点击/长按守卫使用，原 Fragment onGameClick/onGameLongClick 语义）。 */
    fun consumeSwipe(): Boolean {
        val consumed = swipeConsumed
        swipeConsumed = false
        return consumed
    }

    fun setup() {
        swipeGestureDetector = GestureDetector(fragment.requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            private val swipeThreshold = 80
            private val swipeVelocity = 200

            override fun onDown(event: MotionEvent): Boolean {
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (abs(diffX) > abs(diffY) && abs(diffX) > swipeThreshold && abs(velocityX) > swipeVelocity) {
                    val handled = if (diffX < 0) handleSwipeLeft() else handleSwipeRight()
                    if (handled) swipeConsumed = true
                    return handled
                }
                return false
            }
        })

        val currentBinding = fragment.libraryBinding ?: return
        // RecyclerView 区域：通过 OnItemTouchListener 获取触摸事件
        val touchListener = object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                swipeGestureDetector!!.onTouchEvent(e)
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                swipeGestureDetector!!.onTouchEvent(e)
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        }
        recyclerTouchListener = touchListener
        currentBinding.libraryRecycler.addOnItemTouchListener(touchListener)

        // 非列表区域（背景、分类栏、空提示等）
        currentBinding.root.setOnTouchListener { _, event ->
            swipeGestureDetector!!.onTouchEvent(event)
            false
        }
        currentBinding.libraryContent.setOnTouchListener { _, event ->
            swipeGestureDetector!!.onTouchEvent(event)
            false
        }
        currentBinding.libraryEmpty.setOnTouchListener { _, event ->
            swipeGestureDetector!!.onTouchEvent(event)
            false
        }
    }

    /** 解除触摸监听并释放手势检测器（Fragment onDestroyView 调用，§8 生命周期清理）。 */
    fun cleanup() {
        val currentBinding = fragment.libraryBinding
        if (currentBinding != null) {
            val touchListener = recyclerTouchListener
            if (touchListener != null) {
                currentBinding.libraryRecycler.removeOnItemTouchListener(touchListener)
            }
            currentBinding.root.setOnTouchListener(null)
            currentBinding.libraryContent.setOnTouchListener(null)
            currentBinding.libraryEmpty.setOnTouchListener(null)
        }
        recyclerTouchListener = null
        swipeGestureDetector = null
    }

    private fun handleSwipeLeft(): Boolean {
        if (fragment.usesHorizontalPaging()) return fragment.libraryPagingHelper.showNextPage()
        return switchToNextCategory()
    }

    private fun handleSwipeRight(): Boolean {
        if (fragment.usesHorizontalPaging()) return fragment.libraryPagingHelper.showPreviousPage()
        return switchToPreviousCategory()
    }

    private fun getFlatCategories(): List<CategoryOption> {
        val flat = mutableListOf<CategoryOption>()
        flat.add(CategoryOption(fragment.getString(R.string.game_common_all), ""))
        flat.addAll(fragment.libraryCategories)
        return flat
    }

    private fun getCurrentCategoryIndex(): Int {
        val flat = getFlatCategories()
        for (i in flat.indices) {
            if (flat[i].value == fragment.librarySelectedCategory) return i
        }
        return 0
    }

    private fun switchToNextCategory(): Boolean {
        val flat = getFlatCategories()
        val idx = getCurrentCategoryIndex()
        if (idx < flat.size - 1) {
            fragment.librarySelectedCategory = flat[idx + 1].value
            fragment.libraryRenderCategories()
            fragment.libraryApplyFilters()
            animateCategorySwitch()
            return true
        }
        return false
    }

    private fun switchToPreviousCategory(): Boolean {
        val flat = getFlatCategories()
        val idx = getCurrentCategoryIndex()
        if (idx > 0) {
            fragment.librarySelectedCategory = flat[idx - 1].value
            fragment.libraryRenderCategories()
            fragment.libraryApplyFilters()
            animateCategorySwitch()
            return true
        }
        return false
    }

    private fun animateCategorySwitch() {
        val currentBinding = fragment.libraryBinding ?: return
        // 滚动分类栏到当前选中项
        val categoryScroll: HorizontalScrollView = currentBinding.libraryCategoryScroll
        for (i in 0 until currentBinding.libraryCategoryRow.childCount) {
            val child = currentBinding.libraryCategoryRow.getChildAt(i)
            if (child is TextView) {
                val tag = child.tag
                val catValue = tag?.toString() ?: ""
                if (catValue == fragment.librarySelectedCategory) {
                    val scrollX = child.left - categoryScroll.width / 2 + child.width / 2
                    categoryScroll.smoothScrollTo(scrollX, 0)
                    break
                }
            }
        }
        // 列表淡入动画
        currentBinding.libraryRecycler.alpha = 0.7f
        currentBinding.libraryRecycler.animate().alpha(1f).setDuration(250)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
    }
}
