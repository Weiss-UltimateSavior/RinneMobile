package com.apps;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yuki.yukihub.R;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;
import com.yuki.yukihub.util.TimeFormatUtil;

import java.util.ArrayList;
import java.util.List;

final class LauncherLeaderboardAdapter extends RecyclerView.Adapter<LauncherLeaderboardAdapter.Holder> {
    private final List<LauncherAuthBridge.LeaderboardEntry> entries = new ArrayList<>();

    void submit(List<LauncherAuthBridge.LeaderboardEntry> items) {
        entries.clear();
        if (items != null) entries.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_launcher_leaderboard_entry, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        LauncherAuthBridge.LeaderboardEntry entry = entries.get(position);
        holder.rank.setText(String.valueOf(entry.rank));
        holder.rank.setBackground(LauncherTheme.circle(holder.rank.getContext()));
        holder.rank.setTextColor(LauncherTheme.onPrimary(holder.rank.getContext()));
        holder.name.setText(entry.username);
        holder.duration.setVisibility(entry.totalDurationMs < 0L ? View.GONE : View.VISIBLE);
        if (entry.totalDurationMs >= 0L) holder.duration.setText(TimeFormatUtil.playTime(entry.totalDurationMs));
    }

    @Override public int getItemCount() { return entries.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView rank, name, duration;
        Holder(View view) { super(view); rank = view.findViewById(R.id.leaderboardItemRank); name = view.findViewById(R.id.leaderboardItemName); duration = view.findViewById(R.id.leaderboardItemDuration); }
    }
}
