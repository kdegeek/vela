#!/usr/bin/env bats
# os/files/usr/libexec/vela/fetch-wifi-firmware against a local package (file:// URL).

SCRIPT="$BATS_TEST_DIRNAME/../files/usr/libexec/vela/fetch-wifi-firmware"

setup() {
  T=$(mktemp -d)
  mkdir -p "$T/pkg/usr/lib/firmware/brcm"
  echo fw > "$T/pkg/usr/lib/firmware/brcm/brcmfmac4364b2-pcie.apple,test.bin"
  echo nv > "$T/pkg/usr/lib/firmware/brcm/brcmfmac4364b2-pcie.apple,test-HRPN-m.txt"
  echo .PKGINFO > "$T/pkg/.PKGINFO"
  tar --zstd -cf "$T/fw.pkg.tar.zst" -C "$T/pkg" .PKGINFO usr
  export VELA_FIRMWARE_URL="file://$T/fw.pkg.tar.zst"
  VELA_FIRMWARE_SHA256=$(sha256sum "$T/fw.pkg.tar.zst" | cut -d' ' -f1)
  export VELA_FIRMWARE_SHA256
  export VELA_FIRMWARE_DIR="$T/firmware"
  export VELA_FIRMWARE_NO_RELOAD=1
  export TMPDIR="$T"
}

teardown() { rm -rf "$T"; }

@test "downloads, checks and installs the brcm files" {
  run "$SCRIPT"
  [ "$status" -eq 0 ]
  [ -f "$T/firmware/brcm/brcmfmac4364b2-pcie.apple,test.bin" ]
  [ -f "$T/firmware/brcm/brcmfmac4364b2-pcie.apple,test-HRPN-m.txt" ]
  [ -e "$T/firmware/.fetched" ]
  [ ! -e "$T/firmware/brcm.new" ]
  [[ "$output" == *"installed 2 files"* ]]
}

@test "refuses a package whose checksum doesn't match, and installs nothing" {
  export VELA_FIRMWARE_SHA256=0000000000000000000000000000000000000000000000000000000000000000
  run "$SCRIPT"
  [ "$status" -ne 0 ]
  [[ "$output" == *"checksum mismatch"* ]]
  [ ! -e "$T/firmware/brcm" ]
  [ ! -e "$T/firmware/.fetched" ]
}

@test "fails without the marker when the download fails" {
  export VELA_FIRMWARE_URL="file://$T/missing.pkg.tar.zst"
  run "$SCRIPT"
  [ "$status" -ne 0 ]
  [ ! -e "$T/firmware/.fetched" ]
}

@test "keeps firmware from the VELA stick and downloads nothing" {
  mkdir -p "$T/firmware/brcm"
  echo mine > "$T/firmware/brcm/brcmfmac4364b2-pcie.apple,stick.bin"
  export VELA_FIRMWARE_URL="file://$T/missing.pkg.tar.zst"
  run "$SCRIPT"
  [ "$status" -eq 0 ]
  [[ "$output" == *"not downloading"* ]]
  [ -e "$T/firmware/.fetched" ]
  [ "$(cat "$T/firmware/brcm/brcmfmac4364b2-pcie.apple,stick.bin")" = mine ]
}

@test "fails when the package has no Wi-Fi firmware" {
  rm "$T/pkg/usr/lib/firmware/brcm/"brcmfmac*.bin
  tar --zstd -cf "$T/fw.pkg.tar.zst" -C "$T/pkg" .PKGINFO usr
  VELA_FIRMWARE_SHA256=$(sha256sum "$T/fw.pkg.tar.zst" | cut -d' ' -f1)
  export VELA_FIRMWARE_SHA256
  run "$SCRIPT"
  [ "$status" -ne 0 ]
  [[ "$output" == *"no brcmfmac firmware"* ]]
  [ ! -e "$T/firmware/brcm" ]
}
