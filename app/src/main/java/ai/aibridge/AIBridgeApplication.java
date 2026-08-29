package ai.aibridge;

import android.app.Application;

/**
 * Holds process-wide singletons: the WebView manager, the HTTP server and a
 * simple in-memory log bus used by the Server dashboard UI.
 */
public class AIBridgeApplication extends Application {

    private static AIBridgeApplication instance;

    private WebViewManager webViewManager;
    private BridgeServer bridgeServer;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // WebViewManager must be created on the main thread (Looper present here).
        webViewManager = new WebViewManager(this);
        bridgeServer = new BridgeServer(this);
    }

    public static AIBridgeApplication get() {
        return instance;
    }

    public WebViewManager getWebViewManager() {
        return webViewManager;
    }

    public BridgeServer getBridgeServer() {
        return bridgeServer;
    }
}
