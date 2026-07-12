package com.apps.chat;

import android.graphics.Color;
import android.content.Intent;
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
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

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
        LauncherTabletPortraitScaler.applyActivityContent(this);
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
        binding.chatSelectContinue.setOnClickListener(view -> openSelectedChat());
    }

    private void applyIconTone() {
        binding.publicChatRow.getChildAt(0).setBackground(LauncherTheme.circle(this));
    }

    private void openSelectedChat() {
        if (!LauncherAuthBridge.isLoggedIn(this)) {
            Toast.makeText(this, "请先在个人中心登录后再进入聊天室", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent;
        if ("Yuki娘（AI）".equals(selectedChat)) {
            intent = new Intent(this, LauncherAiChatActivity.class)
                    .putExtra(LauncherAiChatActivity.EXTRA_PERSONA, "persona_yuki")
                    .putExtra(LauncherAiChatActivity.EXTRA_THREAD_ID, "launcher-yuki")
                    .putExtra(LauncherAiChatActivity.EXTRA_TITLE, "Yuki娘（AI）");
        } else if ("园神凛弥（AI）".equals(selectedChat)) {
            intent = new Intent(this, LauncherAiChatActivity.class)
                    .putExtra(LauncherAiChatActivity.EXTRA_PERSONA, "persona_rinne")
                    .putExtra(LauncherAiChatActivity.EXTRA_THREAD_ID, "launcher-rinne")
                    .putExtra(LauncherAiChatActivity.EXTRA_TITLE, "园神凛弥（AI）");
        } else {
            intent = new Intent(this, LauncherPublicChatActivity.class);
        }
        startActivity(intent);
        LauncherMotion.applyActivityOpen(this);
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
