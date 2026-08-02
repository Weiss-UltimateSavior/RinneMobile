package com.apps

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherPendingBinding

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
    private fun configureWindow() {
        LauncherEdgeToEdgeHelper.apply(this)
    }
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase)) }
}
