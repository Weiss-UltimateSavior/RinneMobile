package com.apps;

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

import com.yuki.yukihub.MainActivity;
import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.data.MetadataRepository;
import com.yuki.yukihub.databinding.FragmentLauncherLibraryBinding;
import com.yuki.yukihub.launcherbridge.LauncherGameLaunchBridge;
import com.yuki.yukihub.metadata.VnMetadata;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class LauncherLibraryFragment extends Fragment {
    private static final int GRID_COLUMNS = 2;
    private static final int PAGE_SIZE = 8;
    private static final long MIN_PLAY_SESSION_MS = 0L;
    private static final long MAX_PLAY_SESSION_MS = 12L * 60L * 60L * 1000L;
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
    private final List<CategoryOption> categories = new ArrayList<>();
    private final Map<Long, List<String>> gameDevelopers = new HashMap<>();
    private LauncherGameAdapter adapter;
    private String selectedCategory = "";
    private String searchQuery = "";
    private boolean loading;
    private boolean fullyLoaded;
    private boolean categoriesCollapsed;
    private boolean dataLoaded;
    private boolean needsRefresh;
    private long runningSessionId = -1L;
    private Runnable searchDebounce;
    private GestureDetector swipeGestureDetector;
    private boolean swipeConsumed;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLauncherLibraryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        applySystemBarInsets();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        setupSearchAndCategories();
        setupRecycler();
        loadGames();
        setupSwipeGesture();
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

        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), GRID_COLUMNS);
        binding.libraryRecycler.setLayoutManager(layoutManager);
        binding.libraryRecycler.setAdapter(adapter);
        binding.libraryRecycler.setHasFixedSize(true);
        binding.libraryRecycler.setItemViewCacheSize(20);
        RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();
        pool.setMaxRecycledViews(0, 30);
        binding.libraryRecycler.setRecycledViewPool(pool);
        binding.libraryRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy <= 0 || loading || fullyLoaded) return;
                int lastVisible = layoutManager.findLastVisibleItemPosition();
                if (lastVisible >= Math.max(0, visibleGames.size() - GRID_COLUMNS)) {
                    loadNextPage();
                }
            }
        });
    }

    private void setupSwipeGesture() {
        swipeGestureDetector = new GestureDetector(requireContext(), new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 80;
            private static final int SWIPE_VELOCITY = 200;

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float diffX = e2.getX() - e1.getX();
                float diffY = e2.getY() - e1.getY();
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY) {
                    swipeConsumed = true;
                    if (diffX < 0) {
                        switchToNextCategory();
                    } else {
                        switchToPreviousCategory();
                    }
                    return true;
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

    private void switchToNextCategory() {
        List<CategoryOption> flat = getFlatCategories();
        int idx = getCurrentCategoryIndex();
        if (idx < flat.size() - 1) {
            selectedCategory = flat.get(idx + 1).value;
            renderCategories();
            applyFilters();
            animateCategorySwitch();
        }
    }

    private void switchToPreviousCategory() {
        List<CategoryOption> flat = getFlatCategories();
        int idx = getCurrentCategoryIndex();
        if (idx > 0) {
            selectedCategory = flat.get(idx - 1).value;
            renderCategories();
            applyFilters();
            animateCategorySwitch();
        }
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
                GameRepository repo = new GameRepository(appContext);
                games = repo.getAll();
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
                gameDevelopers.clear();
                gameDevelopers.putAll(loadedDevelopers);
                categories.clear();
                categories.addAll(loadedCategories);
                if (selectedCategory != null && !selectedCategory.isEmpty() && !containsCategoryValue(selectedCategory)) {
                    selectedCategory = "";
                }
                renderCategories();
                dataLoaded = true;
                applyFilters();
            });
        });
    }

    private void applyFilters() {
        filteredGames.clear();
        String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
        for (Game game : allGames) {
            if (game == null) continue;
            if (!query.isEmpty() && !safeTitle(game).toLowerCase(Locale.ROOT).contains(query)) continue;
            if (selectedCategory != null && !selectedCategory.isEmpty() && !matchesCategory(game, selectedCategory)) continue;
            filteredGames.add(game);
        }
        if (selectedCategory == null || selectedCategory.isEmpty()) {
            sortGamesByTitle(filteredGames);
        }
        visibleGames.clear();
        fullyLoaded = filteredGames.isEmpty();
        loadNextPage();
        renderState();
    }

    private void loadNextPage() {
        if (adapter == null || loading && !visibleGames.isEmpty()) return;
        loading = true;
        int start = visibleGames.size();
        int end = Math.min(start + PAGE_SIZE, filteredGames.size());
        if (start < end) {
            visibleGames.addAll(filteredGames.subList(start, end));
            adapter.submit(new ArrayList<>(visibleGames));
        }
        fullyLoaded = end >= filteredGames.size();
        loading = false;
        renderState();
    }

    private void renderState() {
        if (binding == null) return;
        boolean hasGames = !visibleGames.isEmpty();
        binding.libraryRecycler.setVisibility(hasGames ? View.VISIBLE : View.GONE);
        binding.libraryEmpty.setText(allGames.isEmpty() ? "还没有游戏" : "没有匹配的游戏");
        binding.libraryEmpty.setVisibility(hasGames ? View.GONE : View.VISIBLE);
        if (allGames.isEmpty() || filteredGames.isEmpty()) {
            binding.libraryFooter.setVisibility(View.GONE);
        } else {
            binding.libraryFooter.setVisibility(fullyLoaded ? View.GONE : View.VISIBLE);
            binding.libraryFooter.setText("上拉加载");
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
        } else if (result.message != null && !result.message.trim().isEmpty()) {
            Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show();
        }
    }

    private void finishDirectPlaySessionIfNeeded() {
        if (runningSessionId <= 0L) return;
        Context context = getContext();
        if (context == null) return;
        LauncherGameLaunchBridge.finishSession(context, runningSessionId, MIN_PLAY_SESSION_MS, MAX_PLAY_SESSION_MS);
        runningSessionId = -1L;
        loadGames();
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
        addGameActionOption(root, "编辑", dialog, game, () -> startEditGameActivity(game));
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
                GameRepository repo = new GameRepository(requireContext());
                Game latest = repo.findById(game.id);
                if (latest != null) {
                    latest.playStatus = status;
                    repo.update(latest);
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
                GameRepository repo = new GameRepository(requireContext());
                Game latest = repo.findById(game.id);
                if (latest == null) return;
                long finalDuration = latest.totalPlayTime;
                if (totalMinutes != null) finalDuration = totalMinutes * 60_000L;
                if (addMinutes != null) finalDuration += addMinutes * 60_000L;
                repo.setManualPlayTimeForGame(latest.id, Math.max(0, finalDuration));
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
                GameRepository repo = new GameRepository(requireContext());
                Game latest = repo.findById(game.id);
                if (latest != null) {
                    latest.favorite = !latest.favorite;
                    repo.update(latest);
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
                GameRepository repo = new GameRepository(requireContext());
                repo.delete(game.id);
            } catch (Throwable ignored) {}
            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                loadGames();
                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show();
            });
        });
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

    private void startEditGameActivity(Game game) {
        android.content.Intent intent = new android.content.Intent(requireContext(), LauncherGameEditActivity.class);
        intent.putExtra(LauncherGameEditActivity.EXTRA_GAME_ID, game.id);
        startActivity(intent);
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
        categories.add(new CategoryOption("最近游玩", CATEGORY_RECENT));
        categories.add(new CategoryOption("在玩", CATEGORY_PLAYING));
        categories.add(new CategoryOption("玩过", CATEGORY_COMPLETED));
        categories.add(new CategoryOption("未玩", CATEGORY_UNPLAYED));

        Map<String, Integer> developerCounts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        MetadataRepository metadataRepository = new MetadataRepository(requireContext().getApplicationContext());
        for (Game game : allGames) {
            List<String> developers = parseDevelopers(developerOf(metadataRepository, game));
            if (game != null) gameDevelopers.put(game.id, developers);
            for (String developer : developers) {
                developerCounts.put(developer, developerCounts.containsKey(developer) ? developerCounts.get(developer) + 1 : 1);
            }
        }
        for (Map.Entry<String, Integer> entry : developerCounts.entrySet()) {
            categories.add(new CategoryOption("开发商 · " + entry.getKey() + " (" + entry.getValue() + ")", CATEGORY_DEVELOPER_PREFIX + entry.getKey()));
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
        cats.add(new CategoryOption("最近游玩", CATEGORY_RECENT));
        cats.add(new CategoryOption("在玩", CATEGORY_PLAYING));
        cats.add(new CategoryOption("玩过", CATEGORY_COMPLETED));
        cats.add(new CategoryOption("未玩", CATEGORY_UNPLAYED));

        Map<String, Integer> developerCounts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        MetadataRepository metadataRepository = new MetadataRepository(appContext);
        for (Game game : games) {
            List<String> developers = parseDevelopers(developerOf(metadataRepository, game));
            if (game != null) devs.put(game.id, developers);
            for (String developer : developers) {
                developerCounts.put(developer, developerCounts.containsKey(developer) ? developerCounts.get(developer) + 1 : 1);
            }
        }
        for (Map.Entry<String, Integer> entry : developerCounts.entrySet()) {
            cats.add(new CategoryOption("开发商 · " + entry.getKey() + " (" + entry.getValue() + ")", CATEGORY_DEVELOPER_PREFIX + entry.getKey()));
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

    private String developerOf(MetadataRepository metadataRepository, Game game) {
        if (metadataRepository == null || game == null || game.id <= 0) return "";
        VnMetadata meta = metadataRepository.getVndb(game.id);
        if (meta == null) meta = metadataRepository.getBangumi(game.id);
        if (meta == null) meta = metadataRepository.getYmgal(game.id);
        return meta == null || meta.developer == null ? "" : meta.developer.trim();
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
