# Vela image for the Shield Tube

Target: the owner's **2019 Shield TV Tube, `sif`**, using LineageOS 22.2 / Android 15, with **32-bit ARM (`armeabi-v7a`) userspace and an ARM64 kernel**. This builds an Android recovery-installable OS package for internal storage. It is separate from the older x86 Linux image under `os/`.

The integration supplies the Vela firmware APK as a privileged system-extension app, a matching two-permission allowlist, a default HOME preference, and a Tube-only OTA assertion. It retains upstream partition sizes, kernel, HALs, setup wizard, fallback launcher, recovery DTBs and SELinux enforcement. After Android setup and unlock, this appliance variant enrolls its accessibility service once; later owner revocation is respected. No provider credentials, home tokens, microphone grants or purchase approvals are provisioned into the image.

**Status:** the current application image is built and inspected at frozen integration `0bc9785`, including the approved glass UI and input-field fix. All 278 non-signature APK members, source/configuration markers, boot/recovery matching and development signatures passed inspection; the coordinator independently checked the transferred bundle on the Mac. The builder is shut off with disk/caches retained. [Current image identities and verification](docs/current-build-20261101.md); [preserved full-build baseline and architecture correction](docs/baseline-build-20261031.md). Neither image boot nor hardware acceptance has been established. Development artifacts use public test keys and must not be shipped as production firmware.

**OS installation follow-through:** the owner selected full-OS work. The Tube
currently lacks confirmed service USB and microSD preparation. An audit also
found an inherited 10.1 GB standalone userdata image; its generation is now
disabled and packaging rejects it or unverified userdata-size metadata. The
older frozen bundle does not contain this correction; a new native rebuild is
required. [Installation, device-fit, rollback and first-boot plan](docs/installation-plan.md).

## Inputs and host

- [upstream-lock.json](upstream-lock.json) embeds the official October 30, 2026 source manifest: 1,171 immutable Git revisions and the 30-project NVIDIA device dependency closure. The replay tool validates the original hash, makes GitHub's relative remote absolute, and removes unused remotes. It records the transformed manifest hash separately.
- NVIDIA's native vendor extractor needs **14 official archives**, plus the external files referenced by its pinned file lists. A single Lineage OTA is insufficient. [Source and binary acquisition details](docs/upstream.md).
- Linux x86_64, nominal 32 GiB RAM minimum for this plan, 64 GiB recommended, 400 GiB initial free space. After checkout, the build preflight requires 150 GiB free. Lower-memory builds should use `VELA_BUILD_JOBS=4`.
- The APK is built separately with the repository's JDK 21/Android SDK 37 toolchain. The OS source tree supplies its own Java/native compiler toolchain.
- Ubuntu 22.04 dependencies follow the [Lineage build guide](https://wiki.oddsolutions.us/devices/sif/build/), plus `brotli`, `p7zip-full`, `android-sdk-libsparse-utils`, `zstd` and the extractor's tool prerequisites. Use the signed current Repo launcher; Ubuntu's older packaged launcher does not recognize `--git-lfs`.

The provisioned builder is the isolated `vela-shield-build` VM on Polycasa: 8 vCPUs, 32 GiB configured RAM, 16 GiB guest swap, and a 650 GiB array-backed disk. Its storage avoids Polycasa's nearly full SSD cache. Autostart is disabled and no inbound port forwarding was added. Existing VMs/services were retained. Runtime connection details and logs stay outside Git.

## Build sequence

First build the application, from the repository root:

```sh
cd android
./gradlew --no-daemon testDebugUnitTest lintDebug lintFirmware assembleDebug assembleFirmware
```

The image input is `android/app/build/outputs/apk/firmware/app-firmware-unsigned.apk`. `assembleDebug` retains its existing sideloaded APK path. The staging tool checks the firmware package, non-debuggable state and two permissions using the SDK's `aapt2`, and requires non-empty ARMv7 native libraries. ARM64-only APKs cannot run in this image's userspace; ARM64 support may additionally be packaged in a universal APK.

On the Linux builder, with this repository available at `$VELA_REPO` and a **new empty** `$VELA_OS_TREE`:

```sh
python3 "$VELA_REPO/firmware/shield/vela_shield.py" doctor --workspace "$VELA_OS_TREE"
python3 "$VELA_REPO/firmware/shield/vela_shield.py" init --workspace "$VELA_OS_TREE"
cd "$VELA_OS_TREE"
repo sync -j4 --fail-fast
```

`init` creates a private local manifest repository from the embedded official lock. It does not create an index or modify a different checkout. Avoid `breakfast`/roomservice downloads from moving branches: all declared device dependencies are already in the pinned manifest. The explicit upstream lunch tuple is `lineage_sif-bp1a-userdebug`.

Before generating vendor files, cache and hash the NVIDIA registry archives:

```sh
python3 "$VELA_REPO/firmware/shield/fetch_vendor.py" --cache "$VELA_ARCHIVE_CACHE"
cd "$VELA_OS_TREE"
bash device/nvidia/sif/extract-files.sh "$VELA_ARCHIVE_CACHE"
```

The first fetch records observed SHA-256 digests because the source registries lack published hashes; subsequent fetches enforce the receipt. A login/error page is rejected. Keep `vendor-inputs.json`, all archive bytes, the generated private vendor tree and the extractor logs. The native helper can still download individually listed external files; freeze those bytes and the generated vendor tree before calling a release reproducible. Do not publish proprietary archives in this repository. Never substitute a generic `extract-files.sh lineage.zip` command.

Freeze the resolved source revisions **before staging** the two managed source edits:

```sh
python3 "$VELA_REPO/firmware/shield/vela_shield.py" freeze \
  --workspace "$VELA_OS_TREE" --output "$VELA_SOURCE_SNAPSHOT"
VELA_BUILD_JOBS=4 "$VELA_REPO/firmware/shield/build.sh" \
  "$VELA_OS_TREE" "$VELA_FIRMWARE_APK" "$VELA_AAPT2" \
  "$VELA_SOURCE_SNAPSHOT" "$VELA_BUNDLE_OUTPUT"
```

Use absolute paths. Staging appends a managed product include and a final `TARGET_OTA_ASSERT_DEVICE := sif` to the Tube BoardConfig; repeated staging preserves upstream content and rejects an unexpected edit. Freeze requires clean tracked upstream sources. To regenerate a snapshot after staging, first restore only those managed stanzas from a verified source revision in this dedicated builder checkout; do not reset an unrelated working tree.

The script builds target-files and OTA tools, inspects every non-signature entry of the installed APK and the image configuration, verifies the source manifest and integration marker embedded in the built archive, and **generates the OTA from that inspected target-files archive**. It does not accept an unrelated existing OTA. It extracts recovery from the same archive, verifies `sif`-only OTA metadata and updater assertions, and publishes:

- `vela-sif-development.zip`: recovery-installable development OTA.
- `recovery.img`: matching recovery image.
- `source-manifest.xml`, `provenance.json`, `SHA256SUMS`: revision, app/configuration and artifact checksums.

The build script explicitly refreshes Lineage's embedded build-manifest before compiling because its upstream Make rule has no source dependencies. Staging also installs a source/app/configuration input marker in the image. An older archive cannot be labeled with newer checkout revisions merely because its app is unchanged.

If the intermediates directory contains multiple target-files ZIPs, pass the exact current archive as argument six. Output/snapshot directories are immutable. The bundle qualification remains `unverified-development-testkeys`: packaging checks do not prove boot, signatures on a shipping trust chain, or successful hardware playback. Source locking also does not guarantee byte-identical builds.

## Tube hardware and recovery

The Tube needs an added internal service USB connection for initial USB fastboot access. That header is **bootloader-only** under this Lineage target, including no USB ADB sideload in recovery. The documented OS installation transport is microSD. Unlocking and recovery formatting erase data. [Hardware and install instructions](https://wiki.oddsolutions.us/devices/sif/install/).

The source lock records NVIDIA's exact **2019 8 GB Tube** stock recovery catalogue entry. On 2026-11-01, the exact 9.2.1 archive and instructions still redirected to NVIDIA login with zero content bytes. No independently verified stock rollback archive has been acquired. The public stock OTA used for vendor extraction is not a verified replacement for that recovery kit. [Acquisition evidence and required owner-supplied files](docs/current-build-20261101.md#stock-rollback-prerequisite). Obtain and inspect the correct stock recovery and instructions before modifying/flashing the owner's only device. No build script unlocks, reboots, wipes, relocks or flashes a physical Shield.

Lineage documents missing NVIDIA AI upscaling, Dolby Vision and local Dolby decoding. Netflix/Prime availability, Widevine/HDCP behavior, remote microphone, HDMI-CEC, 4K output and hardware decode need direct acceptance tests. [Detailed source evidence and limitations](docs/upstream.md).

## Checks

```sh
python3 -m unittest discover -s firmware/shield/tests -v
shellcheck firmware/shield/build.sh
```

Tests exercise source pins, target exclusion, idempotent staging, preservation of another vendor directory, stale app/source/configuration rejection, folded/separate system-extension layouts, userdata-image exclusion, OTA generation and checksum provenance. Synthetic target-files fixtures are not an Android image build.

Remaining image work is acquisition and verification of the exact stock recovery kit, and separately authorized physical installation/boot/media/OTA/recovery acceptance. Vendor acquisition, extraction, full baseline compilation and the current-app rebuild/inspection are complete. Production signing and eventual OEM hardware qualification follow this development PoC.
