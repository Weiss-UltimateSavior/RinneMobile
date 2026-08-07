package com.apps.account;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.core.R;
import com.core.databinding.FragmentLauncherAccountBinding;
import com.core.launcherbridge.AuthCallback;
import com.core.launcherbridge.LauncherAuthBridge;
import com.core.prefs.LauncherMainKeys;
import com.apps.LauncherPreferences;
import com.apps.common.LauncherInsetsHelper;
import com.apps.profile.LauncherProfileFragment;
import com.apps.LauncherNavigationMetricsKt;
import com.apps.HDModel.LauncherDialogRouter;
import com.apps.theme.LauncherTheme;
import com.apps.util.LauncherUrlOpener;
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
            LauncherInsetsHelper.applyTopInset(binding.getRoot(), binding.accountScroll, original -> LauncherNavigationMetricsKt.navigationOverlayBottomPadding(this, original));
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
        LauncherNavigationMetricsKt.refreshNavigationOverlayInsets(this);
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
        String savedEmail = requireContext().getSharedPreferences(LauncherPreferences.APP_PREFS, 0)
                .getString(LauncherMainKeys.KEY_AUTH_SAVED_EMAIL, "");
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
        LauncherDialogRouter.showInfo(requireContext(), title, message);
    }

    private void showQQGroupDialog() {
        LauncherDialogRouter.showStandardConfirm(
                requireContext(),
                getString(R.string.social_qq_group),
                getString(R.string.social_open_qq_message),
                getString(R.string.social_action_open),
                () -> openExternalUrl("https://qun.qq.com/universal-share/share?ac=1&authKey=nZMa0s3mxxG1A0f%2BY0nAWmBYpul7FWTEDI6UWrzqb2IgKC4aDkUhvkV2AekAkW%2F1&busi_data=eyJncm91cENvZGUiOiIxNjM2MDM2MzUiLCJ0b2tlbiI6Im93eFRyY0tqNDdxK3FGQXlVZ0lhMEZGbWZWemphZnpYYW1kWWpPN1ViL3A0SkRUd1dEclMwZkM1bWI0UEYxME4iLCJ1aW4iOiIzMDg2Njc4NzU1In0%3D&data=bwoLG7XAPzqsvtfneNCQUUlu-HpX1yCn-6dkgd8ubDeBJKEPgd7wKYa6ym-EbW07Vapc3xm_o-iy0GbFHhZk5Q&svctype=4&tempid=h5_group_info")
        );
    }

    private void showGitHubDialog() {
        LauncherDialogRouter.showStandardConfirm(
                requireContext(),
                getString(R.string.social_official_site),
                getString(R.string.social_open_github_message),
                getString(R.string.social_action_open),
                () -> openExternalUrl("https://github.com/Weiss-UltimateSavior/RinneMobile")
        );
    }

    private void openExternalUrl(String url) {
        if (!LauncherUrlOpener.open(requireContext(), url)) {
            Toast.makeText(requireContext(), R.string.social_cannot_open_link, Toast.LENGTH_SHORT).show();
        }
    }

}
