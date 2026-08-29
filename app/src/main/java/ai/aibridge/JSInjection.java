package ai.aibridge;

import org.json.JSONObject;

/**
 * Platform-specific JavaScript automation snippets.
 *
 * Each template receives a single %s argument: a JSON-encoded string literal
 * of the user prompt (produced via JSONObject so quotes/backslashes are safe).
 *
 * The injected code:
 *   1. Detects a Cloudflare/interstitial challenge and reports it.
 *   2. Polls (up to several seconds) for the composer input, sets its value
 *      and fires input/change + Enter so the platform registers the text.
 *   3. Polls for the send control and clicks it (Enter is also dispatched).
 *   4. Polls for the assistant container, then watches it with a
 *      MutationObserver until the text stabilises, and reports via
 *      AIBridge.onResponse(text).
 *
 * All callbacks go through the @JavascriptInterface bridge (see WebViewManager).
 * The polling (__waitFor) makes the automation resilient to pages that are
 * still loading when the script is injected.
 */
public final class JSInjection {

    private JSInjection() {}

    private static final String CF_CHECK =
        "function __cf(){return !!document.querySelector('#challenge-form,#cf-challenge-running,iframe[src*=\"captcha\"],.cf-browser-verifying,#trk_jschal_js') || /cloudflare/i.test(document.title) || /just a moment/i.test((document.body&&document.body.innerText)||'');}";

    private static final String HELPERS =
        "function __waitFor(getter,timeout,cb){var s=Date.now();(function lp(){var el=getter();if(el){cb(el);return;}if(Date.now()-s>timeout){cb(null);return;}setTimeout(lp,300);})();}"
      + "function __fire(el){el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));setTimeout(function(){try{el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));}catch(e){}},60);}"
      + "function __observe(box,cb){var last='',stable=0;var obs=new MutationObserver(function(){var t=box.innerText;if(t===last){if(++stable>=3){obs.disconnect();cb(t);}}else{stable=0;last=t;}});obs.observe(box,{childList:true,subtree:true,characterData:true});setTimeout(function(){obs.disconnect();cb(last);},60000);}";

    // ---- ChatGPT -----------------------------------------------------------
    static final String CHATGPT = CF_CHECK + HELPERS +
        "(function(){ if(__cf()){AIBridge.onCloudflare();return;} var p=%s;"
      + "__waitFor(function(){return document.querySelector('textarea#prompt-textarea')||document.querySelector('div[contenteditable=\"true\"]');},5000,function(input){"
      + " if(!input){AIBridge.onError('input-not-found');return;} input.focus();"
      + " if(input.tagName==='TEXTAREA'){input.value=p;}else{input.innerText=p;input.textContent=p;} __fire(input);"
      + " __waitFor(function(){return document.querySelector('button[data-testid=\"send-button\"]');},5000,function(btn){"
      + " if(!btn){AIBridge.onError('send-not-found');return;} btn.click();"
      + " __waitFor(function(){var n=document.querySelectorAll('[data-message-author-role=\"assistant\"]');return n.length?n[n.length-1]:null;},15000,function(box){"
      + " if(!box){AIBridge.onError('resp-box-not-found');return;} __observe(box,function(t){AIBridge.onResponse(t);}); }); }); }); })();";

    // ---- Gemini ------------------------------------------------------------
    static final String GEMINI = CF_CHECK + HELPERS +
        "(function(){ if(__cf()){AIBridge.onCloudflare();return;} var p=%s;"
      + "__waitFor(function(){return document.querySelector('rich-textarea div[contenteditable=\"true\"],div[contenteditable=\"true\"]');},5000,function(input){"
      + " if(!input){AIBridge.onError('input-not-found');return;} input.focus();"
      + " input.innerText=p;input.textContent=p; __fire(input);"
      + " __waitFor(function(){return document.querySelector('.send-button,button[aria-label*=\"Send\"],button[aria-label*=\"send\"]');},5000,function(btn){"
      + " if(!btn){AIBridge.onError('send-not-found');return;} btn.click();"
      + " __waitFor(function(){return document.querySelector('.message-content.model-response,model-response,.model-response');},15000,function(box){"
      + " if(!box){AIBridge.onError('resp-box-not-found');return;} __observe(box,function(t){AIBridge.onResponse(t);}); }); }); }); })();";

    // ---- Claude ------------------------------------------------------------
    static final String CLAUDE = CF_CHECK + HELPERS +
        "(function(){ if(__cf()){AIBridge.onCloudflare();return;} var p=%s;"
      + "__waitFor(function(){return document.querySelector('fieldset div[contenteditable=\"true\"],div[contenteditable=\"true\"]');},5000,function(input){"
      + " if(!input){AIBridge.onError('input-not-found');return;} input.focus();"
      + " input.innerText=p;input.textContent=p; __fire(input);"
      + " __waitFor(function(){return document.querySelector('button[aria-label*=\"Send\"],button[aria-label*=\"send\"],button[type=\"submit\"]');},5000,function(btn){"
      + " if(!btn){AIBridge.onError('send-not-found');return;} btn.click();"
      + " __waitFor(function(){var n=document.querySelectorAll('.font-claude-message');return n.length?n[n.length-1]:null;},15000,function(box){"
      + " if(!box){AIBridge.onError('resp-box-not-found');return;} __observe(box,function(t){AIBridge.onResponse(t);}); }); }); }); })();";

    public static String forAi(String ai, String prompt) {
        JSONObject o = new JSONObject();
        o.put("p", prompt);               // produces a safely-quoted JSON string
        String json = o.getString("p");   // the raw quoted literal
        String tpl;
        switch (ai.toLowerCase()) {
            case "gemini":  tpl = GEMINI;  break;
            case "claude":  tpl = CLAUDE;  break;
            default:        tpl = CHATGPT; break;
        }
        return "javascript:" + String.format(java.util.Locale.US, tpl, json);
    }
}
