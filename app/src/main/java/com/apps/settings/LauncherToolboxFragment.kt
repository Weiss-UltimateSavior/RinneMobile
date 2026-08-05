package com.apps.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.apps.HDModel.HdEmbeddedActivityOwner
import com.apps.HDModel.HdModeActivity
import com.apps.HDModel.LauncherDialogRouter
import com.apps.theme.LauncherTheme
import com.apps.util.LauncherUrlOpener
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherToolboxBinding

/**
 * 工具箱页（重构计划 9.9 阶段 113）：自 [LauncherToolboxActivity] 抽取全部逻辑，
 * HD 由 [com.apps.HDModel.HdHomeFragment] 以子 Fragment 承载。
 */
class LauncherToolboxFragment : Fragment() {
    private var binding: ActivityLauncherToolboxBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherToolboxBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        applySystemBarInsets()
        LauncherTheme.applyPrimaryTone(view)
        LauncherTheme.longActionButton(currentBinding.toolboxBack)
        currentBinding.toolUsefulUnpack.setOnClickListener {
            confirmOpenExternalTool(ToolboxTool.USEFULUNPACK, USEFULUNPACK_URL)
        }
        currentBinding.toolTermux.setOnClickListener {
            confirmOpenExternalTool(ToolboxTool.TERMUX, TERMUX_URL)
        }
        currentBinding.toolShizuku.setOnClickListener {
            confirmOpenExternalTool(ToolboxTool.SHIZUKU, SHIZUKU_URL)
        }
        currentBinding.toolWinlator.setOnClickListener {
            confirmOpenExternalTool(ToolboxTool.WINLATOR, WINLATOR_URL)
        }
        currentBinding.toolGaishi.setOnClickListener {
            confirmOpenExternalTool(getString(R.string.settings_tool_gaishi), GAISHI_URL)
        }
        currentBinding.toolPpsspp.setOnClickListener {
            confirmOpenExternalTool(ToolboxTool.PPSSPP, PPSSPP_URL)
        }
        currentBinding.toolLunabox.setOnClickListener {
            confirmOpenExternalTool(ToolboxTool.LUNABOX, LUNABOX_URL)
        }
        currentBinding.toolAzahar.setOnClickListener {
            confirmOpenExternalTool(ToolboxTool.AZAHARPLUS, AZAHARPLUS_URL)
        }
        currentBinding.toolboxBack.setOnClickListener { requestClose() }
    }

    private fun confirmOpenExternalTool(name: String, url: String) {
        LauncherDialogRouter.showConfirm(
            requireContext(),
            getString(R.string.settings_open_download_title),
            getString(R.string.settings_open_download_message, name),
            getString(R.string.settings_confirm),
        ) {
            // 打开失败时提示用户，避免静默无响应
            if (!LauncherUrlOpener.open(requireContext(), url)) {
                Toast.makeText(requireContext(), R.string.home_cannot_open_link, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applySystemBarInsets() {
        val currentBinding = binding ?: return
        val scroll = currentBinding.toolboxScroll
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
            is LauncherToolboxActivity -> host.finishToolbox()
            is HdModeActivity -> (parentFragment as? HdEmbeddedActivityOwner)?.closeEmbeddedActivity()
            else -> Unit
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val USEFULUNPACK_URL = "https://github.com/znso4pa/usefulunpack/releases"
        private const val TERMUX_URL = "https://github.com/termux/termux-app/releases"
        private const val SHIZUKU_URL = "https://github.com/RikkaApps/Shizuku/releases"
        private const val WINLATOR_URL = "https://github.com/brunodev85/winlator/releases"
        private const val GAISHI_URL = "https://hub.xiaoji.com/zh-cn"
        private const val PPSSPP_URL = "https://www.ppsspp.org/"
        private const val LUNABOX_URL = "https://github.com/Saramanda9988/LunaBox/releases"
        private const val AZAHARPLUS_URL = "https://github.com/AzaharPlus/AzaharPlus/releases"
    }
}
