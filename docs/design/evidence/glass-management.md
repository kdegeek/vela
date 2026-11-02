# Glass management implementation · 2026-10-31

Implementation follows `docs/design/vela-glass-implementation-contract.md` and the
approved `mocks/index.html` editor/apps composition. Inspected the actual
`concept-editor.png` and `concept-apps.png` renders. This is production Compose
presentation backed by existing application state, without concept catalog data,
photography, recommendation rows, or simulated outcomes.

## Implemented

- Management, Settings, Search, House, Activity, and app/library grids use the
  fixed `VelaAmbientBackground` / `VelaGlassSurface` APIs. Related management and
  settings controls share a dense reading material; fields/buttons stay flat.
  Destination headings use the compact title scale.
- Profiles lead with people and Preferences / What Vela remembers. Refresh stays
  available; household recovery remains a confirmed maintenance action below
  people. Profiles still have no PIN; Home and My list remain shared.
- The profile editor has a person summary beside one glass form at normal TV
  width, bounded fields, paired language controls, and Back/Save outside the
  scrolling fields. At short heights (including IME) or enlarged text, the form
  scrolls with safe actions at both ends. Routine and memory editors use the
  same action-region layout.
- Language pickers expose readable choices, subtitle intent, and a custom ordered
  tag field. Existing regional/custom tags and casing survive display and Save.
  Re-selecting the current tag does not canonicalize it. Explicit replacement is
  a draft edit only. Existing `ManagementForms` parsing, backend validation,
  serialization, and explicit Save remain authoritative. Autoplay is still only
  the existing next-episode Boolean; off does not claim an approval workflow.
- Management request feedback occupies a reserved status region. Completion or
  errors do not insert a new row above the active form/list. Details reveals the
  full existing message, including revision-conflict/draft guidance.
- Settings restores the requested origin from the actual keyed section order and
  consumes the existing request after successful focus. No persistent replay flag
  or unrelated focus effect was introduced. The decorative card specimen gallery
  is removed from the normal preferences flow.
- Routine schedules, enable/disable/run/restart/cancel/delete, durable history,
  request keys, memory export/sharing/clear/delete, external control enable/revoke,
  one-time credential reveal/discard, and their exact facade arguments remain.
  Activity retains real runner state and calls; its approval notice stays read-only
  and separate from ordinary owner confirmations.

## Focused verification

Ran `/tmp/vela-glass-management-check.py` using installed Kotlin 2.4.21 and its
serialization/Compose compiler plugins, Java 21, JUnit 4.13.2, serialization 1.12.0,
and coroutines 1.11.0. All outputs are private temporary artifacts; no Gradle task
or shared build-output write was used.

- `GlassManagementLanguagesTest`: 4 passed. Real form + JSON round trips preserve
  ordered regional/custom tags, casing, provider/library references, and restrictions;
  explicit choices alter only the selected preference. Existing backend validation
  still rejects duplicate tags, malformed tags, and more than eight languages.
- Existing `ManagementFormsTest`: 5 passed (profile invariants, schedules, and
  request-key behavior).
- Existing `ManagementDesignTest`: 5 passed (confirmation preparation, single
  flight, conflict guidance, cancellation, and routine-state tones).
- Scoped Compose compilation passed for the six assigned launcher files, the three
  new `Glass*` helpers, and the current shared `Glass.kt` / `GlassMaterial.kt`, using
  installed Android/Compose artifacts and the pre-existing app compile artifact for
  unrelated application symbols. This is not a combined app build or linkage check.

Final private verification output:
`/var/folders/tc/f5wf7p2n68z7htvy4dr283sw0000gn/T/vela-glass-management-k5f8ex8o/`.
An initial private harness run selected coroutines 1.9 before 1.11 and failed with
`NoSuchMethodError`; pinning the installed project 1.11 test/runtime jars resolved
that harness mismatch without any repository dependency change.

## Compatibility, dependencies, and remaining acceptance

No new runtime dependency or dependency upgrade. Uses existing Compose APIs and
project compileSdk 37 / minSdk 28; introduces no RenderEffect or platform blur.
The shared foundations own palettes, typography, material, contrast, and focus-ring
rendering. `PairCard` remains an unchanged call into the shell owner's component.
ManagementForms, HomeData, models, backends, phone APIs, effect admission, and
serialization schemas were not edited.

Compilation and pure tests are not visual acceptance. The coordinator still owns
combined builds/tests and native emulator Day/Night/high-contrast inspection,
including baseline Back/Save visibility, enlarged-text/IME scrolling, long/custom
language tags, full error reading, Settings return focus, and routine/secret flows.
No GUI/device actions occurred. The physical Shield was never contacted. No Git,
PR, server, credential, infrastructure, or firmware operation was performed.

Coordinator follow-through: [combined checks, actual captures and remaining limits](glass-native-20261031/README.md).
