package com.jihun.pikabrawl;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * ⚡ 피카츄 브롤 배구 — WebView 앱.
 * 게임 파일은 모두 앱 안(assets/www)에 들어 있고, https://appassets.androidplatform.net/assets/www/
 * 주소로 가상 호스팅해서 불러온다. (file:// 대신 https 오리진을 쓰면 localStorage·WebSocket이
 * 브라우저와 똑같이 동작한다.)
 */
public class MainActivity extends Activity {

    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String ASSET_BASE = "/assets/www/";
    private static final String START_URL = "https://" + ASSET_HOST + ASSET_BASE + "index.html";

    private WebView web;
    private long lastBackPress = 0L;
    private boolean usedFileFallback = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        goImmersive();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#140B26"));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null || !ASSET_HOST.equals(uri.getHost())) return null;
                String path = uri.getPath();
                if (path == null || !path.startsWith(ASSET_BASE)) return null;
                String assetPath = "www/" + path.substring(ASSET_BASE.length());
                if (assetPath.endsWith("/")) assetPath = assetPath + "index.html";
                try {
                    InputStream in = getAssets().open(assetPath);
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-cache");
                    WebResourceResponse res = new WebResourceResponse(mimeOf(assetPath), "UTF-8", in);
                    res.setResponseHeaders(headers);
                    return res;
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri != null && ASSET_HOST.equals(uri.getHost())) return false;
                // 게임 밖으로 나가는 링크는 열지 않는다.
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                goImmersive();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                // 가상 호스팅이 막히는 기기에서는 앱 안 파일을 직접 연다(안전망).
                if (request != null && request.isForMainFrame() && !usedFileFallback) {
                    usedFileFallback = true;
                    view.loadUrl("file:///android_asset/www/index.html");
                }
            }
        });

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#140B26"));
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        web.loadUrl(START_URL);
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase(java.util.Locale.ROOT);
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

    /** 상태바·내비게이션바를 숨기고 화면 전체를 게임에 쓴다. */
    private void goImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 화면을 돌려도 WebView를 새로 만들지 않는다(게임이 알아서 반응형으로 다시 그린다).
        goImmersive();
    }

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2000) {
            super.onBackPressed();
            return;
        }
        lastBackPress = now;
        Toast.makeText(this, "한 번 더 누르면 종료합니다", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        goImmersive();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
