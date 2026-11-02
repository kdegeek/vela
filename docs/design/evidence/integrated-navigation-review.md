# Integrated navigation review

2026-10-31. Independent review leaf; baseline
`0809edfb31d2fac467dc55e2b67fd93d9cf777df`, reviewed head
`9af13c8b29f913f464585eaf72209c4b86132db3`.
Scope: native composition, navigation, focus/restoration, constraints and visual
state in `design/**` and `launcher/**`; relevant player dialog focus only.
Governing inputs: `vela-design-language.md`, `vela-component-contract.md`, and
`ui-integration-2026-10-31.md`. **Two findings remain for the coordinator.**

Coordinator follow-up: both findings below were subsequently corrected and
verified as recorded in [post-review corrections](post-review-corrections.md).
This note retains the original reviewed-head evidence.

## P1 — Namespace owner row keys separately from UI items

Locations: `launcher/HomeActivity.kt:89,95,98` and
`launcher/ArrangeHome.kt:76,80,92` (paths relative to
`android/app/src/main/java/app/vela/tv/`).

Home uses an unmodified owner row ID as its lazy key and adds an unconditional
`"arrange"` item. Arrange Home similarly combines unmodified IDs with `"status"`,
`"defaults"` and VelaPage's `"vela-page-header"`. These are all valid row IDs:
`layout/HomeLayout.kt:114–121` checks uniqueness only among owner rows and accepts
letters and hyphens. A single nonempty app-format pinned row named `arrange`
therefore produces `[arrange, arrange]` in Home, including its initial skeleton
state. Rows named `status`, `defaults` or `vela-page-header` collide in the editor.

Minimal trigger: use an otherwise valid one-row layout with one of those IDs;
open Home for `arrange`, or open Arrange Home for the other three. When both
items are subcomposed in a measure pass, Compose rejects the duplicate key,
preventing that surface from rendering. This is a source-proven failure, not a
live-device crash claim. Installed UI 1.12.1 `SubcomposeLayout.kt:611–616`
explicitly throws `Key "…" was already used` for this case.

Executed probe: the current compiled production `Layouts.decode` accepted all
four layouts; installed Foundation 1.12.1 `LazyListIntervalContent` returned the
duplicate keys from the adapters' item declarations. No actions were activated.

Bounded correction: prefix **all owner-row lazy keys** in Home (skeleton and
loaded branches) and Arrange Home, e.g. `"row:${row.id}"`; retain raw row IDs for
layout edits and requester lookup. This avoids changing the accepted schema or
persisted layout. Add a regression covering valid IDs matching UI-owned keys.

## P2 — Consume Settings restoration after the child return

Location: `launcher/SettingsHub.kt:27–32`; caller state in
`launcher/TvShell.kt:39–40,79–82,98–113`.

Trigger: Settings → Profiles → Back, then navigate to another tab and activate
Settings again. The first Back increments `restoreSettings`; nothing consumes or
clears it. Settings leaves composition on tab changes, so its
`LaunchedEffect(restore)` starts again on re-entry even though the counter has not
changed. It scrolls to the old origin and requests its focus, pulling focus off
the Settings tab and overriding the ordinary saved position. The same occurs
for Routines, External control and Arrange Home.

Executed probe: the exact production effect block ran under Compose Runtime
1.12.1 with a headless applier and recorded scroll/focus collaborators. The child
return recorded `[scroll:4, focus:profiles]`; ordinary re-entry with unchanged
`restore=1` recorded the same pair again. This proves effect replay; it does not
claim a rendered focus traversal test.

Bounded correction: represent child-return restoration as a pending request and
consume it after successful placement/focus, or persist the last handled request
across destination disposal. Ordinary tab activation must not replay it; a later
genuine child Back must still restore that child's originating row.

## Evidence and limits

Private probe files and results:
`/var/folders/tc/f5wf7p2n68z7htvy4dr283sw0000gn/T/vela-integrated-navigation-review-lzatacsn/`.
`Probe.kt` compiled with installed Kotlin/Compose compiler 2.4.21; final execution
exited 0. Only Android logging/tracing were stubbed for the runtime-only probe;
no UI was rendered. Cached dependency hashes were checked against the existing
input-harness manifest. The input event handler/harness was not re-audited or run.

Other inspected paths have the intended source structure: Home retains stale
rows during in-flight refresh; tab Up/Back routes are explicit; row/button
requesters survive Arrange Home moves through the updated immutable map and
committed retained cache; removal/default reset targets the safe header; editor
entry waits for Back/Hide placement; confirmations and title sheets request
Cancel/Back; the dock tests session presence rather than playing phase, with
permission-only attention in Settings/details. These observations are not
device acceptance. No additional newly introduced defect was proven in scope.

The coordinator's reported builds/tests were read, not rerun. Physical D-pad and
IME behavior, visual bounds/focus rings at short heights and 1.3 font scale,
actual restoration/scrolling and paused-session rendering remain unexecuted.
The physical Shield remains paused by its owner. No subagents, peer interactions,
Git mutations, shared Gradle, device/browser/GUI/SSH, infrastructure, firmware or
credential operations occurred. Only this new repository note was written;
production corrections and PR work remain with the coordinator.
