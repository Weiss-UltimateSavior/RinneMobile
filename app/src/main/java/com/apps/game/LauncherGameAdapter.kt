package com.apps.game

import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import com.apps.theme.LauncherTheme
import com.core.R
import com.core.databinding.ItemLauncherGameCardBinding
import com.apps.widget.LauncherTabletPortraitScaler

/** Portrait card policy; data, selection and bindings are shared with Pad. */
class LauncherGameAdapter @JvmOverloads constructor(
    applyPortraitScaling: Boolean = true,
) : BaseGameCardAdapter(
    { b, h -> applyPortraitLayout(b, h, applyPortraitScaling) },
    false
) {
    interface OnGameCardListener : BaseGameCardAdapter.OnGameCardListener

    companion object {
        private fun applyPortraitLayout(
            binding: ItemLauncherGameCardBinding?,
            fixedHeightPx: Int,
            applyPortraitScaling: Boolean,
        ) {
            if (binding == null) return
            if (applyPortraitScaling) {
                LauncherTabletPortraitScaler.apply(binding.root)
            }
            if (fixedHeightPx <= 0) return

            binding.root.layoutParams?.let { card ->
                if (card.height != fixedHeightPx) {
                    card.height = fixedHeightPx
                    binding.root.layoutParams = card
                }
            }

            binding.launcherGameTextOverlay.layoutParams?.let { overlay ->
                val overlayHeight = minOf(
                    dimen(binding.root, R.dimen.launcher_library_card_text_overlay_height, 41),
                    fixedHeightPx
                )
                if (overlay.height != overlayHeight) {
                    overlay.height = overlayHeight
                    binding.launcherGameTextOverlay.layoutParams = overlay
                }
            }

            binding.launcherGameTitle.includeFontPadding = false
            binding.launcherGamePlayStatus.includeFontPadding = false
        }

        private fun dimen(view: View?, resId: Int, fallbackDp: Int): Int {
            if (view == null) return 0
            return try {
                view.resources.getDimensionPixelSize(resId)
            } catch (_: Resources.NotFoundException) {
                LauncherTheme.dp(view.context, fallbackDp)
            }
        }
    }
}
