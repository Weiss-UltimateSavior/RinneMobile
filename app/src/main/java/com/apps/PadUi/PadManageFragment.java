package com.apps.PadUi;

import android.content.Context;
import android.content.res.ColorStateList;
import android.app.Dialog;
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

import com.yuki.yukihub.databinding.FragmentLauncherLibraryBinding;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;
import com.yuki.yukihub.launcherbridge.LauncherGameLaunchBridge;
import com.yuki.yukihub.launcherbridge.LauncherMetadataBridge;
import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.metadata.VnMetadata;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;
import com.yuki.yukihub.util.RxMainQueue;

import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.settings.LauncherCustomVndbSearchDialog;
import com.apps.game.GameLibraryState;
import com.apps.UserData.LauncherUserData;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import com.apps.game.LauncherLibraryFragment;

/**
 * 横屏手机游戏仓库自包含实现：直接继承 {@link Fragment}，使用 {@link PadManageGameAdapter}。
 * 卡片高度根据实际列宽自适应，列表纵向连续加载，不再锁定固定行数分页。
 * 不再继承 LauncherLibraryFragment，所有逻辑独立维护。
 */
public class PadManageFragment extends Fragment {
    private static final int GRID_COLUMNS = 6;
    private static final int TABLET_MIN_SMALLEST_WIDTH_DP = 600;
    private static final long MIN_PLAY_SESSION_MS = 0L;
    private static final long MAX_PLAY_SESSION_MS = 12L * 60L * 60L * 1000L;
    private static final long PLAY_SESSION_HEARTBEAT_MS = 60L * 1000L;
    private static final String CATEGORY_RECENT = "status:recent";
    private static final String CATEGORY_PLAYING = "status:playing";
    private static final String CATEGORY_COMPLETED = "status:completed";
    private static final String CATEGORY_UNPLAYED = "status:unplayed";
    private static final String CATEGORY_FAVORITE = "status:favorite";
    private static final String CATEGORY_DEVELOPER_PREFIX = "developer:";

    private FragmentLauncherLibraryBinding binding;
    private final RxMainQueue mainQueue = new RxMainQueue();
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
    private boolean categoriesCollapsed = true;
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
            mainQueue.postDelayed(this, PLAY_SESSION_HEARTBEAT_MS);
        }
    };
    private boolean viewportFillCheckPending;
    private int pageSize = GRID_COLUMNS;
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
        pageSize = GRID_COLUMNS * (isTabletLayout() ? 2 : 1);
        setupSearchAndCategories();
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
        mainQueue.removeCallbacks(playSessionHeartbeat);
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
                if (dy <= 0 || loading || fullyLoaded) return;
                if (layoutManager.findLastVisibleItemPosition() >= Math.max(0, visibleGames.size() - GRID_COLUMNS)) {
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
                if (searchDebounce != null) mainQueue.removeCallbacks(searchDebounce);
                searchDebounce = () -> applyFilters();
                mainQueue.postDelayed(searchDebounce, 300);
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
            mainQueue.post(() -> {
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
                // 数据已经落到内存，解除加载锁，允许自动补页或滚动加载继续执行。
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
    }, (left, right) -> Collator.getInstance(Locale.CHINA).compare(safeTitle(left), safeTitle(right)), pageSize, false);
    syncLibraryLists();
    if (adapter != null) adapter.submit(new ArrayList<>(visibleGames), forceFullRefresh);
    renderState();
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
        fullyLoaded = libraryState.isFullyLoaded();
    }

    private void renderState() {
        if (binding == null) return;
        boolean hasGames = !visibleGames.isEmpty();
        binding.libraryRecycler.setVisibility(hasGames ? View.VISIBLE : View.GONE);
        if (hasGames) {
            binding.libraryRecycler.post(this::updateAdaptiveCardHeight);
            scheduleLoadUntilViewportFilled();
        }
        binding.libraryEmpty.setText(allGames.isEmpty() ? "还没有游戏" : "没有匹配的游戏");
        binding.libraryEmpty.setVisibility(hasGames ? View.GONE : View.VISIBLE);
    }

    private void scheduleLoadUntilViewportFilled() {
        if (binding == null || viewportFillCheckPending || loading || fullyLoaded
                || visibleGames.size() >= filteredGames.size()) {
            return;
        }
        viewportFillCheckPending = true;
        RecyclerView recyclerView = binding.libraryRecycler;
        recyclerView.post(() -> {
            viewportFillCheckPending = false;
            if (binding == null || loading || fullyLoaded || visibleGames.size() >= filteredGames.size()) {
                return;
            }
            if (!recyclerView.canScrollVertically(1)) loadNextPage();
        });
    }

    private void setLoading(boolean value) {
        loading = value;
    }

    private void launchGameDirectly(Game game) {
        if (game == null) return;
        LauncherGameLaunchBridge.launchAsync(requireContext(), game, result -> {
            if (!isAdded()) return;
            if (result.success) {
                runningSessionId = result.sessionId;
                runningGameId = game.id;
                runningGameTitle = safeTitle(game);
                runningSessionStart = System.currentTimeMillis();
                runningLaunchType = resolveLaunchTypeForRecord(game);
                startServerPlaySession(game, result.sessionId);
            } else if (result.message != null && !result.message.trim().isEmpty()) {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show();
            }
        });
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
        mainQueue.removeCallbacks(playSessionHeartbeat);
        mainQueue.postDelayed(playSessionHeartbeat, PLAY_SESSION_HEARTBEAT_MS);
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
        mainQueue.removeCallbacks(playSessionHeartbeat);
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
        if (pkg.startsWith("internal.citra") || pkg.equals("io.github.azaharplus.android")) return "internal.citra";
        return "external";
    }

    private void confirmLaunchGame(Game game) {
        if (game == null) return;
        PadDialogFactory.showConfirm(requireContext(), "启动游戏",
                "确定启动「" + safeTitle(game) + "」吗？", "确定",
                () -> launchGameDirectly(game));
    }

    private void showGameActionMenu(Game game) {
        if (game == null) return;
        PadDialogFactory.showActionChoices(requireContext(), safeTitle(game),
                new String[]{"详情", "状态", "修改时长", "更多选项"}, -1, index -> {
                    switch (index) {
                        case 0: showGameDetailDialog(game); break;
                        case 1: showPlayStatusDialog(game); break;
                        case 2: showEditPlayTimeDialog(game); break;
                        case 3: showMoreOptionsDialog(game); break;
                    }
                });
    }

    private String safeTitle(Game game) {
        if (game == null || game.title == null || game.title.trim().isEmpty()) return "未命名游戏";
        return game.title.trim();
    }

    private AlertDialog createLauncherDialog() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
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
        root.setBackground(LauncherTheme.secondaryButton(requireContext(), 20f));
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
        String[] labels = {"未玩", "在玩", "玩过"};
        String[] values = {"unplayed", "playing", "completed"};
        int checkedIndex = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(game.playStatus)) {
                checkedIndex = i;
                break;
            }
        }
        PadDialogFactory.showSingleChoice(requireContext(), "设置游玩状态", labels, checkedIndex,
                index -> updateGameStatus(game, values[index]));
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

        android.widget.EditText totalInput = new com.apps.widget.LauncherEditText(requireContext());
        totalInput.setHint("例如 3h 20m / 200m / 7200s / 2.5h");
        totalInput.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        totalInput.setHintTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_input_hint_color));
        totalInput.setTextSize(13);
        totalInput.setPadding(dp(13), dp(9), dp(13), dp(9));
        totalInput.setBackground(LauncherTheme.cancelChip(requireContext()));
        LauncherTheme.styleTextInput(totalInput);
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

        android.widget.EditText addInput = new com.apps.widget.LauncherEditText(requireContext());
        addInput.setHint("例如 30m / 1h30m / 0.5h");
        addInput.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        addInput.setHintTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_input_hint_color));
        addInput.setTextSize(13);
        addInput.setPadding(dp(13), dp(9), dp(13), dp(9));
        addInput.setBackground(LauncherTheme.cancelChip(requireContext()));
        LauncherTheme.styleTextInput(addInput);
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
        String favoriteLabel = game.favorite ? "取消收藏" : "添加收藏";
        PadDialogFactory.showActionChoices(requireContext(), "更多选项", new String[]{
                favoriteLabel, "重新匹配 VNDB 元数据", "自定义搜索 VNDB", "同步元数据封面到卡片", "删除游戏"
        }, 4, index -> {
            switch (index) {
                case 0: toggleFavorite(game); break;
                case 1: rematchMetadata(game); break;
                case 2: LauncherCustomVndbSearchDialog.show(this, game, this::loadGames); break;
                case 3: syncMetadataToCard(game); break;
                case 4: confirmDeleteGame(game); break;
            }
        });
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
        PadDialogFactory.showDangerConfirm(requireContext(), "删除游戏",
                "要删除「" + safeTitle(game) + "」吗？此操作仅移除游戏库不进行实际删除。",
                "移除", () -> deleteGame(game));
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
        PadDialogFactory.showStandardConfirm(requireContext(), "同步数据",
                "全部同步需要一定时间，是否一键同步刷新所有游戏的元数据与封面？",
                "确定同步", this::performBatchSync);
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
                mainQueue.post(() -> {
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

mainQueue.post(() -> {
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
        root.setBackground(LauncherTheme.secondaryButton(requireContext(), 20f));

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
        PadDialogFactory.showInfo(requireContext(), "同步完成", message);
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
            case NINTENDO_3DS: return "3DS";
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
    int favoriteCount = 0;

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
        if (game.favorite) {
            favoriteCount++;
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
    if (favoriteCount > 0) {
        categories.add(new CategoryOption("收藏", CATEGORY_FAVORITE));
    }
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
    int favoriteCount = 0;

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
            if (game.favorite) {
                favoriteCount++;
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
    if (favoriteCount > 0) {
        cats.add(new CategoryOption("收藏", CATEGORY_FAVORITE));
    }
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
        applyToolbarIconTone(binding.librarySyncButton);
        applyToolbarIconTone(binding.librarySearchButton);
        applyToolbarIconTone(binding.libraryCollapseButton);
    }

    private void applyToolbarIconTone(ImageView view) {
        view.setImageTintList(ColorStateList.valueOf(LauncherTheme.primary(requireContext())));
        view.setBackground(null);
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
        if (CATEGORY_FAVORITE.equals(category)) return game.favorite;
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
