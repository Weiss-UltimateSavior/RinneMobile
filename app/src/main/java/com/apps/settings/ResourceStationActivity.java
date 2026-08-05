package com.apps.settings;

import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherTheme;
import com.apps.util.LauncherUrlOpener;
import com.apps.widget.LauncherTabletPortraitScaler;

public class ResourceStationActivity extends AppCompatActivity {
    public static final String EXTRA_HD_EMBEDDED = "resource_hd_embedded";
    private static final String DEFAULT_URL = "https://www.kungal.com";

    private WebView webView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureImmersiveStatusBar();

        webView = new WebView(this);
        if (getIntent().getBooleanExtra(EXTRA_HD_EMBEDDED, false)) {
            webView.setBackgroundResource(com.core.R.drawable.launcher_resource_webview_hd_bg);
            webView.setClipToOutline(true);
        } else {
            webView.setBackgroundColor(ContextCompat.getColor(this, com.core.R.color.launcher_bg_color));
        }
        FrameLayout.LayoutParams webParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        webParams.topMargin = statusBarHeight() + LauncherTheme.dp(this, 47);
        webView.setLayoutParams(webParams);
        configureWebView(webView);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(ContextCompat.getColor(this, com.core.R.color.launcher_bg_color));
        root.addView(webView);
        root.addView(createTopBar());
        setContentView(root);
        LauncherTabletPortraitScaler.applyActivityContent(this);

        String url = getIntent().getStringExtra("resource_url");
        if (url == null || url.trim().isEmpty()) url = DEFAULT_URL;
        webView.loadUrl(url);
    }

    // ResourceStation WebView 沉浸式状态栏：透明状态栏 + 底栏色导航栏，LIGHT 标志固定
    // （页面顶栏恒为卡片色、无深色分支）。与 LauncherEdgeToEdgeHelper 的明暗自适应语义
    // 不同，故不走 helper（豁免，见 agent.md §8 grep 监控与重构计划 4.7 项 2）。
    private void configureImmersiveStatusBar() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(ContextCompat.getColor(this, com.core.R.color.launcher_bottom_bar_color));
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        );
    }

    private FrameLayout createTopBar() {
        FrameLayout topBar = new FrameLayout(this);
        topBar.setBackgroundColor(ContextCompat.getColor(this, com.core.R.color.launcher_card_color));
        topBar.setElevation(LauncherTheme.dp(this, 4));

        FrameLayout.LayoutParams topBarParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                statusBarHeight() + LauncherTheme.dp(this, 47)
        );
        topBarParams.gravity = Gravity.TOP;
        topBar.setLayoutParams(topBarParams);
        topBar.setPadding(0, statusBarHeight(), 0, 0);

        TextView backButton = new TextView(this);
        // 返回箭头走字符串资源（无既有返回图标 drawable，保守资源化避免视觉风险）
        backButton.setText(getString(com.core.R.string.settings_back_arrow));
        backButton.setTextColor(ContextCompat.getColor(this, com.core.R.color.launcher_text_color));
        backButton.setTextSize(22);
        backButton.setTypeface(null, android.graphics.Typeface.BOLD);
        backButton.setGravity(Gravity.CENTER);
        backButton.setOnClickListener(view -> finish());

        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(LauncherTheme.dp(this, 47), LauncherTheme.dp(this, 47));
        backParams.gravity = Gravity.START | Gravity.TOP;
        topBar.addView(backButton, backParams);

        ImageView openExternalButton = new ImageView(this);
        openExternalButton.setImageResource(com.core.R.drawable.launcher_resource_open_external);
        openExternalButton.setColorFilter(ContextCompat.getColor(this, com.core.R.color.launcher_text_color));
        openExternalButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int iconPad = LauncherTheme.dp(this, 11);
        openExternalButton.setPadding(iconPad, iconPad, iconPad, iconPad);
        openExternalButton.setOnClickListener(view -> {
            String current = webView == null ? null : webView.getUrl();
            if (current == null || current.trim().isEmpty()) {
                Toast.makeText(this, com.core.R.string.settings_no_address_to_open, Toast.LENGTH_SHORT).show();
                return;
            }
            openExternalUri(Uri.parse(current));
        });

        FrameLayout.LayoutParams openParams = new FrameLayout.LayoutParams(LauncherTheme.dp(this, 47), LauncherTheme.dp(this, 47));
        openParams.gravity = Gravity.END | Gravity.TOP;
        topBar.addView(openExternalButton, openParams);

        TextView title = new TextView(this);
        String titleText = getIntent().getStringExtra("resource_title");
        if (titleText == null || titleText.trim().isEmpty()) {
            titleText = getString(com.core.R.string.settings_resource_station_title);
        }
        title.setText(titleText);
        title.setTextColor(ContextCompat.getColor(this, com.core.R.color.launcher_text_color));
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LauncherTheme.dp(this, 47)
        );
        titleParams.gravity = Gravity.TOP;
        titleParams.leftMargin = LauncherTheme.dp(this, 58);
        titleParams.rightMargin = LauncherTheme.dp(this, 58);
        topBar.addView(title, titleParams);

        return topBar;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void configureWebView(WebView view) {
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return shouldOpenExternally(request == null ? null : request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return shouldOpenExternally(url == null ? null : Uri.parse(url));
            }
        });

        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
    }

    /**
     * WebView 导航拦截策略：决定链接是继续在内部 WebView 加载，还是转交外部浏览器。
     * 职责：http/https 命中本页资源站 host 白名单 → 留在内部加载（返回 false）；
     * 其余 http/https 与非 file/content 的其它 scheme → 触发外部打开（调用 {@link #openExternalUri}，
     * 统一委托 {@link LauncherUrlOpener} 的 scheme 白名单 + ActivityNotFoundException 捕获）；
     * file/content scheme → 直接拦截返回 true（阻止本地资源加载，不外部打开）。
     * 返回 true 拦截 WebView 默认导航。本方法自身不直接 startActivity。
     */
    private boolean shouldOpenExternally(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            String host = uri.getHost();
            if (host != null && isAllowedHost(host)) {
                return false;
            }
            openExternalUri(uri);
            return true;
        }
        if ("file".equalsIgnoreCase(scheme) || "content".equalsIgnoreCase(scheme)) {
            return true;
        }

        openExternalUri(uri);
        return true;
    }

    /**
     * 本页资源站 host 白名单：命中 www.kungal.com / www.shinnku.com / www.touchgal.ink
     * 及其子域（*.kungal.com 等，不含裸域名）的链接优先在内部 WebView 中加载，不转外部浏览器。
     * 该白名单仅服务于本页面的 WebView 导航策略，不属于通用 URL 打开规则，
     * 故保留在本页而不下沉到共享的 {@link LauncherUrlOpener}（后者保持通用 scheme 白名单语义）。
     */
    private boolean isAllowedHost(String host) {
        String h = host.toLowerCase();
        return h.equals("www.kungal.com") || h.endsWith(".kungal.com")
                || h.equals("www.shinnku.com") || h.endsWith(".shinnku.com")
                || h.equals("www.touchgal.ink") || h.endsWith(".touchgal.ink");
    }

    // 外部打开统一走共享的 LauncherUrlOpener：scheme 白名单（http/https）校验 + ActivityNotFoundException 捕获
    private void openExternalUri(Uri uri) {
        if (!LauncherUrlOpener.open(this, uri == null ? null : uri.toString())) {
            Toast.makeText(this, com.core.R.string.settings_no_app_for_link, Toast.LENGTH_SHORT).show();
        }
    }

    private int statusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) return getResources().getDimensionPixelSize(resourceId);
        return 0;
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
