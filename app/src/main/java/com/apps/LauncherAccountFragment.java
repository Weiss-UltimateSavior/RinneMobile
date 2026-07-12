package com.apps;

import android.content.Intent;
import android.content.res.Configuration;
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
import com.yuki.yukihub.databinding.FragmentLauncherAccountBinding;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;

public class LauncherAccountFragment extends Fragment {
    private FragmentLauncherAccountBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLauncherAccountBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        applySystemBarInsets();
        bindActions();
        renderMode();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
    }

    @Override
    public void onResume() {
        super.onResume();
        // 如果已登录，直接跳转到个人信息页
        if (LauncherAuthBridge.isLoggedIn(requireContext())) {
            navigateToProfile();
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

    private void applySystemBarInsets() {
        FragmentLauncherAccountBinding currentBinding = binding;
        int originalLeft = currentBinding.accountScroll.getPaddingLeft();
        int originalTop = currentBinding.accountScroll.getPaddingTop();
        int originalRight = currentBinding.accountScroll.getPaddingRight();
        int originalBottom = currentBinding.accountScroll.getPaddingBottom();

        currentBinding.getRoot().setOnApplyWindowInsetsListener((v, insets) -> {
            currentBinding.accountScroll.setPadding(
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
        binding.switchMode.setOnClickListener(view ->
                startActivity(new Intent(requireContext(), LauncherRegisterActivity.class)));
        binding.btnSubmit.setOnClickListener(view -> performLogin());
        binding.btnGoogle.setOnClickListener(view -> showQQGroupDialog());
        binding.btnFacebook.setOnClickListener(view -> showGitHubDialog());
        binding.forgotPassword.setOnClickListener(view ->
                startActivity(new Intent(requireContext(), LauncherPasswordResetActivity.class)));
    }

    private void renderMode() {
        if (binding == null) return;
        binding.labelName.setVisibility(View.GONE);
        binding.inputName.setVisibility(View.GONE);
        binding.labelConfirmPassword.setVisibility(View.GONE);
        binding.inputConfirmPassword.setVisibility(View.GONE);
        binding.loginOptions.setVisibility(View.VISIBLE);
        binding.accountTitle.setText("欢迎回来");
        binding.btnSubmit.setText("登录");
        binding.switchHint.setText("还没有账户？");
        binding.switchMode.setText("注册");
        // 登录模式下使用邮箱登录
        binding.inputEmail.setHint("请输入邮箱");
        binding.inputEmail.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        // 自动填充上次登录的邮箱
        String savedEmail = requireContext().getSharedPreferences("yukihub_prefs", 0)
                .getString("auth_saved_email", "");
        if (savedEmail != null && !savedEmail.trim().isEmpty()) {
            binding.inputEmail.setText(savedEmail);
        }
    }

    private void performLogin() {
        String email = binding.inputEmail.getText() == null ? "" : binding.inputEmail.getText().toString().trim();
        String password = binding.inputPassword.getText() == null ? "" : binding.inputPassword.getText().toString().trim();

        if (email.isEmpty()) {
            binding.inputEmail.setError("请输入邮箱");
            return;
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.inputEmail.setError("邮箱格式不正确");
            return;
        }
        if (password.isEmpty()) {
            binding.inputPassword.setError("请输入密码");
            return;
        }
        if (password.length() < 6) {
            binding.inputPassword.setError("密码至少 6 位");
            return;
        }

        binding.btnSubmit.setEnabled(false);
        binding.btnSubmit.setText("登录中...");

        LauncherAuthBridge.login(requireContext(), email, password, new LauncherAuthBridge.AuthCallback() {
            @Override
            public void onSuccess(String token) {
                if (binding != null) {
                    binding.btnSubmit.setEnabled(true);
                    binding.btnSubmit.setText("登录");
                }
                Toast.makeText(requireContext(), "登录成功", Toast.LENGTH_SHORT).show();
                navigateToProfile();
            }

            @Override
            public void onError(String message) {
                if (binding != null) {
                    binding.btnSubmit.setEnabled(true);
                    binding.btnSubmit.setText("登录");
                }
                showAuthResultDialog("登录失败", message);
            }
        });
    }

    private void navigateToProfile() {
        if (binding == null) return;
        getParentFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.launcher_fragment_enter,
                        R.anim.launcher_fragment_exit,
                        R.anim.launcher_fragment_enter,
                        R.anim.launcher_fragment_exit
                )
                .replace(R.id.launcherFragmentContainer, new LauncherProfileFragment(), "launcher_ACCOUNT_PROFILE")
                .commit();
    }

    private void showAuthResultDialog(String title, String message) {
        if (getContext() == null) return;
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(requireContext()).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(dialogWidthDp()), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(dialogHorizontalPaddingDp()), dp(dialogVerticalPaddingDp()),
                dp(dialogHorizontalPaddingDp()), dp(dialogVerticalPaddingDp()));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);

        TextView titleView = new TextView(requireContext());
        titleView.setText(title);
        titleView.setGravity(android.view.Gravity.CENTER);
        titleView.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_color));
        titleView.setTextSize(dialogTitleTextSp());
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(titleView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView msgView = new TextView(requireContext());
        msgView.setText(message);
        msgView.setGravity(android.view.Gravity.CENTER);
        msgView.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_muted_color));
        msgView.setTextSize(dialogMessageTextSp());
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dp(13), 0, 0);
        root.addView(msgView, msgLp);

        TextView okBtn = new TextView(requireContext());
        okBtn.setText("知道了");
        okBtn.setGravity(android.view.Gravity.CENTER);
        LauncherTheme.primaryButton(okBtn);
        okBtn.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(dialogButtonHeightDp()));
        okLp.setMargins(0, dp(11), 0, 0);
        root.addView(okBtn, okLp);

        window.setContentView(root);
    }

    private void showQQGroupDialog() {
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(requireContext()).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(dialogWidthDp()), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(dialogHorizontalPaddingDp()), dp(dialogVerticalPaddingDp()),
                dp(dialogHorizontalPaddingDp()), dp(dialogVerticalPaddingDp()));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);

        TextView title = new TextView(requireContext());
        title.setText("QQ群聊");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_color));
        title.setTextSize(dialogTitleTextSp());
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView msg = new TextView(requireContext());
        msg.setText("将跳转到QQ加入交流群，是否继续？");
        msg.setGravity(android.view.Gravity.CENTER);
        msg.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_muted_color));
        msg.setTextSize(dialogMessageTextSp());
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dp(13), 0, 0);
        root.addView(msg, msgLp);

        TextView confirm = new TextView(requireContext());
        confirm.setText("跳转");
        confirm.setGravity(android.view.Gravity.CENTER);
        LauncherTheme.primaryButton(confirm);
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            openExternalUrl("https://qun.qq.com/universal-share/share?ac=1&authKey=nZMa0s3mxxG1A0f%2BY0nAWmBYpul7FWTEDI6UWrzqb2IgKC4aDkUhvkV2AekAkW%2F1&busi_data=eyJncm91cENvZGUiOiIxNjM2MDM2MzUiLCJ0b2tlbiI6Im93eFRyY0tqNDdxK3FGQXlVZ0lhMEZGbWZWemphZnpYYW1kWWpPN1ViL3A0SkRUd1dEclMwZkM1bWI0UEYxME4iLCJ1aW4iOiIzMDg2Njc4NzU1In0%3D&data=bwoLG7XAPzqsvtfneNCQUUlu-HpX1yCn-6dkgd8ubDeBJKEPgd7wKYa6ym-EbW07Vapc3xm_o-iy0GbFHhZk5Q&svctype=4&tempid=h5_group_info");
        });
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(dialogButtonHeightDp()));
        confirmLp.setMargins(0, dp(11), 0, 0);
        root.addView(confirm, confirmLp);

        TextView cancel = new TextView(requireContext());
        cancel.setText("取消");
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setTextColor(LauncherTheme.primary(requireContext()));
        cancel.setTextSize(dialogActionTextSp());
        cancel.setTypeface(null, android.graphics.Typeface.BOLD);
        cancel.setBackground(LauncherTheme.cancelChip(requireContext()));
        cancel.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(dialogButtonHeightDp()));
        cancelLp.setMargins(0, dp(9), 0, 0);
        root.addView(cancel, cancelLp);

        window.setContentView(root);
    }

    private void showGitHubDialog() {
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(requireContext()).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(dialogWidthDp()), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(dialogHorizontalPaddingDp()), dp(dialogVerticalPaddingDp()),
                dp(dialogHorizontalPaddingDp()), dp(dialogVerticalPaddingDp()));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);

        TextView title = new TextView(requireContext());
        title.setText("官网首页");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_color));
        title.setTextSize(dialogTitleTextSp());
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView msg = new TextView(requireContext());
        msg.setText("将跳转到GitHub仓库页面，是否继续？");
        msg.setGravity(android.view.Gravity.CENTER);
        msg.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_muted_color));
        msg.setTextSize(dialogMessageTextSp());
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dp(13), 0, 0);
        root.addView(msg, msgLp);

        TextView confirm = new TextView(requireContext());
        confirm.setText("跳转");
        confirm.setGravity(android.view.Gravity.CENTER);
        LauncherTheme.primaryButton(confirm);
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            openExternalUrl("https://github.com/Weiss-UltimateSavior/RinneMobile");
        });
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(dialogButtonHeightDp()));
        confirmLp.setMargins(0, dp(11), 0, 0);
        root.addView(confirm, confirmLp);

        TextView cancel = new TextView(requireContext());
        cancel.setText("取消");
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setTextColor(LauncherTheme.primary(requireContext()));
        cancel.setTextSize(dialogActionTextSp());
        cancel.setTypeface(null, android.graphics.Typeface.BOLD);
        cancel.setBackground(LauncherTheme.cancelChip(requireContext()));
        cancel.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(dialogButtonHeightDp()));
        cancelLp.setMargins(0, dp(9), 0, 0);
        root.addView(cancel, cancelLp);

        window.setContentView(root);
    }

    private void openExternalUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(requireContext(), "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 仅在平板竖屏下放大弹窗，手机和横屏继续沿用原尺寸。
     */
    private boolean isTabletPortrait() {
        Configuration configuration = requireContext().getResources().getConfiguration();
        return configuration.smallestScreenWidthDp >= 600
                && configuration.orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    private int dialogWidthDp() {
        return isTabletPortrait() ? 360 : 270;
    }

    private int dialogHorizontalPaddingDp() {
        return isTabletPortrait() ? 28 : 22;
    }

    private int dialogVerticalPaddingDp() {
        return isTabletPortrait() ? 24 : 20;
    }

    private int dialogButtonHeightDp() {
        return isTabletPortrait() ? 44 : 36;
    }

    private float dialogTitleTextSp() {
        return isTabletPortrait() ? 18f : 16f;
    }

    private float dialogMessageTextSp() {
        return isTabletPortrait() ? 14f : 12f;
    }

    private float dialogActionTextSp() {
        return isTabletPortrait() ? 15f : 13f;
    }

    private int dp(int value) {
        return (int) (value * requireContext().getResources().getDisplayMetrics().density + 0.5f);
    }
}
