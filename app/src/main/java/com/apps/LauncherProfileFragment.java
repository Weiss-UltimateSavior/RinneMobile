package com.apps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.FragmentLauncherProfileBinding;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;

public class LauncherProfileFragment extends Fragment {
    private static final int REQUEST_PICK_COVER = 10021;
    private static final int REQUEST_PICK_AVATAR = 10022;
    private static final String PREFS_NAME = "launcher_profile_prefs";
    private static final String KEY_CUSTOM_COVER = "custom_cover_uri";
    private static final String KEY_CUSTOM_AVATAR = "custom_avatar_uri";

    private FragmentLauncherProfileBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLauncherProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        applySystemBarInsets();
        applyThemeTone();
        binding.actionChangeCover.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, REQUEST_PICK_COVER);
        });
        binding.profileAvatar.setOnClickListener(v -> showChangeAvatarDialog());
        binding.logoutRow.setOnClickListener(v -> showLogoutDialog());
        renderUserInfo();
    }

    @Override
    public void onResume() {
        super.onResume();
        renderUserInfo();
        // 如果已登录，刷新用户信息
        if (LauncherAuthBridge.isLoggedIn(requireContext())) {
            LauncherAuthBridge.fetchUserInfo(requireContext(), new LauncherAuthBridge.UserInfoCallback() {
                @Override
                public void onSuccess(String nickname, String email) {
                    if (binding != null) renderUserInfo();
                }

                @Override
                public void onError(String message) {
                    // 静默处理，使用缓存数据
                }
            });
        }
    }

    private void renderUserInfo() {
        if (binding == null) return;
        if (LauncherAuthBridge.isLoggedIn(requireContext())) {
            String nickname = LauncherAuthBridge.getNickname(requireContext());
            String email = LauncherAuthBridge.getEmail(requireContext());
            binding.profileNickname.setText(nickname != null && !nickname.isEmpty() ? nickname : "在线用户");
            binding.profileEmail.setText(email != null ? email : "");
            binding.profileEmail.setVisibility(email != null && !email.isEmpty() ? View.VISIBLE : View.GONE);
        } else {
            binding.profileNickname.setText("本地用户");
            binding.profileEmail.setVisibility(View.GONE);
        }
    }

    private void showLogoutDialog() {
        if (!LauncherAuthBridge.isLoggedIn(requireContext())) {
            Toast.makeText(requireContext(), "当前未登录", Toast.LENGTH_SHORT).show();
            return;
        }
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
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);

        TextView title = new TextView(requireContext());
        title.setText("退出登录");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        String nickname = LauncherAuthBridge.getNickname(requireContext());
        TextView message = new TextView(requireContext());
        message.setText("确定退出当前账户" + (nickname != null && !nickname.isEmpty() ? "「" + nickname + "」" : "") + "吗？");
        message.setGravity(android.view.Gravity.CENTER);
        message.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_muted_color));
        message.setTextSize(12);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dp(13), 0, 0);
        root.addView(message, msgLp);

        // 保存邮箱用于下次自动填充登录
        String savedEmail = LauncherAuthBridge.getEmail(requireContext());

        TextView confirm = new TextView(requireContext());
        confirm.setText("确定退出");
        confirm.setGravity(android.view.Gravity.CENTER);
        LauncherTheme.dangerButton(confirm);
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            performLogout(savedEmail);
        });
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        confirmLp.setMargins(0, dp(11), 0, 0);
        root.addView(confirm, confirmLp);

        TextView cancel = new TextView(requireContext());
        cancel.setText("取消");
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setTextColor(LauncherTheme.primary(requireContext()));
        cancel.setTextSize(13);
        cancel.setTypeface(null, android.graphics.Typeface.BOLD);
        cancel.setBackground(LauncherTheme.cancelChip(requireContext()));
        cancel.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        cancelLp.setMargins(0, dp(9), 0, 0);
        root.addView(cancel, cancelLp);

        window.setContentView(root);
    }

    private void performLogout(String savedEmail) {
        // 清除 token 和用户信息
        LauncherAuthBridge.clearToken(requireContext());
        // 保留邮箱到登录页输入框的缓存
        if (savedEmail != null && !savedEmail.trim().isEmpty()) {
            requireContext().getSharedPreferences("yukihub_prefs", 0)
                    .edit().putString("auth_saved_email", savedEmail).apply();
        }
        Toast.makeText(requireContext(), "已退出登录", Toast.LENGTH_SHORT).show();
        // 返回登录页
        if (binding == null) return;
        getParentFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.launcher_fragment_enter,
                        R.anim.launcher_fragment_exit,
                        R.anim.launcher_fragment_enter,
                        R.anim.launcher_fragment_exit
                )
                .replace(R.id.launcherFragmentContainer, new LauncherAccountFragment(), "launcher_ACCOUNT")
                .commit();
    }

    private void showChangeAvatarDialog() {
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
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);

        TextView title = new TextView(requireContext());
        title.setText("修改头像");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(requireContext());
        message.setText("是否从图库选择新头像？");
        message.setGravity(android.view.Gravity.CENTER);
        message.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_muted_color));
        message.setTextSize(12);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dp(13), 0, 0);
        root.addView(message, msgLp);

        TextView confirm = new TextView(requireContext());
        confirm.setText("确定");
        confirm.setGravity(android.view.Gravity.CENTER);
        LauncherTheme.primaryButton(confirm);
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, REQUEST_PICK_AVATAR);
        });
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        confirmLp.setMargins(0, dp(11), 0, 0);
        root.addView(confirm, confirmLp);

        TextView cancel = new TextView(requireContext());
        cancel.setText("取消");
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setTextColor(LauncherTheme.primary(requireContext()));
        cancel.setTextSize(13);
        cancel.setTypeface(null, android.graphics.Typeface.BOLD);
        cancel.setBackground(LauncherTheme.cancelChip(requireContext()));
        cancel.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        cancelLp.setMargins(0, dp(9), 0, 0);
        root.addView(cancel, cancelLp);

        window.setContentView(root);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }

        if (requestCode == REQUEST_PICK_COVER) {
            try {
                requireContext().getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
            requireContext().getSharedPreferences(PREFS_NAME, 0)
                    .edit().putString(KEY_CUSTOM_COVER, uri.toString()).apply();
            if (binding != null) {
                binding.profileBgImage.setImageURI(uri);
            }
        } else if (requestCode == REQUEST_PICK_AVATAR) {
            try {
                requireContext().getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
            requireContext().getSharedPreferences(PREFS_NAME, 0)
                    .edit().putString(KEY_CUSTOM_AVATAR, uri.toString()).apply();
            if (binding != null) {
                binding.profileAvatar.setImageURI(uri);
            }
            // 同步头像到主页
            syncAvatarToHome(uri.toString());
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onDestroyView() {
        if (binding != null) {
            binding.getRoot().setOnApplyWindowInsetsListener(null);
        }
        super.onDestroyView();
        binding = null;
    }

    private int dp(int value) {
        return (int) (value * requireContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    private void applySystemBarInsets() {
        FragmentLauncherProfileBinding currentBinding = binding;
        int originalLeft = currentBinding.profileScroll.getPaddingLeft();
        int originalTop = currentBinding.profileScroll.getPaddingTop();
        int originalRight = currentBinding.profileScroll.getPaddingRight();
        int originalBottom = currentBinding.profileScroll.getPaddingBottom();
        int originalHeaderLeft = currentBinding.profileHeader.getPaddingLeft();
        int originalHeaderTop = currentBinding.profileHeader.getPaddingTop();
        int originalHeaderRight = currentBinding.profileHeader.getPaddingRight();
        int originalHeaderBottom = currentBinding.profileHeader.getPaddingBottom();

        currentBinding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            currentBinding.profileScroll.setPadding(
                    originalLeft,
                    originalTop,
                    originalRight,
                    originalBottom
            );
            currentBinding.profileHeader.setPadding(
                    originalHeaderLeft,
                    originalHeaderTop + insets.getSystemWindowInsetTop(),
                    originalHeaderRight,
                    originalHeaderBottom
            );
            return insets;
        });
        currentBinding.getRoot().requestApplyInsets();
    }

    private void applyThemeTone() {
        if (binding == null) return;
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        applyProfileBgImage();
        applyAvatarImage();
        boolean darkMode = LauncherActivity.isLauncherDarkMode(requireContext());
        if (darkMode) {
            binding.actionChangeCover.setColorFilter(android.graphics.Color.WHITE);
        } else {
            binding.actionChangeCover.clearColorFilter();
        }
        for (int i = 0; i < binding.profileActionList.getChildCount(); i++) {
            View row = binding.profileActionList.getChildAt(i);
            if (!(row instanceof ViewGroup)) continue;
            View icon = ((ViewGroup) row).getChildAt(0);
            if (icon instanceof TextView) {
                icon.setBackground(LauncherTheme.circle(requireContext()));
                ((TextView) icon).setTextColor(LauncherTheme.onPrimary(requireContext()));
            }
        }
    }

    private void applyProfileBgImage() {
        if (binding == null) return;
        String customUri = requireContext().getSharedPreferences(PREFS_NAME, 0)
                .getString(KEY_CUSTOM_COVER, null);
        if (customUri != null) {
            binding.profileBgImage.setImageURI(Uri.parse(customUri));
            return;
        }
        if (LauncherActivity.isRinneTheme(requireContext())) {
            binding.profileBgImage.setImageResource(R.drawable.launcher_home_stats_rinne_bg);
        } else if (LauncherActivity.isAnriTheme(requireContext())) {
            binding.profileBgImage.setImageResource(R.drawable.launcher_home_stats_bg_anri);
        } else {
            binding.profileBgImage.setImageResource(R.drawable.launcher_home_stats_bg);
        }
    }

    private void applyAvatarImage() {
        if (binding == null) return;
        // 先检查个人页面自定义头像
        String customAvatarUri = requireContext().getSharedPreferences(PREFS_NAME, 0)
                .getString(KEY_CUSTOM_AVATAR, null);
        if (customAvatarUri != null) {
            binding.profileAvatar.setImageURI(Uri.parse(customAvatarUri));
            return;
        }
        // 再检查主页头像
        String homeAvatar = requireContext().getSharedPreferences("yukihub_prefs", 0)
                .getString("profile_avatar", null);
        if (homeAvatar != null && !homeAvatar.trim().isEmpty()) {
            binding.profileAvatar.setImageURI(Uri.parse(homeAvatar));
            return;
        }
        // 默认头像
        binding.profileAvatar.setImageResource(R.drawable.launcher_default_avatar);
    }

    private void syncAvatarToHome(String avatarUri) {
        // 将个人页头像同步到主页的 SharedPreferences
        requireContext().getSharedPreferences("yukihub_prefs", 0)
                .edit().putString("profile_avatar", avatarUri).apply();
    }
}
