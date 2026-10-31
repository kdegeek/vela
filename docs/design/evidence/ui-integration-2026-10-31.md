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
- Phone presentation shares the language while retaining its request and approval
  behavior. Explicit editor entry also resets stale native validation before
  repopulating each draft. Safari's native dropdown ignored the minimum block size and rendered
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
from `android/`: **passed**. XML reports contain **456 tests**, zero failures,
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
  signatures were independently audited. The phone script has two presentation
  assignments and three explicit editor-entry form resets. All typed-draft,
  conflict, revision and request-lock tests still pass.

Integration corrected Arrange Home's requester creation/caching: row IDs select
remembered requester maps, and retained-cache updates occur after composition
commits. Removed rows are pruned. The new management focus modifier uses the
normal Modifier extension form. No lint baseline or suppression was added.

The independent action review found no regression within its assigned scope.
The navigation review reproduced owner-row/chrome key collisions and replayed
Settings restoration. Both are corrected: owner-row lazy keys use a separate
namespace without changing persisted IDs, and successful child-return focus
consumes its pending request. A new codec/key regression passes. The exact current
Settings effect also passed a Compose Runtime probe for all four origins, ordinary
tab re-entry and a later genuine child return. This probe uses simulated scroll
and focus collaborators; it does not establish live Android focus behavior.
See [post-review corrections](post-review-corrections.md).

Built artifacts at this verification point:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `android/app/build/outputs/apk/debug/app-debug.apk` | 52,925,182 | `dbc51dd22c1f327335809b68b167c2d0885f1a920f523c30c8b3c40d609533a7` |
| `android/app/build/outputs/apk/firmware/app-firmware-unsigned.apk` | 40,118,596 | `1607f23ee611f795a2ff2bf48d37649c83e5730233c59ef72f946437b2b2d660` |

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

Additional Night long-content checks exercised profile loading and an empty-name
save. Native validation blocked the save. Closing and reopening the draft originally
left the old error visible; resetting native form state on explicit entry corrected
it. [Invalid empty draft](vela-phone-night-profile-validation-1280.png) and
[fresh reopened draft](vela-phone-night-profile-clean-draft-1280.png) are actual
window crops with browser chrome removed. The fixture retained revision 10007 and
its original three profiles. The same reset precedes complete field population
in memory and routine editors; their behavior tests pass, without claiming browser
verification of those two editors.

Safari's per-page Light option was exposed, but CUA refused its action with
`element_outside_target_window`, including after a fresh snapshot. Day-theme
selection was not established. A later AX popup value attempt timed out after ten
seconds; the next capture still showed System (Dark). No system appearance/accessibility preference or
browser security setting was changed. The Inspector and test page were closed,
the fixture listener printed its release message, and the named CUA session ended.
Safari retained an empty test window; the existing user window was preserved.

## Remaining acceptance: six groups

1. PR CI on the corrected design commit. Both independent source reviews are
   complete and their two navigation findings are addressed; the owner's
   pivot-review merge gate remains.
2. Native TV appearance/focus/IME/large-text/overlay acceptance. Physical Shield
   interaction remains explicitly paused by the owner; do not reconnect, install,
   launch, navigate or capture it without an explicit resume.
3. Remaining phone rendering: Day/HC/reduced motion/forced-colours/legacy fallback,
   exact 320/390/768 widths, enlarged text/200% zoom, and remaining editor flows.
   Night long-content/profile-validation coverage is recorded above.
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
