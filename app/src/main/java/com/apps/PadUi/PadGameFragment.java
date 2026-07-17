package com.apps.PadUi;

import android.content.Context;
import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
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
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.apps.LauncherActivity;
import com.apps.game.LauncherGameActionController;
import com.apps.game.LauncherGameEditActivity;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.FragmentPadGameBinding;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;
import com.yuki.yukihub.launcherbridge.LauncherGameLaunchBridge;
import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;
import com.yuki.yukihub.util.SafeImageLoader;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 横屏游戏库：手机每页 1 行 × 5 列，平板每页 2 行 × 5 列，横向手势切换分页。 */
public class PadGameFragment extends Fragment {
    private static final int GRID_COLUMNS = 5;
    private static final int PHONE_GRID_ROWS = 1;
    private static final int TABLET_GRID_ROWS = 2;
    private static final int TABLET_MIN_SMALLEST_WIDTH_DP = 600;
    private static final long MIN_PLAY_SESSION_MS = 0L;
    private static final long MAX_PLAY_SESSION_MS = 12L * 60L * 60L * 1000L;

    private FragmentPadGameBinding binding;
    private PadGameCardAdapter adapter;
    private final List<Game> allGames = new ArrayList<>();
    private final List<Game> filteredGames = new ArrayList<>();
    private int currentPage;
    private boolean dataLoaded;
    private boolean loading;
    private boolean needsRefresh;
    private boolean swipeConsumed;
    private boolean pageAnimating;
    private int gridRows = PHONE_GRID_ROWS;
    private int pageSize = GRID_COLUMNS * PHONE_GRID_ROWS;
    private long runningSessionId = -1L;

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
        if (runningSessionId > 0L) {
            LauncherGameLaunchBridge.finishSession(
                    requireContext(), runningSessionId, MIN_PLAY_SESSION_MS, MAX_PLAY_SESSION_MS);
            runningSessionId = -1L;
            loadGames();
        } else if (needsRefresh) {
            needsRefresh = false;
            loadGames();
        } else if (!dataLoaded) {
            loadGames();
        }
    }

    @Override
    public void onDestroyView() {
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
                    LauncherGameActionController.show(PadGameFragment.this, game,
                            new LauncherGameActionController.Host() {
                                @Override
                                public void refreshGames() {
                                    loadGames();
                                }

                                @Override
                                public void editGame(Game target) {
                                    needsRefresh = true;
                                    android.content.Intent intent = new android.content.Intent(
                                            requireContext(), LauncherGameEditActivity.class);
                                    intent.putExtra(LauncherGameEditActivity.EXTRA_GAME_ID, target.id);
                                    startActivity(intent);
                                }

                                @Override
                                public void updateGame(Game updated) {
                                    updateGameInPlace(updated);
                                }

                                @Override
                                public void removeGame(long gameId) {
                                    removeGameInPlace(gameId);
                                }

                                @Override
                                public void reloadGame(long gameId) {
                                    reloadGameInPlace(gameId);
                                }
                            });
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
        binding.padGameRecycler.post(this::updateCardHeight);
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

        int cardWidth = Math.max(1, (availableWidth - dp(10) * GRID_COLUMNS) / GRID_COLUMNS);
        int heightByRatio = Math.max(1, Math.round(cardWidth * 5f / 3f));
        int heightByRows = Math.max(1, (availableHeight - dp(10) * gridRows) / gridRows);
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
                                || Math.abs(deltaX) < dp(64)
                                || Math.abs(velocityX) < dp(180)) {
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
        binding.padGameEmpty.setText(allGames.isEmpty() ? "还没有游戏" : "没有匹配的游戏");
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
            } catch (Throwable throwable) {
                games = Collections.emptyList();
            }
            List<Game> loadedGames = games;
            Activity activity = getActivity();
            if (activity == null) {
                loading = false;
                return;
            }
            activity.runOnUiThread(() -> {
                loading = false;
                if (binding == null) return;
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
        AppExecutors.io().execute(() -> {
            Game updated = null;
            try {
                updated = LauncherRepositoryBridge.findGameById(requireContext(), gameId);
            } catch (Throwable ignored) {}
            final Game result = updated;
            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                if (result != null) updateGameInPlace(result);
            });
        });
    }

    private void confirmLaunchGame(Game game) {
        PadDialogFactory.showConfirm(requireContext(), "启动游戏",
                "确定启动「" + safeTitle(game) + "」吗？", "确定", () -> {
            LauncherGameLaunchBridge.launchAsync(requireContext(), game, result -> {
                if (!isAdded()) return;
                if (result.success) runningSessionId = result.sessionId;
                else Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show();
            });
        });
    }

    private String safeTitle(Game game) {
        if (game == null || game.title == null || game.title.trim().isEmpty()) return "未命名游戏";
        return game.title.trim();
    }

    private static final String APP_PREFS = "yukihub_prefs";
    private static final String KEY_PROFILE_AVATAR = "profile_avatar";
    private static final String KEY_PROFILE_NAME = "profile_name";
    private static final String KEY_AUTH_STATUS = "auth_status";
    private static final String AUTH_STATUS_ONLINE = "online";
    private static final String AUTH_STATUS_SYNCING = "syncing";
    private static final String AUTH_STATUS_EXPIRED = "expired";

    private SharedPreferences appPrefs() {
        return requireContext().getApplicationContext()
                .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
    }

    /** 渲染头像：优先主页头像，其次个人页头像，都没有则显示首字母。 */
    private void renderAvatar() {
        if (binding == null) return;
        String avatar = appPrefs().getString(KEY_PROFILE_AVATAR, "");
        if (avatar == null || avatar.trim().isEmpty()) {
            String profileAvatar = requireContext()
                    .getSharedPreferences("launcher_profile_prefs", 0)
                    .getString("custom_avatar_uri", "");
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
            binding.padAvatarImage.setVisibility(View.GONE);
            binding.padAvatarInitial.setVisibility(View.VISIBLE);
        } catch (Throwable throwable) {
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
        return "本地玩家";
    }

    private String accountMode() {
        if (!LauncherAuthBridge.isLoggedIn(requireContext())) return "本地模式";
        String status = appPrefs().getString(KEY_AUTH_STATUS, "");
        if (AUTH_STATUS_ONLINE.equals(status)) return "在线模式";
        if (AUTH_STATUS_SYNCING.equals(status)) return "在线模式 · 同步中";
        if (AUTH_STATUS_EXPIRED.equals(status)) return "在线模式 · 登录过期";
        return "在线模式";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
