#!/bin/bash
# Replaces the stock Fedora kernel with the pinned t2linux kernel and adds the T2 support
# packages, following https://wiki.t2linux.org/distributions/fedora/installation/ and the
# way kansei-os/t2-atomic swaps the kernel in a bootc image. Usage: t2-kernel.sh <version-release>
set -euxo pipefail
t2="$1"

dnf -y install dnf5-plugins
dnf -y copr enable sharpenedblade/t2linux

# kernel-install must use the ostree layout (modules dir only, nothing in /boot).
grep -qs '^layout=ostree' /usr/lib/kernel/install.conf || echo layout=ostree >> /usr/lib/kernel/install.conf

# Drop the stock kernel packages; their scripts would only touch the initramfs we delete anyway.
mapfile -t stock < <(rpm -qa --qf '%{NAME}\n' | grep -E '^kernel(-core|-modules|-modules-core|-modules-extra)?$' | sort -u)
echo "stock kernel: $(rpm -q kernel-core)"
rpm -e --nodeps --noscripts "${stock[@]}"
rm -rf /usr/lib/modules/*

dnf -y install \
  "kernel-$t2" "kernel-core-$t2" "kernel-modules-$t2" "kernel-modules-core-$t2" "kernel-modules-extra-$t2"

# Fan daemon, module/udev/NetworkManager config, and the T2 audio profiles (wiki: "headless setups").
dnf -y install t2fanrd t2linux-config t2linux-audio t2linux-scripts
dnf -y copr disable sharpenedblade/t2linux

kver=$(rpm -q --qf '%{VERSION}-%{RELEASE}.%{ARCH}\n' kernel-core)
[ "$kver" = "$t2.x86_64" ] || { echo "expected kernel $t2, got $kver" >&2; exit 1; }
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
