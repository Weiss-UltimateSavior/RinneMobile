package com.apps.PadUi;

import android.content.Context;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.apps.LauncherActivity;
import com.apps.LauncherPreferences;
import com.apps.game.GameActionMenuFactory;
import com.apps.game.GameMetadataFormatter;
import com.apps.game.GamePasswordLock;
import com.apps.game.GameSessionController;
import com.apps.game.PinnedGameShortcut;
import com.apps.settings.LauncherCustomVndbSearchDialog;
import com.apps.settings.LauncherKrkrSettingsActivity;
import com.apps.theme.LauncherTheme;
import com.apps.util.LauncherAvatarPersistence;
import com.core.R;
import com.core.databinding.FragmentPadGameBinding;
import com.core.launcherbridge.LauncherAuthBridge;
import com.core.launcherbridge.LauncherMetadataBridge;
import com.core.launcherbridge.LauncherRepositoryBridge;
import com.core.model.EngineType;
import com.core.model.Game;
import com.core.util.AppExecutors;
import com.core.util.DevLogger;
import com.core.util.SafeImageLoader;
import com.core.util.RxMainQueue;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 横屏游戏库：手机每页 1 行 × 5 列，平板每页 2 行 × 5 列，横向手势切换分页。 */
public class PadGameFragment extends Fragment implements GameActionMenuFactory.ActionMenuCallbacks {
    private static final String TAG = "PadGameFragment";
    private static final int GRID_COLUMNS = 5;
    private static final int PHONE_GRID_ROWS = 1;
    private static final int TABLET_GRID_ROWS = 2;
    private static final int TABLET_MIN_SMALLEST_WIDTH_DP = 600;

    private FragmentPadGameBinding binding;
    private PadGameCardAdapter adapter;
    private final List<Game> allGames = new ArrayList<>();
    private final List<Game> filteredGames = new ArrayList<>();
    private int currentPage;
    private boolean dataLoaded;
    private boolean loading;
    private boolean swipeConsumed;
    private boolean pageAnimating;
    private int gridRows = PHONE_GRID_ROWS;
    private int pageSize = GRID_COLUMNS * PHONE_GRID_ROWS;
    private GameSessionController sessionController;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentPadGameBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        binding.padSearchIcon.setColorFilter(LauncherActivity.launcherPrimaryColor(requireContext()));
        binding.padGameNextPage.setColorFilter(LauncherActivity.launcherPrimaryColor(requireContext()));
        binding.padGameSettingsButton.setColorFilter(LauncherActivity.launcherPrimaryColor(requireContext()));
        binding.padAvatarContainer.setClipToOutline(true);
        gridRows = isTabletLayout() ? TABLET_GRID_ROWS : PHONE_GRID_ROWS;
        pageSize = GRID_COLUMNS * gridRows;
        sessionController = new GameSessionController(requireContext(), new RxMainQueue(),
                new GameSessionController.Listener() {
                    @Override
                    public void reloadGame(long gameId) { reloadGameInPlace(gameId); }

                    @Override
                    public void reloadAllGames() { loadGames(); }
                });
        renderAvatar();
        renderAccountInfo();
        setupRecycler();
        setupSearch();
        setupSettingsButton();
        setupNextPageButton();
        setupPagingGesture();
        loadGames();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) {
            binding.padSearchIcon.setColorFilter(LauncherActivity.launcherPrimaryColor(requireContext()));
            binding.padGameNextPage.setColorFilter(LauncherActivity.launcherPrimaryColor(requireContext()));
            binding.padGameSettingsButton.setColorFilter(LauncherActivity.launcherPrimaryColor(requireContext()));
        }
        renderAvatar();
        renderAccountInfo();
        if (sessionController != null && sessionController.hasActiveSession()) {
            sessionController.finishDirectPlaySessionIfNeeded(this);
        } else if (!dataLoaded) {
            loadGames();
        }
    }

    @Override
    public void onDestroyView() {
        if (sessionController != null) sessionController.cleanup();
        if (binding != null) {
            binding.padGameRecycler.setAdapter(null);
            binding.getRoot().setOnTouchListener(null);
        }
        binding = null;
        adapter = null;
        loading = false;
        super.onDestroyView();
    }

    private void setupRecycler() {
        adapter = new PadGameCardAdapter();
        adapter.setOnGameCardListener(new PadGameCardAdapter.OnGameCardListener() {
            @Override
            public void onGameClick(Game game) {
                if (swipeConsumed) {
                    swipeConsumed = false;
                    return;
                }
                if (game != null) confirmLaunchGame(game);
            }

            @Override
            public void onGameLongClick(Game game) {
                if (swipeConsumed) {
                    swipeConsumed = false;
                    return;
                }
                if (game != null) {
                    showGameActionMenu(game);
                }
            }
        });
        binding.padGameRecycler.setLayoutManager(new GridLayoutManager(requireContext(), GRID_COLUMNS));
        binding.padGameRecycler.setAdapter(adapter);
        binding.padGameRecycler.setItemAnimator(null);
        binding.padGameRecycler.setHasFixedSize(true);
        binding.padGameRecycler.setItemViewCacheSize(pageSize);
        binding.padGameRecycler.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                            oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updateCardHeight();
            }
        });
        binding.padGameRecycler.post(() -> { if (!isAdded() || binding == null) return; updateCardHeight(); });
    }

    private void updateCardHeight() {
        if (binding == null || adapter == null) return;
        RecyclerView recyclerView = binding.padGameRecycler;
        int availableWidth = recyclerView.getWidth()
                - recyclerView.getPaddingLeft()
                - recyclerView.getPaddingRight();
        View parent = (View) recyclerView.getParent();
        int availableHeight = parent.getHeight()
                - parent.getPaddingTop()
                - parent.getPaddingBottom();
        if (availableWidth <= 0 || availableHeight <= 0) return;

        int cardWidth = Math.max(1, (availableWidth - LauncherTheme.dp(requireContext(), 10) * GRID_COLUMNS) / GRID_COLUMNS);
        int heightByRatio = Math.max(1, Math.round(cardWidth * 5f / 3f));
        int heightByRows = Math.max(1, (availableHeight - LauncherTheme.dp(requireContext(), 10) * gridRows) / gridRows);
        adapter.setFixedCardHeight(Math.min(heightByRatio, heightByRows));
    }

    private boolean isTabletLayout() {
        return getResources().getConfiguration().smallestScreenWidthDp
                >= TABLET_MIN_SMALLEST_WIDTH_DP;
    }

    private void setupSearch() {
        binding.padSearchIcon.setOnClickListener(view -> applySearch());
        binding.padSearchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                applySearch();
                return true;
            }
            return false;
        });
    }

    private void setupNextPageButton() {
        binding.padGameNextPage.setOnClickListener(view -> {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            showNextPage();
        });
    }

    private void setupSettingsButton() {
        binding.padGameSettingsButton.setOnClickListener(view -> {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            startActivity(new android.content.Intent(requireContext(), PadSettingsActivity.class));
        });
    }

    private void applySearch() {
        binding.padGameRecycler.animate().cancel();
        binding.padGameRecycler.setTranslationX(0f);
        pageAnimating = false;
        String query = binding.padSearchInput.getText() == null
                ? ""
                : binding.padSearchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        filteredGames.clear();
        for (Game game : allGames) {
            if (game == null) continue;
            if (query.isEmpty() || containsQuery(game, query)) filteredGames.add(game);
        }
        currentPage = 0;
        renderPage();

        InputMethodManager inputMethodManager = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.hideSoftInputFromWindow(binding.padSearchInput.getWindowToken(), 0);
        }
        binding.padSearchInput.clearFocus();
    }

    private boolean containsQuery(Game game, String query) {
        return normalized(game.title).contains(query)
                || normalized(game.originalTitle).contains(query)
                || normalized(game.tags).contains(query);
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private void setupPagingGesture() {
        GestureDetector detector = new GestureDetector(requireContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent event) {
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent first, MotionEvent second,
                                           float velocityX, float velocityY) {
                        if (first == null || second == null) return false;
                        float deltaX = second.getX() - first.getX();
                        float deltaY = second.getY() - first.getY();
                        if (Math.abs(deltaX) <= Math.abs(deltaY)
                                || Math.abs(deltaX) < LauncherTheme.dp(requireContext(), 64)
                                || Math.abs(velocityX) < LauncherTheme.dp(requireContext(), 180)) {
                            return false;
                        }
                        swipeConsumed = true;
                        if (deltaX < 0) {
                            showNextPage();
                        } else {
                            showPreviousPage();
                        }
                        // 只屏蔽这次滑动末尾可能误触发的卡片点击。
                        binding.padGameRecycler.postDelayed(() -> swipeConsumed = false, 250L);
                        return true;
                    }
                });

        binding.padGameRecycler.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView recyclerView,
                                                 @NonNull MotionEvent event) {
                detector.onTouchEvent(event);
                return false;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView recyclerView, @NonNull MotionEvent event) {
                detector.onTouchEvent(event);
            }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) { }
        });
        binding.getRoot().setOnTouchListener((view, event) -> {
            detector.onTouchEvent(event);
            return true;
        });
    }

    private boolean showNextPage() {
        int totalPages = totalPages();
        if (pageAnimating || currentPage + 1 >= totalPages) return false;
        animateToPage(currentPage + 1, true);
        return true;
    }

    private boolean showPreviousPage() {
        if (pageAnimating || currentPage <= 0) return false;
        animateToPage(currentPage - 1, false);
        return true;
    }

    /** 当前页整体平移离场，下一页从相反方向完整平移进场。 */
    private void animateToPage(int targetPage, boolean forward) {
        if (binding == null || adapter == null) return;
        pageAnimating = true;
        float distance = Math.max(binding.padGameRecycler.getWidth(), binding.padGamePanel.getWidth());
        if (distance <= 0f) distance = getResources().getDisplayMetrics().widthPixels;
        float exitX = forward ? -distance : distance;
        float enterX = -exitX;
        binding.padGameRecycler.animate().cancel();
        binding.padGameRecycler.animate()
                .translationX(exitX)
                .setDuration(180L)
                .withEndAction(() -> {
                    if (binding == null || adapter == null) return;
                    currentPage = targetPage;
                    renderPage();
                    binding.padGameRecycler.setTranslationX(enterX);
                    binding.padGameRecycler.animate()
                            .translationX(0f)
                            .setDuration(220L)
                            .withEndAction(() -> pageAnimating = false)
                            .start();
                })
                .start();
    }

    private void renderPage() {
        if (binding == null || adapter == null) return;
        int totalPages = totalPages();
        currentPage = Math.max(0, Math.min(currentPage, totalPages - 1));
        int start = currentPage * pageSize;
        int end = Math.min(start + pageSize, filteredGames.size());
        List<Game> pageGames = start < end
                ? new ArrayList<>(filteredGames.subList(start, end))
                : Collections.emptyList();
        adapter.submit(pageGames);

        boolean hasGames = !pageGames.isEmpty();
        binding.padGameRecycler.setVisibility(hasGames ? View.VISIBLE : View.GONE);
        binding.padGameEmpty.setVisibility(hasGames ? View.GONE : View.VISIBLE);
        binding.padGameEmpty.setText(allGames.isEmpty()
                ? R.string.pad_no_games : R.string.pad_no_matching_games);
        binding.padGameNextPage.setVisibility(
                hasGames && currentPage + 1 < totalPages ? View.VISIBLE : View.GONE);
    }

    private int totalPages() {
        return Math.max(1, (filteredGames.size() + pageSize - 1) / pageSize);
    }

    private void loadGames() {
        if (binding == null || loading) return;
        loading = true;
        binding.padGameLoading.setVisibility(View.VISIBLE);
        binding.padGameRecycler.setVisibility(View.GONE);
        binding.padGameNextPage.setVisibility(View.GONE);
        binding.padGameEmpty.setVisibility(View.GONE);
        Context appContext = requireContext().getApplicationContext();
        AppExecutors.runOnSingle(() -> {
            List<Game> games;
            try {
                games = LauncherRepositoryBridge.getAllGames(appContext);
                Collator collator = Collator.getInstance(Locale.getDefault());
                games.sort((left, right) -> collator.compare(safeTitle(left), safeTitle(right)));
            } catch (Exception e) {
                Log.w(TAG, "DB query failed", e);
                games = Collections.emptyList();
            }
            List<Game> loadedGames = games;
            Activity activity = getActivity();
            if (activity == null) {
                loading = false;
                return;
            }
            activity.runOnUiThread(() -> {
                if (!isAdded() || binding == null) return;
                loading = false;
                allGames.clear();
                allGames.addAll(loadedGames);
                dataLoaded = true;
                binding.padGameLoading.setVisibility(View.GONE);
                applySearch();
            });
        });
    }

    /**
     * Updates a single game in-place across allGames/filteredGames without resetting currentPage.
     * Used by long-press dialog actions (status, play time, favorite, cover sync, metadata rematch).
     * renderPage() walks DiffUtil so only the changed card is rebound; horizontal page index is preserved.
     */
    private void updateGameInPlace(Game updated) {
        if (updated == null || binding == null) return;
        for (int i = 0; i < allGames.size(); i++) {
            Game g = allGames.get(i);
            if (g != null && g.id == updated.id) {
                allGames.set(i, updated);
                break;
            }
        }
        for (int i = 0; i < filteredGames.size(); i++) {
            Game g = filteredGames.get(i);
            if (g != null && g.id == updated.id) {
                filteredGames.set(i, updated);
                break;
            }
        }
        renderPage();
    }

    /** Removes a single game by id without resetting currentPage. */
    private void removeGameInPlace(long gameId) {
        if (binding == null) return;
        for (int i = 0; i < allGames.size(); i++) {
            Game g = allGames.get(i);
            if (g != null && g.id == gameId) {
                allGames.remove(i);
                break;
            }
        }
        for (int i = 0; i < filteredGames.size(); i++) {
            Game g = filteredGames.get(i);
            if (g != null && g.id == gameId) {
                filteredGames.remove(i);
                break;
            }
        }
        renderPage();
    }

    /** Re-fetches a single game from DB and updates it in-place, for async metadata operations. */
    private void reloadGameInPlace(long gameId) {
        Context appContext = requireContext().getApplicationContext();
        AppExecutors.io().execute(() -> {
            Game updated = null;
            try {
                updated = LauncherRepositoryBridge.findGameById(appContext, gameId);
            } catch (Exception e) { Log.w(TAG, "DB query failed", e); }
            final Game result = updated;
            Activity activity = getActivity();
            if (activity != null) activity.runOnUiThread(() -> {
                if (!isAdded() || binding == null) return;
                if (result != null) updateGameInPlace(result);
            });
        });
    }

    private void showGameActionMenu(Game game) {
        GameActionMenuFactory.ActionMenuConfig config = new GameActionMenuFactory.ActionMenuConfig();
        // Pad 游戏库不提供卡片编辑入口；其余长按操作保持与竖屏公共菜单一致。
        config.includeEditAction = false;
        // 垂直列表型动作菜单属 compact 类，宽度对齐 §6 标准（270dp），与 PadDialogFactory.showActionChoices 一致。
        config.dialogWidthDp = 270;
        GameActionMenuFactory.showGameActionMenu(this, game, config, this);
    }

    @Override
    public void onShowGameDetail(@NonNull Game game) {
        GameActionMenuFactory.showGameDetailDialog(this, game);
    }

    @Override
    public void onEditGame(@NonNull Game game) {
        // Pad 游戏库动作菜单不展示编辑项；该回调仅满足公共接口契约。
    }

    @Override
    public void onShowPlayStatus(@NonNull Game game) {
        GameActionMenuFactory.showPlayStatusDialog(
                this,
                game,
                (ctx, title, labels, checkedIndex, onChoice) ->
                        PadDialogFactory.showSingleChoice(ctx, title, labels, checkedIndex, onChoice::accept),
                this::updateGameInPlace);
    }

    @Override
    public void onEditPlayTime(@NonNull Game game) {
        GameActionMenuFactory.showEditPlayTimeDialog(this, game, this::updateGameInPlace);
    }

    @Override
    public void onToggleFavorite(@NonNull Game game) {
        toggleFavorite(game);
    }

    @Override
    public void onTogglePassword(@NonNull Game game) {
        if (GamePasswordLock.hasPassword(game)) {
            GamePasswordLock.clearPassword(this, game, null);
        } else {
            GamePasswordLock.setPassword(this, game, null);
        }
    }

    @Override
    public void onShowMoreOptions(@NonNull Game game) {
        showMoreOptionsDialog(game);
    }

    private void showMoreOptionsDialog(Game game) {
        List<String> ids = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        addMoreOption(ids, labels, "edit_play_time", getString(R.string.game_action_edit_duration));
        addMoreOption(ids, labels, "pin_shortcut", getString(R.string.game_action_pin_shortcut));
        addMoreOption(ids, labels, "rematch", getString(R.string.game_action_rematch_vndb));
        addMoreOption(ids, labels, "custom_vndb", getString(R.string.game_action_custom_vndb));
        addMoreOption(ids, labels, "sync", getString(R.string.game_action_sync_cover));
        if (game.engine == EngineType.ONS) {
            addMoreOption(ids, labels, "ons_settings", getString(R.string.game_action_ons_settings));
        }
        addMoreOption(ids, labels, "delete", getString(R.string.game_action_delete));
        int deleteIndex = ids.indexOf("delete");
        PadDialogFactory.showActionChoices(
                requireContext(),
                getString(R.string.game_action_more),
                labels.toArray(new CharSequence[0]),
                deleteIndex,
                index -> {
                    String id = ids.get(index);
                    switch (id) {
                        case "edit_play_time":
                            GameActionMenuFactory.showEditPlayTimeDialog(this, game, this::updateGameInPlace);
                            break;
                        case "pin_shortcut":
                            PinnedGameShortcut.requestPinShortcut(requireContext(), game);
                            break;
                        case "rematch":
                            rematchMetadata(game);
                            break;
                        case "custom_vndb":
                            LauncherCustomVndbSearchDialog.show(this, game, () -> reloadGameInPlace(game.id));
                            break;
                        case "sync":
                            syncMetadataToCard(game);
                            break;
                        case "ons_settings":
                            openOnsGameSettings(game);
                            break;
                        case "delete":
                            confirmDeleteGame(game);
                            break;
                        default:
                            break;
                    }
                });
    }

    private void addMoreOption(List<String> ids, List<CharSequence> labels, String id, CharSequence label) {
        ids.add(id);
        labels.add(label);
    }

    private void toggleFavorite(Game game) {
        Context appContext = requireContext().getApplicationContext();
        AppExecutors.runOnSingle(() -> {
            Game updated = null;
            try {
                Game latest = LauncherRepositoryBridge.findGameById(appContext, game.id);
                if (latest != null) {
                    latest.favorite = !latest.favorite;
                    if (LauncherRepositoryBridge.updateGame(appContext, latest) > 0) {
                        updated = latest;
                    }
                }
            } catch (Exception error) {
                DevLogger.w(TAG, "Failed to toggle favorite", error);
            }
            Game result = updated;
            Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded() || binding == null) return;
                if (result != null) updateGameInPlace(result);
            });
        });
    }

    private void rematchMetadata(Game game) {
        Toast.makeText(requireContext(), R.string.game_vndb_searching, Toast.LENGTH_SHORT).show();
        LauncherMetadataBridge.fetchAndSaveMetadataAsync(requireContext(), game, success -> {
            Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded() || binding == null) return;
                Toast.makeText(requireContext(), success
                                ? R.string.game_metadata_updated : R.string.game_metadata_not_found,
                        Toast.LENGTH_SHORT).show();
                if (success) reloadGameInPlace(game.id);
            });
        });
    }

    private void syncMetadataToCard(Game game) {
        Toast.makeText(requireContext(), R.string.game_cover_syncing, Toast.LENGTH_SHORT).show();
        LauncherMetadataBridge.syncCoverToGameAsync(requireContext(), game, success -> {
            Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded() || binding == null) return;
                Toast.makeText(requireContext(), success
                                ? R.string.game_cover_synced : R.string.game_cover_unavailable,
                        Toast.LENGTH_SHORT).show();
                if (success) reloadGameInPlace(game.id);
            });
        });
    }

    private void openOnsGameSettings(Game game) {
        try {
            Intent intent = new Intent(requireContext(), LauncherKrkrSettingsActivity.class);
            intent.putExtra(LauncherKrkrSettingsActivity.EXTRA_GAME_ID, game.id);
            startActivity(intent);
        } catch (ActivityNotFoundException | IllegalArgumentException error) {
            DevLogger.w(TAG, "Failed to open ONS game settings", error);
            Toast.makeText(requireContext(), R.string.game_action_ons_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDeleteGame(Game game) {
        PadDialogFactory.showDangerConfirm(
                requireContext(),
                getString(R.string.game_action_delete),
                getString(R.string.game_delete_message, GameMetadataFormatter.safeTitle(requireContext(), game)),
                getString(R.string.game_common_remove),
                () -> deleteGame(game));
    }

    private void deleteGame(Game game) {
        Context appContext = requireContext().getApplicationContext();
        AppExecutors.runOnSingle(() -> {
            boolean deleted;
            try {
                deleted = LauncherRepositoryBridge.deleteGame(appContext, game.id) > 0;
            } catch (Exception error) {
                DevLogger.w(TAG, "Failed to delete game", error);
                deleted = false;
            }
            boolean deletedFinal = deleted;
            Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded() || binding == null) return;
                if (!deletedFinal) {
                    Toast.makeText(appContext, R.string.game_delete_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                removeGameInPlace(game.id);
                Toast.makeText(appContext, R.string.game_deleted, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void confirmLaunchGame(Game game) {
        PadDialogFactory.showConfirm(requireContext(), getString(R.string.pad_launch_game),
                getString(R.string.pad_launch_game_message, safeTitle(game)),
                getString(R.string.core_confirm), () -> {
            com.apps.game.GamePasswordLock.interceptLaunch(PadGameFragment.this, game, () -> {
                if (sessionController != null) sessionController.launchGameDirectly(PadGameFragment.this, game);
            });
        });
    }

    private String safeTitle(Game game) {
        if (game == null || game.title == null || game.title.trim().isEmpty()) {
            return getString(R.string.pad_untitled_game);
        }
        return game.title.trim();
    }

    private static final String KEY_PROFILE_AVATAR = LauncherAvatarPersistence.KEY_PROFILE_AVATAR;
    private static final String KEY_PROFILE_NAME = "profile_name";
    private static final String KEY_AUTH_STATUS = "auth_status";
    private static final String AUTH_STATUS_ONLINE = "online";
    private static final String AUTH_STATUS_SYNCING = "syncing";
    private static final String AUTH_STATUS_EXPIRED = "expired";

    private SharedPreferences appPrefs() {
        return requireContext().getApplicationContext()
                .getSharedPreferences(LauncherPreferences.APP_PREFS, Context.MODE_PRIVATE);
    }

    /** 渲染头像：优先主页头像，其次个人页头像，都没有则显示首字母。 */
    private void renderAvatar() {
        if (binding == null) return;
        String avatar = appPrefs().getString(KEY_PROFILE_AVATAR, "");
        if (avatar == null || avatar.trim().isEmpty()) {
            String profileAvatar = requireContext()
                    .getSharedPreferences(LauncherPreferences.PROFILE_PREFS, 0)
                    .getString(LauncherAvatarPersistence.KEY_CUSTOM_AVATAR, "");
            if (profileAvatar != null && !profileAvatar.trim().isEmpty()) {
                avatar = profileAvatar;
            }
        }
        String nickname = LauncherAuthBridge.isLoggedIn(requireContext())
                ? LauncherAuthBridge.getNickname(requireContext()) : "";
        String initial = (nickname != null && !nickname.trim().isEmpty())
                ? String.valueOf(nickname.trim().charAt(0)).toUpperCase() : "Y";
        binding.padAvatarInitial.setText(initial);

        if (avatar == null || avatar.trim().isEmpty()) {
            binding.padAvatarImage.setImageDrawable(null);
            binding.padAvatarImage.setVisibility(View.GONE);
            binding.padAvatarInitial.setVisibility(View.VISIBLE);
            return;
        }
        try {
            binding.padAvatarImage.setClipToOutline(true);
            // 缓存命中时回调会同步显示图片，因此回退态必须在发起请求前设置。
            binding.padAvatarImage.setVisibility(View.GONE);
            binding.padAvatarInitial.setVisibility(View.VISIBLE);
            if (!SafeImageLoader.loadUri(binding.padAvatarImage, avatar, success -> {
                if (binding == null) return;
                binding.padAvatarImage.setVisibility(success ? View.VISIBLE : View.GONE);
                binding.padAvatarInitial.setVisibility(success ? View.GONE : View.VISIBLE);
            })) {
                binding.padAvatarImage.setImageDrawable(null);
                binding.padAvatarImage.setVisibility(View.GONE);
                binding.padAvatarInitial.setVisibility(View.VISIBLE);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "avatar load failed: " + avatar, e);
            binding.padAvatarImage.setImageDrawable(null);
            binding.padAvatarImage.setVisibility(View.GONE);
            binding.padAvatarInitial.setVisibility(View.VISIBLE);
        }
    }

    /** 渲染用户名和在线/本地模式，与首页顶部一致。 */
    private void renderAccountInfo() {
        if (binding == null) return;
        binding.padAccountName.setText(displayName());
        binding.padAccountMode.setText(accountMode());
    }

    private String displayName() {
        if (LauncherAuthBridge.isLoggedIn(requireContext())) {
            String nickname = LauncherAuthBridge.getNickname(requireContext());
            if (nickname != null && !nickname.trim().isEmpty()) return nickname.trim();
        }
        String profileName = appPrefs().getString(KEY_PROFILE_NAME, "");
        if (profileName != null && !profileName.trim().isEmpty()) return profileName.trim();
        return getString(R.string.home_local_player);
    }

    private String accountMode() {
        if (!LauncherAuthBridge.isLoggedIn(requireContext())) return getString(R.string.home_local_mode);
        String status = appPrefs().getString(KEY_AUTH_STATUS, "");
        if (AUTH_STATUS_ONLINE.equals(status)) return getString(R.string.pad_online_mode);
        if (AUTH_STATUS_SYNCING.equals(status)) return getString(R.string.pad_online_syncing);
        if (AUTH_STATUS_EXPIRED.equals(status)) return getString(R.string.pad_online_expired);
        return getString(R.string.pad_online_mode);
    }
}
