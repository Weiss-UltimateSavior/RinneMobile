package com.apps;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.FragmentLauncherAccountBinding;

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

        currentBinding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
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
        binding.btnSubmit.setOnClickListener(view ->
                getParentFragmentManager()
                        .beginTransaction()
                        .replace(R.id.launcherFragmentContainer, new LauncherProfileFragment(), "launcher_ACCOUNT_PROFILE")
                        .commit());
        binding.btnGoogle.setOnClickListener(view ->
                Toast.makeText(requireContext(), "Google 登录待接入", Toast.LENGTH_SHORT).show());
        binding.btnFacebook.setOnClickListener(view ->
                Toast.makeText(requireContext(), "Facebook 登录待接入", Toast.LENGTH_SHORT).show());
        binding.forgotPassword.setOnClickListener(view ->
                Toast.makeText(requireContext(), "密码找回待接入", Toast.LENGTH_SHORT).show());
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
    }
}
