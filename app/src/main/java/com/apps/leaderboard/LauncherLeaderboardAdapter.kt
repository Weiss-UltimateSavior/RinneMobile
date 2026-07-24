package com.apps.leaderboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.yuki.yukihub.R
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge
import com.yuki.yukihub.util.TimeFormatUtil

class LauncherLeaderboardAdapter : RecyclerView.Adapter<LauncherLeaderboardAdapter.Holder>() {
    private val entries = mutableListOf<LauncherAuthBridge.LeaderboardEntry>()

    fun submit(items: List<LauncherAuthBridge.LeaderboardEntry>?) {
        entries.clear()
        items?.let(entries::addAll)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_launcher_leaderboard_entry, parent, false)
        LauncherTabletPortraitScaler.apply(view)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        holder.rank.text = entry.rank.toString()
        holder.rank.background = LauncherTheme.circle(holder.rank.context)
        holder.rank.setTextColor(LauncherTheme.onPrimary(holder.rank.context))
        holder.name.text = entry.username
        holder.duration.visibility = if (entry.totalDurationMs < 0L) View.GONE else View.VISIBLE
        if (entry.totalDurationMs >= 0L) holder.duration.text = TimeFormatUtil.playTime(entry.totalDurationMs)
    }

    override fun getItemCount(): Int = entries.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val rank: TextView = view.findViewById(R.id.leaderboardItemRank)
        val name: TextView = view.findViewById(R.id.leaderboardItemName)
        val duration: TextView = view.findViewById(R.id.leaderboardItemDuration)
    }
}
