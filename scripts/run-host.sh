#!/usr/bin/env bash
# Starts the AndroPilot host with a token that survives restarts.
#
# The host generates a random token when it is not given one, so every restart invalidates
# the endpoint saved on the phone. This keeps one in .andropilot/token and reuses it.
#
#   ./scripts/run-host.sh                       bridge + telemetry ingest
#   ANDROPILOT_MODEL_KEY=sk-... \
#     ANDROPILOT_MODEL_ENDPOINT=https://openrouter.ai/api/v1 \
#     ANDROPILOT_MODEL=vendor/model-name ./scripts/run-host.sh
#   NO_BUILD=1 ./scripts/run-host.sh            skip Gradle when nothing changed
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PORT="${PORT:-8765}"
INGEST_PORT="${INGEST_PORT:-8766}"
# 0.0.0.0 because the phone has to reach this across the LAN. The token is what stands
# between that and everyone else on the network.
BIND="${BIND:-0.0.0.0}"
UI_PORT="${UI_PORT:-0}"

mkdir -p .andropilot
TOKEN_FILE=".andropilot/token"
if [ ! -s "$TOKEN_FILE" ]; then
  # Base64url: a '+' or '/' in a token that also travels as a ?token= query parameter is an
  # escaping bug waiting to happen.
  head -c 24 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n' > "$TOKEN_FILE"
  chmod 600 "$TOKEN_FILE"
  echo "Generated a new token and saved it to $TOKEN_FILE"
fi
TOKEN="$(cat "$TOKEN_FILE")"

LAUNCHER="host/build/install/andropilot-host/bin/andropilot-host"
if [ -z "${NO_BUILD:-}" ] || [ ! -x "$LAUNCHER" ]; then
  echo "Building the host..."
  ./gradlew :andropilot-host:installDist --quiet
fi

# The address on the interface that actually routes out, not merely the first one: a machine
# with Docker, a VPN or several NICs has more than one and only one is on the phone's network.
LAN="$(ip route get 1.1.1.1 2>/dev/null | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}' || true)"
[ -n "$LAN" ] || LAN="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
[ -n "$LAN" ] || LAN="<this machine's LAN address>"

ARGS=(--bind "$BIND" --port "$PORT" --token "$TOKEN" --skills "$ROOT/skills" --ingest-port "$INGEST_PORT")
[ "$UI_PORT" -gt 0 ] && ARGS+=(--ui-port "$UI_PORT")
[ -n "${MCP:-}" ] && ARGS+=(--mcp)
if [ -n "${ANDROPILOT_MODEL_ENDPOINT:-}" ]; then
  ARGS+=(--model-endpoint "$ANDROPILOT_MODEL_ENDPOINT" --model "${ANDROPILOT_MODEL:-gpt-4o-mini}")
  [ -n "${ANDROPILOT_MODEL_KEY:-}" ] || \
    echo "ANDROPILOT_MODEL_KEY is not set; the model endpoint will refuse the request."
fi
# The key is deliberately NOT passed as an argument: the host reads it from the environment
# it inherits, and an argument is readable by anything that can list processes.

echo
echo "  Host endpoint for the agent app:  ws://$LAN:$PORT/agent"
echo "  Shared token:                     $TOKEN"
[ "$UI_PORT" -gt 0 ] && echo "  Control page:                     http://127.0.0.1:$UI_PORT"
echo

exec "$LAUNCHER" "${ARGS[@]}"
