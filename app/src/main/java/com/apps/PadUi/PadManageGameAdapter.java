package com.apps.PadUi;

import android.view.ViewGroup;

import com.apps.BaseGameCardAdapter;
import com.yuki.yukihub.databinding.ItemLauncherGameCardBinding;

/** Pad-only compact-card layout policy; data and interaction binding remain shared. */
public class PadManageGameAdapter extends BaseGameCardAdapter {
    public interface OnGameCardListener extends BaseGameCardAdapter.OnGameCardListener { }
    public PadManageGameAdapter() { super(PadManageGameAdapter::applyPadLayout, true); }
    private static void applyPadLayout(ItemLauncherGameCardBinding binding, int fixedHeightPx) {
        if (fixedHeightPx <= 0 || binding == null) return;
        ViewGroup.LayoutParams card = binding.getRoot().getLayoutParams();
        if (card != null && card.height != fixedHeightPx) { card.height = fixedHeightPx; binding.getRoot().setLayoutParams(card); }
        ViewGroup.LayoutParams overlay = binding.launcherGameTextOverlay.getLayoutParams();
        int height = Math.min(fixedHeightPx, Math.max(dp(binding.getRoot(), 35), Math.min(dp(binding.getRoot(), 38), fixedHeightPx / 4)));
        if (overlay.height != height) { overlay.height = height; binding.launcherGameTextOverlay.setLayoutParams(overlay); }
        compactText(binding);
    }
}
