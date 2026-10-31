# Glass entertainment shell implementation — 2026-10-31

Implementation follows `docs/design/vela-glass-implementation-contract.md`, the
approved `mocks/prototype.html`, and the actual Home Night/Day, Undo, Ask, details
and editor render exports inspected by this leaf. This is production Compose
presentation using current application state, not a mock catalog.

## Changes in the assigned files

- `TvShell.kt`: Home's full-bleed `VelaBackdrop` now belongs to the shell behind
  navigation and Home. Sparse Home and other destinations use the shared ambient
  background. `remember(revision) { HomePresentation() }`, profile identity,
  destination state, existing callback routes and admission paths remain intact.
- `ShellChrome.kt`: one shared glass navigation plane, sail mark, all seven
  labeled destinations, separate selected-tab underline and remote focus perimeter,
  actual profile name/initial, and a fixed 134dp agent slot. Working/approval still
  opens Activity; idle still opens Ask. Navigation scrolls when constraints or
  enlarged text require it.
- `HomeActivity.kt`: removed the second opaque Home canvas/backdrop and the pinned
  large hero. A 56–60dp media context, complete rails, receipt and utility group
  share one vertical scroll boundary. Refreshing is semantics rather than an added
  line. There is no duplicate row kicker over the title or repeated provider line.
  Sparse Home starts with the actual non-empty owner rows (Apps first for the
  default sparse layout), then a compact glass Arrange/Library settings group;
  there is no AI/setup billboard or fabricated recommendation.
- `TileCards.kt`: retains the existing sole guarded artwork activation target and
  exact action callback. Adds readable actual title/subtitle captions, inline
  source/count headers, and `BringIntoViewRequester` around the complete card so
  focus reveals the caption as well as the artwork. Original custom row order,
  source/style/action identity and first/last horizontal focus boundaries remain.
  Lower owner rows remain normal complete scroll content, not decorative peeks.
- `HomeMediaPresentation.kt`: provider decoration comes from actual media effect
  identity. Retained media still resolves to current rows, and focused-tile refresh
  additionally requires both key and action so reused pinned positions do not
  rebind an unrelated action.
- Home updated/Undo/Keep now form one shared glass receipt. Both receipt and
  retained-Undo callbacks capture the displayed receipt before controller
  admission. Keep acknowledges the already-saved layout; `Undo last home change`
  remains in the Arrange group. No timeout/history, new focus request, controller
  shortcut or weakened busy rule was introduced.
- `AskActivity.kt`: replaces the opaque full-canvas fill with a restrained scrim
  and contextual dense glass sheet. Uses the existing translucent platform theme;
  no manifest/theme/resource changes were needed. The labeled input gets initial
  focus, Back stays available, Speak is explicit, speech stays a reviewable draft,
  and Ask remains explicit. Lifecycle cancellation, permissions, Runner calls and
  draft restoration are unchanged. Copy distinguishes closing the sheet from
  stopping a task and makes no title-attachment claim.
- `TitleDialog.kt`: shared glass details with initially focused pinned Back, save
  action, real artwork, exact loaded title/type/provider/metadata and a named
  Play/Resume control beside its resume information. Narrow/enlarged-text layouts
  stack the information. Existing loading/error/missing/type gates, saved-action
  identity, season filters, page generations, append cancellation/deduplication,
  unavailable episode rules and effect callbacks remain in place.
- `NowPlayingSurface.kt`: the live-session bar uses dense shared glass and quiet
  details entry. Exact-session target lookup, current policy check, Runner tool
  execution, lifecycle observer, feedback and access handling are unchanged.
  Permission instructions remain confined to explicitly opened details.
- `ArrangeHome.kt`: each real row's metadata and existing move/remove controls form
  one dense glass group. Stable row/focus identity, latest-layout evaluation,
  controller admission, busy rules and remove/restore confirmations remain intact.

`LayoutEdits.kt`, `TilePresentation.kt`, the manifest and existing launcher tests
were inspected but did not need edits. No other owner's source or evidence note
was written. No fictional photography/catalog, font download, dependency upgrade,
unsupported blur, infrastructure, firmware or credential work was introduced.

## Focused verification

All outputs are private temporary files under:

`/var/folders/tc/f5wf7p2n68z7htvy4dr283sw0000gn/T/vela-glass-shell-__gw73er/`

- `python3 <directory>/compile.py` — **passed**. Direct Kotlin 2.4.21 Compose
  compilation of all 11 assigned launcher files, the shared `Glass.kt` /
  `GlassMaterial.kt` APIs and `UiPresentation.kt`, against read-only existing
  application classes and installed Android/Compose dependency jars; JVM target
  17. No Gradle task or shared build output was used. The last pass includes the
  final Ask/header cleanup.
- `python3 <directory>/tests.py` — **24 tests passed** with JUnit 4.13.2:
  `HomeMediaPresentationTest` (12), `TilePresentationTest` (4), and
  `LayoutEditsTest` (8). This compiles the actual layout codec/edits/presentation
  sources with the installed serialization and Compose compiler plugins. Checks
  cover current-profile/current-action media resolution, retained context across
  app focus, owner row/action preservation, honest Plex handoff affordances,
  stable row keys, stale IDs, overflow-safe moves and codec validation.
- The first private pure-test attempt mixed a non-Compose-compiled RowSpec with
  the cached Compose-compiled LoadedRow and reported missing `$stable`; adding
  the installed Compose plugin to that isolated harness fixed its class shape.
  The corrected run passed all 24 tests. An initial type-check also needed the
  foundations owner's newly added `GlassMaterial.kt` alongside `Glass.kt`.
- No cosmetic/source-string tests were added. No device, GUI, server, Gradle,
  Git or PR action was performed. The physical Shield was not contacted.

## Compatibility, dependencies and acceptance limits

Uses the installed Kotlin 2.4.21 / Compose BOM 2026.09.00 (cached Compose UI
1.12.1), existing Coil artwork rendering and Android APIs available on minSdk 28.
`BringIntoViewRequester` type-checks against the installed foundation API. No new
runtime dependency or manifest permission is required.

The coordinated result depends on the foundations owner's real material,
Manrope typography, palette, cached backdrop and the agreed CardFormat dimensions
(Wide 200 × 112.5dp, App 144 × 81dp). These fixed shared APIs were consumed without
editing their owner files. The stock-height composition uses the 52dp navigation
plane, 48/27dp safe margins and compact media context; bigger type, custom layouts,
long titles, real errors, receipts and active playback can require scrolling.

**Compilation is not visual acceptance.** Root still owns the combined Android
checks/build and actual local-emulator Night/Day/opaque captures. Specifically
verify complete Continue watching/Apps captions at the baseline viewport; deeper
row focus scrolling; all seven tabs at baseline and narrow widths; unchanged
agent-slot width in idle/working/approval states; actual profile identity after a
revision; Ask over Home and another invoking app with typing/speech/Back; details
Back restoration, named Resume and series paging; receipt busy/Keep/retained Undo;
and enlarged-text/short-height layouts. No native performance or visual-fidelity
claim is inferred from the approved HTML or isolated compilation.

Coordinator follow-through: [combined checks, actual captures and remaining limits](glass-native-20261031/README.md).
