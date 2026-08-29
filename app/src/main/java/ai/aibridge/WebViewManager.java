package ai.aibridge;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Keep;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Owns three persistent WebViews (ChatGPT / Gemini / Claude). Each view keeps
 * its own cookies so a manual one-time login survives. The server resolves a
 * pending request by injecting the matching platform script on the MAIN thread
 * (WebView methods are UI-thread only); the script calls back through the
 * {@link BridgeJs} interface when the answer is ready.
 *
 * IMPORTANT: every WebView method (loadUrl, post, settings) must run on the
 * main thread. We therefore funnel all work through a main-thread Handler and
 * block the caller with a CompletableFuture. The constructor must be called
 * from the main thread (AIBridgeApplication.onCreate satisfies this).
 */
public class WebViewManager {

    public enum Ai { CHATGPT("chatgpt", "https://chatgpt.com"),
                     GEMINI("gemini", "https://gemini.google.com"),
                     CLAUDE("claude", "https://claude.ai");

        final String key, url;
        Ai(String k, String u){ key=k; url=u; }
        static Ai from(String s){ for(Ai a:values()) if(a.key.equals(s.toLowerCase(Locale.US))) return a; return CHATGPT; }
    }

    private static final long TIMEOUT_MS = 70_000;

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<Ai, WebView> views = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Ai, Boolean> busy = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();

    public WebViewManager(Context ctx) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("WebViewManager must be created on the main thread");
        }
        this.appContext = ctx.getApplicationContext();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public WebView createView(Ai ai) {
        // This method is only called from the UI (WebViewsFragment), i.e. main thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final WebView[] out = new WebView[1];
            main.post(() -> out[0] = doCreate(ai));
            // caller (fragment) expects the view immediately for addView; post is sync-ish
            // but fragment also calls getView which returns null first time -> handled.
            return out[0];
        }
        return doCreate(ai);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private WebView doCreate(Ai ai) {
        WebView wv = new WebView(appContext);
        wv.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(wv, true);
        }

        wv.addJavascriptInterface(new BridgeJs(ai), "AIBridge");
        wv.setWebChromeClient(new WebChromeClient());
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                LogBus.get().log("WebView " + ai.key + " error " + errorCode + " " + description);
            }
            @Override
            @SuppressLint("WebViewClientOnReceivedSslError")
            public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                // Web AI endpoints are HTTPS; only proceed on recoverable cert issues
                // (e.g. captive portals). Never ignore outright for security.
                LogBus.get().log("WebView " + ai.key + " SSL error: " + error.getPrimaryError());
                handler.cancel();
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                LogBus.get().log("WebView " + ai.key + " loaded: " + url);
            }
        });

        views.put(ai, wv);
        wv.loadUrl(ai.url);
        return wv;
    }

    public WebView getView(Ai ai) { return views.get(ai); }

    public boolean isReady(Ai ai) {
        WebView wv = views.get(ai);
        return wv != null && !busy.getOrDefault(ai, false);
    }

    public boolean isLoggedIn(Ai ai) {
        CookieManager cm = CookieManager.getInstance();
        String url = Ai.from(ai.key).url;
        String cookies = cm.getCookie(url);
        if (cookies == null || cookies.isEmpty()) return false;
        // crude but effective: session cookies present => likely logged in
        String lc = cookies.toLowerCase(Locale.US);
        return lc.contains("session") || lc.contains("token") || lc.contains("auth")
                || lc.contains("__secure") || lc.contains("cookie");
    }

    public String getStatusJson() {
        StringBuilder sb = new StringBuilder("{\"server\":\"ok\",\"sessions\":{");
        boolean first = true;
        for (Ai a : Ai.values()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(a.key).append("\":{")
              .append("\"ready\":").append(isReady(a))
              .append(",\"logged_in\":").append(isLoggedIn(a))
              .append("}");
        }
        sb.append("}}");
        return sb.toString();
    }

    /** Resolve a prompt using the matching WebView. Blocks until done or timeout. */
    public String ask(Ai ai, String prompt) {
        WebView wv = views.get(ai);
        if (wv == null) return "{\"error\":\"webview-not-initialized\"}";
        if (busy.getOrDefault(ai, false)) return "{\"error\":\"busy\"}";

        String key = ai.key + ":" + System.nanoTime();
        CompletableFuture<String> future = new CompletableFuture<>();
        pending.put(key, future);
        busy.put(ai, true);

        final String script = JSInjection.forAi(ai.key, prompt);
        // WebView.loadUrl MUST be on the main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            wv.loadUrl(script);
        } else {
            main.post(() -> wv.loadUrl(script));
        }

        try {
            String result = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return result != null ? result : "{\"error\":\"empty-response\"}";
        } catch (TimeoutException e) {
            busy.put(ai, false);
            pending.remove(key);
            return "{\"error\":\"timeout\"}";
        } catch (Exception e) {
            busy.put(ai, false);
            pending.remove(key);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    public void releaseAll() {
        for (WebView wv : views.values()) {
            if (wv != null) wv.destroy();
        }
        views.clear();
        busy.clear();
        pending.clear();
    }

    /** JS callback surface. Methods are invoked on the WebView thread. */
    @Keep
    private class BridgeJs {
        private final Ai ai;
        BridgeJs(Ai ai) { this.ai = ai; }

        @JavascriptInterface
        public void onResponse(String text) {
            complete("{\"ai\":\"" + ai.key + "\",\"response\":" + quote(text) + "}");
        }

        @JavascriptInterface
        public void onError(String why) {
            complete("{\"error\":\"" + why + "\",\"ai\":\"" + ai.key + "\"}");
        }

        @JavascriptInterface
        public void onCloudflare() {
            complete("{\"error\":\"Cloudflare Challenge Detected, please open App UI to complete CAPTCHA\"}");
        }

        private void complete(String payload) {
            for (ConcurrentHashMap.Entry<String, CompletableFuture<String>> e : pending.entrySet()) {
                if (e.getKey().startsWith(ai.key + ":")) {
                    CompletableFuture<String> f = e.getValue();
                    if (!f.isDone()) f.complete(payload);
                    pending.remove(e.getKey());
                    busy.put(ai, false);
                    break;
                }
            }
        }
    }

    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append("\"");
        return b.toString();
    }
}
