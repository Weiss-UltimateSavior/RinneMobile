package com.apps.HDModel

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.apps.LauncherActivity
import com.apps.PadUi.PadDialogFactory
import com.apps.account.LauncherDisclaimerFragment
import com.apps.home.LauncherHomeAccountBottomSheet
import com.apps.settings.LauncherAppSettingsFragment
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.apps.theme.LauncherThemeMenuFragment
import com.apps.util.LauncherUrlOpener
import com.core.R
import com.core.launcherbridge.LauncherUpdateBridge

/** HD 设置页：左侧保留首页设置菜单，右侧承载对应子 Fragment。 */
class HdSettingsFragment : Fragment(), HdEmbeddedActivityOwner {
    private var detailContainer: FrameLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_hd_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        detailContainer = view.findViewById(R.id.hdSettingsDetailContainer)
        LauncherTheme.applyPrimaryTone(view)
        val actionList = view.findViewById<LinearLayout>(R.id.hdSettingsActionList)
        val toneRow = view.findViewById<View>(R.id.hdSettingsTone)
        toneRow.visibility = if (LauncherActivity.isFollowingSystemTone(requireContext())) {
            View.GONE
        } else {
            View.VISIBLE
        }
        applySettingsActionIcons(view, actionList)
        view.findViewById<View>(R.id.hdSettingsApp).setOnClickListener {
            showChildFragment(CHILD_APP_SETTINGS_TAG, LauncherAppSettingsFragment())
        }
        view.findViewById<View>(R.id.hdSettingsTheme).setOnClickListener {
            showChildFragment(CHILD_THEME_MENU_TAG, LauncherThemeMenuFragment())
        }
        toneRow.setOnClickListener { confirmToggleTone() }
        view.findViewById<View>(R.id.hdSettingsUpdate).setOnClickListener { checkUpdate() }
        view.findViewById<View>(R.id.hdSettingsFeedback).setOnClickListener {
            showFeedbackOptions()
        }
        view.findViewById<View>(R.id.hdSettingsDisclaimer).setOnClickListener {
            showChildFragment(CHILD_DISCLAIMER_TAG, LauncherDisclaimerFragment())
        }
        showChildFragment(CHILD_APP_SETTINGS_TAG, LauncherAppSettingsFragment())
    }

    override fun onDestroyView() {
        detailContainer = null
        super.onDestroyView()
    }

    private fun applySettingsActionIcons(view: View, actionList: LinearLayout) {
        val rowIdsByAction = mapOf(
            LauncherHomeAccountBottomSheet.ACTION_APP_SETTINGS to R.id.hdSettingsApp,
            LauncherHomeAccountBottomSheet.ACTION_THEME to R.id.hdSettingsTheme,
            LauncherHomeAccountBottomSheet.ACTION_TONE to R.id.hdSettingsTone,
            LauncherHomeAccountBottomSheet.ACTION_UPDATE to R.id.hdSettingsUpdate,
            LauncherHomeAccountBottomSheet.ACTION_FEEDBACK to R.id.hdSettingsFeedback,
            LauncherHomeAccountBottomSheet.ACTION_DISCLAIMER to R.id.hdSettingsDisclaimer,
        )
        LauncherHomeAccountBottomSheet.accountActions(requireContext()).forEach { action ->
            val row = rowIdsByAction[action.id]?.let { view.findViewById<LinearLayout>(it) }
                ?: return@forEach
            (row.getChildAt(0) as? ImageView)?.setImageResource(action.iconRes)
        }
        repeat(actionList.childCount) { index ->
            LauncherTheme.styleManageRow(actionList.getChildAt(index))
        }
    }

    private fun showChildFragment(tag: String, fragment: Fragment) {
        if (!isAdded || detailContainer == null) return
        childFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.launcher_fragment_enter,
                R.anim.launcher_fragment_exit,
            )
            .replace(R.id.hdSettingsDetailContainer, fragment, tag)
            .commit()
    }

    override fun closeEmbeddedActivity(child: Activity?): Boolean {
        val existing = childFragmentManager.findFragmentByTag(CHILD_APP_SETTINGS_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_THEME_MENU_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_DISCLAIMER_TAG)
            ?: return false
        childFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.launcher_fragment_enter, R.anim.launcher_fragment_exit)
            .remove(existing)
            .commit()
        return true
    }

    private fun confirmToggleTone() {
        if (LauncherActivity.isFollowingSystemTone(requireContext())) return
        val darkMode = LauncherActivity.isLauncherDarkMode(requireContext())
        val nextTone = getString(if (darkMode) R.string.home_light_mode else R.string.home_dark_mode)
        PadDialogFactory.showConfirm(
            requireContext(),
            getString(R.string.home_switch_tone),
            getString(R.string.home_switch_tone_message, nextTone),
            getString(R.string.core_confirm),
        ) {
            LauncherMotion.recreateWithToneOverlay(requireActivity()) {
                LauncherActivity.setLauncherDarkMode(requireContext(), !darkMode)
            }
        }
    }

    private fun checkUpdate() {
        Toast.makeText(requireContext(), R.string.home_checking_update, Toast.LENGTH_SHORT).show()
        LauncherUpdateBridge.checkUpdate(
            requireContext(),
            object : LauncherUpdateBridge.Callback {
                override fun onResult(
                    info: LauncherUpdateBridge.UpdateInfo?,
                    currentVersion: String,
                    hasUpdate: Boolean,
                ) {
                    if (!isAdded) return
                    PadDialogFactory.showUpdateResult(
                        requireContext(),
                        info,
                        currentVersion,
                        hasUpdate,
                        null,
                    )
                }

                override fun onError(message: String) {
                    if (!isAdded) return
                    PadDialogFactory.showUpdateResult(
                        requireContext(),
                        null,
                        "",
                        false,
                        message,
                    )
                }
            },
        )
    }

    private fun showFeedbackOptions() {
        PadDialogFactory.showActionChoices(
            requireContext(),
            getString(R.string.home_feedback),
            arrayOf(
                getString(R.string.home_github_repository),
                getString(R.string.home_qq_group),
            ),
            -1,
        ) { index ->
            openExternalUrl(if (index == 0) GITHUB_URL else QQ_GROUP_URL)
        }
    }

    private fun openExternalUrl(url: String) {
        if (!LauncherUrlOpener.open(requireContext(), url)) {
            Toast.makeText(requireContext(), R.string.home_cannot_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val CHILD_APP_SETTINGS_TAG = "hd_app_settings"
        private const val CHILD_THEME_MENU_TAG = "hd_theme_settings"
        private const val CHILD_DISCLAIMER_TAG = "hd_disclaimer"
        private const val GITHUB_URL =
            "https://github.com/Weiss-UltimateSavior/RinneMobile"
        private const val QQ_GROUP_URL =
            "https://qun.qq.com/universal-share/share?ac=1&authKey=nZMa0s3mxxG1A0f%2BY0nAWmBYpul7FWTEDI6UWrzqb2IgKC4aDkUhvkV2AekAkW%2F1&busi_data=eyJncm91cENvZGUiOiIxNjM2MDM2MzUiLCJ0b2tlbiI6Im93eFRyY0tqNDdxK3FGQXlVZ0lhMEZGbWZWemphZnpYYW1kWWpPN1ViL3A0SkRUd1dEclMwZkM1bWI0UEYxME4iLCJ1aW4iOiIzMDg2Njc4NzU1In0%3D&data=bwoLG7XAPzqsvtfneNCQUUlu-HpX1yCn-6dkgd8ubDeBJKEPgd7wKYa6ym-EbW07Vapc3xm_o-iy0GbFHhZk5Q&svctype=4&tempid=h5_group_info"
    }
}
