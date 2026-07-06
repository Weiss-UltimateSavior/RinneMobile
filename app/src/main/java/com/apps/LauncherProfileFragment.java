package com.apps;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.yuki.yukihub.databinding.FragmentLauncherProfileBinding;

public class LauncherProfileFragment extends Fragment {
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
        binding.profilePlaceholderCard.setBackground(LauncherTheme.primaryButton(requireContext(), 30f));
        LauncherTheme.applyPrimaryTone(binding.getRoot());
    }
}
