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
import com.apps.common.LauncherInsetsHelper
import com.apps.theme.LauncherTheme
import com.apps.util.LauncherUrlOpener
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherToolboxBinding
import com.core.databinding.FragmentPadToolboxBinding

/**
 * 工具箱页（重构计划 9.9 阶段 113）：自 [LauncherToolboxActivity] 抽取全部逻辑。
 *
 * 承载方式：
 * - 竖屏：由 [LauncherToolboxActivity] 以宿主方式承载，内容布局 activity_launcher_toolbox.xml；
 * - Pad 横屏：由 [com.apps.PadUi.PadToolboxActivity] 承载，内容布局 fragment_pad_toolbox.xml；
 * - HD：由 [com.apps.HDModel.HdHomeFragment] 以子 Fragment 承载。
 *
 * 两种内容布局视图 ID 一致，统一用 [findViewById] 访问，避免按宿主分支访问绑定。
 */
class LauncherToolboxFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val isPad = activity is com.apps.PadUi.PadToolboxActivity
        return if (isPad) {
            FragmentPadToolboxBinding.inflate(inflater, container, false).root
        } else {
            ActivityLauncherToolboxBinding.inflate(inflater, container, false).root
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (activity !is HdModeActivity) LauncherTabletPortraitScaler.apply(view)
        LauncherInsetsHelper.applyTopInset(view, view.findViewById(R.id.toolboxScroll))
        LauncherTheme.applyPrimaryTone(view)
        LauncherTheme.longActionButton(view.findViewById(R.id.toolboxBack))
        view.findViewById<View>(R.id.toolUsefulUnpack).setOnClickListener {
            confirmOpenExternalTool(ToolboxTool.USEFULUNPACK, USEFULUNPACK_URL)
        }
        view.findViewById<View>(R.id.toolTermux).setOnClickListener {
            confirmOpenExternalTool(ToolboxTool.TERMUX, TERMUX_URL)
        }
        view.findViewById<View>(R.id.toolShizuku).setOnClickListener {
            confirmOpenExternalTool(ToolboxTool.SHIZUKU, SHIZUKU_URL)
        }
        view.findViewById<View>(R.id.toolWinlator).setOnClickListener {
            confirmOpenExternalTool(ToolboxTool.WINLATOR, WINLATOR_URL)
        }
        view.findViewById<View>(R.id.toolGaishi).setOnClickListener {
            confirmOpenExternalTool(getString(R.string.settings_tool_gaishi), GAISHI_URL)
        }
        view.findViewById<View>(R.id.toolPpsspp).setOnClickListener {
            confirmOpenExternalTool(ToolboxTool.PPSSPP, PPSSPP_URL)
        }
        view.findViewById<View>(R.id.toolLunabox).setOnClickListener {
            confirmOpenExternalTool(ToolboxTool.LUNABOX, LUNABOX_URL)
        }
        view.findViewById<View>(R.id.toolAzahar).setOnClickListener {
            confirmOpenExternalTool(ToolboxTool.AZAHARPLUS, AZAHARPLUS_URL)
        }
        view.findViewById<View>(R.id.toolArmsx3).setOnClickListener {
            confirmOpenExternalTool(ToolboxTool.ARMSX3, ARMSX3_URL)
        }
        view.findViewById<View>(R.id.toolEden).setOnClickListener {
            confirmOpenExternalTool(ToolboxTool.EDEN, EDEN_URL)
        }
        view.findViewById<View>(R.id.toolboxBack).setOnClickListener { requestClose() }
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

    /** 按承载宿主分派关闭：竖屏薄宿主 finish，Pad 横屏薄宿主 finish，HD 由父 Fragment 关闭子 Fragment。 */
    private fun requestClose() {
        when (val host = activity) {
            is LauncherToolboxActivity -> host.finishToolbox()
            is com.apps.PadUi.PadToolboxActivity -> host.finish()
            is HdModeActivity -> (parentFragment as? HdEmbeddedActivityOwner)?.closeEmbeddedActivity()
            else -> Unit
        }
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
        private const val ARMSX3_URL = "https://github.com/ARMSX2/ARMSX3/releases"
        private const val EDEN_URL = "https://git.eden-emu.dev/eden-emu/eden/releases"
    }
}
