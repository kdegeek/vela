# WP4 native playback chrome

Implementation evidence, 2026-10-31. Baseline: `0809edfb31d2fac467dc55e2b67fd93d9cf777df`.
This is source/compiler evidence, **not visual acceptance**. Physical Shield use remains paused.

Coordinator verification: reviewed the activity diff and complete visual adapter;
reran `verify_activity.py` and the actual-source isolated compile successfully.
Removing only the explicit visual call-site substitutions reproduces the committed
activity exactly, including every playback/cancel/retry callback. No device was used.

## Owned changes

- `PlayerActivity.kt`: construct/apply `PlayerChrome` from the existing high-contrast and reduced-motion
  settings; route the three existing dialog builders/show calls through the visual adapter.
- New `PlayerChrome.kt`: use `VelaPalettes.overlay` (Night / Night HC), `VelaSpace`, `VelaSize`, and
  `VelaType` with native Android views. Inset the existing controller by 48/27 dp without insetting
  the video or subtitles. Give the center/bottom/overflow containers tonal fills, and enabled native
  transport, subtitle, settings and overflow controls brass focus/press fills and 2 dp rings (4 dp HC).
  HC panels/controls have 1 dp essential boundaries; the full-video scrim is omitted in HC.
- Apply supported `DefaultTimeBar` played/scrubber/buffered/unplayed colors and a visible focus state.
  Preserve Media3's seek-arrow drawables, live seconds labels, content descriptions, hit targets,
  padding, enabled state and listeners. Use system type and tabular numbers; seek counts increase
  from the stock 9 dp to the shared 12 sp caption token.
- Native dialogs use a public custom title, 22 sp system type, 16 sp body, 15 sp wrapping action
  labels, a maximum width of 640 dp constrained by the available display width, and Back initial
  focus. Their message stays in the framework's scroll container. The error heading includes the
  platform warning icon alongside the existing error wording. Button and window elevation are zero.
- Disable native controller transitions in reduced motion and HC; no new focus/key/click/player
  listeners, coroutine work, view polling, dependencies, resources or fonts.

Settings are sampled at activity creation, without adding a settings observer or changing lifecycle
behavior. Video/subtitle rendering, subtitle selection preferences and all playback behavior remain
owned by the existing code. The native `PlayerView` still receives the original `requestFocus()`.
Back initial focus applies to the activity's dialogs; Media3 still chooses its playback-control focus.

## API inspection

Inspected the **installed Media3 UI 1.11.1 AAR** using `javap`, and its shipped
`exo_player_control_view.xml`, `exo_player_view.xml` and `values.xml`. Downloaded only its matching
source archive to `/tmp/vela-wp4-check/media3-ui-sources.jar` from
[Google Maven](https://dl.google.com/dl/android/maven2/androidx/media3/media3-ui/1.11.1/media3-ui-1.11.1-sources.jar).
Read `PlayerView`, `PlayerControlView`, `PlayerControlViewLayoutManager` and `DefaultTimeBar` source.
The current [PlayerControlView reference](https://developer.android.com/reference/androidx/media3/ui/PlayerControlView)
and [DefaultTimeBar reference](https://developer.android.com/reference/androidx/media3/ui/DefaultTimeBar)
were also consulted; the installed classes/source determined which APIs were used.

## Executed checks

All generated compiler/test output is under `/tmp/vela-wp4-check`. Shared build outputs were read only.

1. `python3 /tmp/vela-wp4-check/compile.py` — **exit 0, no diagnostics** after the final source edit.
   Compiles the actual `PlayerActivity.kt` and `PlayerChrome.kt` with Kotlin 2.4.21 K2, JDK 21,
   JVM target 17, Android SDK 37 `android.jar`, installed Media3 1.11.1 classes and generated resource
   classes, and the existing compiled design foundations. Uses a private copy of baseline app
   classes for unchanged app types and `-Xfriend-paths` for their internal Kotlin declarations.
   The first attempt exposed missing Guava in the WP1-derived classpath; adding the **already
   installed** Guava 33.3.1-android required by Media3's POM resolved it. No dependency was added.
2. `python3 /tmp/vela-wp4-check/pure.py` — **exit 0; JUnit `OK (13 tests)`**. Recompiled current
   `VelaPalette.kt`, `PlaybackState.kt` and their existing tests into private output, then ran the
   six `VelaPaletteTest` cases and seven `PlaybackStateTest` cases. These cover palette selection,
   contrast and opacity, resume/stop/completion invariants, and reporting ordering/coalescing.
   They do not establish View focus, rendering, menu behavior or runtime integration.
3. `python3 /tmp/vela-wp4-check/verify_activity.py` — **exit 0**. Reversing only the explicit visual
   adapter additions/call-site substitutions recovers the HEAD activity **byte for byte**. Existing
   button/cancel callbacks, retry, request identity, PLAYING observations, profile policy, sessions,
   audio focus, lifecycle, track preference selection, reporting and position durability are unchanged.
4. `git diff --check -- android/app/src/main/java/app/vela/tv/player/PlayerActivity.kt` and a trailing
   whitespace check of all three owned files — **passed**. The two new files are untracked, so
   ordinary `git diff --check` alone does not check them.

No shared Gradle task, Git mutation, GUI/browser/device action, SSH, firmware work or credential
operation was performed.

## Supported-API limits retained deliberately

- **Track/speed popup interiors:** Media3's `settingsView`, `settingsWindow`, and track adapters are
  private. They have no public runtime palette/row-style API. Their launch buttons are styled;
  popup rows keep native styling, checkmarks, scrolling, accessibility labels and selection behavior.
  No reflection, hierarchy scraping or replacement selection callbacks were introduced.
- **Native disabled state:** Media3 sets both enabled state and view alpha in `updateButton`.
  The adapter supplies an opaque disabled color but does not counteract Media3's disabled alpha or
  make disabled native controls focusable. The Compose contract's fully opaque, focusable-disabled
  treatment is therefore not claimed for Media3 controls.
- **Focus-ring geometry:** rings are inside the native hit bounds, not outset. Parent clipping,
  the overflow scroll container and Media3's layout-manager geometry stay intact. No scale motion.
- **Native control layout:** controller child heights and Media3's overflow/minimal-layout behavior
  are retained. The layout manager's hide translations depend on fixed resource dimensions; changing
  those dimensions without resources/layout replacement would be unsafe. Fixed-height native
  menus/seek controls are not claimed to support arbitrary text scale. Icon-only native controls
  retain their localized accessibility descriptions rather than gaining custom visible labels.
- **Dialogs:** title truncation is limited to two lines; message scrolling and wrap-content action
  heights are native. The framework still measures the vertical dialog height; no custom 460 dp
  height cap or replacement scroll/key handler was added. Short-window and D-pad overflow behavior
  needs runtime observation.
- **Reduced motion:** controller transitions are disabled. Native buffering and framework dialog
  animations remain platform-owned; a fully static buffering treatment is not claimed.

## Remaining checks (5; coordinator-owned)

1. Combined Gradle unit tests/lint/debug and firmware assembly after all writers stop, per the
   governing contract. Isolated compilation does not substitute for merged resource/build validation.
2. Observe the changed playback chrome in standard and HC, including Day app settings (playback
   deliberately uses the overlay palette), and verify 48/27 dp safe margins at measured constraints.
3. Exercise native D-pad/held-key transport, progress scrubbing, disabled controls, subtitle/settings
   popups, overflow/minimal layout and focus restoration. Verify focus-ring visibility and clipping.
4. Observe error, completed and Up next dialogs: Back initially focused, unchanged cancel/Retry/Play
   next behavior, long titles/messages, D-pad body scrolling and 1.3× text/short-window action growth.
5. Observe reduced-motion controller transitions and retained native buffering/dialog animation.

These require integrated runtime or visual evidence. None was performed by this worker, and none
authorizes reconnecting, installing, launching, navigating or capturing the paused physical Shield.
