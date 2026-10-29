# Vela installer kickstart. image.yml embeds this file in os/installer/config.toml's
# [customizations.installer.kickstart] for `bootc-image-builder --type anaconda-iso`.
#
# bootc-image-builder adds the payload itself: an `ostreecontainer` command that installs the
# image copied onto the ISO (/run/install/repo/container), and a %post that runs
# `bootc switch --mutate-in-place --transport registry ghcr.io/kdegeek/vela:stable` (image.yml
# builds the ISO from that name), so updates come from GHCR's :stable tag, which only moves on
# version tags after the VM smoke test passed. Do not add ostreecontainer/ostreesetup/liveimg here.
# Source: osbuild/image-builder pkg/manifest/anaconda_installer_iso_tree.go,
# bootcInstallerKickstartStages().

# Wipe the internal SSD and install. There is no way back to macOS without reinstalling it.
zerombr
clearpart --all --initlabel --disklabel=gpt
autopart --type=plain --nohome --noswap
reboot

# With a custom kickstart, bootc-image-builder adds none of its unattended defaults, and Anaconda
# stops at its hub for anything required that is missing (the root password). Text mode, a
# locked root account (there is no console), US English and a US keyboard. No timezone line:
# Anaconda sets it from the network (geolocation), which the on-screen clock needs.
text
lang en_US.UTF-8
keyboard us
rootpw --lock

# Copy the owner's files from an optional VELA stick into the installed system (docs/install.md,
# "Advanced: preset settings on a USB stick"): Broadcom Wi-Fi/Bluetooth firmware (without it,
# vela-wifi-firmware.service downloads it on first boot), vela.toml, secrets.json, and
# authorized_keys (SSH public keys for root). None is needed: without vela.toml the first boot
# opens phone setup, which asks for the media server and the SSH keys. With vela.toml, setup
# doesn't open.
#
# --nochroot, because the USB sticks are only visible from the installer environment. Paths:
# - Install media: Anaconda mounts the ISO at /run/install/repo (the ostreecontainer URL
#   bootc-image-builder writes is under it; see above).
# - Installed system: /mnt/sysroot is the deployment root (/ of the new system), /mnt/sysimage
#   is the physical root holding /ostree. Anaconda docs/developer/mount-points.rst: "rpm-ostree
#   systems ... /mnt/sysroot is attached to a subdirectory of /mnt/sysimage". Anaconda exports it
#   to %post as ANA_INSTALL_PATH (bootc's crates/lib/src/install/completion.rs relies on this).
# - /etc: /mnt/sysroot/etc is the deployment's own /etc and persists.
# - /var: for ostree payloads Anaconda bind-mounts /mnt/sysimage/ostree/deploy/<stateroot>/var
#   onto /mnt/sysroot/var; the stateroot for `ostreecontainer` is "default"
#   (pyanaconda/modules/payloads/payload/rpm_ostree/installation.py: _get_stateroot,
#   _handle_var_mount_point). Those mounts stay up through %post: they are torn down in
#   tear_down_with_tasks, after the configuration queue that runs the %post scripts
#   (pyanaconda/modules/boss/installation.py).
# - kargs: `ostree container image deploy` runs bootc's install completion, which applies
#   /usr/lib/bootc/kargs.d (including firmware_class.path=/var/lib/vela/firmware)
#   (bootc crates/ostree-ext/src/container/deploy.rs). Checked below, because Wi-Fi depends on it.
#
# The flashed installer stick is read-only (ISO 9660), so the files go on a second
# stick (FAT32/exFAT, any name): the first of /run/install/repo or another partition that has
# vela.toml, secrets.json, authorized_keys or a vela-firmware folder wins.
#
# Ownership: /var/lib/vela/secrets.json is written root:root 0600. The vela user only exists
# after first boot (sysusers); the image's tmpfiles rule `z /var/lib/vela/secrets.json 0600 vela
# vela -` then hands it over (systemd-tmpfiles-setup runs after systemd-sysusers), and
# `d /var/lib/vela 0755 vela vela` does the same for the directory.
%post --nochroot
set -u
root="${ANA_INSTALL_PATH:-/mnt/sysroot}"
var="$root/var"
if ! mountpoint -q "$var"; then
  # Fallback: the stateroot's /var on the physical root.
  for d in /mnt/sysimage/ostree/deploy/*/var; do [ -d "$d" ] && var="$d" && break; done
fi
echo "deployment root: $root, var: $var"
mkdir -p "$var/lib/vela" "$root/etc/vela"
log="$var/lib/vela/install.log"
note() { echo "$*"; echo "$(date -u +%FT%TZ) $*" >> "$log"; }

has_files() { [ -e "$1/vela.toml" ] || [ -e "$1/secrets.json" ] || [ -d "$1/vela-firmware" ] || [ -e "$1/authorized_keys" ]; }

src=""
scan=/run/vela-scan
if has_files /run/install/repo; then
  src=/run/install/repo
else
  # Other partitions that are not mounted already (that skips the installer stick and the
  # new system's disk).
  mkdir -p "$scan"
  for dev in $(lsblk -rpno NAME,TYPE,FSTYPE | awk '$2 == "part" && $3 ~ /^(vfat|exfat|hfsplus|ext4|ntfs3?)$/ {print $1}'); do
    findmnt -rn -S "$dev" >/dev/null && continue
    mount -o ro "$dev" "$scan" 2>/dev/null || continue
    if has_files "$scan"; then src="$scan"; note "using files from $dev"; break; fi
    umount "$scan"
  done
fi

if [ -z "$src" ]; then
  note "no vela.toml, secrets.json, authorized_keys or vela-firmware/ found on any stick"
else
  note "copying from $src"
  if [ -d "$src/vela-firmware" ]; then
    fw="$var/lib/vela/firmware/brcm"
    mkdir -p "$fw"
    # The t2linux firmware script's tarball (Method 2): flat brcmfmac*/brcmbt* files that belong
    # in brcm/, the folder firmware_class.path=/var/lib/vela/firmware looks in.
    for t in "$src"/vela-firmware/*.tar; do
      [ -f "$t" ] && tar -xf "$t" -C "$fw" && note "extracted $(basename "$t")"
    done
    # Or the files themselves, loose or already in a brcm/ folder.
    [ -d "$src/vela-firmware/brcm" ] && cp -r "$src/vela-firmware/brcm/." "$fw/"
    find "$src/vela-firmware" -maxdepth 1 -type f ! -name '*.tar' -exec cp {} "$fw/" \;
    chmod -R u=rwX,go=rX "$var/lib/vela/firmware"
    note "firmware files: $(find "$fw" -type f | wc -l)"
  fi
  if [ -f "$src/vela.toml" ]; then
    install -m 0644 "$src/vela.toml" "$root/etc/vela/vela.toml" && note "installed /etc/vela/vela.toml"
  fi
  if [ -f "$src/secrets.json" ]; then
    install -m 0600 "$src/secrets.json" "$var/lib/vela/secrets.json" && note "installed /var/lib/vela/secrets.json"
  fi
  if [ -f "$src/authorized_keys" ]; then
    # /root is a symlink to /var/roothome on bootc systems.
    install -d -m 0700 "$var/roothome/.ssh"
    install -m 0600 "$src/authorized_keys" "$var/roothome/.ssh/authorized_keys" && note "installed root's SSH authorized_keys"
  fi
  [ "$src" = "$scan" ] && umount "$scan"
fi

# The normal case: phone setup on first boot adds root's SSH keys.
if [ ! -s "$var/roothome/.ssh/authorized_keys" ]; then
  note "no authorized_keys from a stick; setup on first boot will ask for SSH keys"
fi

# SELinux labels for what was just written (the installer copies with its own context).
relabel=""
for p in /etc/vela /var/lib/vela /var/roothome/.ssh; do [ -e "$root$p" ] && relabel="$relabel $p"; done
# shellcheck disable=SC2086 # one path per word
[ -n "$relabel" ] && { chroot "$root" restorecon -RF $relabel || note "restorecon failed for:$relabel"; }

# Wi-Fi depends on firmware_class.path from kargs.d; say so if it didn't make it.
if grep -qs 'firmware_class.path=/var/lib/vela/firmware' /mnt/sysimage/boot/loader/entries/*.conf; then
  note "kernel arguments include firmware_class.path"
else
  note "WARNING: firmware_class.path is not in the boot entries; Wi-Fi firmware will not load"
fi
%end
