package com.apps;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.ai.AiReviewController;
import com.yuki.yukihub.ai.AiReviewHistoryStore;
import com.yuki.yukihub.databinding.ActivityLauncherAiReviewImagePreviewBinding;
import com.yuki.yukihub.util.AppExecutors;

import java.util.List;

public class LauncherAiReviewImagePreviewActivity extends AppCompatActivity {
    public static final String EXTRA_ENTRY_INDEX = "entry_index";
    public static final String EXTRA_TEMPLATE_STYLE = "template_style";
    public static final String EXTRA_TEMPLATE_LABEL = "template_label";

    private ActivityLauncherAiReviewImagePreviewBinding binding;
    private AiReviewController aiReviewController;
    private AiReviewController.ImageResult imageResult;
    private String templateLabel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherAiReviewImagePreviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarInsets();

        int index = getIntent().getIntExtra(EXTRA_ENTRY_INDEX, -1);
        int style = getIntent().getIntExtra(EXTRA_TEMPLATE_STYLE, 0);
        templateLabel = getIntent().getStringExtra(EXTRA_TEMPLATE_LABEL);
        if (templateLabel == null) templateLabel = "长图";

        List<AiReviewHistoryStore.Entry> entries = AiReviewHistoryStore.load(this);
        if (index < 0 || index >= entries.size()) {
            Toast.makeText(this, "记录不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        AiReviewHistoryStore.Entry entry = entries.get(index);

        binding.imagePreviewTitle.setText(entry.displayTitle());
        binding.imagePreviewMeta.setText("模板：" + templateLabel);

        aiReviewController = new AiReviewController(this, new LauncherAiReviewDetailActivity.LauncherAiReviewDelegate(this));

        binding.imagePreviewClose.setOnClickListener(v -> finish());
        binding.imagePreviewShare.setOnClickListener(v -> {
            if (imageResult != null) aiReviewController.shareAiReviewImage(imageResult.uri);
        });
        binding.imagePreviewSave.setOnClickListener(v -> {
            if (imageResult != null) aiReviewController.saveAiReviewImageToGallery(imageResult.uri, templateLabel);
        });
        binding.imagePreviewView.setOnClickListener(v -> enterFullScreen());
        binding.imagePreviewFullScreenClose.setOnClickListener(v -> exitFullScreen());

        generateImage(entry.result, style);
    }

    private void generateImage(com.yuki.yukihub.ai.AiReviewResult result, int style) {
        binding.imagePreviewLoading.setVisibility(View.VISIBLE);
        binding.imagePreviewView.setVisibility(View.GONE);
        binding.imagePreviewButtons.setVisibility(View.GONE);

        AppExecutors.io().execute(() -> {
            try {
                final AiReviewController.ImageResult res = aiReviewController.generateAiReviewImageSync(result, style);
                runOnUiThread(() -> {
                    imageResult = res;
                    binding.imagePreviewLoading.setVisibility(View.GONE);
                    binding.imagePreviewView.setVisibility(View.VISIBLE);
                    binding.imagePreviewButtons.setVisibility(View.VISIBLE);
                    binding.imagePreviewView.setImageURI(res.uri);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    binding.imagePreviewLoading.setVisibility(View.GONE);
                    Toast.makeText(this, "生成长图失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void enterFullScreen() {
        if (imageResult == null) return;
        binding.imagePreviewFullScreen.setVisibility(View.VISIBLE);
        binding.imagePreviewFullScreenImage.setImageURI(imageResult.uri);
        Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void exitFullScreen() {
        binding.imagePreviewFullScreen.setVisibility(View.GONE);
        Window window = getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            View decor = window.getDecorView();
            boolean darkMode = LauncherActivity.isLauncherDarkMode(this);
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            if (!darkMode) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    @Override
    public void onBackPressed() {
        if (binding.imagePreviewFullScreen.getVisibility() == View.VISIBLE) {
            exitFullScreen();
            return;
        }
        super.onBackPressed();
    }

    private void applySystemBarInsets() {
        int left = binding.imagePreviewScroll.getPaddingLeft();
        int top = binding.imagePreviewScroll.getPaddingTop();
        int right = binding.imagePreviewScroll.getPaddingRight();
        int bottom = binding.imagePreviewScroll.getPaddingBottom();
        binding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            binding.imagePreviewScroll.setPadding(left, top + insets.getSystemWindowInsetTop(), right, bottom);
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
}
