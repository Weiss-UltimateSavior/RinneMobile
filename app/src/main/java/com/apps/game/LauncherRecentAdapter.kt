package com.apps.game

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.apps.data.LauncherRepository
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.yuki.yukihub.R

class LauncherRecentAdapter : RecyclerView.Adapter<LauncherRecentAdapter.Holder>() {
    private val items = mutableListOf<LauncherRepository.RecentItem>()

    fun submit(newItems: List<LauncherRepository.RecentItem>?) {
        items.clear()
        newItems?.let(items::addAll)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_launcher_recent, parent, false)
        LauncherTabletPortraitScaler.apply(view)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.icon.text = item.iconText
        holder.title.text = item.title
        holder.meta.text = item.timeAndDuration
        holder.status.text = item.status
        LauncherTheme.textPrimary(holder.icon)
        LauncherTheme.textPrimary(holder.status)
    }

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: TextView = itemView.findViewById(R.id.recentIcon)
        val title: TextView = itemView.findViewById(R.id.recentTitle)
        val meta: TextView = itemView.findViewById(R.id.recentMeta)
        val status: TextView = itemView.findViewById(R.id.recentStatus)
    }
}
