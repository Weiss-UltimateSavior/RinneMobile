package com.apps

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

object LauncherUiMode {

    @JvmStatic
    fun applySavedToneMode(activity: AppCompatActivity?) {
        if (activity == null) return
        activity.delegate.setLocalNightMode(
            if (LauncherPreferences.isFollowingSystemTone(activity)) AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else if (LauncherPreferences.isDarkMode(activity)) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    @JvmStatic
    fun wrap(base: Context?): Context? {
        if (base == null) return null
        if (LauncherPreferences.isFollowingSystemTone(base)) return base
        val configuration = Configuration(base.resources.configuration)
        val targetNightMode = if (LauncherPreferences.isDarkMode(base))
            Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or targetNightMode
        return base.createConfigurationContext(configuration)
    }
}
