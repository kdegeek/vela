# Approved glass implementation — actual renders

The owner approved the [warm cinematic glass concepts](../../explorations/liquid-glass-20261031/README.md).
The implementation now runs in the Android application and the production phone
page. The [interactive capture gallery](index.html) contains **14 actual captures**;
these are separate from the earlier HTML concepts.

## Implemented and integrated

- Shared smoke/pearl materials, bundled Manrope, independent focus rings, flat
  controls, bounded softened backdrops and an opaque high-contrast branch.
- Full-bleed Home atmosphere, compact navigation, contextual Ask, named title
  actions, grouped Home Undo feedback, and existing Now Playing presentation.
- Person-first Settings and profile editors, stable Save/Back actions, readable
  language choices with custom-tag preservation, and matching secondary pages.
- The production phone companion shares the material and typography. Its existing
  authenticated APIs, prompt IDs, locks, revisions and unsaved drafts remain intact.

The governing document is [vela-glass-implementation-contract.md](../../vela-glass-implementation-contract.md).
The earlier flat-design documents are marked historical. No dependency upgrade,
API/CSP change, fictional catalog or reference photograph ships in the APK.
Manrope and its OFL license are bundled locally.

## Verification

From `android/`, with JDK 21:

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew --no-daemon \
  testDebugUnitTest lintDebug lintFirmware assembleDebug assembleFirmware
```

Final run: **BUILD SUCCESSFUL**, **512 JVM tests across 75 suites**, zero failures,
errors or skips. Both lint variants report **zero errors and 13 warnings**, matching
the previous warning count. Both APKs assemble; `assembleFirmware` is the unsigned
application variant, not a completed operating-system image.

Additional checks:

| Check | Result |
| --- | --- |
| `node --test android/app/src/test/ui/phone.test.cjs` | 40 passed |
| `python3 dev/design-input-tests/run.py` | 72 cases, 264 assertions, zero failures |
| `python3 -B -m unittest discover -s dev/tv-fixtures -p 'test_*.py'` | 23 passed |
| `python3 -B -m unittest discover -s dev/design-fixtures -p 'test_*.py'` | 25 passed |
| `git diff --check` | Passed |

The input harness executed the actual unchanged activation adapter and installed
Foundation handlers using its documented platform stubs. It was run before the
final counter-only presentation adjustment; no activation behavior changed.
The fixture suites exercise real loopback HTTP, not live service interoperability.

A separate read-only integration reviewer found no proven actionable regressions
within backdrop bounds/cancellation/ownership, overlay control behavior, management
and Undo callback ownership, or the embedded phone FontFace/CSP path. This was a
source review, not additional runtime acceptance.

## Native and browser observations

TV captures use the locally installed debug APK on `vela-gtv-4k`, Android 14,
1920 × 1080 pixels, through the thread's bound device command. The target has
compile SDK 37, min SDK 28 and target SDK 36. This does not establish Android 11
hardware performance. Phone captures use the unchanged production page in Safari
on the local iOS 27 simulator at 402 × 874 CSS pixels; the CLI exported 1× PNGs.

| Captures | Observed behavior |
| --- | --- |
| [Home Night](home-focused-night.png), [Day](home-populated-day.png), [unfocused Night](home-populated-night.png) | Crisp reference art, all seven navigation tabs, full Household name, trailing counters and remote focus |
| [Ask Night](ask-night.png), [Day](ask-day.png) | Native translucent activity over Home; input and Back remain reachable; no model submission |
| [Profile Night](profile-editor-night.png), [Day](profile-editor-day.png) | Actual existing profile, readable language choices, Save/Back visible; no profile write |
| [Details](details-night.png) | Loaded native metadata, exact title and named Resume action |
| [Undo](home-undo-night.png) | Native Move down persisted `apps, resume, latest, watchlist`; native Undo restored the exact pre-test layout JSON |
| [Opaque fallback](home-opaque-night.png) | High contrast and Reduced motion enabled from TV Settings; opaque canvas/boundaries observed, then preferences restored |
| [Player](player-night.png) | Native Resume launched the generated H.264/AAC clip at its fixture resume point; elapsed time advanced and the media remote paused it; Back returned to Home |
| [Apps-only](home-apps-night.png) | Original settings restored, fixture connection removed, no stale media art remains |
| [Phone Night](phone-approval-night.png), [Day](phone-approval-day.png) | Actual page with synthetic HTTP data; Decline sent the exact displayed prompt ID, cleared it and returned the fixture to idle in both themes |

Visual inspection found and corrected three concrete integration issues: the normal
Household name was truncated, row counters stopped inward of the safe edge, and a
Day counter lost contrast against dark artwork. The name now fits, counters align
at the trailing edge and use an opaque canvas backing. Existing palette tests
check the counter's `inkMuted`/`canvas` pair. These checks do not certify arbitrary
translucent composites or every enlarged-text state.

The [photographic fixture](../../../../dev/tv-fixtures/README.md) serves four
allowlisted public reference images with their [existing provenance](../../explorations/liquid-glass-20261031/mocks/ASSETS.md).
Metadata remains visibly labelled FIXTURE. The media sample is a generated
60-second test pattern with synthetic captions. Neither art nor the invented
catalog is copied into production assets. The phone fixture is RAM-only and is
separate from Android's approval broker; its decisions cause no real device effect.

All TV PNGs here were recaptured from the final debug APK after visual corrections.
The phone HTML was unchanged throughout these captures. Images are unmodified;
no live pairing QR or real credential is included. [SHA256SUMS](SHA256SUMS) records
the capture identities.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Debug APK | 54,194,336 | `1da6d8fe4b96eeca5ac56f6e1ea834df82721d517ed9f94288aae2d740045f84` |
| Firmware unsigned APK | 40,353,268 | `330c01bf1eaa7ad02397ed12b74863d120c9fa870665103acd099d002edeedde` |

## Cleanup and limitations

Both verification passes restored the exact original persisted settings and layout,
including theme, contrast and motion, using private backups that were then deleted.
No real provider request was made. Owned fixture listeners and emulator forwarding
were removed. Safari was closed, its original light appearance restored, and the
simulator booted for this task was shut down. The existing TV emulator remains
available for the owner. The physical Shield was never connected, installed,
launched, controlled or captured.

CUA's existing relay route refused with `ResourceExhausted` (monthly remote-hours
quota); no new Space, subscription, driver installation or host setting was used
to bypass it. Local simulator Safari provided the approval proof. After scrolling,
the simulator's XCTest fallback produced drifting element coordinates: an editor
target reached an unrelated recovery confirmation, which was canceled. No recovery
ran. Populated phone editor navigation is therefore **not** accepted by this run.
The TV scroll-until helper also incorrectly claimed an end before any gesture;
a fresh snapshot and one ordinary scroll revealed Arrange home successfully.

**Four acceptance groups remain**, separate from the implemented visual change:

1. Physical Shield/API 30 focus, IME, accessibility timing and performance. Testing
   remains paused by the owner; it requires permission to resume TV interruption.
2. Remaining emulator states: native working/approval and cross-app Now Playing,
   enlarged text, exhaustive focus/IME and motion observation. These can continue
   locally without hardware permission; this capture set does not cover them.
3. Phone populated editors, keyboard/screen-reader, zoom and OS accessibility
   rendering. Behavior tests and disconnected width previews pass; the observed
   simulator coordinate problem and CUA quota limit the current visual evidence.
4. Full firmware build/boot/recovery and live model/media/home services, including
   DRM/4K release qualification. Existing builder and installation approval gates
   remain in force. An APK build and a local direct-play clip do not establish them.
