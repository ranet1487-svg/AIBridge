package ai.aibridge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class SetupFragment extends Fragment {

    private static final String SCRIPT = "#!/bin/bash\n"
            + "# AI Bridge Termux wrapper - copy to Termux and chmod +x\n"
            + "# Usage: ./ai-bridge.sh <chatgpt|gemini|claude> \"your prompt\"\n"
            + "#\n"
            + "# The bridge runs an offline cyber-safety guardrail: unsafe/ambiguous\n"
            + "# prompts are auto-rewritten into an educational + defensive form.\n"
            + "# Clearly illegal content is BLOCKED (never forwarded).\n"
            + "AI_TYPE=\"${1:-chatgpt}\"\n"
            + "PROMPT=\"$2\"\n"
            + "if [ -z \"$PROMPT\" ]; then\n"
            + "  echo 'Usage: ./ai-bridge.sh <chatgpt|gemini|claude> \"your prompt\"'\n"
            + "  exit 1\n"
            + "fi\n"
            + "RESP=$(curl -s -X POST \"http://127.0.0.1:8080/ask\" \\\n"
            + "  -H \"Content-Type: application/json\" \\\n"
            + "  -d \"{\\\"ai\\\":\\\"$AI_TYPE\\\",\\\"prompt\\\":\\\"$PROMPT\\\"}\")\n"
            + "if command -v jq >/dev/null 2>&1; then\n"
            + "  echo \"$RESP\" | jq -r 'if (.safety.blocked==true) then \"=== BLOCKED ===\",.safety.note_hi else \"=== SAFETY ===\",\"level: \"+(.safety.level|tostring),\"category: \"+.safety.category,\"rewritten: \"+(.safety.rewritten|tostring),(if .safety.note_hi!=\"\" then \"note: \"+.safety.note_hi else empty end),\"=== ANSWER ===\",.answer end' 2>/dev/null || echo \"$RESP\"\n"
            + "else\n"
            + "  echo \"$RESP\"\n"
            + "fi";

    private TextView txtScript, txtResult;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup p, @Nullable Bundle b) {
        View root = inf.inflate(R.layout.fragment_setup, p, false);
        txtScript = root.findViewById(R.id.txtScript);
        txtResult = root.findViewById(R.id.txtResult);
        txtScript.setText(SCRIPT);

        Button btnCopy = root.findViewById(R.id.btnCopy);
        Button btnTest = root.findViewById(R.id.btnTest);
        EditText edtAi = root.findViewById(R.id.edtAi);
        EditText edtPrompt = root.findViewById(R.id.edtPrompt);

        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("ai-bridge.sh", SCRIPT));
            Toast.makeText(requireContext(), "Script copied to clipboard", Toast.LENGTH_SHORT).show();
        });

        btnTest.setOnClickListener(v -> {
            String ai = edtAi.getText().toString().trim();
            String prompt = edtPrompt.getText().toString().trim();
            if (prompt.isEmpty()) { txtResult.setText("Enter a prompt first."); return; }
            txtResult.setText("Sending...");
            new Thread(() -> testRequest(ai, prompt)).start();
        });

        return root;
    }

    private void testRequest(String ai, String prompt) {
        try {
            String url = "http://127.0.0.1:8080/ask?_=" + System.currentTimeMillis();
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            String body = "{\"ai\":\"" + ai + "\",\"prompt\":\""
                    + prompt.replace("\"", "\\\"") + "\"}";
            c.getOutputStream().write(body.getBytes("UTF-8"));
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            final String out = sb.toString();
            new Handler(Looper.getMainLooper()).post(() -> txtResult.setText(out));
        } catch (Exception e) {
            final String err = "Error: " + e.getMessage();
            new Handler(Looper.getMainLooper()).post(() -> txtResult.setText(err));
        }
    }
}
