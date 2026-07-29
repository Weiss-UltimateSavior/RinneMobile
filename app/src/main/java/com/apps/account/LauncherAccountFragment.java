package com.apps.account;

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

import com.core.R;
import com.core.databinding.FragmentLauncherAccountBinding;
import com.core.launcherbridge.AuthCallback;
import com.core.launcherbridge.LauncherAuthBridge;
import com.apps.profile.LauncherProfileFragment;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherAccountFragment extends Fragment {
    private FragmentLauncherAccountBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLauncherAccountBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    protected final void bindAccountRoot(@NonNull View root) {
        binding = FragmentLauncherAccountBinding.bind(root);
    }

    protected boolean usePortraitAccountScaler() {
        return true;
    }

    protected boolean applyAccountSystemBarInsets() {
        return true;
    }

    protected int accountFragmentContainerId() {
        return R.id.launcherFragmentContainer;
    }

    protected Fragment createProfileFragment() {
        return new LauncherProfileFragment();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (usePortraitAccountScaler()) {
            LauncherTabletPortraitScaler.apply(binding.getRoot());
            collapseTabletSubmitSpacer();
        }
        if (applyAccountSystemBarInsets()) {
            applySystemBarInsets();
        }
        bindActions();
        renderMode();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        LauncherTheme.formInputs(binding.inputName, binding.inputEmail,
                binding.inputPassword, binding.inputConfirmPassword);
        LauncherTheme.longActionButton(binding.btnSubmit);
        LauncherTheme.shortSecondaryActionButton(binding.btnGoogle);
        LauncherTheme.shortSecondaryActionButton(binding.btnFacebook);
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

    /** Keep the form usable above the Launcher bottom navigation after its controls are enlarged. */
    private void collapseTabletSubmitSpacer() {
        if (!LauncherTabletPortraitScaler.isTabletPortrait(getResources())) return;
        View spacer = binding.getRoot().findViewWithTag("account_submit_spacer");
        if (spacer == null) return;
        ViewGroup.LayoutParams params = spacer.getLayoutParams();
        if (params == null || params.height == 0) return;
        params.height = 0;
        spacer.setLayoutParams(params);
    }

    private void bindActions() {
        binding.switchMode.setOnClickListener(view -> openRegister());
        binding.btnSubmit.setOnClickListener(view -> performLogin());
        binding.btnGoogle.setOnClickListener(view -> showQQGroupDialog());
        binding.btnFacebook.setOnClickListener(view -> showGitHubDialog());
        binding.forgotPassword.setOnClickListener(view -> openPasswordReset());
    }

    protected void openRegister() {
        startActivity(new Intent(requireContext(), LauncherRegisterActivity.class));
    }

    protected void openPasswordReset() {
        startActivity(new Intent(requireContext(), LauncherPasswordResetActivity.class));
    }

    private void renderMode() {
        if (binding == null) return;
        binding.labelName.setVisibility(View.GONE);
        binding.inputName.setVisibility(View.GONE);
        binding.labelConfirmPassword.setVisibility(View.GONE);
        binding.inputConfirmPassword.setVisibility(View.GONE);
        binding.loginOptions.setVisibility(View.VISIBLE);
        binding.accountTitle.setText(R.string.social_account_title);
        binding.btnSubmit.setText(R.string.social_login);
        binding.switchHint.setText(R.string.social_no_account);
        binding.switchMode.setText(R.string.social_register);
        // 登录模式下使用邮箱登录
        binding.inputEmail.setHint(R.string.social_email_hint);
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
            binding.inputEmail.setError(getString(R.string.social_error_email_required));
            return;
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.inputEmail.setError(getString(R.string.social_error_email_invalid));
            return;
        }
        if (password.isEmpty()) {
            binding.inputPassword.setError(getString(R.string.social_error_password_required));
            return;
        }
        if (password.length() < 6) {
            binding.inputPassword.setError(getString(R.string.social_error_password_min));
            return;
        }

        binding.btnSubmit.setEnabled(false);
        binding.btnSubmit.setText(R.string.social_logging_in);

        LauncherAuthBridge.login(requireContext(), email, password, new AuthCallback() {
            @Override
            public void onSuccess(String token) {
                if (binding != null) {
                    binding.btnSubmit.setEnabled(true);
                    binding.btnSubmit.setText(R.string.social_login);
                }
                Toast.makeText(requireContext(), R.string.social_login_success, Toast.LENGTH_SHORT).show();
                navigateToProfile();
            }

            @Override
            public void onError(String message) {
                if (binding != null) {
                    binding.btnSubmit.setEnabled(true);
                    binding.btnSubmit.setText(R.string.social_login);
                }
                showAuthResultDialog(getString(R.string.social_login_failed), message);
            }
        });
    }

    protected void navigateToProfile() {
        if (binding == null) return;
        getParentFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.launcher_fragment_enter,
                        R.anim.launcher_fragment_exit,
                        R.anim.launcher_fragment_enter,
                        R.anim.launcher_fragment_exit
                )
                .replace(accountFragmentContainerId(), createProfileFragment(), "launcher_ACCOUNT_PROFILE")
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
        okBtn.setText(R.string.social_action_got_it);
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
        title.setText(R.string.social_qq_group);
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_color));
        title.setTextSize(dialogTitleTextSp());
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView msg = new TextView(requireContext());
        msg.setText(R.string.social_open_qq_message);
        msg.setGravity(android.view.Gravity.CENTER);
        msg.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_muted_color));
        msg.setTextSize(dialogMessageTextSp());
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dp(13), 0, 0);
        root.addView(msg, msgLp);

        TextView confirm = new TextView(requireContext());
        confirm.setText(R.string.social_action_open);
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
        cancel.setText(R.string.social_action_cancel);
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
        title.setText(R.string.social_official_site);
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_color));
        title.setTextSize(dialogTitleTextSp());
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView msg = new TextView(requireContext());
        msg.setText(R.string.social_open_github_message);
        msg.setGravity(android.view.Gravity.CENTER);
        msg.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_muted_color));
        msg.setTextSize(dialogMessageTextSp());
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dp(13), 0, 0);
        root.addView(msg, msgLp);

        TextView confirm = new TextView(requireContext());
        confirm.setText(R.string.social_action_open);
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
        cancel.setText(R.string.social_action_cancel);
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
            Toast.makeText(requireContext(), R.string.social_cannot_open_link, Toast.LENGTH_SHORT).show();
        }
    }

    private int dialogWidthDp() {
        return 252;
    }

    private int dialogHorizontalPaddingDp() {
        return 22;
    }

    private int dialogVerticalPaddingDp() {
        return 20;
    }

    private int dialogButtonHeightDp() {
        return 36;
    }

    private float dialogTitleTextSp() {
        return scaledSp(16f);
    }

    private float dialogMessageTextSp() {
        return scaledSp(12f);
    }

    private float dialogActionTextSp() {
        return scaledSp(13f);
    }

    private float scaledSp(float baseSp) {
        return usePortraitAccountScaler()
                ? baseSp * LauncherTabletPortraitScaler.scaleFor(binding == null ? null : binding.getRoot())
                : baseSp;
    }

    private int dp(int value) {
        return usePortraitAccountScaler()
                ? LauncherTabletPortraitScaler.dp(requireContext(), value)
                : (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
