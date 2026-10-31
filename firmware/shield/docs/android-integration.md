# Android app integration for Vela firmware

This contract targets the Shield Tube (`sif`) image based on LineageOS 22.2 / Android 15. It adds an unsigned app variant and a narrowly scoped accessibility enrollment adapter. It does not install or flash an image, choose HOME, replace SetupWizard, create an Android device owner, or provision agent/provider credentials.

## Build and package contract

- Build type: `firmware`, initialized from `release`, non-debuggable, with no signing configuration. This is **not a product flavor**.
- Build task from `android/`: `./gradlew :app:assembleFirmware`.
- Input to the image signing/import step: `android/app/build/outputs/apk/firmware/app-firmware-unsigned.apk` (relative to the repository root).
- Application/package ID remains `app.vela.tv`; firmware must not coexist with a differently signed debug install under the same ID.
- `BuildConfig.FIRMWARE` is `true` only for `firmware`; `debug` and `release` receive `false`. `assembleDebug` and its existing `app/build/outputs/apk/debug/app-debug.apk` path are unchanged.
- Only `src/firmware/AndroidManifest.xml` contributes the added permissions and boot receiver. The shared adapter returns immediately on stock build types.
- Sign at image build time with a private Vela app certificate. Keep that signing identity for updates. No platform shared UID or signing material belongs in this repository or APK. The PoC import uses the public development testkey, without platform-signature privileges; production must replace it with a private app key.

Recommended placement is `/system_ext/priv-app/Vela/Vela.apk`, with the allowlist at `/system_ext/etc/permissions/privapp-permissions-vela.xml`. Set the image import module's privileged and system-ext placement accordingly. `/system/priv-app/Vela/Vela.apk` also works, but then **both** APK and allowlist must move to `/system`, with the XML under `/system/etc/permissions/`. A system APK with a system-ext allowlist, or the reverse, does not satisfy permission enforcement. Do not put this APK in `/vendor`; these permissions are not `vendorPrivileged`.

The image build owns this private same-partition XML, containing exactly these added grants:

```xml
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="app.vela.tv">
        <permission name="android.permission.WRITE_SECURE_SETTINGS" />
        <permission name="android.permission.MEDIA_CONTENT_CONTROL" />
    </privapp-permissions>
</permissions>
```

Android 15 declares `WRITE_SECURE_SETTINGS` as `signature|privileged|development|role|installer` and `MEDIA_CONTENT_CONTROL` as `signature|privileged`. The private privileged-app allowlist path is sufficient; a platform signature is unnecessary. Keep `ro.control_privapp_permissions=enforce` in production and resolve missing permissions rather than disabling enforcement. [Android permission definitions](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/res/AndroidManifest.xml), [same-partition allowlist rules](https://source.android.com/docs/core/permissions/perms-allowlist), [Android 15 SystemConfig, including system-ext configuration](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/SystemConfig.java).

The main manifest still requests its existing `INTERNET`, `RECEIVE_BOOT_COMPLETED`, `ACCESS_NETWORK_STATE`, `RECORD_AUDIO`, and `QUERY_ALL_PACKAGES` permissions. `RECORD_AUDIO` retains its ordinary runtime consent flow; do not add an image default-permissions grant for the microphone. The service-level `BIND_ACCESSIBILITY_SERVICE`, `BIND_NOTIFICATION_LISTENER_SERVICE`, and `BIND_JOB_SERVICE` declarations restrict who may bind; they are not additional requested app privileges. No `INJECT_EVENTS`, cross-user, notification-management, device-owner, or root permission is added.

## Owner setup and safe defaults

The owner requested an agent appliance. This PoC firmware sets `BuildConfig.FIRMWARE_ENROLL_BY_DEFAULT=true` and enrolls its control service once after normal Android TV setup and unlock. Stock/debug/release builds set the flag false. An explicit current-user secure setting `vela_firmware_accessibility_opt_in=0` overrides the image default; unknown values also defer. Normal Android TV Settings remains available to disable or manually enable the service. Later revocation is never repaired automatically.

A trusted local firmware provisioner can override the image default by writing the current user's `Settings.Secure["vela_firmware_accessibility_opt_in"] = "1"` after describing screen access and actions. This is a local privileged provisioner handoff, **not** a new exported app API, phone API, model tool, device-policy role, or shell step. A product resource overlay alone does not populate arbitrary secure-setting keys. No additional opt-in UI or provisioner is required for the appliance image. No phone/model route can write this setting.

Enrollment requires all of:

1. The firmware build flag and Android API 35 or later.
2. A preinstalled system package (or its same-signed updated system package), with **both** allowlisted permissions actually granted. A sideloaded firmware APK remains inert even if a development shell previously granted a permission.
3. Android's system user (`UserManager.isSystemUser`, user 0 on this non-headless TV). Secondary users and profiles are never modified; Vela's household profiles do not change Android user identity.
4. The user unlocked, and neither the device nor keyguard locked. Unavailable lock/user services fail closed.
5. `Settings.Global.device_provisioned == "1"` and current-user `Settings.Secure.user_setup_complete == "1"`. Missing/unrecognised values defer. If `tv_user_setup_complete` exists, it must also be exactly `"1"`; absent is permitted for provisioners that do not use this optional TV setting, while `0` defers a restarted TV setup flow.
6. `vela_firmware_accessibility_opt_in == "1"`, or an absent value with this appliance image's enrollment default enabled. An explicit `0` or unknown value defers even on the appliance image.
7. **No value at all** for `Settings.Secure.vela_firmware_accessibility_attempted_v1`. Any existing value, including `0`, empty, or unknown, prevents automatic enrollment.

The bootstrap never writes provisioning flags, opts the owner in, or starts an activity. Keep Lineage's normal setup completion semantics; do not set provisioning bits early to force this adapter to run. The root image integration owns SetupWizard/HOME resolution; its HOME default must not bypass owner setup or require a locked-boot launch by this receiver. Existing `ownHome=false`, empty provider configuration, and owner-controlled external-control defaults are preserved. No purchases, prompts, microphone grants, or controller approvals are accepted by firmware initialization.

## Startup, idempotence and revocation

The sole shared-app hook is `FirmwareBootstrap.onApplicationCreate(context)`, called after `VelaApp` initialization. It schedules work on the main looper so `HandsService` cannot bind before the app's dependencies exist. Firmware also declares a non-exported, non-Direct-Boot-aware receiver for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`. It does not handle `LOCKED_BOOT_COMPLETED`. While enrollment is deferred, process-local observers react to provisioning/consent changes and unlock/user-present broadcasts. These are removed when an attempt finishes. There is no polling, background activity launch, foreground service, or persistent repair job. [Android Direct Boot guidance](https://developer.android.com/privacy-and-security/direct-boot).

At the first permitted attempt, the adapter reads the current enabled-services list, writes and verifies `vela_firmware_accessibility_attempted_v1 = "1"` **before** modifying accessibility, rechecks the gates/list, and appends only `app.vela.tv/app.vela.tv.control.HandsService`. It preserves unrelated entries and their spelling, order, duplicates and empty tokens. Existing short/full Vela component names count as already enabled; the marker is still consumed and the list is left alone. Android 15 observes this list and binds the service, then maintains `accessibility_enabled` itself. The adapter does not write that global accessibility bit, shortcuts, touch-exploration settings, or notification-listener settings. [Android 15 AccessibilityManagerService: SettingsContentObserver, updateServicesLocked, updateAccessibilityEnabledSettingLocked](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java).

The marker lives in per-user secure settings, outside app data. Clearing Vela's app data, rebooting, toggling opt-in, or updating the APK does not authorize another attempt. Android 15's SettingsProvider package-data cleanup removes app-owned **system** settings, not secure settings. Never remove or reset this marker from an OTA, overlay, provisioning repeat, or app migration. The owner can subsequently disable HandsService in TV Settings; boot will not restore it. Manual re-enablement remains available through TV Settings. A factory reset starts a new provisioning lifecycle. [Android 15 SettingsProvider: removeSettingsForPackageLocked](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java).

Exceptions, a failed/unverifiable claim or setting write, or a detected concurrent settings edit terminate the process's attempt; a successfully recorded marker stays consumed even after failed enablement or a crash. No rollback or automatic retry can undo a later owner edit. This can require manual enablement after a failed first attempt, which is intentional.

Settings exposes no public compare-and-set transaction across these keys. The adapter re-reads the list and gates immediately before its single write, but cannot eliminate a final concurrent read/write race. The provisioner must serialize its initial handoff and avoid concurrent accessibility edits. This is a one-time provisioning integration, not a mechanism for maintaining or repairing accessibility defaults. Secure-setting write success/read-back is not a device power-loss durability test.

This gate governs **accessibility enrollment**, not the whole existing app runtime. Starting the boot receiver invokes the existing `VelaApp` lifecycle, including its current phone-service startup and saved external-control restoration. If production requires all network listeners/agent runtime to remain stopped until owner onboarding, a separate coordinated shared-runtime change is required; this adapter does not claim to provide that guarantee.

## Media access without notification enrollment

Existing `DeviceStatus`, `NowPlayingObserver`, and `ProcessMediaSessionTargets` call `MediaSessionManager.getActiveSessions(ComponentName(context, MediaListener::class.java))`; the observer also registers `addOnActiveSessionsChangedListener`. Android 15's service validates component package ownership and then accepts `MEDIA_CONTENT_CONTROL` before testing notification-listener access. Both existing paths therefore work with the image grant and unchanged app code. No notification contents need to be exposed and no notification-listener grant is fabricated. `MediaListener` remains available for stock/manual notification access, but firmware does not need to bind it for these media-session APIs. [Android 15 MediaSessionService: verifySessionsRequest and enforceMediaPermissions](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/media/MediaSessionService.java), [public MediaSessionManager API](https://developer.android.com/reference/android/media/session/MediaSessionManager).

`MEDIA_CONTENT_CONTROL` is an install-time firmware privilege, independent of accessibility opt-in and the notification-listener toggle. Turning off notification access does not revoke that separate media privilege. Provisioning must disclose this capability; Vela's existing control/approval policies still govern actions. If owner-revocable media access is required, an additional app policy gate is needed rather than silently treating notification revocation as privileged permission revocation.

## Verification and image bring-up handoff

The pure JVM tests cover exact list preservation/component identity, idempotence, missing/unknown gates, stock sideloads, multi-user exclusion, first-setup transitions, claim/write failures, detected concurrent edits, relock/consent changes, and revocation across repeated startups. They run in the ordinary shared unit-test source set. AGP 9 enables only the tested build type by default; to run the firmware variant explicitly, use `-Pandroid.onlyEnableUnitTestForTheTestedBuildType=false :app:testFirmwareUnitTest --tests app.vela.tv.firmware.FirmwareBootstrapPolicyTest`.

During this implementation, the policy/tests were compiled directly with cached Kotlin 2.4.21 and run with JUnit 4.13.2 in a temporary directory: **18 tests passed**. The Android adapter and firmware receiver compiled against the installed public Android SDK with both `FIRMWARE=true` and `false` stubs. No Gradle build, shared Gradle output, device operation, or image signing was performed by this implementation worker. The coordinator later completed full APK builds, lint, 416 debug-variant tests and 20 firmware-variant policy tests; see acceptance.md. This verifies scoped syntax and pure behavior, not merged-manifest packaging or behavior on the Shield.

The image owner must still verify the merged firmware APK, unsigned output path, final certificate, APK/allowlist partition match, granted permission state under enforcement, and Android 15 behavior on the actual selected build. Confirm normal setup completes before enrollment, locked boot launches no setup UI from this receiver, unrelated accessibility/listener settings survive, owner revocation persists after reboot/update/app-data clearing, and media observation/control works without notification-listener access. These checks belong to the root's image/device acceptance work; none were run against the user's only Shield by this worker.

Coordinator integration adds the appliance enrollment default, with tests for explicit opt-out and persistent revocation. The root product uses module VelaTv and its matching filenames under system_ext; its concrete paths supersede the illustrative Vela/Vela.apk names above. Full build evidence is recorded in the Shield README and acceptance record.
