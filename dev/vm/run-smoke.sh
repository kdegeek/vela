#!/bin/bash
# Boots the smoke-test qcow2 (dev/vm/build-qcow2.sh) at 3840x2160 and checks it:
#   - /api/health answers within 6 minutes and /api/home lists 6 apps (through QEMU's port forward)
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
# Everything lands in <artifact-dir>: the PNGs, serial.log and diag.txt.
#
# Usage: run-smoke.sh <disk.qcow2> <artifact-dir>
# Needs qemu-system-x86_64 with KVM, OVMF, curl, jq, python3-numpy and python3-pil.
set -euo pipefail
disk="$1" art="$2"
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$art"
art=$(cd "$art" && pwd)
work=$(mktemp -d)
serial="$art/serial.log"
qmp="$work/qmp.sock"
fail=0

ovmf_code=/usr/share/OVMF/OVMF_CODE_4M.fd
cp /usr/share/OVMF/OVMF_VARS_4M.fd "$work/vars.fd"

# UEFI like the Mac mini. The disk is used as a snapshot so the qcow2 stays pristine.
qemu-system-x86_64 -name vela-smoke -machine q35 -enable-kvm -cpu host -m 6G -smp 4 \
  -drive "if=pflash,format=raw,readonly=on,file=$ovmf_code" \
  -drive "if=pflash,format=raw,file=$work/vars.fd" \
  -drive "file=$disk,if=virtio,format=qcow2" -snapshot \
  -device virtio-vga,xres=3840,yres=2160 \
  -nic user,model=virtio-net-pci,hostfwd=tcp::7300-:7300 \
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
finish() {
  sed -n '/VELA-SMOKE DIAG BEGIN/,/VELA-SMOKE DIAG END/p' "$serial" | tr -d '\r' > "$art/diag.txt" || true
  echo "--- hw-check output (failures are expected in a VM)"
  sed -n '/^################ hw-check output/,$p' "$art/diag.txt" | sed -n '2,60p' || true
  echo "--- CHECK lines"
  grep -a 'CHECK ' "$serial" | tr -d '\r' | sort -u || true
  if [ "$fail" != 0 ]; then
    echo "--- serial tail"
    tail -n 80 "$serial" | tr -d '\r' || true
    echo "VM smoke test FAILED" >&2
    exit 1
  fi
  echo "VM smoke test passed"
  exit 0
}

# --- daemon over the port forward ----------------------------------------------------------------
deadline=$((SECONDS + 360))
until curl -fsS -m 5 http://127.0.0.1:7300/api/health; do
  if ! alive || [ "$SECONDS" -ge "$deadline" ]; then
    echo "/api/health did not answer within 6 minutes" >&2
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

checks=$(grep -a 'CHECK ' "$serial" | tr -d '\r' | sort -u)
grep -q ' FAIL' <<<"$checks" && fail=1
[ "$(grep -c ' PASS' <<<"$checks")" -ge 21 ] || { echo "expected 21 passing CHECK lines" >&2; fail=1; }
finish
