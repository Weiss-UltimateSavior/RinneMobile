package com.apps

import android.graphics.Color
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.apps.data.LauncherViewModel
import com.apps.theme.LauncherTheme
import com.core.R
import com.core.databinding.ActivityLauncherBinding

/**
 * 底部导航栏的多套样式渲染（默认 / Pill / Card / LiquidGlass）与中央主题 Logo 应用。
 *
 * 从 LauncherActivity 抽出，保持原有动画时序、指示器位移与视觉行为不变。
 * 持有 LiquidGlass Compose 宿主所需的可观察状态，供 [LauncherActivity] 在组合 Compose 导航时读取。
 */
class LauncherNavRenderer(private val host: LauncherActivity) {

    var currentNavItem: LauncherViewModel.NavItem? = null
    private var navIndicatorReady = false

    val liquidGlassSelectedIndex = mutableIntStateOf(0)
    val liquidGlassPrimaryColor = mutableIntStateOf(Color.TRANSPARENT)
    val liquidGlassBackgroundColor = mutableIntStateOf(Color.TRANSPARENT)
    val liquidGlassLandscapeIcon = mutableIntStateOf(R.drawable.launcher_game_center_default)
    val liquidGlassDarkMode = mutableStateOf(false)

    private val binding: ActivityLauncherBinding? get() = host.launcherBinding

    fun renderSelectedNav(selectedItem: LauncherViewModel.NavItem?) {
        val b = binding ?: return
        val navItem = selectedItem ?: LauncherViewModel.NavItem.HOME
        applyLauncherThemeTone()
        if (LauncherNavigationMetrics.isLiquidGlassStyle(host)) {
            hideXmlNavigation(b)
            liquidGlassSelectedIndex.intValue = when (navItem) {
                LauncherViewModel.NavItem.HOME -> 0
                LauncherViewModel.NavItem.LIBRARY -> 1
                LauncherViewModel.NavItem.MANAGE -> 2
                LauncherViewModel.NavItem.ACCOUNT -> 3
            }
            return
        }
        if (LauncherNavigationMetrics.isCardStyle(host)) {
            b.bottomNav.visibility = View.GONE
            b.bottomNavShadow.visibility = View.GONE
            b.bottomNavPill.visibility = View.GONE
            b.bottomNavCardShadow.visibility = View.VISIBLE
            b.bottomNavCard.visibility = View.VISIBLE
            renderCardNav(navItem)
            return
        }
        if (LauncherNavigationMetrics.isPillStyle(host)) {
            b.bottomNav.visibility = View.GONE
            b.bottomNavShadow.visibility = View.GONE
            b.bottomNavPill.visibility = View.VISIBLE
            b.bottomNavCardShadow.visibility = View.GONE
            b.bottomNavCard.visibility = View.GONE
            renderPillNav(navItem)
            return
        }
        b.bottomNav.visibility = View.VISIBLE
        b.bottomNavShadow.visibility = View.VISIBLE
        b.bottomNavPill.visibility = View.GONE
        b.bottomNavCardShadow.visibility = View.GONE
        b.bottomNavCard.visibility = View.GONE
        setNavSelected(
            b.navHome,
            b.navHomeIcon,
            b.navHomeLabel,
            navItem == LauncherViewModel.NavItem.HOME
        )
        setNavSelected(
            b.navSavings,
            b.navSavingsIcon,
            b.navSavingsLabel,
            navItem == LauncherViewModel.NavItem.LIBRARY
        )
        setNavSelected(
            b.navCards,
            b.navCardsIcon,
            b.navCardsLabel,
            navItem == LauncherViewModel.NavItem.MANAGE
        )
        setNavSelected(
            b.navAccount,
            b.navAccountIcon,
            b.navAccountLabel,
            navItem == LauncherViewModel.NavItem.ACCOUNT
        )
        moveNavIndicator(navItem)
    }

    private fun hideXmlNavigation(b: ActivityLauncherBinding) {
        b.bottomNav.visibility = View.GONE
        b.bottomNavShadow.visibility = View.GONE
        b.bottomNavPill.visibility = View.GONE
        b.bottomNavCard.visibility = View.GONE
        b.bottomNavCardShadow.visibility = View.GONE
    }

    private fun renderPillNav(navItem: LauncherViewModel.NavItem) {
        val b = binding ?: return
        setPillNavSelected(b.navPillHome, b.navPillHomeIcon, b.navPillHomeLabel,
            navItem == LauncherViewModel.NavItem.HOME)
        setPillNavSelected(b.navPillLibrary, b.navPillLibraryIcon, b.navPillLibraryLabel,
            navItem == LauncherViewModel.NavItem.LIBRARY)
        setPillNavSelected(b.navPillManage, b.navPillManageIcon, b.navPillManageLabel,
            navItem == LauncherViewModel.NavItem.MANAGE)
        setPillNavSelected(b.navPillAccount, b.navPillAccountIcon, b.navPillAccountLabel,
            navItem == LauncherViewModel.NavItem.ACCOUNT)
        b.navPillLaunchCenterIcon.setColorFilter(LauncherTheme.primary(host))
    }

    private fun renderCardNav(navItem: LauncherViewModel.NavItem) {
        val b = binding ?: return
        setCardNavSelected(b.navCardHomeIcon, b.navCardHomeLabel,
            navItem == LauncherViewModel.NavItem.HOME)
        setCardNavSelected(b.navCardLibraryIcon, b.navCardLibraryLabel,
            navItem == LauncherViewModel.NavItem.LIBRARY)
        setCardNavSelected(b.navCardManageIcon, b.navCardManageLabel,
            navItem == LauncherViewModel.NavItem.MANAGE)
        setCardNavSelected(b.navCardAccountIcon, b.navCardAccountLabel,
            navItem == LauncherViewModel.NavItem.ACCOUNT)
        moveCardNavIndicator(navItem)
    }

    private fun setNavSelected(container: LinearLayout, icon: ImageView, label: TextView, selected: Boolean) {
        container.setBackgroundResource(R.drawable.launcher_nav_unselected)
        // 选中项始终使用当前主题主色；未选中项在浅色、深色模式下统一使用灰色。
        val color = if (selected) LauncherThemeStyle.primaryColor(host) else Color.GRAY
        icon.setColorFilter(color)
        label.setTextColor(color)
        label.setTypeface(null, android.graphics.Typeface.BOLD)
    }

    private fun setPillNavSelected(
        container: LinearLayout,
        icon: ImageView,
        label: TextView,
        selected: Boolean
    ) {
        (container.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.weight = if (selected) 1.55f else 0.8625f
            params.height = dp(if (selected) 44 else 45)
            params.marginStart = 0
            params.marginEnd = 0
            container.layoutParams = params
        }
        container.background = if (selected) {
            InsetDrawable(LauncherTheme.selectedChip(host), dp(4), 0, dp(6), 0)
        } else {
            null
        }
        val color = if (selected) LauncherTheme.onPrimary(host) else LauncherTheme.textMuted(host)
        icon.setColorFilter(color)
        label.setTextColor(color)
        label.visibility = if (selected) View.VISIBLE else View.GONE
    }

    private fun setCardNavSelected(
        icon: ImageView,
        label: TextView,
        selected: Boolean
    ) {
        val color = if (selected) LauncherTheme.primary(host) else LauncherTheme.textMuted(host)
        icon.setColorFilter(color)
        label.setTextColor(color)
        label.visibility = if (selected) View.VISIBLE else View.GONE
    }

    private fun moveCardNavIndicator(navItem: LauncherViewModel.NavItem) {
        val b = binding ?: return
        val target = cardNavTarget(navItem) ?: return
        if (!b.cardNavItems.isLaidOut || target.width <= 0) {
            b.cardNavItems.post { moveCardNavIndicator(navItem) }
            return
        }
        val indicatorWidth = (target.width * 0.72f).toInt()
        val left = target.left + (target.width - indicatorWidth) / 2
        val params = b.cardNavSelectionIndicator.layoutParams as FrameLayout.LayoutParams
        if (params.width != indicatorWidth) {
            params.width = indicatorWidth
            b.cardNavSelectionIndicator.layoutParams = params
        }
        b.cardNavSelectionIndicator.background = LauncherTheme.solidPrimary(host, 2f)
        b.cardNavSelectionIndicator.animate().cancel()
        b.cardNavSelectionIndicator.animate()
            .translationX(left.toFloat())
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withLayer()
            .start()
    }

    private fun moveNavIndicator(navItem: LauncherViewModel.NavItem) {
        val b = binding ?: return
        val target = navTarget(navItem) ?: return
        if (!b.bottomNav.isLaidOut || target.width <= 0) {
            b.bottomNav.post { moveNavIndicator(navItem) }
            return
        }

        // 指示器与 bottomNavItems 都是 bottomNav 的子 View，且默认水平 gravity 均为 start，
        // 二者 left 都等于 bottomNav 的 paddingLeft，所以只需用 target 在 bottomNavItems
        // 内部的 left 作为 translationX，避免重复叠加 paddingLeft 导致指示器整体右移。
        val left = target.left
        val params = b.navSelectionIndicator.layoutParams as FrameLayout.LayoutParams
        if (params.width != target.width) {
            params.width = target.width
            b.navSelectionIndicator.layoutParams = params
        }
        b.navSelectionIndicator.setBackgroundResource(R.drawable.launcher_nav_selected)
        if (!navIndicatorReady) {
            b.navSelectionIndicator.translationX = left.toFloat()
            navIndicatorReady = true
            return
        }
        b.navSelectionIndicator.animate().cancel()
        b.navSelectionIndicator.animate()
            .translationX(left.toFloat())
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withLayer()
            .start()
    }

    private fun navTarget(navItem: LauncherViewModel.NavItem): View? {
        val b = binding ?: return null
        if (navItem == LauncherViewModel.NavItem.LIBRARY) return b.navSavings
        if (navItem == LauncherViewModel.NavItem.MANAGE) return b.navCards
        if (navItem == LauncherViewModel.NavItem.ACCOUNT) return b.navAccount
        return b.navHome
    }

    private fun cardNavTarget(navItem: LauncherViewModel.NavItem): View? {
        val b = binding ?: return null
        if (navItem == LauncherViewModel.NavItem.LIBRARY) return b.navCardLibrary
        if (navItem == LauncherViewModel.NavItem.MANAGE) return b.navCardManage
        if (navItem == LauncherViewModel.NavItem.ACCOUNT) return b.navCardAccount
        return b.navCardHome
    }

    private fun applyLauncherThemeTone() {
        val b = binding ?: return
        refreshLiquidGlassThemeState()
        b.navLaunchCenterCircle.background = LauncherTheme.circleWithSoftShadow(host)
        val rinneTheme = LauncherThemeStyle.isRinne(host)
        val anriTheme = LauncherThemeStyle.isAnri(host)
        val xinhaitianTheme = LauncherThemeStyle.isXinhaitian(host)
        val natsumeTheme = LauncherThemeStyle.isNatsume(host)
        val themedIcon = rinneTheme || anriTheme || xinhaitianTheme || natsumeTheme
        b.navLaunchCenterImage.visibility = if (themedIcon) View.GONE else View.VISIBLE
        b.navLaunchCenterText.visibility = if (themedIcon) View.VISIBLE else View.GONE
        when {
            rinneTheme -> {
                b.navLaunchCenterText.setImageResource(R.drawable.launcher_theme_rinne_def)
                b.navLaunchCenterImage.clearColorFilter()
                b.navLaunchCenterText.setColorFilter(Color.WHITE)
            }
            anriTheme -> {
                b.navLaunchCenterText.setImageResource(R.drawable.launcher_theme_anri_def)
                b.navLaunchCenterImage.clearColorFilter()
                b.navLaunchCenterText.setColorFilter(Color.WHITE)
            }
            xinhaitianTheme -> {
                b.navLaunchCenterText.setImageResource(R.drawable.launcher_theme_xinhaitian_def)
                b.navLaunchCenterImage.clearColorFilter()
                b.navLaunchCenterText.setColorFilter(Color.WHITE)
            }
            natsumeTheme -> {
                b.navLaunchCenterText.setImageResource(R.drawable.launcher_theme_natsume_def)
                b.navLaunchCenterImage.clearColorFilter()
                b.navLaunchCenterText.setColorFilter(Color.WHITE)
            }
            else -> {
                b.navLaunchCenterImage.setColorFilter(Color.WHITE)
            }
        }
        applyCenterLogoScale(b.navLaunchCenterText, rinneTheme, anriTheme, xinhaitianTheme, natsumeTheme)
        b.navPillLaunchCenterIcon.apply {
            when {
                rinneTheme -> setImageResource(R.drawable.launcher_theme_rinne_def)
                anriTheme -> setImageResource(R.drawable.launcher_theme_anri_def)
                xinhaitianTheme -> setImageResource(R.drawable.launcher_theme_xinhaitian_def)
                natsumeTheme -> setImageResource(R.drawable.launcher_theme_natsume_def)
                else -> setImageResource(R.drawable.launcher_game_center_default)
            }
            setColorFilter(LauncherTheme.primary(host))
        }
        applyCenterLogoScale(b.navPillLaunchCenterIcon, rinneTheme, anriTheme, xinhaitianTheme, natsumeTheme)
    }

    fun refreshLiquidGlassThemeState() {
        liquidGlassPrimaryColor.intValue = LauncherTheme.primary(host)
        liquidGlassBackgroundColor.intValue = LauncherTheme.bg(host)
        liquidGlassLandscapeIcon.intValue = when {
            LauncherThemeStyle.isRinne(host) -> R.drawable.launcher_theme_rinne_def
            LauncherThemeStyle.isAnri(host) -> R.drawable.launcher_theme_anri_def
            LauncherThemeStyle.isXinhaitian(host) -> R.drawable.launcher_theme_xinhaitian_def
            LauncherThemeStyle.isNatsume(host) -> R.drawable.launcher_theme_natsume_def
            else -> R.drawable.launcher_game_center_default
        }
        liquidGlassDarkMode.value = LauncherPreferences.isDarkMode(host)
    }

    /**
     * 主题 Logo 的 PNG 透明边距并不一致；按默认游戏中心 Logo 的可视范围校正缩放。
     * 每个主题只使用一个缩放比例，避免为补偿画布留白而拉伸图案本身。
     */
    private fun applyCenterLogoScale(
        logo: ImageView,
        rinneTheme: Boolean,
        anriTheme: Boolean,
        xinhaitianTheme: Boolean,
        natsumeTheme: Boolean
    ) {
        val scale = when {
            rinneTheme -> 1.09f
            anriTheme -> 1.29f
            xinhaitianTheme -> 1.14f
            natsumeTheme -> 1.02f
            else -> 1f
        }
        logo.scaleX = scale
        logo.scaleY = scale
    }

    private fun dp(value: Int): Int {
        return (value * host.resources.displayMetrics.density).toInt()
    }
}
