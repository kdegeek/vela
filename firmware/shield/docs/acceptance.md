# Shield image integration evidence — 2026-10-31

Hardware scope is the owner's existing 2019 Tube (`sif`). No physical-device writes, reboot, unlock, wipe or flash have been performed for the image work.

## Implemented and checked locally

- Unsigned, non-debuggable firmware APK: `android/app/build/outputs/apk/firmware/app-firmware-unsigned.apk`. SDK `aapt2` verified its package and firmware-only permissions. ARMv7 (`armeabi-v7a`) native support is present and required by the corrected preflight; the earlier ARM64-only assumption was wrong.
- Latest combined application run: **512 JVM tests**, no failures/errors/skips. Includes the firmware bootstrap policy cases. [Current application evidence](../../../docs/design/evidence/field-correction-20261031/README.md).
- Explicit firmware-variant policy run: **20 tests passed**, using `-Pandroid.onlyEnableUnitTestForTheTestedBuildType=false testFirmwareUnitTest --tests app.vela.tv.firmware.FirmwareBootstrapPolicyTest`.
- `lintDebug lintFirmware assembleDebug assembleFirmware`: successful. The firmware-only manifest and receiver packaged successfully. Lint has warnings and no errors; no release qualification is implied.
- Shield tooling: **27 Python tests passed**, including regressions that reproduced the wrong-ABI acceptance before the fix. ARMv7-only and universal APKs pass; ARM64-only, directory markers and empty native libraries fail before staging. `git diff --check` passes. Earlier unchanged shell checks are recorded below.
- Original official source manifest hash independently recomputed as `bd859b78d00048f4e41555e63112421ab293adbb0d4a667e9040a5876dbd6390`; every one of its 1,171 project revisions is immutable, and the 30 NVIDIA dependency revisions match. Public-remote replay hash: `5ca34678edb0bd343b845d401ec6f72623d8d6a3100d67f88adfbba9a94de9c6`, also produced on the Linux VM.

The actual firmware APK also passed staging against the pinned Tube product and board sources. Independent review corrections bind the built OS manifest and installed integration marker to the source snapshot, compare every non-signature APK entry, and declare the observed optional WindowManager libraries. Regression fixtures reject stale OS archives and same-DEX APKs with changed manifests/resources/native libraries.

Tooling tests use synthetic APK/target-files/OTA fixtures. They establish rejection and packaging behavior. The separate [completed baseline build](baseline-build-20261031.md) now establishes full compilation and native packaging for `0809edf`; neither establishes successful OTA installation, boot or hardware playback.

## Provisioned on Polycasa

The owner explicitly authorized a new builder. `vela-shield-build` is an isolated Ubuntu 22.04 x86_64 KVM VM with 8 vCPUs, 32 GiB configured RAM, 16 GiB swap and a 650 GiB sparse disk on array disk 10. Initial guest observation: 31 GiB usable memory and 613 GiB free on `/`. Autostart is disabled. Existing VMs/containers were not stopped or reconfigured. No host inbound forwarding or Tailnet Serve change was made.

The Ubuntu base image SHA-256 was verified against Canonical's published value: `58be26aa7452f3005a4f7370a43928bfb33a7f35a77a8f3b264bf00266a17ca0`. The current Repo launcher was verified with Google's documented Repo Maintainer public-key fingerprint before installation in the new guest user's local bin directory. The old Ubuntu launcher lacked `--git-lfs`; its failed initialization was retained separately before a successful fresh initialization.

Polycasa's configured host DNS failed to resolve the Ubuntu download. The verified download used a Mac-resolved address with HTTPS hostname validation retained. Host DNS was not changed. This new guest uses its own public DNS configuration. Cloud-init reported two unresolved virtual/transitional package names; the actual build tools and additional extractor packages were installed and checked afterward. That first-boot error history was retained.

Source sync, vendor acquisition and native extraction subsequently completed. The baseline full build passed **138,839 actions** under the four-job/four-CPU limits and produced an inspected native OTA and matching recovery. The VM was gracefully shut off, with disk/caches retained and autostart disabled. Its completed units/logs and source patches are retained; no baseline source-sync or build unit remained running at handoff. [Build record](baseline-build-20261031.md).

## Three remaining image acceptance items

1. Incrementally rebuild and inspect an image containing the current glass UI and fixes. The completed `0809edf` baseline is older than the application at `ce8613c`; retain both identities and use a new output directory.
2. Acquire and inspect the exact Tube stock recovery kit. Its recorded catalogue URL redirects to NVIDIA login and its content/checksum has not been verified.
3. Prepare service USB and microSD, then perform separately coordinated physical installation and boot/remote/network/CEC/media/DRM/update/recovery acceptance. Physical Shield work remains paused.

Item 1 is software work on the authorized builder. Items 2–3 remain physical-install prerequisites and are not authorized by successful compilation. Development test keys and known Lineage feature limitations preclude a shipping-ready firmware claim. The broader TV/phone/model acceptance queue is tracked separately in [the application acceptance record](../../../docs/design/acceptance-2026-10-31.md).
