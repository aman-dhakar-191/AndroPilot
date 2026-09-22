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

[ -n "${PORT:-}" ] && PORT_SET=1
[ -n "${INGEST_PORT:-}" ] && INGEST_SET=1
[ -n "${BIND:-}" ] && BIND_SET=1
PORT="${PORT:-8765}"
INGEST_PORT="${INGEST_PORT:-8766}"
# 0.0.0.0 because the phone has to reach this across the LAN. The token is what stands
# between that and everyone else on the network.
BIND="${BIND:-0.0.0.0}"
UI_PORT="${UI_PORT:-0}"

# Settings live in the home directory, not beside the checkout: the token is in them, and a
# token in the working copy is lost when the repository is moved or re-cloned -- which means
# reconfiguring the phone by hand.
SETTINGS_DIR="$HOME/.andropilot-host"
SETTINGS="$SETTINGS_DIR/settings.json"
mkdir -p "$SETTINGS_DIR"

if [ ! -s "$SETTINGS" ] || ! grep -q '"token"' "$SETTINGS" 2>/dev/null; then
  # Carry over a token from the old location before inventing one: a fresh token would
  # silently invalidate whatever the phone already has saved.
  if [ -s ".andropilot/token" ]; then
    TOKEN="$(cat .andropilot/token)"
    echo "Moving your existing token into $SETTINGS"
  else
    # Base64url: a '+' or '/' in a token that also travels as a ?token= query parameter is
    # an escaping bug waiting to happen.
    TOKEN="$(head -c 24 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')"
    echo "Generated a new token and saved it to $SETTINGS"
  fi
  printf '{\n  "token": "%s"\n}\n' "$TOKEN" > "$SETTINGS"
  chmod 600 "$SETTINGS"
fi
TOKEN="$(sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$SETTINGS" | head -1)"

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

# Only what this run asks for. The host reads the settings file itself, and passing a
# default here would override a value the file deliberately sets.
ARGS=(--skills "$ROOT/skills")
[ -n "${BIND_SET:-}" ] && ARGS+=(--bind "$BIND")
[ -n "${PORT_SET:-}" ] && ARGS+=(--port "$PORT")
[ -n "${INGEST_SET:-}" ] && ARGS+=(--ingest-port "$INGEST_PORT")
[ "$UI_PORT" -gt 0 ] && ARGS+=(--ui-port "$UI_PORT")
[ -n "${MCP:-}" ] && ARGS+=(--mcp)
if [ -n "${ANDROPILOT_MODEL_ENDPOINT:-}" ]; then
  ARGS+=(--model-endpoint "$ANDROPILOT_MODEL_ENDPOINT" --model "${ANDROPILOT_MODEL:-gpt-4o-mini}")
fi
# The key is never passed as an argument: the host takes it from the settings file or from
# the environment it inherits, and an argument is readable by anything that lists processes.

echo
echo "  Host endpoint for the agent app:  ws://$LAN:$PORT/agent"
echo "  Shared token:                     $TOKEN"
[ "$UI_PORT" -gt 0 ] && echo "  Control page:                     http://127.0.0.1:$UI_PORT"
echo

exec "$LAUNCHER" "${ARGS[@]}"
