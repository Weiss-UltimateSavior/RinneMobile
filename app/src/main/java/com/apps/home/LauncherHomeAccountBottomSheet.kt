package com.apps.home

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.fragment.app.FragmentManager
import com.apps.LauncherActivity
import com.apps.theme.LauncherTheme
import com.core.R
import com.core.databinding.ItemLauncherHomeAccountActionBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** 首页右上角设置按钮显示的功能抽屉。 */
class LauncherHomeAccountBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val dialog = BottomSheetDialog(context, theme)
        val contentView = LayoutInflater.from(context)
            .inflate(R.layout.sheet_launcher_home_account, null, false)
        val density = resources.displayMetrics.density
        val radius = 24f * density

        val list = contentView.findViewById<LinearLayout>(R.id.homeAccountSheetList)
        val followsSystemTone = LauncherActivity.isFollowingSystemTone(context)
        val actions = accountActions(context)
        val sheetHeight = ((if (followsSystemTone) 347f else 409f) * density).toInt()
        actions.forEach { action ->
            val itemBinding = ItemLauncherHomeAccountActionBinding.inflate(
                LayoutInflater.from(context),
                list,
                false
            )
            itemBinding.homeAccountSheetActionIcon.setImageResource(action.iconRes)
            itemBinding.homeAccountSheetActionTitle.text = action.title
            itemBinding.root.setOnClickListener {
                dismiss()
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    Bundle().apply { putString(RESULT_ACTION, action.id) }
                )
            }
            LauncherTheme.applyPrimaryTone(itemBinding.root)
            LauncherTheme.styleManageRow(itemBinding.root)
            list.addView(itemBinding.root)
        }

        dialog.setContentView(contentView)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = sheetHeight
            }
            bottomSheet.background = GradientDrawable().apply {
                setColor(LauncherTheme.bg(context))
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            BottomSheetBehavior.from(bottomSheet).apply {
                peekHeight = sheetHeight
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
            bottomSheet.visibility = View.VISIBLE
            bottomSheet.requestLayout()
        }
        return dialog
    }

    data class SheetAction(
        @DrawableRes
        val iconRes: Int,
        val title: String,
        val id: String,
    )

    companion object {
        const val REQUEST_KEY = "launcher_home_settings_sheet_result"
        const val RESULT_ACTION = "action"
        const val ACTION_APP_SETTINGS = "app_settings"
        const val ACTION_THEME = "theme"
        const val ACTION_TONE = "tone"
        const val ACTION_UPDATE = "update"
        const val ACTION_FEEDBACK = "feedback"
        const val ACTION_DISCLAIMER = "disclaimer"

        private const val TAG = "launcher_home_settings_sheet"

        fun accountActions(context: Context): List<SheetAction> = buildList {
            add(SheetAction(
                R.drawable.launcher_home_sheet_app_settings,
                context.getString(R.string.app_settings_title),
                ACTION_APP_SETTINGS,
            ))
            add(SheetAction(
                R.drawable.launcher_home_sheet_theme,
                context.getString(R.string.launcher_settings_action_theme),
                ACTION_THEME,
            ))
            if (!LauncherActivity.isFollowingSystemTone(context)) {
                add(SheetAction(
                    R.drawable.launcher_home_sheet_tone,
                    context.getString(R.string.launcher_settings_action_tone),
                    ACTION_TONE,
                ))
            }
            add(SheetAction(
                R.drawable.launcher_home_sheet_update,
                context.getString(R.string.launcher_settings_action_update),
                ACTION_UPDATE,
            ))
            add(SheetAction(
                R.drawable.launcher_home_sheet_feedback,
                context.getString(R.string.launcher_settings_action_feedback),
                ACTION_FEEDBACK,
            ))
            add(SheetAction(
                R.drawable.launcher_home_sheet_disclaimer,
                context.getString(R.string.launcher_settings_action_disclaimer),
                ACTION_DISCLAIMER,
            ))
        }

        fun show(manager: FragmentManager) {
            if (manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            LauncherHomeAccountBottomSheet().show(manager, TAG)
        }
    }
}
