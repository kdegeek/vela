# WP2 shell and destination adoption

Implementation leaf, 2026-10-31. Governing inputs: the complete `vela-design-language.md` and
`vela-component-contract.md`, including coordinator corrections and strict WP2/WP2m ownership.
No Git mutation, shared Gradle task, browser, GUI, simulator, physical Shield, SSH or firmware action
was performed. The physical Shield remains paused. No screenshot or visual acceptance is claimed.

## Delivered

- One nominal 52 dp shell bar: sail/serif wordmark, seven tabs, profile initial chip and read-only
  agent lantern. Settings owns Profiles, Routines, External control and Arrange home navigation.
  Back returns to the originating Settings row; Up out of content restores the selected tab.
  Back on Home content resets to the top and focuses Home; repeated Back on the Home tab does nothing.
- Home uses shared settled backdrop, nominal 96 dp / 52 dp compact hero, full-width keyed rows,
  initial skeletons and stale data while refreshing. Short surfaces scroll the hero; very short
  shell surfaces scroll chrome around a bounded content viewport. Before agent setup, pairing is
  the first scroll item and the duplicate idle hero is omitted. Live QR rendering uses the fixed
  semantic QR palette; screenshot credential redaction remains separate.
- All default and agent rows use `TileRow → TileCard → MediaCard`; selection remains the original
  `TvShell.select → perform → HomeData.perform` path. Plex ByteArray artwork passes unchanged to
  `MediaCard.image`. Search handoffs say Search in app; Jellyfin selection says Open details.
- Search, app/list grids, House, Activity, grouped Settings and the non-interactive six-card
  appearance showcase consume the foundation APIs. TV approval notices have no answer action.
- Arrange home moves/removes existing stable IDs and restores defaults, with Cancel-first shared
  confirmations. Boundary/last-row controls remain focusable and unavailable. Each activation
  reads `app.settings.layout.value`; saves use the exact validated encode/decode,
  Main.immediate → Runner.withController("owner-layout") → IO SettingsStore path. Reordering
  scrolls to and restores the same moved row's same button. Busy/error/success are explicit.
- Ask uses the shared field; title details use the shared sheet with stable Back/Save header,
  retained seasons and paged episodes; Now Playing uses shared artwork/sheet/status controls.
  Only an existing session shows the docked bar, including paused sessions. Access problems
  appear in Settings/details. Bitmap recycling guards remain intact and control feedback stays visible.

## Files written by this leaf

Under `android/app/src/main/java/app/vela/tv/launcher/`:

1. `HomeActivity.kt`
2. `TvShell.kt`
3. `ShellChrome.kt` (new)
4. `TileCards.kt` (new)
5. `TilePresentation.kt` (new, pure)
6. `LayoutEdits.kt` (new, pure)
7. `ArrangeHome.kt` (new)
8. `Destinations.kt` (new)
9. `ActivityPage.kt` (new)
10. `SettingsHub.kt` (new)
11. `AskActivity.kt`
12. `TitleDialog.kt`
13. `NowPlayingSurface.kt`

New tests under `android/app/src/test/java/app/vela/tv/launcher/`:

14. `TilePresentationTest.kt`
15. `LayoutEditsTest.kt`

16. This evidence note, `docs/design/evidence/wp2-shell.md`.

`HomeData.kt`, `ManagementForms.kt`, the three WP2m files, all foundation files, existing tests,
contract documents, backend/settings/schema/manifest/build files were not edited by this leaf.
Peer modifications visible in the shared checkout are not attributed to this work. The deprecated
`VelaDesign` alias remains available for coordinator cleanup.

## Actual verification

All compiler outputs/logs/scripts are private under `/tmp/vela-wp2-check/`.

- `python3 /tmp/vela-wp2-check/compile.py` — **passed**, exit 0, empty compiler diagnostic log.
  Compiles **36 current Kotlin sources** (all design and launcher sources, including the contemporaneous
  WP2m changes) with the installed Kotlin 2.4.21 compiler and Compose compiler plugin, JVM target 17,
  JDK 21, Android SDK 37 and cached dependency classpath. Dependencies outside those source sets are
  supplied by a copied baseline app JAR. The copy is a Kotlin friend module for existing internal
  control declarations; the initial invocation without friend paths failed solely on visibility and
  the harness was corrected. This is actual Compose compilation, not stubs or a full Android build.
- `python3 /tmp/vela-wp2-check/pure.py` — **11 JUnit tests passed**. Compiles the current `HomeLayout.kt`
  with the Kotlin serialization compiler plugin, `VelaGlyph.kt`, both new pure adapters and both new
  test files, without Android/Compose. Tests cover honest Jellyfin/Plex affordances, agent treatment,
  source labels, source/action/ID preservation, inverse and successive moves, extreme-delta overflow,
  end no-ops, removal and last-row rejection, stale IDs, real codec round trips and invalid documents.
- Direct source comparison against the captured pre-edit Now Playing source — **passed**:
  observer construction/lifecycle, policy checks, session selection and the complete `act()` block are
  byte-identical. Comparison of title state/loading/paging/generation/gating against HEAD — **passed**.
- Static audit of **13 owned launcher production files** — **passed**: no forbidden M3 control
  invocations, raw colour/shape literals, `answer()` calls or `VelaDesign` references. Both new pure
  files have no Android/Compose imports.
- `git diff --check` — **passed** (read-only check of the shared checkout).

## Pending checks — 4 groups, all coordinator-owned

1. Combined integration: after all writers finish, run
   `./gradlew --no-daemon testDebugUnitTest lintDebug lintFirmware assembleDebug assembleFirmware`,
   the phone Node suite and final shared diff check. Isolated compilation does not replace the complete
   app build, Android lint or existing management/media/control regressions.
2. Changed TV visual proof: Night, Day, high contrast, reduced motion, baseline and short measured
   constraints, 1.3 font scale, full-width row overhang, pairing, hero/bar budget and appearance preview.
   This requires the owner to resume physical-device work or authorize another target.
3. Interactive focus/input proof: cold Home focus; one-Down content access; row ends and per-row
   restoration; refresh/app-return restoration; Back/Up, Settings origin focus, move-button retention,
   Cancel-first confirmations; busy/disabled held-key activation; field/IME and sheet/queue scrolling.
4. Live integration/guard proof: paused-session bar, permission/unavailable recovery, exact media-session
   controls and unknown-effect feedback, profile transitions, title season/episode pagination and native
   playback gating, Plex raw-byte artwork and scan of the real unredacted QR. Existing policy and
   paging code is preserved, but these interactions were not exercised here.

Independent phone browser QA and PR6 delivery remain with the coordinator and their respective owners.
