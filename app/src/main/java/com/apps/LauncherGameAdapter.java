package com.apps;

import android.content.res.Resources;
import android.util.TypedValue;
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
        if (fixedHeightPx <= 0 || binding == null) return;

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

        // 只有 values-sw600dp-port / values-sw720dp-port 会开启内容缩放。
        // 手机竖屏仍完全使用 item_launcher_game_card.xml 原有字号和留白。
        if (!shouldScaleTabletPortraitContent(binding.getRoot())) return;

        int horizontalPadding = dimen(
                binding.getRoot(),
                R.dimen.launcher_library_card_overlay_horizontal_padding,
                8
        );
        int verticalPadding = dimen(
                binding.getRoot(),
                R.dimen.launcher_library_card_overlay_vertical_padding,
                2
        );
        binding.launcherGameTextOverlay.setPadding(
                horizontalPadding,
                verticalPadding,
                horizontalPadding,
                verticalPadding
        );

        binding.launcherGameTitle.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                binding.getRoot().getResources().getDimension(
                        R.dimen.launcher_library_card_title_text_size)
        );
        binding.launcherGamePlayStatus.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                binding.getRoot().getResources().getDimension(
                        R.dimen.launcher_library_card_status_text_size)
        );
        binding.launcherGameInitial.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                binding.getRoot().getResources().getDimension(
                        R.dimen.launcher_library_card_initial_text_size)
        );

        binding.launcherGameTitle.setIncludeFontPadding(false);
        binding.launcherGamePlayStatus.setIncludeFontPadding(false);
    }

    private static boolean shouldScaleTabletPortraitContent(View view) {
        if (view == null) return false;
        try {
            return view.getResources().getBoolean(
                    R.bool.launcher_library_scale_tablet_portrait_card_content);
        } catch (Resources.NotFoundException ignored) {
            return false;
        }
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
