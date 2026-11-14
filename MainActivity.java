package com.ezytel.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * پوسته‌ی اندروید برای برنامه‌ی EzyTel: همان فایل index.html را بدون تغییر
 * از روی https://appassets.androidplatform.net/ اجرا می‌کند (مبدأ https واقعی؛
 * برای fetch، IndexedDB و Cache API لازم است).
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";
    private static final int REQ_FILE = 1001;

    private FrameLayout root;
    private WebView web;
    private WebChromeClient chrome;
    private ValueCallback<Uri[]> filePathCb;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private String bridgeJs = "";

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // فاصله از نوار وضعیت/ناوبری/کیبورد
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(ins.getSystemWindowInsetLeft(), ins.getSystemWindowInsetTop(),
                    ins.getSystemWindowInsetRight(), ins.getSystemWindowInsetBottom());
            return ins;
        });

        bridgeJs = readAsset("native_bridge.js");

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        // نشانه‌های WebView را از UA برمی‌داریم تا صفحه خودش لینک‌ها را دستکاری نکند؛
        // باز کردن لینک‌ها را همین پوسته انجام می‌دهد.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", ""));

        web.addJavascriptInterface(new Bridge(), "EzAndroid");

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString(), request.isForMainFrame());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.startsWith("https://" + HOST + "/")) {
                    view.evaluateJavascript(bridgeJs, null);
                }
            }
        });

        chrome = new WebChromeClient() {
            // window.open(...) → مرورگر گوشی
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    private boolean done = false;

                    private boolean take(String url) {
                        if (done || url == null || url.startsWith("about:")) return false;
                        done = true;
                        handleUrl(url, true);
                        return true;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        return take(r.getUrl().toString());
                    }

                    @Override
                    public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                        if (take(url)) v.stopLoading();
                    }
                });
                WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
                t.setWebView(tmp);
                resultMsg.sendToTarget();
                return true;
            }

            // پنجره‌های alert / confirm / prompt
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                      final JsPromptResult result) {
                final EditText input = new EditText(MainActivity.this);
                input.setText(defaultValue == null ? "" : defaultValue);
                input.setSelectAllOnFocus(true);
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm(input.getText().toString()))
                        .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            // انتخاب فایل (بازیابی پشتیبان، وارد کردن فهرست کانال‌ها…)
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCb != null) filePathCb.onReceiveValue(null);
                filePathCb = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    filePathCb = null;
                    return false;
                }
                return true;
            }

            // ویدیوی تمام‌صفحه
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCb = callback;
                web.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                immersive(true);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                web.setVisibility(View.VISIBLE);
                immersive(false);
                if (customCb != null) customCb.onCustomViewHidden();
                customCb = null;
            }
        };
        web.setWebChromeClient(chrome);

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    // ───────── لینک‌ها ─────────
    private boolean handleUrl(String url, boolean mainFrame) {
        Uri u = Uri.parse(url);
        String sch = u.getScheme() == null ? "" : u.getScheme();
        if (sch.equals("https") && HOST.equals(u.getHost())) return false;
        if (sch.equals("intent")) {
            openIntent(url);
            return true;
        }
        if (sch.equals("http") || sch.equals("https")) {
            if (!mainFrame) return false;
            openExternal(url);
            return true;
        }
        if (sch.equals("blob") || sch.equals("data") || sch.equals("about") || sch.equals("javascript")) {
            return false;
        }
        openExternal(url); // tg:, mailto:, ...
        return true;
    }

    private void openExternal(String url) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            it.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(it);
        } catch (Exception e) {
            toast("برنامه‌ای برای باز کردن این لینک پیدا نشد");
        }
    }

    private void openIntent(String url) {
        String fallback = null;
        try {
            Intent it = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            fallback = it.getStringExtra("browser_fallback_url");
            it.addCategory(Intent.CATEGORY_BROWSABLE);
            it.setComponent(null);
            it.setSelector(null);
            startActivity(it);
        } catch (ActivityNotFoundException e) {
            if (fallback != null) openExternal(fallback);
        } catch (Exception e) {
            if (fallback != null) openExternal(fallback);
        }
    }

    // ───────── پل جاوااسکریپت ─────────
    private class Bridge {
        @JavascriptInterface
        public void openUrl(final String url) {
            if (url == null) return;
            if (!(url.startsWith("http://") || url.startsWith("https://"))) return;
            runOnUiThread(() -> openExternal(url));
        }

        @JavascriptInterface
        public void chunk(String id, String b64, String name, String mime, boolean last) {
            try {
                String safeId = id == null ? "x" : id.replaceAll("[^A-Za-z0-9_]", "");
                File f = new File(getCacheDir(), "dl_" + safeId);
                try (FileOutputStream o = new FileOutputStream(f, true)) {
                    if (b64 != null && !b64.isEmpty()) o.write(Base64.decode(b64, Base64.DEFAULT));
                }
                if (last) {
                    saveToDownloads(f, name, mime);
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            } catch (Exception e) {
                toast("ذخیره‌ی فایل انجام نشد");
            }
        }

        @JavascriptInterface
        public void themeColor(final String css) {
            runOnUiThread(() -> applyThemeColor(css));
        }
    }

    private void saveToDownloads(File f, String name, String mime) {
        String fileName = safeName(name);
        String type = (mime == null || mime.trim().isEmpty()) ? "application/octet-stream" : mime.split(";")[0].trim();
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            cv.put(MediaStore.Downloads.MIME_TYPE, type);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("MediaStore insert failed");
            try (OutputStream o = getContentResolver().openOutputStream(uri);
                 InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, cv, null, null);
            toast("ذخیره شد در پوشه‌ی Downloads: " + fileName);
        } catch (Exception e) {
            toast("ذخیره‌ی فایل انجام نشد");
        }
    }

    private static String safeName(String n) {
        if (n == null) n = "file";
        n = n.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim();
        if (n.isEmpty()) n = "file";
        return n.length() > 120 ? n.substring(0, 120) : n;
    }

    // ───────── ظاهر ─────────
    @SuppressWarnings("deprecation")
    private void applyThemeColor(String css) {
        try {
            int c = Color.parseColor(css.trim());
            Window win = getWindow();
            win.setStatusBarColor(c);
            win.setNavigationBarColor(c);
            root.setBackgroundColor(c);
            boolean light = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) > 150;
            int flags = win.getDecorView().getSystemUiVisibility();
            int bits = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            win.getDecorView().setSystemUiVisibility(light ? (flags | bits) : (flags & ~bits));
        } catch (Exception ignored) {
            // رنگ‌هایی مثل rgb(...) را رد می‌کنیم؛ پیش‌فرض تم سیستم می‌ماند
        }
    }

    @SuppressWarnings("deprecation")
    private void immersive(boolean on) {
        View d = getWindow().getDecorView();
        if (on) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            web.evaluateJavascript("(function(){var m=document.querySelector('meta[name=theme-color]');if(m&&m.content)EzAndroid.themeColor(m.content)})()", null);
        }
    }

    // ───────── چرخه‌ی زندگی ─────────
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE && filePathCb != null) {
            filePathCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCb = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (customView != null) {
            chrome.onHideCustomView();
            return;
        }
        web.evaluateJavascript(
                "(function(){var d=document.querySelector('dialog[open]');if(d){d.close();return 1}return 0})()",
                value -> {
                    if ("1".equals(value)) return;
                    if (web.canGoBack()) web.goBack();
                    else moveTaskToBack(true);
                });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    // ───────── کمکی‌ها ─────────
    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
