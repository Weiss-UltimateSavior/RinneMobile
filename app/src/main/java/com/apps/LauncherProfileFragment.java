package com.apps;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.yuki.yukihub.databinding.FragmentLauncherProfileBinding;

public class LauncherProfileFragment extends Fragment {
    private static final int REQUEST_PICK_COVER = 10021;
    private static final String PREFS_NAME = "launcher_profile_prefs";
    private static final String KEY_CUSTOM_COVER = "custom_cover_uri";

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
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_PICK_COVER && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
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
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
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
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        applyProfileBgImage();
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
            binding.profileBgImage.setImageResource(com.yuki.yukihub.R.drawable.launcher_home_stats_rinne_bg);
        } else if (LauncherActivity.isAnriTheme(requireContext())) {
            binding.profileBgImage.setImageResource(com.yuki.yukihub.R.drawable.launcher_home_stats_bg_anri);
        } else {
            binding.profileBgImage.setImageResource(com.yuki.yukihub.R.drawable.launcher_home_stats_bg);
        }
    }
}
