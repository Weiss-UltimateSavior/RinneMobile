package com.apps.PadUi

import android.view.ViewGroup
import com.apps.game.BaseGameCardAdapter
import com.apps.theme.LauncherTheme
import com.core.databinding.ItemLauncherGameCardBinding
import kotlin.math.min

/**
 * GAME 页专用适配器：使用 [item_launcher_game_card] 布局，固定高度 + 遮罩逻辑，
 * 不加 compactText 紧凑样式，保留 GAME 页旧观感。
 */
class PadGameListAdapter : BaseGameCardAdapter(::applyPadGameLayout, true) {
    interface OnGameCardListener : BaseGameCardAdapter.OnGameCardListener

    companion object {
        @JvmStatic
        private fun applyPadGameLayout(binding: ItemLauncherGameCardBinding?, fixedHeightPx: Int) {
            if (fixedHeightPx <= 0 || binding == null) return
            binding.root.layoutParams?.let { card ->
                if (card.height != fixedHeightPx) {
                    card.height = fixedHeightPx
                    binding.root.layoutParams = card
                }
            }
            val overlay = binding.launcherGameTextOverlay.layoutParams
            val height = min(fixedHeightPx, LauncherTheme.dp(binding.root.context, 41))
            if (overlay.height != height) {
                overlay.height = height
                binding.launcherGameTextOverlay.layoutParams = overlay
            }
            // 不加 compactText 紧凑样式，保留 GAME 页旧观感
        }
    }
}