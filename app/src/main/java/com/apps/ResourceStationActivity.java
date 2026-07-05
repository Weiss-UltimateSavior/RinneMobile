package com.apps;

import android.content.ActivityNotFoundException;
import android.content.Intent;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class ResourceStationActivity extends AppCompatActivity {
    private static final String RESOURCE_STATION_URL = "https://www.kungal.com";

    private WebView webView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureImmersiveStatusBar();

        webView = new WebView(this);
        webView.setBackgroundColor(ContextCompat.getColor(this, com.yuki.yukihub.R.color.launcher_bg_color));
        FrameLayout.LayoutParams webParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        webParams.topMargin = statusBarHeight() + dp(52);
        webView.setLayoutParams(webParams);
        configureWebView(webView);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(ContextCompat.getColor(this, com.yuki.yukihub.R.color.launcher_bg_color));
        root.addView(webView);
        root.addView(createTopBar());
        setContentView(root);

        webView.loadUrl(RESOURCE_STATION_URL);
    }

    private void configureImmersiveStatusBar() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(ContextCompat.getColor(this, com.yuki.yukihub.R.color.launcher_bottom_bar_color));
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        );
    }

    private FrameLayout createTopBar() {
        FrameLayout topBar = new FrameLayout(this);
        topBar.setBackgroundColor(ContextCompat.getColor(this, com.yuki.yukihub.R.color.launcher_card_color));
        topBar.setElevation(dp(4));

        FrameLayout.LayoutParams topBarParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                statusBarHeight() + dp(52)
        );
        topBarParams.gravity = Gravity.TOP;
        topBar.setLayoutParams(topBarParams);
        topBar.setPadding(0, statusBarHeight(), 0, 0);

        TextView backButton = new TextView(this);
        backButton.setText("<");
        backButton.setTextColor(ContextCompat.getColor(this, com.yuki.yukihub.R.color.launcher_text_color));
        backButton.setTextSize(24);
        backButton.setTypeface(null, android.graphics.Typeface.BOLD);
        backButton.setGravity(Gravity.CENTER);
        backButton.setOnClickListener(view -> finish());

        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(dp(52), dp(52));
        backParams.gravity = Gravity.START | Gravity.TOP;
        topBar.addView(backButton, backParams);

        TextView title = new TextView(this);
        title.setText("资源站");
        title.setTextColor(ContextCompat.getColor(this, com.yuki.yukihub.R.color.launcher_text_color));
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
        );
        titleParams.gravity = Gravity.TOP;
        titleParams.leftMargin = dp(64);
        titleParams.rightMargin = dp(64);
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
            if (host != null && ("www.kungal.com".equalsIgnoreCase(host) || host.toLowerCase().endsWith(".kungal.com"))) {
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

    private void openExternalUri(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有可打开该链接的应用", Toast.LENGTH_SHORT).show();
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
}
