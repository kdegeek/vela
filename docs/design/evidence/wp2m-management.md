# WP2m — native management adoption

Implementation leaf, 2026-10-31. Governing inputs: the complete current
`vela-design-language.md` and `vela-component-contract.md`, including coordinator
ownership and the physical-Shield pause. No contract or foundation file edited.

## Changes

- `ManagementPage.kt`: `ManagementList` uses `VelaPage` with the Settings
  breadcrumb; `ManagementField` wraps `VelaTextField` with the original input
  limits and multiline bounds. Actions retain their coroutine, lock, exception,
  and confirmation behavior, with shared working/success/error presentation.
- Confirmations use `ConfirmDialog`, with Cancel initially focused and Danger
  styling for deletion, clearing memory, and external credential revocation.
  The confirmation is composed outside the lazy feedback item so scrolling that
  item out of composition cannot prevent a confirmation from appearing.
- Safe editor entry focus waits until Back/Hide export is placed and requests
  focus once per editor/page entry. Long editors remain full pages, with Back
  and Save at the end. No management operation is attached to focus or layout.
- Profile/preferences and explicit memory use shared list rows, choice groups,
  toggles, section headers, fields, statuses, and flow-layout action groups.
  Existing draft state, revisions, restrictions, memory scope, confirmations,
  pagination, and private export callbacks are retained.
- Routines use the same controls, a glyph and explicit word for each history
  state, and tabular timestamps. Only SUCCEEDED receives success presentation;
  queued/canceling/interrupted/missed states need attention. Schedule parsing,
  validation, policy binding, cancellation, and retry/request-key callbacks are
  unchanged.
- Prose is at the body type size within the shared reading width. A preview
  that exceeds four lines gets Read more, opening the full text in `VelaDialog`
  with Back focused and the foundation's D-pad-scrollable reading body. Nothing
  is silently discarded from long task, memory, or error text.
- External control retains its original enable/revoke/status callbacks. The
  credential remains ephemeral `remember` state, masked by default in a read-only
  `VelaTextField`, with the existing reveal/mask and discard operations. No real
  credential was accessed, displayed, exported, or included in this evidence.

## Executed checks

All compiler/test outputs are private to `/tmp/vela-wp2m-check-d8h9trn4/`.
Shared Gradle outputs were only read to copy a baseline app API jar; Gradle was
not run. Current design sources were compiled directly, without design stubs.

1. `python3 /tmp/vela-wp2m-check-d8h9trn4/compile.py` — passed, exit 0.
   Compiles all current `design/*.kt`, the three owned management sources, and
   the unchanged `ManagementForms.kt` against the baseline app API and installed
   dependencies. Uses JDK 21, Kotlin/Compose compiler 2.4.21, SDK 37, Compose UI /
   Foundation 1.12.1, Material3 1.4.0, Media3 1.11.1, and Coil 3.6.3.
2. `python3 /tmp/vela-wp2m-check-d8h9trn4/tests.py` — passed on the final Kotlin
   sources, exit 0; **JUnit OK (10 tests)**. This recompiles the same production
   sources plus the tests. Five unchanged `ManagementFormsTest` cases cover
   profile restriction/provider preservation, adult defaults, schedule parsing
   and rejection, and idempotent retry keys. Five new `ManagementDesignTest`
   cases cover deferred confirmation work, single-flight busy actions, explicit
   retry after failure, cancellation without false completion, and honest
   routine-history status tones. Final compiler log has no diagnostics.
3. `python3 /tmp/vela-wp2m-check-d8h9trn4/audit.py` — passed. Compares all
   `management.*(...)` call arguments with HEAD; confirms no forbidden M3
   controls, color/shape literals, or TV approval-answer calls in these files;
   uses `javap` against the final compiled classes to verify all existing
   callable JVM signatures in `ManagementPageKt`, `ProfileManagementKt`,
   `RoutineManagementKt`, and `ManagementActions` remain present. Also checks
   trailing whitespace in all four owned Kotlin files.
4. Scoped `git diff --check` for the three production files, new test, and this
   evidence note — passed. The audit separately checks the untracked test's
   whitespace; no Git mutation was performed.

The static callback/signature audit complements compilation and tests; it does
not constitute runtime UI or end-to-end facade verification.

## Pending coordinator checks

- Combined Android checks after all writers finish:
  `./gradlew --no-daemon testDebugUnitTest lintDebug lintFirmware assembleDebug assembleFirmware`.
  This includes the broader owner-management, profile, memory, and routine
  regression suites; this leaf did not execute those suites.
- Actual TV visual/navigation acceptance after the owner resumes physical
  testing: Night, Day, both high-contrast palettes, 1.3 font scale, short
  viewport, Back/Cancel focus, lazy-row confirmation delivery, focus
  restoration, long-text reading, native field/IME behavior, disabled/busy
  held-key and pointer suppression, and reduced motion. No screenshot or
  device acceptance is claimed. The physical Shield was not touched.
- Root-owned Settings destination navigation, combined browser/phone QA,
  and PR delivery remain with the coordinator. No browser, GUI, device, SSH,
  infrastructure, credential, firmware, or Git mutation action was performed
  by this leaf.
