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
import com.core.databinding.ActivityLauncherMetadataSourceBinding
import com.core.launcherbridge.LauncherMetadataBridge
import com.core.metadata.MetadataController

/**
 * 元数据源设置页（重构计划 9.9 阶段 110 自 LauncherMetadataSourceActivity 抽取）。
 *
 * 竖屏由 [LauncherMetadataSourceActivity] 薄宿主承载，HD 由
 * [com.apps.HDModel.HdManageFragment] 作为子 Fragment 承载。
 */
class LauncherMetadataSourceFragment : Fragment() {
    private var binding: ActivityLauncherMetadataSourceBinding? = null
    private var selectedMetadataSourceIndex = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherMetadataSourceBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (activity !is HdModeActivity) LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        LauncherInsetsHelper.applyTopInset(currentBinding.root, currentBinding.sourceScroll)
        bindActions()
        applyThemeTone()
        loadConfig(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_METADATA_SOURCE_INDEX, selectedMetadataSourceIndex)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun bindActions() {
        val currentBinding = binding ?: return
        currentBinding.btnSave.setOnClickListener { save() }
        currentBinding.btnCancel.setOnClickListener { requestClose() }
        currentBinding.tokenLink.setOnClickListener { openTokenUrl() }
        currentBinding.sourceText.setOnClickListener { showMetadataSourcePicker() }
    }

    private fun applyThemeTone() {
        val currentBinding = binding ?: return
        LauncherTheme.applyPrimaryTone(requireView())
        LauncherTheme.styleTextInput(currentBinding.tokenInput)
        LauncherTheme.longActionButton(currentBinding.btnSave)
        LauncherTheme.longActionButton(currentBinding.btnCancel)
    }

    private fun loadConfig(savedInstanceState: Bundle?) {
        val currentBinding = binding ?: return
        val current = LauncherMetadataBridge.getMetadataSource(requireContext())
        var selection = 0
        if (MetadataController.SOURCE_BANGUMI == current) selection = 1
        else if (MetadataController.SOURCE_BANGUMI_MIRROR == current) selection = 2
        else if (MetadataController.SOURCE_YMGAL == current) selection = 3
        setMetadataSourceSelection(
            if (savedInstanceState != null && savedInstanceState.containsKey(STATE_METADATA_SOURCE_INDEX)) {
                savedInstanceState.getInt(STATE_METADATA_SOURCE_INDEX, 0)
            } else {
                selection
            },
        )
        currentBinding.tokenInput.setText(LauncherMetadataBridge.getBangumiToken(requireContext()))
    }

    private fun save() {
        val currentBinding = binding ?: return
        val pos = selectedMetadataSourceIndex
        var source = MetadataController.SOURCE_VNDB
        if (pos == 1) source = MetadataController.SOURCE_BANGUMI
        else if (pos == 2) source = MetadataController.SOURCE_BANGUMI_MIRROR
        else if (pos == 3) source = MetadataController.SOURCE_YMGAL

        val token = currentBinding.tokenInput.text?.toString()?.trim().orEmpty()
        if ((pos == 1 || pos == 2) && token.isEmpty()) {
            Toast.makeText(requireContext(), R.string.settings_bangumi_token_required, Toast.LENGTH_SHORT).show()
            return
        }
        LauncherMetadataBridge.setMetadataSource(requireContext(), source)
        LauncherMetadataBridge.setBangumiToken(requireContext(), token)
        Toast.makeText(
            requireContext(),
            getString(R.string.settings_metadata_source_saved, metadataSourceLabels()[selectedMetadataSourceIndex]),
            Toast.LENGTH_SHORT,
        ).show()
        requestClose()
    }

    private fun showMetadataSourcePicker() {
        LauncherDialogRouter.showSingleChoice(
            requireContext(),
            getString(R.string.settings_choose_metadata_source),
            metadataSourceLabels(),
            selectedMetadataSourceIndex,
            ::setMetadataSourceSelection,
        )
    }

    private fun setMetadataSourceSelection(index: Int) {
        val currentBinding = binding ?: return
        val labels = metadataSourceLabels()
        selectedMetadataSourceIndex = if (index in labels.indices) index else 0
        currentBinding.sourceText.text = labels[selectedMetadataSourceIndex]
    }

    private fun openTokenUrl() {
        if (!LauncherUrlOpener.open(requireContext(), "https://next.bgm.tv/demo/access-token/create")) {
            Toast.makeText(requireContext(), R.string.settings_cannot_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun metadataSourceLabels(): Array<CharSequence> = arrayOf(
        getString(R.string.settings_metadata_vndb_default),
        getString(R.string.settings_metadata_bangumi_token),
        getString(R.string.settings_metadata_bangumi_mirror_token),
        getString(R.string.settings_metadata_ymgal_public),
    )

    /** 按承载宿主分派关闭：竖屏薄宿主 finish，HD 由父 Fragment 关闭子 Fragment。 */
    private fun requestClose() {
        when (val host = activity) {
            is LauncherMetadataSourceActivity -> host.finishMetadataSource()
            is HdModeActivity -> (parentFragment as? HdEmbeddedActivityOwner)?.closeEmbeddedActivity()
            else -> Unit
        }
    }

    companion object {
        private const val STATE_METADATA_SOURCE_INDEX = "metadata_source_index"
    }
}
