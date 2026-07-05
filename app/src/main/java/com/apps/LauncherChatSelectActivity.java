package com.apps;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherChatSelectBinding;

public class LauncherChatSelectActivity extends AppCompatActivity {
    private ActivityLauncherChatSelectBinding binding;
    private String selectedChat = "公共聊天室";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherChatSelectBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarInsets();
        bindActions();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        applyIconTone();
        renderSelection();
    }

    private void applySystemBarInsets() {
        int originalLeft = binding.chatSelectScroll.getPaddingLeft();
        int originalTop = binding.chatSelectScroll.getPaddingTop();
        int originalRight = binding.chatSelectScroll.getPaddingRight();
        int originalBottom = binding.chatSelectScroll.getPaddingBottom();

        binding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            binding.chatSelectScroll.setPadding(
                    originalLeft,
                    originalTop + insets.getSystemWindowInsetTop(),
                    originalRight,
                    originalBottom
            );
            return insets;
        });
        binding.getRoot().requestApplyInsets();
    }

    private void bindActions() {
        binding.publicChatRow.setOnClickListener(view -> selectChat("公共聊天室"));
        binding.yukiAiRow.setOnClickListener(view -> selectChat("Yuki娘（AI）"));
        binding.rinmiAiRow.setOnClickListener(view -> selectChat("园神凛弥（AI）"));
        binding.chatSelectContinue.setOnClickListener(view ->
                Toast.makeText(this, selectedChat + " 待接入", Toast.LENGTH_SHORT).show());
    }

    private void applyIconTone() {
        binding.publicChatRow.getChildAt(0).setBackground(LauncherTheme.circle(this));
    }

    private void selectChat(String chatName) {
        selectedChat = chatName;
        renderSelection();
    }

    private void renderSelection() {
        boolean publicSelected = "公共聊天室".equals(selectedChat);
        boolean yukiSelected = "Yuki娘（AI）".equals(selectedChat);
        boolean rinmiSelected = "园神凛弥（AI）".equals(selectedChat);

        binding.publicChatRow.setBackgroundResource(publicSelected
                ? 0
                : R.drawable.launcher_chat_option_bg);
        if (publicSelected) binding.publicChatRow.setBackground(LauncherTheme.selectedOption(this));
        binding.yukiAiRow.setBackgroundResource(yukiSelected
                ? 0
                : R.drawable.launcher_chat_option_bg);
        if (yukiSelected) binding.yukiAiRow.setBackground(LauncherTheme.selectedOption(this));
        binding.rinmiAiRow.setBackgroundResource(rinmiSelected
                ? 0
                : R.drawable.launcher_chat_option_bg);
        if (rinmiSelected) binding.rinmiAiRow.setBackground(LauncherTheme.selectedOption(this));

        binding.publicChatCheck.setVisibility(publicSelected ? View.VISIBLE : View.INVISIBLE);
        binding.yukiAiCheck.setVisibility(yukiSelected ? View.VISIBLE : View.INVISIBLE);
        binding.rinmiAiCheck.setVisibility(rinmiSelected ? View.VISIBLE : View.INVISIBLE);
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

    private void applySavedToneMode() {
        LauncherActivity.applySavedToneMode(this);
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
