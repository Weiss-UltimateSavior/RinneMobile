package com.apps.settings

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.apps.LauncherActivity
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherAppSettingsBinding

/** 应用设置页面；具体设置项将在后续功能中添加。 */
class LauncherAppSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLauncherAppSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherActivity.applySavedToneMode(this)
        super.onCreate(savedInstanceState)
        configureEdgeToEdgeWindow()

        binding = ActivityLauncherAppSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        LauncherTabletPortraitScaler.applyActivityContent(this)
        applySystemBarInsets()
        LauncherTheme.applyPrimaryTone(binding.root)
        binding.appLanguageText.text = languageLabels()[currentLanguageIndex()]
        binding.appLanguageText.setOnClickListener { showLanguagePicker() }
    }

    private fun showLanguagePicker() {
        val labels = languageLabels()
        LauncherDialogFactory.showSingleChoice(
            this,
            getString(R.string.app_language_dialog_title),
            Array(labels.size) { labels[it] },
            currentLanguageIndex()
        ) { index ->
            val languageTag = LANGUAGE_TAGS.getOrElse(index) { LANGUAGE_TAGS[0] }
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(languageTag)
            )
        }
    }

    private fun languageLabels(): Array<CharSequence> = arrayOf(
        getString(R.string.language_simplified_chinese),
        getString(R.string.language_english),
        getString(R.string.language_japanese)
    )

    private fun currentLanguageIndex(): Int {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val language = if (!appLocales.isEmpty) {
            appLocales[0]?.language
        } else {
            resources.configuration.locales[0].language
        }
        return when (language) {
            "en" -> 1
            "ja" -> 2
            else -> 0
        }
    }

    private fun applySystemBarInsets() {
        val scroll = binding.appSettingsScroll
        val left = scroll.paddingLeft
        val top = scroll.paddingTop
        val right = scroll.paddingRight
        val bottom = scroll.paddingBottom
        binding.root.setOnApplyWindowInsetsListener { _, insets ->
            scroll.setPadding(left, top + insets.systemWindowInsetTop, right, bottom)
            insets
        }
        binding.root.requestApplyInsets()
    }

    private fun configureEdgeToEdgeWindow() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = ContextCompat.getColor(this, R.color.launcher_bg_color)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                if (!LauncherActivity.isLauncherDarkMode(this)) {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                        View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                } else {
                    0
                }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase))
    }

    companion object {
        private val LANGUAGE_TAGS = arrayOf("zh-CN", "en", "ja")
    }
}
