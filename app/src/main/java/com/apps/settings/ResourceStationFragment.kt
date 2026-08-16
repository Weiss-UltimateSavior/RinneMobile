package com.apps.settings

import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.apps.HDModel.HdEmbeddedActivityOwner
import com.apps.HDModel.HdModeActivity
import com.apps.theme.LauncherTheme
import com.apps.util.LauncherUrlOpener
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R

/**
 * 资源站页（重构计划 9.9 W-3，阶段 125）：自 [ResourceStationActivity] 抽取全部逻辑，
 * HD 由 [com.apps.HDModel.HdHomeFragment] 以子 Fragment 承载（替代原 EXTRA_HD_EMBEDDED
 * 嵌入路径），竖屏由薄宿主 [ResourceStationActivity] 承载。
 *
 * 沉浸式窗口配置（透明状态栏 + LIGHT 标志）保留在薄宿主 Activity；
 * 本 Fragment 仅构建 WebView/顶栏视图树与导航拦截策略（原 Activity 逐字等价迁移）。
 */
class ResourceStationFragment : Fragment() {
    private var webView: WebView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val args = requireArguments()
        val web = WebView(requireContext())
        if (args.getBoolean(ARG_HD_EMBEDDED, false)) {
            web.setBackgroundResource(R.drawable.launcher_resource_webview_hd_bg)
            web.clipToOutline = true
        } else {
            web.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.launcher_bg_color))
        }
        val webParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        webParams.topMargin = statusBarHeight() + LauncherTheme.dp(requireContext(), 47)
        web.layoutParams = webParams
        configureWebView(web)
        webView = web

        val root = FrameLayout(requireContext())
        root.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.launcher_bg_color))
        root.addView(web)
        root.addView(createTopBar())
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (activity !is HdModeActivity) LauncherTabletPortraitScaler.apply(view)
        val url = requireArguments().getString(EXTRA_URL)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_URL
        webView?.loadUrl(url)
        // 硬件返回：WebView 可回退则 goBack，否则按承载宿主关闭（原 Activity onBackPressed 语义，双上下文统一）。
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val web = webView
                    if (web != null && web.canGoBack()) {
                        web.goBack()
                    } else {
                        requestClose()
                    }
                }
            },
        )
    }

    override fun onDestroyView() {
        val web = webView
        if (web != null) {
            web.stopLoading()
            web.destroy()
            webView = null
        }
        super.onDestroyView()
    }

    private fun createTopBar(): FrameLayout {
        val topBar = FrameLayout(requireContext())
        topBar.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.launcher_card_color))
        topBar.elevation = LauncherTheme.dp(requireContext(), 4).toFloat()

        val topBarParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            statusBarHeight() + LauncherTheme.dp(requireContext(), 47),
        )
        topBarParams.gravity = Gravity.TOP
        topBar.layoutParams = topBarParams
        topBar.setPadding(0, statusBarHeight(), 0, 0)

        val backButton = TextView(requireContext())
        // 返回箭头走字符串资源（无既有返回图标 drawable，保守资源化避免视觉风险）
        backButton.setText(getString(R.string.settings_back_arrow))
        backButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_color))
        backButton.setTextSize(22f)
        backButton.setTypeface(null, Typeface.BOLD)
        backButton.gravity = Gravity.CENTER
        backButton.setOnClickListener { requestClose() }

        val backParams = FrameLayout.LayoutParams(
            LauncherTheme.dp(requireContext(), 47),
            LauncherTheme.dp(requireContext(), 47),
        )
        backParams.gravity = Gravity.START or Gravity.TOP
        topBar.addView(backButton, backParams)

        val openExternalButton = ImageView(requireContext())
        openExternalButton.setImageResource(R.drawable.launcher_resource_open_external)
        openExternalButton.setColorFilter(ContextCompat.getColor(requireContext(), R.color.launcher_text_color))
        openExternalButton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val iconPad = LauncherTheme.dp(requireContext(), 11)
        openExternalButton.setPadding(iconPad, iconPad, iconPad, iconPad)
        openExternalButton.setOnClickListener {
            val current = webView?.url
            if (current == null || current.trim().isEmpty()) {
                Toast.makeText(requireContext(), R.string.settings_no_address_to_open, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openExternalUri(Uri.parse(current))
        }

        val openParams = FrameLayout.LayoutParams(
            LauncherTheme.dp(requireContext(), 47),
            LauncherTheme.dp(requireContext(), 47),
        )
        openParams.gravity = Gravity.END or Gravity.TOP
        topBar.addView(openExternalButton, openParams)

        val title = TextView(requireContext())
        val titleText = requireArguments().getString(EXTRA_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.settings_resource_station_title)
        title.text = titleText
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.launcher_text_color))
        title.setTextSize(15f)
        title.setTypeface(null, Typeface.BOLD)
        title.gravity = Gravity.CENTER

        val titleParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            LauncherTheme.dp(requireContext(), 47),
        )
        titleParams.gravity = Gravity.TOP
        titleParams.leftMargin = LauncherTheme.dp(requireContext(), 58)
        titleParams.rightMargin = LauncherTheme.dp(requireContext(), 58)
        topBar.addView(title, titleParams)

        return topBar
    }

    private fun configureWebView(view: WebView) {
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return shouldOpenExternally(request.url)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return shouldOpenExternally(url.toUriOrNull())
            }
        }

        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
    }

    /**
     * WebView 导航拦截策略：决定链接是继续在内部 WebView 加载，还是转交外部浏览器。
     * 职责：http/https 命中本页资源站 host 白名单 → 留在内部加载（返回 false）；
     * 其余 http/https 与非 file/content 的其它 scheme → 触发外部打开（调用 [openExternalUri]，
     * 统一委托 [LauncherUrlOpener] 的 scheme 白名单 + ActivityNotFoundException 捕获）；
     * file/content scheme → 直接拦截返回 true（阻止本地资源加载，不外部打开）。
     * 返回 true 拦截 WebView 默认导航。本方法自身不直接 startActivity。
     */
    private fun shouldOpenExternally(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme
        if ("http".equals(scheme, ignoreCase = true) || "https".equals(scheme, ignoreCase = true)) {
            val host = uri.host
            if (host != null && isAllowedHost(host)) {
                return false
            }
            openExternalUri(uri)
            return true
        }
        if ("file".equals(scheme, ignoreCase = true) || "content".equals(scheme, ignoreCase = true)) {
            return true
        }
        openExternalUri(uri)
        return true
    }

    /**
     * 本页资源站 host 白名单：命中 www.kungal.com / www.shinnku.com / www.touchgal.ink
     * 及其子域（*.kungal.com 等，不含裸域名）的链接优先在内部 WebView 中加载，不转外部浏览器。
     * 该白名单仅服务于本页面的 WebView 导航策略，不属于通用 URL 打开规则，
     * 故保留在本页而不下沉到共享的 [LauncherUrlOpener]（后者保持通用 scheme 白名单语义）。
     */
    private fun isAllowedHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "www.kungal.com" || h.endsWith(".kungal.com")
            || h == "www.shinnku.com" || h.endsWith(".shinnku.com")
            || h == "www.touchgal.ink" || h.endsWith(".touchgal.ink")
    }

    // 外部打开统一走共享的 LauncherUrlOpener：scheme 白名单（http/https）校验 + ActivityNotFoundException 捕获
    private fun openExternalUri(uri: Uri?) {
        if (!LauncherUrlOpener.open(requireContext(), uri?.toString())) {
            Toast.makeText(requireContext(), R.string.settings_no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun statusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) return resources.getDimensionPixelSize(resourceId)
        return 0
    }

    /** 按承载宿主分派关闭：竖屏薄宿主 finish，HD 由父 Fragment 关闭子 Fragment，Pad 横屏薄宿主 finish。 */
    private fun requestClose() {
        when (val host = activity) {
            is ResourceStationActivity -> host.finishResourceStation()
            is com.apps.PadUi.PadResourceStationActivity -> host.finish()
            is HdModeActivity -> (parentFragment as? HdEmbeddedActivityOwner)?.closeEmbeddedActivity()
            else -> Unit
        }
    }

    private fun String.toUriOrNull(): Uri? = if (isNullOrEmpty()) null else Uri.parse(this)

    companion object {
        private const val DEFAULT_URL = "https://www.kungal.com"

        /** 资源 URL/标题 传递键（Activity intent extra 与 Fragment arg 共用，阶段 125 单源化）。 */
        internal const val EXTRA_URL = "resource_url"
        internal const val EXTRA_TITLE = "resource_title"
        private const val ARG_HD_EMBEDDED = "resource_hd_embedded"

        fun newInstance(url: String?, title: String?, hdEmbedded: Boolean): ResourceStationFragment =
            ResourceStationFragment().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_URL, url)
                    putString(EXTRA_TITLE, title)
                    putBoolean(ARG_HD_EMBEDDED, hdEmbedded)
                }
            }
    }
}
