package com.apps.settings

import android.content.Context
import android.graphics.BitmapFactory
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
import com.apps.HDModel.HdModeActivity
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
    private val portraitBackgroundPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) savePortraitBackground(uri)
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
        renderNavigationStyleState()
        binding.appNavigationStyleText.setOnClickListener { showNavigationStylePicker() }
        renderPortraitBackgroundState()
        binding.appPortraitBackgroundText.setOnClickListener { showPortraitBackgroundChoices() }
        LauncherTheme.styleMaterialSwitch(binding.appFollowSystemToneSwitch)
        binding.appFollowSystemToneSwitch.isChecked = LauncherActivity.isFollowingSystemTone(this)
        binding.appFollowSystemToneSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == LauncherActivity.isFollowingSystemTone(this)) return@setOnCheckedChangeListener
            LauncherActivity.setFollowingSystemTone(this, checked)
        }
        renderSplashImageState()
        binding.appSplashImageText.setOnClickListener { showSplashImageConfirmDialog() }
        LauncherTheme.styleMaterialSwitch(binding.appSplashImageSwitch)
        binding.appSplashImageSwitch.isChecked = LauncherActivity.isSplashImageEnabled(this)
        binding.appSplashImageSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == LauncherActivity.isSplashImageEnabled(this)) return@setOnCheckedChangeListener
            LauncherActivity.setSplashImageEnabled(this, checked)
        }
        LauncherTheme.styleMaterialSwitch(binding.appHdModeStartupSwitch)
        binding.appHdModeStartupSwitch.isChecked = LauncherActivity.isHdModeStartupEnabled(this)
        binding.appHdModeStartupSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == LauncherActivity.isHdModeStartupEnabled(this)) return@setOnCheckedChangeListener
            LauncherActivity.setHdModeStartupEnabled(this, checked)
        }
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
        LauncherDialogFactory.showStandardActionChoices(
            this,
            getString(R.string.app_splash_image_label),
            arrayOf(
                getString(R.string.app_splash_image_restore_default),
                getString(R.string.app_splash_image_change),
            ),
        ) { index ->
            when (index) {
                0 -> restoreDefaultSplashImage()
                1 -> launchSplashImagePicker()
            }
        }
    }

    private fun showPortraitBackgroundChoices() {
        LauncherDialogFactory.showStandardActionChoices(
            this,
            getString(R.string.app_portrait_background_label),
            arrayOf(
                getString(R.string.app_portrait_background_restore_solid),
                getString(R.string.app_portrait_background_choose_image),
            ),
        ) { index ->
            when (index) {
                0 -> restoreSolidPortraitBackground()
                1 -> launchPortraitBackgroundPicker()
            }
        }
    }

    private fun launchSplashImagePicker() {
        // 嵌入到 HdModeActivity 时，LocalActivityManager 不会把 Activity Result 回调
        // 派发回本 Activity，因此需要委托给宿主 Fragment 启动图片选择器。
        val host = parent as? HdModeActivity
        if (host != null && host.launchSplashImagePicker { uri -> onPicked(uri) }) return
        splashImagePicker.launch("image/*")
    }

    private fun launchPortraitBackgroundPicker() {
        // LocalActivityManager cannot deliver Activity Result callbacks to an embedded Activity.
        val host = parent as? HdModeActivity
        if (host != null && host.launchSplashImagePicker { uri ->
                if (uri != null) savePortraitBackground(uri)
            }) return
        portraitBackgroundPicker.launch("image/*")
    }

    private fun onPicked(uri: Uri?) {
        if (uri != null) saveSplashImage(uri)
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

    private fun showNavigationStylePicker() {
        val labels: Array<CharSequence> = arrayOf(
            getString(R.string.app_navigation_style_default),
            getString(R.string.app_navigation_style_pill),
            getString(R.string.app_navigation_style_card),
            getString(R.string.app_navigation_style_liquid_glass),
        )
        LauncherDialogFactory.showSingleChoice(
            this,
            getString(R.string.app_navigation_style_dialog_title),
            labels,
            when {
                LauncherActivity.isLiquidGlassNavigationStyle(this) -> 3
                LauncherActivity.isCardNavigationStyle(this) -> 2
                LauncherActivity.isPillNavigationStyle(this) -> 1
                else -> 0
            },
        ) { index ->
            LauncherActivity.setPillNavigationStyle(this, index == 1)
            LauncherActivity.setCardNavigationStyle(this, index == 2)
            LauncherActivity.setLiquidGlassNavigationStyle(this, index == 3)
            // 返回 Launcher 后由 onResume() 切换导航；进入或离开 Compose 玻璃宿主时重建页面。
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

    private fun savePortraitBackground(uri: Uri) {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val destination = LauncherActivity.customPortraitBackgroundFile(
                    this@LauncherAppSettingsActivity,
                )
                copyUriAtomically(uri, destination)
            }
            if (saved) {
                LauncherActivity.invalidateCustomPortraitBackground(this@LauncherAppSettingsActivity)
                renderPortraitBackgroundState()
                Toast.makeText(
                    this@LauncherAppSettingsActivity,
                    R.string.app_portrait_background_updated,
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                Toast.makeText(
                    this@LauncherAppSettingsActivity,
                    R.string.app_portrait_background_save_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun copyUriAtomically(uri: Uri, destination: File): Boolean {
        val pending = File(destination.parentFile, "${destination.name}.pending")
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                pending.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(pending.absolutePath, bounds)
            val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || pixels > 100_000_000L) {
                return false
            }
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

    private fun renderPortraitBackgroundState() {
        binding.appPortraitBackgroundText.setText(
            if (LauncherActivity.hasCustomPortraitBackground(this)) {
                R.string.app_portrait_background_custom
            } else {
                R.string.app_portrait_background_solid
            },
        )
    }

    private fun restoreSolidPortraitBackground() {
        lifecycleScope.launch {
            val file = LauncherActivity.customPortraitBackgroundFile(this@LauncherAppSettingsActivity)
            val restored = withContext(Dispatchers.IO) { !file.exists() || file.delete() }
            if (restored) {
                LauncherActivity.invalidateCustomPortraitBackground(this@LauncherAppSettingsActivity)
                renderPortraitBackgroundState()
                Toast.makeText(
                    this@LauncherAppSettingsActivity,
                    R.string.app_portrait_background_restored,
                    Toast.LENGTH_SHORT,
                ).show()
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

    private fun restoreDefaultSplashImage() {
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                LauncherActivity.customSplashImageFile(this@LauncherAppSettingsActivity).delete()
            }
            if (deleted) {
                renderSplashImageState()
                Toast.makeText(
                    this@LauncherAppSettingsActivity,
                    R.string.app_splash_image_restored,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
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

    private fun renderNavigationStyleState() {
        binding.appNavigationStyleText.setText(
            when {
                LauncherActivity.isLiquidGlassNavigationStyle(this) -> R.string.app_navigation_style_liquid_glass
                LauncherActivity.isCardNavigationStyle(this) -> R.string.app_navigation_style_card
                LauncherActivity.isPillNavigationStyle(this) -> R.string.app_navigation_style_pill
                else -> R.string.app_navigation_style_default
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
