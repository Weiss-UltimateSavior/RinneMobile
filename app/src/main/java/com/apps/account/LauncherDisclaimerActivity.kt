package com.apps.account

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.apps.LauncherActivity
import com.apps.LauncherEdgeToEdgeHelper
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherDisclaimerBinding
import com.core.launcherbridge.LauncherDisclaimerBridge

class LauncherDisclaimerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLauncherDisclaimerBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherActivity.applySavedToneMode(this); super.onCreate(savedInstanceState); LauncherEdgeToEdgeHelper.apply(this)
        binding = ActivityLauncherDisclaimerBinding.inflate(layoutInflater); setContentView(binding.root); LauncherTabletPortraitScaler.applyActivityContent(this)
        val content = binding.disclaimerContent; val left = content.paddingLeft; val top = content.paddingTop; val right = content.paddingRight; val bottom = content.paddingBottom
        binding.root.setOnApplyWindowInsetsListener { _, insets -> content.setPadding(left, top + insets.systemWindowInsetTop, right, bottom + insets.systemWindowInsetBottom); insets }; binding.root.requestApplyInsets()
        LauncherTheme.applyPrimaryTone(binding.root); LauncherTheme.longActionButton(binding.disclaimerClose)
        (content.getChildAt(1) as? ViewGroup)?.let { group -> repeat(group.childCount) { group.getChildAt(it).background = LauncherTheme.circle(this) } }
        binding.disclaimerTitle.text = LauncherDisclaimerBridge.getTitle(); binding.disclaimerBody.text = LauncherDisclaimerBridge.getContent(); binding.disclaimerClose.setOnClickListener { finish() }
    }
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase)) }
}
