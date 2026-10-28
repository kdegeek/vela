#!/bin/bash
# Enables services, sets the Plymouth theme, rebuilds the initramfs for the t2linux kernel,
# and checks the result. Runs after os/files/ is copied in.
set -euxo pipefail

# --- services ---
systemctl set-default graphical.target
systemctl enable greetd.service vela-flatpaks.service podman-auto-update.timer t2fanrd.service
systemctl --global enable vela-daemon.service
# No text console: greetd owns tty1 and nothing spawns gettys on the others.
systemctl mask getty@tty1.service autovt@.service
# get-apple-firmware copies blobs from a macOS partition into /usr/lib/firmware; the disk is wiped
# and /usr is read-only. Firmware goes to /var/lib/vela/firmware instead (kargs, Task 11).
systemctl disable get-apple-firmware.service || true
systemctl is-enabled greetd.service vela-flatpaks.service podman-auto-update.timer t2fanrd.service
systemctl --global is-enabled vela-daemon.service
systemctl is-enabled get-apple-firmware.service || true

# Home Assistant quadlet must translate into a unit.
/usr/lib/systemd/system-generators/podman-system-generator --dryrun 2>&1 | grep -A3 '^---homeassistant.service---'

# --- Plymouth, then one initramfs build that includes the theme ---
plymouth-set-default-theme vela
grep -A2 '^\[Daemon\]' /etc/plymouth/plymouthd.conf

kver=$(rpm -q --qf '%{VERSION}-%{RELEASE}.%{ARCH}\n' kernel-core)
export DRACUT_NO_XATTR=1
dracut --no-hostonly --kver "$kver" --reproducible -f "/usr/lib/modules/$kver/initramfs.img"
lsinitrd "/usr/lib/modules/$kver/initramfs.img" > /tmp/initrd.txt
grep -q 'ostree-prepare-root' /tmp/initrd.txt || { echo "initramfs lacks ostree-prepare-root" >&2; exit 1; }
grep -q 'plymouth/themes/vela/wordmark.png' /tmp/initrd.txt || { echo "initramfs lacks the vela Plymouth theme" >&2; exit 1; }
grep -q 'drm/i915/i915.ko' /tmp/initrd.txt || { echo "initramfs lacks i915 (early KMS for the splash)" >&2; exit 1; }
grep 't2bce' /tmp/initrd.txt || echo "note: t2bce modules are not in the initramfs (loaded from the root fs)"

# --- the kiosk and the daemon ---
command -v chromium-browser || command -v chromium
command -v swaymsg greetd sway Xwayland mpv ir-ctl wtype flatpak node curl jq vela-ctl
/ctx/smoke-daemon.sh /usr/bin/node /usr/lib/vela/daemon /usr/share/vela/assets /usr/share/vela/apps /usr/lib/vela/launcher

# --- record what we built ---
cat /etc/os-release
rpm -q kernel-core t2fanrd t2linux-config t2linux-audio t2linux-scripts sway greetd chromium mpv nodejs ffmpeg intel-media-driver
du -sh /usr/share/vela/irdb /usr/lib/vela
