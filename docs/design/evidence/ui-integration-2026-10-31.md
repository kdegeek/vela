# Design integration evidence

2026-10-31. Implemented from the Opus design language/component contract with
Astra Extra High implementation and review assignments. This is an application
and design increment; it does not establish complete TV firmware qualification.

## Integrated changes

- Four semantic palettes, shared typography/spacing/glyphs, artwork, cards, rows,
  grids, fields, dialogs, status and agent components. Legacy `VelaDesign` was
  removed after every launcher caller migrated.
- Seven-tab shell, common default/agent tile path, grouped Settings, native Home
  arrangement, management screens, title sheets, agent drafts, Now Playing and
  native Media3 visual adapters. Actions retain the existing guarded controller
  and management paths; TV approvals remain read-only.
- Phone presentation shares the language while retaining its existing script
  behavior. Safari's native dropdown ignored the minimum block size and rendered
  at 24 points; an explicit text-scaled block size increased its observed frame
  to 51 points. Native picker semantics and all existing controls remain.
- Held-input protection binds continuous eligibility across busy changes and
  observes direct, key and indirect input around the existing focus target.
  Orphaned repeats, canceled key-up, overlapping inputs and unused receipts have
  actual Foundation-handler regressions.
- Synthetic loopback phone fixtures and a standalone input-handler harness are
  available for repeatable checks; neither controls a device or uses a provider.

## Final executed checks

`env JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew --no-daemon testDebugUnitTest lintDebug lintFirmware assembleDebug assembleFirmware`
from `android/`: **passed**. XML reports contain **455 tests**, zero failures,
errors or skips. This includes **20 FirmwareBootstrapPolicyTest cases**, rather
than an additional 20 tests. Both lint variants contain zero errors and the same
13 existing warnings. Both debug and unsigned firmware APKs assemble.

There is no `testFirmwareUnitTest` task in this AGP configuration. Attempting that
task failed selection; no firmware-variant execution is claimed. The bootstrap
policy cases above run in the debug JVM suite, and the firmware variant compiles,
lints and assembles through its actual build configuration.

- `node --test android/app/src/test/ui/phone.test.cjs`: **36 passed**.
- `python3 -B -m unittest discover -s dev/design-fixtures -p 'test_*.py' -q`:
  **25 passed**, using real loopback HTTP and closing listeners.
- `python3 dev/design-input-tests/run.py`: coordinator independently reproduced
  **72 cases / 264 assertions / zero failures** against the current production
  node and actual Compose 1.12.1 handlers. This uses simulated platform events,
  explicit pass ordering and limited Android stubs, not live input dispatch.
- Player visual substitutions were independently compiled and audited: removing
  only those substitutions reproduces the committed activity, including every
  playback/retry/cancel callback. Management call arguments and baseline callable
  signatures were independently audited. Existing phone script differs only in
  its two permitted presentation assignments.

Integration corrected Arrange Home's requester creation/caching: row IDs select
remembered requester maps, and retained-cache updates occur after composition
commits. Removed rows are pruned. The new management focus modifier uses the
normal Modifier extension form. No lint baseline or suppression was added.

Built artifacts at this verification point:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `android/app/build/outputs/apk/debug/app-debug.apk` | 51,761,770 | `91dfa27da5491ad41e6db2ab652c174980bd91a77356fa6f7e6900589c891581` |
| `android/app/build/outputs/apk/firmware/app-firmware-unsigned.apk` | 40,118,540 | `0872887194c0f64c93937d7ae6bafb7357e3746a4de32bdf837ef9e70d7ef087` |

## Observed browser behavior

The coordinator used native Safari in the **Nate’s Mac mini** CUA Space with the
unchanged shipped page and public synthetic fixture data. At 1280 CSS pixels,
the two-column page, running transcript, approval question and both answer buttons
rendered. The Inspector body box model confirmed the width. Clicking Decline
through the actual page cleared the fixture's exact pending ID, changed running
to false, and visibly returned the page to Connected with the decline transcript.
This is phone-fixture integration, not Android approval-broker qualification.

[Actual Night approval capture](vela-phone-night-pending-1280.png) is cropped only
to remove browser chrome and personal bookmark titles. Its source window was
1280 × 960 points; it is not a full-page export. Earlier actual 393 × 852 preset
and disconnected navigation checks are in [initial browser evidence](phone-browser-initial.md).

Safari's per-page Light option was exposed, but CUA refused its action with
`element_outside_target_window`, including after a fresh snapshot. Day-theme
selection was not established. No system appearance/accessibility preference or
browser security setting was changed. The Inspector and test page were closed,
the fixture listener printed its release message, and the named CUA session ended.
Safari retained an empty test window; the existing user window was preserved.

## Remaining acceptance: six groups

1. Integrated source review and PR CI on the new design commit. These can proceed
   from the existing source; the owner's pivot-review merge gate remains.
2. Native TV appearance/focus/IME/large-text/overlay acceptance. Physical Shield
   interaction remains explicitly paused by the owner; do not reconnect, install,
   launch, navigate or capture it without an explicit resume.
3. Remaining phone rendering: Day/HC/reduced motion/forced-colours/legacy fallback,
   exact 320/390/768 widths, enlarged text/200% zoom, long content and editors.
   The native Light-menu binding refusal is documented above.
4. Live model/service/media acceptance and parent gesture/accessibility timing,
   including actual playback and paused-session controls. Existing regression
   and simulated transport evidence does not establish these outcomes.
5. Full OS compilation/package provenance and an incremental image containing
   this committed UI APK. The separate builder assignment owns baseline OS work;
   these application checks do not prove its current progress or completion.
6. Stock recovery kit, authorized physical image install/boot and release
   qualification, including 4K/DRM and signing. No unlock, wipe or flash occurred.

Groups 1, 3 and 5 have independent work available. Physical portions of groups
2, 4 and 6 require a later owner-approved testing window; this document requests
no new permission and does not change the Shield pause.
