package com.apps.home;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
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
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.yuki.yukihub.databinding.FragmentLauncherHomeBinding;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;
import com.yuki.yukihub.launcherbridge.LauncherUpdateBridge;
import com.yuki.yukihub.util.AppExecutors;
import com.yuki.yukihub.util.SafeImageLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import com.apps.LauncherActivity;
import com.apps.agent.LocalAgentActivity;
import com.apps.account.LauncherDisclaimerActivity;
import com.apps.data.LauncherRepository;
import com.apps.game.LauncherSaveCategoryActivity;
import com.apps.data.LauncherViewModel;
import com.apps.settings.LauncherToolboxActivity;
import com.apps.settings.ResourceStationActivity;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherTheme;
import com.apps.theme.LauncherThemeMenuActivity;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherHomeFragment extends Fragment {
    private static final String APP_PREFS = "yukihub_prefs";
    private static final String KEY_PROFILE_AVATAR = "profile_avatar";

    private FragmentLauncherHomeBinding binding;
    private LauncherViewModel viewModel;
    private ActivityResultLauncher<PickVisualMediaRequest> avatarPickerLauncher;

    public LauncherHomeFragment() {
        avatarPickerLauncher = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri == null) return;
            copyAvatarToInternal(uri);
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
        LauncherTabletPortraitScaler.apply(binding.getRoot());
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
    }

    @Override
    public void onResume() {
        super.onResume();
        applyThemeStyle();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        applyIconTone();
        renderAvatar();
        viewModel.refreshRecentItems();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onDestroyView() {
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
        binding.launcherAvatarContainer.setOnClickListener(view -> showChangeAvatarDialog());
        binding.actionProfileMenu.setOnClickListener(this::showPlaceholderMenu);
        binding.actionSaveSlot.setOnClickListener(view ->
                startLauncherActivity(new Intent(requireContext(), LauncherSaveCategoryActivity.class)));
        binding.actionResourceStation.setOnClickListener(view -> showResourceStationDialog());
        binding.actionToolbox.setOnClickListener(view ->
                startLauncherActivity(new Intent(requireContext(), LauncherToolboxActivity.class)));
        binding.actionAgent.setOnClickListener(view ->
                LauncherDialogFactory.showStandardConfirm(requireContext(),
                        "本地智能体",
                        "功能目前处于初期测试状态，可能会有不可预料的问题",
                        "继续",
                        () -> startLauncherActivity(new Intent(requireContext(), LocalAgentActivity.class))));
        binding.recentRefresh.setOnRefreshListener(() -> {
            viewModel.refreshStats();
            viewModel.refreshRecentItems(true);
        });
    }

    private void applyIconTone() {
        boolean darkMode = LauncherActivity.isLauncherDarkMode(requireContext());
        int white = android.graphics.Color.WHITE;
        applyIconTint(binding.actionProfileMenu, darkMode, white);
        applyIconTint(binding.actionSaveSlotIcon, darkMode, white);
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
        } else if (LauncherActivity.isXinhaitianTheme(requireContext())) {
            binding.homeStatsImage.setImageResource(com.yuki.yukihub.R.drawable.launcher_home_stats_xinhaitian_bg);
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
        menu.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_white_card);
        menu.setPadding(dp(7), dp(7), dp(7), dp(7));

        PopupWindow popupWindow = new PopupWindow(menu, dp(119), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
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
        item.setGravity(Gravity.CENTER);
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

    private void showResourceStationDialog() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(252), android.view.WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_dialog_bg);

        TextView title = new TextView(requireContext());
        title.setText("资讯站");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        addResourceOption(root, "聚合搜索", "https://searchgal.top", dialog);
        addResourceOption(root, "鲲Galgame", "https://www.kungal.com", dialog);
        addResourceOption(root, "真红小站", "https://www.shinnku.com/", dialog);
        addResourceOption(root, "Touch Gal", "https://www.touchgal.ink/", dialog);

        TextView cancel = new TextView(requireContext());
        cancel.setText("取消");
        cancel.setGravity(android.view.Gravity.CENTER);
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

    private void addResourceOption(LinearLayout root, String label, String url, AlertDialog dialog) {
        TextView option = new TextView(requireContext());
        option.setText(label);
        option.setGravity(android.view.Gravity.CENTER);
        option.setSingleLine(true);
        option.setTextSize(13);
        option.setTypeface(null, android.graphics.Typeface.BOLD);
        LauncherTheme.menuItem(option);
        option.setOnClickListener(view -> {
            dialog.dismiss();
            Intent intent = new Intent(requireContext(), ResourceStationActivity.class);
            intent.putExtra("resource_url", url);
            intent.putExtra("resource_title", label);
            startLauncherActivity(intent);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        lp.setMargins(0, dp(11), 0, 0);
        root.addView(option, lp);
    }

    private void showFeedbackOptions() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(252), WindowManager.LayoutParams.WRAP_CONTENT);

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
        LauncherDialogFactory.showConfirm(
                requireContext(),
                "切换色调",
                "确定切换到" + nextTone + "吗？",
                "确定",
                () -> LauncherMotion.recreateWithToneOverlay(requireActivity(), () ->
                        LauncherActivity.setLauncherDarkMode(requireContext(), !darkMode))
        );
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
            LauncherTabletPortraitScaler.apply(itemView);
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

    private void showChangeAvatarDialog() {
        LauncherDialogFactory.showStandardConfirm(
                requireContext(),
                "修改头像",
                "是否从图库选择新头像？",
                "确定",
                () -> avatarPickerLauncher.launch(
                        new PickVisualMediaRequest.Builder()
                                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                                .build())
        );
    }

    private void copyAvatarToInternal(Uri sourceUri) {
        AppExecutors.runOnIo(() -> {
            File outFile = new File(requireContext().getFilesDir(), "launcher_avatar.jpg");
            boolean ok = false;
            try (InputStream in = requireContext().getContentResolver().openInputStream(sourceUri);
                 OutputStream out = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                ok = true;
            } catch (Throwable ignored) {
            }
            final boolean success = ok;
            final String savedUri = Uri.fromFile(outFile).toString();
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (!success) {
                    Toast.makeText(requireContext(), "头像保存失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs().edit().putString(KEY_PROFILE_AVATAR, savedUri).apply();
                // 同步头像到个人页
                requireContext().getSharedPreferences("launcher_profile_prefs", 0)
                        .edit().putString("custom_avatar_uri", savedUri).apply();
                renderAvatar();
                Toast.makeText(requireContext(), "头像已更新", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void renderAvatar() {
        if (binding == null) return;
        // 优先使用主页头像，再检查个人页头像
        String avatar = prefs().getString(KEY_PROFILE_AVATAR, "");
        if (avatar == null || avatar.trim().isEmpty()) {
            String profileAvatar = requireContext().getSharedPreferences("launcher_profile_prefs", 0)
                    .getString("custom_avatar_uri", "");
            if (profileAvatar != null && !profileAvatar.trim().isEmpty()) {
                avatar = profileAvatar;
            }
        }
        // 更新首字母
        String nickname = LauncherAuthBridge.isLoggedIn(requireContext())
                ? LauncherAuthBridge.getNickname(requireContext()) : "";
        String initial = (nickname != null && !nickname.trim().isEmpty())
                ? String.valueOf(nickname.trim().charAt(0)).toUpperCase() : "Y";
        binding.launcherAvatarInitial.setText(initial);

        if (avatar == null || avatar.trim().isEmpty()) {
            binding.launcherAvatarImage.setImageDrawable(null);
            binding.launcherAvatarImage.setVisibility(View.GONE);
            binding.launcherAvatarInitial.setVisibility(View.VISIBLE);
            return;
        }
        try {
            binding.launcherAvatarImage.setClipToOutline(true);
            if (!SafeImageLoader.loadUri(binding.launcherAvatarImage, avatar, success -> {
                if (binding == null) return;
                if (success) {
                    binding.launcherAvatarImage.setVisibility(View.VISIBLE);
                    binding.launcherAvatarInitial.setVisibility(View.GONE);
                } else {
                    showDefaultAvatar();
                }
            })) {
                showDefaultAvatar();
                return;
            }
            binding.launcherAvatarImage.setVisibility(View.GONE);
            binding.launcherAvatarInitial.setVisibility(View.VISIBLE);
        } catch (Throwable throwable) {
            showDefaultAvatar();
        }
    }

    private void showDefaultAvatar() {
        if (binding == null) return;
        String nickname = LauncherAuthBridge.isLoggedIn(requireContext())
                ? LauncherAuthBridge.getNickname(requireContext()) : "";
        String initial = (nickname != null && !nickname.trim().isEmpty())
                ? String.valueOf(nickname.trim().charAt(0)).toUpperCase() : "Y";
        binding.launcherAvatarInitial.setText(initial);
        binding.launcherAvatarImage.setImageDrawable(null);
        binding.launcherAvatarImage.setVisibility(View.GONE);
        binding.launcherAvatarInitial.setVisibility(View.VISIBLE);
    }

    private SharedPreferences prefs() {
        return requireContext().getApplicationContext().getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE);
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
