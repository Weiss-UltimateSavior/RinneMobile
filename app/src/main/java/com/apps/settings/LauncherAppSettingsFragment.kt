package com.apps.settings

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.apps.HDModel.HdEmbeddedActivityOwner
import com.apps.HDModel.HdModeActivity
import com.apps.LauncherActivity
import com.apps.home.HomeStyle
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherAppSettingsBinding
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用设置页（重构计划 9.9 阶段 109 自 LauncherAppSettingsActivity 抽取）。
 *
 * 竖屏由 [LauncherAppSettingsActivity] 薄宿主承载，HD 由
 * [com.apps.HDModel.HdSettingsFragment] 作为子 Fragment 承载；图片选择器使用
 * Fragment 自身 ActivityResultRegistry，HD 嵌入环境无需再委托宿主代理。
 */
class LauncherAppSettingsFragment : Fragment() {
    private var binding: ActivityLauncherAppSettingsBinding? = null
    private val splashImagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) saveSplashImage(uri)
        }
    private val portraitBackgroundPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) savePortraitBackground(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherAppSettingsBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        applySystemBarInsets()
        LauncherTheme.applyPrimaryTone(view)
        currentBinding.appLanguageText.text = languageLabels()[currentLanguageIndex()]
        currentBinding.appLanguageText.setOnClickListener { showLanguagePicker() }
        renderStartPageState()
        currentBinding.appStartPageText.setOnClickListener { showStartPagePicker() }
        renderHomeStyleState()
        currentBinding.appHomeStyleText.setOnClickListener { showHomeStylePicker() }
        renderNavigationStyleState()
        currentBinding.appNavigationStyleText.setOnClickListener { showNavigationStylePicker() }
        renderPortraitBackgroundState()
        currentBinding.appPortraitBackgroundText.setOnClickListener { showPortraitBackgroundChoices() }
        LauncherTheme.styleMaterialSwitch(currentBinding.appFollowSystemToneSwitch)
        currentBinding.appFollowSystemToneSwitch.isChecked = LauncherActivity.isFollowingSystemTone(requireContext())
        currentBinding.appFollowSystemToneSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == LauncherActivity.isFollowingSystemTone(requireContext())) return@setOnCheckedChangeListener
            LauncherActivity.setFollowingSystemTone(requireContext(), checked)
        }
        renderSplashImageState()
        currentBinding.appSplashImageText.setOnClickListener { showSplashImageConfirmDialog() }
        LauncherTheme.styleMaterialSwitch(currentBinding.appSplashImageSwitch)
        currentBinding.appSplashImageSwitch.isChecked = LauncherActivity.isSplashImageEnabled(requireContext())
        currentBinding.appSplashImageSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == LauncherActivity.isSplashImageEnabled(requireContext())) return@setOnCheckedChangeListener
            LauncherActivity.setSplashImageEnabled(requireContext(), checked)
        }
        LauncherTheme.styleMaterialSwitch(currentBinding.appHdModeStartupSwitch)
        currentBinding.appHdModeStartupSwitch.isChecked = LauncherActivity.isHdModeStartupEnabled(requireContext())
        currentBinding.appHdModeStartupSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == LauncherActivity.isHdModeStartupEnabled(requireContext())) return@setOnCheckedChangeListener
            LauncherActivity.setHdModeStartupEnabled(requireContext(), checked)
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun showLanguagePicker() {
        val labels = languageLabels()
        LauncherDialogFactory.showSingleChoice(
            requireContext(),
            getString(R.string.app_language_dialog_title),
            Array(labels.size) { labels[it] },
            currentLanguageIndex(),
        ) { index ->
            val languageTag = LANGUAGE_TAGS.getOrElse(index) { LANGUAGE_TAGS[0] }
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(languageTag),
            )
        }
    }

    private fun showSplashImageConfirmDialog() {
        LauncherDialogFactory.showStandardActionChoices(
            requireContext(),
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
            requireContext(),
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
        // Fragment 自身 ActivityResultRegistry 在 HD 嵌入环境下可直接接收回调，
        // 无需再委托宿主 Fragment 代理（原 HdModeActivity 代理路径已废弃）。
        splashImagePicker.launch("image/*")
    }

    private fun launchPortraitBackgroundPicker() {
        // 同上：Fragment 自有 ActivityResult 可靠接收 GetContent 回调。
        portraitBackgroundPicker.launch("image/*")
    }

    private fun showStartPagePicker() {
        val labels: Array<CharSequence> = arrayOf(
            getString(R.string.app_start_page_portrait),
            getString(R.string.app_start_page_landscape),
        )
        LauncherDialogFactory.showSingleChoice(
            requireContext(),
            getString(R.string.app_start_page_dialog_title),
            labels,
            if (LauncherActivity.isLandscapeStartupPage(requireContext())) 1 else 0,
        ) { index ->
            LauncherActivity.setLandscapeStartupPage(requireContext(), index == 1)
            renderStartPageState()
        }
    }

    private fun showHomeStylePicker() {
        val styles = HomeStyle.entries.toTypedArray()
        val labels = Array<CharSequence>(styles.size) { getString(styles[it].labelResId) }
        LauncherDialogFactory.showSingleChoice(
            requireContext(),
            getString(R.string.app_home_style_dialog_title),
            labels,
            styles.indexOf(LauncherActivity.getHomeStyle(requireContext())).coerceAtLeast(0),
        ) { index ->
            LauncherActivity.setHomeStyle(requireContext(), styles[index])
            // 返回首页时由 LauncherActivity.onResume() 立即替换对应 Fragment，无需重启应用。
            requestClose()
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
            requireContext(),
            getString(R.string.app_navigation_style_dialog_title),
            labels,
            when {
                LauncherActivity.isLiquidGlassNavigationStyle(requireContext()) -> 3
                LauncherActivity.isCardNavigationStyle(requireContext()) -> 2
                LauncherActivity.isPillNavigationStyle(requireContext()) -> 1
                else -> 0
            },
        ) { index ->
            LauncherActivity.setPillNavigationStyle(requireContext(), index == 1)
            LauncherActivity.setCardNavigationStyle(requireContext(), index == 2)
            LauncherActivity.setLiquidGlassNavigationStyle(requireContext(), index == 3)
            // 返回 Launcher 后由 onResume() 切换导航；进入或离开 Compose 玻璃宿主时重建页面。
            requestClose()
        }
    }

    private fun saveSplashImage(uri: Uri) {
        val context = requireContext().applicationContext
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val destination = LauncherActivity.customSplashImageFile(context)
                val pending = File(destination.parentFile, "${destination.name}.pending")
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        pending.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext false
                    moveReplacingAtomically(pending, destination)
                    true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IOException) {
                    false
                } catch (error: SecurityException) {
                    false
                } catch (error: IllegalArgumentException) {
                    false
                } finally {
                    if (pending.exists()) pending.delete()
                }
            }
            if (saved) {
                renderSplashImageState()
                Toast.makeText(requireContext(), R.string.app_splash_image_updated, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.app_splash_image_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun savePortraitBackground(uri: Uri) {
        val context = requireContext().applicationContext
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val destination = LauncherActivity.customPortraitBackgroundFile(context)
                copyUriAtomically(uri, destination)
            }
            if (saved) {
                LauncherActivity.invalidateCustomPortraitBackground(requireContext())
                renderPortraitBackgroundState()
                Toast.makeText(
                    requireContext(),
                    R.string.app_portrait_background_updated,
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.app_portrait_background_save_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun copyUriAtomically(uri: Uri, destination: File): Boolean {
        val context = requireContext().applicationContext
        val pending = File(destination.parentFile, "${destination.name}.pending")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                pending.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(pending.absolutePath, bounds)
            val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || pixels > 100_000_000L) {
                return false
            }
            moveReplacingAtomically(pending, destination)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: OutOfMemoryError) {
            throw error
        } catch (error: IOException) {
            false
        } catch (error: SecurityException) {
            false
        } catch (error: IllegalArgumentException) {
            false
        } finally {
            if (pending.exists()) pending.delete()
        }
    }

    private fun moveReplacingAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (error: IOException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun renderPortraitBackgroundState() {
        binding?.appPortraitBackgroundText?.setText(
            if (LauncherActivity.hasCustomPortraitBackground(requireContext())) {
                R.string.app_portrait_background_custom
            } else {
                R.string.app_portrait_background_solid
            },
        )
    }

    private fun restoreSolidPortraitBackground() {
        lifecycleScope.launch {
            val file = LauncherActivity.customPortraitBackgroundFile(requireContext())
            val restored = withContext(Dispatchers.IO) { !file.exists() || file.delete() }
            if (restored) {
                LauncherActivity.invalidateCustomPortraitBackground(requireContext())
                renderPortraitBackgroundState()
                Toast.makeText(
                    requireContext(),
                    R.string.app_portrait_background_restored,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun renderSplashImageState() {
        binding?.appSplashImageText?.setText(
            if (LauncherActivity.hasCustomSplashImage(requireContext())) {
                R.string.app_splash_image_custom
            } else {
                R.string.app_splash_image_default
            },
        )
    }

    private fun restoreDefaultSplashImage() {
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                LauncherActivity.customSplashImageFile(requireContext()).delete()
            }
            if (deleted) {
                renderSplashImageState()
                Toast.makeText(
                    requireContext(),
                    R.string.app_splash_image_restored,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun renderStartPageState() {
        binding?.appStartPageText?.setText(
            if (LauncherActivity.isLandscapeStartupPage(requireContext())) {
                R.string.app_start_page_landscape
            } else {
                R.string.app_start_page_portrait
            },
        )
    }

    private fun renderHomeStyleState() {
        binding?.appHomeStyleText?.setText(LauncherActivity.getHomeStyle(requireContext()).labelResId)
    }

    private fun renderNavigationStyleState() {
        binding?.appNavigationStyleText?.setText(
            when {
                LauncherActivity.isLiquidGlassNavigationStyle(requireContext()) -> R.string.app_navigation_style_liquid_glass
                LauncherActivity.isCardNavigationStyle(requireContext()) -> R.string.app_navigation_style_card
                LauncherActivity.isPillNavigationStyle(requireContext()) -> R.string.app_navigation_style_pill
                else -> R.string.app_navigation_style_default
            },
        )
    }

    private fun languageLabels(): Array<CharSequence> = arrayOf(
        getString(R.string.language_simplified_chinese),
        getString(R.string.language_english),
        getString(R.string.language_japanese),
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
        val currentBinding = binding ?: return
        val scroll = currentBinding.appSettingsScroll
        val left = scroll.paddingLeft
        val top = scroll.paddingTop
        val right = scroll.paddingRight
        val bottom = scroll.paddingBottom
        currentBinding.root.setOnApplyWindowInsetsListener { _, insets ->
            scroll.setPadding(left, top + insets.systemWindowInsetTop, right, bottom)
            insets
        }
        currentBinding.root.requestApplyInsets()
    }

    /** 按承载宿主分派关闭：竖屏薄宿主 finish，HD 由父 Fragment 关闭子 Fragment。 */
    private fun requestClose() {
        when (val host = activity) {
            is LauncherAppSettingsActivity -> host.finishAppSettings()
            is HdModeActivity -> (parentFragment as? HdEmbeddedActivityOwner)?.closeEmbeddedActivity()
            else -> Unit
        }
    }

    companion object {
        private val LANGUAGE_TAGS = arrayOf("zh-CN", "en", "ja")
    }
}
