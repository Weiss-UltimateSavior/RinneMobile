package com.apps;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ItemLauncherGameCardBinding;

/** Portrait card policy; data, selection and bindings are shared with Pad. */
public class LauncherGameAdapter extends BaseGameCardAdapter {
    public interface OnGameCardListener extends BaseGameCardAdapter.OnGameCardListener { }

    public LauncherGameAdapter() {
        super(LauncherGameAdapter::applyPortraitLayout, false);
    }

    private static void applyPortraitLayout(
            ItemLauncherGameCardBinding binding,
            int fixedHeightPx
    ) {
        if (binding == null) return;
        LauncherTabletPortraitScaler.apply(binding.getRoot());
        if (fixedHeightPx <= 0) return;

        ViewGroup.LayoutParams card = binding.getRoot().getLayoutParams();
        if (card != null && card.height != fixedHeightPx) {
            card.height = fixedHeightPx;
            binding.getRoot().setLayoutParams(card);
        }

        ViewGroup.LayoutParams overlay = binding.launcherGameTextOverlay.getLayoutParams();
        int overlayHeight = Math.min(
                dimen(binding.getRoot(), R.dimen.launcher_library_card_text_overlay_height, 41),
                fixedHeightPx
        );
        if (overlay != null && overlay.height != overlayHeight) {
            overlay.height = overlayHeight;
            binding.launcherGameTextOverlay.setLayoutParams(overlay);
        }

        binding.launcherGameTitle.setIncludeFontPadding(false);
        binding.launcherGamePlayStatus.setIncludeFontPadding(false);
    }

    private static int dimen(View view, int resId, int fallbackDp) {
        if (view == null) return 0;
        try {
            return view.getResources().getDimensionPixelSize(resId);
        } catch (Resources.NotFoundException ignored) {
            return dp(view, fallbackDp);
        }
    }
}
