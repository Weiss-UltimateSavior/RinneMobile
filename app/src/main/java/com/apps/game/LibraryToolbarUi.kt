package com.apps.game

import android.content.Context
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.FragmentLauncherLibraryBinding

/**
 * 游戏库工具栏 UI：搜索按钮/搜索输入（300ms 防抖）、同步设置菜单、分类折叠按钮、
 * 分类 chip 渲染与海报样式切换（含持久化与 RecyclerView idle 后应用）。
 *
 * 生命周期由 [LauncherLibraryFragment] 包裹；binding/分类状态等经 fragment 的
 * internal 访问器获取，View 销毁后返回 null 作为守卫（§8 协调类模式）。
 */
internal class LibraryToolbarUi(private val fragment: LauncherLibraryFragment) {

    /** 分类栏折叠状态（原 Fragment 私有字段迁移）。 */
    private var categoriesCollapsed: Boolean = true

    /** 搜索输入防抖任务（原 Fragment 私有字段迁移）。 */
    private var searchDebounce: Runnable? = null

    fun setup() {
        // 初始折叠状态（原 onViewCreated 中 categoriesCollapsed 初始化与分类栏显隐）
        categoriesCollapsed = fragment.libraryAreCategoriesCollapsedByDefault()
        fragment.libraryBinding?.libraryCategoryScroll?.visibility = if (categoriesCollapsed) View.GONE else View.VISIBLE
        val currentBinding = fragment.libraryBinding ?: return
        currentBinding.librarySearchButton.setOnClickListener {
            val show = currentBinding.librarySearchInput.visibility != View.VISIBLE
            currentBinding.librarySearchInput.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                currentBinding.librarySearchInput.requestFocus()
                val imm = fragment.requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(currentBinding.librarySearchInput, InputMethodManager.SHOW_IMPLICIT)
            } else {
                currentBinding.librarySearchInput.setText("")
                val imm = fragment.requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(currentBinding.librarySearchInput.windowToken, 0)
            }
            renderToolbarButtonState()
        }
        currentBinding.librarySyncButton.setOnClickListener { showLibrarySettingsMenu() }
        currentBinding.libraryCollapseButton.setOnClickListener {
            categoriesCollapsed = !categoriesCollapsed
            currentBinding.libraryCategoryScroll.visibility = if (categoriesCollapsed) View.GONE else View.VISIBLE
            renderToolbarButtonState()
        }
        currentBinding.librarySearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                fragment.librarySearchQuery = if (s == null) "" else s.toString().trim { it <= ' ' }
                if (searchDebounce != null) fragment.libraryMainQueue.removeCallbacks(searchDebounce!!)
                searchDebounce = Runnable {
                    if (!fragment.isAdded || fragment.libraryBinding == null) return@Runnable
                    fragment.libraryApplyFilters()
                }
                fragment.libraryMainQueue.postDelayed(searchDebounce!!, 300)
            }
            override fun afterTextChanged(s: Editable?) { }
        })
        renderToolbarButtonState()
    }

    private fun showLibrarySettingsMenu() {
        val styleLabel = fragment.getString(if (fragment.libraryPosterGridStyle)
            R.string.game_library_horizontal_cards else R.string.game_library_poster_grid)
        LauncherDialogFactory.showStandardActionChoices(
            fragment.requireContext(), fragment.getString(R.string.game_library_settings),
            arrayOf(fragment.getString(R.string.game_library_sync_all), styleLabel,
                fragment.getString(R.string.game_library_clear))
        ) { index ->
            when (index) {
                0 -> fragment.librarySyncController.showSyncDataConfirmDialog()
                1 -> togglePosterGridStyle()
                2 -> fragment.libraryConfirmClearList()
            }
        }
    }

    private fun togglePosterGridStyle() {
        fragment.libraryPosterGridStyle = !fragment.libraryPosterGridStyle
        fragment.requireContext().applicationContext
            .getSharedPreferences(LauncherLibraryFragment.LIBRARY_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(fragment.libraryGetPosterStylePreferenceKey(), fragment.libraryPosterGridStyle).apply()
        val currentBinding = fragment.libraryBinding
        if (currentBinding != null) {
            applyPosterStyleWhenRecyclerIsIdle(currentBinding, fragment.libraryPosterGridStyle)
        }
        Toast.makeText(
            fragment.requireContext(),
            fragment.getString(if (fragment.libraryPosterGridStyle)
                R.string.game_library_switched_poster else R.string.game_library_switched_horizontal),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applyPosterStyleWhenRecyclerIsIdle(
        currentBinding: FragmentLauncherLibraryBinding,
        posterStyle: Boolean,
    ) {
        val recyclerView = currentBinding.libraryRecycler
        recyclerView.post {
            if (fragment.libraryBinding !== currentBinding || fragment.libraryPosterGridStyle != posterStyle) return@post
            if (recyclerView.isComputingLayout) {
                applyPosterStyleWhenRecyclerIsIdle(currentBinding, posterStyle)
                return@post
            }
            recyclerView.stopScroll()
            recyclerView.itemAnimator?.endAnimations()
            fragment.libraryGridManager?.spanCount = fragment.libraryActiveGridColumns()
            fragment.libraryAdapter?.setPosterStyle(posterStyle)
            recyclerView.recycledViewPool.clear()
            recyclerView.scrollToPosition(0)
            recyclerView.invalidateItemDecorations()
            recyclerView.post {
                if (fragment.libraryBinding !== currentBinding || fragment.libraryPosterGridStyle != posterStyle) return@post
                when {
                    !posterStyle && fragment.usesHorizontalPaging() -> fragment.libraryPagingHelper.updateFixedGridCardHeight()
                    !posterStyle && fragment.libraryUsesTabletPortraitCardSizing() -> fragment.libraryPagingHelper.updateTabletPortraitCardHeight()
                }
            }
        }
    }

    fun renderCategories() {
        val currentBinding = fragment.libraryBinding ?: return
        currentBinding.libraryCategoryRow.removeAllViews()
        addCategoryChip(fragment.getString(R.string.game_common_all), "")
        for (category in fragment.libraryCategories) {
            addCategoryChip(category.label, category.value)
        }
    }

    private fun addCategoryChip(label: String?, value: String?) {
        val chip = TextView(fragment.requireContext())
        val selected = value == fragment.librarySelectedCategory
        chip.text = label
        chip.isSingleLine = true
        chip.gravity = Gravity.CENTER
        chip.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_PX,
            fragment.resources.getDimension(com.core.R.dimen.launcher_library_category_text_size)
        )
        chip.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        chip.tag = value
        if (selected) {
            chip.setTextColor(LauncherTheme.onPrimary(fragment.requireContext()))
            chip.background = LauncherTheme.selectedChip(fragment.requireContext())
        } else {
            LauncherTheme.menuItem(chip)
        }
        val chipHorizontalPadding = fragment.resources.getDimensionPixelSize(
            com.core.R.dimen.launcher_library_category_horizontal_padding
        )
        chip.setPadding(chipHorizontalPadding, 0, chipHorizontalPadding, 0)
        chip.setOnClickListener {
            fragment.librarySelectedCategory = value ?: ""
            renderCategories()
            fragment.libraryApplyFilters()
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            fragment.resources.getDimensionPixelSize(com.core.R.dimen.launcher_library_category_chip_height)
        )
        lp.setMargins(
            0, 0,
            fragment.resources.getDimensionPixelSize(com.core.R.dimen.launcher_library_category_chip_margin_end),
            0
        )
        val currentBinding = fragment.libraryBinding ?: return
        currentBinding.libraryCategoryRow.addView(chip, lp)
        if (fragment.libraryUsePortraitLibraryScaler()) {
            LauncherTabletPortraitScaler.apply(chip)
        }
    }

    private fun renderToolbarButtonState() {
        val currentBinding = fragment.libraryBinding ?: return
        applyToolbarIconTone(currentBinding.librarySyncButton)
        applyToolbarIconTone(currentBinding.librarySearchButton)
        applyToolbarIconTone(currentBinding.libraryCollapseButton)
    }

    private fun applyToolbarIconTone(view: ImageView) {
        view.imageTintList = ColorStateList.valueOf(LauncherTheme.primary(fragment.requireContext()))
        view.background = null
    }
}
