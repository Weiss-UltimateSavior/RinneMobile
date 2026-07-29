package com.apps.HDModel

import android.app.LocalActivityManager
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.apps.LauncherActivity
import com.apps.account.LauncherDisclaimerActivity
import com.apps.settings.LauncherAppSettingsActivity
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.apps.theme.LauncherThemeMenuActivity
import com.core.R
import com.core.launcherbridge.LauncherUpdateBridge

/** HD 设置页：左侧保留首页设置菜单，右侧承载对应 Activity。 */
@Suppress("DEPRECATION")
class HdSettingsFragment : Fragment(), HdEmbeddedActivityOwner {
    private var detailContainer: FrameLayout? = null
    private var localActivityManager: LocalActivityManager? = null
    private var embeddedActivityId: String? = null

    /**
     * 嵌入的 [LauncherAppSettingsActivity] 无法接收 Activity Result 回调，
     * 因此由本 Fragment 使用自身的 ActivityResultRegistry 启动系统图片选择器，
     * 把选中的 Uri 通过 [pendingSplashImageCallback] 回传给发起请求的嵌入 Activity。
     */
    private var pendingSplashImageCallback: ((Uri?) -> Unit)? = null
    private val splashImagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val callback = pendingSplashImageCallback
            pendingSplashImageCallback = null
            callback?.invoke(uri)
        }

    override fun launchSplashImagePicker(callback: (Uri?) -> Unit): Boolean {
        if (!isAdded) return false
        pendingSplashImageCallback = callback
        splashImagePicker.launch("image/*")
        return true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_hd_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        localActivityManager = LocalActivityManager(requireActivity(), false).apply {
            dispatchCreate(savedInstanceState)
        }
        detailContainer = view.findViewById(R.id.hdSettingsDetailContainer)
        LauncherTheme.applyPrimaryTone(view)
        val actionList = view.findViewById<LinearLayout>(R.id.hdSettingsActionList)
        repeat(actionList.childCount) { index ->
            LauncherTheme.styleManageRow(actionList.getChildAt(index))
        }
        val toneRow = view.findViewById<View>(R.id.hdSettingsTone)
        toneRow.visibility = if (LauncherActivity.isFollowingSystemTone(requireContext())) {
            View.GONE
        } else {
            View.VISIBLE
        }
        view.findViewById<View>(R.id.hdSettingsApp).setOnClickListener {
            showEmbeddedActivity("hd_app_settings", LauncherAppSettingsActivity::class.java)
        }
        view.findViewById<View>(R.id.hdSettingsTheme).setOnClickListener {
            showEmbeddedActivity("hd_theme_settings", LauncherThemeMenuActivity::class.java)
        }
        toneRow.setOnClickListener { confirmToggleTone() }
        view.findViewById<View>(R.id.hdSettingsUpdate).setOnClickListener { checkUpdate() }
        view.findViewById<View>(R.id.hdSettingsFeedback).setOnClickListener {
            showFeedbackOptions()
        }
        view.findViewById<View>(R.id.hdSettingsDisclaimer).setOnClickListener {
            showEmbeddedActivity("hd_disclaimer", LauncherDisclaimerActivity::class.java)
        }
        showEmbeddedActivity("hd_app_settings", LauncherAppSettingsActivity::class.java)
    }

    override fun onResume() {
        super.onResume()
        localActivityManager?.dispatchResume()
    }

    override fun onPause() {
        localActivityManager?.dispatchPause(requireActivity().isFinishing)
        super.onPause()
    }

    override fun onStop() {
        localActivityManager?.dispatchStop()
        super.onStop()
    }

    override fun onDestroyView() {
        localActivityManager?.dispatchDestroy(requireActivity().isFinishing)
        localActivityManager = null
        detailContainer = null
        embeddedActivityId = null
        pendingSplashImageCallback = null
        super.onDestroyView()
    }

    private fun showEmbeddedActivity(id: String, activityClass: Class<*>) {
        val manager = localActivityManager ?: return
        val container = detailContainer ?: return
        embeddedActivityId = id
        val window = manager.startActivity(
            id,
            Intent(requireContext(), activityClass).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        ) ?: return
        val content = window.decorView
        HdPageMotion.showEmbedded(container, content)
    }

    override fun closeEmbeddedActivity(child: Activity?): Boolean {
        val manager = localActivityManager ?: return false
        val id = embeddedActivityId ?: return false
        val current = manager.currentActivity
        if (child != null && current != null && current !== child) return false
        embeddedActivityId = null
        detailContainer?.apply {
            HdPageMotion.closeEmbedded(this) {
                post { manager.destroyActivity(id, true) }
            }
        }
        return true
    }

    private fun confirmToggleTone() {
        if (LauncherActivity.isFollowingSystemTone(requireContext())) return
        val darkMode = LauncherActivity.isLauncherDarkMode(requireContext())
        val nextTone = getString(if (darkMode) R.string.home_light_mode else R.string.home_dark_mode)
        LauncherDialogFactory.showConfirm(
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
                    LauncherTheme.showUpdateResultDialog(
                        requireContext(),
                        info,
                        currentVersion,
                        hasUpdate,
                        null,
                    )
                }

                override fun onError(message: String) {
                    if (!isAdded) return
                    LauncherTheme.showUpdateResultDialog(
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
        LauncherDialogFactory.showStandardActionChoices(
            requireContext(),
            getString(R.string.home_feedback),
            arrayOf(
                getString(R.string.home_github_repository),
                getString(R.string.home_qq_group),
            ),
        ) { index ->
            openExternalUrl(if (index == 0) GITHUB_URL else QQ_GROUP_URL)
        }
    }

    private fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
            Toast.makeText(requireContext(), R.string.home_cannot_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val GITHUB_URL =
            "https://github.com/Weiss-UltimateSavior/RinneMobile"
        private const val QQ_GROUP_URL =
            "https://qun.qq.com/universal-share/share?ac=1&authKey=nZMa0s3mxxG1A0f%2BY0nAWmBYpul7FWTEDI6UWrzqb2IgKC4aDkUhvkV2AekAkW%2F1&busi_data=eyJncm91cENvZGUiOiIxNjM2MDM2MzUiLCJ0b2tlbiI6Im93eFRyY0tqNDdxK3FGQXlVZ0lhMEZGbWZWemphZnpYYW1kWWpPN1ViL3A0SkRUd1dEclMwZkM1bWI0UEYxME4iLCJ1aW4iOiIzMDg2Njc4NzU1In0%3D&data=bwoLG7XAPzqsvtfneNCQUUlu-HpX1yCn-6dkgd8ubDeBJKEPgd7wKYa6ym-EbW07Vapc3xm_o-iy0GbFHhZk5Q&svctype=4&tempid=h5_group_info"
    }
}
