package com.apps.game;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.core.launcherbridge.LauncherRepositoryBridge;
import com.core.model.Game;
import com.core.util.AppExecutors;
import com.core.util.RxMainQueue;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 持有 allGames / filteredGames / visibleGames 三个列表，统一管理 Library 与 Pad 两个 Fragment
 * 的加载、过滤、分页、单卡刷新逻辑。
 *
 * <p>Fragment 仅负责 ViewBinding、RecyclerView 初始化以及通过 {@link Listener} 回调
 * 把 Adapter 与 Controller 的事件对接。所有列表状态由本类独占。</p>
 */
public class GameListController {

    /** 宿主 Fragment 注入的钩子。 */
    public interface Listener {
        /** 应用级 Context，用于后台 IO。 */
        Context getAppContext();
        /** Fragment 是否已销毁视图（binding == null）。 */
        boolean isBindingNull();

        /** 当前搜索关键字（Fragment 持有，用于工具栏 UI）。 */
        String getSearchQuery();
        /** 当前选中的分类（Fragment 持有，用于工具栏 UI）。 */
        String getSelectedCategory();
        /** 当前开发商映射（Fragment 持有，由 {@link #onDataLoaded} 更新）。 */
        Map<Long, List<String>> getGameDevelopers();

        /** 分页大小。 */
        int getPageSize();
        /** 是否启用横向分页布局。 */
        boolean usesHorizontalPaging();

        /**
         * 数据加载完成后回调，Fragment 应更新自身的 categories/gameDevelopers 集合并调用
         * renderCategories()。
         */
        void onDataLoaded(@NonNull List<CategoryOption> categories,
                          @NonNull Map<Long, List<String>> developers);

        /**
         * 可见列表变化时回调，Fragment 应调用
         * {@code adapter.submit(new ArrayList<>(controller.getVisibleGames()), forceFullRefresh)}。
         */
        void onVisibleGamesChanged(boolean forceFullRefresh);

        /**
         * 视图状态需要刷新时回调（可见性、空态文案、卡片高度、视口填充检测）。
         */
        void onRenderStateRequested();
    }

    private final GameLibraryState libraryState = new GameLibraryState();
    private final List<Game> allGames = new ArrayList<>();
    private final RxMainQueue mainQueue;
    private final Listener listener;

    private boolean loading;
    private boolean fullyLoaded;
    private boolean dataLoaded;
    private boolean viewportFillCheckPending;

    public GameListController(@NonNull RxMainQueue mainQueue, @NonNull Listener listener) {
        this.mainQueue = mainQueue;
        this.listener = listener;
    }

    public List<Game> getAllGames() { return Collections.unmodifiableList(allGames); }
    public List<Game> getFilteredGames() { return libraryState.getFiltered(); }
    public List<Game> getVisibleGames() { return libraryState.getVisible(); }
    public boolean isLoading() { return loading; }
    public boolean isFullyLoaded() { return fullyLoaded; }
    public boolean isDataLoaded() { return dataLoaded; }
    public boolean isViewportFillCheckPending() { return viewportFillCheckPending; }

    public void setLoading(boolean value) { loading = value; }
    public void setViewportFillCheckPending(boolean value) { viewportFillCheckPending = value; }
    public void setDataLoaded(boolean value) { dataLoaded = value; }

    /** 整体替换 allGames（同步 libraryState）。供同步逻辑使用。 */
    public void replaceAllGames(List<Game> games) {
        allGames.clear();
        if (games != null) allGames.addAll(games);
        libraryState.replaceAll(games);
    }

    /** 清空 allGames（同步 libraryState）。 */
    public void clearAllGames() {
        allGames.clear();
        libraryState.replaceAll(null);
    }

    /** 加载全部游戏并构建分类。 */
    public void loadGames() {
        setLoading(true);
        Context appContext = listener.getAppContext();
        AppExecutors.runOnSingle(() -> {
            List<Game> games;
            Map<Long, List<String>> developers;
            List<CategoryOption> builtCategories;
            try {
                games = LauncherRepositoryBridge.getAllGames(appContext);
            } catch (Throwable throwable) {
                games = Collections.emptyList();
            }
            // 在后台线程构建分类（含元数据查询），避免主线程卡顿
            try {
                CategoryBuildResult result = GameCategoryBuilder.build(appContext, games);
                developers = result.developers;
                builtCategories = result.categories;
            } catch (Throwable throwable) {
                developers = Collections.emptyMap();
                builtCategories = Collections.emptyList();
            }
            List<Game> loadedGames = games;
            Map<Long, List<String>> loadedDevelopers = developers;
            List<CategoryOption> loadedCategories = builtCategories;
            mainQueue.post(() -> {
                if (listener.isBindingNull()) return;
                allGames.clear();
                allGames.addAll(loadedGames);
                libraryState.replaceAll(loadedGames);
                listener.onDataLoaded(loadedCategories, loadedDevelopers);
                dataLoaded = true;
                // 后台数据已经加载完成，必须先解除 loading 状态。
                // 否则 RecyclerView 的滚动监听和上拉手势都会被 loading 条件拦截。
                setLoading(false);
                applyFilters();
            });
        });
    }

    public void applyFilters() {
        applyFilters(false);
    }

    public void applyFilters(boolean forceFullRefresh) {
        libraryState.setQuery(listener.getSearchQuery());
        libraryState.setCategory(listener.getSelectedCategory());
        libraryState.rebuild(this::matchGame, this::compareGames,
                listener.getPageSize(), listener.usesHorizontalPaging());
        fullyLoaded = libraryState.isFullyLoaded();
        if (listener.usesHorizontalPaging()) {
            renderPagedGrid(forceFullRefresh);
        } else {
            listener.onVisibleGamesChanged(forceFullRefresh);
        }
        listener.onRenderStateRequested();
    }

    public void renderPagedGrid(boolean forceFullRefresh) {
        libraryState.renderPage(listener.getPageSize());
        fullyLoaded = libraryState.isFullyLoaded();
        listener.onVisibleGamesChanged(forceFullRefresh);
    }

    public boolean showNextPage() {
        if (!listener.usesHorizontalPaging() || loading) return false;
        if (!libraryState.nextPage(listener.getPageSize())) return false;
        fullyLoaded = libraryState.isFullyLoaded();
        listener.onVisibleGamesChanged(false);
        listener.onRenderStateRequested();
        return true;
    }

    public boolean showPreviousPage() {
        if (!listener.usesHorizontalPaging() || loading
                || !libraryState.previousPage(listener.getPageSize())) return false;
        fullyLoaded = libraryState.isFullyLoaded();
        listener.onVisibleGamesChanged(false);
        listener.onRenderStateRequested();
        return true;
    }

    public void loadNextPage() {
        loadNextPage(false);
    }

    public void loadNextPage(boolean forceFullRefresh) {
        if (listener.isBindingNull()) return;
        if (loading && !libraryState.getVisible().isEmpty()) return;
        loading = true;
        libraryState.loadNext(listener.getPageSize());
        fullyLoaded = libraryState.isFullyLoaded();
        listener.onVisibleGamesChanged(forceFullRefresh);
        loading = false;
        listener.onRenderStateRequested();
    }

    /**
     * 就地刷新单张卡片，避免 loadGames() 重置分页与滑动位置。
     * 用于长按菜单（状态、游玩时长、收藏、封面同步、元数据 rematch）等异步操作。
     */
    public void updateSingleGame(Game updated) {
        if (updated == null || listener.isBindingNull()) return;
        for (int i = 0; i < allGames.size(); i++) {
            Game g = allGames.get(i);
            if (g != null && g.id == updated.id) {
                allGames.set(i, updated);
                break;
            }
        }
        libraryState.updateGame(updated, this::matchGame);
        fullyLoaded = libraryState.isFullyLoaded();
        listener.onVisibleGamesChanged(false);
        listener.onRenderStateRequested();
    }

    /** 按 id 移除单张卡片，保留滑动位置。 */
    public void removeSingleGame(long gameId) {
        if (listener.isBindingNull()) return;
        for (int i = 0; i < allGames.size(); i++) {
            Game g = allGames.get(i);
            if (g != null && g.id == gameId) {
                allGames.remove(i);
                break;
            }
        }
        libraryState.removeGame(gameId);
        fullyLoaded = libraryState.isFullyLoaded();
        listener.onVisibleGamesChanged(false);
        listener.onRenderStateRequested();
    }

    /** 异步从 DB 重新拉取单张卡片，用于封面/元数据等异步操作完成后回填。 */
    public void reloadSingleGame(@NonNull Fragment fragment, long gameId) {
        AppExecutors.io().execute(() -> {
            Game updated = null;
            try {
                updated = LauncherRepositoryBridge.findGameById(fragment.requireContext(), gameId);
            } catch (Throwable ignored) {}
            final Game result = updated;
            if (fragment.getActivity() != null) fragment.getActivity().runOnUiThread(() -> {
                if (result != null) updateSingleGame(result);
            });
        });
    }

    private boolean matchGame(Game game, String query, String category) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        return (normalized.isEmpty()
                || GameMetadataFormatter.safeTitle(game).toLowerCase(Locale.ROOT).contains(normalized))
                && (category.trim().isEmpty()
                || GameCategoryBuilder.matches(game, category, listener.getGameDevelopers()));
    }

    private int compareGames(Game left, Game right) {
        return Collator.getInstance(Locale.CHINA)
                .compare(GameMetadataFormatter.safeTitle(left), GameMetadataFormatter.safeTitle(right));
    }
}
