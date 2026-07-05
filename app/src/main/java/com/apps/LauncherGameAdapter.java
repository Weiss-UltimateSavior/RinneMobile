package com.apps;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ItemLauncherGameCardBinding;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.SafeImageLoader;
import com.yuki.yukihub.util.TimeFormatUtil;

import java.util.ArrayList;
import java.util.List;

public class LauncherGameAdapter extends RecyclerView.Adapter<LauncherGameAdapter.Holder> {
    public interface OnGameCardListener {
        void onGameClick(Game game);
        void onGameLongClick(Game game);
    }

    private final List<Game> games = new ArrayList<>();
    private OnGameCardListener listener;
    private long selectedGameId = -1L;

    public void setOnGameCardListener(OnGameCardListener listener) {
        this.listener = listener;
    }

    public void submit(List<Game> newGames) {
        games.clear();
        if (newGames != null) games.addAll(newGames);
        notifyDataSetChanged();
    }

    public void setSelectedGameId(long id) {
        selectedGameId = id;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemLauncherGameCardBinding binding = ItemLauncherGameCardBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        );
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Game game = games.get(position);
        holder.bind(game, game != null && game.id == selectedGameId);
    }

    @Override
    public int getItemCount() {
        return games.size();
    }

    final class Holder extends RecyclerView.ViewHolder {
        private final ItemLauncherGameCardBinding binding;

        Holder(ItemLauncherGameCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Game game, boolean selected) {
            if (game == null) return;
            binding.getRoot().setBackgroundResource(selected ? R.drawable.launcher_game_card_selected : R.drawable.launcher_game_card);
            binding.launcherGameTitle.setText(safeTitle(game));
            binding.launcherGamePlayStatus.setText(playStatus(game));
            binding.launcherGameInitial.setText(initial(game.title));
            LauncherTheme.textPrimary(binding.launcherGameTitle);
            LauncherTheme.textPrimary(binding.launcherGamePlayStatus);
            LauncherTheme.textPrimary(binding.launcherGameInitial);
            binding.launcherGameFavorite.setVisibility(game.favorite ? View.VISIBLE : View.GONE);
            bindCover(game);

            binding.getRoot().setOnClickListener(view -> {
                setSelectedGameId(game.id);
                if (listener != null) listener.onGameClick(game);
            });
            binding.getRoot().setOnLongClickListener(view -> {
                setSelectedGameId(game.id);
                if (listener != null) listener.onGameLongClick(game);
                return true;
            });
        }

        private void bindCover(Game game) {
            String cover = coverUri(game);
            binding.launcherGameCoverFrame.setClipToOutline(true);
            binding.launcherGameCover.setClipToOutline(true);
            binding.launcherGameCover.setImageDrawable(null);
            if (cover == null || cover.isEmpty()) {
                showCoverPlaceholder();
                return;
            }
            if (!SafeImageLoader.loadUri(binding.launcherGameCover, cover)) {
                showCoverPlaceholder();
                return;
            }
            binding.launcherGameCover.setVisibility(View.VISIBLE);
            binding.launcherGameInitial.setVisibility(View.GONE);
        }

        private void showCoverPlaceholder() {
            binding.launcherGameCover.setImageDrawable(null);
            binding.launcherGameCover.setVisibility(View.GONE);
            binding.launcherGameInitial.setVisibility(View.VISIBLE);
        }
    }

    private String safeTitle(Game game) {
        if (game == null || game.title == null || game.title.trim().isEmpty()) return "未命名游戏";
        return game.title.trim();
    }

    private String coverUri(Game game) {
        if (game == null) return "";
        if (game.coverPersistUri != null && !game.coverPersistUri.trim().isEmpty()) return game.coverPersistUri.trim();
        if (game.coverUri != null && !game.coverUri.trim().isEmpty()) return game.coverUri.trim();
        return "";
    }

    private String playStatus(Game game) {
        if (game == null || game.totalPlayTime <= 0L) return "未游玩";
        return TimeFormatUtil.playTime(game.totalPlayTime);
    }

    private String initial(String title) {
        if (title == null) return "游";
        String trimmed = title.trim();
        if (trimmed.isEmpty()) return "游";
        int end = trimmed.offsetByCodePoints(0, 1);
        return trimmed.substring(0, end);
    }
}
