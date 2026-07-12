package com.apps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.apps.UserData.LauncherUserData;
import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.FragmentLauncherProfileBinding;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;
import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.util.TimeFormatUtil;
import com.yuki.yukihub.util.AppExecutors;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

public class LauncherProfileFragment extends Fragment {
    private static final int REQUEST_PICK_COVER = 10021;
    private static final int REQUEST_PICK_AVATAR = 10022;
    private static final String PREFS_NAME = "launcher_profile_prefs";
    private static final String KEY_CUSTOM_COVER = "custom_cover_uri";
    private static final String KEY_CUSTOM_AVATAR = "custom_avatar_uri";

    private FragmentLauncherProfileBinding binding;
    private AlertDialog loadingDialog;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLauncherProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        LauncherTabletPortraitScaler.apply(binding.getRoot());
        applySystemBarInsets();
        applyThemeTone();
        binding.actionChangeCover.setOnClickListener(v -> showChangeCoverDialog());
        binding.profileAvatar.setOnClickListener(v -> showChangeAvatarDialog());
        binding.profileInfoRow.setOnClickListener(v -> {
            if (!LauncherAuthBridge.isLoggedIn(requireContext())) {
                Toast.makeText(requireContext(), "当前未登录", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(requireContext(), LauncherProfileEditActivity.class);
            startActivity(intent);
            LauncherMotion.applyActivityOpen(requireActivity());
        });
        binding.accountSettingsRow.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), LauncherAccountSettingsActivity.class);
            startActivity(intent);
            LauncherMotion.applyActivityOpen(requireActivity());
        });
        binding.cloudRestoreRow.setOnClickListener(v -> showCloudRestoreConfirmDialog());
        binding.logoutRow.setOnClickListener(v -> showLogoutDialog());
        binding.profilePlaytimeRankCard.setOnClickListener(v -> showLeaderboardConfirmDialog());
        renderUserInfo();
        renderPlayTimeRankLoading();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshProfileRankFromServer();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) refreshProfileRankFromServer();
    }

    private void refreshProfileRankFromServer() {
        if (binding == null || !isAdded() || !isResumed() || isHidden()) return;
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
        refreshPlayTimeRank();
        refreshWeeklyPlaytimeChart();
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

    private void refreshPlayTimeRank() {
        if (binding == null || !isAdded()) return;
        if (!LauncherAuthBridge.isLoggedIn(requireContext())) {
            binding.profilePlaytimeRankValue.setText("登录后可查看");
            binding.profilePlaytimeTotalValue.setText("--");
            return;
        }
        renderPlayTimeRankLoading();
        LauncherAuthBridge.fetchMyPlayTimeRank(requireContext(), new LauncherAuthBridge.MyRankCallback() {
            @Override public void onSuccess(LauncherAuthBridge.MyRank rank) {
                if (binding == null || !isAdded()) return;
                binding.profilePlaytimeRankValue.setText(rank.rank > 0 ? "全站第 " + rank.rank + " 名" : "暂无游玩记录");
                binding.profilePlaytimeTotalValue.setText(TimeFormatUtil.playTime(rank.totalDurationMs));
            }

            @Override public void onError(String message) {
                if (binding == null || !isAdded()) return;
                binding.profilePlaytimeRankValue.setText("排名暂不可用");
                binding.profilePlaytimeTotalValue.setText("--");
            }
        });
    }

    private void renderPlayTimeRankLoading() {
        if (binding == null) return;
        if (!LauncherAuthBridge.isLoggedIn(requireContext())) {
            binding.profilePlaytimeRankValue.setText("登录后可查看");
            binding.profilePlaytimeTotalValue.setText("--");
            return;
        }
        binding.profilePlaytimeRankValue.setText("加载中…");
        binding.profilePlaytimeTotalValue.setText("--");
    }

    private void refreshWeeklyPlaytimeChart() {
        if (!isAdded()) return;
        final android.content.Context appContext = requireContext().getApplicationContext();
        AppExecutors.runOnIo(() -> {
            long[] durations = new long[7];
            String[] labels = new String[7];
            Calendar day = Calendar.getInstance();
            day.set(Calendar.HOUR_OF_DAY, 0);
            day.set(Calendar.MINUTE, 0);
            day.set(Calendar.SECOND, 0);
            day.set(Calendar.MILLISECOND, 0);
            day.add(Calendar.DAY_OF_YEAR, -6);
            SimpleDateFormat formatter = new SimpleDateFormat("E", Locale.CHINA);
            for (int i = 0; i < 7; i++) {
                long start = day.getTimeInMillis();
                long end = start + 24L * 60L * 60L * 1000L;
                long total = 0L;
                for (Long duration : LauncherRepositoryBridge.getPlayDurationsBetween(appContext, start, end).values()) {
                    if (duration != null) total += Math.max(0L, duration);
                }
                durations[i] = total;
                labels[i] = formatter.format(day.getTime());
                day.add(Calendar.DAY_OF_YEAR, 1);
            }
            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                if (binding != null) binding.profileWeeklyPlaytimeChart.setDailyDurations(durations, labels);
            });
        });
    }

    private void showCloudRestoreConfirmDialog() {
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
        title.setText("云端恢复");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(requireContext());
        message.setText("将从云端下载配置并覆盖当前设置，确定恢复吗？");
        message.setGravity(android.view.Gravity.CENTER);
        message.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_muted_color));
        message.setTextSize(12);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dp(13), 0, 0);
        root.addView(message, msgLp);

        TextView confirm = new TextView(requireContext());
        confirm.setText("确定恢复");
        confirm.setGravity(android.view.Gravity.CENTER);
        LauncherTheme.primaryButton(confirm);
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            performCloudRestore();
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

    private void showLeaderboardConfirmDialog() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(280), WindowManager.LayoutParams.WRAP_CONTENT);
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_launcher_confirm, null);
        window.setContentView(view);
        ((TextView) view.findViewById(R.id.dialogTitle)).setText("全站排行榜");
        ((TextView) view.findViewById(R.id.dialogMessage)).setText("是否查看全站游玩时长排行榜？");
        TextView cancel = view.findViewById(R.id.dialogBtnCancel);
        TextView confirm = view.findViewById(R.id.dialogBtnConfirm);
        LauncherTheme.dialogButtons(cancel, confirm);
        cancel.setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(new Intent(requireContext(), LauncherLeaderboardActivity.class));
            LauncherMotion.applyActivityOpen(requireActivity());
        });
    }

    private void performCloudRestore() {
        loadingDialog = showLoadingDialog("正在恢复配置...", "请不要关闭应用及网络，否则可能导致配置出错");

        LauncherAuthBridge.fetchConfig(requireContext(), new LauncherAuthBridge.ConfigCallback() {
            @Override
            public void onSuccess(String configJson) {
                LauncherAuthBridge.fetchPlayData(requireContext(), new LauncherAuthBridge.PlayDataCallback() {
                    @Override
                    public void onSuccess(String playSql) {
                        // 直接导入云端设置
                        boolean settingsOk = LauncherUserData.importSettingsFromJson(requireContext(), configJson);
                        // 直接导入云端游玩记录
                        boolean playOk = LauncherUserData.importCloudPlayData(requireContext(), playSql);
                        dismissLoadingDialog();
                        if (settingsOk && playOk) {
                            new ViewModelProvider(requireActivity()).get(LauncherViewModel.class).refresh();
                            showResultDialog("恢复成功", "配置已从云端恢复，即将重启生效");
                        } else {
                            showResultDialog("部分恢复失败", settingsOk ? "设置已恢复，游玩记录部分导入失败" : "设置恢复失败");
                        }
                    }

                    @Override
                    public void onError(String message) {
                        // 仅恢复设置
                        boolean ok = LauncherUserData.importSettingsFromJson(requireContext(), configJson);
                        dismissLoadingDialog();
                        showResultDialog(ok ? "部分恢复成功" : "恢复失败", ok ? "设置已恢复，游玩记录获取失败：" + message : message);
                    }
                });
            }

            @Override
            public void onError(String message) {
                dismissLoadingDialog();
                showResultDialog("恢复失败", message);
            }
        });
    }

    private AlertDialog showLoadingDialog(String titleText, String hintText) {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.setCancelable(false);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return dialog;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(270), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);

        TextView title = new TextView(requireContext());
        title.setText(titleText);
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ProgressBar progressBar = new ProgressBar(requireContext());
        progressBar.setIndeterminate(true);
        progressBar.getIndeterminateDrawable().setColorFilter(
                LauncherTheme.primary(requireContext()), android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        pbLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        pbLp.setMargins(0, dp(14), 0, 0);
        root.addView(progressBar, pbLp);

        TextView hint = new TextView(requireContext());
        hint.setText(hintText);
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_muted_color));
        hint.setTextSize(11);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, dp(10), 0, 0);
        root.addView(hint, hintLp);

        window.setContentView(root);
        return dialog;
    }

    private void dismissLoadingDialog() {
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
            loadingDialog = null;
        }
    }

    private void showResultDialog(String title, String message) {
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

        TextView titleView = new TextView(requireContext());
        titleView.setText(title);
        titleView.setGravity(android.view.Gravity.CENTER);
        titleView.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_color));
        titleView.setTextSize(16);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(titleView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView msgView = new TextView(requireContext());
        msgView.setText(message);
        msgView.setGravity(android.view.Gravity.CENTER);
        msgView.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_muted_color));
        msgView.setTextSize(12);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dp(13), 0, 0);
        root.addView(msgView, msgLp);

        TextView okBtn = new TextView(requireContext());
        okBtn.setText("知道了");
        okBtn.setGravity(android.view.Gravity.CENTER);
        LauncherTheme.primaryButton(okBtn);
        okBtn.setOnClickListener(v -> {
            dialog.dismiss();
            // 数据已导入，直接重启使设置生效（不再重复 importAll）
            LauncherUserData.restartLauncher(requireActivity());
        });
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        okLp.setMargins(0, dp(11), 0, 0);
        root.addView(okBtn, okLp);

        window.setContentView(root);
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

    private void showChangeCoverDialog() {
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
        title.setText("更换背景");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(requireContext());
        message.setText("是否从图库选择新的背景图片？");
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
            startActivityForResult(intent, REQUEST_PICK_COVER);
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
        binding.profilePlaytimeTotalIcon.setImageTintList(ColorStateList.valueOf(LauncherTheme.primary(requireContext())));
        binding.profileWeeklyPlaytimeChart.invalidate();
    }

    private void applyProfileBgImage() {
        if (binding == null) return;
        String customUri = requireContext().getSharedPreferences(PREFS_NAME, 0)
                .getString(KEY_CUSTOM_COVER, null);
        if (customUri != null) {
            try {
                binding.profileBgImage.setImageURI(Uri.parse(customUri));
            } catch (SecurityException e) {
                binding.profileBgImage.setImageResource(R.drawable.launcher_home_stats_bg);
            }
            return;
        }
        if (LauncherActivity.isRinneTheme(requireContext())) {
            binding.profileBgImage.setImageResource(R.drawable.launcher_home_stats_rinne_bg);
        } else if (LauncherActivity.isAnriTheme(requireContext())) {
            binding.profileBgImage.setImageResource(R.drawable.launcher_home_stats_bg_anri);
        } else if (LauncherActivity.isXinhaitianTheme(requireContext())) {
            binding.profileBgImage.setImageResource(R.drawable.launcher_home_stats_xinhaitian_bg);
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
            try {
                binding.profileAvatar.setImageURI(Uri.parse(customAvatarUri));
            } catch (SecurityException e) {
                binding.profileAvatar.setImageResource(R.drawable.launcher_default_avatar);
            }
            return;
        }
        // 再检查主页头像
        String homeAvatar = requireContext().getSharedPreferences("yukihub_prefs", 0)
                .getString("profile_avatar", null);
        if (homeAvatar != null && !homeAvatar.trim().isEmpty()) {
            try {
                binding.profileAvatar.setImageURI(Uri.parse(homeAvatar));
            } catch (SecurityException e) {
                binding.profileAvatar.setImageResource(R.drawable.launcher_default_avatar);
            }
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
