package com.apps.account

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import com.apps.LauncherActivity
import com.apps.LauncherIntents
import com.apps.HDModel.LauncherDialogRouter
import com.core.R
import com.core.launcherbridge.LauncherAuthBridge
import java.lang.ref.WeakReference

/** Shows one actionable session-expiry prompt for the foreground Launcher page. */
object LauncherSessionExpiredNotifier : LauncherAuthBridge.SessionExpiredListener {
    private var resumedActivity = WeakReference<Activity>(null)
    private var promptVisible = false

    @JvmStatic
    fun install(application: Application) {
        LauncherAuthBridge.setSessionExpiredListener(this)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumedActivity = WeakReference(activity) }
            override fun onActivityDestroyed(activity: Activity) { if (resumedActivity.get() === activity) resumedActivity = WeakReference(null) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }

    override fun onSessionExpired() {
        val activity = resumedActivity.get()
        if (promptVisible || activity == null || activity.isFinishing || activity.isDestroyed) return
        promptVisible = true
        showDialog(activity)
    }

    override fun onSessionRestored() { promptVisible = false }

    private fun showDialog(activity: Activity) {
        LauncherDialogRouter.showConfirm(
            activity,
            activity.getString(R.string.social_session_expired_title),
            activity.getString(R.string.social_session_expired_message),
            activity.getString(R.string.social_action_log_in_again),
            Runnable {
                activity.startActivity(
                    Intent(activity, LauncherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(LauncherIntents.EXTRA_OPEN_ACCOUNT_LOGIN, true)
                )
            },
            activity.getString(R.string.social_action_later),
            Runnable { promptVisible = false },
        )
    }
}
