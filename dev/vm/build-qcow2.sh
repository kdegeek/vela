#!/bin/bash
# Builds the VM smoke-test disk: the Vela image plus dev/vm/test-image (no vela.toml, so it boots
# into phone setup; software rendering; the diag service), turned into a qcow2 by bootc-image-builder.
# Needs rootful podman (bootc-image-builder reads /var/lib/containers/storage).
#
# Usage: build-qcow2.sh <vela-image-ref> <out-dir>     -> <out-dir>/qcow2/disk.qcow2
set -euo pipefail
base="$1" out="$2"
here=$(cd "$(dirname "$0")" && pwd)
# bootc-image-builder "latest" as of 2026-10-26; bump deliberately.
BIB=quay.io/centos-bootc/bootc-image-builder@sha256:b858892139e0779d48869a4dd8cbbc4e257da49f5a4b502824a55d221ed65a55
test_image=localhost/vela-vmtest:latest

sudo podman build --build-arg BASE="$base" -t "$test_image" "$here/test-image"

mkdir -p "$out"
cat > "$out/bib-config.toml" <<'EOF'
[[customizations.filesystem]]
mountpoint = "/"
minsize = "20 GiB"
EOF

# fedora-bootc does not name a default root filesystem, so --rootfs is required.
sudo podman run --rm --privileged \
  --security-opt label=type:unconfined_t \
  -v "$out/bib-config.toml:/config.toml:ro" \
  -v "$out:/output" \
  -v /var/lib/containers/storage:/var/lib/containers/storage \
  "$BIB" --type qcow2 --rootfs xfs "$test_image"

sudo chown -R "$(id -u):$(id -g)" "$out"
ls -l "$out/qcow2/disk.qcow2"
