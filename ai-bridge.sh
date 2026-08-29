#!/bin/bash
# AI Bridge Termux wrapper - copy to Termux and chmod +x
# Usage: ./ai-bridge.sh <chatgpt|gemini|claude> "your prompt"
#
# The bridge runs an offline cyber-safety guardrail: unsafe/ambiguous prompts
# are auto-rewritten into an educational + defensive form before reaching the
# AI; clearly illegal content is BLOCKED (never forwarded). The wrapper prints
# the safety verdict (level/category/rewritten/blocked) and the AI answer.
AI_TYPE="${1:-chatgpt}"
PROMPT="$2"
if [ -z "$PROMPT" ]; then
  echo 'Usage: ./ai-bridge.sh <chatgpt|gemini|claude> "your prompt"'
  exit 1
fi

RESP=$(curl -s -X POST "http://127.0.0.1:8080/ask" \
  -H "Content-Type: application/json" \
  -d "{\"ai\":\"$AI_TYPE\",\"prompt\":\"$PROMPT\"}")

if command -v jq >/dev/null 2>&1; then
  echo "$RESP" | jq -r '
    if (.safety.blocked == true) then
      "=== BLOCKED BY SAFETY ===",
      "category: " + .safety.category,
      (.safety.note_hi // .safety.note_en)
    else
      "=== SAFETY ===",
      "level    : " + (.safety.level|tostring),
      "category : " + .safety.category,
      "rewritten: " + (.safety.rewritten|tostring),
      (if .safety.note_hi != "" then "note(hi): " + .safety.note_hi else empty end),
      "=== ANSWER ===",
      .answer
    end
  ' 2>/dev/null || echo "$RESP"
else
  echo "$RESP"
fi
