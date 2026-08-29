# AI Bridge — Local HTTP Bridge Server (Termux CLI <-> Web AI)

A local Android app that lets **Termux CLI** (or any localhost client) talk to
**ChatGPT / Gemini / Claude** using your **own logged-in web sessions** — no
official API keys required. It drives hidden WebViews with JavaScript
automation and exposes a tiny NanoHTTPD server on `127.0.0.1:8080`.

## How it works
1. You open the app and manually log into each AI in its WebView tab
   (sessions persist via cookie storage).
2. Flip "Background Execution" to hide the views (they keep running in RAM).
3. Start the foreground server (Tab 2).
4. From Termux: `./ai-bridge.sh chatgpt "explain recursion"`

## Endpoints
- `GET  /status` -> JSON of session readiness / login state.
- `POST /ask`   -> `{"ai":"chatgpt|gemini|claude","prompt":"...","stream":false}`
  - Returns `{"answer": "...", "safety": {level, category, rewritten, note_hi, ...}}`

## Cyber-safety guardrail (built in, offline)
Every `/ask` prompt is passed through `CyberSafetyEngine` **before** it reaches
the AI. It:
1. Detects the category (recon, sqli, xss, creds, phishing, malware, dos, mitm,
   privesc, social, wifi, defense, learning) via keyword matching.
2. Detects scope flags — unauthorized third-party target (neighbor, company,
   IP/domain, "without permission") vs authorized scope (own lab, DVWA, CTF,
   written permission).
3. Assigns a risk level:
   - `0 SAFE`        — general / defensive question, forwarded as-is.
   - `1 EDUCATIONAL` — authorized-scope practice, forwarded as-is.
   - `2 CAUTION`     — ambiguous offensive intent → rewritten to an educational
                     + defensive version before sending.
   - `3 UNSAFE`      — explicit third-party / unauthorized target → original
                     target stripped, rewritten to authorized practice scope.
   - `4 BLOCKED`     — clearly illegal / severe-harm content (bombs, poisons,
                     carding, CSAM, weapons, etc.) → NEVER forwarded; a refusal
                     with a legal note is returned instead.
4. The verdict (level, category, rewritten flag, Hindi + English note) is
   returned alongside the answer so the Termux client always knows a rewrite
   happened.

Only practice on systems you own or have **written authorization** to test
(labs, CTFs, OWASP WebGoat, DVWA). The guardrail is a safety aid, not a
substitute for your own legal responsibility.

## Build
- Open in Android Studio (Giraffe+), sync, run on device (minSdk 24).
- Or build APK: `./gradlew assembleDebug`.
- Install on the Realme: `adb install app/build/outputs/apk/debug/app-debug.apk`
  (or copy APK + install via file manager / Shizuku).

## Termux usage
```
pkg install curl
curl -s -X POST http://127.0.0.1:8080/ask \
  -H "Content-Type: application/json" \
  -d '{"ai":"chatgpt","prompt":"hello"}'
```

## Notes / limits
- Web AI UIs change selectors frequently; update the JS in `JSInjection.java`
  if a platform ships a new layout.
- Cloudflare challenges surface as:
  `{"error":"Cloudflare Challenge Detected, please open App UI to complete CAPTCHA"}`
  -> open the app, solve the CAPTCHA, retry.
- This tool is for **your own accounts, on your own device** (lab/CTF/personal
  automation). Respect each platform's ToS.
