package com.apps;

import android.content.Intent;
import android.content.Context;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
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
import com.yuki.yukihub.metadata.VnMetadata;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;

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
        setupSearchAndCategories();
        setupRecycler();
        loadGames();
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
                if (game != null) {
                    adapter.setSelectedGameId(game.id);
                    confirmLaunchGame(game);
                }
            }

            @Override
            public void onGameLongClick(Game game) {
                if (game != null) Toast.makeText(requireContext(), game.title, Toast.LENGTH_SHORT).show();
            }
        });

        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), GRID_COLUMNS);
        binding.libraryRecycler.setLayoutManager(layoutManager);
        binding.libraryRecycler.setAdapter(adapter);
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
        });
        binding.librarySearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s == null ? "" : s.toString().trim();
                applyFilters();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private void loadGames() {
        setLoading(true);
        Context appContext = requireContext().getApplicationContext();
        AppExecutors.runOnSingle(() -> {
            List<Game> games;
            try {
                games = new GameRepository(appContext).getAll();
            } catch (Throwable throwable) {
                games = Collections.emptyList();
            }
            List<Game> loadedGames = games;
            mainHandler.post(() -> {
                if (binding == null) return;
                allGames.clear();
                allGames.addAll(loadedGames);
                rebuildCategories();
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
        visibleGames.clear();
        fullyLoaded = filteredGames.isEmpty();
        if (adapter != null) adapter.submit(Collections.emptyList());
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
            binding.libraryFooter.setText("继续上拉加载更多");
        }
    }

    private void setLoading(boolean value) {
        loading = value;
        if (binding != null) {
            binding.libraryFooter.setVisibility(value ? View.VISIBLE : View.GONE);
            binding.libraryFooter.setText("正在加载...");
        }
    }

    private void launchGameInMainActivity(Game game) {
        Intent intent = new Intent(requireContext(), MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_LAUNCH_ACTION, MainActivity.ACTION_LAUNCH_GAME);
        intent.putExtra(MainActivity.EXTRA_LAUNCH_GAME_ID, game.id);
        startActivity(intent);
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
                    (int) (280 * getResources().getDisplayMetrics().density),
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

            btnCancel.setOnClickListener(v -> dialog.dismiss());
            btnConfirm.setOnClickListener(v -> {
                dialog.dismiss();
                launchGameInMainActivity(game);
            });
        }
    }

    private String safeTitle(Game game) {
        if (game == null || game.title == null || game.title.trim().isEmpty()) return "未命名游戏";
        return game.title.trim();
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
        chip.setTextSize(13);
        chip.setTextColor(ContextCompat.getColor(
                requireContext(),
                selected
                        ? com.yuki.yukihub.R.color.launcher_on_primary_color
                        : com.yuki.yukihub.R.color.launcher_primary_color
        ));
        chip.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        chip.setBackgroundResource(selected ? com.yuki.yukihub.R.drawable.launcher_filter_chip_selected : com.yuki.yukihub.R.drawable.launcher_filter_chip_unselected);
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setOnClickListener(view -> {
            selectedCategory = value;
            renderCategories();
            applyFilters();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
        lp.setMargins(0, 0, dp(8), 0);
        binding.libraryCategoryRow.addView(chip, lp);
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
