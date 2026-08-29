package ai.aibridge;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Lightweight local HTTP bridge built on NanoHTTPD.
 *
 * Endpoints:
 *   GET  /status                 -> JSON of active sessions + readiness
 *   POST /ask  {ai, prompt, ...} -> dispatches to the matching WebView and
 *                                    returns the scraped answer (blocking up
 *                                    to the WebView manager timeout).
 *
 * Listens only on 127.0.0.1 so the bridge is local to the device. CORS is
 * opened so a browser-side tester (or Termux curl) can reach it.
 */
public class BridgeServer extends NanoHTTPD {

    private final Context context;
    private final WebViewManager wvm;
    private final LogBus bus = LogBus.get();
    private int port = 8080;
    private boolean running = false;

    public BridgeServer(Context ctx) {
        super(8080);
        this.context = ctx.getApplicationContext();
        this.wvm = AIBridgeApplication.get().getWebViewManager();
    }

    public synchronized void startServer() throws IOException {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        running = true;
        bus.log("Server started on 127.0.0.1:" + port);
    }

    public synchronized void stopServer() {
        stop();
        running = false;
        bus.log("Server stopped");
    }

    public synchronized boolean isRunning() { return running; }

    public int getPort() { return port; }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        // ---- CORS preflight ----
        if (method == Method.OPTIONS) {
            return addCors(NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/plain", ""));
        }

        if (uri.startsWith("/status")) {
            return json(wvm.getStatusJson());
        }

        if (uri.startsWith("/ask") && method == Method.POST) {
            try {
                Map<String, String> body = new java.util.HashMap<>();
                session.parseBody(body);
                String payload = body.get("postData");
                if (payload == null) payload = body.get("content");
                JSONObject req = new JSONObject(payload == null ? "{}" : payload);
                String ai = req.optString("ai", "chatgpt");
                String prompt = req.optString("prompt", "");

                if (prompt.isEmpty()) {
                    return json("{\"error\":\"empty-prompt\"}");
                }

                // ---- CYBER SAFETY GUARDRAIL ----
                // Classify the prompt, and when it is ambiguous/unsafe rewrite it
                // into an authorized, educational + defensive version before
                // forwarding to the AI. BLOCKED prompts are never forwarded.
                CyberSafetyEngine.Verdict verdict = CyberSafetyEngine.classify(prompt);

                // If blocked, return the refusal immediately (no AI call).
                if (verdict.blocked) {
                    JSONObject refuse = new JSONObject();
                    refuse.put("answer",
                            "Request blocked by local safety policy. " + verdict.noteEn);
                    refuse.put("safety", new JSONObject(verdict.toJson()));
                    bus.log("GUARDRAIL: BLOCKED prompt (cat=" + verdict.category + ")");
                    return json(refuse.toString());
                }

                String forwarded = verdict.rewritten ? verdict.safePrompt : prompt;
                if (verdict.rewritten) {
                    bus.log("GUARDRAIL: prompt rewritten (level=" + verdict.level
                            + ", cat=" + verdict.category + ")");
                }

                WebViewManager.Ai target = WebViewManager.Ai.from(ai);
                if (!wvm.isLoggedIn(target)) {
                    return json("{\"error\":\"not-logged-in\"}");
                }

                String result = wvm.ask(target, forwarded);
                bus.log("ask(" + ai + ") -> " + result.length() + " chars");

                // Merge the raw scraped text into {"answer": ...} and attach the
                // safety verdict. We parse the WebView JSON as an object so the
                // answer is a real string field (not a double-encoded JSON string).
                JSONObject out = new JSONObject();
                String answer = extractAnswer(result);
                out.put("answer", answer);
                out.put("safety", new JSONObject(verdict.toJson()));
                return json(out.toString());
            } catch (JSONException e) {
                return json("{\"error\":\"bad-json\"}");
            } catch (IOException | NanoHTTPD.ResponseException e) {
                return json("{\"error\":\"read-failed\"}");
            } catch (Exception e) {
                return json("{\"error\":\"internal\"}");
            }
        }

        return addCors(NanoHTTPD.newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found"));
    }

    /**
     * The WebView bridge returns either {"ai":..,"response":TEXT} or
     * {"error":..}. Extract the plain answer text so it can be placed in the
     * top-level "answer" field. Falls back to the raw string on parse failure.
     */
    private String extractAnswer(String result) {
        if (result == null) return "";
        try {
            JSONObject o = new JSONObject(result);
            if (o.has("error")) return "{\"error\":\"" + o.getString("error") + "\"}";
            if (o.has("response")) return o.getString("response");
            return result;
        } catch (Exception e) {
            return result;
        }
    }

    private Response json(String body) {
        return addCors(NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", body));
    }

    private Response addCors(Response r) {
        r.addHeader("Access-Control-Allow-Origin", "*");
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        r.addHeader("Access-Control-Allow-Headers", "Content-Type");
        return r;
    }
}
