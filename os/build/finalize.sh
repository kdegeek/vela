#!/bin/bash
# Enables services, sets the Plymouth theme, rebuilds the initramfs for the t2linux kernel,
# and checks the result. Runs after os/files/ is copied in.
set -euxo pipefail

# --- services ---
systemctl set-default graphical.target
systemctl enable greetd.service vela-flatpaks.service vela-wifi-firmware.service podman-auto-update.timer t2fanrd.service
# Phone setup: the gate opens it on a box without vela.toml; the path unit installs what it staged.
systemctl enable vela-setup-gate.service vela-setup-commit.path
systemctl --global enable vela-daemon.service vela-kiosk.service
# No text console: greetd owns tty1 and nothing spawns gettys on the others.
systemctl mask getty@tty1.service autovt@.service
# get-apple-firmware copies blobs from a macOS partition into /usr/lib/firmware; the disk is wiped
# and /usr is read-only. vela-wifi-firmware.service downloads it to /var/lib/vela/firmware instead
# (firmware_class.path in kargs.d).
systemctl disable get-apple-firmware.service || true
systemctl is-enabled greetd.service vela-flatpaks.service vela-wifi-firmware.service podman-auto-update.timer t2fanrd.service
systemctl is-enabled vela-setup-gate.service vela-setup-commit.path
# Started by setup-gate open (no [Install]); it closes setup after 2 hours.
grep -qx 'Unit=vela-setup-expire.service' /usr/lib/systemd/system/vela-setup-expire.timer
grep -qx 'ExecStart=/usr/libexec/vela/setup-gate expire' /usr/lib/systemd/system/vela-setup-expire.service
grep -qx 'ExecStart=/usr/libexec/vela/setup-gate open' /usr/lib/systemd/system/vela-setup-gate.service
systemctl --global is-enabled vela-daemon.service vela-kiosk.service
# Both must be pulled in by the session target that session-start restarts.
test -L /etc/systemd/user/vela-session.target.wants/vela-daemon.service
test -L /etc/systemd/user/vela-session.target.wants/vela-kiosk.service
systemctl is-enabled get-apple-firmware.service || true
# A config typo or crash loop must never park the session units in "start limit hit".
grep -qx 'StartLimitIntervalSec=0' /usr/lib/systemd/user/vela-daemon.service
grep -qx 'StartLimitIntervalSec=0' /usr/lib/systemd/user/vela-kiosk.service
# Session audio: the PipeWire user units (normally enabled by package presets; make sure).
systemctl --global enable pipewire.socket pipewire-pulse.socket wireplumber.service

# SSH is the only way to edit vela.toml and secrets.json later (docs/install.md): always on.
systemctl enable sshd.service
systemctl is-enabled sshd.service

# Automatic updates: fedora-bootc's timer runs `bootc upgrade --apply`, which reboots the TV box
# whenever an update lands. Vela stages only, at night (drop-ins in os/files), and the image
# boots at the next restart. Print what the base image shipped, for the build log.
echo "bootc-fetch-apply-updates.timer as shipped: $(systemctl is-enabled bootc-fetch-apply-updates.timer 2>&1 || true)"
cat /usr/lib/systemd/system/bootc-fetch-apply-updates.service /usr/lib/systemd/system/bootc-fetch-apply-updates.timer
systemctl enable bootc-fetch-apply-updates.timer
systemctl is-enabled bootc-fetch-apply-updates.timer
upd=/usr/lib/systemd/system/bootc-fetch-apply-updates.service.d/10-vela.conf
grep -qx 'ExecStart=' "$upd"
grep -qx 'ExecStart=/usr/bin/bootc upgrade --quiet' "$upd"
if grep -v '^#' "$upd" | grep -q -- '--apply'; then echo "$upd must not apply updates" >&2; exit 1; fi
grep -qx 'OnCalendar=\*-\*-\* 03:00' /usr/lib/systemd/system/bootc-fetch-apply-updates.timer.d/10-vela.conf

# Home Assistant quadlet must translate into a unit.
/usr/lib/systemd/system-generators/podman-system-generator --dryrun 2>&1 | grep -A3 '^---homeassistant.service---'

# --- Plymouth, then one initramfs build that includes the theme ---
plymouth-set-default-theme vela
grep -A2 '^\[Daemon\]' /etc/plymouth/plymouthd.conf

kver=$(rpm -q --qf '%{VERSION}-%{RELEASE}.%{ARCH}\n' kernel-core)
export DRACUT_NO_XATTR=1
# /var is a tmpfs during the build; dracut needs /var/tmp and the target of /root.
mkdir -p /var/tmp /var/roothome
dracut --no-hostonly --kver "$kver" --reproducible -f "/usr/lib/modules/$kver/initramfs.img" 2>&1 | tee /tmp/dracut.log
# dracut exits 0 even when dracut-install fails on an item.
if grep -F 'dracut[E]: FAILED' /tmp/dracut.log; then exit 1; fi
lsinitrd "/usr/lib/modules/$kver/initramfs.img" > /tmp/initrd.txt
grep -q 'ostree-prepare-root' /tmp/initrd.txt || { echo "initramfs lacks ostree-prepare-root" >&2; exit 1; }
grep -q 'plymouth/themes/vela/wordmark.png' /tmp/initrd.txt || { echo "initramfs lacks the vela Plymouth theme" >&2; exit 1; }
grep -q 'drm/i915/i915.ko' /tmp/initrd.txt || { echo "initramfs lacks i915 (early KMS for the splash)" >&2; exit 1; }
grep 't2bce' /tmp/initrd.txt || echo "note: t2bce modules are not in the initramfs (loaded from the root fs)"

# --- the kiosk and the daemon ---
command -v chromium-browser || command -v chromium
grep -qx "VELA_KERNEL=$(rpm -q --qf '%{VERSION}-%{RELEASE}' kernel-core)" /usr/lib/os-release
test -x /usr/libexec/vela/session-start -a -x /usr/libexec/vela/launcher-kiosk -a -x /usr/libexec/vela/install-flatpaks -a -x /usr/libexec/vela/fetch-wifi-firmware -a -x /usr/libexec/vela/setup-commit -a -x /usr/libexec/vela/setup-gate
# setup-commit validates the staged vela.toml with it; without it every setup is refused.
test -r /usr/lib/vela/daemon/dist/check-config.js
command -v swaymsg greetd sway Xwayland mpv ir-ctl wtype flatpak node curl jq zstd sha256sum vela-ctl grim lsusb vainfo wpctl aplay busctl pw-play pw-dump
test -x /usr/libexec/vela/acceptance/hw-check.sh -a -r /usr/libexec/vela/acceptance/lib.sh
/ctx/smoke-daemon.sh /usr/bin/node /usr/lib/vela/daemon /usr/share/vela/assets /usr/share/vela/apps /usr/lib/vela/launcher

grep -qx 'title=Vela player' /usr/share/vela/mpv/mpv.conf

# --- record what we built ---
cat /etc/os-release
rpm -q kernel-core t2fanrd t2linux-config t2linux-audio t2linux-scripts sway greetd chromium mpv ffmpeg intel-media-driver
rpm -q --whatprovides nodejs
du -sh /usr/share/vela/irdb /usr/lib/vela
