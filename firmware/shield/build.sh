#!/usr/bin/env bash
# Build only. Device preparation/flashing is deliberately a separate operation.
set -eo pipefail
if [[ $# -lt 5 || $# -gt 6 ]]; then
    echo "Usage: $0 LINEAGE_TREE FIRMWARE_APK AAPT2 SOURCE_SNAPSHOT BUNDLE_OUTPUT [TARGET_FILES_ZIP]" >&2
    exit 2
fi
script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
tree="$(cd -- "$1" && pwd)"
apk="$2"
aapt="$3"
snapshot="$4"
bundle_output="$5"
target_files="${6:-}"
build_jobs="${VELA_BUILD_JOBS:-8}"
if ! [[ "$build_jobs" =~ ^[1-9][0-9]*$ ]]; then
    echo "VELA_BUILD_JOBS must be a positive integer" >&2
    exit 2
fi
python3 "$script_dir/vela_shield.py" doctor --workspace "$tree" --phase build
python3 "$script_dir/vela_shield.py" stage --workspace "$tree" --apk "$apk" --aapt2 "$aapt" --snapshot "$snapshot"
# Resolve paths before envsetup changes the shell's working directory.
snapshot="$(cd -- "$snapshot" && pwd)"
bundle_output="$(python3 -c 'import os,sys; print(os.path.abspath(sys.argv[1]))' "$bundle_output")"
if [[ -n "$target_files" ]]; then
    target_files="$(python3 -c 'import os,sys; print(os.path.abspath(sys.argv[1]))' "$target_files")"
fi
cd -- "$tree"
# The source file belongs to the separately synced Android tree.
# shellcheck source=/dev/null
source build/envsetup.sh
export WITH_GMS=false
breakfast sif userdebug
# Upstream's manifest target has no source dependencies. Refresh it explicitly
# so a rebuilt archive cannot retain an older source-revision manifest.
manifest_etc="$(get_build_var TARGET_OUT_ETC)"
if [[ -z "$manifest_etc" ]]; then
    echo "TARGET_OUT_ETC was not resolved; source provenance cannot be embedded." >&2
    exit 2
fi
mkdir -p "$manifest_etc"
repo manifest -r -o "$manifest_etc/build-manifest.xml"
m -j"$build_jobs" target-files-package otatools
if [[ -z "$target_files" ]]; then
    product_out="$(get_build_var PRODUCT_OUT)"
    candidates=()
    while IFS= read -r file; do candidates+=("$file"); done < <(
        find "$product_out/obj/PACKAGING/target_files_intermediates" -maxdepth 1 -type f -name '*.zip' -print
    )
    if [[ ${#candidates[@]} -ne 1 ]]; then
        echo "Expected one target-files archive; found ${#candidates[@]}. Pass its exact path as argument 6." >&2
        exit 2
    fi
    target_files="${candidates[0]}"
fi
python3 "$script_dir/vela_shield.py" bundle --workspace "$tree" \
    --target-files "$target_files" --snapshot "$snapshot" --output "$bundle_output"
