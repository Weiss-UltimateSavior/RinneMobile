package com.apps.PadUi;

import android.content.Context;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.Manifest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yuki.yukihub.databinding.FragmentLauncherLibraryBinding;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;
import com.yuki.yukihub.launcherbridge.LauncherGameLaunchBridge;
import com.yuki.yukihub.launcherbridge.LauncherMetadataBridge;
import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.metadata.VnMetadata;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;

import com.apps.LauncherMotion;
import com.apps.LauncherTheme;
import com.apps.LauncherCustomVndbSearchDialog;
import com.apps.GameLibraryState;
import com.apps.UserData.LauncherUserData;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 横屏手机游戏仓库自包含实现：直接继承 {@link Fragment}，使用 {@link PadManageGameAdapter}。
 * 横屏手机以 1 × 6、平板以 2 × 6 卡片分页展示，卡片高度根据 RecyclerView 的实际可用空间动态计算。
 * 不再继承 LauncherLibraryFragment，所有逻辑独立维护。
 */
public class PadManageFragment extends Fragment {
    private static final int GRID_COLUMNS = 6;
    private static final int PHONE_GRID_ROWS = 1;
    private static final int TABLET_GRID_ROWS = 2;
    private static final int TABLET_MIN_SMALLEST_WIDTH_DP = 600;
    private static final long MIN_PLAY_SESSION_MS = 0L;
    private static final long MAX_PLAY_SESSION_MS = 12L * 60L * 60L * 1000L;
    private static final long PLAY_SESSION_HEARTBEAT_MS = 60L * 1000L;
    private static final String CATEGORY_RECENT = "status:recent";
    private static final String CATEGORY_PLAYING = "status:playing";
    private static final String CATEGORY_COMPLETED = "status:completed";
    private static final String CATEGORY_UNPLAYED = "status:unplayed";
    private static final String CATEGORY_DEVELOPER_PREFIX = "developer:";

    private FragmentLauncherLibraryBinding binding;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Game> allGames = new ArrayList<>();
    private final List<Game> filteredGames = new ArrayList<>();
    private final List<Game> visibleGames = new ArrayList<>();
    private final GameLibraryState libraryState = new GameLibraryState();
    private final List<CategoryOption> categories = new ArrayList<>();
    private final Map<Long, List<String>> gameDevelopers = new HashMap<>();
    private PadManageGameAdapter adapter;
    private String selectedCategory = "";
    private String searchQuery = "";
    private boolean loading;
    private boolean fullyLoaded;
    private boolean categoriesCollapsed;
    private boolean dataLoaded;
    private boolean needsRefresh;
    private long runningSessionId = -1L;
    // 实际游玩时间监控：本地仍维护 play_sessions，线上排行榜使用服务端会话结算。
    private long runningGameId = -1L;
    private String runningGameTitle = "";
    private long runningSessionStart = 0L;
    private String runningServerSessionId = "";
    private String runningLaunchType = "external";
    private Runnable searchDebounce;
    private final Runnable playSessionHeartbeat = new Runnable() {
        @Override
        public void run() {
            heartbeatServerPlaySession();
            mainHandler.postDelayed(this, PLAY_SESSION_HEARTBEAT_MS);
        }
    };
    private GestureDetector swipeGestureDetector;
    private boolean swipeConsumed;
    private float loadMoreDragStartY;
    private boolean loadMoreDragCandidate;
    private int currentPage;
    private int gridRows = PHONE_GRID_ROWS;
    private int pageSize = GRID_COLUMNS * PHONE_GRID_ROWS;
    private AlertDialog syncLoadingDialog;

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
        gridRows = isTabletLayout() ? TABLET_GRID_ROWS : PHONE_GRID_ROWS;
        pageSize = GRID_COLUMNS * gridRows;
        setupSearchAndCategories();
        setupRecycler();
        loadGames();
        setupSwipeGesture();
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
        if (runningSessionId > 0L) {
            finishDirectPlaySessionIfNeeded();
        } else if (!dataLoaded || needsRefresh) {
            loadGames();
        }
    }

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog dialog = createLauncherDialog();
                LinearLayout root = createDialogRoot();
                root.addView(createDialogTitle("需要文件访问权限"));

                TextView info = new TextView(requireContext());
                info.setText("应用需要完全访问文件夹的权限来读取游戏文件。请在系统页面允许\"管理所有文件\"。");
                info.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_muted_color));
                info.setTextSize(12);
                info.setLineSpacing(dp(4), 1f);
                LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                infoLp.setMargins(0, dp(13), 0, 0);
                root.addView(info, infoLp);

                root.addView(createDialogButton("前往", true, () -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + requireContext().getPackageName())));
                    } catch (Throwable t) {
                        try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); } catch (Throwable ignored) { }
                    }
                }, dialog));

                root.addView(createDialogCancelButton(dialog));

                android.view.Window window = dialog.getWindow();
                if (window != null) {
                    window.setContentView(root);
                    window.setLayout(dp(288), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
                }
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (requireActivity().checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1001);
            }
        }
    }

    @Override
    public void onDestroyView() {
        mainHandler.removeCallbacks(playSessionHeartbeat);
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
            if (bottom - top != oldBottom - oldTop) updateFixedGridCardHeight();
        });
        binding.libraryRecycler.post(this::updateFixedGridCardHeight);
        binding.libraryRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                // 横向分页模式下不通过滚动加载更多
                return;
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

    /**
     * 横屏手机高度有限，不能只根据卡片宽度按固定比例反推高度。
     * 这里同时计算：
     * 1. 六列布局允许的卡片宽度；
     * 2. 当前设备一行或两行布局允许的最大高度；
     * 最终取两者较小值，确保所有卡片完整显示。
     */
    private void updateFixedGridCardHeight() {
        if (binding == null || adapter == null) return;

        RecyclerView recyclerView = binding.libraryRecycler;
        int recyclerWidth = recyclerView.getWidth();
        int recyclerHeight = recyclerView.getHeight();
        if (recyclerWidth <= 0 || recyclerHeight <= 0) return;

        int usableWidth = recyclerWidth
                - recyclerView.getPaddingLeft()
                - recyclerView.getPaddingRight();
        int usableHeight = recyclerHeight
                - recyclerView.getPaddingTop()
                - recyclerView.getPaddingBottom();
        if (usableWidth <= 0 || usableHeight <= 0) return;

        // item_launcher_game_card 每张卡片左右各约 5dp margin。
        int totalHorizontalMargins = dp(10) * GRID_COLUMNS;
        int cardWidth = Math.max(
                1,
                (usableWidth - totalHorizontalMargins) / GRID_COLUMNS
        );

        // 每行卡片上下 margin 合计约 10dp。
        int totalVerticalMargins = dp(10) * gridRows;
        int heightByAvailableSpace = Math.max(
                1,
                (usableHeight - totalVerticalMargins) / gridRows
        );

        // 横屏手机使用更紧凑的封面比例，避免按原 5:3 比例生成过高卡片。
        int heightByCompactRatio = Math.max(1, Math.round(cardWidth * 1.25f));

        // 优先保证当前行数完整显示，卡片绝不超过 RecyclerView 当前可用高度。
        int finalCardHeight = Math.min(heightByAvailableSpace, heightByCompactRatio);
        adapter.setFixedCardHeight(finalCardHeight);
    }

    private boolean isTabletLayout() {
        return getResources().getConfiguration().smallestScreenWidthDp
                >= TABLET_MIN_SMALLEST_WIDTH_DP;
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
        return showNextPage();
    }

    private boolean handleSwipeRight() {
        return showPreviousPage();
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
        binding.librarySyncButton.setOnClickListener(view -> showSyncDataConfirmDialog());
        binding.libraryCollapseButton.setOnClickListener(view -> {
            categoriesCollapsed = !categoriesCollapsed;
            binding.libraryCategoryScroll.setVisibility(categoriesCollapsed ? View.GONE : View.VISIBLE);
            renderToolbarButtonState();
        });
        binding.librarySearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s == null ? "" : s.toString().trim();
                if (searchDebounce != null) mainHandler.removeCallbacks(searchDebounce);
                searchDebounce = () -> applyFilters();
                mainHandler.postDelayed(searchDebounce, 300);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        renderToolbarButtonState();
    }

    private void loadGames() {
        setLoading(true);
        needsRefresh = false;
        Context appContext = requireContext().getApplicationContext();
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
                CategoryBuildResult result = buildCategoriesInBackground(appContext, games);
                developers = result.developers;
                builtCategories = result.categories;
            } catch (Throwable throwable) {
                developers = Collections.emptyMap();
                builtCategories = Collections.emptyList();
            }
            List<Game> loadedGames = games;
            Map<Long, List<String>> loadedDevelopers = developers;
            List<CategoryOption> loadedCategories = builtCategories;
            mainHandler.post(() -> {
                if (binding == null) return;
                allGames.clear();
                allGames.addAll(loadedGames);
                libraryState.replaceAll(loadedGames);
                gameDevelopers.clear();
                gameDevelopers.putAll(loadedDevelopers);
                categories.clear();
                categories.addAll(loadedCategories);
                if (selectedCategory != null && !selectedCategory.isEmpty() && !containsCategoryValue(selectedCategory)) {
                    selectedCategory = "";
                }
                renderCategories();
                dataLoaded = true;
                // 数据已经落到内存，解除翻页锁。showNextPage()/showPreviousPage()
                // 会在 loading=true 时直接拒绝手势；此前这里没有复位，导致列表永远停在第一页。
                setLoading(false);
                applyFilters();
            });
        });
    }

    private void applyFilters() {
    applyFilters(false);
}

private void applyFilters(boolean forceFullRefresh) {
    libraryState.setQuery(searchQuery);
    libraryState.setCategory(selectedCategory);
    libraryState.rebuild((game, query, category) -> {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        return (normalized.isEmpty() || safeTitle(game).toLowerCase(Locale.ROOT).contains(normalized))
                && (category.trim().isEmpty() || matchesCategory(game, category));
    }, (left, right) -> Collator.getInstance(Locale.CHINA).compare(safeTitle(left), safeTitle(right)), pageSize, true);
    syncLibraryLists();
    // 横向分页恒为 true：直接走分页渲染
    renderPagedGrid(forceFullRefresh);
    renderState();
}

private void renderPagedGrid(boolean forceFullRefresh) {
    if (adapter == null) return;
    libraryState.renderPage(pageSize);
    syncLibraryLists();
    adapter.submit(new ArrayList<>(visibleGames), forceFullRefresh);
}

private boolean showNextPage() {
    if (loading) return false;
    if (!libraryState.nextPage(pageSize)) return false;
    syncLibraryLists();
    renderPagedGrid(false);
    renderState();
    animatePageChange(true);
    return true;
}

private boolean showPreviousPage() {
    if (loading || !libraryState.previousPage(pageSize)) return false;
    syncLibraryLists();
    renderPagedGrid(false);
    renderState();
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
    loadNextPage(false);
}

private void loadNextPage(boolean forceFullRefresh) {
    if (adapter == null || loading && !visibleGames.isEmpty()) return;
    loading = true;
    libraryState.loadNext(pageSize);
    syncLibraryLists();
    adapter.submit(new ArrayList<>(visibleGames), forceFullRefresh);
    loading = false;
    renderState();
}

    private void syncLibraryLists() {
        filteredGames.clear(); filteredGames.addAll(libraryState.getFiltered());
        visibleGames.clear(); visibleGames.addAll(libraryState.getVisible());
        currentPage = libraryState.getPage(); fullyLoaded = libraryState.isFullyLoaded();
    }

    private void renderState() {
        if (binding == null) return;
        boolean hasGames = !visibleGames.isEmpty();
        binding.libraryRecycler.setVisibility(hasGames ? View.VISIBLE : View.GONE);
        if (hasGames) {
            binding.libraryRecycler.post(this::updateFixedGridCardHeight);
        }
        binding.libraryEmpty.setText(allGames.isEmpty() ? "还没有游戏" : "没有匹配的游戏");
        binding.libraryEmpty.setVisibility(hasGames ? View.GONE : View.VISIBLE);
        if (allGames.isEmpty() || filteredGames.isEmpty()) {
            binding.libraryFooter.setVisibility(View.GONE);
        } else {
            binding.libraryFooter.setVisibility(fullyLoaded ? View.GONE : View.VISIBLE);
            binding.libraryFooter.setText("上拉加载");
        }
    }


    private void handleLoadMoreDragWhenNotScrollable(@NonNull RecyclerView recyclerView, @NonNull MotionEvent event) {
        if (loading || fullyLoaded || filteredGames.isEmpty() || visibleGames.size() >= filteredGames.size()) {
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

    private void setLoading(boolean value) {
        loading = value;
        if (binding != null) {
            binding.libraryFooter.setVisibility(value ? View.VISIBLE : View.GONE);
            binding.libraryFooter.setText("正在加载...");
        }
    }

    private void launchGameDirectly(Game game) {
        if (game == null) return;
        LauncherGameLaunchBridge.LaunchResult result = LauncherGameLaunchBridge.launch(requireContext(), game);
        if (result.success) {
            runningSessionId = result.sessionId;
            // 记录会话元信息；线上实际游玩时长由服务端 session 结算。
            runningGameId = game.id;
            runningGameTitle = safeTitle(game);
            runningSessionStart = System.currentTimeMillis();
            runningLaunchType = resolveLaunchTypeForRecord(game);
            startServerPlaySession(game, result.sessionId);
        } else if (result.message != null && !result.message.trim().isEmpty()) {
            Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show();
        }
    }

    private void finishDirectPlaySessionIfNeeded() {
        if (runningSessionId <= 0L) return;
        Context context = getContext();
        if (context == null) return;
        // 1) 主项目会话收尾（写入 play_sessions 表 + 累加 total_play_time）
        LauncherGameLaunchBridge.finishSession(context, runningSessionId, MIN_PLAY_SESSION_MS, MAX_PLAY_SESSION_MS);
        Context app = context.getApplicationContext();
        // 2) 线上实际游玩时长只结束服务端 session，不提交本地 duration。
        finishServerPlaySession(app, runningSessionId);
        runningSessionId = -1L;
        runningGameId = -1L;
        runningGameTitle = "";
        runningSessionStart = 0L;
        runningServerSessionId = "";
        runningLaunchType = "external";
        loadGames();
    }

    private void startServerPlaySession(Game game, long localSessionId) {
        Context context = getContext();
        if (context == null || game == null || localSessionId <= 0L) return;
        Context app = context.getApplicationContext();
        if (!LauncherAuthBridge.isLoggedIn(app)) return;
        android.content.SharedPreferences prefs = app.getSharedPreferences("launcher_account_settings", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("realtime_playtime", true)) return;
        String deviceId = LauncherUserData.getRealtimePlaytimeDeviceId(app);
        LauncherAuthBridge.startPlayTimeSession(app, game.id, safeTitle(game), deviceId,
                new LauncherAuthBridge.PlaySessionCallback() {
            @Override
            public void onSuccess(LauncherAuthBridge.PlaySession session) {
                if (session == null || session.sessionId == null || session.sessionId.trim().isEmpty()) return;
                if (runningSessionId != localSessionId) return;
                runningServerSessionId = session.sessionId;
                LauncherUserData.rememberServerPlaySession(app, localSessionId, game.id, safeTitle(game), session.sessionId);
                scheduleServerPlayHeartbeat();
            }

            @Override
            public void onError(String message) {
                // 静默失败：不能回退到本地 duration 上传。
            }
        });
    }

    private void scheduleServerPlayHeartbeat() {
        mainHandler.removeCallbacks(playSessionHeartbeat);
        mainHandler.postDelayed(playSessionHeartbeat, PLAY_SESSION_HEARTBEAT_MS);
    }

    private void heartbeatServerPlaySession() {
        Context context = getContext();
        if (context == null || runningServerSessionId == null || runningServerSessionId.trim().isEmpty()) return;
        Context app = context.getApplicationContext();
        LauncherAuthBridge.heartbeatPlayTimeSession(app, runningServerSessionId, new LauncherAuthBridge.PlaySessionCallback() {
            @Override public void onSuccess(LauncherAuthBridge.PlaySession session) { }
            @Override public void onError(String message) { }
        });
    }

    private void finishServerPlaySession(Context app, long localSessionId) {
        mainHandler.removeCallbacks(playSessionHeartbeat);
        String serverSessionId = runningServerSessionId == null || runningServerSessionId.trim().isEmpty()
                ? LauncherUserData.findServerPlaySessionId(app, localSessionId)
                : runningServerSessionId;
        if (serverSessionId == null || serverSessionId.trim().isEmpty()) return;
        LauncherAuthBridge.finishPlayTimeSession(app, serverSessionId, new LauncherAuthBridge.PlaySessionCallback() {
            @Override
            public void onSuccess(LauncherAuthBridge.PlaySession session) {
                LauncherUserData.removeServerPlaySession(app, localSessionId);
            }

            @Override
            public void onError(String message) {
                // finish 可重试；失败时保留 session_id 映射，等待后续恢复流程处理。
            }
        });
    }

    /**
     * 与 LauncherGameLaunchBridge.resolveLaunchType 保持一致的启动类型推导，
     * 仅用于实际游玩记录的 launchType 字段标记，便于后续上传区分启动方式。
     */
    private String resolveLaunchTypeForRecord(Game game) {
        if (game == null || game.emulatorPackage == null) return "external";
        String pkg = game.emulatorPackage.trim().toLowerCase(Locale.ROOT);
        if (pkg.startsWith("internal.krkr") || pkg.equals("org.tvp.kirikiri2.internal")) return "internal.krkr";
        if (pkg.startsWith("internal.ons") || pkg.equals("com.yuki.yukihub.ons")) return "internal.ons";
        if (pkg.startsWith("internal.tyrano") || pkg.equals("com.yuki.yukihub.tyrano")) return "internal.tyrano";
        if (pkg.startsWith("internal.artemis")) return pkg;
        if (pkg.startsWith("internal.psp") || pkg.equals("org.ppsspp.ppsspp")) return "internal.psp";
        return "external";
    }

    private void confirmLaunchGame(Game game) {
        if (game == null) return;
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .create();
        dialog.show();

        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(
                    (int) (252 * getResources().getDisplayMetrics().density),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT
            );
            android.view.View dialogView = android.view.LayoutInflater.from(requireContext())
                    .inflate(com.yuki.yukihub.R.layout.dialog_launcher_confirm, null);
            window.setContentView(dialogView);

            TextView titleView = dialogView.findViewById(com.yuki.yukihub.R.id.dialogTitle);
            TextView messageView = dialogView.findViewById(com.yuki.yukihub.R.id.dialogMessage);
            TextView btnCancel = dialogView.findViewById(com.yuki.yukihub.R.id.dialogBtnCancel);
            TextView btnConfirm = dialogView.findViewById(com.yuki.yukihub.R.id.dialogBtnConfirm);

            titleView.setText("启动游戏");
            messageView.setText("确定启动「" + safeTitle(game) + "」吗？");
            LauncherTheme.dialogButtons(btnCancel, btnConfirm);

            btnCancel.setOnClickListener(v -> dialog.dismiss());
            btnConfirm.setOnClickListener(v -> {
                dialog.dismiss();
                launchGameDirectly(game);
            });
        }
    }

    private void showGameActionMenu(Game game) {
        if (game == null) return;
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.show();

        android.view.Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(270), android.view.WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_dialog_bg);

        TextView title = new TextView(requireContext());
        title.setText(safeTitle(game));
        title.setGravity(android.view.Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        addGameActionOption(root, "详情", dialog, game, () -> showGameDetailDialog(game));
        addGameActionOption(root, "状态", dialog, game, () -> showPlayStatusDialog(game));
        addGameActionOption(root, "修改时长", dialog, game, () -> showEditPlayTimeDialog(game));
        addGameActionOption(root, "更多选项", dialog, game, () -> showMoreOptionsDialog(game));

        TextView cancel = new TextView(requireContext());
        cancel.setText("取消");
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setTextColor(LauncherTheme.primary(requireContext()));
        cancel.setTextSize(13);
        cancel.setTypeface(null, android.graphics.Typeface.BOLD);
        cancel.setBackground(LauncherTheme.cancelChip(requireContext()));
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        cancelLp.setMargins(0, dp(9), 0, 0);
        root.addView(cancel, cancelLp);

        window.setContentView(root);
    }

    private void addGameActionOption(LinearLayout root, String label, AlertDialog dialog, Game game, Runnable action) {
        TextView option = new TextView(requireContext());
        option.setText(label);
        option.setGravity(android.view.Gravity.CENTER);
        option.setSingleLine(true);
        option.setTextSize(13);
        option.setTypeface(null, android.graphics.Typeface.BOLD);
        LauncherTheme.menuItem(option);
        option.setOnClickListener(view -> {
            dialog.dismiss();
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        lp.setMargins(0, dp(11), 0, 0);
        root.addView(option, lp);
    }

    private String safeTitle(Game game) {
        if (game == null || game.title == null || game.title.trim().isEmpty()) return "未命名游戏";
        return game.title.trim();
    }

    private AlertDialog createLauncherDialog() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        return dialog;
    }

    private LinearLayout createDialogRoot() {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_dialog_bg);
        return root;
    }

    private TextView createDialogTitle(String text) {
        TextView title = new TextView(requireContext());
        title.setText(text);
        title.setGravity(android.view.Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        return title;
    }

    private TextView createDialogButton(String text, boolean primary, Runnable action, AlertDialog dialog) {
        TextView btn = new TextView(requireContext());
        btn.setText(text);
        btn.setGravity(android.view.Gravity.CENTER);
        btn.setTextSize(13);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        if (primary) {
            LauncherTheme.primaryButton(btn);
        } else {
            LauncherTheme.secondaryButton(btn);
        }
        btn.setOnClickListener(v -> { dialog.dismiss(); action.run(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        lp.setMargins(0, dp(9), 0, 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private TextView createDialogCancelButton(AlertDialog dialog) {
        TextView cancel = new TextView(requireContext());
        cancel.setText("取消");
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setTextColor(LauncherTheme.primary(requireContext()));
        cancel.setTextSize(13);
        cancel.setTypeface(null, android.graphics.Typeface.BOLD);
        cancel.setBackground(LauncherTheme.cancelChip(requireContext()));
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        lp.setMargins(0, dp(9), 0, 0);
        cancel.setLayoutParams(lp);
        return cancel;
    }

    private void showPlayStatusDialog(Game game) {
        if (game == null) return;
        AlertDialog dialog = createLauncherDialog();
        LinearLayout root = createDialogRoot();
        root.addView(createDialogTitle("设置游玩状态"));

        String[] labels = {"☆ 未玩", "🎮 在玩", "🏆 玩过"};
        String[] values = {"unplayed", "playing", "completed"};
        for (int i = 0; i < labels.length; i++) {
            final String status = values[i];
            TextView option = new TextView(requireContext());
            option.setText((status.equals(game.playStatus) ? "● " : "○ ") + labels[i]);
            option.setGravity(android.view.Gravity.CENTER);
            option.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
            option.setTextSize(13);
            option.setTypeface(null, android.graphics.Typeface.BOLD);
            option.setBackground(LauncherTheme.cancelChip(requireContext()));
            option.setOnClickListener(v -> {
                dialog.dismiss();
                updateGameStatus(game, status);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
            lp.setMargins(0, dp(11), 0, 0);
            root.addView(option, lp);
        }
        root.addView(createDialogCancelButton(dialog));
        dialog.getWindow().setContentView(root);
        dialog.getWindow().setLayout(dp(252), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void updateGameStatus(Game game, String status) {
        AppExecutors.io().execute(() -> {
            try {
                Game latest = LauncherRepositoryBridge.findGameById(requireContext(), game.id);
                if (latest != null) {
                    latest.playStatus = status;
                    LauncherRepositoryBridge.updateGame(requireContext(), latest);
                }
            } catch (Throwable ignored) {}
            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                needsRefresh = true;
                loadGames();
            });
        });
    }

    private void showEditPlayTimeDialog(Game game) {
        if (game == null) return;
        // 使用 Dialog 而非 AlertDialog，避免 FLAG_NOT_FOCUSABLE 导致输入法无法唤醒
        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout root = createDialogRoot();
        root.addView(createDialogTitle("修改游玩时长"));

        TextView info = new TextView(requireContext());
        info.setText("当前总时长：" + com.yuki.yukihub.util.TimeFormatUtil.playTime(game.totalPlayTime)
                + "\n最近游玩：" + (game.lastPlayedAt > 0 ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new java.util.Date(game.lastPlayedAt)) : "无"));
        info.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_muted_color));
        info.setTextSize(12);
        info.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, dp(13), 0, 0);
        root.addView(info, infoLp);

        TextView totalLabel = new TextView(requireContext());
        totalLabel.setText("设置新的总时长");
        totalLabel.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        totalLabel.setTextSize(12);
        totalLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlLp.setMargins(0, dp(13), 0, 0);
        root.addView(totalLabel, tlLp);

        android.widget.EditText totalInput = new android.widget.EditText(requireContext());
        totalInput.setHint("例如 3h 20m / 200m / 7200s / 2.5h");
        totalInput.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        totalInput.setHintTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_input_hint_color));
        totalInput.setTextSize(13);
        totalInput.setPadding(dp(13), dp(9), dp(13), dp(9));
        totalInput.setBackground(LauncherTheme.cancelChip(requireContext()));
        LinearLayout.LayoutParams tiLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tiLp.setMargins(0, dp(5), 0, 0);
        root.addView(totalInput, tiLp);

        TextView addLabel = new TextView(requireContext());
        addLabel.setText("追加游玩时长");
        addLabel.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        addLabel.setTextSize(12);
        addLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams alLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alLp.setMargins(0, dp(11), 0, 0);
        root.addView(addLabel, alLp);

        android.widget.EditText addInput = new android.widget.EditText(requireContext());
        addInput.setHint("例如 30m / 1h30m / 0.5h");
        addInput.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        addInput.setHintTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_input_hint_color));
        addInput.setTextSize(13);
        addInput.setPadding(dp(13), dp(9), dp(13), dp(9));
        addInput.setBackground(LauncherTheme.cancelChip(requireContext()));
        LinearLayout.LayoutParams aiLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        aiLp.setMargins(0, dp(5), 0, 0);
        root.addView(addInput, aiLp);

        TextView hint = new TextView(requireContext());
        hint.setText("可填 d/h/m/s 单位组合，纯数字视为分钟");
        hint.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_muted_color));
        hint.setTextSize(11);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hLp.setMargins(0, dp(7), 0, 0);
        root.addView(hint, hLp);

        LinearLayout btnRow = new LinearLayout(requireContext());
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setWeightSum(2f);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brLp.setMargins(0, dp(13), 0, 0);
        btnRow.setLayoutParams(brLp);

        TextView cancelBtn = new TextView(requireContext());
        cancelBtn.setText("取消");
        cancelBtn.setGravity(android.view.Gravity.CENTER);
        cancelBtn.setTextSize(13);
        cancelBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        LauncherTheme.secondaryButton(cancelBtn);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        cancelLp.setMargins(0, 0, dp(5), 0);
        cancelBtn.setLayoutParams(cancelLp);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(cancelBtn);

        TextView saveBtn = new TextView(requireContext());
        saveBtn.setText("保存");
        saveBtn.setGravity(android.view.Gravity.CENTER);
        saveBtn.setTextSize(13);
        saveBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        LauncherTheme.primaryButton(saveBtn);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        saveLp.setMargins(dp(5), 0, 0, 0);
        saveBtn.setLayoutParams(saveLp);
        saveBtn.setOnClickListener(v -> {
            Long totalMinutes = parseDuration(totalInput.getText().toString().trim());
            Long addMinutes = parseDuration(addInput.getText().toString().trim());
            if (totalMinutes == null && addMinutes == null) {
                Toast.makeText(requireContext(), "请输入有效时长", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            updatePlayTime(game, totalMinutes, addMinutes);
        });
        btnRow.addView(saveBtn);
        root.addView(btnRow);

        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.setContentView(root);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        if (window != null) {
            window.setLayout(dp(288), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }

        totalInput.requestFocus();
        totalInput.post(() -> {
            InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(totalInput, 0);
        });
    }

    private Long parseDuration(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        text = text.trim().toLowerCase(Locale.ROOT);
        try {
            if (!text.matches(".*[dhms].*")) return (long) Double.parseDouble(text);
            long total = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([dhms])").matcher(text);
            boolean found = false;
            while (m.find()) {
                found = true;
                double val = Double.parseDouble(m.group(1));
                String unit = m.group(2);
                if (unit.equals("d")) total += val * 1440;
                else if (unit.equals("h")) total += val * 60;
                else if (unit.equals("m")) total += val;
                else if (unit.equals("s")) total += val / 60;
            }
            return found ? total : null;
        } catch (Throwable t) { return null; }
    }

    private void updatePlayTime(Game game, Long totalMinutes, Long addMinutes) {
        AppExecutors.io().execute(() -> {
            try {
                Game latest = LauncherRepositoryBridge.findGameById(requireContext(), game.id);
                if (latest == null) return;
                long finalDuration = latest.totalPlayTime;
                if (totalMinutes != null) finalDuration = totalMinutes * 60_000L;
                if (addMinutes != null) finalDuration += addMinutes * 60_000L;
                LauncherRepositoryBridge.setManualPlayTimeForGame(requireContext(), latest.id, Math.max(0, finalDuration));
            } catch (Throwable ignored) {}
            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                needsRefresh = true;
                loadGames();
            });
        });
    }

    private void showGameDetailDialog(Game game) {
        if (game == null) return;
        AlertDialog dialog = createLauncherDialog();
        LinearLayout root = createDialogRoot();
        root.addView(createDialogTitle(safeTitle(game)));

        TextView info = new TextView(requireContext());
        StringBuilder sb = new StringBuilder();
        sb.append("状态：").append(playStatusText(game.playStatus));
        sb.append("\n引擎：").append(engineText(game.engine));
        sb.append("\n总时长：").append(com.yuki.yukihub.util.TimeFormatUtil.playTime(game.totalPlayTime));
        sb.append("\n最近游玩：").append(game.lastPlayedAt > 0 ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new java.util.Date(game.lastPlayedAt)) : "未游玩");
        if (game.emulatorPackage != null && !game.emulatorPackage.trim().isEmpty())
            sb.append("\n模拟器：").append(game.emulatorPackage);
        sb.append("\n\n路径：").append(game.rootUri);
        info.setText(sb.toString());
        info.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        info.setTextSize(12);
        info.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, dp(13), 0, 0);
        root.addView(info, infoLp);


        root.addView(createDialogCancelButton(dialog));
        dialog.getWindow().setContentView(root);
        dialog.getWindow().setLayout(dp(288), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void showMoreOptionsDialog(Game game) {
        if (game == null) return;
        AlertDialog dialog = createLauncherDialog();
        LinearLayout root = createDialogRoot();
        root.addView(createDialogTitle("更多选项"));

        String favoriteLabel = game.favorite ? "取消收藏" : "添加收藏";
        String[][] options = {
            {favoriteLabel, "favorite"},
            {"重新匹配 VNDB 元数据", "rematch"},
            {"自定义搜索 VNDB", "custom_vndb"},
            {"同步元数据封面到卡片", "sync"},
            {"删除游戏", "delete"}
        };
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
                    case "rematch": rematchMetadata(game); break;
                    case "custom_vndb": LauncherCustomVndbSearchDialog.show(this, game, this::loadGames); break;
                    case "sync": syncMetadataToCard(game); break;
                    case "delete": confirmDeleteGame(game); break;
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
            lp.setMargins(0, dp(11), 0, 0);
            root.addView(option, lp);
        }
        root.addView(createDialogCancelButton(dialog));
        dialog.getWindow().setContentView(root);
        dialog.getWindow().setLayout(dp(270), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void toggleFavorite(Game game) {
        AppExecutors.io().execute(() -> {
            try {
                Game latest = LauncherRepositoryBridge.findGameById(requireContext(), game.id);
                if (latest != null) {
                    latest.favorite = !latest.favorite;
                    LauncherRepositoryBridge.updateGame(requireContext(), latest);
                }
            } catch (Throwable ignored) {}
            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                needsRefresh = true;
                loadGames();
            });
        });
    }

    private void confirmDeleteGame(Game game) {
        AlertDialog confirm = createLauncherDialog();
        LinearLayout root = createDialogRoot();
        root.addView(createDialogTitle("删除游戏"));
        TextView msg = new TextView(requireContext());
        msg.setText("要删除「" + safeTitle(game) + "」吗？此操作仅移除游戏库不进行实际删除。");
        msg.setGravity(android.view.Gravity.CENTER);
        msg.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_muted_color));
        msg.setTextSize(12);
        msg.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dp(13), 0, 0);
        root.addView(msg, msgLp);

        LinearLayout btnRow = new LinearLayout(requireContext());
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setWeightSum(2f);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brLp.setMargins(0, dp(13), 0, 0);
        btnRow.setLayoutParams(brLp);

        TextView cancelBtn = createDialogCancelButton(confirm);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        cancelLp.setMargins(0, 0, dp(5), 0);
        cancelBtn.setLayoutParams(cancelLp);
        btnRow.addView(cancelBtn);

        TextView deleteBtn = new TextView(requireContext());
        deleteBtn.setText("移除");
        deleteBtn.setGravity(android.view.Gravity.CENTER);
        deleteBtn.setTextSize(13);
        deleteBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        LauncherTheme.dangerButton(deleteBtn);
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        delLp.setMargins(dp(5), 0, 0, 0);
        deleteBtn.setLayoutParams(delLp);
        deleteBtn.setOnClickListener(v -> {
            confirm.dismiss();
            deleteGame(game);
        });
        btnRow.addView(deleteBtn);
        root.addView(btnRow);

        confirm.getWindow().setContentView(root);
        confirm.getWindow().setLayout(dp(270), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void deleteGame(Game game) {
        AppExecutors.io().execute(() -> {
            try {
                LauncherRepositoryBridge.deleteGame(requireContext(), game.id);
            } catch (Throwable ignored) {}
            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                loadGames();
                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void showSyncDataConfirmDialog() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(270), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_dialog_bg);

        TextView title = new TextView(requireContext());
        title.setText("同步数据");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(requireContext());
        message.setText("全部同步需要一定时间，是否一键同步刷新所有游戏的元数据与封面？");
        message.setGravity(android.view.Gravity.CENTER);
        message.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_muted_color));
        message.setTextSize(12);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dp(13), 0, 0);
        root.addView(message, msgLp);

        TextView confirmBtn = new TextView(requireContext());
        confirmBtn.setText("确定同步");
        confirmBtn.setGravity(android.view.Gravity.CENTER);
        LauncherTheme.primaryButton(confirmBtn);
        confirmBtn.setOnClickListener(v -> {
            dialog.dismiss();
            performBatchSync();
        });
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        confirmLp.setMargins(0, dp(11), 0, 0);
        root.addView(confirmBtn, confirmLp);

        TextView cancelBtn = new TextView(requireContext());
        cancelBtn.setText("取消");
        cancelBtn.setGravity(android.view.Gravity.CENTER);
        cancelBtn.setTextColor(LauncherTheme.primary(requireContext()));
        cancelBtn.setTextSize(13);
        cancelBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        cancelBtn.setBackground(LauncherTheme.cancelChip(requireContext()));
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        cancelLp.setMargins(0, dp(9), 0, 0);
        root.addView(cancelBtn, cancelLp);

        window.setContentView(root);
    }

    private void performBatchSync() {
        syncLoadingDialog = showSyncLoadingDialog("正在同步数据...", "请不要关闭应用及网络，否则可能导致数据出错");

        Context appContext = requireContext().getApplicationContext();
        AppExecutors.io().execute(() -> {
            final long syncBatchVersion = System.currentTimeMillis();
            // 获取所有游戏
            List<Game> syncGames;
            try {
                syncGames = LauncherRepositoryBridge.getAllGames(appContext);
            } catch (Throwable e) {
                syncGames = Collections.emptyList();
            }

            int total = syncGames.size();
            int synced = 0;
            int failed = 0;

            for (int i = 0; i < total; i++) {
                Game game = syncGames.get(i);
                if (game.title == null || game.title.trim().isEmpty()) {
                    failed++;
                    continue;
                }
                try {
                    // 1. 重新匹配 VNDB 元数据
                    VnMetadata meta = LauncherMetadataBridge.fetchAndSaveVndbSync(appContext, game);
                    if (meta != null) {
                        // 2. 同步封面到卡片
                        if (meta.coverUrl != null && !meta.coverUrl.trim().isEmpty()) {
                            String cover = com.yuki.yukihub.launcherbridge.LauncherCoverBridge.downloadCover(
                                  appContext,
                                  meta.coverUrl,
                                  "sync_cover_" + game.id + "_" + syncBatchVersion
                            );
                            if (cover != null) {
                                Game latest = LauncherRepositoryBridge.findGameById(appContext, game.id);
                                if (latest != null) {
                                    latest.coverUri = cover;
                                    latest.coverPersistUri = cover;
                                    latest.coverSourceType = 1;
                                    LauncherRepositoryBridge.updateGame(appContext, latest);
                                }
                            }
                        }
                        synced++;
                    } else {
                        failed++;
                    }
                } catch (Throwable e) {
                    failed++;
                }

                // 更新加载弹窗进度
                final int progress = i + 1;
                final int totalGames = total;
                mainHandler.post(() -> {
                    if (syncLoadingDialog != null && syncLoadingDialog.isShowing()) {
                        Window w = syncLoadingDialog.getWindow();
                        if (w != null) {
                            android.widget.TextView progressView = w.getDecorView().findViewWithTag("sync_progress");
                            if (progressView != null) {
                                progressView.setText(progress + "/" + totalGames + " 已完成");
                            }
                        }
                    }
                });
            }

            // 同步完成后：在 IO 线程直接重新加载游戏列表，然后一次性刷新 UI
            List<Game> finalGames;
            try {
                finalGames = LauncherRepositoryBridge.getAllGames(appContext);
            } catch (Throwable e) {
                finalGames = Collections.emptyList();
            }

            final int syncedCount = synced;
            final int failedCount = failed;
            List<Game> loadedGames = finalGames;
            CategoryBuildResult categoryResult;
try {
    categoryResult = buildCategoriesInBackground(appContext, loadedGames);
} catch (Throwable throwable) {
    categoryResult = new CategoryBuildResult(Collections.emptyList(), Collections.emptyMap());
}

CategoryBuildResult loadedCategoryResult = categoryResult;

mainHandler.post(() -> {
    if (!isAdded()) return;

    if (binding != null) {
        allGames.clear();
        allGames.addAll(loadedGames);

        gameDevelopers.clear();
        gameDevelopers.putAll(loadedCategoryResult.developers);

        categories.clear();
        categories.addAll(loadedCategoryResult.categories);

        if (selectedCategory != null && !selectedCategory.isEmpty() && !containsCategoryValue(selectedCategory)) {
            selectedCategory = "";
        }

        renderCategories();
        dataLoaded = true;

        // 关键：批量同步完成后，强制刷新当前页面所有卡片
        applyFilters(true);

        binding.libraryRecycler.post(() -> {
            if (adapter != null) adapter.notifyDataSetChanged();
        });
    }

    dismissSyncLoadingDialog();
    showSyncResultDialog(syncedCount, failedCount);
});
        });
    }

    private AlertDialog showSyncLoadingDialog(String titleText, String hintText) {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.setCancelable(false);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return dialog;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(270), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_dialog_bg);

        TextView title = new TextView(requireContext());
        title.setText(titleText);
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
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
        progressText.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_muted_color));
        progressText.setTextSize(12);
        LinearLayout.LayoutParams ptLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ptLp.setMargins(0, dp(6), 0, 0);
        root.addView(progressText, ptLp);

        TextView hint = new TextView(requireContext());
        hint.setText(hintText);
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_muted_color));
        hint.setTextSize(11);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, dp(10), 0, 0);
        root.addView(hint, hintLp);

        window.setContentView(root);
        return dialog;
    }

    private void dismissSyncLoadingDialog() {
        if (syncLoadingDialog != null && syncLoadingDialog.isShowing()) {
            syncLoadingDialog.dismiss();
            syncLoadingDialog = null;
        }
    }

    private void showSyncResultDialog(int synced, int failed) {
        String message = "同步完成 " + synced + " 个" + (failed > 0 ? "\n失败 " + failed + " 个" : "");
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(270), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_dialog_bg);

        TextView titleView = new TextView(requireContext());
        titleView.setText("同步完成");
        titleView.setGravity(android.view.Gravity.CENTER);
        titleView.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        titleView.setTextSize(16);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(titleView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView msgView = new TextView(requireContext());
        msgView.setText(message);
        msgView.setGravity(android.view.Gravity.CENTER);
        msgView.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_muted_color));
        msgView.setTextSize(12);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dp(13), 0, 0);
        root.addView(msgView, msgLp);

        TextView okBtn = new TextView(requireContext());
        okBtn.setText("知道了");
        okBtn.setGravity(android.view.Gravity.CENTER);
        LauncherTheme.primaryButton(okBtn);
        okBtn.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        okLp.setMargins(0, dp(11), 0, 0);
        root.addView(okBtn, okLp);

        window.setContentView(root);
    }

    private void rematchMetadata(Game game) {
        Toast.makeText(requireContext(), "正在搜索 VNDB...", Toast.LENGTH_SHORT).show();
        com.yuki.yukihub.launcherbridge.LauncherMetadataBridge.fetchAndSaveMetadataAsync(requireContext(), game, success -> {
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                Toast.makeText(requireContext(), success ? "元数据已更新" : "未找到匹配的元数据", Toast.LENGTH_SHORT).show();
                if (success) loadGames();
            });
        });
    }

    private void syncMetadataToCard(Game game) {
        Toast.makeText(requireContext(), "正在同步封面...", Toast.LENGTH_SHORT).show();
        com.yuki.yukihub.launcherbridge.LauncherMetadataBridge.syncCoverToGameAsync(requireContext(), game, success -> {
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                Toast.makeText(requireContext(), success ? "封面已同步" : "无可用封面", Toast.LENGTH_SHORT).show();
                if (success) loadGames();
            });
        });
    }

    private String playStatusText(String status) {
        if (status == null) return "未玩";
        switch (status) {
            case "playing": return "在玩";
            case "completed": return "玩过";
            default: return "未玩";
        }
    }

    private String engineText(EngineType engine) {
        if (engine == null) return "未知";
        switch (engine) {
            case KIRIKIRI: return "Kirikiri";
            case ONS: return "ONS";
            case TYRANO: return "Tyrano";
            case ARTEMIS: return "Artemis";
            case WINLATOR: return "Winlator";
            case GAMEHUB: return "GameHub";
            case PSP: return "PSP";
            default: return "未知";
        }
    }

    private void rebuildCategories() {
    categories.clear();
    gameDevelopers.clear();

    int recentCount = 0;
    int playingCount = 0;
    int completedCount = 0;
    int unplayedCount = 0;

    Map<String, Integer> developerCounts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    Context appContext = requireContext().getApplicationContext();

    for (Game game : allGames) {
        if (game == null) continue;

        if (game.lastPlayedAt > 0L) {
            recentCount++;
        }

        String status = normalizePlayStatus(game.playStatus);
        if ("playing".equals(status)) {
            playingCount++;
        } else if ("completed".equals(status)) {
            completedCount++;
        } else {
            unplayedCount++;
        }

        List<String> developers = parseDevelopers(LauncherMetadataBridge.getDeveloperOf(appContext, game.id));
        gameDevelopers.put(game.id, developers);

        for (String developer : developers) {
            developerCounts.put(
                    developer,
                    developerCounts.containsKey(developer) ? developerCounts.get(developer) + 1 : 1
            );
        }
    }

    // 只添加有数据的固定分类
    if (recentCount > 0) {
        categories.add(new CategoryOption("最近游玩", CATEGORY_RECENT));
    }
    if (playingCount > 0) {
        categories.add(new CategoryOption("在玩", CATEGORY_PLAYING));
    }
    if (completedCount > 0) {
        categories.add(new CategoryOption("玩过", CATEGORY_COMPLETED));
    }
    if (unplayedCount > 0) {
        categories.add(new CategoryOption("未玩", CATEGORY_UNPLAYED));
    }

    // 开发商分类本来就是统计出来的，这里也加一层保护
    for (Map.Entry<String, Integer> entry : developerCounts.entrySet()) {
        if (entry.getValue() > 0) {
            categories.add(new CategoryOption(
                    "开发商 · " + entry.getKey() + " (" + entry.getValue() + ")",
                    CATEGORY_DEVELOPER_PREFIX + entry.getKey()
            ));
        }
    }

    if (selectedCategory != null && !selectedCategory.isEmpty() && !containsCategoryValue(selectedCategory)) {
        selectedCategory = "";
    }

    renderCategories();
}

    private static final class CategoryBuildResult {
    final List<CategoryOption> categories;
    final Map<Long, List<String>> developers;

    CategoryBuildResult(List<CategoryOption> categories, Map<Long, List<String>> developers) {
        this.categories = categories;
        this.developers = developers;
    }
}

    private CategoryBuildResult buildCategoriesInBackground(Context appContext, List<Game> games) {
    List<CategoryOption> cats = new ArrayList<>();
    Map<Long, List<String>> devs = new HashMap<>();

    int recentCount = 0;
    int playingCount = 0;
    int completedCount = 0;
    int unplayedCount = 0;

    Map<String, Integer> developerCounts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    if (games != null) {
        for (Game game : games) {
            if (game == null) continue;

            if (game.lastPlayedAt > 0L) {
                recentCount++;
            }

            String status = normalizePlayStatus(game.playStatus);
            if ("playing".equals(status)) {
                playingCount++;
            } else if ("completed".equals(status)) {
                completedCount++;
            } else {
                unplayedCount++;
            }

            List<String> developers = parseDevelopers(LauncherMetadataBridge.getDeveloperOf(appContext, game.id));
            devs.put(game.id, developers);

            for (String developer : developers) {
                developerCounts.put(
                        developer,
                        developerCounts.containsKey(developer) ? developerCounts.get(developer) + 1 : 1
                );
            }
        }
    }

    // 只添加有数据的固定分类
    if (recentCount > 0) {
        cats.add(new CategoryOption("最近游玩", CATEGORY_RECENT));
    }
    if (playingCount > 0) {
        cats.add(new CategoryOption("在玩", CATEGORY_PLAYING));
    }
    if (completedCount > 0) {
        cats.add(new CategoryOption("玩过", CATEGORY_COMPLETED));
    }
    if (unplayedCount > 0) {
        cats.add(new CategoryOption("未玩", CATEGORY_UNPLAYED));
    }

    for (Map.Entry<String, Integer> entry : developerCounts.entrySet()) {
        if (entry.getValue() > 0) {
            cats.add(new CategoryOption(
                    "开发商 · " + entry.getKey() + " (" + entry.getValue() + ")",
                    CATEGORY_DEVELOPER_PREFIX + entry.getKey()
            ));
        }
    }

    return new CategoryBuildResult(cats, devs);
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
        applyToolbarChipState(binding.librarySearchButton, binding.librarySearchInput.getVisibility() == View.VISIBLE);
        applyToolbarChipState(binding.libraryCollapseButton, categoriesCollapsed);
    }

    private void applyToolbarChipState(TextView view, boolean selected) {
        view.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        if (selected) {
            view.setTextColor(LauncherTheme.onPrimary(requireContext()));
            view.setBackground(LauncherTheme.selectedChip(requireContext()));
        } else {
            LauncherTheme.menuItem(view);
        }
    }

    private void sortGamesByTitle(List<Game> games) {
        if (games == null || games.size() <= 1) return;
        Collator collator = Collator.getInstance(Locale.CHINA);
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(games, (left, right) -> {
            int result = collator.compare(safeTitle(left), safeTitle(right));
            if (result != 0) return result;
            long leftId = left == null ? 0L : left.id;
            long rightId = right == null ? 0L : right.id;
            return Long.compare(leftId, rightId);
        });
    }

    private boolean matchesCategory(Game game, String category) {
        if (game == null || category == null || category.isEmpty()) return true;
        if (CATEGORY_RECENT.equals(category)) return game.lastPlayedAt > 0L;
        if (CATEGORY_PLAYING.equals(category)) return "playing".equals(normalizePlayStatus(game.playStatus));
        if (CATEGORY_COMPLETED.equals(category)) return "completed".equals(normalizePlayStatus(game.playStatus));
        if (CATEGORY_UNPLAYED.equals(category)) return "unplayed".equals(normalizePlayStatus(game.playStatus));
        if (category.startsWith(CATEGORY_DEVELOPER_PREFIX)) {
            String selectedDeveloper = category.substring(CATEGORY_DEVELOPER_PREFIX.length()).toLowerCase(Locale.ROOT);
            List<String> developers = gameDevelopers.get(game.id);
            if (developers == null) developers = Collections.emptyList();
            for (String developer : developers) {
                if (developer.toLowerCase(Locale.ROOT).contains(selectedDeveloper)) return true;
            }
        }
        return false;
    }

    private boolean containsCategoryValue(String value) {
        for (CategoryOption category : categories) {
            if (category.value.equals(value)) return true;
        }
        return false;
    }

    private String normalizePlayStatus(String status) {
        String value = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if ("completed".equals(value) || "playing".equals(value)) return value;
        return "unplayed";
    }

    private List<String> parseDevelopers(String developersText) {
        List<String> result = new ArrayList<>();
        if (developersText == null || developersText.trim().isEmpty() || "-".equals(developersText.trim())) return result;
        String[] parts = developersText.split("/|、|,|，");
        for (String raw : parts) {
            String developer = raw == null ? "" : raw.trim();
            if (!developer.isEmpty() && !result.contains(developer)) result.add(developer);
        }
        return result;
    }

    private static final class CategoryOption {
        final String label;
        final String value;

        CategoryOption(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
