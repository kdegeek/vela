#!/usr/bin/env bash
# Complete removable-media image build; never writes to a card or physical disk.
set -euo pipefail
if (( $# != 4 )); then
    echo "Usage: $0 SOURCE SNAPSHOT FIRMWARE_APK EVIDENCE_DIRECTORY" >&2
    exit 2
fi
tool_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source_dir=$(realpath -- "$1")
snapshot=$(realpath -m -- "$2")
apk=$(realpath -- "$3")
evidence=$(realpath -m -- "$4")
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]]
[[ -d $source_dir/.repo && $source_dir != "$tool_dir" ]]
for destination in "$snapshot" "$evidence"; do
    if [[ $destination == "$source_dir" || $destination == "$source_dir/"* ]]; then
        echo 'Snapshots and evidence must be outside the Android source tree.' >&2
        exit 2
    fi
done
export VELA_BUILD_JOBS=4 GOMAXPROCS=4 NINJA_HIGHMEM_NUM_JOBS=1
export VELA_ANDROID_SOURCE=$source_dir
mkdir -p "$evidence"
for executable in repo git python3 make sfdisk fdisk kpartx mkfs.ext4 mkfs.vfat mcopy debugfs rsync fallocate dd losetup; do
    command -v "$executable" >/dev/null
done
sudo -n true
free_bytes=$(df -B1 --output=avail "$source_dir" | tail -1 | tr -d ' ')
if (( free_bytes < 107374182400 )); then
    echo "Insufficient compilation margin: need 100 GiB free; found $free_bytes bytes" >&2
    exit 75
fi
if [[ ! -e $snapshot ]]; then
    python3 "$tool_dir/vela_pi5.py" freeze --source "$source_dir" --snapshot "$snapshot" > "$evidence/freeze.json"
elif [[ ! -f $snapshot/manifest.xml || ! -f $snapshot/source-lock.json ]]; then
    echo 'Incomplete source snapshot: preserve it and choose a new snapshot path.' >&2
    exit 1
fi
sdk_tools=$source_dir/prebuilts/sdk/tools/linux/bin
python3 "$tool_dir/vela_pi5.py" stage --source "$source_dir" --snapshot "$snapshot" \
    --apk "$apk" --aapt2 "$sdk_tools/aapt2" --zipalign "$sdk_tools/zipalign" --display 4k > "$evidence/staging.json"
cp "$source_dir/vendor/vela/rpi5/vela-staging.json" "$evidence/image-inputs.json"
cd "$source_dir"
# AOSP's environment functions, including make's post-build logger, reference
# optional unset variables. Keep nounset disabled throughout those calls.
set +u
# shellcheck disable=SC1091
source build/envsetup.sh
lunch vela_rpi5_tv-cp2a-userdebug
[[ $TARGET_PRODUCT == vela_rpi5_tv ]]
[[ $(realpath -m -- "$ANDROID_PRODUCT_OUT") == "$source_dir/out/target/product/rpi5" ]]
make bootimage systemimage vendorimage -j4
set -u
# Upstream uses image-backed loop devices inside this VM. -e/pipefail prevents
# its final echo from masking a failed partition copy or format operation.
# Keep every upstream sudo call non-interactive throughout a long headless build.
# shellcheck disable=SC2329 # Invoked by the upstream child bash process.
sudo() { command sudo -n "$@"; }
export -f sudo
bash -e -o pipefail ./rpi5-mkimg.sh
unset -f sudo
shopt -s nullglob
images=("$ANDROID_PRODUCT_OUT"/RaspberryVanillaAOSP17-*-vela_rpi5_tv.img)
if (( ${#images[@]} != 1 )); then
    echo 'Expected exactly one complete Pi 5 image; preserve prior outputs separately.' >&2
    exit 1
fi
python3 "$tool_dir/verify_image.py" --image "${images[0]}" --input-apk "$apk" \
    --expected-marker "$evidence/image-inputs.json" --apksigner "$tool_dir/apksigner.sh" \
    --output "$evidence/image-verification.json"
sha256sum "${images[0]}" > "$evidence/image-SHA256SUMS"
printf '%s\n' "${images[0]}" > "$evidence/image-path.txt"
printf 'BUILD_VERIFIED %s\n' "$(date -u +%FT%TZ)"
