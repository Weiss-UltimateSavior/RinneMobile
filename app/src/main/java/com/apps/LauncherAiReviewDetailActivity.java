package com.apps;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.ai.AiReviewController;
import com.yuki.yukihub.ai.AiReviewHistoryStore;
import com.yuki.yukihub.ai.AiReviewResult;
import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.data.MetadataRepository;
import com.yuki.yukihub.databinding.ActivityLauncherAiReviewDetailBinding;
import com.yuki.yukihub.metadata.VnMetadata;
import com.yuki.yukihub.model.Game;

import java.util.List;
import java.util.Locale;

public class LauncherAiReviewDetailActivity extends AppCompatActivity {
    public static final String EXTRA_ENTRY_INDEX = "entry_index";

    private ActivityLauncherAiReviewDetailBinding binding;
    private AiReviewController aiReviewController;
    private AiReviewHistoryStore.Entry entry;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherAiReviewDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarInsets();

        int index = getIntent().getIntExtra(EXTRA_ENTRY_INDEX, -1);
        List<AiReviewHistoryStore.Entry> entries = AiReviewHistoryStore.load(this);
        if (index < 0 || index >= entries.size()) {
            Toast.makeText(this, "记录不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        entry = entries.get(index);

        aiReviewController = new AiReviewController(this, new LauncherAiReviewDelegate(this));

        binding.aiDetailTitle.setText(entry.displayTitle());
        binding.aiDetailMeta.setText(entry.displaySummary());
        renderAiReviewCard(binding.aiDetailCardContainer, entry.result);
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        binding.aiDetailClose.setOnClickListener(v -> finish());
    }

    private void renderAiReviewCard(LinearLayout container, AiReviewResult result) {
        if (container == null || result == null) return;
        container.removeAllViews();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(cardBackground());

        TextView badge = new TextView(this);
        badge.setText("✦ AI 周点评 ✦");
        badge.setTextColor(LauncherTheme.primary(this));
        badge.setTextSize(11);
        badge.setTypeface(null, Typeface.BOLD);
        card.addView(badge);

        TextView title = new TextView(this);
        title.setText(result.title);
        title.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(4), 0, 0);
        card.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(result.subtitle);
        subtitle.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_muted_color));
        subtitle.setTextSize(12);
        subtitle.setPadding(0, dp(4), 0, dp(8));
        card.addView(subtitle);

        TextView score = new TextView(this);
        score.setText(result.scoreName + "  " + result.score + "/100");
        score.setTextColor(LauncherTheme.primary(this));
        score.setTextSize(13);
        score.setTypeface(null, Typeface.BOLD);
        card.addView(score);

        int progress = Math.max(0, Math.min(100, result.score));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(roundBg(R.color.launcher_card_alt_color, dp(8), 0));
        LinearLayout fill = new LinearLayout(this);
        fill.setBackground(LauncherTheme.primaryButton(this, 8f));
        bar.addView(fill, new LinearLayout.LayoutParams(0, dp(8), Math.max(1, progress)));
        View rest = new View(this);
        bar.addView(rest, new LinearLayout.LayoutParams(0, dp(8), Math.max(1, 100 - progress)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        barLp.setMargins(0, dp(6), 0, dp(10));
        card.addView(bar, barLp);

        TextView roast = new TextView(this);
        roast.setText(result.roast);
        roast.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        roast.setTextSize(14);
        roast.setLineSpacing(dp(2), 1.05f);
        roast.setPadding(dp(10), dp(8), dp(10), dp(8));
        roast.setBackground(roundBg(R.color.launcher_card_alt_color, dp(10), R.color.launcher_line_color));
        card.addView(roast, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (result.highlights != null && !result.highlights.isEmpty()) {
            card.addView(sectionTitle("本周抓包"));
            for (String h : result.highlights) card.addView(chip("• " + h));
        }
        if (result.topGamesComment != null && !result.topGamesComment.isEmpty()) {
            card.addView(sectionTitle("重点游戏吐槽"));
            for (AiReviewResult.GameComment gc : result.topGamesComment) {
                String text = (gc.game == null || gc.game.isEmpty() ? "游戏" : "《" + gc.game + "》") + "：" + gc.comment;
                card.addView(chip(text));
            }
        }
        if (result.advice != null && !result.advice.isEmpty()) {
            card.addView(sectionTitle("下周处方"));
            for (String a : result.advice) card.addView(chip("✧ " + a));
        }
        if (result.oneLine != null && !result.oneLine.trim().isEmpty()) {
            TextView one = new TextView(this);
            one.setText(result.oneLine);
            one.setTextColor(LauncherTheme.primary(this));
            one.setTextSize(12);
            one.setTypeface(null, Typeface.BOLD);
            one.setPadding(0, dp(10), 0, 0);
            card.addView(one);
        }

        LinearLayout shareRow = new LinearLayout(this);
        shareRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView copy = actionButton("复制点评");
        TextView share = actionButton("分享文本");
        copy.setOnClickListener(v -> copyAiReviewText(result));
        share.setOnClickListener(v -> shareAiReviewText(result));
        shareRow.addView(copy, new LinearLayout.LayoutParams(0, dp(40), 1));
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(0, dp(40), 1);
        shareLp.setMargins(dp(8), 0, 0, 0);
        shareRow.addView(share, shareLp);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        copyLp.setMargins(0, dp(10), 0, 0);
        card.addView(shareRow, copyLp);

        TextView imageShare = actionButtonPrimary("分享模板长图");
        imageShare.setOnClickListener(v -> showTemplateSelectionDialog());
        LinearLayout.LayoutParams imageShareLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        imageShareLp.setMargins(0, dp(8), 0, 0);
        card.addView(imageShare, imageShareLp);

        container.addView(card, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LauncherTheme.applyPrimaryTone(card);
    }

    private TextView sectionTitle(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        v.setTextSize(13);
        v.setTypeface(null, Typeface.BOLD);
        v.setPadding(0, dp(12), 0, dp(5));
        return v;
    }

    private TextView chip(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_muted_color));
        v.setTextSize(12);
        v.setLineSpacing(dp(1), 1.0f);
        v.setPadding(dp(10), dp(7), dp(10), dp(7));
        v.setBackground(roundBg(R.color.launcher_card_alt_color, dp(9), R.color.launcher_line_color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(5));
        v.setLayoutParams(lp);
        return v;
    }

    private TextView actionButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(LauncherTheme.primary(this));
        v.setTextSize(14);
        v.setTypeface(null, Typeface.BOLD);
        v.setBackground(LauncherTheme.cancelChip(this));
        return v;
    }

    private TextView actionButtonPrimary(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(LauncherTheme.onPrimary(this));
        v.setTextSize(14);
        v.setTypeface(null, Typeface.BOLD);
        v.setBackground(LauncherTheme.primaryButton(this, 22f));
        return v;
    }

    private void showTemplateSelectionDialog() {
        if (entry == null || entry.result == null) return;
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.show();

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(300), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(22), dp(24), dp(18));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);

        TextView title = new TextView(this);
        title.setText("选择导出模板");
        title.setGravity(Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText("选择一套导出长图模板。手账风更接近报告预览，霓虹周报适合深色风格。");
        hint.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_muted_color));
        hint.setTextSize(12);
        hint.setLineSpacing(dp(1), 1.0f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, dp(10), 0, dp(6));
        root.addView(hint, hintLp);

        String[] labels = AiReviewController.getTemplateLabels();
        for (int i = 0; i < labels.length; i++) {
            final int style = i;
            TextView row = new TextView(this);
            row.setText(AiReviewController.getTemplatePrefix(style) + labels[i]);
            row.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
            row.setTextSize(14);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(LauncherTheme.cancelChip(this));
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(6), 0, dp(6));
            root.addView(row, lp);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                Intent intent = new Intent(this, LauncherAiReviewImagePreviewActivity.class);
                intent.putExtra(LauncherAiReviewImagePreviewActivity.EXTRA_ENTRY_INDEX, getIntent().getIntExtra(EXTRA_ENTRY_INDEX, -1));
                intent.putExtra(LauncherAiReviewImagePreviewActivity.EXTRA_TEMPLATE_STYLE, style);
                intent.putExtra(LauncherAiReviewImagePreviewActivity.EXTRA_TEMPLATE_LABEL, labels[style]);
                startActivity(intent);
            });
        }

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setGravity(Gravity.CENTER);
        cancel.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_muted_color));
        cancel.setTextSize(14);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        cancelLp.setMargins(0, dp(10), 0, 0);
        root.addView(cancel, cancelLp);
        cancel.setOnClickListener(v -> dialog.dismiss());

        window.setContentView(root);
    }

    private GradientDrawable cardBackground() {
        int cardColor = ContextCompat.getColor(this, R.color.launcher_card_color);
        int cardAltColor = ContextCompat.getColor(this, R.color.launcher_card_alt_color);
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{cardColor, cardAltColor, cardColor}
        );
        g.setCornerRadius(dp(16));
        return g;
    }

    private GradientDrawable roundBg(int colorResId, int radius, int strokeColorResId) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(ContextCompat.getColor(this, colorResId));
        g.setCornerRadius(radius);
        return g;
    }

    private void copyAiReviewText(AiReviewResult result) {
        try {
            Object service = getSystemService(Context.CLIPBOARD_SERVICE);
            if (service instanceof ClipboardManager) {
                ((ClipboardManager) service).setPrimaryClip(ClipData.newPlainText("YukiHub AI Review", result.toShareText()));
                Toast.makeText(this, "点评已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareAiReviewText(AiReviewResult result) {
        if (result == null) return;
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "YukiHub AI 周点评");
            send.putExtra(Intent.EXTRA_TEXT, result.toShareText());
            startActivity(Intent.createChooser(send, "分享 AI 周点评"));
        } catch (Throwable t) {
            Toast.makeText(this, "分享失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void applySystemBarInsets() {
        int left = binding.aiDetailScroll.getPaddingLeft();
        int top = binding.aiDetailScroll.getPaddingTop();
        int right = binding.aiDetailScroll.getPaddingRight();
        int bottom = binding.aiDetailScroll.getPaddingBottom();
        binding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            binding.aiDetailScroll.setPadding(left, top + insets.getSystemWindowInsetTop(), right, bottom);
            return insets;
        });
        binding.getRoot().requestApplyInsets();
    }

    private void configureEdgeToEdgeWindow() {
        boolean darkMode = LauncherActivity.isLauncherDarkMode(this);
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bg_color));
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!darkMode) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private int dp(int value) {
        return LauncherTheme.dp(this, value);
    }

    static final class LauncherAiReviewDelegate implements AiReviewController.Delegate {
        private final AppCompatActivity activity;
        private final GameRepository gameRepository;
        private final MetadataRepository metadataRepository;
        private final List<Game> allGames;

        LauncherAiReviewDelegate(AppCompatActivity activity) {
            this.activity = activity;
            this.gameRepository = new GameRepository(activity.getApplicationContext());
            this.metadataRepository = new MetadataRepository(activity.getApplicationContext());
            this.allGames = gameRepository.getAll();
        }

        @Override public GameRepository gameRepository() { return gameRepository; }
        @Override public MetadataRepository metadataRepository() { return metadataRepository; }
        @Override public List<Game> allGames() { return allGames; }
        @Override public SharedPreferences prefs() { return activity.getPreferences(Context.MODE_PRIVATE); }
        @Override public String visibleMetadataSource(long gameId) { return null; }
        @Override public VnMetadata metadataForSource(long gameId, String source) { return null; }
        @Override public VnMetadata anyCachedMetadata(long gameId) { return null; }
        @Override public boolean usingYmgal() { return false; }
        @Override public boolean usingBangumi() { return false; }
        @Override public boolean usingBangumiMirror() { return false; }
        @Override public String bangumiToken() { return null; }
        @Override public String buildMetadataSearchKeyword(String title) { return title; }
        @Override public boolean isConfidentMatch(String localTitle, VnMetadata meta) { return false; }

        @Override public int dp(int value) {
            return LauncherTheme.dp(activity, value);
        }

        @Override public int getColorCompat(int id) {
            if (id == R.color.yh_text) return ContextCompat.getColor(activity, R.color.launcher_text_color);
            if (id == R.color.yh_text_muted) return ContextCompat.getColor(activity, R.color.launcher_text_muted_color);
            if (id == R.color.yh_primary) return LauncherTheme.primary(activity);
            if (id == R.color.yh_secondary) return LauncherTheme.primary(activity);
            if (id == R.color.yh_card) return ContextCompat.getColor(activity, R.color.launcher_card_color);
            if (id == R.color.yh_card_2) return ContextCompat.getColor(activity, R.color.launcher_card_alt_color);
            if (id == R.color.yh_line) return ContextCompat.getColor(activity, R.color.launcher_line_color);
            if (id == R.color.yh_bg) return ContextCompat.getColor(activity, R.color.launcher_bg_color);
            if (id == R.color.yh_bg_2) return ContextCompat.getColor(activity, R.color.launcher_card_alt_color);
            return ContextCompat.getColor(activity, id);
        }

        @Override public Button krButton(String text) {
            Button b = new Button(activity);
            b.setText(text);
            b.setBackgroundResource(R.drawable.launcher_account_input);
            return b;
        }

        @Override public Spinner krSpinner(String[] values, String selected) {
            Spinner s = new Spinner(activity);
            return s;
        }

        @Override public CheckBox krCheckBox(String text, boolean checked) {
            CheckBox cb = new CheckBox(activity);
            cb.setText(text);
            cb.setChecked(checked);
            return cb;
        }

        @Override public void styleAlertDialogDark(AlertDialog dialog) {
            if (dialog == null) return;
            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawableResource(R.drawable.launcher_dialog_bg);
            }
        }

        @Override public void applyImmersiveToWindow(Window window) {
            if (window == null) return;
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            View decor = window.getDecorView();
            if (decor == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller != null) {
                    controller.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        }

        @Override public void playUiSound(int type) { }

        @Override public String emptyText(String s, String fallback) {
            return s == null || s.trim().isEmpty() ? fallback : s;
        }

        @Override public String normalizePlayStatus(String status) {
            if (status == null) return "unplayed";
            String s = status.trim().toLowerCase(Locale.ROOT);
            if ("completed".equals(s) || "played".equals(s) || "done".equals(s)) return "completed";
            if ("playing".equals(s) || "current".equals(s)) return "playing";
            return "unplayed";
        }

        @Override public String safeCoverUri(Game g) {
            if (g == null) return null;
            if (g.coverPersistUri != null && !g.coverPersistUri.isEmpty()) return g.coverPersistUri;
            if (g.coverUri != null && !g.coverUri.isEmpty()) return g.coverUri;
            return null;
        }

        @Override public String initials(String title) {
            if (title == null || title.trim().isEmpty()) return "YH";
            return title.trim().substring(0, 1).toUpperCase(Locale.ROOT);
        }
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
