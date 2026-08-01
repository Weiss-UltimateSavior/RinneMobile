package com.apps.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.apps.agent.LocalAgentActivity
import com.apps.data.LauncherRepository
import com.apps.data.LauncherViewModel
import com.apps.game.LauncherSaveCategoryActivity
import com.apps.settings.LauncherToolboxActivity
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.databinding.ItemLauncherSquareFavoriteBinding
import com.core.databinding.ItemLauncherSquareRecentBinding
import com.core.databinding.ViewLauncherSquareHomeListsBinding
import com.core.util.SafeImageLoader

/**
 * Home variant with a horizontal favorites strip and an eight-item,
 * two-column play-history grid.
 */
class LauncherSquareGridHomeFragment : LauncherHomeFragment() {
    private var squareListsBinding: ViewLauncherSquareHomeListsBinding? = null

    override fun onHomeLayoutReady() {
        val currentBinding = binding ?: return
        currentBinding.homeActionsGrid.visibility = View.GONE
        currentBinding.actionProfileMenu.setOnClickListener { showUnifiedFunctionMenu() }
        currentBinding.homeDefaultRecentContent.visibility = View.GONE
        val section = currentBinding.homeSquareListsHost
        section.visibility = View.VISIBLE
        squareListsBinding = ViewLauncherSquareHomeListsBinding.inflate(
            LayoutInflater.from(requireContext()),
            section,
            true,
        ).also {
            if (usePortraitTabletScaler()) LauncherTabletPortraitScaler.apply(it.root)
            LauncherTheme.applyPrimaryTone(it.root)
        }
    }

    private fun showUnifiedFunctionMenu() {
        val choices: Array<CharSequence> = arrayOf(
            getString(com.core.R.string.launcher_action_save_slot),
            getString(com.core.R.string.launcher_action_resources),
            getString(com.core.R.string.launcher_action_toolbox),
            getString(com.core.R.string.launcher_action_agent),
            getString(com.core.R.string.home_square_more_settings),
        )
        LauncherDialogFactory.showStandardActionChoices(
            requireContext(),
            getString(com.core.R.string.home_quick_features),
            choices,
        ) { index ->
            when (index) {
                0 -> startLauncherActivity(
                    Intent(requireContext(), LauncherSaveCategoryActivity::class.java),
                )
                1 -> showResourceStationDialog()
                2 -> startLauncherActivity(
                    Intent(requireContext(), LauncherToolboxActivity::class.java),
                )
                3 -> startLauncherActivity(
                    Intent(requireContext(), LocalAgentActivity::class.java),
                )
                4 -> LauncherHomeAccountBottomSheet.show(parentFragmentManager)
            }
        }
    }

    override fun renderHomeLists(state: LauncherViewModel.LauncherState) {
        renderFavorites(state.favoriteItems)
        renderPlayHistory(state.recentItems.take(recentDisplayLimit().coerceAtLeast(0)))
    }

    override fun recentDisplayLimit(): Int = 8

    override fun recentGridColumns(): Int = 2

    override fun includeFavoriteItems(): Boolean = HomeStyle.SQUARE_GRID.needsFavorites

    override fun applyIconTone() {
        val menu = binding?.actionProfileMenu ?: return
        LauncherTheme.applyCardCircleIcon(menu, requireContext())
    }

    override fun onDestroyView() {
        squareListsBinding = null
        super.onDestroyView()
    }

    private fun renderFavorites(items: List<LauncherRepository.FavoriteItem>) {
        val lists = squareListsBinding ?: return
        lists.squareFavoriteList.removeAllViews()
        lists.squareFavoriteEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        lists.squareFavoriteScroll.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(requireContext())
        val itemGap = LauncherTabletPortraitScaler.dp(requireContext(), 4)
        items.forEachIndexed { index, item ->
            val card = ItemLauncherSquareFavoriteBinding.inflate(
                inflater,
                lists.squareFavoriteList,
                false,
            )
            if (usePortraitTabletScaler()) LauncherTabletPortraitScaler.apply(card.root)
            card.squareFavoriteTitle.text = item.title
            card.squareFavoritePlayTime.text = item.playTime
            card.squareFavoriteInitial.text = item.iconText
            card.squareFavoriteInitial.setTextColor(LauncherTheme.primary(requireContext()))
            if (!SafeImageLoader.loadUri(
                    card.squareFavoriteCover,
                    item.coverUri,
                    SafeImageLoader.Callback { success ->
                        binding ?: return@Callback
                        card.squareFavoriteCover.visibility = if (success) View.VISIBLE else View.GONE
                        card.squareFavoriteInitial.visibility = if (success) View.GONE else View.VISIBLE
                    },
                )) {
                card.squareFavoriteCover.visibility = View.GONE
                card.squareFavoriteInitial.visibility = View.VISIBLE
            }
            card.root.setOnClickListener { confirmLaunchGame(item.gameId, item.title) }
            card.root.layoutParams = (card.root.layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = if (index == 0) 0 else itemGap
                marginEnd = itemGap
            }
            LauncherTheme.applyPrimaryTone(card.root)
            lists.squareFavoriteList.addView(card.root)
        }
    }

    private fun renderPlayHistory(items: List<LauncherRepository.RecentItem>) {
        val lists = squareListsBinding ?: return
        lists.squareRecentGrid.removeAllViews()
        lists.squareRecentEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        lists.squareRecentGrid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(requireContext())
        val columns = recentGridColumns().coerceAtLeast(1)
        val columnGap = LauncherTabletPortraitScaler.dp(requireContext(), 4)
        val rowGap = LauncherTabletPortraitScaler.dp(requireContext(), 8)
        var row: LinearLayout? = null
        items.forEachIndexed { index, item ->
            if (index % columns == 0) {
                row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }
                lists.squareRecentGrid.addView(row)
            }
            val card = ItemLauncherSquareRecentBinding.inflate(inflater, row, false)
            if (usePortraitTabletScaler()) LauncherTabletPortraitScaler.apply(card.root)
            card.squareRecentDate.text = item.dateTime
            card.squareRecentDuration.text = item.duration
            card.squareRecentTitle.text = item.title
            card.squareRecentEmulator.text = item.status
            card.squareRecentDuration.setTextColor(LauncherTheme.primary(requireContext()))
            card.root.setOnClickListener { confirmLaunchRecentGame(item) }
            card.root.setOnLongClickListener {
                confirmDeleteRecentItem(item)
                true
            }
            card.root.layoutParams = (card.root.layoutParams as LinearLayout.LayoutParams).apply {
                width = 0
                weight = 1f
                marginStart = if (index % columns == 0) 0 else columnGap
                marginEnd = if (index % columns == 0) columnGap else 0
                bottomMargin = rowGap
            }
            LauncherTheme.applyPrimaryTone(card.root)
            row?.addView(card.root)
        }
        val missing = (columns - items.size % columns) % columns
        repeat(missing) {
            row?.addView(View(requireContext()), LinearLayout.LayoutParams(0, 0, 1f).apply {
                marginStart = columnGap
            })
        }
    }
}
