#!/bin/bash
# Keeps a copy of the pinned t2linux kernel RPMs in a GitHub release (tag t2-kernel-<version>),
# because the COPR prunes old builds. Idempotent: does nothing when the release is complete.
# Needs GH_TOKEN with contents: write. Run from the repository root.
set -euo pipefail
vr=$(sed -n 's/^ARG T2_KERNEL=//p' os/Containerfile)
[ -n "$vr" ] || { echo "no ARG T2_KERNEL in os/Containerfile" >&2; exit 1; }
fedora=${vr##*.fc}
tag="t2-kernel-$vr"

have=$(gh release view "$tag" --json assets --jq '[.assets[].name | select(endswith(".rpm") or . == "SHA256SUMS")] | length' 2>/dev/null || echo 0)
if [ "$have" = 6 ]; then
  echo "release $tag already has the 5 kernel RPMs and SHA256SUMS"
  exit 0
fi

dir=$(mktemp -d)
python3 .github/scripts/t2-copr-kernels.py "$fedora" "$vr" > "$dir/list" || {
  echo "::error::Pinned kernel $vr is not in the COPR and release $tag is incomplete; pick a kernel the COPR still has." >&2
  exit 1
}
while read -r url sum; do
  f="$dir/$(basename "$url")"
  curl -fsSL --retry 3 -o "$f" "$url"
  echo "$sum  $f" | sha256sum -c -
done < "$dir/list"
(cd "$dir" && sha256sum ./*.rpm | sed 's#  \./#  #' > SHA256SUMS && cat SHA256SUMS)

if ! gh release view "$tag" >/dev/null 2>&1; then
  gh release create "$tag" --prerelease --latest=false --title "t2linux kernel $vr (mirror)" \
    --notes "Mirror of the pinned t2linux kernel RPMs from https://copr.fedorainfracloud.org/coprs/sharpenedblade/t2linux/ (signed by the COPR key). os/Containerfile installs from the COPR and falls back to these files."
fi
gh release upload "$tag" --clobber "$dir"/*.rpm "$dir/SHA256SUMS"
echo "mirrored $vr to release $tag"
