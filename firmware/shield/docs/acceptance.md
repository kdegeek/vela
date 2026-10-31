# Shield image integration evidence — 2026-10-31

Hardware scope is the owner's existing 2019 Tube (`sif`). No physical-device writes, reboot, unlock, wipe or flash have been performed for the image work.

## Implemented and checked locally

- Unsigned, non-debuggable firmware APK: `android/app/build/outputs/apk/firmware/app-firmware-unsigned.apk`. SDK `aapt2` verified its package, firmware-only permissions and arm64 native libraries.
- `testDebugUnitTest`: **416 tests**, no failures/errors/skips. Includes all 20 firmware policy cases.
- Explicit firmware-variant policy run: **20 tests passed**, using `-Pandroid.onlyEnableUnitTestForTheTestedBuildType=false testFirmwareUnitTest --tests app.vela.tv.firmware.FirmwareBootstrapPolicyTest`.
- `lintDebug lintFirmware assembleDebug assembleFirmware`: successful. The firmware-only manifest and receiver packaged successfully. Lint has warnings and no errors; no release qualification is implied.
- Shield tooling: **25 Python tests passed**. `bash -n`, ShellCheck and `git diff --check` passed.
- Original official source manifest hash independently recomputed as `bd859b78d00048f4e41555e63112421ab293adbb0d4a667e9040a5876dbd6390`; every one of its 1,171 project revisions is immutable, and the 30 NVIDIA dependency revisions match. Public-remote replay hash: `5ca34678edb0bd343b845d401ec6f72623d8d6a3100d67f88adfbba9a94de9c6`, also produced on the Linux VM.

The actual firmware APK also passed staging against the pinned Tube product and board sources. Independent review corrections bind the built OS manifest and installed integration marker to the source snapshot, compare every non-signature APK entry, and declare the observed optional WindowManager libraries. Regression fixtures reject stale OS archives and same-DEX APKs with changed manifests/resources/native libraries.

Tooling tests use synthetic APK/target-files/OTA fixtures. They establish rejection and packaging behavior, not actual Soong linkage, successful OTA installation, boot, or playback. Full platform compilation remains a separate required check.

## Provisioned on Polycasa

The owner explicitly authorized a new builder. `vela-shield-build` is an isolated Ubuntu 22.04 x86_64 KVM VM with 8 vCPUs, 32 GiB configured RAM, 16 GiB swap and a 650 GiB sparse disk on array disk 10. Initial guest observation: 31 GiB usable memory and 613 GiB free on `/`. Autostart is disabled. Existing VMs/containers were not stopped or reconfigured. No host inbound forwarding or Tailnet Serve change was made.

The Ubuntu base image SHA-256 was verified against Canonical's published value: `58be26aa7452f3005a4f7370a43928bfb33a7f35a77a8f3b264bf00266a17ca0`. The current Repo launcher was verified with Google's documented Repo Maintainer public-key fingerprint before installation in the new guest user's local bin directory. The old Ubuntu launcher lacked `--git-lfs`; its failed initialization was retained separately before a successful fresh initialization.

Polycasa's configured host DNS failed to resolve the Ubuntu download. The verified download used a Mac-resolved address with HTTPS hostname validation retained. Host DNS was not changed. This new guest uses its own public DNS configuration. Cloud-init reported two unresolved virtual/transitional package names; the actual build tools and additional extractor packages were installed and checked afterward. That first-boot error history was retained.

Full source sync is running in `vela-source-sync.service`, with four sync jobs and a four-CPU quota. Vendor archive acquisition is running in `vela-vendor-fetch.service`. Both are named, bounded systemd units inside the new VM, with task logs under `/srv/vela`. Source initialization and the first NVIDIA archive download have succeeded. No full OS compile has completed yet.

## Four remaining image acceptance items

1. Finish source/vendor acquisition, validate native vendor extraction, and retain all binary/tooling receipts and generated vendor hashes.
2. Compile the complete OS, generate the OTA from verified target-files, and inspect its recovery/device assertions and checksums.
3. Acquire and inspect the exact Tube stock recovery kit. Its catalogue URL currently redirects to NVIDIA login and its content/checksum has not been verified.
4. Prepare service USB and microSD, then perform separately coordinated physical installation and boot/remote/network/CEC/media/DRM/update/recovery acceptance.

Items 1–2 are ongoing software work on the authorized builder. Items 3–4 remain physical-install prerequisites. Development test keys and known Lineage feature limitations preclude a shipping-ready firmware claim. The broader TV/phone/model acceptance queue is tracked separately in [the application acceptance record](../../../docs/design/acceptance-2026-10-31.md).
