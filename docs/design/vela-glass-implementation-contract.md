# Approved glass implementation

The owner approved the liquid-glass design on 2026-10-31: “this is exactly what I
had in mind.” Implement the approved concept, using
[the prototype](explorations/liquid-glass-20261031/mocks/index.html) and its
[render exports](explorations/liquid-glass-20261031/mocks/renders/) as the visual
reference. This supersedes the earlier flat/no-glass and system-font-only visual
rules. Existing control, persistence and approval boundaries remain in force.

## Outcome

Warm cinematic glass, Manrope typography, crisp content artwork, a quiet unified
navigation plane, compact media context, contextual Ask, clear details/Resume,
grouped checked Undo, person-first management and the matching phone material.
Night and Day must both look intentional. Preserve a strong independent focus
perimeter; material rims are decorative. High contrast uses opaque surfaces.
Reduced motion preserves clear still-state feedback.

Production contains real application state and catalog data only. The invented
films and public photography in the concepts are reference/QA material, not a
catalog to ship in the application. The approved Manrope font and its OFL license
may be bundled from the existing exploration assets; no new runtime dependency
or font service is required.

## Shared APIs

Existing callable design APIs remain compatible. These two additions are fixed
so the independent owners can build against them:

```kotlin
@Composable fun VelaGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = VelaShapes.Panel,
    dense: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
)
@Composable fun VelaAmbientBackground(modifier: Modifier = Modifier)
```

`VelaGlassSurface` is a **visual container only**. It adds no click/focus/semantics
target and has no app/store/controller dependency. It provides tint, a coherent
edge/reflection and restrained separation; high contrast is fully opaque with
an essential boundary. Fields and child buttons remain flat inside it. The dense
variant provides a stable reading surface. `VelaAmbientBackground` is the calm
sparse/management scene; it performs no effects or network reads.

`VelaBackdrop(model, modifier)` keeps its current signature. It owns bounded,
cached soft artwork and theme-specific scrims, compatible with minSdk 28. It must
not rely on API31 RenderEffect/Modifier.blur, perform image work on Main, blur
foreground media art, or allocate a new full-screen bitmap each frame. Handle
image changes/cancellation and retain the existing no-load high-contrast path.
Native performance remains something to measure, not a claim inferred from a web
mock. A theme/scene may use a fully opaque tier without changing the control map.

The shell owns the full-bleed scene behind navigation and Home. Home content must
not add a second opaque canvas/backdrop over it. Management may use the ambient
scene. Profile revisions already invalidate Home presentation; preserve that.

Target the approved 960 × 540 logical composition with 48/27 safe margins.
Start near a 52dp navigation plane, 56–60dp media context, 200 × 112.5dp wide art
and 144 × 81dp app banners. Use actual constraints and allow scrolling at short
heights/enlarged text. Preserve readable prose/control type rather than shrinking
it to fit. No chopped captions under pinned content or one-pixel tile peeks.

## Behavioral invariants

- All existing model, phone, owner UI and media effects keep their current owned
  paths. Presentation code does not bypass Runner, management, prepared actions,
  revision checks, cancellation/drain, or exact media-session identity.
- `ActivationGate.kt` and `ActivationInput.kt` are frozen. `Focus.kt` may receive
  visual changes only; its input gating, sole focus target and semantics behavior
  remain intact. Disabled controls retain their existing focus/activation rules.
- TV approvals remain read-only. Overlay flags stay non-focusable/non-touchable,
  with `OK to allow · Back to cancel`. Phone decisions retain the exact prompt ID,
  locking and freshness semantics. Normal Restore/Delete confirmations are separate.
- Home stays entertainment-first and preserves explicit saved layouts. Media OK
  opens details; app OK opens its app. Retain media identity across app focus while
  keeping that context compact and the scroll boundary clean.
- Undo remains checked, one step and session-only. Capture the displayed receipt
  before controller admission; retain the existing atomic storage identity check.
  Keep layout acknowledges an already-saved change. Available Undo remains obvious
  beside or in the Arrange home group after acknowledgement. No timeout/history stack.
- Ask preserves reviewable speech drafts, explicit microphone start/submission,
  existing backend calls and lifecycle cancellation. Make its activity genuinely
  contextual/translucent, with a stable input and reachable Back, while preserving
  its current entry points. Closing its presentation is distinct from Stop task.
- Profiles remain without PIN protection; Home/My list remain shared. Save stays
  explicit and uses existing revisions/validation. Human-readable language controls
  must round-trip current tags, preserve custom tags and retain error/draft behavior.
- Phone route schemas, existing DOM IDs and behavior remain compatible. Any script
  changes needed for a real presentation control must preserve request/approval/
  revision/draft locks and be covered by meaningful behavior checks.

## Ownership

All writers share the checkout. Each owner is a leaf, writes only its paths and
does not run Git, whole-project Gradle builds, devices or GUI automation.

| Owner | Exclusive writes |
| --- | --- |
| Foundations | `design/**` except frozen activation files and only visual changes in `Focus.kt`; `control/Overlay.kt` visual only; new `res/font/**` and the bundled Manrope license; focused `src/test/.../design/**`; evidence `glass-foundations.md` |
| Entertainment shell | `launcher/HomeActivity.kt`, `HomeMediaPresentation.kt`, `TvShell.kt`, `ShellChrome.kt`, `TileCards.kt`, `TilePresentation.kt`, `AskActivity.kt`, `TitleDialog.kt`, `NowPlayingSurface.kt`, `ArrangeHome.kt`, `LayoutEdits.kt`; only Ask presentation metadata in `AndroidManifest.xml` if needed; a new Ask theme in `res/values/vela_glass_themes.xml` if needed; focused launcher tests; evidence `glass-shell.md` |
| Management and secondary destinations | `launcher/ManagementPage.kt`, `ProfileManagement.kt`, `RoutineManagement.kt`, `SettingsHub.kt`, `Destinations.kt`, `ActivityPage.kt`; new presentation helpers in launcher with `Glass` prefix; focused new launcher tests with `GlassManagement` prefix; evidence `glass-management.md` |
| Phone | `assets/phone.html`, `src/test/ui/phone.test.cjs`; evidence `glass-phone.md`. Bundle the existing WOFF2 inline with license attribution, without a new server route. |
| Coordinator | This contract and governing-document status, shared integration, fixtures, combined validation, emulator/browser evidence and PR delivery. All unassigned files stay read-only to leaves. |

`HomeData.kt`, `ManagementForms.kt`, agent/control/runtime/settings/media/layout
models and backends stay read-only to the presentation owners. Raise a concrete
dependency rather than editing a peer's files. Native Media3 adapters automatically
consume the changed palettes; the coordinator owns any further player integration.

## Evidence

Use the actual installed Kotlin/Compose/Coil APIs and official documentation as
needed, without dependency upgrades. Scoped pure/isolated compilation is allowed
outside shared build outputs. Avoid source-string tests and redundant suites for
cosmetic edits. Preserve useful existing tests; add regressions for actual new
logic such as bounded image processing or language-tag round trips.

The coordinator runs combined Android tests/lint/builds and phone checks after
writers finish, then installs on the **local emulator only** through the bound
device tools. Capture native Night/Day Home, Ask, details, management, Undo and
opaque states with credible fixture media, plus phone rendering. Show actual
results, with provenance and limits, to compare against the approved mocks.

**The physical Shield remains paused.** No reconnect, install, launch, input,
capture, unlock, wipe or flash is authorized by design approval. Firmware work
remains independently owned. Existing broader release/acceptance gates remain.
