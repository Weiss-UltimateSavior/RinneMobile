package com.apps;

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
import com.yuki.yukihub.ai.AiReviewHistoryStore;
import com.yuki.yukihub.ai.AiReviewResult;
import com.yuki.yukihub.util.TimeFormatUtil;

import java.util.List;

public class LauncherAiReviewHistoryActivity extends AppCompatActivity {
    private ScrollView scroll;
    private LinearLayout list;
    private TextView detailTitle;
    private TextView detailBody;
    private TextView btnClear;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        setContentView(R.layout.activity_launcher_ai_review_history);
        bindViews();
        applySystemBarInsets();
        applyThemeTone();
        bindActions();
        loadHistory();
    }

    private void bindViews() {
        scroll = findViewById(R.id.aiHistoryScroll);
        list = findViewById(R.id.aiHistoryList);
        detailTitle = findViewById(R.id.aiHistoryDetailTitle);
        detailBody = findViewById(R.id.aiHistoryDetailBody);
        btnClear = findViewById(R.id.aiHistoryClear);
    }

    private void bindActions() {
        btnClear.setOnClickListener(view -> {
            AiReviewHistoryStore.clear(this);
            Toast.makeText(this, "点评历史已清空", Toast.LENGTH_SHORT).show();
            loadHistory();
        });
    }

    private void loadHistory() {
        List<AiReviewHistoryStore.Entry> entries = AiReviewHistoryStore.load(this);
        list.removeAllViews();
        if (entries.isEmpty()) {
            list.addView(historyItem("暂无点评历史", "生成点评后会显示在这里", null));
            detailTitle.setText("暂无详情");
            detailBody.setText("还没有保存过的智能评价。");
            return;
        }
        for (AiReviewHistoryStore.Entry entry : entries) {
            list.addView(historyItem(entry.displayTitle(), entry.displaySummary(), () -> renderEntry(entry)));
        }
        renderEntry(entries.get(0));
    }

    private View historyItem(String title, String summary, @Nullable Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(LauncherTheme.dp(this, 14f), LauncherTheme.dp(this, 12f), LauncherTheme.dp(this, 14f), LauncherTheme.dp(this, 12f));
        row.setBackground(LauncherTheme.cancelChip(this));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        titleView.setTextSize(15f);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView summaryView = new TextView(this);
        summaryView.setText(summary);
        summaryView.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_muted_color));
        summaryView.setTextSize(13f);
        summaryView.setPadding(0, LauncherTheme.dp(this, 4f), 0, 0);

        row.addView(titleView);
        row.addView(summaryView);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, LauncherTheme.dp(this, 10f));
        row.setLayoutParams(params);
        if (action != null) row.setOnClickListener(view -> action.run());
        return row;
    }

    private void renderEntry(AiReviewHistoryStore.Entry entry) {
        if (entry == null || entry.result == null) return;
        AiReviewResult result = entry.result;
        detailTitle.setText(result.title);
        detailBody.setText(entry.displaySummary()
                + "\n总时长：" + TimeFormatUtil.playTime(entry.totalDuration)
                + "\n\n" + result.toShareText());
    }

    private void applyThemeTone() {
        btnClear.setBackground(LauncherTheme.cancelChip(this));
        LauncherTheme.textPrimary(btnClear);
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
