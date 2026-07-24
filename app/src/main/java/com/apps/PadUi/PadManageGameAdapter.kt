package com.apps.PadUi

import android.view.ViewGroup
import com.apps.game.BaseGameCardAdapter
import com.yuki.yukihub.databinding.ItemLauncherGameCardBinding
import kotlin.math.max
import kotlin.math.min

/** Pad-only compact-card layout policy; data and interaction binding remain shared. */
class PadManageGameAdapter : BaseGameCardAdapter(::applyPadLayout, true) {
    interface OnGameCardListener : BaseGameCardAdapter.OnGameCardListener

    companion object {
        @JvmStatic
        private fun applyPadLayout(binding: ItemLauncherGameCardBinding?, fixedHeightPx: Int) {
            if (fixedHeightPx <= 0 || binding == null) return
            binding.root.layoutParams?.let { card ->
                if (card.height != fixedHeightPx) {
                    card.height = fixedHeightPx
                    binding.root.layoutParams = card
                }
            }
            val overlay = binding.launcherGameTextOverlay.layoutParams
            val height = min(fixedHeightPx, max(dp(binding.root, 35), min(dp(binding.root, 38), fixedHeightPx / 4)))
            if (overlay.height != height) {
                overlay.height = height
                binding.launcherGameTextOverlay.layoutParams = overlay
            }
            compactText(binding)
        }
    }
}
