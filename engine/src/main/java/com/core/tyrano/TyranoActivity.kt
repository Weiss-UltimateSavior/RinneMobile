package com.core.tyrano

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.core.engine.R
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tyrano WebView 宿主；资源服务与存档沙箱分别由独立组件负责。
 *
 * 本 Activity 隶属于 engine 模块，不依赖 app 层。用户偏好（UI 缩放、外网开关）
 * 直接读取共享的 yukihub_prefs，与 OnsSettings 同模式；确认对话框通过 Intent extras
 * 传入的 Launcher 主题色在 engine 内复刻 LauncherDialogFactory 的视觉风格，保持统一。
 */
class TyranoActivity : Activity() {
    private var webView: WebView? = null
    private var gameDir: String? = null
    private var gameRootFile: File? = null
    private var saveDirectory: File? = null
    private var gameUsesAsar = false
    private var asarPath: String? = null
    private var asarArchive: AsarArchive? = null
    private var firstResume = true
    private var localServer: TyranoLocalHttpServer? = null
    private var allowExternalNetwork = false
    private val processExitScheduled = AtomicBoolean(false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextForUiScale(newBase) ?: newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreen()
        allowExternalNetwork = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TYRANO_EXTERNAL_NETWORK, false)

        gameDir = resolveGameDir(intent)
        Log.i(TAG, "onCreate gameDir=$gameDir")
        val resolvedGameDir = gameDir
        if (resolvedGameDir.isNullOrBlank()) {
            failLaunch(getString(R.string.engine_tyrano_empty_game_directory))
            return
        }

        val gameRoot = File(resolvedGameDir)
        gameRootFile = gameRoot
        val saves = resolveSaveDirectory(intent, gameRoot)
        saveDirectory = saves
        if (!ensureWritableSaveDirectory(saves)) {
            failLaunch(getString(R.string.engine_tyrano_unwritable_save_directory))
            return
        }
        Log.i(TAG, "save directory=${saves!!.absolutePath} scoped=${intent.getBooleanExtra(EXTRA_SCOPED_SAVE_DIR, false)}")

        val entry = findTyranoEntry(gameRoot, 0)
        if (entry == null) {
            val rootAsar = File(gameRoot, "app.asar")
            val resourcesAsar = File(File(gameRoot, "resources"), "app.asar")
            val index = File(gameRoot, "index.html")
            Log.e(TAG, "entry not found index=${index.absolutePath} app.asar=${rootAsar.absolutePath} resources/app.asar=${resourcesAsar.absolutePath} (searched subdirs: ${TYRANO_ENTRY_SUBDIRS.joinToString()})")
            failLaunch(getString(R.string.engine_tyrano_entry_not_found))
            return
        }
        val contentRoot = entry.contentRoot
        if (entry.asarPath != null) {
            gameUsesAsar = true
            asarPath = entry.asarPath
        }

        if (gameUsesAsar) {
            try {
                asarArchive = AsarArchive(File(requireNotNull(asarPath)))
            } catch (error: Throwable) {
                Log.e(TAG, "open asar failed", error)
                failLaunch(getString(R.string.engine_tyrano_asar_unreadable))
                return
            }
        }
        Log.i(TAG, "entry mode=${if (gameUsesAsar) "asar" else "dir"} asar=$asarPath contentRoot=${contentRoot.absolutePath}")

        try {
            val hook = assets.open(TYRANO_HOOK_ASSET).buffered().use { it.readBytes() }
            Log.i(TAG, "asset loaded $TYRANO_HOOK_ASSET bytes=${hook.size}")
            localServer = if (gameUsesAsar) {
                TyranoLocalHttpServer(contentRoot, asarArchive, hook)
            } else {
                TyranoLocalHttpServer(contentRoot, hook)
            }.also { it.start() }
        } catch (error: Throwable) {
            Log.e(TAG, "start local server failed", error)
            failLaunch(getString(R.string.engine_tyrano_server_failed))
            return
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        val browser = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
        }
        webView = browser
        root.addView(browser)
        setContentView(root)

        configureWebView(browser)
        browser.addJavascriptInterface(TyranoJsBridge(saves), JS_BRIDGE_NAME)
        val url = "http://localhost:${requireNotNull(localServer).port}/index.html"
        Log.i(TAG, "loadUrl=$url")
        browser.loadUrl(url)
    }

    private fun failLaunch(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun configureWebView(browser: WebView) {
        browser.isHorizontalScrollBarEnabled = false
        browser.isVerticalScrollBarEnabled = false
        runCatching { browser.clearCache(true) }
        runCatching { browser.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        browser.setBackgroundColor(Color.BLACK)
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return handleNavigation(request?.url?.toString(), request?.isForMainFrame != false)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                handleNavigation(url, true)

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? = request?.url
                ?.takeUnless(::isAllowedGameResource)
                ?.let { blockedResponse() }

            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                val uri = url?.let(Uri::parse)
                return uri?.takeUnless(::isAllowedGameResource)?.let { blockedResponse() }
            }
        }
        browser.webChromeClient = WebChromeClient()
        browser.settings.apply {
            userAgentString = "$userAgentString;tyranoplayer-android-1.0;yukihub-internal-tyrano"
            javaScriptEnabled = true
            allowContentAccess = false
            allowFileAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = false
            loadsImagesAutomatically = true
            blockNetworkImage = false
            mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = if (allowExternalNetwork) {
                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                } else {
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
            }
        }
    }

    private fun handleNavigation(url: String?, mainFrame: Boolean): Boolean {
        if (url == null) return true
        if (handleSpecialScheme(url)) return true
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return true
        if (isLocalGameUri(uri)) return false
        if (mainFrame) openExternalHttpUrl(uri)
        return true
    }

    private fun isLocalGameUri(uri: Uri?): Boolean {
        val server = localServer ?: return false
        if (!uri?.scheme.equals("http", ignoreCase = true)) return false
        return (uri?.host.equals("localhost", ignoreCase = true) || uri?.host == "127.0.0.1") &&
            uri?.port == server.port
    }

    private fun isAllowedGameResource(uri: Uri): Boolean = isLocalGameUri(uri) ||
        (allowExternalNetwork && (uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true))) ||
        uri.scheme.equals("data", ignoreCase = true) ||
        uri.scheme.equals("blob", ignoreCase = true) ||
        uri.scheme.equals("about", ignoreCase = true)

    private fun blockedResponse() = WebResourceResponse(
        "text/plain",
        "UTF-8",
        ByteArrayInputStream(ByteArray(0)),
    )

    private fun openExternalHttpUrl(uri: Uri?) {
        if (uri == null ||
            (!uri.scheme.equals("http", ignoreCase = true) &&
                !uri.scheme.equals("https", ignoreCase = true))
        ) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (error: Throwable) {
            Log.w(TAG, "open external URL failed: $uri", error)
        }
    }

    private fun resolveGameDir(source: Intent?): String? {
        source ?: return null
        val path = uriToFilePath(
            firstNonEmpty(
                source.getStringExtra("path"),
                source.getStringExtra("gamePath"),
                source.getStringExtra("projectRoot"),
                source.getStringExtra("gamedir"),
                source.getStringExtra("rootUri"),
            ),
        ) ?: return null
        val file = File(path).let { if (it.isFile) it.parentFile else it }
        return file?.absolutePath
    }

    private fun uriToFilePath(value: String?): String? {
        val raw = value?.trim() ?: return null
        if (raw.startsWith("file://")) return raw.removePrefix("file://")
        if (raw.startsWith("content://")) {
            val segment = Uri.parse(raw).lastPathSegment
            val colon = segment?.indexOf(':') ?: -1
            if (segment != null && colon >= 0) {
                val volume = segment.substring(0, colon)
                val relative = segment.substring(colon + 1)
                return if (volume.equals("primary", ignoreCase = true)) {
                    "/storage/emulated/0/$relative"
                } else {
                    "/storage/$volume/$relative"
                }
            }
        }
        return raw
    }

    private fun firstNonEmpty(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun handleSpecialScheme(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return try {
            when {
                lower.startsWith("tyranoplayer-save://") -> {
                    persistTyranoPlayerSave(url)
                    true
                }
                lower.startsWith("tyranoplayer-web://") -> {
                    val target = Uri.decode(
                        queryParam(url, "url")?.takeIf(String::isNotBlank)
                            ?: url.removePrefix("tyranoplayer-web://"),
                    )
                    target?.takeIf(String::isNotBlank)?.let { openExternalHttpUrl(Uri.parse(it.trim())) }
                    true
                }
                lower.startsWith("tyranoplayer-back://") -> {
                    runOnUiThread(::onBackPressed)
                    true
                }
                else -> false
            }
        } catch (error: Throwable) {
            Log.w(TAG, "handleSpecialScheme failed url=$url", error)
            true
        }
    }

    private fun persistTyranoPlayerSave(url: String) {
        try {
            TyranoStorage.write(saveDirectory, queryParam(url, "key"), queryParam(url, "data"))
        } catch (error: Throwable) {
            Log.w(TAG, "persistTyranoPlayerSave failed url=$url", error)
        }
    }

    private fun confirmReturnToTitle() {
        showEngineConfirm(
            getString(R.string.engine_return_to_title),
            getString(R.string.engine_return_to_title_message),
            getString(R.string.engine_confirm),
        ) {
            webView?.post { runCatching { webView?.reload() } }
        }
    }

    override fun finish() {
        super.finish()
        if (processExitScheduled.compareAndSet(false, true)) {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
            }, PROCESS_EXIT_DELAY_MS)
        }
    }

    private fun queryParam(url: String?, key: String?): String? {
        if (url == null || key == null) return null
        val queryStart = url.indexOf('?')
        if (queryStart < 0 || queryStart + 1 >= url.length) return null
        for (pair in url.substring(queryStart + 1).split('&')) {
            val equals = pair.indexOf('=')
            val encodedKey = if (equals >= 0) pair.substring(0, equals) else pair
            if (key.equals(Uri.decode(encodedKey), ignoreCase = true)) {
                return if (equals >= 0) Uri.decode(pair.substring(equals + 1)) else ""
            }
        }
        return null
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        showEngineConfirm(
            getString(R.string.engine_exit_game),
            getString(R.string.engine_exit_game_message),
            getString(R.string.engine_exit_game),
            ::finish,
        )
    }

    override fun onPause() {
        runCatching { webView?.loadUrl("javascript:if(window._tyrano_player){_tyrano_player.pauseAllAudio();}") }
        runCatching { webView?.onPause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        enterFullscreen()
        if (firstResume) firstResume = false else {
            runCatching { webView?.loadUrl("javascript:if(window._tyrano_player){_tyrano_player.resumeAllAudio();}") }
        }
        runCatching { webView?.onResume() }
    }

    override fun onDestroy() {
        runCatching {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.destroy()
        }
        webView = null
        runCatching { localServer?.stop() }
        localServer = null
        runCatching { asarArchive?.close() }
        asarArchive = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    @Suppress("DEPRECATION")
    private fun enterFullscreen() {
        val flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        runCatching { window.decorView.systemUiVisibility = flags }
    }

    private fun resolveSaveDirectory(source: Intent?, gameRoot: File?): File? {
        if (source?.getBooleanExtra(EXTRA_SCOPED_SAVE_DIR, false) == true) {
            val explicit = source.getStringExtra(EXTRA_SCOPED_SAVE_ROOT)?.takeIf(String::isNotBlank)
                ?: return null
            return try {
                val external = getExternalFilesDir(null) ?: return null
                val namespace = File(File(external, "save"), "tyrano").canonicalFile
                File(explicit).canonicalFile.takeIf {
                    it.path.startsWith(namespace.path + File.separator)
                }
            } catch (_: Throwable) {
                null
            }
        }
        return gameRoot?.let { File(it, "savedata") }
    }

    /**
     * 与 Launcher 统一风格的确认对话框。
     *
     * engine 模块不依赖 app 的 LauncherDialogFactory/LauncherTheme，但 Launcher 通过
     * Intent extras 传入了主题色（primaryColor / themeColorCard / themeColorText 等，
     * 见 ScriptEngineLaunchers.appendThemeColors）。此处用这些颜色在 engine 内复刻
     * LauncherDialogFactory.showConfirm 的视觉效果：圆角卡片背景 + 药丸形按钮，
     * 与 app 内其他确认弹窗保持一致。
     */
    private fun showEngineConfirm(
        title: String,
        message: String,
        confirmText: String,
        onConfirm: () -> Unit,
    ) {
        val dialog = AlertDialog.Builder(this).create()
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val density = resources.displayMetrics.density
        val dp = { value: Float -> (value * density + 0.5f).toInt() }
        val colors = ThemeColors.fromIntent(intent)

        // 根容器：圆角卡片，padding 22dp
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(22f), dp(22f), dp(22f))
            background = GradientDrawable().apply {
                setColor(colors.card)
                cornerRadius = dp(20f).toFloat()
            }
        }

        // 标题：16sp bold 居中
        val titleView = TextView(this).apply {
            text = title
            setTextColor(colors.text)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        root.addView(titleView)

        // 消息：13sp 居中，topMargin 14dp
        val messageView = TextView(this).apply {
            text = message
            setTextColor(colors.textMuted)
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(14f)
            }
        }
        root.addView(messageView)

        // 按钮行：topMargin 22dp
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(22f)
            }
        }

        // 取消按钮：药丸形（card 底色 + primary 文字）
        val cancelBtn = TextView(this).apply {
            text = getString(R.string.engine_cancel)
            setTextColor(colors.primary)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(colors.card)
                cornerRadius = dp(999f).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(36f)).apply {
                weight = 1f
                marginEnd = dp(7f)
            }
        }
        buttonRow.addView(cancelBtn)

        // 确认按钮：药丸形（primary 底色 + onPrimary 文字）
        val confirmBtn = TextView(this).apply {
            text = confirmText
            setTextColor(colors.onPrimary)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(colors.primary)
                cornerRadius = dp(999f).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(36f)).apply {
                weight = 1f
                marginStart = dp(7f)
            }
        }
        buttonRow.addView(confirmBtn)
        root.addView(buttonRow)

        dialog.setView(root)
        dialog.show()

        // 窗口：透明背景 + 固定宽度 252dp，与 LauncherDialogFactory 一致
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(252f), -2)
        }

        cancelBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
    }

    /** 从 Intent extras 读取 Launcher 主题色，缺失时按 darkMode 回落到默认值。 */
    private data class ThemeColors(
        val primary: Int,
        val onPrimary: Int,
        val card: Int,
        val text: Int,
        val textMuted: Int,
    ) {
        companion object {
            fun fromIntent(intent: Intent): ThemeColors {
                val extras = intent.extras
                val dark = extras?.getBoolean("darkMode", false) ?: false
                // 缺失 Intent extras 时按 darkMode 回落到 Launcher 默认色值
                val primary = if (dark) 0x22D88E else 0x18B978
                val onPrimary = if (dark) 0x06120D else 0xFFFFFF
                val card = if (dark) 0x1E1F1F else 0xFFFFFF
                val text = if (dark) 0xF0F0F0 else 0x14221B
                val textMuted = if (dark) 0x9A9A9A else 0x82908A
                return ThemeColors(
                    primary = extras?.getInt("primaryColor", primary) ?: primary,
                    onPrimary = extras?.getInt("themeColorOnPrimary", onPrimary) ?: onPrimary,
                    card = extras?.getInt("themeColorCard", card) ?: card,
                    text = extras?.getInt("themeColorText", text) ?: text,
                    textMuted = extras?.getInt("themeColorTextMuted", textMuted) ?: textMuted,
                )
            }
        }
    }

    /**
     * Tyrano 游戏入口定位结果。
     *
     * @property contentRoot 包含 index.html 或 app.asar 的目录，将作为本地 HTTP 服务器的 root。
     * @property asarPath 命中的 app.asar 绝对路径；非空表示 asar 模式，空表示散文件模式。
     */
    private class TyranoEntry(val contentRoot: File, val asarPath: String?)

    /**
     * 递归查找 Tyrano 游戏入口（index.html 或 app.asar）。
     *
     * 根目录优先匹配 app.asar / resources/app.asar / index.html；未命中时按
     * [TYRANO_ENTRY_SUBDIRS] 列表递归搜索子目录，与启动器侧的引擎特征探测子目录保持一致，
     * 避免扫描器识别成功但启动器找不到入口而闪退。
     *
     * @param dir 当前搜索目录。
     * @param depth 当前递归深度，根目录传入 0。
     * @return 入口定位结果；未找到返回 null。
     */
    private fun findTyranoEntry(dir: File, depth: Int): TyranoEntry? {
        // 当前目录的入口文件（保持原逻辑：asar 优先于 index.html）
        dir.resolve("app.asar").takeIf { it.isFile }?.let {
            return TyranoEntry(dir, it.absolutePath)
        }
        dir.resolve("resources/app.asar").takeIf { it.isFile }?.let {
            return TyranoEntry(dir, it.absolutePath)
        }
        dir.resolve("index.html").takeIf { it.isFile }?.let {
            return TyranoEntry(dir, null)
        }
        // 达到最大深度后不再递归
        if (depth >= MAX_ENTRY_SEARCH_DEPTH) return null
        for (name in TYRANO_ENTRY_SUBDIRS) {
            val sub = dir.resolve(name)
            if (!sub.isDirectory) continue
            findTyranoEntry(sub, depth + 1)?.let { return it }
        }
        return null
    }

    inner class TyranoJsBridge(private val saveDirectory: File?) {
        @JavascriptInterface
        fun closeGame() = runOnUiThread(::onBackPressed)

        @JavascriptInterface
        fun finishGame() = runOnUiThread(::confirmReturnToTitle)

        @JavascriptInterface
        fun getStorage(key: String?): String = TyranoStorage.read(saveDirectory, key)

        @JavascriptInterface
        fun setStorage(key: String?, value: String?) = TyranoStorage.write(saveDirectory, key, value)

        @JavascriptInterface
        fun openUrl(url: String?) = runOnUiThread {
            try {
                openExternalHttpUrl(Uri.parse(url))
            } catch (error: Throwable) {
                Log.w(TAG, "invalid external URL", error)
            }
        }

        @JavascriptInterface fun stopMovie() = Unit
        @JavascriptInterface fun audio(value: String?) = Unit
    }

    companion object {
        private const val TAG = "YukiTyrano"
        private const val TYRANO_HOOK_ASSET = "__tyrano__.js"
        private const val JS_BRIDGE_NAME = "appJsInterface"
        private const val EXTRA_SCOPED_SAVE_DIR = "scopedSaveDir"
        private const val EXTRA_SCOPED_SAVE_ROOT = "scopedSaveRoot"
        private const val PROCESS_EXIT_DELAY_MS = 500L
        private const val MAX_ENTRY_SEARCH_DEPTH = 2
        private val TYRANO_ENTRY_SUBDIRS = arrayOf("resources", "app", "tyrano", "data", "scenario", "system", "game")

        // 共享偏好键：与 app 模块的 LauncherKrkrBridge / UiScaleUtil 保持一致，
        // engine 直接读取以保证 Launcher 修改后引擎侧立即可见。
        private const val PREFS_NAME = "yukihub_prefs"
        private const val KEY_TYRANO_EXTERNAL_NETWORK = "tyrano_external_network"
        private const val KEY_UI_FONT_SCALE = "ui_font_scale"
        private const val KEY_UI_SCALE = "ui_scale"
        private const val DEFAULT_FONT_SCALE = 1.0f
        private const val MIN_FONT_SCALE = 0.85f
        private const val MAX_FONT_SCALE = 1.30f
        private const val DEFAULT_UI_SCALE = 1.0f
        private const val MIN_UI_SCALE = 0.70f
        private const val MAX_UI_SCALE = 1.50f

        private fun ensureWritableSaveDirectory(directory: File?): Boolean = try {
            directory != null &&
                (directory.exists() || directory.mkdirs()) &&
                directory.isDirectory &&
                directory.canWrite()
        } catch (_: Throwable) {
            false
        }

        @JvmStatic
        @Throws(Exception::class)
        fun resolveStorageFile(directory: File?, key: String?): File? =
            TyranoStorage.resolveFile(directory, key)

        /**
         * 通过 SharedPreferences 持久化的用户偏好创建自定义 Configuration 的 Context。
         *
         * 复刻 app 模块 UiScaleUtil.wrap 的语义：读取 yukihub_prefs 中的字体缩放与全局
         * UI 缩放，应用到 Configuration 后返回新的 Context。engine 不依赖 app 的工具类，
         * 此处保留独立的等价实现以避免反向依赖。
         */
        private fun wrapContextForUiScale(base: Context?): Context? {
            if (base == null) return null
            val prefs = base.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // NaN/Infinite 回落到各自默认值，与 app 模块 UiScaleUtil.clamp/clampUiScale 严格一致
            val fontScale = prefs.getFloat(KEY_UI_FONT_SCALE, DEFAULT_FONT_SCALE).let {
                if (it.isNaN() || it.isInfinite()) DEFAULT_FONT_SCALE else it.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
            }
            val uiScale = prefs.getFloat(KEY_UI_SCALE, DEFAULT_UI_SCALE).let {
                if (it.isNaN() || it.isInfinite()) DEFAULT_UI_SCALE else it.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)
            }
            val config = Configuration(base.resources.configuration)
            config.fontScale = fontScale
            // 通过修改 densityDpi 实现全局 UI 缩放
            if (uiScale != 1.0f) {
                config.densityDpi = (config.densityDpi * uiScale).toInt()
            }
            return base.createConfigurationContext(config)
        }
    }
}
