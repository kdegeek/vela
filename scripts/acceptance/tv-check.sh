#!/bin/bash
# Vela TV control check: power and volume through Home Assistant (IR fallback for power on).
# Needs a kind="tv" device in /etc/vela/vela.toml and a paired TV in Home Assistant. Sit where you can
# see and hear the TV; you answer y/n after each step.
#   vela-ctl acceptance tv-check
set -u
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib.sh
. "$here/lib.sh"
begin tv-check

check "vela-daemon answers on 127.0.0.1:7300" api_get /api/health || { finish; exit 1; }
devices=$(api_get /api/devices 2>/dev/null || echo '[]')
mapfile -t tvs < <(printf '%s' "$devices" | jq -r '.[] | select(.kind == "tv") | .id')
if [ "${#tvs[@]}" -eq 0 ]; then
  check "a TV device is configured" false
  echo "FAIL no kind=\"tv\" device: add a [[devices]] entry (docs/install.md, Home Assistant section), then restart vela-daemon."
  finish; exit 1
fi

# IR leaves no trace in the daemon's responses, so watch for the sender process while power_on runs.
watch_ir() { # watch_ir <flag-file>
  while :; do
    pgrep -x 'ir-ctl|pw-play|aplay' >/dev/null && { : > "$1"; return; }
    sleep 0.05
  done
}

for id in "${tvs[@]}"; do
  echo "=== TV device: $id"
  check "$id power_off request" api_post "/api/devices/$id/power_off" \
    || echo "HINT is the TV paired in Home Assistant, and is homeAssistantToken in secrets.json?"
  echo "Waiting 10 s..."
  sleep 10
  confirm "$id is off" "Did the TV turn off?"

  flag=$(mktemp -u)
  watch_ir "$flag" &
  watcher=$!
  check "$id power_on request" api_post "/api/devices/$id/power_on" \
    || echo "HINT neither Home Assistant nor the IR fallback worked; a TV that is fully off needs powerOnFallback in vela.toml and an IR transmitter"
  kill "$watcher" 2>/dev/null; wait "$watcher" 2>/dev/null
  if [ -e "$flag" ]; then echo "INFO $id power on used the IR fallback"; else echo "INFO $id power on did not use the IR fallback (best effort: a very short IR burst could be missed)"; fi
  rm -f "$flag"
  confirm "$id is on" "Did the TV turn on (give it a few seconds)?"
  sleep 5

  for n in 1 2; do check "$id volume_up request $n" api_post "/api/devices/$id/volume_up"; sleep 1; done
  confirm "$id volume went up" "Did the volume go up twice?"
  for n in 1 2; do check "$id volume_down request $n" api_post "/api/devices/$id/volume_down"; sleep 1; done
  confirm "$id volume went down" "Did the volume go down twice?"
done
finish
