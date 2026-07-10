package com.apps.PadUi;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.apps.LauncherCoverLoader;
import com.apps.LauncherTheme;
import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ItemLauncherGameCardBinding;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.TimeFormatUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 横屏手机游戏仓库专用适配器：5 × 2 卡片分页展示。
 * 不复用 LauncherGameAdapter，独立维护仓库卡片的绑定与动态固定高度逻辑。
 */
public class PadManageGameAdapter extends RecyclerView.Adapter<PadManageGameAdapter.Holder> {
    public interface OnGameCardListener {
        void onGameClick(Game game);
        void onGameLongClick(Game game);
    }

    private final List<Game> games = new ArrayList<>();
    private OnGameCardListener listener;
    private long selectedGameId = -1L;
    private int fixedCardHeight;
    private RecyclerView attachedRecyclerView;

    public void setOnGameCardListener(OnGameCardListener listener) {
        this.listener = listener;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        attachedRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        attachedRecyclerView = null;
    }

    /**
     * 横屏紧凑布局下按 RecyclerView 可用行高固定卡片高度，
     * 避免第二行被底部悬浮导航栏遮挡。
     * 注意：不调用 notifyDataSetChanged()，否则会在页面切换时打断 DiffUtil 动画，
     * 导致卡片尺寸跳变。这里直接更新已附着卡片，后续新卡片在 bind() 中应用高度。
     */
    public void setFixedCardHeight(int heightPx) {
        int newHeight = Math.max(0, heightPx);
        if (newHeight == fixedCardHeight) return;
        fixedCardHeight = newHeight;
        applyHeightToAttached();
    }

    private void applyHeightToAttached() {
        RecyclerView rv = attachedRecyclerView;
        if (rv == null) return;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child == null) continue;
            applyFixedCardLayout(ItemLauncherGameCardBinding.bind(child));
        }
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
        applyFixedCardLayout(binding);
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
            applyFixedCardLayout(binding);
            binding.getRoot().setBackgroundResource(selected ? R.drawable.launcher_game_card_selected : R.drawable.launcher_game_card);
            binding.launcherGameTitle.setText(safeTitle(game));
            binding.launcherGamePlayStatus.setText(playStatus(game));
            binding.launcherGameInitial.setText(initial(game.title));
            LauncherTheme.textPrimary(binding.launcherGameTitle);
            LauncherTheme.textPrimary(binding.launcherGamePlayStatus);
            LauncherTheme.textPrimary(binding.launcherGameInitial);
            applyTextSpacingLayout(binding);
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

    private void applyFixedCardLayout(ItemLauncherGameCardBinding binding) {
        if (fixedCardHeight <= 0 || binding == null) return;
        ViewGroup.LayoutParams cardParams = binding.getRoot().getLayoutParams();
        if (cardParams != null && cardParams.height != fixedCardHeight) {
            cardParams.height = fixedCardHeight;
            binding.getRoot().setLayoutParams(cardParams);
        }

        // 不缩小两行文字字体，只给底部文字层预留稳定高度。
        // 通过压缩 overlay 自身上下 padding 和两行 TextView 的 margin，避免“游玩时间”被裁剪。
        ViewGroup.LayoutParams overlayParams = binding.launcherGameTextOverlay.getLayoutParams();
        int minOverlayHeight = dp(binding.getRoot(), 35);
        int maxOverlayHeight = dp(binding.getRoot(), 38);
        int proportionalHeight = fixedCardHeight / 4;
        int compactOverlayHeight = Math.min(
                fixedCardHeight,
                Math.max(minOverlayHeight, Math.min(maxOverlayHeight, proportionalHeight))
        );
        if (overlayParams.height != compactOverlayHeight) {
            overlayParams.height = compactOverlayHeight;
            binding.launcherGameTextOverlay.setLayoutParams(overlayParams);
        }
        applyTextSpacingLayout(binding);
    }

    /**
     * 只调整两行文字的上下边距，不修改字体大小。
     * 标题和游玩时间保持 XML/主题里的原字号，避免因为强制缩小导致视觉不一致。
     */
    private void applyTextSpacingLayout(ItemLauncherGameCardBinding binding) {
        if (binding == null) return;

        int horizontalPadding = dp(binding.getRoot(), 8);
        int verticalPadding = dp(binding.getRoot(), 2);
        binding.launcherGameTextOverlay.setPadding(
                horizontalPadding,
                verticalPadding,
                horizontalPadding,
                verticalPadding
        );

        binding.launcherGameTitle.setSingleLine(true);
        binding.launcherGameTitle.setMaxLines(1);
        binding.launcherGameTitle.setEllipsize(TextUtils.TruncateAt.END);
        binding.launcherGameTitle.setIncludeFontPadding(false);

        binding.launcherGamePlayStatus.setSingleLine(true);
        binding.launcherGamePlayStatus.setMaxLines(1);
        binding.launcherGamePlayStatus.setEllipsize(TextUtils.TruncateAt.END);
        binding.launcherGamePlayStatus.setIncludeFontPadding(false);

        setTextMargins(binding.launcherGameTitle, 0, 0, 0, dp(binding.getRoot(), 1));
        setTextMargins(binding.launcherGamePlayStatus, 0, 0, 0, 0);
    }

    private void setTextMargins(View view, int left, int top, int right, int bottom) {
        if (view == null) return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams marginParams = (ViewGroup.MarginLayoutParams) params;
        if (marginParams.leftMargin == left
                && marginParams.topMargin == top
                && marginParams.rightMargin == right
                && marginParams.bottomMargin == bottom) {
            return;
        }
        marginParams.setMargins(left, top, right, bottom);
        view.setLayoutParams(marginParams);
    }

    private int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
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
