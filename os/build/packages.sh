#!/bin/bash
# RPM Fusion codecs and Intel VA-API, then everything the kiosk session needs.
set -euxo pipefail
fedora=$(rpm -E %fedora)

dnf -y install \
  "https://mirrors.rpmfusion.org/free/fedora/rpmfusion-free-release-$fedora.noarch.rpm" \
  "https://mirrors.rpmfusion.org/nonfree/fedora/rpmfusion-nonfree-release-$fedora.noarch.rpm"

# Brief list, plus what it implies on a minimal bootc base: an audio server for the session
# (t2linux-audio is a PipeWire config), XWayland for Steam, the Plymouth script plugin for the
# splash, portals for Flatpak apps, greetd's SELinux policy, and Wi-Fi support in NetworkManager.
dnf -y install \
  sway swaybg greetd greetd-selinux chromium mpv wtype v4l-utils pipewire-utils alsa-utils \
  podman flatpak nodejs plymouth jq curl \
  plymouth-plugin-script xorg-x11-server-Xwayland \
  pipewire wireplumber pipewire-pulseaudio pipewire-alsa \
  xdg-desktop-portal-wlr xdg-desktop-portal-gtk \
  NetworkManager-wifi

# Full ffmpeg instead of the patent-trimmed build, and Intel's VA-API driver (iHD) for UHD 630.
if rpm -q ffmpeg-free >/dev/null; then
  dnf -y swap ffmpeg-free ffmpeg --allowerasing
else
  dnf -y install --allowerasing ffmpeg
fi
dnf -y install intel-media-driver libva-utils

rpm -q sway greetd chromium mpv nodejs ffmpeg intel-media-driver plymouth
node --version
dnf clean all
