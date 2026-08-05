package com.apps.theme

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.apps.HDModel.HdEmbeddedActivityOwner
import com.apps.HDModel.HdModeActivity
import com.apps.HDModel.LauncherDialogRouter
import com.apps.LauncherActivity
import com.apps.LauncherPreferences
import com.apps.LauncherThemeStyle
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherThemeMenuBinding

/**
 * 主题菜单页（重构计划 9.9 阶段 109 自 LauncherThemeMenuActivity 抽取）。
 *
 * 竖屏由 [LauncherThemeMenuActivity] 薄宿主承载，HD 由
 * [com.apps.HDModel.HdSettingsFragment] 作为子 Fragment 承载。
 */
class LauncherThemeMenuFragment : Fragment() {
    private var binding: ActivityLauncherThemeMenuBinding? = null
    private var selectedTheme = LauncherThemeStyle.THEME_STYLE_DEFAULT

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherThemeMenuBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        selectedTheme = LauncherActivity.getLauncherThemeStyle(requireContext())
        applySystemBarInsets()
        bindActions()
        LauncherTheme.applyPrimaryTone(view)
        LauncherTheme.longActionButton(currentBinding.themeMenuApply)
        applyIconTone()
        renderSelection()
        renderParticleToggle()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun applySystemBarInsets() {
        val currentBinding = binding ?: return
        val originalLeft = currentBinding.themeMenuScroll.paddingLeft
        val originalTop = currentBinding.themeMenuScroll.paddingTop
        val originalRight = currentBinding.themeMenuScroll.paddingRight
        val originalBottom = currentBinding.themeMenuScroll.paddingBottom
        currentBinding.root.setOnApplyWindowInsetsListener { _, insets ->
            currentBinding.themeMenuScroll.setPadding(
                originalLeft,
                originalTop + insets.systemWindowInsetTop,
                originalRight,
                originalBottom,
            )
            insets
        }
        currentBinding.root.requestApplyInsets()
    }

    private fun bindActions() {
        val currentBinding = binding ?: return
        currentBinding.freshThemeRow.setOnClickListener { selectTheme(LauncherThemeStyle.THEME_STYLE_DEFAULT) }
        currentBinding.nightThemeRow.setOnClickListener { selectTheme(LauncherThemeStyle.THEME_STYLE_RINNE) }
        currentBinding.pinkThemeRow.setOnClickListener { selectTheme(LauncherThemeStyle.THEME_STYLE_ANRI) }
        currentBinding.xinhaitianThemeRow.setOnClickListener { selectTheme(LauncherThemeStyle.THEME_STYLE_XINHAITIAN) }
        currentBinding.natsumeThemeRow.setOnClickListener { selectTheme(LauncherThemeStyle.THEME_STYLE_NATSUME) }
        currentBinding.particleToggleRow.setOnClickListener { showParticleStyleDialog() }
        currentBinding.themeMenuApply.setOnClickListener { applySelectedTheme() }
    }

    private fun applyIconTone() {
        val currentBinding = binding ?: return
        currentBinding.freshThemeIcon.background = LauncherTheme.circle(requireContext(), LauncherTheme.primary(requireContext()))
        currentBinding.freshThemeIcon.clipToOutline = true
        currentBinding.rinneThemeLogo.background = LauncherTheme.circle(requireContext(), LauncherThemeStyle.RINNE_PRIMARY_COLOR)
        currentBinding.rinneThemeLogo.clipToOutline = true
        currentBinding.anriThemeLogo.background = LauncherTheme.circle(requireContext(), LauncherThemeStyle.ANRI_PRIMARY_COLOR)
        currentBinding.anriThemeLogo.clipToOutline = true
        currentBinding.xinhaitianThemeLogo.background = LauncherTheme.xinhaitianCircle(requireContext())
        currentBinding.xinhaitianThemeLogo.clipToOutline = true
        currentBinding.natsumeThemeLogo.background = LauncherTheme.circle(requireContext(), LauncherThemeStyle.NATSUME_PRIMARY_COLOR)
        currentBinding.natsumeThemeLogo.clipToOutline = true
        currentBinding.particleToggleIcon.background = LauncherTheme.circle(requireContext())
    }

    private fun selectTheme(themeName: String) {
        selectedTheme = themeName
        renderSelection()
    }

    private fun renderSelection() {
        val currentBinding = binding ?: return
        val freshSelected = LauncherThemeStyle.THEME_STYLE_DEFAULT == selectedTheme
        val nightSelected = LauncherThemeStyle.THEME_STYLE_RINNE == selectedTheme
        val pinkSelected = LauncherThemeStyle.THEME_STYLE_ANRI == selectedTheme
        val xinhaitianSelected = LauncherThemeStyle.THEME_STYLE_XINHAITIAN == selectedTheme
        val natsumeSelected = LauncherThemeStyle.THEME_STYLE_NATSUME == selectedTheme

        currentBinding.freshThemeRow.setBackgroundResource(if (freshSelected) 0 else R.drawable.launcher_chat_option_bg)
        if (freshSelected) currentBinding.freshThemeRow.background = LauncherTheme.selectedOption(requireContext())
        currentBinding.nightThemeRow.setBackgroundResource(if (nightSelected) 0 else R.drawable.launcher_chat_option_bg)
        if (nightSelected) currentBinding.nightThemeRow.background = LauncherTheme.selectedOption(requireContext())
        currentBinding.pinkThemeRow.setBackgroundResource(if (pinkSelected) 0 else R.drawable.launcher_chat_option_bg)
        if (pinkSelected) currentBinding.pinkThemeRow.background = LauncherTheme.selectedOption(requireContext())
        currentBinding.xinhaitianThemeRow.setBackgroundResource(if (xinhaitianSelected) 0 else R.drawable.launcher_chat_option_bg)
        if (xinhaitianSelected) currentBinding.xinhaitianThemeRow.background = LauncherTheme.selectedOption(requireContext())
        currentBinding.natsumeThemeRow.setBackgroundResource(if (natsumeSelected) 0 else R.drawable.launcher_chat_option_bg)
        if (natsumeSelected) currentBinding.natsumeThemeRow.background = LauncherTheme.selectedOption(requireContext())

        currentBinding.freshThemeCheck.visibility = if (freshSelected) View.VISIBLE else View.INVISIBLE
        currentBinding.nightThemeCheck.visibility = if (nightSelected) View.VISIBLE else View.INVISIBLE
        currentBinding.pinkThemeCheck.visibility = if (pinkSelected) View.VISIBLE else View.INVISIBLE
        currentBinding.xinhaitianThemeCheck.visibility = if (xinhaitianSelected) View.VISIBLE else View.INVISIBLE
        currentBinding.natsumeThemeCheck.visibility = if (natsumeSelected) View.VISIBLE else View.INVISIBLE
    }

    private fun applySelectedTheme() {
        val context = requireContext()
        when (selectedTheme) {
            LauncherThemeStyle.THEME_STYLE_RINNE -> {
                LauncherThemeStyle.setThemeStyle(context, LauncherThemeStyle.THEME_STYLE_RINNE)
                Toast.makeText(context, R.string.theme_rinne_applied, Toast.LENGTH_SHORT).show()
            }
            LauncherThemeStyle.THEME_STYLE_ANRI -> {
                LauncherThemeStyle.setThemeStyle(context, LauncherThemeStyle.THEME_STYLE_ANRI)
                Toast.makeText(context, R.string.theme_anri_applied, Toast.LENGTH_SHORT).show()
            }
            LauncherThemeStyle.THEME_STYLE_XINHAITIAN -> {
                LauncherThemeStyle.setThemeStyle(context, LauncherThemeStyle.THEME_STYLE_XINHAITIAN)
                Toast.makeText(context, R.string.theme_xinhaitian_applied, Toast.LENGTH_SHORT).show()
            }
            LauncherThemeStyle.THEME_STYLE_NATSUME -> {
                LauncherThemeStyle.setThemeStyle(context, LauncherThemeStyle.THEME_STYLE_NATSUME)
                Toast.makeText(context, R.string.theme_natsume_applied, Toast.LENGTH_SHORT).show()
            }
            LauncherThemeStyle.THEME_STYLE_DEFAULT -> {
                LauncherThemeStyle.setThemeStyle(context, LauncherThemeStyle.THEME_STYLE_DEFAULT)
                Toast.makeText(context, R.string.theme_default_restored, Toast.LENGTH_SHORT).show()
            }
            else -> {
                Toast.makeText(context, getString(R.string.theme_not_available, selectedTheme), Toast.LENGTH_SHORT).show()
                return
            }
        }
        requestClose()
    }

    private fun showParticleStyleDialog() {
        val context = requireContext()
        val enabled = LauncherActivity.isLauncherParticlesEnabled(context)
        val selectedStyle = LauncherActivity.getLauncherParticleStyle(context)
        val styles = arrayOf(
            LauncherPreferences.PARTICLE_STYLE_FLOATING,
            LauncherPreferences.PARTICLE_STYLE_RAIN,
            LauncherPreferences.PARTICLE_STYLE_STAR,
            LauncherPreferences.PARTICLE_STYLE_SAKURA,
            LauncherPreferences.PARTICLE_STYLE_FIREFLIES,
            LauncherPreferences.PARTICLE_STYLE_CONSTELLATION,
            LauncherPreferences.PARTICLE_STYLE_RIPPLES,
        )
        val labels = arrayOf<CharSequence>(
            getString(R.string.theme_particle_floating),
            getString(R.string.theme_particle_rain),
            getString(R.string.theme_particle_star),
            getString(R.string.theme_particle_button_waterfall),
            getString(R.string.theme_particle_fireflies),
            getString(R.string.theme_particle_constellation),
            getString(R.string.theme_particle_ripples),
            getString(R.string.theme_particles_off),
        )
        var checkedIndex = styles.size // 关闭位置 = 7
        if (enabled) {
            for (i in styles.indices) {
                if (styles[i] == selectedStyle) {
                    checkedIndex = i
                    break
                }
            }
        }
        LauncherDialogRouter.showSingleChoice(
            context,
            getString(R.string.theme_particle_style_title),
            labels,
            checkedIndex,
        ) { index ->
            if (index == styles.size) {
                LauncherActivity.setLauncherParticlesEnabled(context, false)
                renderParticleToggle()
                Toast.makeText(context, R.string.theme_particles_disabled, Toast.LENGTH_SHORT).show()
                return@showSingleChoice
            }
            LauncherActivity.setLauncherParticleStyle(context, styles[index])
            LauncherActivity.setLauncherParticlesEnabled(context, true)
            renderParticleToggle()
            Toast.makeText(context, getString(R.string.theme_particle_applied, labels[index]), Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderParticleToggle() {
        binding?.particleToggleState?.setText(R.string.theme_configure)
        binding?.particleToggleState?.let { LauncherTheme.chip(it, true) }
    }

    /** 按承载宿主分派关闭：竖屏薄宿主 LauncherMotion.finish，HD 由父 Fragment 关闭子 Fragment。 */
    private fun requestClose() {
        when (val host = activity) {
            is LauncherThemeMenuActivity -> host.finishThemeMenu()
            is HdModeActivity -> (parentFragment as? HdEmbeddedActivityOwner)?.closeEmbeddedActivity()
            else -> Unit
        }
    }
}
