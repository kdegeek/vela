# Shield Tube upstream foundation

Read date: **2026-10-31**. Scope: the owned **2019 NVIDIA Shield TV Tube, 8 GB, codename `sif`**, on LineageOS **`lineage-22.2`**. No Shield Pro purchase or target is required. This research made no device calls, source sync, blob extraction, OS build, or flash. It does **not** establish that a Vela image compiles or boots.

The adjacent [upstream-lock.json](../upstream-lock.json) is schema version 1. It locks a verified official build manifest and the complete recursively declared device dependency closure, and records binary-input gaps explicitly. Commands below describe later root implementation/build work; none were executed by this research.

## Product and build entry points

The current official device record lists `22.2`, maintainers `webgeek1234` and `npjohnson`, Tegra X1+ T210b01, 2 GB RAM, 8 GB storage, and `has_no_usb: true`. The product is `lineage_sif`, `PRODUCT_DEVICE := sif`, and inherits Android TV configuration. This is an Android OS/OTA foundation for internal storage; the microSD card carries the recovery update ZIP, rather than serving as a Linux boot disk. Sources: [device metadata](https://github.com/LineageOS/lineage_wiki/blob/aa06ba20f9afdb979b035d1a0063e0bcb17cb020/_data/devices/sif.yml), [product makefile](https://github.com/LineageOS/android_device_nvidia_sif/blob/3fae828e4211469a212429ce7774654e261a5b79/lineage_sif.mk), [base product](https://github.com/LineageOS/android_device_nvidia_sif/blob/3fae828e4211469a212429ce7774654e261a5b79/full_sif.mk).

The official moving-branch bootstrap is:

```bash
repo init -u https://github.com/LineageOS/android.git -b lineage-22.2 --git-lfs --no-clone-bundle
repo sync
source build/envsetup.sh
breakfast sif
# Populate vendor/nvidia using the device-specific route below, then repeat breakfast.
brunch sif
```

The exact explicit selection at these pins is:

```bash
source build/envsetup.sh
lunch lineage_sif-bp1a-userdebug
mka bacon
```

`breakfast sif` expands to that lunch tuple: the pinned `aosp_target_release` is `bp1a`, and its default variant is `userdebug`. `brunch sif` runs breakfast followed by `mka bacon`. Expect `out/target/product/sif/boot.img`, `recovery.img`, and `lineage-22.2-*-UNOFFICIAL-sif.zip` after a successful full build. A root-owned Vela overlay can add packages to this product, but must preserve the `sif` hardware target. Use a supported Linux x86_64 build environment; this research did not validate a native macOS/arm64 build. Sources: [official build template](https://github.com/LineageOS/lineage_wiki/blob/aa06ba20f9afdb979b035d1a0063e0bcb17cb020/_includes/templates/device_build.md), [init/sync instructions](https://github.com/LineageOS/lineage_wiki/blob/aa06ba20f9afdb979b035d1a0063e0bcb17cb020/_includes/templates/device_build_init_sync.md), [shell functions](https://github.com/LineageOS/android_vendor_lineage/blob/87f03bdeafbf22dc35130c61ae2ff97f2041b90f/build/envsetup.sh), [release selection](https://github.com/LineageOS/android_vendor_lineage/blob/87f03bdeafbf22dc35130c61ae2ff97f2041b90f/vars/aosp_target_release).

**`sif` selection alone does not restrict the installer to `sif`.** Its BoardConfig includes `foster/BoardConfig.mk`, then appends `sif` to the inherited `foster,darcy,jetson,loki,mdarcy,nx` OTA allowlist. The official ZIP directory also contains several models' firmware blobs and DTBs. The root implementation must narrow the OTA assertion after inheritance and inspect generated updater metadata, firmware packaging, and releasetools for a `sif`-only installer. Shared source dependencies are expected; they are not additional shipping targets. Do not blindly delete shared recovery DTB machinery: the upstream recovery DTImage contains `sif` board revisions and is named after `mdarcy`. Sources: [sif BoardConfig](https://github.com/LineageOS/android_device_nvidia_sif/blob/3fae828e4211469a212429ce7774654e261a5b79/BoardConfig.mk), [inherited BoardConfig](https://github.com/LineageOS/android_device_nvidia_foster/blob/666d034739cd89e5a2628129e2141927e9d65592/BoardConfig.mk), [shared releasetools](https://github.com/LineageOS/android_device_nvidia_foster/blob/666d034739cd89e5a2628129e2141927e9d65592/releasetools/releasetools.py), [recovery DTImage construction](https://github.com/LineageOS/android_device_nvidia_foster/blob/666d034739cd89e5a2628129e2141927e9d65592/mkbootimg.mk).

## Freeze the whole source tree

Pinned manifest repository: `https://github.com/LineageOS/android.git`, commit **`10ba61c98559ecc9409b39937e10f7c065631e25`**. Pinning this commit alone is a **bootstrap pin**, not a complete source lock: `default.xml` still assigns the moving `lineage-22.2` branch to many projects, includes other manifest snippets, and `breakfast`/roomservice normally discovers more projects from dependency files. Source: [pinned default.xml](https://github.com/LineageOS/android/blob/10ba61c98559ecc9409b39937e10f7c065631e25/default.xml).

The official 2026-10-30 build publishes [build-manifest.xml](https://mirrorbits.lineageos.org/full/sif/20261030/build-manifest.xml), **290869 bytes**, SHA-256 **`bd859b78d00048f4e41555e63112421ab293adbb0d4a667e9040a5876dbd6390`**. I downloaded only this metadata file and verified that hash against the official API. All **1,171** project entries have explicit 40-character hexadecimal Git object IDs; there are no include/submanifest indirections and no projects on the declared private remote. The lock embeds the exact original XML as `manifest.resolved.embedded_xml`, including linkfiles/copyfiles/remotes, so it remains available after the upstream build is pruned. Hash its UTF-8 bytes against `manifest.resolved.sha256` before use.

One implementable replay sequence, in a new dedicated build checkout, is:

```bash
repo init -u https://github.com/LineageOS/android.git \
  -b 10ba61c98559ecc9409b39937e10f7c065631e25 \
  --manifest-upstream-branch lineage-22.2 --git-lfs --no-clone-bundle
# Root script: verify the embedded XML hash and write those exact bytes to
# .repo/manifests/vela-upstream.xml; do not merge branch-based roomservice entries.
repo init -u https://github.com/LineageOS/android.git \
  -b 10ba61c98559ecc9409b39937e10f7c065631e25 \
  --manifest-upstream-branch lineage-22.2 -m vela-upstream.xml \
  --git-lfs --no-clone-bundle
repo sync
```

An alternative is to generate a standalone manifest and use `repo init --standalone-manifest -u <manifest-URL>`. **First replace the original `github` remote's relative `fetch=".."` with `fetch="https://github.com"`**; otherwise resolving it against a local file or a mirror URL can select the wrong repository base. Keep the original hash and record the transformed manifest's own hash. These are integration instructions derived from Repo's documented manifest handling, not a sync validated here. Sources: [Repo init](https://gerrit.googlesource.com/git-repo/+/refs/heads/main/subcmds/init.py), [manifest remote URL semantics](https://gerrit.googlesource.com/git-repo/+/refs/heads/main/docs/manifest-format.md).

After any authorized sync and addition of Vela's source/patches, export the actual complete tree:

```bash
repo manifest -r -o vela-resolved-manifest.xml
sha256sum vela-resolved-manifest.xml
```

Record Vela's own commit and patches separately: `repo manifest -r` records repository HEADs, not uncommitted edits. Also record Repo launcher/tool versions, host/container package versions, build parameters, LFS objects, all downloaded vendor archive hashes, generated vendor output hashes, and signing-key identity. A Git revision lock does not make output bit-for-bit reproducible and cannot reproduce LineageOS release signatures. Source: [Repo revision-locked manifest export](https://gerrit.googlesource.com/git-repo/+/refs/heads/main/subcmds/manifest.py).

### Device dependency recursion

The lock's `device_projects` contains **30 unique projects**, each with `repository`, official HTTPS `url`, checkout `target_path`, actual `branch`, `revision`, `observed_branch_head`, `dependencies`, `required_by`, and an immutable `dependencies_url` when present. Every observed branch head matched the official build's resolved revision on the read date. Follow dependency-file `branch` overrides: the kernel uses **`lineage-22.2_4.9`**, rather than `lineage-22.2`.

The main recursion is:

- `sif` → `foster`.
- `foster` → `shield-common`, `t210-common`, five kernel/module projects, NVIDIA hardware interfaces/services/platform trees, U-Boot, and GCC 9.3 prebuilts.
- `t210-common` → `tegra-common` → NVIDIA sepolicy and Dolby interfaces.
- The platform trees recursively add `platform/tegra/common`, `soc/tegra`, `platform/t210/common`, `soc/t210`, `platform/t210b01/ers`, and `platform/t210b01/common`.

Source files: [sif dependencies](https://github.com/LineageOS/android_device_nvidia_sif/blob/3fae828e4211469a212429ce7774654e261a5b79/lineage.dependencies), [foster dependencies](https://github.com/LineageOS/android_device_nvidia_foster/blob/666d034739cd89e5a2628129e2141927e9d65592/lineage.dependencies), [t210-common dependencies](https://github.com/LineageOS/android_device_nvidia_t210-common/blob/21b0ac17affa5273306a3c217bc87a82bb8eb861/lineage.dependencies), [tegra-common dependencies](https://github.com/LineageOS/android_device_nvidia_tegra-common/blob/9d34c7b48c4a51d132c0c89483ac85d95d324dd5/lineage.dependencies), [sif platform dependencies](https://github.com/LineageOS/android_hardware_nvidia_platform_t210b01_sif/blob/4aea15d56c70f813f457b4c837abacb2bec4929d/lineage.dependencies). The machine-readable lock is authoritative for all 30 revisions; selected anchors are:

| Checkout | Pinned revision |
| --- | --- |
| `device/nvidia/sif` | `3fae828e4211469a212429ce7774654e261a5b79` |
| `device/nvidia/foster` | `666d034739cd89e5a2628129e2141927e9d65592` |
| `device/nvidia/t210-common` | `21b0ac17affa5273306a3c217bc87a82bb8eb861` |
| `device/nvidia/tegra-common` | `9d34c7b48c4a51d132c0c89483ac85d95d324dd5` |
| `device/nvidia/shield-common` | `3b5f044bf4647644700a85b845c17b1a885821f5` |
| `device/nvidia/sepolicy` | `834e3d1e775d29ce5ee544e4bfcff584baac0a25` |
| `kernel/nvidia/kernel-4.9` | `9d1bd583388e37cf3afb552f435186867526c493` |

## Official OTA and available file types

The official API, read on 2026-10-31, lists three retained `22.2` nightly builds (October 16, 23, and 30). The selected build is **2026-10-30**, Android SDK **35**, reported OS patch level **2026-10-01**. These are API claims, not an independent security audit. Sources: [official download API](https://download.lineageos.org/api/v2/devices/sif/builds), [official download UI](https://download.lineageos.org/devices/sif).

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| [lineage-22.2-20261030-nightly-sif-signed.zip](https://mirrorbits.lineageos.org/full/sif/20261030/lineage-22.2-20261030-nightly-sif-signed.zip) | 632935217 | `74c3c99425de9a0f65aecee299b1032afa974008b7071b32f1026c37d09912ea` |
| [boot.img](https://mirrorbits.lineageos.org/full/sif/20261030/boot.img) | 14098432 | `349a859520cf20aec31a58c4937d369018b13023c19b818473be654b31a49c0d` |
| [recovery.img](https://mirrorbits.lineageos.org/full/sif/20261030/recovery.img) | 22061056 | `914528d5d65f942729fbc425b995324a3bbc197777e560a644e76c317b0161d0` |
| [build-manifest.xml](https://mirrorbits.lineageos.org/full/sif/20261030/build-manifest.xml) | 290869 | `bd859b78d00048f4e41555e63112421ab293adbb0d4a667e9040a5876dbd6390` |

Exact OTA URL: [lineage-22.2-20261030-nightly-sif-signed.zip](https://mirrorbits.lineageos.org/full/sif/20261030/lineage-22.2-20261030-nightly-sif-signed.zip). The API names SHA-1 and SHA-256 separately; their values are respectively 40 and 64 hexadecimal characters. The lock retains both fields and build scripts should verify **SHA-256**. The manifest hash was independently recomputed; the OTA, boot and recovery image hashes are **published values, not independently recomputed here**. A `ZIP.sha256` sidecar request returned HTTP 404; use the explicit API `sha256` value, not an invented sidecar or the SHA-1 field.

A 65,557-byte HTTP range read of the ZIP tail inspected its central directory without downloading the payload. The OTA is **block based with Brotli**, containing `system.new.dat.br`, `vendor.new.dat.br`, both `.transfer.list` files, `boot.img`, `recovery.img`, firmware blobs and DTBs. It has **no `payload.bin`**. The API offers no `system.img`, `vendor.img`, `super.img`, `vendor_boot.img`, or target-files ZIP as separate downloads for this build. Don't write a payload.bin-only extractor or assume a downloadable raw disk image.

### No-root extraction: filesystem inspection versus complete vendor generation

The official [manual ZIP extraction guide](https://github.com/LineageOS/lineage_wiki/blob/aa06ba20f9afdb979b035d1a0063e0bcb17cb020/pages/extracting_blobs_from_zips_manually.md) explains block-based reconstruction. After downloading and SHA-256-verifying the pinned Lineage ZIP in later authorized work, these are the relevant filesystem steps, with `LINEAGE_ROOT` and `OTA` set to absolute paths:

```bash
unzip "$OTA" system.new.dat.br system.transfer.list vendor.new.dat.br vendor.transfer.list
brotli --decompress system.new.dat.br
brotli --decompress vendor.new.dat.br
python3 "$LINEAGE_ROOT/tools/extract-utils/sdat2img.py" system.transfer.list system.new.dat system.img
python3 "$LINEAGE_ROOT/tools/extract-utils/sdat2img.py" vendor.transfer.list vendor.new.dat vendor.img
7z x -osystem system.img
7z x -ovendor vendor.img
```

This reconstructs filesystem contents without a rooted device or mounting on the host. The pinned upstream extractor uses this Brotli → sdat2img → 7z sequence for NVIDIA block OTAs. Check extracted paths and reject missing files; upstream notes that 7z can report dangerous symlinks. These steps were inferred from source, not executed on downloaded payloads. Sources: [pinned sdat2img](https://github.com/LineageOS/android_tools_extract-utils/blob/3f351b85d09ac0160ee42130dff6b0c695333b86/sdat2img.py), [Tegra extraction implementation](https://github.com/LineageOS/android_device_nvidia_tegra-common/blob/9d34c7b48c4a51d132c0c89483ac85d95d324dd5/extract/extract_utils.sh).

**That dump is not a complete `vendor/nvidia` build input.** The device's actual [`extract-files.sh`](https://github.com/LineageOS/android_device_nvidia_sif/blob/3fae828e4211469a212429ce7774654e261a5b79/extract-files.sh) delegates to [`tegra-common/extract/extract-files.sh`](https://github.com/LineageOS/android_device_nvidia_tegra-common/blob/9d34c7b48c4a51d132c0c89483ac85d95d324dd5/extract/extract-files.sh). There is no device `extract-files.py` in this tree. The shared shell helper discovers all checked-out `device/nvidia/*/extract/extract_sources.sh` registries, reads their `sources.txt` archives, copies files from per-archive file lists into its NVIDIA-specific vendor layout, applies patches and generates Blueprint files. Its source argument is an **archive directory**, not the generic extracted filesystem directory described by the wiki template. Even `adb` would be treated as an archive-directory argument and trigger registry downloads; this is not a rooted-device extraction path. Do not claim `./extract-files.sh path/to/lineage.zip` is sufficient.

The supported device-free vendor generation command, after the full dependency closure and host tools are present, is:

```bash
bash device/nvidia/sif/extract-files.sh /absolute/path/to/nvidia-archive-cache
```

The cache should contain the registry archives under their original basenames or `<source-name>.zip` / `.sh` / `.tbz2`. Missing archives are automatically downloaded, so the unmodified command is **not offline**. It consumes **14 archives** across the foster, t210-common, and tegra-common registries, including NVIDIA's stock `sif` OTA, shared stock archives and L4T packages. `shield-common` registers an additional file-list/patch set. These cross-model source assets do not require owning or buying another model. Sources: [foster registry](https://github.com/LineageOS/android_device_nvidia_foster/blob/666d034739cd89e5a2628129e2141927e9d65592/extract/sources.txt), [t210 registry](https://github.com/LineageOS/android_device_nvidia_t210-common/blob/21b0ac17affa5273306a3c217bc87a82bb8eb861/extract/sources.txt), [Tegra registry](https://github.com/LineageOS/android_device_nvidia_tegra-common/blob/9d34c7b48c4a51d132c0c89483ac85d95d324dd5/extract/sources.txt), [shared file mappings](https://github.com/LineageOS/android_device_nvidia_tegra-common/blob/9d34c7b48c4a51d132c0c89483ac85d95d324dd5/extract/file.list), [foster file mappings](https://github.com/LineageOS/android_device_nvidia_foster/blob/666d034739cd89e5a2628129e2141927e9d65592/extract/file.list).

The specifically pinned NVIDIA `stock-sif` source is:

- URL: [productionBL-droid-signed-sif_32b-full_ota-7825199_4387.0822_7E5FF6.zip](https://ota-downloads.nvidia.com/ota/productionBL-droid-signed-sif_32b-full_ota-7825199_4387.0822_7E5FF6.zip).
- Observed length: **702,834,240 bytes**, with a successful HTTP 206 tail-range read. Its ZIP directory confirms the same block/Brotli system/vendor layout plus `blob` and `bmp.blob`.
- The pinned upstream registry publishes **no SHA-256** for this archive. The filename suffix `7E5FF6` has no declared checksum algorithm, and HTTP `ETag` is not a verified file digest. The lock uses `sha256: null` rather than fabricating one.

`vendor_blobs.archives` records all 14 exact official URLs, source types, output vendor branches, pinned source-registry URLs and explicit absent checksums. Acquiring the archive bytes and establishing their digests is a remaining binary-lock step for the root build implementation. A single Lineage OTA is valuable for inspection and matched-release reference, but a proven single-OTA-to-complete-vendor regeneration path was **not** found in this upstream. Preserve the native multi-archive extractor or separately implement and validate such a transformation; do not silently swap in third-party vendor repositories.

The helper explicitly checks for `brotli`, `7z`, `simg2img`, `tar`, `ar`, `zstd`, `wget`, ImageMagick `identify`/`convert`, `unlz4`, and patchelf 0.18 (or the tree's `patchelf-0_18`). Its copy/patch logic also uses LLVM tools and generated vendor files. Record the tool environment when validating this path; no blob generation was run here. Source: [helper prerequisites and extraction/copy logic](https://github.com/LineageOS/android_device_nvidia_tegra-common/blob/9d34c7b48c4a51d132c0c89483ac85d95d324dd5/extract/extract_utils.sh).

## NVIDIA stock recovery catalogue

The first-party [Shield resources page](https://developer.nvidia.com/shield-open-source) distinguishes the **2019 8 GB** recovery from the **2019 Pro 16 GB** recovery. Its [download catalogue](https://developer.nvidia.com/tools-downloads.json), key `nvidia-shield-android-tv-2019-recovery-os-image-9-2-1`, currently lists **9.2.1** dated **2025/05/12** for the Tube. The resources page also advertises **9.2.2 open-source packages**; this does not make the catalogue recovery ZIP 9.2.2.

- Catalogue archive: [nv-recovery-image-shield-2019-atv-9.2.1.zip](https://developer.nvidia.com/downloads/assets/mobile/secure/images/shield_atv_2019/9.2.1/nv-recovery-image-shield-2019-atv-9.2.1.zip).
- Catalogue instructions: [How-To-Flash-Recovery-Image-shield-atv-2019.txt](https://developer.nvidia.com/downloads/assets/mobile/secure/images/shield_atv_2019/9.2.1/How-To-Flash-Recovery-Image-shield-atv-2019.txt).
- Published checksum: **none exposed in the catalogue**; `stock_recovery.sha256` and `published_checksum` are null.
- Access result: the archive HEAD request and instruction GET redirected unauthenticated requests to `https://developer.nvidia.com/login`. No login or credential access was attempted. The resources page says the Linux package needs no login, but this was not the live behavior of its listed 9.2.1 URL on the read date. Do not save the login HTML as a `.zip` or hash it as the recovery image. The archive's internal layout and content remain unverified.

This is a correct **stock recovery catalogue pointer**, not a verified rollback kit. Before any later flash, separately acquire and verify the exact Tube archive and its included instructions; do not substitute a Pro image or invent a SHA-256. This research neither flashed stock nor assessed rollback success.

## Tube installation and feature boundaries

- **USB modification:** the Tube has an unpopulated PCB USB header; the maintainer guide requires adding a suitable vertical Micro-USB Type-B connector or equivalent wiring for bootloader access. This requires soldering/hot-air competence and is not established by existing network ADB access. Source: [sif-specific hardware installation guide](https://github.com/LineageOS/lineage_wiki/blob/aa06ba20f9afdb979b035d1a0063e0bcb17cb020/_includes/templates/device_specific/before_install_sif.md).
- **Bootloader only:** the added header can work in fastboot, but **does not work in LineageOS or Lineage Recovery**. Do not promise USB ADB sideload, USB input peripherals, or normal OS USB access through that header. Source: [USB quirk](https://github.com/LineageOS/lineage_wiki/blob/aa06ba20f9afdb979b035d1a0063e0bcb17cb020/pages/quirks/usb.md).
- **Unlock wipes:** the official route uses `fastboot oem unlock` and erases all device data. With power disconnected, holding the pairing button while applying power enters the fastboot menu. The install template then flashes the dedicated recovery partition; this research performed neither action. Sources: [unlock template](https://github.com/LineageOS/lineage_wiki/blob/aa06ba20f9afdb979b035d1a0063e0bcb17cb020/_includes/templates/recovery_install_fastboot_nexus.md), [recovery install template](https://github.com/LineageOS/lineage_wiki/blob/aa06ba20f9afdb979b035d1a0063e0bcb17cb020/_includes/templates/recovery_install_fastboot_generic.md).
- **microSD installation:** after recovery is installed, the official `has_no_usb` path copies the ZIP to removable microSD and selects **Apply update → Apply from EXT_SDCARD** (the filesystem label may replace that name). Recovery's factory reset/format data is also destructive. Recovery can be navigated with short/long presses of the side pairing button. This is the expected install transport for `sif`; existing ADB does not remove it. Sources: [device install template](https://github.com/LineageOS/lineage_wiki/blob/aa06ba20f9afdb979b035d1a0063e0bcb17cb020/_includes/templates/device_install.md), [device flags](https://github.com/LineageOS/lineage_wiki/blob/aa06ba20f9afdb979b035d1a0063e0bcb17cb020/_data/devices/sif.yml).
- **Dolby:** the device's linked quirk says Dolby Vision and local Dolby content decoding are unsupported under LineageOS; proprietary stock/locked-bootloader and TEE `ipprotect` dependencies constrain support. Do not advertise restored Dolby support because proprietary libraries or Dolby interfaces exist in source. Source: [Dolby Vision quirk](https://github.com/LineageOS/lineage_wiki/blob/aa06ba20f9afdb979b035d1a0063e0bcb17cb020/pages/quirks/dolby_vision.md).
- **AI upscaling:** NVIDIA AI upscaling is unsupported, owing to proprietary NVIDIA frameworks and `ipprotect`; it is not inherited by Vela's source overlay. Source: [AI upscaling quirk](https://github.com/LineageOS/lineage_wiki/blob/aa06ba20f9afdb979b035d1a0063e0bcb17cb020/pages/quirks/ai_upscaling.md).
- **DRM/certification:** LineageOS does not bypass device-integrity checks. Apps may reject custom firmware or an unlocked bootloader. No source examined establishes that this Vela image retains stock Widevine security level, streaming certification, HDCP behavior, or a particular Netflix/other provider resolution. Treat those as unvalidated; do not assert universal L3 or guaranteed L1/4K. Sources: [LineageOS device-integrity policy](https://github.com/LineageOS/lineage_wiki/blob/aa06ba20f9afdb979b035d1a0063e0bcb17cb020/pages/quirks/snet.md), [Widevine source mappings](https://github.com/LineageOS/android_device_nvidia_tegra-common/blob/9d34c7b48c4a51d132c0c89483ac85d95d324dd5/extract/file.list).

## Lock key contract and remaining work

The proposed root-script contract is explicit rather than an implied pre-existing repository schema:

| Key | Meaning |
| --- | --- |
| `schema_version`, `device`, `lineage_branch` | Required constants `1`, `sif`, `lineage-22.2`; reject a different device. |
| `manifest.repository`, `revision` | Official manifest Git bootstrap pin. |
| `manifest.resolved` | Published source metadata URL, exact SHA-256, byte/project counts, and embedded original XML; use this for the whole tree. |
| `device_projects` | All 30 recursive dependency pins and branch overrides; suitable for closure validation or pinned local manifest generation. |
| `build_support_projects`, `build` | Useful core source pins and exact upstream lunch/build tuple. |
| `official_build.files` | Pinned signed Lineage OTA/boot/recovery/manifest metadata and distinct SHA-1/SHA-256 values. |
| `vendor_blobs` | Actual multi-archive extraction method and all registry inputs; absent binary hashes remain null. |
| `stock_recovery` | Correct Tube catalogue pointer, observed access result, unavailable checksum/content verification. |
| `artifact_constraints`, `scope`, `limitations` | `sif`-only output requirements and explicit verification limits. |

No additional user decision is needed to use these Git source pins. Remaining implementation/validation work is: freeze vendor bytes/tooling, validate extraction, apply the root-owned Vela product and `sif`-only installer restrictions, build on a suitable host, and later validate the device through the separately authorized install path. Stock recovery acquisition is still unresolved. These files do not claim any of those steps completed.
