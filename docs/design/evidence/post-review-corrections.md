# Design review corrections

2026-10-31. Coordinator follow-up to the independent reviews of `0809edfb..9af13c8`.
The physical Shield remains paused. No Android rendering or live D-pad acceptance
is claimed.

## Owner row IDs

The navigation reviewer reproduced valid owner IDs colliding with Home's
`arrange` key and Arrange Home's `status`, `defaults` and `vela-page-header` keys.
The coordinator confirmed that the real layout codec accepts these names and
that the lazy adapters previously used raw IDs beside their chrome keys.

Home's skeleton and loaded branches, and Arrange Home, now use `ownerRowKey`.
It gives owner rows a separate `row:` namespace. Persisted IDs, edit operations,
requester lookup and action contents retain their original identities.
`LayoutEditsTest.validOwnerIdsCannotCollideWithHomeOrEditorChrome` passes with
all seven current chrome names through the real codec, checking uniqueness of
the combined keys and preservation of stored IDs.

## Settings return focus

The old counter remained positive after returning from a child. Re-entering the
Settings composition replayed its effect. The shell now holds a pending origin,
clears it after successful placement/focus, and discards it on explicit tab
activation or entry to a new child. A later child return creates a new request.

The coordinator extracted the exact current `LaunchedEffect` block from
`SettingsHub.kt` into a private Compose Runtime 1.12.1 probe with recorded scroll
and focus collaborators. Profiles, Routines, External control and Arrange Home
each restored once; ordinary Settings re-entry added no events; a later genuine
Profiles return restored again. Final execution exited zero. Probe artifacts:
`/var/folders/tc/f5wf7p2n68z7htvy4dr283sw0000gn/T/vela-navigation-correction-w5pot8j6/`.
Initial probe setup needed a removed unused application import and explicit
per-entry origin capture; those setup failures are not production failures.
The probe does not render Android views or establish physical focus timing.

## Native phone validation

Actual Safari in the **Nate’s Mac mini** CUA Space reproduced stale `:user-invalid`
state after an empty-name Save, Close editor and New adult profile. Explicit
entry now calls the native form reset before restoring every field. This also
applies to memory and routine editor entry; polling, reload and conflict paths
do not reset open drafts. The Node DOM harness implements standard reset behavior.

Actual repeated profile flow showed the required-name error on Save and a clean
field after closing/reopening. The fixture retained revision 10007 and three
profiles. The [invalid draft](vela-phone-night-profile-validation-1280.png) and
[reopened draft](vela-phone-night-profile-clean-draft-1280.png) are cropped actual
window captures, with browser chrome removed. No memory/routine browser outcome
is claimed. Their existing typed-body and conflict tests remain green.

The Vela test tab was closed, the public RAM-only fixture printed its release
message, and the named CUA session ended. Safari retained an empty test window;
the existing user window was preserved.

## Executed combined checks

- `env JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew --no-daemon testDebugUnitTest lintDebug lintFirmware assembleDebug assembleFirmware`:
  passed; 456 JVM tests in 68 suites, zero failures/errors/skips; both lint variants
  zero errors and 13 existing warnings; both APKs assemble.
- `node --test android/app/src/test/ui/phone.test.cjs`: 36 passed.
- `git diff --check`: passed.

The unchanged 72-case input harness and 25-test fixture suite passed at the prior
design integration point; these corrections do not edit their owned production
input or fixture code. Remaining release and visual gates are tracked in
[design integration evidence](ui-integration-2026-10-31.md).
