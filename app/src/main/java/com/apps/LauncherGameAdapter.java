package com.apps;

import android.view.ViewGroup;

import com.yuki.yukihub.databinding.ItemLauncherGameCardBinding;

/** Portrait card policy; data, selection and bindings are shared with Pad. */
public class LauncherGameAdapter extends BaseGameCardAdapter {
    public interface OnGameCardListener extends BaseGameCardAdapter.OnGameCardListener { }
    public LauncherGameAdapter() { super(LauncherGameAdapter::applyPortraitLayout, false); }
    private static void applyPortraitLayout(ItemLauncherGameCardBinding binding, int fixedHeightPx) {
        if (fixedHeightPx <= 0 || binding == null) return;
        ViewGroup.LayoutParams card = binding.getRoot().getLayoutParams();
        if (card != null && card.height != fixedHeightPx) { card.height = fixedHeightPx; binding.getRoot().setLayoutParams(card); }
        ViewGroup.LayoutParams overlay = binding.launcherGameTextOverlay.getLayoutParams();
        int height = Math.min(dp(binding.getRoot(), 41), fixedHeightPx);
        if (overlay.height != height) { overlay.height = height; binding.launcherGameTextOverlay.setLayoutParams(overlay); }
    }
}
