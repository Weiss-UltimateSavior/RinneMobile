package com.apps.theme

import android.app.Activity
import com.apps.LauncherActivity
import com.apps.HDModel.LauncherDialogRouter
import com.core.R

/**
 * Shared light/dark appearance switcher for Launcher surfaces.
 *
 * The optional [onFollowingSystemTone] callback is invoked instead of opening the confirmation
 * dialog when the user has enabled the system appearance setting.
 */
object LauncherToneSwitcher {
    @JvmStatic
    fun confirmToggle(activity: Activity?, onFollowingSystemTone: Runnable? = null) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        if (LauncherActivity.isFollowingSystemTone(activity)) {
            onFollowingSystemTone?.run()
            return
        }
        val darkMode = LauncherActivity.isLauncherDarkMode(activity)
        val nextTone = activity.getString(if (darkMode) R.string.home_light_mode else R.string.home_dark_mode)
        LauncherDialogRouter.showConfirm(
            activity,
            activity.getString(R.string.home_switch_tone),
            activity.getString(R.string.home_switch_tone_message, nextTone),
            activity.getString(R.string.core_confirm),
        ) {
            LauncherMotion.recreateWithToneOverlay(activity) {
                LauncherActivity.setLauncherDarkMode(activity, !darkMode)
            }
        }
    }
}
