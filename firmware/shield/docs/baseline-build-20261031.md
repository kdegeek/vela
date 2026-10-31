# First complete Shield Tube build

The isolated Polycasa builder completed the frozen `0809edfb31d2fac467dc55e2b67fd93d9cf777df`
baseline: **138,839 successful build actions**, with a reported compile duration of
**04:30:38**, followed by native OTA generation and inspection. It produced a real
block OTA and matching recovery. The image remains **unboot-tested and signed with
public development testkeys**. It is not the current glass application and is not
ready for physical installation or production delivery.

Local outputs, intentionally excluded from Git:

```text
firmware/shield/artifacts/baseline-0809edf-20261031T233556Z/
  vela-sif-development.zip
  recovery.img
  provenance.json
  source-manifest.xml
  SHA256SUMS
  HANDOFF.md
  TRANSPORT_VERIFICATION.json
  VM_SHUTDOWN_VERIFICATION.json
  build-evidence/
  coordinator-verification/
```

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Development OTA | 646,374,105 | `6da55b23810131884907ed928e74a649fcaf27a3eebab5c99e91ceecf6fca125` |
| Recovery | 22,065,152 | `d92e9bf75415c0323b9218119b39458cc51af65ee13d464b7edccda59ae04f63` |
| Source manifest | 289,298 | `32a6c75ca8135f7708d397418a561ed969ac1793d907c2d8e2b2e08374727919` |
| Provenance | 1,844 | `124aefe58562f3b567a2680da78c49f72fc6384b2148efcff6a53977fce8c5e3` |

The coordinator independently recomputed all four bundle checksums on the Mac.
The OTA's whole-file CMS signature also independently verified against the
[public test certificate at the frozen build-source revision](https://github.com/LineageOS/android_build/blob/ab5acb1281035ebc9b0f190baf22ef3ea896106e/target/product/security/testkey.x509.pem),
with trust-chain and signature checks enabled. The fetched certificate SHA-256
was `a4384ba815b9499a5ce349b4e33c1755278873fe2eac150a068823f526e6dbde`.
OpenSSL returned `CMS Verification successful` for 646,372,365 signed bytes.
The verification receipt is retained under `coordinator-verification/`.
This checks development-key integrity, not a shipping trust chain.

## Architecture correction

The actual pinned `sif` configuration builds **32-bit ARM userspace**:

- `TARGET_ARCH=arm`, `TARGET_CPU_ABI=armeabi-v7a`, architecture variant `armv8-a`.
- `TARGET_KERNEL_ARCH=arm64`, with `CONFIG_ARM64=y` and `CONFIG_COMPAT=y`.
- Built system properties report `ro.product.cpu.abi=armeabi-v7a` and `abi2=armeabi`.

The retained source makefile, effective lunch output, kernel config and built
properties agree. The earlier arm64-userspace documentation and APK preflight were
incorrect. The old APK happened to include ARMv7, so this did not invalidate the
completed build. The preflight now requires non-empty ARMv7 shared libraries and
rejects ARM64-only APKs before writing staging data. Regressions first reproduced
the wrong acceptance/rejection, then all **27 image-tooling tests passed**.

The current application from `ce8613c` was independently inspected: its ARMv7
`libandroidx.graphics.path.so` is ELF32 / ARM machine 40. Other ABI variants are
also present. It passed the corrected validator with the real SDK 37 `aapt2`.
Its firmware APK hash is
`b1237c0e1ca32efa8c6e3252aa0198d88397fee652f5e20efddf67b65b34db2f`,
different from the baseline input
`4cfda084de575834793012b69d2852d40cebb26aaa1303faa74e8b0c2f1703ec`.
The newer APK is retained as an immutable input under `artifacts/inputs/<hash>/`.
No newer image is claimed until that input has actually been rebuilt and inspected.

## Sources and packaging evidence

The source manifest freezes all **1,171** project HEADs. It includes the recorded
local `device/nvidia/foster` patch commit
`ed4b3ce0041888d53d649c1149a55d5ca0d4efea`, based on upstream
`666d034739cd89e5a2628129e2141927e9d65592`. The reviewed patch has two effects:

1. Replace a failing calibration-dump download with Google's matching pinned source
   file and explicit SHA-256 verification. Equality with the unavailable original
   dump was not established; that limit is recorded.
2. Override the upstream kernel make job count to four, preserving the owner's
   resource limit without changing kernel features or partition sizes.

The patch and Git bundle are retained in `build-evidence/evidence/`, together with
all 14 NVIDIA archive receipts, the generated vendor-tree manifest and four
external-input receipts. Archive hashes are observed-first-fetch receipts, not
independently published NVIDIA checksums. Ubuntu inputs were checked through their
signed package metadata. The source snapshot includes the patched HEAD rather than
describing an unmodified upstream tree.

The builder checked every non-signature installed-APK member, same-partition
configuration, source manifest and integration marker. It retained the two-permission
allowlist and separate app/platform signing identities. The generated updater is
gated to `sif` before writes. Recovery matches the target-files output. The retained
transport record reports 105 safely extracted evidence-member hashes, and shutdown
evidence records `vela-shield-build` off with its persistent disk/caches retained.

The native updater can write the `sif` DTB and `vbmeta_skip`, and can stage the
signed bootloader blob to USP. Existing fuse/key checks remain; none were bypassed.
Its inherited stock NVIDIA/Android 11 fingerprint is not a claim about this image:
built properties report Android 15/API 35, userdebug and test-keys. Upstream
`userdata_size` metadata was not changed, and this OTA has no userdata format/write
action. These facts do not substitute for the correct 8 GB Tube rollback kit and
separately authorized boot/recovery/media testing.

## Follow-through

Reuse only the authorized `vela-shield-build` VM and its retained caches. Keep the
four-job/four-CPU limits, existing VM allocation, unrelated services, host networking
and baseline outputs intact. Freeze the current APK/tooling identity, restage it,
and rebuild into a new output directory using native OTA generation and the same
package/source/signature checks. Preserve the baseline bundle for comparison.

The [remaining image acceptance queue](acceptance.md) has three groups: the current
application image update, verified stock rollback acquisition, and physical
installation/boot/media/OTA acceptance. The physical Shield remains paused; no
power, app, bootloader, wipe or flash action is authorized by this build result.
