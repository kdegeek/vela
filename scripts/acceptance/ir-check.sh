#!/bin/bash
# Vela IR transmitter check: lists the transmitters, sends a test code on each, you confirm.
# A phone camera shows an IR blaster's LED flashing; or watch the device react.
#   vela-ctl acceptance ir-check
set -u
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib.sh
. "$here/lib.sh"
begin ir-check

shopt -s nullglob
lircs=(/dev/lirc*)
echo "Transmitters found:"
for l in "${lircs[@]}"; do echo "  LIRC device $l"; done
alsa=$(aplay -l 2>/dev/null | grep -Ei 'analog|headphone' | head -n 1)
[ -n "$alsa" ] && echo "  headphone-jack sink: $alsa"
[ "${#lircs[@]}" -eq 0 ] && [ -z "$alsa" ] && echo "  (none)"

check "at least one IR transmitter is present" test "${#lircs[@]}" -gt 0 -o -n "$alsa"

for l in "${lircs[@]}"; do
  if command -v ir-ctl >/dev/null 2>&1; then
    # A fixed NEC scancode; any IR receiver sees the burst, a phone camera sees the LED.
    check "ir-ctl sends on $l" ir-ctl -d "$l" -S nec:0x00ff
    confirm "$l transmitted" "Did the blaster on $l flash (phone camera) or the target device react?"
  else
    check "ir-ctl is installed" false
  fi
done

# The daemon's own path: the first command of each configured IR device, on the transmitter it names.
if api_get /api/health >/dev/null 2>&1; then
  devices=$(api_get /api/devices 2>/dev/null || echo '[]')
  mapfile -t irs < <(printf '%s' "$devices" | jq -r '.[] | select(.kind == "ir" and (.commands | length) > 0) | "\(.id) \(.commands[0])"')
  if [ "${#irs[@]}" -eq 0 ]; then
    warn "configured IR devices" "no kind=\"ir\" device in vela.toml; only the raw transmitter test ran"
  fi
  for line in "${irs[@]}"; do
    id=${line%% *}
    cmd=${line#* }
    check "daemon sends $id/$cmd" api_post "/api/devices/$id/$cmd"
    confirm "$id/$cmd reached the device" "Did the device respond to '$cmd'?"
  done
else
  warn "daemon" "vela-daemon is not answering; skipped the configured-device test"
fi
finish
