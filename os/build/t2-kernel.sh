#!/bin/bash
# Replaces the stock Fedora kernel with the pinned t2linux kernel and adds the T2 support
# packages, following https://wiki.t2linux.org/distributions/fedora/installation/ and the
# way kansei-os/t2-atomic swaps the kernel in a bootc image.
# Usage: t2-kernel.sh <version-release> <mirror-base-url> <auto|mirror>
#   auto:   install the pinned kernel from the COPR; if the COPR no longer has it (it prunes old
#           builds), use the copy in the GitHub release t2-kernel-<version-release>.
#   mirror: go straight to the release copy (to test the fallback, or when the COPR is down).
# Either way the RPMs must carry a valid signature from the COPR's key.
set -euxo pipefail
t2="$1"
mirror="$2"
source="${3:-auto}"
pkgs=(kernel kernel-core kernel-modules kernel-modules-core kernel-modules-extra)

dnf -y install dnf5-plugins
dnf -y copr enable sharpenedblade/t2linux

# kernel-install must use the ostree layout (modules dir only, nothing in /boot).
grep -qs '^layout=ostree' /usr/lib/kernel/install.conf || echo layout=ostree >> /usr/lib/kernel/install.conf

# Drop the stock kernel packages; their scripts would only touch the initramfs we delete anyway.
mapfile -t stock < <(rpm -qa --qf '%{NAME}\n' | grep -E '^kernel(-core|-modules|-modules-core|-modules-extra)?$' | sort -u)
echo "stock kernel: $(rpm -q kernel-core)"
rpm -e --nodeps --noscripts "${stock[@]}"
rm -rf /usr/lib/modules/*

install_from_mirror() {
  local dir=/tmp/t2-kernel
  mkdir -p "$dir"
  curl -fsSL --retry 3 -o "$dir/SHA256SUMS" "$mirror/t2-kernel-$t2/SHA256SUMS"
  for p in "${pkgs[@]}"; do
    curl -fsSL --retry 3 -o "$dir/$p-$t2.x86_64.rpm" "$mirror/t2-kernel-$t2/$p-$t2.x86_64.rpm"
  done
  (cd "$dir" && sha256sum -c SHA256SUMS)
  rpm --import https://download.copr.fedorainfracloud.org/results/sharpenedblade/t2linux/pubkey.gpg
  for f in "$dir"/*.rpm; do
    rpm -K "$f" | grep -q 'signatures OK' || { rpm -Kv "$f"; echo "bad or missing COPR signature: $f" >&2; exit 1; }
  done
  dnf -y install "$dir"/*.rpm
  echo "kernel source: mirror release t2-kernel-$t2"
}
if [ "$source" = mirror ]; then
  install_from_mirror
elif dnf -y install "${pkgs[@]/%/-$t2}"; then
  echo "kernel source: COPR"
else
  echo "pinned kernel $t2 is not installable from the COPR; falling back to the mirror release"
  install_from_mirror
fi

# Fan daemon, module/udev/NetworkManager config, and the T2 audio profiles (wiki: "headless setups").
dnf -y install t2fanrd t2linux-config t2linux-audio t2linux-scripts
dnf -y copr disable sharpenedblade/t2linux

kver=$(rpm -q --qf '%{VERSION}-%{RELEASE}.%{ARCH}\n' kernel-core)
[ "$kver" = "$t2.x86_64" ] || { echo "expected kernel $t2, got $kver" >&2; exit 1; }
echo "VELA_KERNEL=$t2" >> /usr/lib/os-release
test -f "/usr/lib/modules/$kver/vmlinuz"
[ "$(ls /usr/lib/modules | wc -l)" = 1 ] || { ls -la /usr/lib/modules; echo "more than one kernel in /usr/lib/modules" >&2; exit 1; }

# The T2 bridge driver (formerly apple-bce) is split into t2bce_* modules in the 7.x t2linux kernel.
modinfo -k "$kver" t2bce_core
modinfo -k "$kver" t2bce_vhci >/dev/null && echo "t2bce_vhci ok"
modinfo -k "$kver" brcmfmac >/dev/null && echo "brcmfmac ok"
grep -iE 'apple[-_]bce' "/usr/lib/modules/$kver/modules.alias" || echo "note: no apple-bce alias in modules.alias"
modinfo -k "$kver" apple-bce || echo "note: modinfo apple-bce: not found (module is t2bce_core)"

rpm -q kernel-core kernel-modules kernel-modules-extra t2fanrd t2linux-config t2linux-audio t2linux-scripts
dnf clean all
rm -rf /run/dnf /run/selinux-policy  # runtime-only dirs dnf leaves behind (bootc lint)
