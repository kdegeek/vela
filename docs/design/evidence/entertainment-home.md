# Entertainment-first Home

Implementation leaf, 2026-10-31, against assignment baseline `2050260`.
The owner's approved presentation overrides the generic idle/agent hero, Home pairing panel and
footer Ask promotion in `vela-design-language.md` §11.2 and `vela-component-contract.md` §4.3.
The remaining component, focus, action-dispatch and layout-storage contracts are preserved.

## Delivered behavior

- The default document now orders **Continue watching → Apps → Recently added → My list**, with
  the same IDs, sources and styles. Restore-default confirmation describes this exact order.
  Stored layouts are neither migrated nor reordered. Owner-created Ask rows, custom sources and
  their actions remain intact. The existing empty-row/error handling is unchanged.
- Home has no generic marketing hero and no full phone-pairing panel. `PairCard` and QR generation
  are byte-for-byte unchanged and remain available to Settings. Home's footer retains Arrange home;
  when no media is present, a quiet **Library settings** button opens the existing Settings callback
  after the content. The redundant footer Ask promotion is removed. The shell lantern is untouched.
- The media header appears as soon as real title rows load, without consulting AI readiness. It
  initially selects the first eligible title in document order. Focusing media updates its title,
  metadata and artwork; focusing apps, Ask, house actions or shell controls retains the last media
  selection, keeping header presence stable. With no eligible title, Home has no hero.
- Jellyfin title actions and Plex/watchlist content can supply metadata even without artwork.
  Pinned search/link shortcuts need artwork to qualify for the header. App, Ask and house actions
  never qualify, even when decorated with artwork. This rule affects decoration only, never row
  visibility, style, order or action dispatch. Artwork uses the existing backdrop, falling back to
  the title image (including Plex bytes) through `VelaBackdrop`.
- Media selection is reconciled against current rows after refresh: use the current matching tile,
  fall back to another available title, or remove the header when none remain. Old-profile or
  removed media cannot survive without a current match. Existing stale rows remain during loading;
  initial skeletons, retries, cancellation, settings/watchlist refresh triggers and profile-scoped
  `HomePresentation` lifetime are unchanged. Short surfaces and large text retain the scrollable
  header behavior; Now Playing still requests the compact media header.
- All rows still follow **TileRow → TileCard → MediaCard** and pass the original tile to `onSelect`.
  `HomeData`, `Runner`, `TvShell`, row key namespacing and the shell's compact agent status are unchanged.

## Exact files and APIs

Production:

1. `android/app/src/main/java/app/vela/tv/launcher/HomeActivity.kt`
   - Keeps the exact `HomeContent(app, refresh, onSelect, onSettings, onArrange, onAsk, compact,
     reset, presentation)` signature. `onAsk` is retained for root integration; Home no longer
     consumes it because the shell owns Ask.
   - Adds `HomePresentation.media: Tile?` to retain media independently of ordinary `focused` state.
     No new persistent field or schema. Root should preserve this distinction when wiring Undo.
   - Replaces the nullable marketing `Hero` with private `MediaHero(tile: Tile, ...)`.
2. **New** `android/app/src/main/java/app/vela/tv/launcher/HomeMediaPresentation.kt`
   - Pure internal API: `homeMediaTile(rows: List<LoadedRow>, focused: Tile? = null,
     previous: Tile? = null): Tile?`. Returns an original current tile; never rewrites an action.
3. `android/app/src/main/java/app/vela/tv/layout/HomeLayout.kt` — default row order only.
4. `android/app/src/main/java/app/vela/tv/launcher/ArrangeHome.kt` — default-restore wording only.

Tests and evidence:

5. `android/app/src/test/java/app/vela/tv/launcher/LayoutEditsTest.kt` — only the existing fixture
   changed, selecting Apps and Continue watching by explicit IDs instead of default row indices.
   Every existing assertion is retained.
6. **New** `android/app/src/test/java/app/vela/tv/launcher/HomeMediaPresentationTest.kt` — 12 tests.
7. **New** `android/app/src/test/java/app/vela/tv/layout/EntertainmentHomeLayoutTest.kt` — 2 tests.
8. **New** `docs/design/evidence/entertainment-home.md` — this note.

No other repository files were written by this leaf. No Undo placeholder, dependency, API route,
settings field, source/action type, saved layout mutation or background task was added.

## Executed checks

Private output directory:
`/var/folders/tc/f5wf7p2n68z7htvy4dr283sw0000gn/T/vela-entertainment-home-lu10y1nx/`.
The harness reads cached compiler/dependency paths and a private copy of the baseline runtime app
JAR; it does not run Gradle or write shared build output.

- `python3 <private-output>/check.py compose` — **passed**, empty diagnostics. Compiles the actual
  changed Home activity, media helper, Arrange home and HomeLayout sources using Kotlin 2.4.21,
  Compose and serialization compiler plugins, JDK 21, JVM target 17 and cached Android/Compose
  dependencies. Other production declarations resolve through the copied baseline JAR with friend
  visibility. This is isolated compilation, not the combined app build.
- `python3 <private-output>/check.py pure` — **26 JUnit tests passed**: 12 new media-presentation
  cases, 2 new default/custom-layout cases, all 8 existing LayoutEdits cases and all 4 existing
  TilePresentation cases. The current HomeLayout codec and helpers are compiled from source;
  `Tile`/`LoadedRow` resolve from the copied real application JAR, without behavior stubs.
  Tests cover initial media selection, app/action/shell focus stability, actual media selection,
  refreshed metadata, removed media, old-profile inputs, failed/empty rows, artwork-free catalogue
  titles, pinned shortcuts, duplicate titles, reused pinned positions, exact action preservation,
  new default order and unchanged custom layouts. They do not assert CSS or source literals.
- The first private JUnit run compiled without the Compose plugin and failed because the copied
  `LoadedRow` expects Compose-generated `RowSpec.$stable`. Matching the cached model's compiler
  instrumentation fixed this harness mismatch; the final 26 tests pass. No product change was made
  for that failure. Logs: `compose-compile.log`, `pure-compile.log`, `junit.log`; the initial failure
  is retained as `junit-first-attempt.log`.
- Read-only comparison against `2050260` confirmed the exact HomeContent signature and unchanged
  PairCard/QR implementation, HomeData, TileCards, TilePresentation, TvShell and ShellChrome.
  It also confirmed ArrangeHome is wording-only and HomeLayout is default-order-only.
- Scoped `git diff --check` — **passed** for the four modified existing files.

No shared Gradle, Git mutation, device, GUI, browser, SSH, infrastructure, PR or credential operation
was performed. No subagent was started. Physical Shield testing remains paused.

## Root integration and capture needs — 3 remaining groups

1. **Undo integration:** wire the other leaf's final Undo API into the retained HomeContent surface
   after both leaves finish; no placeholder control is supplied here. Settings owns its restore
   controls. The current Arrange home default restore now uses the entertainment-first default.
2. **Combined verification:** run the complete Android build/unit/lint checks after integration.
   Verify cancellation/stale-refresh behavior and profile invalidation with the combined changes;
   isolated compilation and pure selection tests do not establish interactive focus or app behavior.
3. **Fresh emulator proof:** reset the authorized test fixture through Restore defaults before
   evaluating the new default. The supplied [night](visual-corrections-20261031/home-night.png) and
   [day](visual-corrections-20261031/home-day.png) baselines show a saved **Tonight / Something light**
   Ask row, not `Layouts.default`. That owner-layout row correctly remains until an explicit restore.
   Capture new light/dark app screens with real fixture media and with apps only; verify the media
   header stays in place while moving between media and app/Ask/house cards, the shell still offers
   Ask/status, pairing remains functional in Settings, and Arrange home is reachable. Exercise
   refresh/removal and profile changes, compact Now Playing, short surfaces and large text.
   Keep any custom-layout test separate from the restored-default capture. No fresh visual proof
   or screenshot acceptance is claimed by this implementation leaf.

All three groups are root-owned and resolvable from the existing assignment; no user decision is
needed for this leaf. The physical Shield remains excluded from those checks until explicitly resumed.

Coordinator integration subsequently replaces the unused HomeContent `onAsk`
parameter with receipt-bound `onUndo` and adds an `undoing` argument. The shell
retains its Ask entry. Home shows a compact change notice and preserves Undo in
the footer after Keep layout; the exact captured receipt enters the existing
owner-layout controller and verified storage path. The new `layout_undo` tool
shares prepared-action risk/profile/controller enforcement. The leaf's signature
preservation report above describes its original handoff, before coordinator wiring.
