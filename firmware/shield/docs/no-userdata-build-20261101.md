# Corrected Vela OS bundle — 2026-11-01

The native `sif` rebuild at frozen integration
`8276c5813425e8216c0bf73c6735636a356e4241` completed successfully. Native compile
time was **10:52**, with build/package execution from **01:50:01 to 02:07:20 UTC**.
This is a complete Android 15 recovery-installable development OS package, still
**unboot-tested and signed with public testkeys**. It was not installed on the Tube.

The [installation-readiness correction](installation-plan.md#software-correction-before-the-next-image)
is now verified in the actual build: `PRODUCT_BUILD_USERDATA_IMAGE=false`, empty
`BUILDING_USERDATA_IMAGE`, no `userdata.img` member anywhere in target-files, no
`userdata_size`, and no enabled-generation metadata. Checks ran before native OTA
generation and during inspection; the image was not removed after building.
The older bundles and their oversized-userdata build evidence remain preserved.

## Exact artifacts

Local bundle/evidence root, excluded from Git:
`firmware/shield/artifacts/current-8276c58-20261101T014148Z/`.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Development OTA | 646,537,338 | `4bcb6a1859ea9388a1a890b2cc362b9795a1ba5bd79db09cca9f934f0bc741bb` |
| Recovery | 22,065,152 | `a689f52f88707089910a7dc53de085c3a1061553fc2a138e268e36773b650d37` |
| Source manifest | 289,298 | `32a6c75ca8135f7708d397418a561ed969ac1793d907c2d8e2b2e08374727919` |
| Provenance | 1,911 | `022bcd10d64131856b6ecc1f627ddd0bdb092a19f336b61b8029702f496b7b72` |

The new target-files archive remains on the powered-off builder: 1,458,513,855
bytes, SHA-256 `fd523a27f1255d4b6e990efb29ff44b4c8ca095ccc41c5a9cdaeabf8ba38e81d`.
The APK input is unchanged: 40,353,268 bytes, SHA-256
`b1237c0e1ca32efa8c6e3252aa0198d88397fee652f5e20efddf67b65b34db2f`.
Application sources are unchanged from `ce8613c`; no new visual implementation is
claimed by this OS build.

## Verification and its limits

- All **29 tooling tests** passed on the frozen guest integration. Preflight
  rehashed 14 vendor archives, four external inputs, 521 vendor-tree entries,
  retained receipts and all 1,171 source revisions. The source manifest and local
  foster fixes are unchanged from the preserved baseline.
- Native packaging/inspection verified the disabled-userdata policy, exact APK,
  source/configuration/input markers, ARMv7 userspace/ARM64 kernel, Android 15/API
  35, VINTF and privileged-permission enforcement, and non-permissive kernel
  configuration/cmdlines. Boot/recovery bytes match target-files, OTA and outputs.
- Recovery's actual CPIO fstab maps `/dev/block/by-name/UDA` to `/data` as ext4 with
  no fixed length. The coordinator independently decoded the delivered recovery
  ramdisk and verified that entry. It is formatting configuration, not a physical
  factory-reset result or proof of raw partition capacities.
- The sif-only updater and all **28 native installer/firmware payloads** are
  byte-identical to the previous current bundle. Its 32 write/run actions have no
  userdata/data-format effect. Fuse/key/version guards remain intact; the staged
  bootloader blob's downstream effects and compatibility remain physical gates.
- Installed APK and whole-file OTA signatures passed on the builder. The worker
  repeated payload/transport/CMS checks on the Mac. The coordinator separately
  rehashed all delivered files and verified the evidence archive's safe member
  types/paths and **89 evidence-file hashes**, all **278 non-signature APK members**,
  both configuration XMLs, input/source markers, matching recovery and all native
  updater payloads. The coordinator independently verified the installed APK
  signature and whole-file CMS signature/trust chain against the pinned public
  certificate; OpenSSL reported success for 646,535,598 signed bytes.
- Target-files itself remains on the builder; the coordinator checked its
  receipted metadata and inspection evidence locally, rather than claiming a
  second local read of that entire archive. Coordinator receipts are retained in
  `coordinator-verification/`; worker reports/logs remain unchanged.

The exact authorized builder retained its original allocation, four effective
Ninja/kernel jobs, four-CPU quota and nice 10. It was gracefully shut down after
inspection and transfer. A fresh coordinator read verified its UUID, **shut-off
state and disabled autostart**. Sources, caches and previous outputs are retained.

## Next physical stage

The owner reports service USB and microSD are not ready / not known ready. The
exact NVIDIA stock recovery kit and instructions still require authenticated
acquisition and content/compatibility verification. Raw partition capacities and
the stock bootloader's compatibility with the native staged blob remain
unresolved. The [installation plan](installation-plan.md) defines those checks and
the later separately approved unlock/erase/install sequence.

No Shield reboot, unlock, wipe, recovery flash, OS install or boot test occurred
in this build task. This evidence completes the software rebuild, not the full
physical appliance, recovery, update, hardware/media or production-signing gates.
