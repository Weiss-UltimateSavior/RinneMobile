package com.apps

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.yuki.yukihub.R
import com.yuki.yukihub.databinding.ActivityLauncherPendingBinding

class LauncherPendingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLauncherPendingBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherActivity.applySavedToneMode(this)
        super.onCreate(savedInstanceState)
        configureWindow()
        binding = ActivityLauncherPendingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        LauncherTabletPortraitScaler.applyActivityContent(this)
        val content = binding.pendingContent
        val left = content.paddingLeft; val top = content.paddingTop; val right = content.paddingRight; val bottom = content.paddingBottom
        binding.root.setOnApplyWindowInsetsListener { _, insets -> content.setPadding(left, top + insets.systemWindowInsetTop, right, bottom + insets.systemWindowInsetBottom); insets }
        binding.root.requestApplyInsets()
        LauncherTheme.applyPrimaryTone(binding.root); LauncherTheme.longActionButton(binding.pendingClose)
        (content.getChildAt(1) as? ViewGroup)?.let { group -> repeat(group.childCount) { group.getChildAt(it).background = LauncherTheme.circle(this) } }
        binding.pendingClose.setOnClickListener { finish() }
    }
    private fun configureWindow() { window.apply { clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN); addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS); statusBarColor = Color.TRANSPARENT; navigationBarColor = ContextCompat.getColor(this@LauncherPendingActivity, R.color.launcher_bg_color); decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or if (!LauncherActivity.isLauncherDarkMode(this@LauncherPendingActivity)) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0 } }
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase)) }
}
