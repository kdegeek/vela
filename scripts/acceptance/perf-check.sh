#!/bin/bash
# Vela 4K frame-timing check. Arms the launcher's 30 s sample, moves the focus around with arrow
# keys the whole time, then reads the result. Run it with the Vela home screen showing.
#   vela-ctl acceptance perf-check
# In effects=auto mode the run measures full effects; a failure here means lite effects are needed.
set -u
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib.sh
. "$here/lib.sh"
begin perf-check

SAMPLE_S=30
TIMEOUT_S=45
KEY_INTERVAL=0.25

check "vela-daemon answers on 127.0.0.1:7300" api_get /api/health || { finish; exit 1; }
check "wtype is installed" command -v wtype || { finish; exit 1; }

# Start from the home screen, with the launcher window focused, so the keys land on the launcher.
KIOSK_RE='^(chrome-127\.0\.0\.1|chromium)'
launcher_focused() {
  swaymsg -t get_tree -r | jq -e --arg re "$KIOSK_RE" '[recurse(.nodes[]?, .floating_nodes[]?) | select(.focused == true and ((.app_id // "") | test($re)))] | length > 0' >/dev/null
}
focus_launcher() {
  api_post /api/home/go >/dev/null || { echo "POST /api/home/go failed"; return 1; }
  sleep 2
  launcher_focused && return 0
  swaymsg "[app_id=\"$KIOSK_RE\"] focus" >/dev/null 2>&1
  sleep 1
  launcher_focused || { echo "the launcher window could not be focused; is the kiosk running (hw-check)?"; return 1; }
}
check "launcher has focus" focus_launcher || { finish; exit 1; }

# One key every 250 ms for longer than the sample: right along a row, down, back left, down, ...
keys=(Right Right Right Right Right Right Down Left Left Left Left Left Left Down
      Right Right Right Right Right Right Down Left Left Left Left Left Left Up Up Up)
inject() {
  local i=0 end=$((SECONDS + SAMPLE_S + 3))
  while [ "$SECONDS" -lt "$end" ]; do
    wtype -k "${keys[i % ${#keys[@]}]}" 2>/dev/null
    i=$((i + 1))
    sleep "$KEY_INTERVAL"
  done
}

echo "Arming the sample; keep your hands off the remote for $SAMPLE_S seconds."
check "perf sample armed (POST /api/perf/arm)" api_post /api/perf/arm '{}' || { finish; exit 1; }
inject &
injector=$!
report=
deadline=$((SECONDS + TIMEOUT_S))
while [ "$SECONDS" -lt "$deadline" ]; do
  r=$(api_get /api/perf 2>/dev/null) || { sleep 1; continue; }
  if printf '%s' "$r" | jq -e '.report != null' >/dev/null 2>&1; then report=$(printf '%s' "$r" | jq -c .report); break; fi
  sleep 1
done
kill "$injector" 2>/dev/null
wait "$injector" 2>/dev/null

if [ -z "$report" ]; then
  check "perf report received within ${TIMEOUT_S} s" false
  echo "FAIL perf report: none arrived. Is the launcher on screen (not an app)? journalctl --user -u vela-kiosk"
  finish; exit 1
fi
echo "perf report: $report"
effects=$(printf '%s' "$report" | jq -r .effects)
echo "INFO effects mode measured: $effects (in auto mode this run measures full effects)"

meets() { # meets <label> <jq expression>
  printf '%s' "$report" | jq -e "$2" >/dev/null || { echo "$1: measured $(printf '%s' "$report" | jq -c '{width,height,frames,p50Ms,p95Ms,droppedPct,effects}')"; return 1; }
}
check "frames measured at width 3840" meets width '.width == 3840'
check "p95 frame time <= 17.5 ms" meets p95 '.p95Ms <= 17.5'
check "dropped frames < 1%" meets dropped '.droppedPct < 1'
echo "INFO frames sampled: $(printf '%s' "$report" | jq -r .frames)"

screenshot perf-check-screen >/dev/null 2>&1 || warn "screenshot" "grim could not capture the screen"
finish
