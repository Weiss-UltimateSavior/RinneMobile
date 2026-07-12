package com.apps.leaderboard;

import android.graphics.Color;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yuki.yukihub.R;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;
import com.yuki.yukihub.util.TimeFormatUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

/** User-facing global playtime leaderboard. */
public class LauncherLeaderboardActivity extends AppCompatActivity {
    private LauncherLeaderboardAdapter adapter;
    private FrameLayout topThree;
    private TextView state;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        setContentView(R.layout.activity_launcher_leaderboard);
        LauncherTabletPortraitScaler.applyActivityContent(this);
        applyInsets();
        LauncherTheme.applyPrimaryTone(findViewById(R.id.leaderboardRoot));
        adapter = new LauncherLeaderboardAdapter();
        RecyclerView list = findViewById(R.id.leaderboardList);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        topThree = findViewById(R.id.leaderboardTopThree);
        state = findViewById(R.id.leaderboardState);
        loadLeaderboard();
    }

    private void loadLeaderboard() {
        LauncherAuthBridge.fetchPlayTimeLeaderboard(this, new LauncherAuthBridge.LeaderboardCallback() {
            @Override public void onSuccess(List<LauncherAuthBridge.LeaderboardEntry> entries) {
                showLeaderboard(entries);
            }
            @Override public void onError(String message) {
                showLeaderboard(Collections.emptyList());
                state.setText("排行榜暂不可用");
                state.setVisibility(View.VISIBLE);
            }
        });
    }

    private void showLeaderboard(List<LauncherAuthBridge.LeaderboardEntry> serverEntries) {
        List<LauncherAuthBridge.LeaderboardEntry> entries = normalizeEntries(serverEntries);
        bindTop(entries, 0, R.id.leaderboardFirst, R.id.leaderboardFirstRank, R.id.leaderboardFirstName, R.id.leaderboardFirstDuration);
        bindTop(entries, 1, R.id.leaderboardSecond, R.id.leaderboardSecondRank, R.id.leaderboardSecondName, R.id.leaderboardSecondDuration);
        bindTop(entries, 2, R.id.leaderboardThird, R.id.leaderboardThirdRank, R.id.leaderboardThirdName, R.id.leaderboardThirdDuration);
        topThree.setVisibility(View.VISIBLE);
        state.setVisibility(View.GONE);
        adapter.submit(entries.subList(3, entries.size()));
    }

    private List<LauncherAuthBridge.LeaderboardEntry> normalizeEntries(List<LauncherAuthBridge.LeaderboardEntry> serverEntries) {
        List<LauncherAuthBridge.LeaderboardEntry> normalized = new ArrayList<>();
        for (int rank = 1; rank <= 15; rank++) {
            LauncherAuthBridge.LeaderboardEntry matched = null;
            if (serverEntries != null) for (LauncherAuthBridge.LeaderboardEntry entry : serverEntries) {
                if (entry != null && entry.rank == rank) { matched = entry; break; }
            }
            normalized.add(matched != null ? matched : new LauncherAuthBridge.LeaderboardEntry(rank, "暂无排名", -1L));
        }
        return normalized;
    }

    private void bindTop(List<LauncherAuthBridge.LeaderboardEntry> entries, int index, int containerId, int rankId, int nameId, int durationId) {
        View container = findViewById(containerId);
        LauncherAuthBridge.LeaderboardEntry entry = entries.get(index);
        container.setVisibility(View.VISIBLE);
        ImageView rank = findViewById(rankId);
        rank.setImageTintList(ColorStateList.valueOf(medalColor(entry.rank)));
        ((TextView) findViewById(nameId)).setText(entry.username);
        TextView duration = findViewById(durationId);
        duration.setVisibility(entry.totalDurationMs < 0L ? View.GONE : View.VISIBLE);
        if (entry.totalDurationMs >= 0L) duration.setText(TimeFormatUtil.playTime(entry.totalDurationMs));
    }

    private int medalColor(int rank) {
        if (rank == 1) return ContextCompat.getColor(this, R.color.launcher_rank_gold_color);
        if (rank == 2) return ContextCompat.getColor(this, R.color.launcher_rank_silver_color);
        return ContextCompat.getColor(this, R.color.launcher_rank_bronze_color);
    }

    private void applyInsets() { View root = findViewById(R.id.leaderboardRoot); int left = root.getPaddingLeft(), top = root.getPaddingTop(), right = root.getPaddingRight(), bottom = root.getPaddingBottom(); root.setOnApplyWindowInsetsListener((view, insets) -> { view.setPadding(left, top + insets.getSystemWindowInsetTop(), right, bottom + insets.getSystemWindowInsetBottom()); return insets; }); root.requestApplyInsets(); }
    private void configureEdgeToEdgeWindow() { boolean dark = LauncherActivity.isLauncherDarkMode(this); Window window = getWindow(); window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN); window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS); window.setStatusBarColor(Color.TRANSPARENT); window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bg_color)); int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN; if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; window.getDecorView().setSystemUiVisibility(flags); }
    private void applySavedToneMode() { LauncherActivity.applySavedToneMode(this); }
    @Override protected void attachBaseContext(android.content.Context newBase) { super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase)); }
}
