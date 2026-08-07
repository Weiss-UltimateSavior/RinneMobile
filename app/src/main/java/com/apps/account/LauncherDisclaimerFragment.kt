package com.apps.account

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.apps.HDModel.HdEmbeddedActivityOwner
import com.apps.HDModel.HdModeActivity
import com.apps.common.LauncherInsetsHelper
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherDisclaimerBinding
import com.core.launcherbridge.LauncherDisclaimerBridge

/**
 * 免责声明页（重构计划 9.9 阶段 109 自 LauncherDisclaimerActivity 抽取）。
 *
 * 竖屏由 [LauncherDisclaimerActivity] 薄宿主承载，HD 由
 * [com.apps.HDModel.HdSettingsFragment] 作为子 Fragment 承载。
 */
class LauncherDisclaimerFragment : Fragment() {
    private var binding: ActivityLauncherDisclaimerBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherDisclaimerBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (activity !is HdModeActivity) LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        val content = currentBinding.disclaimerContent
        LauncherInsetsHelper.applyTopAndBottomInsets(currentBinding.root, currentBinding.disclaimerContent)
        LauncherTheme.applyPrimaryTone(view)
        LauncherTheme.longActionButton(currentBinding.disclaimerClose)
        (content.getChildAt(1) as? ViewGroup)?.let { group ->
            repeat(group.childCount) { group.getChildAt(it).background = LauncherTheme.circle(requireContext()) }
        }
        currentBinding.disclaimerTitle.text = LauncherDisclaimerBridge.getTitle()
        currentBinding.disclaimerBody.text = LauncherDisclaimerBridge.getContent()
        currentBinding.disclaimerClose.setOnClickListener { requestClose() }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    /** 按承载宿主分派关闭：竖屏薄宿主 finish，HD 由父 Fragment 关闭子 Fragment。 */
    private fun requestClose() {
        when (val host = activity) {
            is LauncherDisclaimerActivity -> host.finishDisclaimer()
            is HdModeActivity -> (parentFragment as? HdEmbeddedActivityOwner)?.closeEmbeddedActivity()
            else -> Unit
        }
    }
}
