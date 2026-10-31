# Glass foundations implementation — 2026-10-31

Implemented against `docs/design/vela-glass-implementation-contract.md` and the approved
prototype CSS / render exports. Inspected native-reference images: Home Night, Home Day,
Ask, person editor, details and opaque Home. These are design references, not evidence of
this APK rendering. No fictional catalog or photographic asset was added to production.

## Delivered

- `VelaGlassSurface(modifier, shape, dense, content)` and
  `VelaAmbientBackground(modifier)` retain the fixed declared argument order and types.
  The containers add drawing only: smoke/pearl tint, a restrained leading champagne
  reflection/rim and a static shadow. Regular tint opacity is 0.88 and dense opacity 0.94;
  dense panels favour reading over transmission. High contrast uses an opaque surface
  and essential solid boundary, with no reflection/shadow pipeline. Ambient backgrounds
  are still radial colour fields, with no application reads or network work.
- Night smoke / Day pearl replace the earlier blue palette. `VelaType` is still the public
  entry point; all its text roles now use the approved bundled variable Manrope with
  explicit 400/500/600/700 weight axes. Native overlay text uses the same font. The TTF
  and OFL are byte-for-byte copies of the approved exploration assets; no font service
  or runtime dependency was added. Font SHA-256:
  `3ae11c49db0455a3cc33e37d380f20fdb8c7f8b41dc07625c177e3d87a9d6ae6`.
  OFL SHA-256: `d6a309fcfb963d329a93baf9df66fa5f3c23a4be2b5f67edf24d6cafecde12ac`.
  The coordinator packages this license as `assets/manrope-ofl.txt`, avoiding an
  unused Android resource while retaining the license in both APKs.
- Wide art starts at 200 × 112.5dp, App art at 144 × 81dp; crop/fit policy stays unchanged.
  Shared card labels can wrap completely, with a two-line minimum. Safe margins remain
  48/27dp; navigation is 52dp and compact media context is 56–60dp. Existing scroll and
  enlarged-text handling is retained. Shared panel treatment gives dialogs, sheets,
  status and agent panels the dense material. List children remain flat/transparent.
- Primary buttons use contrasting ink/pearl, with flat child fills and 10dp corners.
  Champagne remains an accent, not the primary fill. Agent/Danger text retains its
  identifying colour on the flat focused fill; focus uses an independent perimeter and
  contrasting separation. Reduced motion keeps the perimeter and still fill feedback.
  Focus.kt changes are drawing-only. Activation files, sole focus target, disabled-focus
  policy, semantics, callbacks and activation admission were not changed.
- The native overlay keeps its flags and exact `OK to allow · Back to cancel` hint.
  Its changes are font, still gradient material, rim and elevation only. No buttons or
  input target were introduced; no external app pixels are sampled.

## Bounded backdrop

`VelaBackdrop(model, modifier)` retains its signature. Ambient requests decode toward
480 × 270 using the installed Coil request size/precision APIs. A transformation first
bounds actual bitmap dimensions (including portrait/extreme aspect ratios), then runs
three separable radius-8 box passes. Processing uses premultiplied alpha and clamped
edges; it never modifies or recycles Coil's input. It runs on a shared
`Dispatchers.Default.limitedParallelism(1)` dispatcher with cancellation checks on every
scanline. Two work arrays are reused across the passes. Each output has at most 129,600
pixels / 518,400 ARGB bytes, independent of display resolution; the bounded pixel arrays
are also independent of display resolution. Temporary resampling/input allocations are
separate from that output bound.

A process-scoped derived Coil loader shares the installed loader's fetchers and disk
cache but has its own 4MiB strong LRU with weak references disabled. The versioned
transformation cache key separates softened pixels from foreground artwork. This is a
cache bound, not a total-process-memory claim: decoder/source buffers and displayed
transition images also consume memory. Coil owns request replacement/disposal;
`LaunchedEffect(model)` cancels obsolete settle delays, and coroutine cancellation
propagates through the CPU transform. Null models clear without the settle delay.

High contrast returns an opaque canvas before loader/request/animation creation, so no
hidden ambient request is initiated in that mode. Switching modes disposes the prior
composition's requests. Reduced motion selects the still image branch. Background work
occurs on image changes/cache misses, not every frame. Foreground `VelaArtwork` remains
on its existing crisp path. No API31 `RenderEffect`, `Modifier.blur`, full-screen bitmap
processing or dependency upgrade was introduced.

## Verification performed

No Gradle build or shared build output was used. All generated classes and extracted
library artifacts are in private temporary directories.

1. Isolated Kotlin 2.4.21 / JVM 17 compilation of the pure presentation, palette,
   activation, material coefficients and soft-pixel code plus the focused JUnit classes.
   Then JUnit 4.13.2 `org.junit.runner.JUnitCore` ran:
   - `app.vela.tv.design.VelaPaletteTest` — 6 tests.
   - `app.vela.tv.design.UiPresentationTest` — 3 tests.
   - `app.vela.tv.design.AgentPresentationTest` — 5 tests.
   - `app.vela.tv.design.ActivationGateTest` — 12 tests.
   - `app.vela.tv.design.SoftArtworkTest` — 6 new tests.
   - `app.vela.tv.design.GlassMaterialTest` — 2 new tests.

   **Result: OK (34 tests).** Exact compiler/JUnit argument arrays and output are retained
   in `/var/folders/tc/f5wf7p2n68z7htvy4dr283sw0000gn/T/vela-glass-checks-4g8mqpet/commands.json`
   and `junit.txt`. The palette test additionally covers Agent/Danger text on focused
   flat fills. The new material check covers both tints and their maximum leading
   reflection over 125 black/bright/saturated RGB backgrounds per theme/density,
   requiring >=4.5:1 text and >=3:1 focus contrast. The Day subtle metadata colour was
   darkened after the existing raised-surface contrast check caught 4.30:1.

   Pixel regressions cover landscape/portrait/extreme aspect ratio bounds, no upscaling,
   constant/one-pixel images, horizontal and vertical edge smoothing, monotonicity and
   symmetry, transparent-colour bleed, source immutability, real Job cancellation
   during processing, and invalid dimensions/buffers. No source-string tests were added.

2. Isolated compilation of **all design Kotlin sources plus control/Overlay.kt**, using
   the installed Kotlin 2.4.21 Compose compiler, Compose UI/Foundation 1.12.1,
   Material3 1.4.0 and Coil 3.6.3 AAR classes. Android API37 compile stubs and existing
   read-only debug app classes supplied external types; a private `R.font.manrope` stub
   supplied the new resource symbol. **Result: exit 0, no diagnostics.** Arguments and
   output are retained in
   `/var/folders/tc/f5wf7p2n68z7htvy4dr283sw0000gn/T/vela-glass-compile-dp_e5sea/args.txt`
   and `compile.txt`. This does not test Android resource packaging or replace a combined
   Gradle build.

3. Confirmed installed Coil `Transformation`, `transformations`, `allowHardware`,
   `MemoryCache.Builder.maxSizeBytes/weakReferencesEnabled` and Compose variable-font
   APIs against installed AAR bytecode before use. Also consulted the official
   [Coil request documentation](https://coil-kt.github.io/coil/image_requests/),
   [loader/cache documentation](https://coil-kt.github.io/coil/image_loaders/),
   [transformation API](https://coil-kt.github.io/coil/api/coil-core/coil3.transform/-transformation/)
   and [Compose font API](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/font/package-summary).
   Current web API pages may describe a newer snapshot; installed-artifact compilation
   is the version-specific compatibility check. The chosen Android bitmap/font/shadow
   primitives support the project's API28 minimum; no API28/API30 runtime claim is made
   from compilation.

## Integration and remaining acceptance

The shell owner mounts `VelaBackdrop` behind the full shell and Home, with an ambient
scene for sparse/management destinations; these foundations do not read launcher state
or mount another Home canvas. No dependency changes or additional caller APIs are needed.
The coordinator must run combined resource packaging/tests/lint/build and actual local
emulator Night/Day, details, Ask, management, high-contrast and reduced-motion checks.
Actual cache hits, image replacement/disposal, scrolling, screenshots, native runtime
cost and visual match remain to be observed there. Compilation is not visual acceptance.

No Git/PR operations, device/GUI actions, servers, credentials, infrastructure or firmware
changes were performed. The physical Shield was not contacted. No writes were made in
another owner's paths.

Coordinator follow-through: [combined checks, actual captures and remaining limits](glass-native-20261031/README.md).
