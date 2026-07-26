package com.apps.PadUi;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.Manifest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.core.databinding.FragmentLauncherLibraryBinding;
import com.core.launcherbridge.LauncherAuthBridge;
import com.core.launcherbridge.PlaySessionCallback;
import com.core.launcherbridge.PlaySession;
import com.core.launcherbridge.LauncherGameLaunchBridge;
import com.core.launcherbridge.LauncherRepositoryBridge;
import com.core.model.EngineType;
import com.core.model.Game;
import com.core.util.AppExecutors;
import com.core.util.RxMainQueue;

import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.settings.LauncherCustomVndbSearchDialog;
import com.apps.settings.LauncherKrkrSettingsActivity;
import com.apps.game.CategoryBuildResult;
import com.apps.game.CategoryOption;
import com.apps.game.GameActionMenuFactory;
import com.apps.game.GameCategoryBuilder;
import com.apps.game.GameListController;
import com.apps.game.GameMetadataFormatter;
import com.apps.game.GamePasswordLock;
import com.apps.game.GameSessionController;
import com.apps.game.GameSyncController;
import com.apps.UserData.LauncherUserData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 横屏手机游戏仓库自包含实现：直接继承 {@link Fragment}，使用 {@link PadManageGameAdapter}。
 * 卡片高度根据实际列宽自适应，列表纵向连续加载，不再锁定固定行数分页。
 * 不再继承 LauncherLibraryFragment，所有逻辑独立维护。
 */
public class PadManageFragment extends Fragment implements GameListController.Listener,
        GameActionMenuFactory.ActionMenuCallbacks, GameSyncController.Listener {
    private static final int GRID_COLUMNS = 6;
    private static final int TABLET_MIN_SMALLEST_WIDTH_DP = 600;

    private FragmentLauncherLibraryBinding binding;
    private final RxMainQueue mainQueue = new RxMainQueue();

    /** Pad 偏好使用 PadDialogFactory 的单选实现。 */
    private final GameActionMenuFactory.SubDialogFactory subDialogFactory =
            (ctx, title, labels, checked, onChoice) ->
                    PadDialogFactory.showSingleChoice(ctx, title, labels, checked,
                            index -> onChoice.accept(index));

    /** Pad 同步对话框工厂：复用 PadDialogFactory，使用 secondaryButton 背景，270dp 宽度。 */
    private final GameSyncController.DialogFactory syncDialogFactory =
            new GameSyncController.DialogFactory() {
                @Override
                public void showSyncConfirmDialog(Runnable onConfirm) {
                    PadDialogFactory.showStandardConfirm(requireContext(), "同步数据",
                            "全部同步需要一定时间，是否一键同步刷新所有游戏的元数据与封面？",
                            "确定同步", onConfirm);
                }

                @Override
                public AlertDialog createSyncLoadingDialog(String title, String hint) {
                    return createPadSyncLoadingDialog(title, hint);
                }

                @Override
                public void showSyncResultDialog(int synced, int failed) {
                    String message = "同步完成 " + synced + " 个" + (failed > 0 ? "\n失败 " + failed + " 个" : "");
                    PadDialogFactory.showInfo(requireContext(), "同步完成", message);
                }
            };

    private GameSessionController sessionController;
    private GameListController listController;
    private GameSyncController syncController;
    private final List<CategoryOption> categories = new ArrayList<>();
    private final Map<Long, List<String>> gameDevelopers = new HashMap<>();
    private PadManageGameAdapter adapter;
    private String selectedCategory = "";
    private String searchQuery = "";
    private boolean categoriesCollapsed = true;
    private boolean needsRefresh;
    private Runnable searchDebounce;
    private int pageSize = GRID_COLUMNS;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLauncherLibraryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        applyPadContentSpacing();
        applySystemBarInsets();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        binding.libraryTitle.setText("游戏仓库");
        pageSize = GRID_COLUMNS * (isTabletLayout() ? 2 : 1);
        setupSearchAndCategories();
        sessionController = new GameSessionController(requireContext(), mainQueue, new GameSessionController.Listener() {
            @Override
            public void reloadGame(long gameId) { reloadSingleGame(gameId); }
            @Override
            public void reloadAllGames() { loadGames(); }
        });
        syncController = new GameSyncController(mainQueue, this, syncDialogFactory);
        listController = new GameListController(mainQueue, this);
        setupRecycler();
        loadGames();
    }

    /** 压缩通用游戏库布局为 Launcher 底栏预留的底部空白，仅影响 Pad 管理页。 */
    private void applyPadContentSpacing() {
        binding.libraryContent.setPadding(
                binding.libraryContent.getPaddingLeft(),
                binding.libraryContent.getPaddingTop(),
                binding.libraryContent.getPaddingRight(),
                dp(6));
        binding.libraryRecycler.setPadding(
                binding.libraryRecycler.getPaddingLeft(),
                binding.libraryRecycler.getPaddingTop(),
                binding.libraryRecycler.getPaddingRight(),
                0);
    }

    @Override
    public void onResume() {
        super.onResume();
        checkStoragePermission();
        if (sessionController != null && sessionController.hasActiveSession()) {
            sessionController.finishDirectPlaySessionIfNeeded(this);
        } else if (!listController.isDataLoaded() || needsRefresh) {
            loadGames();
        }
    }

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog dialog = GameActionMenuFactory.createLauncherDialog(requireContext());
                LinearLayout root = GameActionMenuFactory.createDialogRoot(requireContext());
                root.addView(GameActionMenuFactory.createDialogTitle(requireContext(), "需要文件访问权限"));

                TextView info = new TextView(requireContext());
                info.setText("应用需要完全访问文件夹的权限来读取游戏文件。请在系统页面允许\"管理所有文件\"。");
                info.setTextColor(ContextCompat.getColor(requireContext(), com.core.R.color.launcher_text_muted_color));
                info.setTextSize(12);
                info.setLineSpacing(dp(4), 1f);
                LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                infoLp.setMargins(0, dp(13), 0, 0);
                root.addView(info, infoLp);

                root.addView(GameActionMenuFactory.createDialogButton(requireContext(), "前往", true, () -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + requireContext().getPackageName())));
                    } catch (Throwable t) {
                        try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); } catch (Throwable ignored) { }
                    }
                }, dialog));

                root.addView(GameActionMenuFactory.createDialogCancelButton(requireContext(), dialog));

                GameActionMenuFactory.setDialogContent(dialog, root, 288);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (requireActivity().checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1001);
            }
        }
    }

    @Override
    public void onDestroyView() {
        if (sessionController != null) sessionController.cleanup();
        if (syncController != null) syncController.cleanup();
        if (binding != null) {
            binding.getRoot().setOnApplyWindowInsetsListener(null);
            binding.libraryRecycler.setAdapter(null);
        }
        super.onDestroyView();
        binding = null;
        adapter = null;
    }

    private void applySystemBarInsets() {
        FragmentLauncherLibraryBinding currentBinding = binding;
        int originalLeft = currentBinding.libraryContent.getPaddingLeft();
        int originalTop = currentBinding.libraryContent.getPaddingTop();
        int originalRight = currentBinding.libraryContent.getPaddingRight();
        int originalBottom = currentBinding.libraryContent.getPaddingBottom();

        currentBinding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            currentBinding.libraryContent.setPadding(
                    originalLeft,
                    originalTop + insets.getSystemWindowInsetTop(),
                    originalRight,
                    originalBottom
            );
            return insets;
        });
        currentBinding.getRoot().requestApplyInsets();
    }

    private void setupRecycler() {
        adapter = new PadManageGameAdapter();
        adapter.setOnGameCardListener(new PadManageGameAdapter.OnGameCardListener() {
            @Override
            public void onGameClick(Game game) {
                if (game != null) {
                    adapter.setSelectedGameId(game.id);
                    confirmLaunchGame(game);
                }
            }

            @Override
            public void onGameLongClick(Game game) {
                if (game != null) showGameActionMenu(game);
            }
        });

        final int gridColumns = GRID_COLUMNS;
        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), gridColumns);
        binding.libraryRecycler.setLayoutManager(layoutManager);
        binding.libraryRecycler.setAdapter(adapter);
        binding.libraryRecycler.setHasFixedSize(true);
        binding.libraryRecycler.setItemViewCacheSize(pageSize);
        RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();
        pool.setMaxRecycledViews(0, pageSize * 2);
        binding.libraryRecycler.setRecycledViewPool(pool);
        binding.libraryRecycler.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                            oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) updateAdaptiveCardHeight();
        });
        binding.libraryRecycler.post(this::updateAdaptiveCardHeight);
        binding.libraryRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy <= 0 || listController.isLoading() || listController.isFullyLoaded()) return;
                if (layoutManager.findLastVisibleItemPosition() >= Math.max(0, listController.getVisibleGames().size() - GRID_COLUMNS)) {
                    loadNextPage();
                }
            }
        });
    }

    /** Keeps compact landscape cards proportional to the actual available column width. */
    private void updateAdaptiveCardHeight() {
        if (binding == null || adapter == null) return;

        RecyclerView recyclerView = binding.libraryRecycler;
        int recyclerWidth = recyclerView.getWidth();
        if (recyclerWidth <= 0) return;

        int usableWidth = recyclerWidth
                - recyclerView.getPaddingLeft()
                - recyclerView.getPaddingRight();
        if (usableWidth <= 0) return;

        // item_launcher_game_card 每张卡片左右各约 5dp margin。
        int totalHorizontalMargins = dp(10) * GRID_COLUMNS;
        int cardWidth = Math.max(
                1,
                (usableWidth - totalHorizontalMargins) / GRID_COLUMNS
        );

        adapter.setFixedCardHeight(Math.max(dp(34), Math.round(cardWidth * 1.25f)));
    }

    private boolean isTabletLayout() {
        return getResources().getConfiguration().smallestScreenWidthDp
                >= TABLET_MIN_SMALLEST_WIDTH_DP;
    }

    private void setupSearchAndCategories() {
        binding.librarySearchButton.setOnClickListener(view -> {
            boolean show = binding.librarySearchInput.getVisibility() != View.VISIBLE;
            binding.librarySearchInput.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                binding.librarySearchInput.requestFocus();
                InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(binding.librarySearchInput, InputMethodManager.SHOW_IMPLICIT);
            } else {
                binding.librarySearchInput.setText("");
                InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(binding.librarySearchInput.getWindowToken(), 0);
            }
            renderToolbarButtonState();
        });
        binding.librarySyncButton.setOnClickListener(view -> syncController.showSyncDataConfirmDialog());
        binding.libraryCollapseButton.setOnClickListener(view -> {
            categoriesCollapsed = !categoriesCollapsed;
            binding.libraryCategoryScroll.setVisibility(categoriesCollapsed ? View.GONE : View.VISIBLE);
            renderToolbarButtonState();
        });
        binding.librarySearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s == null ? "" : s.toString().trim();
                if (searchDebounce != null) mainQueue.removeCallbacks(searchDebounce);
                searchDebounce = () -> applyFilters();
                mainQueue.postDelayed(searchDebounce, 300);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        renderToolbarButtonState();
    }

    private void loadGames() {
        needsRefresh = false;
        listController.loadGames();
    }

    private void applyFilters() {
        listController.applyFilters();
    }

    private void applyFilters(boolean forceFullRefresh) {
        listController.applyFilters(forceFullRefresh);
    }

    /**
     * Updates a single game in-place without reloading the entire list, preserving scroll position.
     * Used by long-press dialog actions (status, play time, favorite, cover sync, metadata rematch).
     * DiffUtil detects only the changed card and dispatches a single notifyItemChanged.
     */
    private void updateSingleGame(Game updated) {
        listController.updateSingleGame(updated);
    }

    /** Removes a single game by id without reloading the entire list, preserving scroll position. */
    private void removeSingleGame(long gameId) {
        listController.removeSingleGame(gameId);
    }

    /** Re-fetches a single game from DB and updates it in-place, for async metadata operations. */
    @Override
    public void reloadSingleGame(long gameId) {
        listController.reloadSingleGame(this, gameId);
    }

    private void loadNextPage() {
        listController.loadNextPage();
    }

    private void loadNextPage(boolean forceFullRefresh) {
        listController.loadNextPage(forceFullRefresh);
    }

    private void renderState() {
        if (binding == null) return;
        boolean hasGames = !listController.getVisibleGames().isEmpty();
        binding.libraryRecycler.setVisibility(hasGames ? View.VISIBLE : View.GONE);
        if (hasGames) {
            binding.libraryRecycler.post(this::updateAdaptiveCardHeight);
            scheduleLoadUntilViewportFilled();
        }
        binding.libraryEmpty.setText(listController.getAllGames().isEmpty() ? "还没有游戏" : "没有匹配的游戏");
        binding.libraryEmpty.setVisibility(hasGames ? View.GONE : View.VISIBLE);
    }

    /**
     * 使用 OnPreDrawListener 等待 RecyclerView 完成布局后再检测是否填满容器。
     * 若用 post() 检测，runnable 可能在 DiffUtil 触发的布局完成前运行，
     * canScrollVertically() 基于旧布局返回 true（误判为已填满），导致下一页无法自动加载。
     */
    private void scheduleLoadUntilViewportFilled() {
        if (binding == null || listController.isViewportFillCheckPending() || listController.isLoading()
                || listController.isFullyLoaded()
                || listController.getVisibleGames().size() >= listController.getFilteredGames().size()) {
            return;
        }
        listController.setViewportFillCheckPending(true);
        RecyclerView recyclerView = binding.libraryRecycler;
        ViewTreeObserver observer = recyclerView.getViewTreeObserver();
        ViewTreeObserver.OnPreDrawListener listener = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                ViewTreeObserver vto = recyclerView.getViewTreeObserver();
                vto.removeOnPreDrawListener(this);
                listController.setViewportFillCheckPending(false);
                if (binding == null || listController.isLoading() || listController.isFullyLoaded()
                        || listController.getVisibleGames().size() >= listController.getFilteredGames().size()) {
                    return true;
                }
                // 列表无法向下滚动时，说明内容未填满容器，加载下一页
                if (!recyclerView.canScrollVertically(1)) {
                    listController.loadNextPage();
                }
                return true;
            }
        };
        observer.addOnPreDrawListener(listener);
    }

    private void confirmLaunchGame(Game game) {
        if (game == null) return;
        PadDialogFactory.showConfirm(requireContext(), "启动游戏",
                "确定启动「" + GameMetadataFormatter.safeTitle(game) + "」吗？", "确定",
                () -> com.apps.game.GamePasswordLock.interceptLaunch(PadManageFragment.this, game,
                        () -> sessionController.launchGameDirectly(PadManageFragment.this, game)));
    }

    private void showGameActionMenu(Game game) {
        if (game == null) return;
        GameActionMenuFactory.ActionMenuConfig config = new GameActionMenuFactory.ActionMenuConfig();
        config.includeEditAction = false;
        config.includeEditPlayTimeAction = true;
        config.includeFavoriteAction = false;
        config.includePasswordAction = false;
        config.dialogWidthDp = 252;
        GameActionMenuFactory.showGameActionMenu(this, game, config, this);
    }

    // ===== GameActionMenuFactory.ActionMenuCallbacks =====

    @Override
    public void onShowGameDetail(Game game) {
        GameActionMenuFactory.showGameDetailDialog(this, game);
    }

    @Override
    public void onEditGame(Game game) {
        // Pad 不支持从动作菜单编辑游戏，保留空实现以满足接口契约
    }

    @Override
    public void onShowPlayStatus(Game game) {
        GameActionMenuFactory.showPlayStatusDialog(this, game, subDialogFactory, this::updateSingleGame);
    }

    @Override
    public void onEditPlayTime(Game game) {
        GameActionMenuFactory.showEditPlayTimeDialog(this, game, this::updateSingleGame);
    }

    @Override
    public void onToggleFavorite(Game game) {
        toggleFavorite(game);
    }

    @Override
    public void onTogglePassword(Game game) {
        if (GamePasswordLock.hasPassword(game)) {
            GamePasswordLock.clearPassword(this, game, null);
        } else {
            GamePasswordLock.setPassword(this, game, null);
        }
    }

    @Override
    public void onShowMoreOptions(Game game) {
        showMoreOptionsDialog(game);
    }

    private void showMoreOptionsDialog(Game game) {
        if (game == null) return;
        AlertDialog dialog = GameActionMenuFactory.createLauncherDialog(requireContext());
        LinearLayout root = GameActionMenuFactory.createDialogRoot(requireContext());
        root.addView(GameActionMenuFactory.createDialogTitle(requireContext(), "更多选项"));

        String favoriteLabel = game.favorite ? "取消收藏" : "添加收藏";
        java.util.List<String[]> options = new java.util.ArrayList<>();
        options.add(new String[]{favoriteLabel, "favorite"});
        options.add(new String[]{"重新匹配 VNDB 元数据", "rematch"});
        options.add(new String[]{"自定义搜索 VNDB", "custom_vndb"});
        options.add(new String[]{"同步元数据封面到卡片", "sync"});
        // ONS 引擎游戏支持单独配置 ONS 引擎参数（编码/拉伸/锐化/视频/独立存档目录等）
        final boolean isOns = game.engine == EngineType.ONS;
        if (isOns) {
            options.add(new String[]{"ONS 引擎设置", "ons_settings"});
        }
        final boolean hasPassword = GamePasswordLock.hasPassword(game);
        options.add(new String[]{hasPassword ? "取消密码" : "密码锁定", "password"});
        options.add(new String[]{"删除游戏", "delete"});

        for (String[] opt : options) {
            TextView option = new TextView(requireContext());
            option.setText(opt[0]);
            option.setGravity(android.view.Gravity.CENTER);
            option.setTextSize(13);
            option.setTypeface(null, android.graphics.Typeface.BOLD);
            if (opt[1].equals("delete")) {
                LauncherTheme.dangerMenuItem(option);
            } else {
                LauncherTheme.menuItem(option);
            }
            String action = opt[1];
            option.setOnClickListener(v -> {
                dialog.dismiss();
                switch (action) {
                    case "favorite": toggleFavorite(game); break;
                    case "rematch": syncController.rematchMetadata(game); break;
                    case "custom_vndb": LauncherCustomVndbSearchDialog.show(this, game, () -> reloadSingleGame(game.id)); break;
                    case "sync": syncController.syncMetadataToCard(game); break;
                    case "ons_settings": openOnsGameSettings(game); break;
                    case "password":
                        if (hasPassword) GamePasswordLock.clearPassword(this, game, null);
                        else GamePasswordLock.setPassword(this, game, null);
                        break;
                    case "delete": confirmDeleteGame(game); break;
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
            lp.setMargins(0, dp(11), 0, 0);
            root.addView(option, lp);
        }
        root.addView(GameActionMenuFactory.createDialogCancelButton(requireContext(), dialog));
        GameActionMenuFactory.setDialogContent(dialog, root, 252);
    }

    private void openOnsGameSettings(Game game) {
        try {
            Intent intent = new Intent(requireContext(), LauncherKrkrSettingsActivity.class);
            intent.putExtra(LauncherKrkrSettingsActivity.EXTRA_GAME_ID, game.id);
            startActivity(intent);
        } catch (Throwable ignored) {
            Toast.makeText(requireContext(), "无法打开 ONS 引擎设置", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleFavorite(Game game) {
        AppExecutors.io().execute(() -> {
            Game updated = null;
            try {
                Game latest = LauncherRepositoryBridge.findGameById(requireContext(), game.id);
                if (latest != null) {
                    latest.favorite = !latest.favorite;
                    LauncherRepositoryBridge.updateGame(requireContext(), latest);
                    updated = latest;
                }
            } catch (Throwable ignored) {}
            final Game result = updated;
            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                if (result != null) updateSingleGame(result);
            });
        });
    }

    private void confirmDeleteGame(Game game) {
        PadDialogFactory.showDangerConfirm(requireContext(), "删除游戏",
                "要删除「" + GameMetadataFormatter.safeTitle(game) + "」吗？此操作仅移除游戏库不进行实际删除。",
                "移除", () -> deleteGame(game));
    }

    private void deleteGame(Game game) {
        AppExecutors.io().execute(() -> {
            try {
                LauncherRepositoryBridge.deleteGame(requireContext(), game.id);
            } catch (Throwable ignored) {}
            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                removeSingleGame(game.id);
                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show();
            });
        });
    }

    /** Pad 风格的同步加载对话框：包含进度文本（tag "sync_progress"），供 DialogFactory 调用。 */
    private AlertDialog createPadSyncLoadingDialog(String titleText, String hintText) {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.setCancelable(false);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return dialog;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(PadDialogFactory.dialogWidthPx(requireContext(), PadDialogFactory.WIDTH_COMPACT_DP), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackground(LauncherTheme.secondaryButton(requireContext(), 20f));

        TextView title = new TextView(requireContext());
        title.setText(titleText);
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), com.core.R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        android.widget.ProgressBar progressBar = new android.widget.ProgressBar(requireContext());
        progressBar.setIndeterminate(true);
        progressBar.getIndeterminateDrawable().setColorFilter(
                LauncherTheme.primary(requireContext()), android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        pbLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        pbLp.setMargins(0, dp(14), 0, 0);
        root.addView(progressBar, pbLp);

        TextView progressText = new TextView(requireContext());
        progressText.setTag("sync_progress");
        progressText.setText("0/0 已完成");
        progressText.setGravity(android.view.Gravity.CENTER);
        progressText.setTextColor(ContextCompat.getColor(requireContext(), com.core.R.color.launcher_text_muted_color));
        progressText.setTextSize(12);
        LinearLayout.LayoutParams ptLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ptLp.setMargins(0, dp(6), 0, 0);
        root.addView(progressText, ptLp);

        TextView hint = new TextView(requireContext());
        hint.setText(hintText);
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setTextColor(ContextCompat.getColor(requireContext(), com.core.R.color.launcher_text_muted_color));
        hint.setTextSize(11);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, dp(10), 0, 0);
        root.addView(hint, hintLp);

        window.setContentView(root);
        return dialog;
    }

    // ===== GameSyncController.Listener =====

    @Override
    public void onBatchSyncComplete(List<Game> loadedGames, CategoryBuildResult categoryResult) {
        if (binding == null) return;
        // 关键：必须同步更新 controller 内部的 all 列表，否则后续 applyFilters()
        // 调用的 controller.rebuild() 仍会遍历旧 Game 对象，导致新封面无法刷新到卡片。
        listController.replaceAllGames(loadedGames);

        gameDevelopers.clear();
        gameDevelopers.putAll(categoryResult.developers);

        categories.clear();
        categories.addAll(categoryResult.categories);

        if (selectedCategory != null && !selectedCategory.isEmpty()
                && !GameCategoryBuilder.containsCategoryValue(categories, selectedCategory)) {
            selectedCategory = "";
        }

        renderCategories();
        listController.setDataLoaded(true);

        // controller 已持有最新数据，applyFilters(true) 会强制全量刷新卡片
        applyFilters(true);
    }

    private void renderCategories() {
        if (binding == null) return;
        binding.libraryCategoryRow.removeAllViews();
        addCategoryChip("全部", "");
        for (CategoryOption category : categories) {
            addCategoryChip(category.label, category.value);
        }
    }

    private void addCategoryChip(String label, String value) {
        TextView chip = new TextView(requireContext());
        boolean selected = value.equals(selectedCategory == null ? "" : selectedCategory);
        chip.setText(label);
        chip.setSingleLine(true);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setTextSize(12);
        chip.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        chip.setTag(value);
        if (selected) {
            chip.setTextColor(LauncherTheme.onPrimary(requireContext()));
            chip.setBackground(LauncherTheme.selectedChip(requireContext()));
        } else {
            LauncherTheme.menuItem(chip);
        }
        chip.setPadding(dp(13), 0, dp(13), 0);
        chip.setOnClickListener(view -> {
            selectedCategory = value;
            renderCategories();
            applyFilters();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(29));
        lp.setMargins(0, 0, dp(7), 0);
        binding.libraryCategoryRow.addView(chip, lp);
    }

    private void renderToolbarButtonState() {
        if (binding == null) return;
        applyToolbarIconTone(binding.librarySyncButton);
        applyToolbarIconTone(binding.librarySearchButton);
        applyToolbarIconTone(binding.libraryCollapseButton);
    }

    private void applyToolbarIconTone(ImageView view) {
        view.setImageTintList(ColorStateList.valueOf(LauncherTheme.primary(requireContext())));
        view.setBackground(null);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ===== GameListController.Listener =====
    @Override
    public Context getAppContext() {
        return requireContext().getApplicationContext();
    }

    @Override
    public boolean isBindingNull() {
        return binding == null;
    }

    @Override
    public String getSearchQuery() {
        return searchQuery;
    }

    @Override
    public String getSelectedCategory() {
        return selectedCategory == null ? "" : selectedCategory;
    }

    @Override
    public Map<Long, List<String>> getGameDevelopers() {
        return gameDevelopers;
    }

    @Override
    public int getPageSize() {
        return pageSize;
    }

    @Override
    public boolean usesHorizontalPaging() {
        return false;  // Pad 始终不使用横向分页
    }

    @Override
    public void onDataLoaded(@NonNull List<CategoryOption> categories,
                             @NonNull Map<Long, List<String>> developers) {
        gameDevelopers.clear();
        gameDevelopers.putAll(developers);
        this.categories.clear();
        this.categories.addAll(categories);
        if (selectedCategory != null && !selectedCategory.isEmpty()
                && !GameCategoryBuilder.containsCategoryValue(this.categories, selectedCategory)) {
            selectedCategory = "";
        }
        renderCategories();
    }

    @Override
    public void onVisibleGamesChanged(boolean forceFullRefresh) {
        if (adapter != null) {
            adapter.submit(new ArrayList<>(listController.getVisibleGames()), forceFullRefresh);
        }
    }

    @Override
    public void onRenderStateRequested() {
        renderState();
    }
}
