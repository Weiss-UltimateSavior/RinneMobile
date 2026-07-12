package com.apps.game;

import android.text.TextUtils;
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
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherCoverLoader;

/** Shared data, selection, diffing and binding contract for portrait and Pad game cards. */
public abstract class BaseGameCardAdapter extends RecyclerView.Adapter<BaseGameCardAdapter.Holder> {
    public interface OnGameCardListener {
        void onGameClick(Game game);
        void onGameLongClick(Game game);
    }

    /** Keeps layout-only variations out of the shared card data/binding pipeline. */
    public interface CardLayoutSpec {
        void apply(ItemLauncherGameCardBinding binding, int fixedHeightPx);
    }

    private final List<Game> games = new ArrayList<>();
    private final CardLayoutSpec layoutSpec;
    private final boolean updateAttachedHeightsOnly;
    private OnGameCardListener listener;
    private long selectedGameId = -1L;
    private int fixedCardHeight;
    private RecyclerView attachedRecyclerView;

    protected BaseGameCardAdapter(CardLayoutSpec layoutSpec, boolean updateAttachedHeightsOnly) {
        this.layoutSpec = layoutSpec;
        this.updateAttachedHeightsOnly = updateAttachedHeightsOnly;
        setHasStableIds(true);
    }

    public void setOnGameCardListener(OnGameCardListener listener) { this.listener = listener; }
    public void setFixedCardHeight(int heightPx) {
        int next = Math.max(0, heightPx);
        if (next == fixedCardHeight) return;
        fixedCardHeight = next;
        if (updateAttachedHeightsOnly) applyHeightToAttached(); else notifyDataSetChanged();
    }
    public void submit(List<Game> newGames) { submit(newGames, false); }
    public void submit(List<Game> newGames, boolean forceFullRefresh) {
        List<Game> next = newGames == null ? new ArrayList<>() : new ArrayList<>(newGames);
        List<Game> old = new ArrayList<>(games);
        games.clear();
        games.addAll(next);
        if (forceFullRefresh) { notifyDataSetChanged(); return; }
        DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return games.size(); }
            @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
                Game a = old.get(oldPosition), b = games.get(newPosition);
                return a != null && b != null && a.id == b.id;
            }
            @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
                return sameContent(old.get(oldPosition), games.get(newPosition));
            }
        }).dispatchUpdatesTo(this);
    }
    public void setSelectedGameId(long id) {
        long old = selectedGameId;
        selectedGameId = id;
        for (int i = 0; i < games.size(); i++) {
            Game game = games.get(i);
            if (game != null && (game.id == old || game.id == id)) notifyItemChanged(i);
        }
    }
    @Override public long getItemId(int position) { return games.get(position) == null ? RecyclerView.NO_ID : games.get(position).id; }
    @Override public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) { super.onAttachedToRecyclerView(recyclerView); attachedRecyclerView = recyclerView; }
    @Override public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) { super.onDetachedFromRecyclerView(recyclerView); attachedRecyclerView = null; }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemLauncherGameCardBinding binding = ItemLauncherGameCardBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        applyLayout(binding);
        return new Holder(binding);
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        Game game = games.get(position);
        holder.bind(game, game != null && game.id == selectedGameId);
    }
    @Override public int getItemCount() { return games.size(); }
    @Override public void onViewRecycled(@NonNull Holder holder) { holder.recycle(); }

    public final class Holder extends RecyclerView.ViewHolder {
        private final ItemLauncherGameCardBinding binding;
        Holder(ItemLauncherGameCardBinding binding) { super(binding.getRoot()); this.binding = binding; }
        void bind(Game game, boolean selected) {
            if (game == null) return;
            applyLayout(binding);
            binding.getRoot().setBackgroundResource(selected ? R.drawable.launcher_game_card_selected : R.drawable.launcher_game_card);
            binding.launcherGameTitle.setText(title(game));
            binding.launcherGamePlayStatus.setText(game.totalPlayTime <= 0L ? "未游玩" : TimeFormatUtil.playTime(game.totalPlayTime));
            binding.launcherGameInitial.setText(initial(game.title));
            LauncherTheme.textPrimary(binding.launcherGameTitle);
            LauncherTheme.textPrimary(binding.launcherGamePlayStatus);
            LauncherTheme.textPrimary(binding.launcherGameInitial);
            binding.launcherGameFavorite.setVisibility(game.favorite ? View.VISIBLE : View.GONE);
            bindCover(game);
            binding.getRoot().setOnClickListener(v -> { setSelectedGameId(game.id); if (listener != null) listener.onGameClick(game); });
            binding.getRoot().setOnLongClickListener(v -> { setSelectedGameId(game.id); if (listener != null) listener.onGameLongClick(game); return true; });
        }
        void recycle() { LauncherCoverLoader.clear(binding.launcherGameCover); }
        private void bindCover(Game game) {
            String cover = game.coverPersistUri != null && !game.coverPersistUri.trim().isEmpty() ? game.coverPersistUri.trim()
                    : game.coverUri == null ? "" : game.coverUri.trim();
            binding.launcherGameCoverFrame.setClipToOutline(true);
            binding.launcherGameCover.setClipToOutline(true);
            LauncherCoverLoader.clear(binding.launcherGameCover);
            binding.launcherGameCover.setImageDrawable(null);
            binding.launcherGameCover.setVisibility(View.GONE);
            binding.launcherGameInitial.setVisibility(View.VISIBLE);
            if (!cover.isEmpty()) LauncherCoverLoader.loadInto(binding.launcherGameCover, cover, success -> {
                if (success) { binding.launcherGameCover.setVisibility(View.VISIBLE); binding.launcherGameInitial.setVisibility(View.GONE); }
            });
        }
    }

    private void applyHeightToAttached() {
        if (attachedRecyclerView == null) return;
        for (int i = 0; i < attachedRecyclerView.getChildCount(); i++) {
            View child = attachedRecyclerView.getChildAt(i);
            if (child != null) applyLayout(ItemLauncherGameCardBinding.bind(child));
        }
    }
    private void applyLayout(ItemLauncherGameCardBinding binding) { if (layoutSpec != null) layoutSpec.apply(binding, fixedCardHeight); }
    private static boolean sameContent(Game a, Game b) {
        if (a == null || b == null) return false;
        return a.id == b.id && eq(a.title, b.title) && a.totalPlayTime == b.totalPlayTime && eq(a.playStatus, b.playStatus)
                && a.favorite == b.favorite && eq(a.coverPersistUri, b.coverPersistUri) && eq(a.coverUri, b.coverUri);
    }
    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }
    private static String title(Game game) { return game.title == null || game.title.trim().isEmpty() ? "未命名游戏" : game.title.trim(); }
    private static String initial(String title) { if (title == null || title.trim().isEmpty()) return "游"; String value = title.trim(); return value.substring(0, value.offsetByCodePoints(0, 1)); }
    protected static int dp(View view, int value) { return Math.round(value * view.getResources().getDisplayMetrics().density); }
    protected static void compactText(ItemLauncherGameCardBinding binding) {
        binding.launcherGameTextOverlay.setPadding(dp(binding.getRoot(), 8), dp(binding.getRoot(), 2), dp(binding.getRoot(), 8), dp(binding.getRoot(), 2));
        binding.launcherGameTitle.setSingleLine(true); binding.launcherGameTitle.setMaxLines(1); binding.launcherGameTitle.setEllipsize(TextUtils.TruncateAt.END); binding.launcherGameTitle.setIncludeFontPadding(false);
        binding.launcherGamePlayStatus.setSingleLine(true); binding.launcherGamePlayStatus.setMaxLines(1); binding.launcherGamePlayStatus.setEllipsize(TextUtils.TruncateAt.END); binding.launcherGamePlayStatus.setIncludeFontPadding(false);
        setMargins(binding.launcherGameTitle, 0, 0, 0, dp(binding.getRoot(), 1));
        setMargins(binding.launcherGamePlayStatus, 0, 0, 0, 0);
    }
    private static void setMargins(View view, int left, int top, int right, int bottom) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams margin = (ViewGroup.MarginLayoutParams) params;
        if (margin.leftMargin == left && margin.topMargin == top && margin.rightMargin == right && margin.bottomMargin == bottom) return;
        margin.setMargins(left, top, right, bottom);
        view.setLayoutParams(margin);
    }
}
