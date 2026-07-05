package com.apps;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.ai.AiReviewClient;
import com.yuki.yukihub.ai.AiReviewHistoryStore;
import com.yuki.yukihub.ai.AiReviewResult;
import com.yuki.yukihub.ai.AiReviewSettings;
import com.yuki.yukihub.ai.WeeklyPlayStats;
import com.yuki.yukihub.util.AppExecutors;
import com.yuki.yukihub.util.TimeFormatUtil;

import java.util.Map;

public class LauncherAiReviewGenerateActivity extends AppCompatActivity {
    private ScrollView scroll;
    private TextView statsText;
    private LinearLayout topGamesList;
    private TextView resultTitle;
    private TextView resultBody;
    private TextView btnGenerate;
    private TextView btnHistory;
    private WeeklyPlayStats stats;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        setContentView(R.layout.activity_launcher_ai_review_generate);
        bindViews();
        applySystemBarInsets();
        applyThemeTone();
        loadStats();
        bindActions();
    }

    private void bindViews() {
        scroll = findViewById(R.id.aiGenerateScroll);
        statsText = findViewById(R.id.aiGenerateStats);
        topGamesList = findViewById(R.id.aiGenerateTopGames);
        resultTitle = findViewById(R.id.aiGenerateResultTitle);
        resultBody = findViewById(R.id.aiGenerateResultBody);
        btnGenerate = findViewById(R.id.aiGenerateSubmit);
        btnHistory = findViewById(R.id.aiGenerateHistory);
    }

    private void loadStats() {
        stats = LauncherAiReviewStatsBuilder.build(this);
        statsText.setText("最近 7 天 · " + stats.gameCount() + " 款游戏 · "
                + stats.sessionCount + " 次游玩 · 总计 " + TimeFormatUtil.playTime(stats.totalDuration));
        topGamesList.removeAllViews();
        if (stats.topGames.isEmpty()) {
            topGamesList.addView(infoLine("最近 7 天暂无可评价的游玩记录"));
            return;
        }
        for (Map.Entry<String, Long> entry : stats.topGames.entrySet()) {
            topGamesList.addView(infoLine(entry.getKey() + " · " + TimeFormatUtil.playTime(entry.getValue() == null ? 0L : entry.getValue())));
        }
    }

    private void bindActions() {
        btnGenerate.setOnClickListener(view -> generateReview());
        btnHistory.setOnClickListener(view -> startActivity(new Intent(this, LauncherAiReviewHistoryActivity.class)));
    }

    private void generateReview() {
        if (stats == null) stats = LauncherAiReviewStatsBuilder.build(this);
        if (stats.isEmpty()) {
            Toast.makeText(this, "最近 7 天还没有有效游玩记录", Toast.LENGTH_SHORT).show();
            return;
        }
        AiReviewSettings settings = AiReviewSettings.load(this);
        if (settings.apiKey == null || settings.apiKey.trim().isEmpty()) {
            Toast.makeText(this, "请先在智能评价中填写 API Key", Toast.LENGTH_LONG).show();
            return;
        }
        setLoading(true);
        resultTitle.setText("生成中...");
        resultBody.setText("正在请求模型，请稍候。");
        AppExecutors.runOnIo(() -> {
            try {
                String content = new AiReviewClient().requestReview(settings, stats);
                AiReviewResult result = AiReviewResult.fromContent(content);
                AiReviewHistoryStore.save(this, stats, settings, result);
                runOnUiThread(() -> {
                    setLoading(false);
                    renderResult(result);
                    Toast.makeText(this, "点评已保存到历史", Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    setLoading(false);
                    resultTitle.setText("生成失败");
                    resultBody.setText(throwable.getMessage() == null ? "请检查模型配置和网络" : throwable.getMessage());
                });
            }
        });
    }

    private void renderResult(AiReviewResult result) {
        resultTitle.setText(result == null ? "AI 周点评" : result.title);
        resultBody.setText(result == null ? "" : result.toShareText());
    }

    private TextView infoLine(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        view.setTextSize(15f);
        view.setPadding(0, LauncherTheme.dp(this, 6f), 0, LauncherTheme.dp(this, 6f));
        return view;
    }

    private void setLoading(boolean loading) {
        btnGenerate.setEnabled(!loading);
        btnGenerate.setAlpha(loading ? 0.55f : 1f);
        btnGenerate.setText(loading ? "生成中..." : "生成点评");
    }

    private void applyThemeTone() {
        LauncherTheme.primaryButton(btnGenerate);
        btnHistory.setBackground(LauncherTheme.cancelChip(this));
        LauncherTheme.textPrimary(btnHistory);
        LauncherTheme.applyPrimaryTone(findViewById(android.R.id.content));
    }

    private void applySystemBarInsets() {
        int left = scroll.getPaddingLeft();
        int top = scroll.getPaddingTop();
        int right = scroll.getPaddingRight();
        int bottom = scroll.getPaddingBottom();
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            scroll.setPadding(left, top + insets.getSystemWindowInsetTop(), right, bottom);
            return insets;
        });
        scroll.requestApplyInsets();
    }

    private void configureEdgeToEdgeWindow() {
        boolean darkMode = LauncherActivity.isLauncherDarkMode(this);
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bg_color));
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!darkMode) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
    }
}
