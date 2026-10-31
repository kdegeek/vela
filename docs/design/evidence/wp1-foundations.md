# WP1 native foundations

Implementation leaf for the frozen `vela-component-contract.md` §3, on the coordinator's shared checkout. No Git mutations, shared Gradle tasks, device/browser/GUI actions, firmware changes, or writes to peer-owned packages were performed.

## Ready for WP2 and coordinator integration

All 17 production design files compile together, including every §3 public API and its specified parameter order:

- `VelaPalette.kt`: opaque ARGB roles, four palettes, exact day/default-night selection, night-only overlay selection, QR constants and WCAG contrast calculation.
- `VelaGlyph.kt`, `UiPresentation.kt`: the bounded glyph enum, agent precedence, task tone/words, approval instructions and status glyphs. Existing `displayProgress`/`titleContent` implementations and assertions are preserved.
- `VelaTokens.kt`, `VelaTheme.kt`: semantic colors, spacing, sizes, shapes, motion, system serif/sans typography, mapped M3 theme and accessibility locals. `VelaDesign` remains as a deprecated compatibility alias with all contracted members.
- `Focus.kt`, `Buttons.kt`, `Glyphs.kt`: one clickable/focus target per control, outset rings, focus/press state, all button/tab/choice APIs, and 20 cached inline vectors. The first four `VelaButton` parameters and trailing callback retain their order.
- `Artwork.kt`, `Cards.kt`: fallback until Coil success, unchanged String/Drawable/ByteArray model support, finite clamped watched progress, media/app formats, visual previews, cards, and settled backdrops.
- `Rows.kt`, `Pages.kt`, `Lists.kt`: full-bleed keyed rows, static skeletons, row status, adaptive grids, inset helpers, pages, list rows and toggles.
- `Status.kt`, `Agent.kt`: status/loading/empty states, lantern, read-only approval notice and task panel. No TV approval action exists.
- `Fields.kt`, `Dialogs.kt`: labeled fields with bounded input/password handling, dialog/sheet shells, cancel-first confirmations and initial focus.

The existing `VelaTheme`, `VelaButton`, `EmptyState` and `LoadingState` consumers already receive the new implementation. WP2 owns migration of remaining launcher surfaces to the new APIs; WP1 did not edit launcher files. There are no missing public signatures or placeholder bodies.

## Behavior and accessibility

Disabled and busy buttons remain focusable. Activation is guarded at event time; disabled/busy press state is suppressed, disabled semantics remain, and busy controls announce Working. Focus requesters precede the control's single focus target. Cards ring/scale only their artwork, leaving their external labels clear; decorative labels and fallback text do not duplicate accessibility descriptions. The ring geometry is cached and drawn outside the bounds, without shadows or clipping the artwork.

Reduced motion immediately forces scale to 1, snaps color changes, swaps settled backdrops without a crossfade, and does not instantiate the indeterminate loading indicator. HC uses opaque fills and labels, essential borders and 4 dp focus rings; the backdrop returns before creating any request/effect. Card images remain visible identifiers. The two backdrop scrims use the canvas color. Background decoding uses an explicit 1920×1080 FIT/EXACT bound; cards use Coil's measured constraints. No source-sized painter request is introduced. See the installed-version-compatible [Coil Compose guidance](https://coil-kt.github.io/coil/compose/) and [Compose focus guidance](https://developer.android.com/develop/ui/compose/touch-input/focus/request-focus).

Rows and controls grow with text. Skeleton/status row minimums add scaled header/label height to the baseline format formula. Dialog bodies have a D-pad scroll target only when overflowing; key handling runs only when that scroll target itself is focused and yields at the ends, leaving descendant field/button keys alone. In short windows or above font scale 1.3, dialogs/sheets let their chrome participate in scrolling so header/actions cannot consume the entire body. At ordinary sizes sheet header actions stay fixed. Root should exercise these behaviors physically.

## Palette verification

All supplied palette literals are retained: no correction is needed for the rendered role pairs at the contract's ≥4.5 standard / ≥7 HC text and ≥3 essential-boundary/focus thresholds. Tests cover primary/supporting text on every surface (including focused and agent surfaces), subtle/disabled text on its supported surfaces, resting and focused button text, status colors, boundaries, focus fill changes, opacity, QR contrast and selection.

Avoided invalid role combinations: Night subtle-on-focused is 4.040; Day subtle-on-focused is 4.223; Day success-on-focused is 4.402; Day HC success-on-focused is 6.680 and caution-on-focused is 6.978. Focused list text therefore uses ink/inkMuted; status glyphs preserve the status alongside its word. These are not used as small-text pairings. The design table's Day success/surface “6.3” is rounded: measured 6.282, still above the required standard-text floor.

Selected executed measurements (Night / Day / Night HC / Day HC):

- ink/canvas: 16.682 / 15.172 / 21.000 / 21.000.
- muted/raised: 7.986 / 7.272 / 13.909 / 14.079.
- onAccent/accent: 10.611 / 7.152 / 15.369 / 10.294.
- onAgent/agent: 9.290 / 7.429 / 12.660 / 11.522.

## Overlay

`control/Overlay.kt` reads current `VelaApp.settings.settings.value.highContrast` inside every posted `show()` call, including updates of an existing panel. It uses Night/Night HC, a 95%/opaque surface, 20 dp corners, agent header/prompt styling, the last three steps, larger readable text, safe 48/27 dp offsets and width bounded by 520 dp and the available display width. No text shadows remain.

The `TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`, pixel format, main-handler scheduling, hide timer/callback behavior, show/hide signatures and exact `OK to allow · Back to cancel` hint are unchanged. No approval buttons or effects were added.

## Executed verification

- `python3 /tmp/vela-wp1-check/pure.py`: **14 JUnit tests passed** (`UiPresentationTest`, `AgentPresentationTest`, `VelaPaletteTest`), including every pre-existing presentation assertion. These compile only the three Compose-free production files and design tests with Kotlin/JUnit; no Android classes or stubs are used.
- `python3 /tmp/vela-wp1-check/compile.py`: **all 17 production design files plus Overlay.kt compiled successfully**, with Kotlin 2.4.21 and its Compose compiler plugin, JDK 21, Android SDK 37.0, installed Compose UI/Foundation 1.12.1, Material3 1.4.0 and Coil 3.6.3. Settings/VelaApp types resolve from a copied baseline application jar; dependencies come from installed library jars/AARs. Outputs are isolated under `/tmp/vela-wp1-check/compose-classes`; shared build outputs are read only. Final compiler log is empty (no errors/warnings).
- `git diff --check`: passed. Separate whitespace inspection also covered newly created WP1 files, which ordinary unstaged diff does not include.
- Source review confirmed a single guarded clickable implementation, no backend/Runner references under design, no approval actions, no new dependencies/assets/manifests and no shadow drawing.

Logs are `/tmp/vela-wp1-check/pure.log` and `/tmp/vela-wp1-check/compose.log`. This is isolated compilation and pure-test evidence, not a full Android build, instrumented test or visual acceptance claim.

## Remaining coordinator checks (5; no new owner decision needed)

1. Physical D-pad focus ring/overhang, row-end behavior, restoration and Back through WP2's adopted surfaces.
2. Disabled/busy activation and key repeat, working/disabled TalkBack semantics, and absence of duplicate targets.
3. Font scale 1.3 and larger/short-window dialogs, long-body D-pad scrolling, safe initial focus, API 30 text entry/IME behavior.
4. Night/Day/HC screenshots, reduced motion, and actual URL/Drawable/ByteArray success/failure/fallback plus watched progress.
5. Overlay over another app, prompt hint, current HC setting on subsequent show calls, and unchanged prompt-key/lifecycle behavior.

The coordinator runs full Gradle/phone checks after writers quiesce and handles PR6, kept unmerged for owner review. WP1 does not claim these pending checks have passed.
