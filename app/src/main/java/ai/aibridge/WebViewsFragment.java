package ai.aibridge;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import ai.aibridge.WebViewManager.Ai;

/**
 * Interactive tab: user manually logs into each AI's WebView, then flips the
 * Background Execution checkbox to drop the views to GONE (they keep running
 * in memory so the JS automation still works).
 *
 * The three WebView instances are owned by WebViewManager (process-wide), so
 * switching tabs never destroys them; we only detach/reattach the current one
 * into this fragment's container.
 */
public class WebViewsFragment extends Fragment {

    private Ai current = Ai.CHATGPT;
    private WebView currentView;
    private FrameLayout container;
    private CheckBox bg;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup p, @Nullable Bundle b) {
        View root = inf.inflate(R.layout.fragment_webviews, p, false);

        container = root.findViewById(R.id.webContainer);
        bg = root.findViewById(R.id.chkBg);

        Button bChat = root.findViewById(R.id.btnChatgpt);
        Button bGem = root.findViewById(R.id.btnGemini);
        Button bCla = root.findViewById(R.id.btnClaude);
        Button bReload = root.findViewById(R.id.btnReload);

        WebViewManager wvm = AIBridgeApplication.get().getWebViewManager();
        show(wvm, Ai.CHATGPT);

        bChat.setOnClickListener(v -> show(wvm, Ai.CHATGPT));
        bGem.setOnClickListener(v -> show(wvm, Ai.GEMINI));
        bCla.setOnClickListener(v -> show(wvm, Ai.CLAUDE));
        bReload.setOnClickListener(v -> { if (currentView != null) currentView.reload(); });

        bg.setOnCheckedChangeListener((v, checked) -> {
            if (checked) {
                container.setVisibility(View.GONE);
                LogBus.get().log("WebViews hidden (background execution ON)");
            } else {
                container.setVisibility(View.VISIBLE);
                LogBus.get().log("WebViews visible (background execution OFF)");
            }
        });

        return root;
    }

    private void show(WebViewManager wvm, Ai ai) {
        if (currentView != null && currentView.getParent() == container) {
            container.removeView(currentView);
        }
        current = ai;
        WebView v = wvm.getView(ai);
        if (v == null) {
            v = wvm.createView(ai);   // createView runs on the main thread here
        }
        currentView = v;
        if (currentView.getParent() != null) {
            ((ViewGroup) currentView.getParent()).removeView(currentView);
        }
        container.addView(currentView);
        LogBus.get().log("Showing WebView: " + ai.key);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Detach but DO NOT destroy — WebViews are reused across tab switches.
        if (currentView != null && currentView.getParent() == container) {
            container.removeView(currentView);
        }
    }
}
