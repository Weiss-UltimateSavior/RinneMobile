package com.apps.game;

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
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
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

import com.apps.LauncherActivity;
import com.apps.game.CategoryBuildResult;
import com.apps.game.CategoryOption;
import com.apps.game.GameCategoryBuilder;
import com.apps.game.GameMetadataFormatter;
import com.apps.game.GameSessionController;
import com.apps.theme.LauncherDialogFactory;
import com.core.R;
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

import com.apps.UserData.LauncherUserData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.apps.settings.LauncherCustomVndbSearchDialog;
import com.apps.settings.LauncherKrkrSettingsActivity;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherLibraryFragment extends Fragment implements GameListController.Listener,
        GameActionMenuFactory.ActionMenuCallbacks, GameSyncController.Listener {
    private FragmentLauncherLibraryBinding binding;
    private final RxMainQueue mainQueue = new RxMainQueue();

    /** Library 偏好使用 LauncherDialogFactory 的单选实现。 */
    private final GameActionMenuFactory.SubDialogFactory subDialogFactory =
            (ctx, title, labels, checked, onChoice) ->
                    LauncherDialogFactory.showSingleChoice(ctx, title, labels, checked,
                            index -> onChoice.accept(index));

    /** Library 同步对话框工厂：自绘对话框，使用 launcher_dialog_bg 背景，252dp 宽度。 */
    private final GameSyncController.DialogFactory syncDialogFactory =
            new GameSyncController.DialogFactory() {
                @Override
                public void showSyncConfirmDialog(Runnable onConfirm) {
                    LauncherDialogFactory.showStandardConfirm(requireContext(), "同步数据",
                            "全部同步需要一定时间，是否一键同步刷新所有游戏的元数据与封面？",
                            "确定同步", onConfirm);
                }

                @Override
                public AlertDialog createSyncLoadingDialog(String title, String hint) {
                    return createLibrarySyncLoadingDialog(title, hint);
                }

                @Override
                public void showSyncResultDialog(int synced, int failed) {
                    String message = "同步完成 " + synced + " 个" + (failed > 0 ? "\n失败 " + failed + " 个" : "");
                    LauncherDialogFactory.showInfo(requireContext(), "同步完成", message);
                }
            };

    private GameSessionController sessionController;
    private GameListController listController;
    private GameSyncController syncController;
    private final List<CategoryOption> categories = new ArrayList<>();
    private final Map<Long, List<String>> gameDevelopers = new HashMap<>();
    private LauncherGameAdapter adapter;
    private GridLayoutManager gridLayoutManager;
    private String selectedCategory = "";
    private String searchQuery = "";
    private boolean categoriesCollapsed = true;
    // 编辑卡片后回退时，仅就地刷新被编辑的那张卡片，避免 loadGames() 重置分页与滑动位置。
    private long pendingEditGameId = -1L;
    private Runnable searchDebounce;
    private GestureDetector swipeGestureDetector;
    private boolean swipeConsumed;
    private float loadMoreDragStartY;
    private boolean loadMoreDragCandidate;
    private boolean posterGridStyle;
    private static final String LIBRARY_PREFS = "launcher_library_preferences";
    private static final String KEY_POSTER_GRID_STYLE = "poster_grid_style";

    /**
     * Configuration hooks used by the landscape game repository. Keeping the shared library
     * implementation here means search, categories, sync and game actions stay identical.
     */
    protected int getGridColumns() {
        return LauncherTabletPortraitScaler.libraryGridColumns(getResources());
    }

    private int getActiveGridColumns() {
        // 参考样式以三列海报为核心；原横向卡片继续沿用设备自适应列数。
        return posterGridStyle ? 3 : Math.max(1, getGridColumns());
    }

    @Override
    public int getPageSize() {
        return LauncherTabletPortraitScaler.libraryPageSize(getResources());
    }

    private boolean usesTabletPortraitCardSizing() {
        return LauncherTabletPortraitScaler.isTabletPortrait(getResources());
    }

    protected int getFixedGridRows() {
        return 0;
    }

    @Override
    public boolean usesHorizontalPaging() {
        return false;
    }

    protected String getLibraryTitle() {
        return "游戏库";
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLauncherLibraryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        LauncherTabletPortraitScaler.apply(binding.getRoot());
        applySystemBarInsets();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        binding.libraryTitle.setText(getLibraryTitle());
        posterGridStyle = requireContext().getApplicationContext()
                .getSharedPreferences(LIBRARY_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_POSTER_GRID_STYLE, false);
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
        setupSwipeGesture();
    }

    @Override
    public void onResume() {
        super.onResume();
        checkStoragePermission();
        if (sessionController != null && sessionController.hasActiveSession()) {
            sessionController.finishDirectPlaySessionIfNeeded(this);
        } else if (pendingEditGameId > 0L) {
            // 编辑页返回时仅就地刷新该卡片，保留当前滑动位置与已加载分页。
            long id = pendingEditGameId;
            pendingEditGameId = -1L;
            reloadSingleGame(id);
        } else if (!listController.isDataLoaded()) {
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
        if (listController != null) listController.cleanup();
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
        adapter = new LauncherGameAdapter();
        adapter.setPosterStyle(posterGridStyle);
        adapter.setOnGameCardListener(new LauncherGameAdapter.OnGameCardListener() {
            @Override
            public void onGameClick(Game game) {
                if (swipeConsumed) {
                    swipeConsumed = false;
                    return;
                }
                if (game != null) {
                    adapter.setSelectedGameId(game.id);
                    confirmLaunchGame(game);
                }
            }

            @Override
            public void onGameLongClick(Game game) {
                if (swipeConsumed) {
                    swipeConsumed = false;
                    return;
                }
                if (game != null) showGameActionMenu(game);
            }
        });

        gridLayoutManager = new GridLayoutManager(requireContext(), getActiveGridColumns());
        binding.libraryRecycler.setLayoutManager(gridLayoutManager);
        binding.libraryRecycler.setAdapter(adapter);
        binding.libraryRecycler.setHasFixedSize(true);
        int bottomPadding = getResources().getDimensionPixelSize(R.dimen.launcher_library_recycler_bottom_padding);
        if (getActivity() instanceof LauncherActivity) {
            bottomPadding += getResources().getDimensionPixelSize(R.dimen.launcher_bottom_nav_height);
        }
        binding.libraryRecycler.setPadding(
                binding.libraryRecycler.getPaddingLeft(),
                binding.libraryRecycler.getPaddingTop(),
                binding.libraryRecycler.getPaddingRight(),
                bottomPadding);
        binding.libraryRecycler.setItemViewCacheSize(20);
        RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();
        pool.setMaxRecycledViews(0, 30);
        binding.libraryRecycler.setRecycledViewPool(pool);
        if (usesHorizontalPaging()) {
            // The floating landscape navigation occupies the bottom of the Fragment. Reserve its
            // height so the fourth card row is never obscured, then size all four rows from the
            // actual remaining viewport (rather than assuming a particular screen density).
            binding.libraryRecycler.setPadding(
                    binding.libraryRecycler.getPaddingLeft(),
                    binding.libraryRecycler.getPaddingTop(),
                    binding.libraryRecycler.getPaddingRight(),
                    dp(72));
            binding.libraryRecycler.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                                oldLeft, oldTop, oldRight, oldBottom) -> {
                if (bottom - top != oldBottom - oldTop) updateFixedGridCardHeight();
            });
            binding.libraryRecycler.post(this::updateFixedGridCardHeight);
        } else if (usesTabletPortraitCardSizing()) {
            // 平板竖屏增加列数后，根据每列实际宽度重新计算 5:3 卡片比例。
            // 这样不会继续沿用手机写死高度，也不会影响手机竖屏。
            binding.libraryRecycler.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                                oldLeft, oldTop, oldRight, oldBottom) -> {
                if (right - left != oldRight - oldLeft) updateTabletPortraitCardHeight();
            });
            binding.libraryRecycler.post(this::updateTabletPortraitCardHeight);
        }
        binding.libraryRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (usesHorizontalPaging() || dy <= 0 || listController.isLoading() || listController.isFullyLoaded()) return;
                int lastVisible = gridLayoutManager.findLastVisibleItemPosition();
                if (lastVisible >= Math.max(0, listController.getVisibleGames().size() - getActiveGridColumns())) {
                    loadNextPage();
                }
            }
        });

        // 当分类收起后，第一页可能铺不满屏幕，RecyclerView 没有滚动距离，onScrolled 不会触发。
        // 这里单独监听“向上拉”的手势，每次手势最多加载一页，避免一次性加载全部。
        binding.libraryRecycler.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                handleLoadMoreDragWhenNotScrollable(rv, e);
                return false;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                handleLoadMoreDragWhenNotScrollable(rv, e);
            }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) { }
        });
    }

    private void updateFixedGridCardHeight() {
        if (posterGridStyle || binding == null || adapter == null) return;
        int rows = getFixedGridRows();
        int height = binding.libraryRecycler.getHeight();
        if (rows <= 0 || height <= 0) return;
        int usableHeight = height
                - binding.libraryRecycler.getPaddingTop()
                - binding.libraryRecycler.getPaddingBottom();
        // item_launcher_game_card contributes 5dp top + 5dp bottom margins per row.
        adapter.setFixedCardHeight(Math.max(dp(34), usableHeight / rows - dp(10)));
    }

    /**
     * 平板竖屏卡片按列宽保持原来的高:宽 = 5:3。
     * item_launcher_game_card 每张卡片左右各有约 5dp margin。
     */
    private void updateTabletPortraitCardHeight() {
        if (posterGridStyle || binding == null || adapter == null || !usesTabletPortraitCardSizing()) return;

        RecyclerView recyclerView = binding.libraryRecycler;
        int recyclerWidth = recyclerView.getWidth();
        int columns = Math.max(1, getGridColumns());
        if (recyclerWidth <= 0) return;

        int usableWidth = recyclerWidth
                - recyclerView.getPaddingLeft()
                - recyclerView.getPaddingRight();
        int totalHorizontalMargins = dp(10) * columns;
        int cardWidth = Math.max(1, (usableWidth - totalHorizontalMargins) / columns);
        int cardHeight = Math.round(cardWidth * 5f / 3f);
        adapter.setFixedCardHeight(Math.max(dp(34), cardHeight));
    }

    private void setupSwipeGesture() {
        swipeGestureDetector = new GestureDetector(requireContext(), new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 80;
            private static final int SWIPE_VELOCITY = 200;

            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float diffX = e2.getX() - e1.getX();
                float diffY = e2.getY() - e1.getY();
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY) {
                    boolean handled = diffX < 0 ? handleSwipeLeft() : handleSwipeRight();
                    if (handled) swipeConsumed = true;
                    return handled;
                }
                return false;
            }
        });

        // RecyclerView 区域：通过 OnItemTouchListener 获取触摸事件
        binding.libraryRecycler.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                swipeGestureDetector.onTouchEvent(e);
                return false;
            }
            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                swipeGestureDetector.onTouchEvent(e);
            }
            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
        });

        // 非列表区域（背景、分类栏、空提示等）
        binding.getRoot().setOnTouchListener((v, event) -> {
            swipeGestureDetector.onTouchEvent(event);
            return false;
        });
        binding.libraryContent.setOnTouchListener((v, event) -> {
            swipeGestureDetector.onTouchEvent(event);
            return false;
        });
        binding.libraryEmpty.setOnTouchListener((v, event) -> {
            swipeGestureDetector.onTouchEvent(event);
            return false;
        });
    }

    private boolean handleSwipeLeft() {
        if (usesHorizontalPaging()) return showNextPage();
        return switchToNextCategory();
    }

    private boolean handleSwipeRight() {
        if (usesHorizontalPaging()) return showPreviousPage();
        return switchToPreviousCategory();
    }

    private List<CategoryOption> getFlatCategories() {
        List<CategoryOption> flat = new ArrayList<>();
        flat.add(new CategoryOption("全部", ""));
        flat.addAll(categories);
        return flat;
    }

    private int getCurrentCategoryIndex() {
        List<CategoryOption> flat = getFlatCategories();
        for (int i = 0; i < flat.size(); i++) {
            if (flat.get(i).value.equals(selectedCategory == null ? "" : selectedCategory)) return i;
        }
        return 0;
    }

    private boolean switchToNextCategory() {
        List<CategoryOption> flat = getFlatCategories();
        int idx = getCurrentCategoryIndex();
        if (idx < flat.size() - 1) {
            selectedCategory = flat.get(idx + 1).value;
            renderCategories();
            applyFilters();
            animateCategorySwitch();
            return true;
        }
        return false;
    }

    private boolean switchToPreviousCategory() {
        List<CategoryOption> flat = getFlatCategories();
        int idx = getCurrentCategoryIndex();
        if (idx > 0) {
            selectedCategory = flat.get(idx - 1).value;
            renderCategories();
            applyFilters();
            animateCategorySwitch();
            return true;
        }
        return false;
    }

    private void animateCategorySwitch() {
        if (binding == null) return;
        // 滚动分类栏到当前选中项
        HorizontalScrollView categoryScroll = binding.libraryCategoryScroll;
        for (int i = 0; i < binding.libraryCategoryRow.getChildCount(); i++) {
            View child = binding.libraryCategoryRow.getChildAt(i);
            if (child instanceof TextView) {
                Object tag = child.getTag();
                String catValue = tag != null ? tag.toString() : "";
                if (catValue.equals(selectedCategory == null ? "" : selectedCategory)) {
                    int scrollX = child.getLeft() - categoryScroll.getWidth() / 2 + child.getWidth() / 2;
                    categoryScroll.smoothScrollTo(scrollX, 0);
                    break;
                }
            }
        }
        // 列表淡入动画
        binding.libraryRecycler.setAlpha(0.7f);
        binding.libraryRecycler.animate().alpha(1f).setDuration(250).setInterpolator(new AccelerateDecelerateInterpolator()).start();
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
        binding.librarySyncButton.setOnClickListener(view -> showLibrarySettingsMenu());
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

    private void showLibrarySettingsMenu() {
        String styleLabel = posterGridStyle ? "横向卡片" : "海报网格";
        LauncherDialogFactory.showStandardActionChoices(requireContext(), "游戏库设置",
                new CharSequence[]{"一键同步", styleLabel}, index -> {
                    if (index == 0) {
                        syncController.showSyncDataConfirmDialog();
                    } else {
                        togglePosterGridStyle();
                    }
                });
    }

    private void togglePosterGridStyle() {
        posterGridStyle = !posterGridStyle;
        requireContext().getApplicationContext().getSharedPreferences(LIBRARY_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_POSTER_GRID_STYLE, posterGridStyle).apply();
        if (adapter != null) adapter.setPosterStyle(posterGridStyle);
        if (gridLayoutManager != null) gridLayoutManager.setSpanCount(getActiveGridColumns());
        if (binding != null) {
            binding.libraryRecycler.scrollToPosition(0);
            binding.libraryRecycler.post(() -> {
                if (posterGridStyle) {
                    binding.libraryRecycler.invalidateItemDecorations();
                } else if (usesTabletPortraitCardSizing()) {
                    updateTabletPortraitCardHeight();
                }
            });
        }
        Toast.makeText(requireContext(), posterGridStyle ? "已切换为海报网格" : "已切换为横向卡片", Toast.LENGTH_SHORT).show();
    }

    private void loadGames() {
        listController.loadGames();
    }

    private void applyFilters() {
        listController.applyFilters();
    }

    private void applyFilters(boolean forceFullRefresh) {
        listController.applyFilters(forceFullRefresh);
    }

    private void renderPagedGrid(boolean forceFullRefresh) {
        listController.renderPagedGrid(forceFullRefresh);
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

    private boolean showNextPage() {
        if (!listController.showNextPage()) return false;
        animatePageChange(true);
        return true;
    }

    private boolean showPreviousPage() {
        if (!listController.showPreviousPage()) return false;
        animatePageChange(false);
        return true;
    }

    private void animatePageChange(boolean forward) {
        if (binding == null) return;
        float distance = dp(36) * (forward ? 1f : -1f);
        binding.libraryRecycler.animate().cancel();
        binding.libraryRecycler.setTranslationX(distance);
        binding.libraryRecycler.setAlpha(0.72f);
        binding.libraryRecycler.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(220L)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
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
        if (hasGames && usesHorizontalPaging()) {
            binding.libraryRecycler.post(this::updateFixedGridCardHeight);
        } else if (hasGames && usesTabletPortraitCardSizing()) {
            binding.libraryRecycler.post(this::updateTabletPortraitCardHeight);
        }
        binding.libraryEmpty.setText(listController.getAllGames().isEmpty() ? "还没有游戏" : "没有匹配的游戏");
        binding.libraryEmpty.setVisibility(hasGames ? View.GONE : View.VISIBLE);
        if (hasGames) scheduleLoadUntilViewportFilled();
    }

    /**
     * A short first page can leave no scroll range, which previously required a manual upward
     * drag to reveal more games. Add pages after layout until the list is scrollable or exhausted.
     *
     * 使用 OnPreDrawListener 等待 RecyclerView 完成布局后再检测是否填满容器。
     * 高 dpi 手机首屏尤其需要：page size 默认 8 项（2 列 × 4 行）往往填不满高屏幕，
     * 若用 post() 检测，runnable 可能在 DiffUtil 触发的布局完成前运行，
     * canScrollVertically() 基于旧布局返回 true（误判为已填满），导致下一页无法自动加载。
     */
    private void scheduleLoadUntilViewportFilled() {
        if (binding == null || listController.isViewportFillCheckPending() || usesHorizontalPaging()
                || listController.isLoading() || listController.isFullyLoaded()
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


    private void handleLoadMoreDragWhenNotScrollable(@NonNull RecyclerView recyclerView, @NonNull MotionEvent event) {
        if (listController.isLoading() || listController.isFullyLoaded()
                || listController.getFilteredGames().isEmpty()
                || listController.getVisibleGames().size() >= listController.getFilteredGames().size()) {
            loadMoreDragCandidate = false;
            return;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                loadMoreDragStartY = event.getY();
                loadMoreDragCandidate = !recyclerView.canScrollVertically(1);
                break;

            case MotionEvent.ACTION_MOVE:
                if (loadMoreDragCandidate && loadMoreDragStartY - event.getY() > dp(48)) {
                    loadMoreDragCandidate = false;
                    loadNextPage();
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                loadMoreDragCandidate = false;
                break;
        }
    }

    private void confirmLaunchGame(Game game) {
        if (game == null) return;
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(
                    dp(252),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT
            );
            android.view.View dialogView = android.view.LayoutInflater.from(requireContext())
                    .inflate(com.core.R.layout.dialog_launcher_confirm, null);
            window.setContentView(dialogView);

            TextView titleView = dialogView.findViewById(com.core.R.id.dialogTitle);
            TextView messageView = dialogView.findViewById(com.core.R.id.dialogMessage);
            TextView btnCancel = dialogView.findViewById(com.core.R.id.dialogBtnCancel);
            TextView btnConfirm = dialogView.findViewById(com.core.R.id.dialogBtnConfirm);

            titleView.setText("启动游戏");
            messageView.setText("确定启动「" + GameMetadataFormatter.safeTitle(game) + "」吗？");
            LauncherTheme.dialogButtons(btnCancel, btnConfirm);

            btnCancel.setOnClickListener(v -> dialog.dismiss());
            btnConfirm.setOnClickListener(v -> {
                dialog.dismiss();
                com.apps.game.GamePasswordLock.interceptLaunch(LauncherLibraryFragment.this, game,
                        () -> sessionController.launchGameDirectly(LauncherLibraryFragment.this, game));
            });
        }
    }

    private void showGameActionMenu(Game game) {
        if (game == null) return;
        GameActionMenuFactory.ActionMenuConfig config = new GameActionMenuFactory.ActionMenuConfig();
        // Library 默认包含编辑/收藏/密码，宽度 252dp（与原实现一致）
        GameActionMenuFactory.showGameActionMenu(this, game, config, this);
    }

    // ===== GameActionMenuFactory.ActionMenuCallbacks =====

    @Override
    public void onShowGameDetail(Game game) {
        GameActionMenuFactory.showGameDetailDialog(this, game);
    }

    @Override
    public void onEditGame(Game game) {
        startEditGameActivity(game);
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

        java.util.List<String[]> options = new java.util.ArrayList<>();
        options.add(new String[]{"修改时长", "edit_play_time"});
        options.add(new String[]{"添加到桌面", "pin_shortcut"});
        options.add(new String[]{"重新匹配 VNDB 元数据", "rematch"});
        options.add(new String[]{"自定义搜索 VNDB", "custom_vndb"});
        options.add(new String[]{"同步元数据封面到卡片", "sync"});
        // ONS 引擎游戏支持单独配置 ONS 引擎参数（编码/拉伸/锐化/视频/独立存档目录等）
        if (game.engine == EngineType.ONS) {
            options.add(new String[]{"ONS 引擎设置", "ons_settings"});
        }
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
                    case "edit_play_time": GameActionMenuFactory.showEditPlayTimeDialog(this, game, this::updateSingleGame); break;
                    case "pin_shortcut": PinnedGameShortcut.requestPinShortcut(requireContext(), game); break;
                    case "rematch": syncController.rematchMetadata(game); break;
                    case "custom_vndb": LauncherCustomVndbSearchDialog.show(this, game, () -> reloadSingleGame(game.id)); break;
                    case "sync": syncController.syncMetadataToCard(game); break;
                    case "ons_settings": openOnsGameSettings(game); break;
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
        LauncherDialogFactory.showDangerConfirm(
                requireContext(),
                "删除游戏",
                "要删除「" + GameMetadataFormatter.safeTitle(game) + "」吗？此操作仅移除游戏库不进行实际删除。",
                "移除",
                () -> deleteGame(game)
        );
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

    /** Library 风格的同步加载对话框：包含进度文本（tag "sync_progress"），供 DialogFactory 调用。 */
    private AlertDialog createLibrarySyncLoadingDialog(String titleText, String hintText) {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.setCancelable(false);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return dialog;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(252), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(com.core.R.drawable.launcher_dialog_bg);

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

    private void startEditGameActivity(Game game) {
        pendingEditGameId = game.id;
        android.content.Intent intent = new android.content.Intent(requireContext(), LauncherGameEditActivity.class);
        intent.putExtra(LauncherGameEditActivity.EXTRA_GAME_ID, game.id);
        startActivity(intent);
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
        chip.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(com.core.R.dimen.launcher_library_category_text_size));
        chip.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        chip.setTag(value);
        if (selected) {
            chip.setTextColor(LauncherTheme.onPrimary(requireContext()));
            chip.setBackground(LauncherTheme.selectedChip(requireContext()));
        } else {
            LauncherTheme.menuItem(chip);
        }
        int chipHorizontalPadding = getResources().getDimensionPixelSize(
                com.core.R.dimen.launcher_library_category_horizontal_padding);
        chip.setPadding(chipHorizontalPadding, 0, chipHorizontalPadding, 0);
        chip.setOnClickListener(view -> {
            selectedCategory = value;
            renderCategories();
            applyFilters();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                getResources().getDimensionPixelSize(
                        com.core.R.dimen.launcher_library_category_chip_height));
        lp.setMargins(0, 0,
                getResources().getDimensionPixelSize(
                        com.core.R.dimen.launcher_library_category_chip_margin_end),
                0);
        binding.libraryCategoryRow.addView(chip, lp);
        LauncherTabletPortraitScaler.apply(chip);
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
