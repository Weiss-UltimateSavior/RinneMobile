package com.apps.account

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.apps.LauncherActivity
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.yuki.yukihub.R
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

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
        val dialog = AlertDialog.Builder(activity).create().apply {
            setCancelable(true)
            setOnDismissListener { promptVisible = false }
            show()
        }
        LauncherMotion.applyDialogMotion(dialog)
        val window = dialog.window ?: return
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setLayout(dp(activity, 252), WindowManager.LayoutParams.WRAP_CONTENT)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(activity,22),dp(activity,20),dp(activity,22),dp(activity,16)); setBackgroundResource(R.drawable.launcher_dialog_bg) }
        root.addView(text(activity,"登录已过期",16f,true), LinearLayout.LayoutParams(-1,-2))
        root.addView(text(activity,"当前账号的登录状态已失效。重新登录后即可继续使用聊天、云同步和在线游玩统计。",12f,false), LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,dp(activity,13),0,0) })
        val actions=LinearLayout(activity).apply { gravity=Gravity.CENTER }
        val actionParams=LinearLayout.LayoutParams(-1,dp(activity,36)).apply { setMargins(0,dp(activity,16),0,0) }
        root.addView(actions,actionParams)
        actions.addView(text(activity,"稍后",13f,true).apply { LauncherTheme.secondaryButton(this); setOnClickListener { dialog.dismiss() } },LinearLayout.LayoutParams(0,-1,1f))
        actions.addView(text(activity,"重新登录",13f,true).apply { LauncherTheme.primaryButton(this); setOnClickListener { dialog.dismiss(); activity.startActivity(Intent(activity,LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(LauncherActivity.EXTRA_OPEN_ACCOUNT_LOGIN,true)) } },LinearLayout.LayoutParams(0,-1,1f).apply { setMargins(dp(activity,8),0,0,0) })
        window.setContentView(root)
    }
    private fun text(activity:Activity,value:String,size:Float,bold:Boolean)=TextView(activity).apply { text=value; gravity=Gravity.CENTER; textSize=size; setTextColor(ContextCompat.getColor(activity,if(bold) R.color.launcher_text_color else R.color.launcher_text_muted_color)); if(bold) setTypeface(null,Typeface.BOLD) }
    private fun dp(activity:Activity,value:Int)=(value*activity.resources.displayMetrics.density).roundToInt()
}
