package com.apps;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.yuki.yukihub.databinding.FragmentLauncherHomeBinding;
import com.yuki.yukihub.launcherbridge.LauncherUpdateBridge;
import com.yuki.yukihub.util.SafeImageLoader;

import java.util.List;

public class LauncherHomeFragment extends Fragment {
    private static final long STATS_REFRESH_INTERVAL_MS = 3000L;
    private static final String APP_PREFS = "yukihub_prefs";
    private static final String KEY_PROFILE_AVATAR = "profile_avatar";

    private FragmentLauncherHomeBinding binding;
    private LauncherViewModel viewModel;
    private ActivityResultLauncher<String[]> avatarPickerLauncher;
    private final Handler statsRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable statsRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (binding == null || viewModel == null) return;
            viewModel.refreshStats();
            statsRefreshHandler.postDelayed(this, STATS_REFRESH_INTERVAL_MS);
        }
    };

    public LauncherHomeFragment() {
        avatarPickerLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            persistAvatarUri(uri);
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLauncherHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(LauncherViewModel.class);

        applySystemBarInsets();
        setupRecentList();
        binding.launcherAvatarContainer.setClipToOutline(true);
        renderAvatar();
        applyThemeStyle();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        applyIconTone();
        bindActions();
        observeState();
        viewModel.refreshRecentItemsIfNeeded();
    }

    @Override
    public void onResume() {
        super.onResume();
        applyThemeStyle();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        applyIconTone();
        startStatsRefresh();
    }

    @Override
    public void onPause() {
        stopStatsRefresh();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        stopStatsRefresh();
        if (binding != null) {
            binding.getRoot().setOnApplyWindowInsetsListener(null);
        }
        super.onDestroyView();
        binding = null;
    }

    private void applySystemBarInsets() {
        FragmentLauncherHomeBinding currentBinding = binding;
        int originalLeft = currentBinding.contentScroll.getPaddingLeft();
        int originalTop = currentBinding.contentScroll.getPaddingTop();
        int originalRight = currentBinding.contentScroll.getPaddingRight();
        int originalBottom = currentBinding.contentScroll.getPaddingBottom();

        currentBinding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            currentBinding.contentScroll.setPadding(
                    originalLeft,
                    originalTop + insets.getSystemWindowInsetTop(),
                    originalRight,
                    originalBottom
            );
            return insets;
        });
        currentBinding.getRoot().requestApplyInsets();
    }

    private void bindActions() {
        binding.launcherAvatarContainer.setOnClickListener(view -> avatarPickerLauncher.launch(new String[]{"image/*"}));
        binding.actionProfileMenu.setOnClickListener(this::showPlaceholderMenu);
        binding.actionChatRoom.setOnClickListener(view ->
                startLauncherActivity(new Intent(requireContext(), LauncherChatSelectActivity.class)));
        binding.actionResourceStation.setOnClickListener(view ->
                startLauncherActivity(new Intent(requireContext(), ResourceStationActivity.class)));
        binding.actionToolbox.setOnClickListener(view ->
                startLauncherActivity(new Intent(requireContext(), LauncherToolboxActivity.class)));
        binding.actionAgent.setOnClickListener(view ->
                startLauncherActivity(new Intent(requireContext(), LauncherPendingActivity.class)));
        binding.recentRefresh.setOnRefreshListener(() -> viewModel.refreshRecentItems(true));
    }

    private void applyIconTone() {
        boolean darkMode = LauncherActivity.isLauncherDarkMode(requireContext());
        int white = android.graphics.Color.WHITE;
        applyIconTint(binding.actionProfileMenu, darkMode, white);
        applyIconTint(binding.actionChatRoomIcon, darkMode, white);
        applyIconTint(binding.actionResourceStationIcon, darkMode, white);
        applyIconTint(binding.actionToolboxIcon, darkMode, white);
        applyIconTint(binding.actionAgentIcon, darkMode, white);
    }

    private void applyIconTint(ImageView imageView, boolean tint, int color) {
        if (imageView == null) return;
        if (tint) {
            imageView.setColorFilter(color);
        } else {
            imageView.clearColorFilter();
        }
    }

    private void applyThemeStyle() {
        if (binding == null) return;
        if (LauncherActivity.isRinneTheme(requireContext())) {
            binding.homeStatsImage.setImageResource(com.yuki.yukihub.R.drawable.launcher_home_stats_rinne_bg);
            binding.homeStatsScrim.setBackground(LauncherTheme.statsScrim(requireContext()));
        } else if (LauncherActivity.isAnriTheme(requireContext())) {
            binding.homeStatsImage.setImageResource(com.yuki.yukihub.R.drawable.launcher_home_stats_bg_anri);
            binding.homeStatsScrim.setBackground(LauncherTheme.statsScrim(requireContext()));
        } else {
            binding.homeStatsImage.setImageResource(com.yuki.yukihub.R.drawable.launcher_home_stats_bg);
            binding.homeStatsScrim.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_home_stats_scrim);
        }
    }

    private void showPlaceholderMenu(View anchor) {
        if (binding == null || anchor == null) return;
        LinearLayout menu = new LinearLayout(requireContext());
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_popup_menu_bg);
        menu.setPadding(dp(7), dp(7), dp(7), dp(7));

        PopupWindow popupWindow = new PopupWindow(menu, dp(119), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        popupWindow.setElevation(dp(7));
        popupWindow.setAnimationStyle(com.yuki.yukihub.R.style.LauncherDialogAnimation);

        addMenuItem(menu, "主题管理", popupWindow, () ->
                startLauncherActivity(new Intent(requireContext(), LauncherThemeMenuActivity.class)));
        addMenuItem(menu, "色调切换", popupWindow, this::confirmToggleTone);
        addMenuItem(menu, "检查更新", popupWindow, this::checkUpdate);
        addMenuItem(menu, "建议反馈", popupWindow, this::showFeedbackOptions);
        addMenuItem(menu, "免责声明", popupWindow, this::openDisclaimer);

        popupWindow.showAsDropDown(anchor, anchor.getWidth() - dp(119), dp(5), Gravity.NO_GRAVITY);
    }

    private void addMenuItem(LinearLayout menu, String label, PopupWindow popupWindow, @Nullable Runnable action) {
        TextView item = new TextView(requireContext());
        item.setText(label);
        item.setTextSize(13);
        item.setTypeface(null, android.graphics.Typeface.BOLD);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setSingleLine(true);
        item.setPadding(dp(13), 0, dp(13), 0);
        item.setTextColor(LauncherTheme.primary(requireContext()));
        item.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        item.setOnClickListener(view -> {
            popupWindow.dismiss();
            if (action != null) {
                action.run();
            } else {
                Toast.makeText(requireContext(), label + " 功能待接入", Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
        );
        lp.setMargins(0, 0, 0, dp(5));
        menu.addView(item, lp);
    }

    private void showFeedbackOptions() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(270), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_dialog_bg);

        TextView title = new TextView(requireContext());
        title.setText("建议反馈");
        title.setGravity(Gravity.CENTER);
        title.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        addFeedbackOption(root, "GitHub 仓库", dialog, () -> openExternalUrl("https://github.com/Weiss-UltimateSavior/RinneMobile"));
        addFeedbackOption(root, "YukiHub 官网", dialog, () -> openExternalUrl("https://yukihub.kesug.com/"));
        addFeedbackOption(root, "QQ 交流群", dialog, () -> openExternalUrl("https://qun.qq.com/universal-share/share?ac=1&authKey=nZMa0s3mxxG1A0f%2BY0nAWmBYpul7FWTEDI6UWrzqb2IgKC4aDkUhvkV2AekAkW%2F1&busi_data=eyJncm91cENvZGUiOiIxNjM2MDM2MzUiLCJ0b2tlbiI6Im93eFRyY0tqNDdxK3FGQXlVZ0lhMEZGbWZWemphZnpYYW1kWWpPN1ViL3A0SkRUd1dEclMwZkM1bWI0UEYxME4iLCJ1aW4iOiIzMDg2Njc4NzU1In0%3D&data=bwoLG7XAPzqsvtfneNCQUUlu-HpX1yCn-6dkgd8ubDeBJKEPgd7wKYa6ym-EbW07Vapc3xm_o-iy0GbFHhZk5Q&svctype=4&tempid=h5_group_info"));

        TextView cancel = new TextView(requireContext());
        cancel.setText("取消");
        cancel.setGravity(Gravity.CENTER);
        cancel.setTextColor(LauncherTheme.primary(requireContext()));
        cancel.setTextSize(13);
        cancel.setTypeface(null, android.graphics.Typeface.BOLD);
        cancel.setBackground(LauncherTheme.cancelChip(requireContext()));
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        cancelLp.setMargins(0, dp(9), 0, 0);
        root.addView(cancel, cancelLp);

        window.setContentView(root);
    }

    private void addFeedbackOption(LinearLayout root, String label, AlertDialog dialog, Runnable action) {
        TextView option = new TextView(requireContext());
        option.setText(label);
        option.setGravity(Gravity.CENTER);
        option.setSingleLine(true);
        option.setTextSize(13);
        option.setTypeface(null, android.graphics.Typeface.BOLD);
        LauncherTheme.menuItem(option);
        option.setOnClickListener(view -> {
            dialog.dismiss();
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        lp.setMargins(0, dp(11), 0, 0);
        root.addView(option, lp);
    }

    private void confirmToggleTone() {
        boolean darkMode = LauncherActivity.isLauncherDarkMode(requireContext());
        String nextTone = darkMode ? "浅色模式" : "深色模式";
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(252), WindowManager.LayoutParams.WRAP_CONTENT);
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(com.yuki.yukihub.R.layout.dialog_launcher_confirm, null);
        window.setContentView(dialogView);

        TextView titleView = dialogView.findViewById(com.yuki.yukihub.R.id.dialogTitle);
        TextView messageView = dialogView.findViewById(com.yuki.yukihub.R.id.dialogMessage);
        TextView btnCancel = dialogView.findViewById(com.yuki.yukihub.R.id.dialogBtnCancel);
        TextView btnConfirm = dialogView.findViewById(com.yuki.yukihub.R.id.dialogBtnConfirm);

        titleView.setText("切换色调");
        messageView.setText("确定切换到" + nextTone + "吗？");
        LauncherTheme.dialogButtons(btnCancel, btnConfirm);
        btnCancel.setOnClickListener(view -> dialog.dismiss());
        btnConfirm.setOnClickListener(view -> {
            dialog.dismiss();
            LauncherMotion.recreateWithToneOverlay(requireActivity(), () ->
                    LauncherActivity.setLauncherDarkMode(requireContext(), !darkMode));
        });
    }

    private void setupRecentList() {
        binding.recentRefresh.setOnChildScrollUpCallback((parent, child) ->
                binding != null && binding.contentScroll.canScrollVertically(-1));
    }

    private void observeState() {
        viewModel.getLauncherState().observe(getViewLifecycleOwner(), state -> {
            binding.recentRefresh.setRefreshing(state.isRecentRefreshing());
            binding.tvAccountMode.setText(state.getAccountMode());
            binding.tvStateTitle.setText(state.getAccountName());
            binding.tvGameCount.setText(String.valueOf(state.getGameCount()));
            binding.tvTotalPlayTime.setText(state.getTotalPlayTime());
            binding.tvTodayPlayTime.setText(state.getTodayPlayTime());
            renderRecentItems(state.getRecentItems());
        });
    }

    private void renderRecentItems(List<LauncherRepository.RecentItem> items) {
        if (items == null || items.isEmpty()) {
            binding.recentEmpty.setVisibility(View.VISIBLE);
            binding.recentList.setVisibility(View.GONE);
            binding.recentList.removeAllViews();
            return;
        }
        binding.recentEmpty.setVisibility(View.GONE);
        binding.recentList.setVisibility(View.VISIBLE);
        binding.recentList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (LauncherRepository.RecentItem item : items) {
            View itemView = inflater.inflate(com.yuki.yukihub.R.layout.item_launcher_recent, binding.recentList, false);
            TextView icon = itemView.findViewById(com.yuki.yukihub.R.id.recentIcon);
            TextView title = itemView.findViewById(com.yuki.yukihub.R.id.recentTitle);
            TextView meta = itemView.findViewById(com.yuki.yukihub.R.id.recentMeta);
            TextView status = itemView.findViewById(com.yuki.yukihub.R.id.recentStatus);
            icon.setText(item.iconText);
            title.setText(item.title);
            meta.setText(item.timeAndDuration);
            status.setText(item.status);
            LauncherTheme.applyPrimaryTone(itemView);
            binding.recentList.addView(itemView);
        }
    }

    private void persistAvatarUri(Uri uri) {
        String oldAvatar = prefs().getString(KEY_PROFILE_AVATAR, "");
        try {
            requireContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some providers return a readable Uri without persistable grants.
        }
        if (oldAvatar != null && !oldAvatar.trim().isEmpty() && !oldAvatar.equals(uri.toString())) {
            try {
                requireContext().getContentResolver().releasePersistableUriPermission(Uri.parse(oldAvatar), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
        }
        prefs().edit().putString(KEY_PROFILE_AVATAR, uri.toString()).apply();
        renderAvatar();
        Toast.makeText(requireContext(), "头像已更新", Toast.LENGTH_SHORT).show();
    }

    private void renderAvatar() {
        if (binding == null) return;
        String avatar = prefs().getString(KEY_PROFILE_AVATAR, "");
        if (avatar == null || avatar.trim().isEmpty()) {
            binding.launcherAvatarImage.setImageDrawable(null);
            binding.launcherAvatarImage.setVisibility(View.GONE);
            binding.launcherAvatarInitial.setVisibility(View.VISIBLE);
            return;
        }
        try {
            binding.launcherAvatarImage.setClipToOutline(true);
            if (!SafeImageLoader.loadUri(binding.launcherAvatarImage, avatar)) {
                showDefaultAvatar();
                return;
            }
            binding.launcherAvatarImage.setVisibility(View.VISIBLE);
            binding.launcherAvatarInitial.setVisibility(View.GONE);
        } catch (Throwable throwable) {
            showDefaultAvatar();
        }
    }

    private void showDefaultAvatar() {
        if (binding == null) return;
        binding.launcherAvatarImage.setImageDrawable(null);
        binding.launcherAvatarImage.setVisibility(View.GONE);
        binding.launcherAvatarInitial.setVisibility(View.VISIBLE);
    }

    private SharedPreferences prefs() {
        return requireContext().getApplicationContext().getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE);
    }

    private void startStatsRefresh() {
        stopStatsRefresh();
        if (viewModel != null) viewModel.refreshStats();
        statsRefreshHandler.postDelayed(statsRefreshRunnable, STATS_REFRESH_INTERVAL_MS);
    }

    private void stopStatsRefresh() {
        statsRefreshHandler.removeCallbacks(statsRefreshRunnable);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void checkUpdate() {
        Toast.makeText(requireContext(), "正在检查更新...", Toast.LENGTH_SHORT).show();
        LauncherUpdateBridge.checkUpdate(requireContext(), new LauncherUpdateBridge.Callback() {
            @Override
            public void onResult(LauncherUpdateBridge.UpdateInfo info, String currentVersion, boolean hasUpdate) {
                if (!isAdded()) return;
                showUpdateResultDialog(info, currentVersion, hasUpdate, null);
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                showUpdateResultDialog(null, "", false, message);
            }
        });
    }

    private void showUpdateResultDialog(LauncherUpdateBridge.UpdateInfo info, String currentVersion, boolean hasUpdate, String error) {
        LauncherTheme.showUpdateResultDialog(requireContext(), info, currentVersion, hasUpdate, error);
    }

    private void openDisclaimer() {
        startLauncherActivity(new Intent(requireContext(), LauncherDisclaimerActivity.class));
    }

    private void startLauncherActivity(Intent intent) {
        startActivity(intent);
        LauncherMotion.applyActivityOpen(requireActivity());
    }

    private void openExternalUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable throwable) {
            Toast.makeText(requireContext(), "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    private TextView primaryDialogButton(String text, View.OnClickListener listener) {
        TextView button = new TextView(requireContext());
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(LauncherTheme.onPrimary(requireContext()));
        button.setTextSize(13);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setBackground(LauncherTheme.primaryButton(requireContext(), 20f));
        button.setOnClickListener(listener);
        return button;
    }

    private TextView secondaryDialogButton(String text, View.OnClickListener listener) {
        TextView button = new TextView(requireContext());
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(LauncherTheme.primary(requireContext()));
        button.setTextSize(13);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setBackground(LauncherTheme.cancelChip(requireContext()));
        button.setOnClickListener(listener);
        return button;
    }

    private TextView cancelDialogButton(String text, View.OnClickListener listener) {
        TextView button = new TextView(requireContext());
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_muted_color));
        button.setTextSize(13);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        lp.setMargins(0, dp(9), 0, 0);
        return lp;
    }

    private String emptyOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private String trimUpdateBody(String text, int max) {
        if (text == null) return "";
        String t = text.trim();
        if (max <= 0 || t.length() <= max) return t;
        return t.substring(0, max) + "\n...";
    }

}
