package com.apps.PadUi;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.apps.widget.LauncherCoverLoader;
import com.apps.theme.LauncherTheme;
import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ItemPadGameCardBinding;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.TimeFormatUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 横屏游戏库专用适配器。
 * 卡片高度由 {@link PadGameCardView} 的 onMeasure 自动计算，无需外部设置 fixedCardHeight。
 */
public class PadGameCardAdapter extends RecyclerView.Adapter<PadGameCardAdapter.Holder> {

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

    public void setFixedCardHeight(int height) {
        int normalizedHeight = Math.max(0, height);
        if (fixedCardHeight == normalizedHeight) return;
        fixedCardHeight = normalizedHeight;
        notifyItemRangeChanged(0, games.size());
    }

    public void submit(List<Game> newGames) {
        if (newGames == null) newGames = new ArrayList<>();
        final List<Game> oldGames = new ArrayList<>(games);
        games.clear();
        games.addAll(newGames);
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
        ItemPadGameCardBinding binding = ItemPadGameCardBinding.inflate(
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

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        holder.recycle();
    }

    final class Holder extends RecyclerView.ViewHolder {
        private final ItemPadGameCardBinding binding;

        Holder(ItemPadGameCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Game game, boolean selected) {
            if (game == null) return;
            binding.getRoot().setFixedCardHeight(fixedCardHeight);
            binding.getRoot().setBackgroundResource(
                    selected ? R.drawable.launcher_game_card_selected : R.drawable.launcher_game_card);
            binding.padGameTitle.setText(safeTitle(game));
            binding.padGamePlayStatus.setText(playStatus(game));
            binding.padGameInitial.setText(initial(game.title));
            LauncherTheme.textPrimary(binding.padGameTitle);
            LauncherTheme.textPrimary(binding.padGamePlayStatus);
            LauncherTheme.textPrimary(binding.padGameInitial);
            binding.padGameFavorite.setVisibility(game.favorite ? View.VISIBLE : View.GONE);
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
            LauncherCoverLoader.clear(binding.padGameCover);
        }

        private void bindCover(Game game) {
            String cover = coverUri(game);
            binding.padGameCoverFrame.setClipToOutline(true);
            binding.padGameCover.setClipToOutline(true);
            LauncherCoverLoader.clear(binding.padGameCover);
            if (cover == null || cover.isEmpty()) {
                showCoverPlaceholder();
                return;
            }
            showCoverPlaceholder();
            LauncherCoverLoader.loadInto(binding.padGameCover, cover, success -> {
                if (success) {
                    binding.padGameCover.setVisibility(View.VISIBLE);
                    binding.padGameInitial.setVisibility(View.GONE);
                }
            });
        }

        private void showCoverPlaceholder() {
            binding.padGameCover.setImageDrawable(null);
            binding.padGameCover.setVisibility(View.GONE);
            binding.padGameInitial.setVisibility(View.VISIBLE);
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
        if (game.coverPersistUri != null && !game.coverPersistUri.trim().isEmpty())
            return game.coverPersistUri.trim();
        if (game.coverUri != null && !game.coverUri.trim().isEmpty())
            return game.coverUri.trim();
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
