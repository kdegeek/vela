#!/bin/bash
# Boots the smoke-test qcow2 (dev/vm/build-qcow2.sh) at 3840x2160 and checks it:
#   - phone setup (the image has no vela.toml): vm-setup-3840.png shows the setup screen's QR code;
#     with the PIN the diag service prints, this script plays the phone through QEMU's port
#     forward (port 7301): skip media, add a throwaway SSH key, finish. The diag service checks
#     what root's vela-setup-commit installed; this script then logs in over SSH with that key
#   - after setup (and the diag service's test-only listen = 0.0.0.0): /api/health answers and
#     /api/home lists 6 apps (through QEMU's port forward)
#   - vm-home-3840.png: a 3840x2160 screenshot of the home screen that is not one colour
#   - the Home tile's glyph is drawn (not a near-uniform box)
#   - the diag service's CHECK lines on the serial console: the kiosk window is on workspace
#     "home", vela-daemon and vela-kiosk are active, the session runs in unconfined_t,
#     graphical-session.target is active and a portal call answers; all again after sway is crashed
#     (vm-after-crash.png is the screen after the recovery)
#   - hw-check.sh (the owner's acceptance script) exits with 0 or 1 and leaves a report bundle
#     (most of its checks fail in a VM; only the end-to-end run is asserted)
#   - degraded mode: with a broken /etc/vela/vela.toml the restarted daemon still serves the
#     launcher and /api/home, and its status line names vela.toml; restoring the file recovers
#   - automatic updates: bootc-fetch-apply-updates stages only (no --apply), on a 03:00 timer
# Everything lands in <artifact-dir>: the PNGs, serial.log, diag.txt and runner-checks.txt.
# CHECK lines come from the diag service (serial console) and from this script (setup_screen_shown,
# setup_ssh_login); all must pass and there must be $EXPECTED_PASS of them.
#
# Usage: run-smoke.sh <disk.qcow2> <artifact-dir>
# Needs qemu-system-x86_64 with KVM, OVMF, curl, jq, ssh, ssh-keygen, python3-numpy and python3-pil.
set -euo pipefail
disk="$1" art="$2"
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$art"
art=$(cd "$art" && pwd)
work=$(mktemp -d)
serial="$art/serial.log"
qmp="$work/qmp.sock"
runner_checks="$art/runner-checks.txt"
: > "$runner_checks"
fail=0
# 22 before phone setup, 6 setup checks from the diag service, 2 from this script.
EXPECTED_PASS=30

ovmf_code=/usr/share/OVMF/OVMF_CODE_4M.fd
cp /usr/share/OVMF/OVMF_VARS_4M.fd "$work/vars.fd"

# UEFI like the Mac mini. The disk is used as a snapshot so the qcow2 stays pristine.
qemu-system-x86_64 -name vela-smoke -machine q35 -enable-kvm -cpu host -m 6G -smp 4 \
  -drive "if=pflash,format=raw,readonly=on,file=$ovmf_code" \
  -drive "if=pflash,format=raw,file=$work/vars.fd" \
  -drive "file=$disk,if=virtio,format=qcow2" -snapshot \
  -device virtio-vga,xres=3840,yres=2160 \
  -nic user,model=virtio-net-pci,hostfwd=tcp::7300-:7300,hostfwd=tcp::7301-:7301,hostfwd=tcp::2222-:22 \
  -qmp "unix:$qmp,server=on,wait=off" \
  -serial "file:$serial" -display none \
  -daemonize -pidfile "$work/qemu.pid"
qemu_pid=$(cat "$work/qemu.pid")
cleanup() { kill "$qemu_pid" 2>/dev/null || true; rm -rf "$work"; }
trap cleanup EXIT
start=$SECONDS
echo "QEMU started (pid $qemu_pid)"

alive() { kill -0 "$qemu_pid" 2>/dev/null; }
elapsed() { echo "$((SECONDS - start)) s"; }
# wait_serial <pattern> <seconds>
wait_serial() {
  local deadline=$((SECONDS + $2))
  until grep -aq -- "$1" "$serial" 2>/dev/null; do
    alive || { echo "QEMU exited while waiting for '$1'" >&2; return 1; }
    [ "$SECONDS" -lt "$deadline" ] || { echo "timed out waiting for '$1'" >&2; return 1; }
    sleep 1
  done
  echo "serial: '$1' after $(elapsed)"
}
# The Home tile's glyph (last of the 6 app tiles; the layout is fixed in rem at 3840x2160).
HOME_GLYPH_BOX=2838,1353,2953,1468
shot() { # shot <name> [checked]
  if python3 "$here/screendump.py" "$qmp" "$art/$1" --glyph-box "$HOME_GLYPH_BOX"; then return 0; fi
  [ "${2:-}" = checked ] && fail=1
  return 0
}
# The setup screen's QR code (the layout is fixed in rem at 3840x2160; see screendump.py).
SETUP_QR_BOX=2700,750,3450,1550
# rcheck <name> <command...>: a CHECK line from this script, in the diag service's format.
rcheck() {
  local name=$1; shift
  if "$@"; then echo "CHECK $name PASS" | tee -a "$runner_checks"; else echo "CHECK $name FAIL" | tee -a "$runner_checks"; fail=1; fi
}
all_checks() { { grep -a 'CHECK ' "$serial"; cat "$runner_checks"; } 2>/dev/null | tr -d '\r' | sort -u; }
finish() {
  sed -n '/VELA-SMOKE DIAG BEGIN/,/VELA-SMOKE DIAG END/p' "$serial" | tr -d '\r' > "$art/diag.txt" || true
  echo "--- hw-check output (failures are expected in a VM)"
  sed -n '/^################ hw-check output/,$p' "$art/diag.txt" | sed -n '2,60p' || true
  echo "--- CHECK lines"
  all_checks || true
  if [ "$fail" != 0 ]; then
    echo "--- serial tail"
    tail -n 80 "$serial" | tr -d '\r' || true
    echo "VM smoke test FAILED" >&2
    exit 1
  fi
  echo "VM smoke test passed"
  exit 0
}

# --- 0. phone setup, played from the host -----------------------------------------------------------
# The PIN comes from the diag service on the serial console (the newest line: it prints a new PIN
# again). The setup listener only accepts Host 127.0.0.1:7301 or localhost:7301 through the port
# forward, so the host port must be the guest's 7301.
SETUP_PORT=7301
# (|| true: with pipefail a missing line would fail the assignment and set -e would stop the run.)
setup_pin() { { grep -ao 'VELA-SMOKE SETUP-PIN [0-9]\{6\}' "$serial" || true; } | tail -n 1 | awk '{print $3}'; }
# setup_post <path> <json>: 200 = done. A busy box (429) is retried; after a wrong PIN (401) this
# waits for the diag service to print a different one rather than burning the 5 tries.
setup_post() {
  local code pin i
  for i in $(seq 1 15); do
    pin=$(setup_pin)
    code=$(curl -sS -m 30 -o "$work/setup-response" -w '%{http_code}' -X POST \
      -H "Authorization: Pin $pin" -H 'Content-Type: application/json' --data "$2" \
      "http://127.0.0.1:$SETUP_PORT$1") || code=000
    echo "POST $1 (try $i): $code $(head -c 400 "$work/setup-response" 2>/dev/null || true)"
    case "$code" in
      200) return 0 ;;
      401)
        local deadline=$((SECONDS + 60))
        while [ "$(setup_pin)" = "$pin" ] && [ "$SECONDS" -lt "$deadline" ]; do sleep 2; done ;;
      429 | 000) sleep 3 ;;
      *) return 1 ;;
    esac
  done
  return 1
}
setup_shot() {
  python3 "$here/screendump.py" "$qmp" "$art/vm-setup-3840.png" --qr-box "$SETUP_QR_BOX"
}
ssh_login() {
  local i
  for i in 1 2 3 4 5 6; do
    if ssh -n -i "$work/smoke-key" -p 2222 -o BatchMode=yes -o StrictHostKeyChecking=no \
         -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o LogLevel=ERROR root@127.0.0.1 true; then
      echo "ssh root@ (key from setup) worked on try $i"; return 0
    fi
    sleep 10
  done
  return 1
}

ssh-keygen -q -t ed25519 -N '' -C vela-smoke-runner -f "$work/smoke-key"
if wait_serial 'VELA-SMOKE SETUP-PIN' 900; then
  rcheck setup_screen_shown setup_shot
  pin=$(setup_pin)
  if [ -z "$pin" ]; then
    echo "the diag service found no setup PIN (setup mode did not start?)" >&2
    fail=1
  else
    keys_json=$(jq -cn --arg k "$(cat "$work/smoke-key.pub")" '{keys: [$k]}')
    if setup_post /setup/api/jellyfin/skip '{}' && setup_post /setup/api/ssh/keys "$keys_json" \
         && setup_post /setup/api/finish '{}'; then
      echo "setup finished from the host after $(elapsed)"
    else
      echo "driving the setup API failed" >&2
      fail=1
    fi
  fi
else
  setup_shot || true
  rcheck setup_screen_shown false
  fail=1
fi
# The diag service waits for root's commit, checks it, appends listen = 0.0.0.0 and restarts the daemon.
if wait_serial 'VELA-SMOKE PHASE0' 720; then
  rcheck setup_ssh_login ssh_login
else
  rcheck setup_ssh_login false
  fail=1; finish
fi

# --- daemon over the port forward ----------------------------------------------------------------
deadline=$((SECONDS + 180))
until curl -fsS -m 5 http://127.0.0.1:7300/api/health; do
  if ! alive || [ "$SECONDS" -ge "$deadline" ]; then
    echo "/api/health did not answer within 3 minutes of setup" >&2
    shot vm-health-timeout.png || true
    fail=1; finish
  fi
  sleep 5
done
echo; echo "/api/health answered after $(elapsed)"
apps=$(curl -fsS -m 10 http://127.0.0.1:7300/api/home | jq '.apps | length' || echo none)
echo "/api/home: $apps apps"
if [ "$apps" != 6 ]; then
  echo "expected 6 apps on /api/home" >&2
  shot vm-home-3840.png || true
  fail=1; finish
fi

# --- first boot: the home screen -----------------------------------------------------------------
if wait_serial 'VELA-SMOKE PHASE1' 720; then
  shot vm-home-3840.png checked
else
  shot vm-home-3840.png || true
  fail=1; finish
fi

# --- after the diag service crashes sway ---------------------------------------------------------
if wait_serial 'VELA-SMOKE PHASE2' 600; then
  shot vm-after-crash.png checked
else
  shot vm-after-crash.png || true
  fail=1
fi
wait_serial 'VELA-SMOKE DONE' 480 || fail=1

checks=$(all_checks)
grep -q ' FAIL' <<<"$checks" && fail=1
[ "$(grep -c ' PASS' <<<"$checks")" -ge "$EXPECTED_PASS" ] || { echo "expected $EXPECTED_PASS passing CHECK lines" >&2; fail=1; }
finish
