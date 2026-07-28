package com.apps.settings

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.apps.LauncherActivity
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherAppSettingsBinding
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 应用设置页面；具体设置项将在后续功能中添加。 */
class LauncherAppSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLauncherAppSettingsBinding
    private val splashImagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) saveSplashImage(uri)
    }

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
        renderStartPageState()
        binding.appStartPageText.setOnClickListener { showStartPagePicker() }
        renderHomeStyleState()
        binding.appHomeStyleText.setOnClickListener { showHomeStylePicker() }
        renderSplashImageState()
        binding.appSplashImageText.setOnClickListener { showSplashImageConfirmDialog() }
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

    private fun showSplashImageConfirmDialog() {
        LauncherDialogFactory.showStandardConfirm(
            this,
            getString(R.string.app_splash_image_confirm_title),
            getString(R.string.app_splash_image_confirm_message),
            getString(R.string.app_splash_image_confirm_action),
        ) {
            splashImagePicker.launch("image/*")
        }
    }

    private fun showStartPagePicker() {
        val labels: Array<CharSequence> = arrayOf(
            getString(R.string.app_start_page_portrait),
            getString(R.string.app_start_page_landscape),
        )
        LauncherDialogFactory.showSingleChoice(
            this,
            getString(R.string.app_start_page_dialog_title),
            labels,
            if (LauncherActivity.isLandscapeStartupPage(this)) 1 else 0,
        ) { index ->
            LauncherActivity.setLandscapeStartupPage(this, index == 1)
            renderStartPageState()
        }
    }

    private fun showHomeStylePicker() {
        val labels: Array<CharSequence> = arrayOf(
            getString(R.string.app_home_style_default),
            getString(R.string.app_home_style_featured),
        )
        LauncherDialogFactory.showSingleChoice(
            this,
            getString(R.string.app_home_style_dialog_title),
            labels,
            if (LauncherActivity.isFeaturedHomeStyle(this)) 1 else 0,
        ) { index ->
            LauncherActivity.setFeaturedHomeStyle(this, index == 1)
            // 返回首页时由 LauncherActivity.onResume() 立即替换对应 Fragment，无需重启应用。
            finish()
        }
    }

    private fun saveSplashImage(uri: Uri) {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val destination = LauncherActivity.customSplashImageFile(this@LauncherAppSettingsActivity)
                val pending = File(destination.parentFile, "${destination.name}.pending")
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        pending.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext false
                    try {
                        Files.move(
                            pending.toPath(),
                            destination.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: Throwable) {
                        Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    true
                } catch (_: Throwable) {
                    false
                } finally {
                    if (pending.exists()) pending.delete()
                }
            }
            if (saved) {
                renderSplashImageState()
                Toast.makeText(this@LauncherAppSettingsActivity, R.string.app_splash_image_updated, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@LauncherAppSettingsActivity, R.string.app_splash_image_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderSplashImageState() {
        binding.appSplashImageText.setText(
            if (LauncherActivity.hasCustomSplashImage(this)) {
                R.string.app_splash_image_custom
            } else {
                R.string.app_splash_image_default
            }
        )
    }

    private fun renderStartPageState() {
        binding.appStartPageText.setText(
            if (LauncherActivity.isLandscapeStartupPage(this)) {
                R.string.app_start_page_landscape
            } else {
                R.string.app_start_page_portrait
            }
        )
    }

    private fun renderHomeStyleState() {
        binding.appHomeStyleText.setText(
            if (LauncherActivity.isFeaturedHomeStyle(this)) {
                R.string.app_home_style_featured
            } else {
                R.string.app_home_style_default
            }
        )
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
