package com.apps;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ItemLauncherGameCardBinding;
import com.yuki.yukihub.model.Game;
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
    private int fixedCardHeight;

    public void setOnGameCardListener(OnGameCardListener listener) {
        this.listener = listener;
    }

    /** Allows landscape grids to reuse the card while supplying their own aspect ratio. */
    public void setFixedCardHeight(int heightPx) {
        int newHeight = Math.max(0, heightPx);
        if (newHeight == fixedCardHeight) return;
        fixedCardHeight = newHeight;
        notifyDataSetChanged();
    }

    public void submit(List<Game> newGames) {
    submit(newGames, false);
}

public void submit(List<Game> newGames, boolean forceFullRefresh) {
    if (newGames == null) newGames = new ArrayList<>();

    final List<Game> oldGames = new ArrayList<>(games);
    games.clear();
    games.addAll(newGames);

    // 批量同步封面后强制刷新当前页面所有卡片
    if (forceFullRefresh) {
        notifyDataSetChanged();
        return;
    }

    DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
        @Override
        public int getOldListSize() { return oldGames.size(); }

        @Override
        public int getNewListSize() { return games.size(); }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            Game o = oldGames.get(oldPos), n = games.get(newPos);
            return o != null && n != null && o.id == n.id;
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            Game o = oldGames.get(oldPos), n = games.get(newPos);
            if (o == null || n == null) return false;
            return o.id == n.id
                    && eq(o.title, n.title)
                    && o.totalPlayTime == n.totalPlayTime
                    && eq(o.playStatus, n.playStatus)
                    && o.favorite == n.favorite
                    && eq(o.coverPersistUri, n.coverPersistUri)
                    && eq(o.coverUri, n.coverUri);
        }
    });
    result.dispatchUpdatesTo(this);
}

    public void setSelectedGameId(long id) {
        long oldId = selectedGameId;
        selectedGameId = id;
        for (int i = 0; i < games.size(); i++) {
            Game g = games.get(i);
            if (g != null && (g.id == oldId || g.id == id)) {
                notifyItemChanged(i);
            }
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemLauncherGameCardBinding binding = ItemLauncherGameCardBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        );
        if (fixedCardHeight > 0) {
            ViewGroup.LayoutParams params = binding.getRoot().getLayoutParams();
            if (params != null) params.height = fixedCardHeight;
        }
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

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        holder.recycle();
    }

    final class Holder extends RecyclerView.ViewHolder {
        private final ItemLauncherGameCardBinding binding;

        Holder(ItemLauncherGameCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Game game, boolean selected) {
            if (game == null) return;
            if (fixedCardHeight > 0 && binding.getRoot().getLayoutParams().height != fixedCardHeight) {
                ViewGroup.LayoutParams params = binding.getRoot().getLayoutParams();
                params.height = fixedCardHeight;
                binding.getRoot().setLayoutParams(params);
            }
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

        void recycle() {
            LauncherCoverLoader.clear(binding.launcherGameCover);
        }

        private void bindCover(Game game) {
            String cover = coverUri(game);
            binding.launcherGameCoverFrame.setClipToOutline(true);
            binding.launcherGameCover.setClipToOutline(true);
            LauncherCoverLoader.clear(binding.launcherGameCover);
            if (cover == null || cover.isEmpty()) {
                showCoverPlaceholder();
                return;
            }
            // 异步加载：加载期间先显示首字母占位，成功后切换为封面
            showCoverPlaceholder();
            LauncherCoverLoader.loadInto(binding.launcherGameCover, cover, success -> {
                if (success) {
                    binding.launcherGameCover.setVisibility(View.VISIBLE);
                    binding.launcherGameInitial.setVisibility(View.GONE);
                }
            });
        }

        private void showCoverPlaceholder() {
            binding.launcherGameCover.setImageDrawable(null);
            binding.launcherGameCover.setVisibility(View.GONE);
            binding.launcherGameInitial.setVisibility(View.VISIBLE);
        }
    }

    private static boolean eq(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
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
