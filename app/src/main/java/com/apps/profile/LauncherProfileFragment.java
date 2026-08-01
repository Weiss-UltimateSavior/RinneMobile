package com.apps.profile;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.core.userdata.LauncherUserData;
import com.apps.LauncherNavigationMetricsKt;
import com.apps.LauncherPreferences;
import com.apps.LauncherThemeStyle;
import com.core.R;
import com.core.databinding.FragmentLauncherProfileBinding;
import com.core.launcherbridge.LauncherAuthBridge;
import com.core.launcherbridge.LauncherRepositoryBridge;
import com.core.launcherbridge.UserInfoCallback;
import com.core.launcherbridge.MyRankCallback;
import com.core.launcherbridge.MyRank;
import com.core.launcherbridge.ConfigCallback;
import com.core.launcherbridge.PlayDataCallback;
import com.core.util.TimeFormatUtil;
import com.core.util.AppExecutors;
import com.core.util.SafeImageLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Calendar;
import java.util.Map;
import com.apps.LauncherActivity;
import com.apps.account.LauncherAccountFragment;
import com.apps.account.LauncherAccountSettingsActivity;
import com.apps.data.LauncherViewModel;
import com.apps.chat.LauncherChatSelectActivity;
import com.apps.leaderboard.LauncherLeaderboardActivity;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.AvatarCropActivity;
import com.apps.widget.LauncherTabletPortraitScaler;
import com.apps.translation.TranslationSettingActivity;

public class LauncherProfileFragment extends Fragment {
    private static final String PREFS_NAME = "launcher_profile_prefs";
    private static final String KEY_CUSTOM_COVER = "custom_cover_uri";
    private static final String KEY_CUSTOM_AVATAR = "custom_avatar_uri";

    private FragmentLauncherProfileBinding binding;
    private AlertDialog loadingDialog;

    private final ActivityResultLauncher<PickVisualMediaRequest> avatarPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri == null) return;
                startCrop(uri);
            });
    private final ActivityResultLauncher<PickVisualMediaRequest> coverPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri == null) return;
                copyImageToInternal(uri, "launcher_cover.jpg", KEY_CUSTOM_COVER, this::applyProfileBgImage, false);
            });
    private final ActivityResultLauncher<Intent> cropLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                    String outputUri = result.getData().getStringExtra(AvatarCropActivity.EXTRA_OUTPUT_URI);
                    if (outputUri != null && !outputUri.isEmpty()) {
                        copyImageToInternal(Uri.parse(outputUri), "launcher_avatar.jpg", KEY_CUSTOM_AVATAR, this::applyAvatarImage, true);
                    }
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLauncherProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    protected final void bindProfileRoot(@NonNull View root) {
        binding = FragmentLauncherProfileBinding.bind(root);
    }

    protected boolean usePortraitProfileScaler() {
        return true;
    }

    protected boolean applyProfileSystemBarInsets() {
        return true;
    }

    protected int profileFragmentContainerId() {
        return R.id.launcherFragmentContainer;
    }

    protected Fragment createAccountFragment() {
        return new LauncherAccountFragment();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (usePortraitProfileScaler()) {
            LauncherTabletPortraitScaler.apply(binding.getRoot());
        }
        if (applyProfileSystemBarInsets()) {
            applySystemBarInsets();
        }
        applyThemeTone();
        binding.actionChangeCover.setOnClickListener(v -> showChangeCoverDialog());
        binding.profileAvatar.setOnClickListener(v -> showChangeAvatarDialog());
        binding.profileInfoRow.setOnClickListener(v -> {
            if (!LauncherAuthBridge.isLoggedIn(requireContext())) {
                Toast.makeText(requireContext(), R.string.profile_not_logged_in, Toast.LENGTH_SHORT).show();
                return;
            }
            openProfileEdit();
        });
        binding.accountSettingsRow.setOnClickListener(v -> openAccountSettings());
        binding.chatRoomRow.setOnClickListener(v -> openChatRoom());
        binding.moduleCompatibilityRow.setOnClickListener(v -> {
            if (hasApplicationListPermission()) {
                openModuleCompatibility();
                return;
            }
            LauncherDialogFactory.showConfirm(requireContext(),
                    getString(R.string.profile_module_permission_title),
                    getString(R.string.profile_module_permission_message),
                    getString(R.string.settings_confirm),
                    () -> Toast.makeText(requireContext(),
                            R.string.profile_app_list_permission_missing, Toast.LENGTH_SHORT).show());
        });
        binding.cloudRestoreRow.setOnClickListener(v -> showCloudRestoreConfirmDialog());
        binding.logoutRow.setOnClickListener(v -> showLogoutDialog());
        binding.translationRow.setOnClickListener(v -> openTranslationSettings());
        binding.profilePlaytimeRankCard.setOnClickListener(v -> showLeaderboardConfirmDialog());
        renderUserInfo();
        renderPlayTimeRankLoading();
    }

    protected void openProfileEdit() {
        startActivity(new Intent(requireContext(), LauncherProfileEditActivity.class));
        LauncherMotion.applyActivityOpen(requireActivity());
    }

    protected void openAccountSettings() {
        startActivity(new Intent(requireContext(), LauncherAccountSettingsActivity.class));
        LauncherMotion.applyActivityOpen(requireActivity());
    }

    protected void openChatRoom() {
        startActivity(new Intent(requireContext(), LauncherChatSelectActivity.class));
        LauncherMotion.applyActivityOpen(requireActivity());
    }

    protected void openModuleCompatibility() {
        startActivity(new Intent(requireContext(), LauncherModuleCompatibilityActivity.class));
        LauncherMotion.applyActivityOpen(requireActivity());
    }

    protected void openTranslationSettings() {
        startActivity(new Intent(requireContext(), TranslationSettingActivity.class));
        LauncherMotion.applyActivityOpen(requireActivity());
    }

    protected void openLeaderboard() {
        startActivity(new Intent(requireContext(), LauncherLeaderboardActivity.class));
        LauncherMotion.applyActivityOpen(requireActivity());
    }

    private boolean hasApplicationListPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true;
        return requireContext().getPackageManager().checkPermission(
                "android.permission.QUERY_ALL_PACKAGES", requireContext().getPackageName())
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onResume() {
        super.onResume();
        LauncherNavigationMetricsKt.refreshNavigationOverlayInsets(this);
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
            LauncherAuthBridge.fetchUserInfo(requireContext(), new UserInfoCallback() {
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
            binding.profileNickname.setText(nickname != null && !nickname.isEmpty()
                    ? nickname : getString(R.string.profile_online_user));
            binding.profileEmail.setText(email != null ? email : "");
            binding.profileEmail.setVisibility(email != null && !email.isEmpty() ? View.VISIBLE : View.GONE);
        } else {
            binding.profileNickname.setText(R.string.profile_local_user);
            binding.profileEmail.setVisibility(View.GONE);
        }
    }

    private void refreshPlayTimeRank() {
        if (binding == null || !isAdded()) return;
        if (!LauncherAuthBridge.isLoggedIn(requireContext())) {
            binding.profilePlaytimeRankValue.setText(R.string.profile_sign_in_to_view);
            binding.profilePlaytimeTotalValue.setText("--");
            return;
        }
        renderPlayTimeRankLoading();
        LauncherAuthBridge.fetchMyPlayTimeRank(requireContext(), new MyRankCallback() {
            @Override public void onSuccess(MyRank rank) {
                if (binding == null || !isAdded()) return;
                binding.profilePlaytimeRankValue.setText(rank.rank > 0
                        ? getString(R.string.profile_site_rank, rank.rank)
                        : getString(R.string.profile_no_play_records));
                binding.profilePlaytimeTotalValue.setText(TimeFormatUtil.playTime(rank.totalDurationMs));
            }

            @Override public void onError(String message) {
                if (binding == null || !isAdded()) return;
                binding.profilePlaytimeRankValue.setText(R.string.profile_rank_unavailable);
                binding.profilePlaytimeTotalValue.setText("--");
            }
        });
    }

    private void renderPlayTimeRankLoading() {
        if (binding == null) return;
        if (!LauncherAuthBridge.isLoggedIn(requireContext())) {
            binding.profilePlaytimeRankValue.setText(R.string.profile_sign_in_to_view);
            binding.profilePlaytimeTotalValue.setText("--");
            return;
        }
        binding.profilePlaytimeRankValue.setText(R.string.settings_loading);
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
            for (int i = 0; i < 7; i++) {
                long start = day.getTimeInMillis();
                long end = start + 24L * 60L * 60L * 1000L;
                long total = 0L;
                for (Long duration : LauncherRepositoryBridge.getPlayDurationsBetween(appContext, start, end).values()) {
                    if (duration != null) total += Math.max(0L, duration);
                }
                durations[i] = total;
                labels[i] = TimeFormatUtil.weekDayLabel(day.getTimeInMillis());
                day.add(Calendar.DAY_OF_YEAR, 1);
            }
            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                if (binding != null) binding.profileWeeklyPlaytimeChart.setDailyDurations(durations, labels);
            });
        });
    }

    private void showCloudRestoreConfirmDialog() {
        if (!LauncherAuthBridge.isLoggedIn(requireContext())) {
            Toast.makeText(requireContext(), R.string.profile_not_logged_in, Toast.LENGTH_SHORT).show();
            return;
        }
        LauncherDialogFactory.showStandardConfirm(
                requireContext(),
                getString(R.string.profile_restore_configuration),
                getString(R.string.profile_restore_configuration_message),
                getString(R.string.profile_confirm_restore),
                this::performCloudRestore
        );
    }

    private void showLeaderboardConfirmDialog() {
        LauncherDialogFactory.showConfirm(
                requireContext(),
                getString(R.string.profile_site_leaderboard),
                getString(R.string.profile_site_leaderboard_message),
                getString(R.string.settings_confirm),
                () -> openLeaderboard());
    }

    private void performCloudRestore() {
        loadingDialog = showLoadingDialog(getString(R.string.profile_restoring_configuration),
                getString(R.string.profile_restore_in_progress_message));

        LauncherAuthBridge.fetchConfig(requireContext(), new ConfigCallback() {
            @Override
            public void onSuccess(String configJson) {
                LauncherAuthBridge.fetchPlayData(requireContext(), new PlayDataCallback() {
                    @Override
                    public void onSuccess(String playSql) {
                        // 直接导入云端设置
                        boolean settingsOk = LauncherUserData.importSettingsFromJson(requireContext(), configJson);
                        // 直接导入云端游玩记录
                        boolean playOk = LauncherUserData.importCloudPlayData(requireContext(), playSql);
                        dismissLoadingDialog();
                        if (settingsOk && playOk) {
                            new ViewModelProvider(requireActivity()).get(LauncherViewModel.class).refresh();
                            showResultDialog(getString(R.string.profile_restore_success),
                                    getString(R.string.profile_restore_success_message));
                        } else {
                            showResultDialog(getString(R.string.profile_partial_restore_failed),
                                    getString(settingsOk
                                            ? R.string.profile_play_records_partial_import_failed
                                            : R.string.profile_settings_restore_failed));
                        }
                    }

                    @Override
                    public void onError(String message) {
                        // 仅恢复设置
                        boolean ok = LauncherUserData.importSettingsFromJson(requireContext(), configJson);
                        dismissLoadingDialog();
                        showResultDialog(getString(ok
                                        ? R.string.profile_partial_restore_success
                                        : R.string.profile_restore_failed),
                                ok ? getString(R.string.profile_play_records_fetch_failed, message)
                                        : message);
                    }
                });
            }

            @Override
            public void onError(String message) {
                dismissLoadingDialog();
                showResultDialog(getString(R.string.profile_restore_failed), message);
            }
        });
    }

    private AlertDialog showLoadingDialog(String titleText, String hintText) {
        return LauncherDialogFactory.showLoading(requireContext(), titleText, hintText);
    }

    private void dismissLoadingDialog() {
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
            loadingDialog = null;
        }
    }

    private void showResultDialog(String title, String message) {
        LauncherDialogFactory.showInfo(
                requireContext(),
                title,
                message,
                () -> LauncherUserData.restartLauncher(requireActivity())
        );
    }

    private void showLogoutDialog() {
        if (!LauncherAuthBridge.isLoggedIn(requireContext())) {
            Toast.makeText(requireContext(), R.string.profile_not_logged_in, Toast.LENGTH_SHORT).show();
            return;
        }
        final String nickname = LauncherAuthBridge.getNickname(requireContext());
        final String savedEmail = LauncherAuthBridge.getEmail(requireContext());
        LauncherDialogFactory.showDangerConfirm(
                requireContext(),
                getString(R.string.profile_logout),
                nickname != null && !nickname.isEmpty()
                        ? getString(R.string.profile_logout_named_message, nickname)
                        : getString(R.string.profile_logout_message),
                getString(R.string.profile_confirm_logout),
                () -> performLogout(savedEmail));
    }

    private void performLogout(String savedEmail) {
        // 清除 token 和用户信息
        LauncherAuthBridge.clearToken(requireContext());
        // 保留邮箱到登录页输入框的缓存
        if (savedEmail != null && !savedEmail.trim().isEmpty()) {
            requireContext().getSharedPreferences(LauncherPreferences.APP_PREFS, 0)
                    .edit().putString("auth_saved_email", savedEmail).apply();
        }
        Toast.makeText(requireContext(), R.string.profile_logged_out, Toast.LENGTH_SHORT).show();
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
                .replace(profileFragmentContainerId(), createAccountFragment(), "launcher_ACCOUNT")
                .commit();
    }

    private void showChangeCoverDialog() {
        LauncherDialogFactory.showStandardConfirm(
                requireContext(),
                getString(R.string.profile_change_background),
                getString(R.string.profile_choose_background_message),
                getString(R.string.settings_confirm),
                () -> coverPickerLauncher.launch(
                        new PickVisualMediaRequest.Builder()
                                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                                .build())
        );
    }

    private void showChangeAvatarDialog() {
        LauncherDialogFactory.showStandardConfirm(
                requireContext(),
                getString(R.string.profile_change_avatar),
                getString(R.string.profile_choose_avatar_message),
                getString(R.string.settings_confirm),
                () -> avatarPickerLauncher.launch(
                        new PickVisualMediaRequest.Builder()
                                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                                .build())
        );
    }

    private void startCrop(Uri sourceUri) {
        Intent intent = new Intent(requireContext(), AvatarCropActivity.class);
        intent.putExtra(AvatarCropActivity.EXTRA_INPUT_URI, sourceUri.toString());
        cropLauncher.launch(intent);
    }

    private void copyImageToInternal(Uri sourceUri, String fileName, String prefsKey, Runnable onDone, boolean syncToHome) {
        AppExecutors.runOnIo(() -> {
            File outFile = new File(requireContext().getFilesDir(), fileName);
            // 先写临时文件，成功后再原子替换正式文件，避免复制失败时破坏旧头像/封面
            File tempFile = new File(outFile.getParentFile(), fileName + ".tmp");
            boolean ok = false;
            try (InputStream in = requireContext().getContentResolver().openInputStream(sourceUri);
                 OutputStream out = new FileOutputStream(tempFile)) {
                if (in == null) {
                    Log.w("LauncherProfile", "copyImageToInternal openInputStream null: " + fileName);
                } else {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    ok = true;
                }
            } catch (IOException | SecurityException e) {
                // openInputStream/复制/close 可能抛 IOException 或权限缺失 SecurityException；
                // 进入 catch 即视为失败，恢复 ok=false，由调用方展示“图片保存失败”提示。
                Log.w("LauncherProfile", "copyImageToInternal failed: " + fileName, e);
                ok = false;
            }
            // 用 Files.move 原子替换正式文件；失败时旧文件不受影响，仅清理临时文件
            if (ok) {
                try {
                    Files.move(tempFile.toPath(), outFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Log.w("LauncherProfile", "copyImageToInternal move failed: " + fileName, e);
                    ok = false;
                }
            }
            if (!ok && tempFile.exists()) {
                tempFile.delete();
            }
            final boolean success = ok;
            final String savedUri = Uri.fromFile(outFile).toString();
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (!success) {
                    Toast.makeText(requireContext(), R.string.profile_image_save_failed,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                requireContext().getSharedPreferences(PREFS_NAME, 0)
                        .edit().putString(prefsKey, savedUri).apply();
                if (syncToHome) {
                    syncAvatarToHome(savedUri);
                    SafeImageLoader.invalidateUri(savedUri);
                }
                onDone.run();
                Toast.makeText(requireContext(), R.string.profile_image_updated,
                        Toast.LENGTH_SHORT).show();
            });
        });
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
                    LauncherNavigationMetricsKt.navigationOverlayBottomPadding(this, originalBottom)
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
        LauncherTheme.applyCardCircleIcon(binding.actionChangeCover, requireContext());
        for (int i = 0; i < binding.profileActionList.getChildCount(); i++) {
            View actionContainer = binding.profileActionList.getChildAt(i);
            if (!(actionContainer instanceof ViewGroup)) continue;
            ViewGroup group = (ViewGroup) actionContainer;
            View firstChild = group.getChildCount() > 0 ? group.getChildAt(0) : null;
            if (firstChild instanceof TextView || firstChild instanceof ImageView) {
                // Single-column manage row (icon + title + arrow).
                LauncherTheme.styleManageRow(actionContainer);
                continue;
            }
            // Two-column containers: the actual manage rows are the container's
            // children rather than direct children of profileActionList.
            for (int j = 0; j < group.getChildCount(); j++) {
                LauncherTheme.styleManageRow(group.getChildAt(j));
            }
        }
        binding.profilePlaytimeTotalIcon.setImageTintList(ColorStateList.valueOf(LauncherTheme.primary(requireContext())));
        binding.profileWeeklyPlaytimeChart.invalidate();
    }

    private void applyProfileBgImage() {
        if (binding == null) return;
        applyDefaultProfileBgImage();
        String customUri = requireContext().getSharedPreferences(PREFS_NAME, 0)
                .getString(KEY_CUSTOM_COVER, null);
        if (customUri == null) return;

        Uri uri = Uri.parse(customUri);
        if (!isReadableImageUri(uri)) {
            requireContext().getSharedPreferences(PREFS_NAME, 0)
                    .edit().remove(KEY_CUSTOM_COVER).apply();
            return;
        }

        binding.profileBgImage.setImageURI(uri);
    }

    private void applyDefaultProfileBgImage() {
        binding.profileBgImage.setImageResource(LauncherThemeStyle.homeStatsImageRes(requireContext()));
    }

    private boolean isReadableImageUri(@NonNull Uri uri) {
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            return path != null && new File(path).isFile();
        }
        try (InputStream ignored = requireContext().getContentResolver().openInputStream(uri)) {
            return ignored != null;
        } catch (IOException | SecurityException e) {
            // 授权过期或权限缺失时返回 false，让调用方清除失效 URI 并回退默认背景。
            return false;
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
        String homeAvatar = requireContext().getSharedPreferences(LauncherPreferences.APP_PREFS, 0)
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
        requireContext().getSharedPreferences(LauncherPreferences.APP_PREFS, 0)
                .edit().putString("profile_avatar", avatarUri).apply();
    }
}
