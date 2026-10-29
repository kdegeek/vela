#!/bin/bash
# Shared helpers for the Vela owner-run acceptance scripts. Source this file; do not run it.
# Scripts run as the `vela` user inside the kiosk session (vela-ctl acceptance <name> arranges that).
#
#   check <name> <cmd...>   run cmd; print "PASS name" or "FAIL name: <last lines of its output>"
#   pass <name> / fail <name> <why>   record a result the script decided itself
#   warn <name> <message>   print "WARN name: message" (optional hardware; not a failure)
#   info <message>          print "INFO message"
#   confirm <name> <prompt> ask the owner y/n; PASS on y, FAIL otherwise (VELA_CONFIRM=y|n answers for tests)
#   summary                 print the totals; returns 1 if anything failed
#   report_dir              print (and create) the directory the scripts write their output to
#   begin <script>          copy this script's output into report_dir/<script>.txt as well
#   bundle                  write ~/vela-report-<YYYYmmdd-HHMMSS>.tar.gz and print its path
#   finish                  summary + bundle; the exit status says whether any check failed
set -u

VELA_URL=${VELA_URL:-http://127.0.0.1:7300}
VELA_PASSED=0
VELA_FAILED=0
VELA_WARNED=0

info() { printf 'INFO %s\n' "$*"; }

# Sets XDG_RUNTIME_DIR, WAYLAND_DISPLAY and SWAYSOCK from /run/user/<uid> when the caller (an SSH
# shell, say) doesn't have them, so swaymsg, grim and wtype reach the running kiosk session.
locate_session() {
  : "${XDG_RUNTIME_DIR:=/run/user/$(id -u)}"
  export XDG_RUNTIME_DIR
  local f best=
  if [ -z "${WAYLAND_DISPLAY:-}" ]; then
    for f in "$XDG_RUNTIME_DIR"/wayland-[0-9]; do
      [ -S "$f" ] || [ -e "$f" ] || continue
      if [ -z "$best" ] || [ "$f" -nt "$best" ]; then best=$f; fi
    done
    [ -n "$best" ] && export WAYLAND_DISPLAY=${best##*/}
  fi
  if [ -z "${SWAYSOCK:-}" ]; then
    best=
    for f in "$XDG_RUNTIME_DIR"/sway-ipc.*.sock; do
      [ -e "$f" ] || continue
      if [ -z "$best" ] || [ "$f" -nt "$best" ]; then best=$f; fi
    done
    [ -n "$best" ] && export SWAYSOCK=$best
  fi
  if [ -z "${DBUS_SESSION_BUS_ADDRESS:-}" ] && [ -S "$XDG_RUNTIME_DIR/bus" ]; then
    export DBUS_SESSION_BUS_ADDRESS="unix:path=$XDG_RUNTIME_DIR/bus"
  fi
  return 0
}

report_dir() {
  local d=${VELA_REPORT_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/vela-acceptance}
  mkdir -p "$d"
  printf '%s\n' "$d"
}

# begin <script>: from here on, everything the script prints also lands in report_dir/<script>.txt.
begin() {
  locate_session
  local d
  d=$(report_dir)
  exec > >(tee "$d/$1.txt") 2>&1
  info "$1 on $(uname -n), kernel $(uname -r), $(date -Is)"
}

check() {
  local name=$1 out rc
  shift
  out=$("$@" 2>&1)
  rc=$?
  if [ "$rc" -eq 0 ]; then
    printf 'PASS %s\n' "$name"
    VELA_PASSED=$((VELA_PASSED + 1))
  else
    local tail
    tail=$(printf '%s\n' "$out" | grep -v '^$' | tail -n 3 | paste -sd '|' - | sed 's/|/ | /g')
    [ -n "$tail" ] || tail="command failed (exit $rc) with no output: $*"
    printf 'FAIL %s: %s\n' "$name" "$tail"
    VELA_FAILED=$((VELA_FAILED + 1))
  fi
  return "$rc"
}

pass() { printf 'PASS %s\n' "$1"; VELA_PASSED=$((VELA_PASSED + 1)); }
fail() { printf 'FAIL %s: %s\n' "$1" "$2"; VELA_FAILED=$((VELA_FAILED + 1)); }

warn() {
  printf 'WARN %s: %s\n' "$1" "$2"
  VELA_WARNED=$((VELA_WARNED + 1))
}

# confirm <name> <prompt>: y counts as PASS, anything else (or no answer) as FAIL.
confirm() {
  local name=$1 prompt=$2 ans=${VELA_CONFIRM:-}
  if [ -z "$ans" ]; then
    printf '%s [y/n] ' "$prompt" >&2
    read -r ans || ans=n
  fi
  case "$ans" in
    [Yy]*) printf 'PASS %s\n' "$name"; VELA_PASSED=$((VELA_PASSED + 1)); return 0 ;;
    *) printf 'FAIL %s: you answered no\n' "$name"; VELA_FAILED=$((VELA_FAILED + 1)); return 1 ;;
  esac
}

summary() {
  local w=warnings
  [ "$VELA_WARNED" -eq 1 ] && w=warning
  printf -- '--- %d passed, %d failed, %d %s\n' "$VELA_PASSED" "$VELA_FAILED" "$VELA_WARNED" "$w"
  [ "$VELA_FAILED" -eq 0 ]
}

# --- helpers shared by the scripts -------------------------------------------------------------

# api_get <path> / api_post <path> [json]: the daemon wants JSON POSTs and a loopback Host.
api_get() { curl -fsS -m 10 "$VELA_URL$1"; }
api_post() { curl -fsS -m 30 -X POST -H 'Content-Type: application/json' -d "${2:-"{}"}" "$VELA_URL$1"; }

# screenshot <name>: one grim PNG of the whole output into the report dir.
screenshot() {
  command -v grim >/dev/null 2>&1 || { echo "grim is not installed" >&2; return 1; }
  grim "$(report_dir)/$1.png"
}

# --- the bundle ----------------------------------------------------------------------------------

# Masks api keys and tokens in a text file, in place.
redact_file() {
  local f=$1
  sed -E \
    -e 's/([Aa]pi_?[Kk]ey=)[^&[:space:]"]+/\1[redacted]/g' \
    -e 's/([Tt]oken=)[^&[:space:]"]+/\1[redacted]/g' \
    -e 's/(Authorization: *(Bearer|MediaBrowser)?) *[^[:space:]]+/\1 [redacted]/g' \
    -e 's/("[A-Za-z]*([Tt]oken|[Aa]pi[Kk]ey)": *)"[^"]*"/\1"[redacted]"/g' \
    "$f" > "$f.redacted" && mv "$f.redacted" "$f"
}

# Runs a collector into <dir>/<file>, noting a missing tool instead of failing.
collect() { # collect <dir> <file> <cmd...>
  local dir=$1 file=$2
  shift 2
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "($1 is not installed)" > "$dir/$file"
  else
    "$@" > "$dir/$file" 2>&1 || echo "(exit status $?)" >> "$dir/$file"
  fi
}

collect_system() { # collect_system <dir>
  local d=$1
  mkdir -p "$d"
  cp /usr/lib/os-release "$d/os-release" 2>/dev/null || echo "(no /usr/lib/os-release)" > "$d/os-release"
  collect "$d" uname.txt uname -a
  collect "$d" journal-system.txt journalctl -b --no-pager -n "${VELA_JOURNAL_LINES:-3000}"
  collect "$d" journal-user.txt journalctl --user -b --no-pager -n "${VELA_JOURNAL_LINES:-3000}"
  collect "$d" sway-tree.json swaymsg -t get_tree
  collect "$d" sway-outputs.json swaymsg -t get_outputs
  collect "$d" vainfo.txt vainfo
  collect "$d" lsusb.txt lsusb
  collect "$d" lsmod.txt lsmod
  collect "$d" wpctl-status.txt wpctl status
  collect "$d" user-units.txt systemctl --user --no-pager --failed
  collect "$d" daemon-health.txt curl -sS -m 5 "$VELA_URL/api/health"
}

bundle() {
  locate_session
  local ts name stage out src f
  ts=$(date +%Y%m%d-%H%M%S)
  name=vela-report-$ts
  src=$(report_dir)
  stage=$(mktemp -d)
  mkdir -p "$stage/$name"
  cp -R "$src"/. "$stage/$name/" 2>/dev/null
  collect_system "$stage/$name/system"
  # The HA token and Jellyfin key live there; never ship it, whatever else got copied.
  find "$stage" -name 'secrets.json*' -exec rm -f {} +
  while IFS= read -r f; do redact_file "$f"; done < <(find "$stage/$name" -type f ! -name '*.png')
  out=${VELA_BUNDLE_DIR:-$HOME}/$name.tar.gz
  tar czf "$out" -C "$stage" "$name" || { rm -rf "$stage"; echo "could not write $out" >&2; return 1; }
  rm -rf "$stage"
  printf 'Report bundle: %s\n' "$out"
}

finish() {
  local rc=0
  summary || rc=1
  sleep 1  # let the output copy to report_dir/<script>.txt (tee) catch up before it is bundled
  bundle || rc=1
  return "$rc"
}
