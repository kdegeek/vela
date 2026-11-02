# Vela OS development image for Raspberry Pi 5

This builds a complete Android TV SD-card image for the owner's Raspberry Pi 5
(reported as 8 GB RAM). It uses public Raspberry Vanilla/AOSP Android 17 source,
the public Pi 5 kernel prebuilt, and Vela's frozen firmware APK. It does not
unpack or redistribute the enhanced KonstaKANG images.

The custom `vela_rpi5_tv-cp2a-userdebug` product inherits the Pi 5 board, graphics,
media and TV provisioning configuration. Vela is signed with the development
test key and installed as a privileged `system_ext` Home app. The matching
permission allowlist grants `WRITE_SECURE_SETTINGS` and `MEDIA_CONTENT_CONTROL`;
the preferred-Home XML selects Vela while retaining upstream provisioning and
the fallback launcher.

The Vela candidate changes only the pinned display mode to `3840x2160` and
density to `480`. Native 4K application buffers, refresh rate, playback, audio,
CEC and thermal behavior require physical testing. The public media contract
provides H.265 hardware decoding; it does not establish hardware decoding for
H.264, VP9 or AV1.

## Inputs and acquisition

`upstream-lock.json` contains the exact AOSP manifest commit, 20 Raspberry
Vanilla project commits, raw local manifest and primary-source references.
`app-input.json` identifies the retained non-debuggable firmware APK by SHA-256.
`prepare_sources.py` verifies and materializes the manifest, retaining upstream
link files and removal directives. It initializes only a new empty source tree.

On a Linux x86-64 Android build host:

```sh
python3 firmware/pi5/prepare_sources.py --source /srv/vela/pi5/android-17
cd /srv/vela/pi5/android-17
repo sync -c -j4 --fail-fast --no-clone-bundle
```

Use the maintainer's shallow checkout plus the pinned `remove_projects.xml`.
After a successful sync, `vela_pi5.py freeze` saves the complete resolved source
manifest. Staging rejects changed revisions, foreign source edits, modified
managed files, and symlinks in managed destinations. A matching pending
transaction allows an interrupted stage to resume with the same frozen inputs.
It generates the product
under `device/vela/rpi5` and modules under `vendor/vela/rpi5`. It does not inherit
Shield product settings or alter Pi partition geometry.

Google's published host minimums are 64 GiB RAM and 400 GB free disk. The retained
builder has 32 GiB RAM plus 16 GiB swap and initially about 360 GiB free. This is a
bounded attempt below that supported envelope, with four jobs, a four-core CPU
quota, nice 10, `GOMAXPROCS=4`, `NINJA_HIGHMEM_NUM_JOBS=1`, and free-space stop
guards. Preserve the existing Shield checkout and outputs; do not expand or
reconfigure infrastructure silently to make a build fit.

## Build and inspect

Install the normal AOSP build dependencies plus `dosfstools`, `e2fsprogs`,
`fdisk`, `kpartx`, `mtools` and `rsync`. From this repository, run under the
bounded build service:

```sh
bash firmware/pi5/build.sh \
  /srv/vela/pi5/android-17 \
  /srv/vela/pi5/source-snapshot \
  /path/to/app-firmware-unsigned.apk \
  /srv/vela/pi5/build-evidence
```

The wrapper freezes and stages the inputs, builds `bootimage`, `systemimage` and
`vendorimage`, then invokes the upstream Pi assembler. That assembler allocates
loop devices **backed by the new image file inside the build VM**; it never
selects a physical disk. It formats metadata and userdata inside that image.
No standalone `userdata.img` is consumed.

The resulting raw file is
`out/target/product/rpi5/RaspberryVanillaAOSP17-YYYYMMDD-vela_rpi5_tv.img`,
15,360,000,000 bytes, with:

| Partition | Contents | Size |
| --- | --- | --- |
| 1, active FAT32 | Pi boot configuration, kernel, ramdisk and device trees | 128 MiB |
| 2, extended | Container for partitions 5–7 | 3,476 MiB |
| 5, ext4 | Android system, including Vela in system_ext | 3,072 MiB |
| 6, ext4 | Hardware vendor stack | 384 MiB |
| 7, ext4 | Metadata | 16 MiB |
| 3, ext4 | Empty userdata | Remaining space, about 10.8 GiB |

`verify_image.py` inspects the final raw disk image without mounting filesystems.
The report binds its SHA-256, partition geometry, boot contents, installed Vela
APK payload and signature, privilege/Home configuration and source marker.
It also checks the built vendor display mode and density against that marker.
Artifact inspection establishes build contents; it cannot establish a successful
boot or Android runtime behavior.

Signature inspection uses `apksigner.sh` with `VELA_ANDROID_SOURCE` pointing to
the locked checkout. It invokes that checkout's pinned JDK and SDK JAR directly;
the SDK's installed-layout launcher does not locate the JAR in a source tree.

Tooling regression checks:

```sh
python3 -m unittest discover -s firmware/pi5 -p 'test_*.py' -v
```

## First physical test

Writing this image requires a separately identified removable SD card of at
least 16 GB nominal capacity and erases that card. No write is part of the build.
The locked fstab targets SD (`mmcblk0`); USB/NVMe installation is a separate
configuration. Keep a keyboard, HDMI display, suitable Pi 5 power supply and
cooling available for first boot.

After boot, verify HOME provisioning, Vela accessibility enrollment, the two
privileged grants, a real 3840×2160 Vela client buffer, HDMI audio/CEC, H.265
MediaCodec selection, sustained playback, and cold boot. Do not infer these from
a selected HDMI mode or a successful compiler exit.

This is a public-testkey development image. Upstream SELinux is permissive and
userdata encryption, recovery, certified Google services/DRM and a validated OTA
pipeline are not provided by this source recipe. Production distribution also
requires complete license/NOTICE and kernel-source materials; the lock records
the source and firmware provenance, not a completed release audit.

Primary references: [upstream build recipe](https://github.com/raspberry-vanilla/android_local_manifest/blob/a155dda7f0db82d9b8076c1f39f41db4167fcb73/README.md),
[Pi image assembler](https://github.com/raspberry-vanilla/android_device_brcm_rpi5/blob/26b5e928276f155717fc5ff4b28e4b9b854ccbd7/mkimg.sh),
[display configuration](https://github.com/raspberry-vanilla/android_local_manifest/wiki/HDMI-display),
[Android host requirements](https://source.android.com/docs/setup/start/requirements).
