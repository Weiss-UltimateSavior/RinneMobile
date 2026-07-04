package com.apps;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.yuki.yukihub.databinding.FragmentLauncherPlaceholderBinding;

public class LauncherPlaceholderFragment extends Fragment {
    private static final String ARG_TITLE = "title";

    private FragmentLauncherPlaceholderBinding binding;

    public static LauncherPlaceholderFragment newInstance(String title) {
        LauncherPlaceholderFragment fragment = new LauncherPlaceholderFragment();
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLauncherPlaceholderBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = getArguments();
        binding.tvPlaceholderTitle.setText(args == null ? "占位" : args.getString(ARG_TITLE, "占位"));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
