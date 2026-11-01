# Vela OS: Tube installation and recovery handoff

Target: the owner's existing **2019 Shield TV Tube (`sif`, 8 GB)**. The outcome is
a full Android 15 system booting to Vela with system privileges, working device
hardware and a demonstrated update/recovery path. A sideloaded launcher check or
successful compile does not complete this outcome.

## Current state — 2026-11-01 UTC

- The full OS and matching recovery already build. The earlier
  [current-application image](current-build-20261101.md) remains preserved and
  unboot-tested. Development signing uses public testkeys.
- The physical Tube remains on stock Android 11/API 30. Fresh read-only properties
  report `ro.boot.vbmeta.device_state=locked`, verified boot `green`, and bootloader
  `32.00.2019.50-t210-69ebfcbe`. No reboot, unlock, format or flash was performed.
- The owner reports **neither service USB nor microSD is ready / known ready**.
  The documented Tube route requires fitting an internal Micro-USB Type-B service
  connector (or suitable wired connection), checking fastboot enumeration, then
  installing from removable microSD. This is physical board work, not a network
  ADB setting. [Device maintainer installation guide](https://wiki.oddsolutions.us/devices/sif/install/).
- SDK fastboot 37.0.0 is present on this Mac at
  `~/Library/Android/sdk/platform-tools/fastboot`. No fastboot device was listed
  while the Tube continued running stock Android. That is not a failed USB test;
  no bootloader-mode transition was attempted.
- NVIDIA's exact 2019 8 GB stock recovery 9.2.1 archive and its instructions still
  redirect to login with zero content bytes. The [catalogue pointers](current-build-20261101.md#stock-rollback-prerequisite)
  are verified; archive/model/image/rollback compatibility is not.

Local read-only observations and downloaded pinned-source references are retained
under ignored `firmware/shield/artifacts/install-preflight-20261101/`. Stock shell
access to `/proc/partitions`, raw partition sizes and the updater's two efuse nodes
was denied. No root, exploit or permission change was attempted.

## Software correction before the next image

The inherited foster configuration declares `userdata_size=10099646976`, larger
than the Tube's entire 8 GB storage. The retained build log records construction
and validation of a sparse `userdata.img` with **2,465,734 × 4096 = 10,099,646,464
logical bytes**. This is log evidence, not a newly read sparse header.

The existing OTA has no userdata write or format action. Its recovery fstab maps
UDA to `/data` without a fixed length, so the documented recovery reset formats
the live partition. The oversized standalone image is nevertheless unsuitable
for later raw flashing/repacking.

Vela now sets `PRODUCT_BUILD_USERDATA_IMAGE := false`. The pinned
[Android build configuration](https://github.com/LineageOS/android_build/blob/ab5acb1281035ebc9b0f190baf22ef3ea896106e/core/board_config.mk)
supports this product variable, and its
[image packaging rules](https://github.com/LineageOS/android_build/blob/ab5acb1281035ebc9b0f190baf22ef3ea896106e/core/Makefile)
omit userdata image generation and its image-size metadata when disabled. No
partition size was guessed or changed. The package validator rejects a userdata
image, enabled/ambiguous generation metadata, missing metadata or a declared
userdata size before invoking OTA generation. Provenance records the live-UDA
recovery-format policy.

Five unsafe fixture cases reproduced acceptance before the guard. All **29
firmware-tooling tests** pass after the correction, including the real build's
omitted-properties form. The native rebuild at `8276c58` now verifies the correction
in target-files and the delivered bundle; [artifact identities and independent
checks](no-userdata-build-20261101.md). Older bundles and their evidence remain
unchanged. Physical installation/boot is still unverified.

## Preparation and installation sequence

1. **Prepare physical access.** Have the service connector/wiring fitted by someone
   equipped for this board work, and obtain a microSD card. The maintainer guide
   identifies a vertical Micro-USB Type-B connector such as Würth
   `614105150621`; follow its pin/orientation photos. Do not power the board while
   working on it. No purchase or hardware modification has been performed here.
2. **Acquire rollback files.** After NVIDIA authentication, retain the exact
   `nv-recovery-image-shield-2019-atv-9.2.1.zip` and matching How-To file locally.
   Hash the bytes, inspect all image/model identifiers and instructions, and
   determine compatibility with the installed stock bootloader. Neither a stock
   OTA nor a Lineage ZIP substitutes for the stock recovery kit. Do not send
   credentials/cookies.
3. **Verify the new Vela bundle.** Require target-files to contain no
   `IMAGES/userdata.img`, no userdata size or enabled-generation metadata, and
   matching Vela APK/configuration/source markers. Verify the recovery fstab's
   live-UDA mapping, exact OTA assertions/write actions, package signatures and
   transport hashes. Stage the verified OTA for microSD and keep its matching
   recovery on the host; do not write media or devices from this document.
4. **Inspect fastboot and device fit.** Once physical access is ready, coordinate
   entry into fastboot and pin its exact observed serial. Read product, unlock
   state and supported partition-size variables without writing. `df` reports
   filesystem capacity, not raw partition geometry. Missing/unsupported size
   variables remain unresolved rather than being inferred from a nominal model.
5. **Review the destructive operation.** Back up other app/user data; the existing
   private Vela backup is not a full-device backup. Resolve Google-account/factory
   reset protection through the maintainer's documented preparation. Then obtain
   explicit approval for the exact unlock/erase/recovery-flash/install sequence,
   with the verified bundle and rollback identities in view.
6. **Execute and observe, only after approval.** The maintainer flow uses
   `fastboot oem unlock` (erases data), flashes the matching recovery, boots
   directly into it, selects Factory Reset / Format data, then applies the Vela
   ZIP from `EXT_SDCARD`. Do not boot stock Android between flashing custom
   recovery and entering it. Keep the bootloader unlocked for this development
   image; no relock action is part of this handoff. Stop on a failed assertion or
   signature check instead of bypassing it.

These are reviewable stages, not an executable flash script. Current approval
covers software preparation and diagnosis; it does not authorize the destructive
commands in stage 6. The owner's leave-Home-visible preference remains in force
until a separately coordinated bootloader/recovery session.

## Device-fit and bootloader checks

The inspected current OTA directly requires at least these endpoint capacities;
recompute them from the replacement artifact rather than copying them blindly:

| Native partition | Current operation / minimum extent |
| --- | --- |
| APP (`system`) | Block transfer reaches 1,616,904,192 bytes; then filesystem resize |
| vendor | Block transfer reaches 379,584,512 bytes; then filesystem resize |
| LNX (`boot`) | Writes the 14,098,432-byte boot image |
| SOS (`recovery`) | Initial fastboot stage writes the 22,065,152-byte recovery |
| DTB | Native sif branch writes 575,532 bytes |
| vbmeta | Native sif branch writes 256 bytes |
| USP | May stage a 6,669,260-byte signed sif bootloader blob |
| UDA / CAC | Unlock and recovery reset erase/format live storage, separately from OTA |

The stock bootloader string differs from the native sif updater's accepted
`32.00.2019.50-t210-da5c2b1b`. On the fused, exact-key branch, that mismatch can
stage the signed blob to USP before OS writes. An unfused or unknown-key path can
abort. Preserve the original production-mode/public-key guards. USP is the direct
staging endpoint; the blob's later bootloader-applied component blast radius is
not established by inspecting the updater script alone. Do not equate these
version suffixes with a proven safe downgrade/upgrade order.

## What completes OS acceptance

- First boot completes setup, selects Vela as Home, and returns there after a
  normal reboot. Keeping SetupWizard installed and a preferred-Home XML is source
  configuration, not runtime proof of that handoff.
- The two private privileges are granted on-device; the accessibility service
  enrolls once after setup/unlock, binds, and remains revoked across reboot if the
  owner later disables it. The firmware boot policy is tested but not yet run on
  this Tube's Android 15 system image.
- Remote input, Wi-Fi/Ethernet/Bluetooth, CEC/power, sound, hardware media decode
  and 4K output work on actual hardware. DRM/service certification is measured
  separately rather than inferred from successful local playback.
- Media-session control works through the firmware privilege path. Phone/control
  startup behavior during setup is observed; the current app's runtime is not
  globally gated by its narrower accessibility-enrollment gate.
- A subsequent Vela OTA preserves expected user data and credentials, and a
  separately approved recovery/stock rollback is demonstrated.

Public testkeys and `userdebug` qualify this as a development OS even if those
tests pass. Private release signing and production/OEM qualification remain later
delivery work.
