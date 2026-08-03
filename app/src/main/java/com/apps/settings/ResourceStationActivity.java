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
        webParams.topMargin = statusBarHeight() + dp(47);
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
        topBar.setElevation(dp(4));

        FrameLayout.LayoutParams topBarParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                statusBarHeight() + dp(47)
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

        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(dp(47), dp(47));
        backParams.gravity = Gravity.START | Gravity.TOP;
        topBar.addView(backButton, backParams);

        ImageView openExternalButton = new ImageView(this);
        openExternalButton.setImageResource(com.core.R.drawable.launcher_resource_open_external);
        openExternalButton.setColorFilter(ContextCompat.getColor(this, com.core.R.color.launcher_text_color));
        openExternalButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int iconPad = dp(11);
        openExternalButton.setPadding(iconPad, iconPad, iconPad, iconPad);
        openExternalButton.setOnClickListener(view -> {
            String current = webView == null ? null : webView.getUrl();
            if (current == null || current.trim().isEmpty()) {
                Toast.makeText(this, com.core.R.string.settings_no_address_to_open, Toast.LENGTH_SHORT).show();
                return;
            }
            openExternalUri(Uri.parse(current));
        });

        FrameLayout.LayoutParams openParams = new FrameLayout.LayoutParams(dp(47), dp(47));
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
                dp(47)
        );
        titleParams.gravity = Gravity.TOP;
        titleParams.leftMargin = dp(58);
        titleParams.rightMargin = dp(58);
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

    private boolean isAllowedHost(String host) {
        String h = host.toLowerCase();
        return h.equals("www.kungal.com") || h.endsWith(".kungal.com")
                || h.equals("www.shinnku.com") || h.endsWith(".shinnku.com")
                || h.equals("www.touchgal.ink") || h.endsWith(".touchgal.ink");
    }

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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
