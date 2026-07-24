package com.apps.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.apps.widget.LauncherTabletPortraitScaler
import com.yuki.yukihub.databinding.FragmentLauncherPlaceholderBinding

class LauncherPlaceholderFragment : Fragment() {
    private var binding: FragmentLauncherPlaceholderBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return FragmentLauncherPlaceholderBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentBinding = requireNotNull(binding)
        LauncherTabletPortraitScaler.apply(currentBinding.root)
        currentBinding.tvPlaceholderTitle.text = arguments?.getString(ARG_TITLE, "占位") ?: "占位"
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TITLE = "title"

        @JvmStatic
        fun newInstance(title: String?): LauncherPlaceholderFragment = LauncherPlaceholderFragment().apply {
            arguments = Bundle().apply { putString(ARG_TITLE, title) }
        }
    }
}
