#!/bin/bash
# Vela hardware check: run on the Mac mini, as the vela user, with the TV on and the kiosk showing.
# Prints PASS/FAIL/WARN per check and writes ~/vela-report-<timestamp>.tar.gz.
#   vela-ctl acceptance hw-check
set -u
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib.sh
. "$here/lib.sh"
begin hw-check

STATE=${VELA_STATE_DIR:-/var/lib/vela}
FLATPAKS_LIST=${VELA_FLATPAKS_LIST:-/usr/share/vela/flatpaks.list}

# --- kernel and storage ----------------------------------------------------------------------------
module_loaded() { [ -d "/sys/module/$1" ] || lsmod | grep -q "^$1 "; }
check "kernel module t2bce_core loaded (T2 bridge)" module_loaded t2bce_core
if module_loaded t2bce_vhci; then info "t2bce_vhci is loaded (T2 keyboard and trackpad bridge)"; else info "t2bce_vhci is not loaded"; fi
kernel_matches() {
  local want
  want=$(sed -n 's/^VELA_KERNEL=//p' /usr/lib/os-release | tr -d '"')
  [ -n "$want" ] || { echo "VELA_KERNEL is missing from /usr/lib/os-release"; return 1; }
  case "$(uname -r)" in
    "$want"*) ;;
    *) echo "image expects kernel $want but the running kernel is $(uname -r)"; return 1 ;;
  esac
}
check "running kernel matches the image's VELA_KERNEL" kernel_matches
nvme_present() { compgen -G '/sys/class/nvme/nvme*' >/dev/null || { echo "no /sys/class/nvme/nvme*; the T2 NVMe is not visible"; return 1; }; }
check "internal NVMe present" nvme_present

# --- display ---------------------------------------------------------------------------------------
output_4k60() {
  local out
  out=$(swaymsg -t get_outputs -r) || return 1
  printf '%s' "$out" | jq -e '[.[] | select(.active) | .current_mode | select(.width == 3840 and .height == 2160 and .refresh >= 59900)] | length > 0' >/dev/null && return 0
  printf '%s' "$out" | jq -r '.[] | "output \(.name): \(.current_mode.width)x\(.current_mode.height)@\(.current_mode.refresh / 1000) Hz"' >&2
  return 1
}
check "output is 3840x2160 at 59.9 Hz or more" output_4k60

# --- VA-API -----------------------------------------------------------------------------------------
vainfo_out=$(mktemp)
vainfo >"$vainfo_out" 2>&1 || vainfo --display drm >"$vainfo_out" 2>&1
has_profile() { grep -q "$1" "$vainfo_out" || { echo "$1 not listed by vainfo"; tail -n 3 "$vainfo_out"; return 1; }; }
check "vainfo lists VAProfileHEVCMain10" has_profile VAProfileHEVCMain10
check "vainfo lists VAProfileVP9Profile2" has_profile VAProfileVP9Profile2
cp "$vainfo_out" "$(report_dir)/vainfo.txt"
rm -f "$vainfo_out"

# --- USB, network ----------------------------------------------------------------------------------
flirc_present() { lsusb | grep -qi ' ID 20a0:' || { echo "no USB device with vendor 20a0 (Flirc); replug it"; return 1; }; }
check "Flirc receiver present (USB 20a0)" flirc_present
ethernet_up() {
  local n
  for n in /sys/class/net/*; do
    case "${n##*/}" in lo|wl*|veth*|virbr*|docker*|podman*|cni*|br-*) continue ;; esac
    [ -d "$n/device" ] || continue
    [ "$(cat "$n/operstate" 2>/dev/null)" = up ] && { echo "${n##*/} is up"; return 0; }
  done
  echo "no wired interface has a link (interfaces: $(basename -a /sys/class/net/* | tr '\n' ' '))"
  return 1
}
check "Ethernet link up" ethernet_up
wifi_present() { compgen -G '/sys/class/net/*/wireless' >/dev/null || compgen -G '/sys/class/net/wl*' >/dev/null; }
if wifi_present; then pass "Wi-Fi interface present"; else warn "Wi-Fi interface" "none found; Wi-Fi is optional. Needs the firmware vela-wifi-firmware.service downloads to $STATE/firmware (journalctl -u vela-wifi-firmware)."; fi
if [ ! -d "$STATE/firmware" ]; then
  warn "firmware directory" "$STATE/firmware is missing; Wi-Fi and Bluetooth stay off (optional)"
elif [ -z "$(ls -A "$STATE/firmware" 2>/dev/null)" ]; then
  warn "firmware directory" "$STATE/firmware is empty; Wi-Fi and Bluetooth stay off (optional)"
else
  pass "firmware directory $STATE/firmware has files"
fi

# --- audio -----------------------------------------------------------------------------------------
sinks=$(wpctl status 2>&1 | sed -n '/Sinks:/,/Sources:/p')
printf '%s\n' "$sinks" > "$(report_dir)/audio-sinks.txt"
has_sink() { printf '%s\n' "$sinks" | grep -Eqi "$1" || { echo "no $2 sink in wpctl status; sinks seen:"; printf '%s\n' "$sinks" | grep -E '[0-9]+\.' | head -n 6; return 1; }; }
check "HDMI audio sink exists" has_sink 'hdmi' HDMI
check "headphone-jack sink exists" has_sink 'headphone|analog|line.?out' headphone

# --- session ---------------------------------------------------------------------------------------
check "vela-daemon answers on 127.0.0.1:7300" api_get /api/health
check "vela-daemon and vela-kiosk units active" systemctl --user is-active --quiet vela-daemon.service vela-kiosk.service
check "graphical-session.target active" systemctl --user is-active --quiet graphical-session.target
portal_answers() {
  busctl --user --timeout=30 call org.freedesktop.portal.Desktop /org/freedesktop/portal/desktop \
    org.freedesktop.DBus.Properties Get ss org.freedesktop.portal.Settings version
}
check "xdg-desktop-portal answers" portal_answers
kiosk_on_home() {
  swaymsg -t get_tree -r | jq -e '
    [recurse(.nodes[]?, .floating_nodes[]?) | select(.type == "workspace" and .name == "home")
     | recurse(.nodes[]?, .floating_nodes[]?)
     | select((.app_id // "") | test("^(chrome-127\\.0\\.0\\.1|chromium)"))] | length > 0' >/dev/null \
    || { echo "no kiosk Chromium window on workspace home"; return 1; }
}
check "kiosk window on workspace home" kiosk_on_home

# --- Flatpaks --------------------------------------------------------------------------------------
if [ -e "$STATE/flatpaks.done" ]; then
  pass "Flatpaks installed ($STATE/flatpaks.done exists)"
elif [ -r "$FLATPAKS_LIST" ]; then
  while read -r id _; do
    case "$id" in ''|'#'*) continue ;; esac
    if flatpak info --system "$id" >/dev/null 2>&1; then pass "Flatpak $id installed"; else warn "Flatpak $id" "not installed yet; the first-boot install needs network and may still be running (journalctl -u vela-flatpaks)"; fi
  done < "$FLATPAKS_LIST"
else
  warn "Flatpaks" "no $STATE/flatpaks.done and no $FLATPAKS_LIST to check"
fi

screenshot hw-check-screen >/dev/null 2>&1 || warn "screenshot" "grim could not capture the screen"
finish
