package com.apps;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yuki.yukihub.R;

import java.util.ArrayList;
import java.util.List;

public class LauncherRecentAdapter extends RecyclerView.Adapter<LauncherRecentAdapter.Holder> {
    private final List<LauncherRepository.RecentItem> items = new ArrayList<>();

    public void submit(List<LauncherRepository.RecentItem> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_launcher_recent, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        LauncherRepository.RecentItem item = items.get(position);
        holder.icon.setText(item.iconText);
        holder.title.setText(item.title);
        holder.meta.setText(item.timeAndDuration);
        holder.status.setText(item.status);
        LauncherTheme.textPrimary(holder.icon);
        LauncherTheme.textPrimary(holder.status);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView icon;
        final TextView title;
        final TextView meta;
        final TextView status;

        Holder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.recentIcon);
            title = itemView.findViewById(R.id.recentTitle);
            meta = itemView.findViewById(R.id.recentMeta);
            status = itemView.findViewById(R.id.recentStatus);
        }
    }
}
