package com.core.engine

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import java.util.WeakHashMap

/** Shared double-back-to-exit helper for engine activities. */
object DoubleBackExit {
    private const val EXIT_WINDOW_MS = 2_000L
    private val lastBackTime = WeakHashMap<Activity, Long>()
    private val suppressBackUp = WeakHashMap<Activity, Boolean>()

    @JvmStatic
    fun shouldExit(activity: Activity?): Boolean {
        activity ?: return true
        val now = SystemClock.elapsedRealtime()
        val last = lastBackTime[activity]
        if (last != null && now - last <= EXIT_WINDOW_MS) {
            lastBackTime.remove(activity)
            return true
        }
        lastBackTime[activity] = now
        return false
    }

    @JvmStatic
    fun handleBack(activity: Activity?, action: ExitAction?) {
        if (shouldExit(activity)) action?.exit()
    }

    @JvmStatic
    fun dispatchBackKey(activity: Activity?, event: KeyEvent?, action: ExitAction?): Boolean {
        if (event == null || event.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                activity?.let { suppressBackUp[it] = true }
                handleBack(activity, action)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && activity != null && suppressBackUp.remove(activity) != null) {
            return true
        }
        return event.action == KeyEvent.ACTION_MULTIPLE
    }

    @JvmStatic
    fun clear(activity: Activity?) {
        activity ?: return
        lastBackTime.remove(activity)
        suppressBackUp.remove(activity)
    }

    fun interface ExitAction {
        fun exit()
    }
}
