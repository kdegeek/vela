#!/bin/bash
# Starts the deployed daemon with no sway, no gamepads and a throwaway config, and checks
# that /api/health and the launcher page answer. Usage:
#   smoke-daemon.sh <node> <daemon-dir> <assets-dir> <manifests-dir> <launcher-dir>
set -euo pipefail
node="$1" dir="$2" assets="$3" manifests="$4" launcher="$5"
tmp=$(mktemp -d)
printf 'manifestDirs = ["%s"]\n' "$manifests" > "$tmp/vela.toml"
"$node" --version
cd "$dir"
VELA_SWAY=noop VELA_GAMEPAD=0 VELA_CONFIG="$tmp/vela.toml" VELA_SECRETS="$tmp/secrets.json" \
  VELA_ASSETS_DIR="$assets" VELA_LAUNCHER_DIR="$launcher" VELA_IRDB="$tmp" \
  "$node" dist/main.js > "$tmp/log" 2>&1 &
pid=$!
ok=0
for _ in $(seq 1 60); do
  if curl -fsS -H 'Host: 127.0.0.1:7300' http://127.0.0.1:7300/api/health; then ok=1; break; fi
  kill -0 "$pid" 2>/dev/null || break
  sleep 0.5
done
echo
if [ "$ok" = 1 ]; then
  code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: 127.0.0.1:7300' http://127.0.0.1:7300/)
  home=$(curl -fsS -H "Host: 127.0.0.1:7300" http://127.0.0.1:7300/api/home | head -c 300 || true)
  echo "launcher page: HTTP $code; /api/home: $home"
  [ "$code" = 200 ] || ok=0
fi
kill "$pid" 2>/dev/null || true
wait "$pid" 2>/dev/null || true
cat "$tmp/log"
rm -rf "$tmp"
[ "$ok" = 1 ] || { echo "daemon smoke check FAILED" >&2; exit 1; }
echo "daemon smoke check passed"
