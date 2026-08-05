package com.apps.theme

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.core.R
import com.core.util.RxMainScheduler

object LauncherMotion {
    @JvmStatic
    fun applyDialogMotion(dialog: Dialog?) {
        dialog?.window?.setWindowAnimations(R.style.LauncherDialogAnimation)
    }

    @JvmStatic
    fun applyActivityOpen(activity: Activity?) {
        activity?.overridePendingTransition(R.anim.launcher_activity_enter, R.anim.launcher_activity_exit)
    }

    @JvmStatic
    fun applyActivityClose(activity: Activity?) {
        activity?.overridePendingTransition(R.anim.launcher_activity_pop_enter, R.anim.launcher_activity_pop_exit)
    }

    @JvmStatic
    fun finish(activity: Activity?) {
        activity ?: return
        activity.finish()
        applyActivityClose(activity)
    }

    @JvmStatic
    fun pulse(view: View?) {
        view ?: return
        view.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .setDuration(110L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(130L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
            .start()
    }

    @JvmStatic
    fun recreateWithToneOverlay(activity: Activity?, beforeRecreate: Runnable?) {
        activity ?: return
        beforeRecreate?.run()
        activity.startActivity(Intent(activity, activity.javaClass))
        activity.finish()
        activity.overridePendingTransition(R.anim.launcher_tone_enter, R.anim.launcher_tone_exit)
    }

    @JvmStatic
    fun runAfterPulse(view: View?, action: Runnable?) {
        pulse(view)
        val target = view
        RxMainScheduler.postDelayed({
            // 150ms 内视图可能已随页面销毁而分离，先判窗口附着再执行回调（§8:302 守卫）
            if (target != null && target.isAttachedToWindow) action?.run()
        }, 150L)
    }
}
