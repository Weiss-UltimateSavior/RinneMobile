package com.apps.game

import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.fragment.app.FragmentManager
import com.apps.theme.LauncherTheme
import com.core.R
import com.core.databinding.ItemLauncherHomeAccountActionBinding
import com.core.databinding.SheetLauncherGameLaunchBinding
import com.core.model.EngineType
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * 竖屏游戏库点击游戏卡片后的动作抽屉（取代原确认弹窗）。
 * 样式参考首页设置抽屉 [com.apps.home.LauncherHomeAccountBottomSheet]，
 * 动作行与竖屏管理页引擎设置行同款结构（图标+标题+箭头）。
 *
 * 承载原长按菜单迁移来的动作：启动游戏、编辑游戏、收藏、密码锁定、添加到桌面、引擎设置。
 */
class LauncherGameLaunchBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val dialog = BottomSheetDialog(context, theme)
        val binding = SheetLauncherGameLaunchBinding.inflate(LayoutInflater.from(context))
        val radius = LauncherTheme.dpFloat(context, 24f)

        binding.gameLaunchSheetTitle.apply {
            text = arguments?.getString(ARG_TITLE).orEmpty()
            setTextColor(LauncherTheme.text(context))
        }

        addAction(binding.gameLaunchSheetList, R.drawable.launcher_game_sheet_action_launch, getString(R.string.game_launch_title), ACTION_LAUNCH)
        addAction(binding.gameLaunchSheetList, R.drawable.launcher_game_sheet_action_edit, getString(R.string.game_action_edit), ACTION_EDIT)
        val favoriteAction = if (arguments?.getBoolean(ARG_FAVORITE) == true)
            ACTION_FAVORITE_REMOVE else ACTION_FAVORITE_ADD
        val favoriteLabel = getString(if (arguments?.getBoolean(ARG_FAVORITE) == true)
            R.string.game_action_favorite_remove else R.string.game_action_favorite_add)
        addAction(binding.gameLaunchSheetList, R.drawable.launcher_game_sheet_action_favorite, favoriteLabel, favoriteAction)
        val hasPassword = arguments?.getBoolean(ARG_HAS_PASSWORD) == true
        addAction(
            binding.gameLaunchSheetList, R.drawable.launcher_game_sheet_action_password,
            getString(if (hasPassword) R.string.game_action_password_remove else R.string.game_action_password_lock),
            if (hasPassword) ACTION_PASSWORD_REMOVE else ACTION_PASSWORD_SET,
        )
        addAction(binding.gameLaunchSheetList, R.drawable.launcher_game_sheet_action_shortcut, getString(R.string.game_action_pin_shortcut), ACTION_PIN_SHORTCUT)
        // EngineType.fromString 为单一来源解析工具；未识别引擎不展示引擎设置项。
        val engine = EngineType.fromString(arguments?.getString(ARG_ENGINE))
        if (engine == EngineType.ONS || engine == EngineType.KIRIKIRI || engine == EngineType.ARTEMIS) {
            // 引擎设置图标直接复用竖屏管理页引擎设置入口的 logo。
            addAction(binding.gameLaunchSheetList, R.drawable.launcher_manage_action_engine_settings, getString(R.string.game_action_engine_settings), ACTION_ENGINE_SETTINGS)
        }

        dialog.setContentView(binding.root)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            bottomSheet.background = GradientDrawable().apply {
                setColor(LauncherTheme.bg(context))
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            BottomSheetBehavior.from(bottomSheet).apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
            bottomSheet.visibility = View.VISIBLE
            bottomSheet.requestLayout()
        }
        return dialog
    }

    /** 追加一个带图标通用动作行（与首页设置抽屉同款 item_launcher_home_account_action）。 */
    private fun addAction(list: ViewGroup, @DrawableRes icon: Int, title: String, action: String) {
        val context = requireContext()
        val itemBinding = ItemLauncherHomeAccountActionBinding.inflate(
            LayoutInflater.from(context), list, false
        )
        itemBinding.homeAccountSheetActionIcon.setImageResource(icon)
        itemBinding.homeAccountSheetActionTitle.text = title
        itemBinding.root.setOnClickListener {
            if (!isAdded) return@setOnClickListener
            dismiss()
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                Bundle().apply { putString(RESULT_ACTION, action) }
            )
        }
        LauncherTheme.applyPrimaryTone(itemBinding.root)
        LauncherTheme.styleManageRow(itemBinding.root)
        list.addView(itemBinding.root)
    }

    companion object {
        const val REQUEST_KEY = "launcher_game_launch_sheet_result"
        const val RESULT_ACTION = "action"
        const val ACTION_LAUNCH = "launch"
        const val ACTION_EDIT = "edit"
        const val ACTION_FAVORITE_ADD = "favorite_add"
        const val ACTION_FAVORITE_REMOVE = "favorite_remove"
        const val ACTION_PASSWORD_SET = "password_set"
        const val ACTION_PASSWORD_REMOVE = "password_remove"
        const val ACTION_PIN_SHORTCUT = "pin_shortcut"
        const val ACTION_ENGINE_SETTINGS = "engine_settings"

        private const val ARG_GAME_ID = "game_id"
        private const val ARG_TITLE = "game_title"
        private const val ARG_FAVORITE = "game_favorite"
        private const val ARG_HAS_PASSWORD = "game_has_password"
        private const val ARG_ENGINE = "game_engine"
        private const val TAG = "launcher_game_launch_sheet"

        fun show(manager: FragmentManager, game: com.core.model.Game): Boolean {
            if (manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return false
            LauncherGameLaunchBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_GAME_ID, game.id)
                    putString(ARG_TITLE, game.title)
                    putBoolean(ARG_FAVORITE, game.favorite)
                    putBoolean(ARG_HAS_PASSWORD, GamePasswordLock.hasPassword(game))
                    putString(ARG_ENGINE, game.engine?.name)
                }
            }.show(manager, TAG)
            return true
        }
    }
}
