# Current application Shield Tube image

The retained builder completed the current-application rebuild on **2026-11-01
at 00:22:54 UTC**. Integration is frozen at
`0bc9785bf3e9dd1dff24fe033d815b083b718a3c`; the application is unchanged from
`ce8613cc6cbd0d4e1b21b74e73500dfd02dfcf6c`, including the approved glass UI and
filled-input correction. The independent systemd build continued after its agent
hit a provider quota error. Native compilation reports **12:49**; the full
build/inspection/OTA-packaging phase ran from 00:03:51 to 00:22:54 UTC.

This is an **unboot-tested development image signed with public testkeys**.
Packaging and signature checks do not establish physical installation, recovery,
4K/DRM playback or production suitability. The physical Shield was not contacted.

## Bundle and verification

Local artifacts are excluded from Git:

```text
firmware/shield/artifacts/current-0bc9785-20261031T235534Z/
  vela-sif-development.zip
  recovery.img
  source-manifest.xml
  provenance.json
  SHA256SUMS
  TRANSPORT_VERIFICATION.json
  VM_SHUTDOWN_VERIFICATION.json
  INSPECTION_FOLLOWUP.md
  build-evidence/
  evidence/
  task-bin/
```

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Development OTA | 646,479,900 | `585aa023508d03bd55e655942285d5a639516ea743f620c849fbedc24d564a47` |
| Recovery | 22,065,152 | `0e76fd32d85dce7ef20f7d5b2a1da4cc42f91b50570a60b2228bd6960e84f56b` |
| Source manifest | 289,298 | `32a6c75ca8135f7708d397418a561ed969ac1793d907c2d8e2b2e08374727919` |
| Provenance | 1,844 | `0ef3745de374ac355c1d8a313fc9e5b709eab40b32871c3799510b533a564d5f` |

The inspected target-files archive remains on the builder: 1,458,513,851 bytes,
SHA-256 `5a8c61b4819fafbedafa64f887da37ceae86f316f54224abb545195d92ec649f`.
The immutable input APK is 40,353,268 bytes, SHA-256
`b1237c0e1ca32efa8c6e3252aa0198d88397fee652f5e20efddf67b65b34db2f`.

The completed checks establish:

- All **278 non-signature APK members** match the frozen input, including resources,
  manifests and ARMv7 libraries. Both configuration XMLs and the installed input
  marker match the frozen repository. The coordinator repeated the payload and
  configuration comparisons on the Mac after transfer.
- All **1,171** embedded source revisions match the retained manifest, which is
  byte-identical to the [baseline](baseline-build-20261031.md). Preflight rehashed
  14 vendor archives, four retained external inputs and 521 vendor-tree entries.
  The two recorded foster source fixes and their provenance remain unchanged.
- Boot and recovery bytes agree across target-files, OTA and built outputs.
  The updater script and all **28 native installer/firmware payloads** are
  byte-identical to baseline. Tube-only assertions precede its 32 write/run actions;
  there is no userdata write/format action. SELinux and privileged-permission
  enforcement remain enabled in the inspected configuration.
- The installed APK signature verifies with the public test certificate, distinct
  from the platform certificate. The OTA whole-file CMS signature verifies against
  the pinned public certificate with trust-chain and signature checks enabled.
  The coordinator repeated CMS verification on the Mac after transport.
- All bundle and transport checksums match. The evidence archive was checked for
  duplicate/escaping paths and unsafe entry types before extracting and verifying
  **57 evidence-file hashes**. The original baseline bundle was preserved.

The original prepared CPIO inspector rejected the boot ramdisk's second empty
`dev` directory. Separate diagnostics found exactly the same two directory entries
in the baseline, differing only in generated inode number; neither recovery has
duplicate names. The original script is preserved. Its revised version accepts
only that one exact, baseline-matched empty-directory case; all other duplicate
and path checks remain in place. No image bytes were changed for this diagnostic.

Upstream `userdata_size=10099646976` metadata is retained. Neither unchanged
geometry nor the absence of OTA userdata writes proves fit or correct recovery
formatting on the physical 8 GB Tube. That remains part of physical qualification.

The active-unit receipt records the four-CPU quota, nice 10 and six-hour timeout;
the observed kernel command ends in `-j4`. After inspection and transfer, no owned
Vela task services or build processes remained. The coordinator gracefully shut
down the exact builder VM and verified it **shut off**, with autostart disabled and
its disk/caches retained. No unrelated service or VM allocation was changed.

## Stock rollback prerequisite

The exact NVIDIA catalogue entry remains
[`nvidia-shield-android-tv-2019-recovery-os-image-9-2-1`](https://developer.nvidia.com/shield-open-source):
**2019, 8 GB**, recovery version **9.2.1**, dated 2025/05/12. The independently
inspected current catalogue JSON is 1,465,351 bytes, SHA-256
`55b1c808bda7541f581b636332b906d1c5bdbca60be3193ff9f99e6b649a7b2c`.
It publishes no checksum for this recovery entry.

Both the [exact archive](https://developer.nvidia.com/downloads/assets/mobile/secure/images/shield_atv_2019/9.2.1/nv-recovery-image-shield-2019-atv-9.2.1.zip)
and [NVIDIA instructions](https://developer.nvidia.com/downloads/assets/mobile/secure/images/shield_atv_2019/9.2.1/How-To-Flash-Recovery-Image-shield-atv-2019.txt)
returned an unauthenticated HTTP 302 to NVIDIA login with zero content bytes on
2026-11-01. No authentication was attempted. The stock recovery archive and its
internal model/image identities therefore remain **unverified**. A stock OTA or
Lineage ZIP is not a substitute for this kit.

Local acquisition evidence is retained under
`firmware/shield/artifacts/rollback-20261101/VERIFICATION.md` with primary-source
snapshots. Completion requires the owner to download the exact archive and its
instructions after NVIDIA authentication and place the files in that ignored
directory. Credentials/cookies are not needed. Once supplied, hash and inspect
the archive, its included instructions, image identities and model identifiers.

Two image gates remain: verified stock rollback acquisition, and separately
authorized physical installation/boot/media/OTA/recovery acceptance. Physical
Shield work remains paused; this result authorizes no unlock, wipe or flash.
