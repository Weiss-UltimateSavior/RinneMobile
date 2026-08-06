package com.apps.leaderboard

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.apps.common.LauncherInsetsHelper
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherLeaderboardBinding
import com.core.launcherbridge.LauncherAuthBridge
import com.core.launcherbridge.LeaderboardCallback
import com.core.launcherbridge.LeaderboardEntry
import com.core.util.TimeFormatUtil

/**
 * 游玩时长排行榜页（重构计划 9.9 阶段 111 自 LauncherLeaderboardActivity 抽取）。
 *
 * 竖屏由 [LauncherLeaderboardActivity] 薄宿主承载，HD 由
 * [com.apps.HDModel.HdProfileFragment] 作为子 Fragment 承载。
 */
class LauncherLeaderboardFragment : Fragment() {
    private var adapter: LauncherLeaderboardAdapter? = null
    private var binding: ActivityLauncherLeaderboardBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherLeaderboardBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        LauncherInsetsHelper.applyTopAndBottomInsets(view, view)
        LauncherTheme.applyPrimaryTone(view)
        val listAdapter = LauncherLeaderboardAdapter()
        adapter = listAdapter
        currentBinding.leaderboardList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = listAdapter
        }
        LauncherAuthBridge.fetchPlayTimeLeaderboard(
            requireContext(),
            object : LeaderboardCallback {
                override fun onSuccess(entries: List<LeaderboardEntry>) = show(entries)

                override fun onError(message: String) {
                    if (!isAdded) return
                    show(emptyList())
                    currentBinding.leaderboardState.setText(R.string.social_leaderboard_unavailable)
                    currentBinding.leaderboardState.visibility = View.VISIBLE
                }
            },
        )
    }

    override fun onDestroyView() {
        adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun show(source: List<LeaderboardEntry>?) {
        val currentBinding = binding ?: return
        val entries = (1..15).map { rank ->
            source?.firstOrNull { it.rank == rank } ?: LeaderboardEntry(
                rank,
                getString(R.string.social_no_rank),
                -1,
            )
        }
        val groups = arrayOf(
            arrayOf<View>(
                currentBinding.leaderboardFirst,
                currentBinding.leaderboardFirstRank,
                currentBinding.leaderboardFirstName,
                currentBinding.leaderboardFirstDuration,
            ),
            arrayOf<View>(
                currentBinding.leaderboardSecond,
                currentBinding.leaderboardSecondRank,
                currentBinding.leaderboardSecondName,
                currentBinding.leaderboardSecondDuration,
            ),
            arrayOf<View>(
                currentBinding.leaderboardThird,
                currentBinding.leaderboardThirdRank,
                currentBinding.leaderboardThirdName,
                currentBinding.leaderboardThirdDuration,
            ),
        )
        repeat(3) { i -> bind(entries[i], groups[i]) }
        currentBinding.leaderboardTopThree.visibility = View.VISIBLE
        currentBinding.leaderboardState.visibility = View.GONE
        adapter?.submit(entries.drop(3))
    }

    private fun bind(entry: LeaderboardEntry, views: Array<View>) {
        views[0].visibility = View.VISIBLE
        (views[1] as ImageView).imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                requireContext(),
                if (entry.rank == 1) R.color.launcher_rank_gold_color
                else if (entry.rank == 2) R.color.launcher_rank_silver_color
                else R.color.launcher_rank_bronze_color,
            ),
        )
        (views[2] as TextView).text = entry.username
        (views[3] as TextView).apply {
            visibility = if (entry.totalDurationMs < 0) View.GONE else View.VISIBLE
            if (entry.totalDurationMs >= 0) text = TimeFormatUtil.playTime(entry.totalDurationMs)
        }
    }
}
