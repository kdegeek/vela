# Coordinator synthesis and Sol mockup brief

All three independent reviews are complete:

- [Astra art direction](astra-art-direction.md): eight observations, 15 inspected captures.
- [Astra experience](astra-experience.md): six findings, 17 inspected captures.
- [Fable creative direction](fable-design-critique.md): identity, copy and material critique.

This brief resolves their disagreements. It is a concept assignment, not an
instruction to change the application. The user's request for tasteful, beautiful,
joyful modern liquid glass is the governing aesthetic preference.

## Recommended direction

**Warm cinematic glass — night water, morning light.** Artwork supplies emotion
and color. A small number of substantial, luminous glass surfaces carry controls.
The sail stays; champagne becomes edge light rather than brown/gold button paint.
Periwinkle marks the agent sparingly. Night is deep and inviting; Day is luminous
pearl with recognizable artwork, rather than cream laid over bleached pictures.

Use a warm, contemporary sans hierarchy as the primary proposal. A chosen display
face is permissible if it improves the work; identify the face and fallback.
Do not retain the default serif merely for continuity. The result should feel
desirable and original, not like an administration dashboard or a tvOS tracing.

There is no requirement to preserve the existing flat material, pill repetition,
brass fills or oversized wordmark. Solve hierarchy before adding optical effects.

## Decisions from the reviews

| Topic | Direction for the mockups |
| --- | --- |
| Home hierarchy | Remove repeated hero/row announcements. Compact selected-title context; show a complete content rail and meaningful Apps content. Clean scroll boundaries rather than chopped captions or hairline artwork peeks. |
| Shell | One quiet glass navigation plane. Keep all destinations discoverable with labels and a documented remote route. Distinguish entertainment from utilities, persistent selection from focus, and idle Ask from urgent status. Avoid target movement when status grows. |
| Material | Transmitted artwork color, coherent upper-edge light, restrained thickness/separation and dense reading regions. Crisp artwork stays outside the glass treatment. No pile of nested glass panes/buttons. |
| Focus | Retain the Astra reviewers' strong independent perimeter and separation against art. Add a restrained lift/rim response if helpful. Reject Fable's glow-only interpretation: highlights cannot carry focus by themselves. Show exactly one active target. |
| Ask | A contextual glass sheet over recognizable browsing. A clearly labeled field and deliberate Ask/Speak/Back choices; microphone never starts or submits automatically. Back remains visible/reachable. |
| Sparse Home | A finished, quiet apps-first composition. No new compulsory library-setup card or assistant billboard. A compact optional Library settings affordance may be welcoming without occupying the page. |
| Undo | One grouped, elegant glass receipt with Undo and acknowledgement. Keep the available Undo action obvious after acknowledgement, alongside or directly within the Arrange home control group. No timeout or multistep history claim. |
| People/settings | Person-first hierarchy, bounded fields, warm exact copy and a stable Back/Save region. Recovery is discoverable but secondary. Language names may be proposed rather than raw tags, clearly identified as a new widget concept. |
| Delight | Tactility, light, lovely proportions and immediate feedback. Any proposed motion is brief and purposeful, with a still/reduced-motion counterpart. No perpetual shimmer, giant orb, confetti or decorative waveform. |

## Factual qualifications

These corrections prevent subjective review proposals from becoming false
product or platform requirements:

- Undo is **conditional**, not a permanent first-run control. `HomeActivity.kt`
  observes a nullable receipt and exposes the later action only for the exact
  acknowledged receipt (lines 60–64, 127). The apps-only screenshot was captured
  after a layout change. Preserve the capability and improve its grouping.
- Settings does not start with Media access. `SettingsHub.kt` places Phone and
  Household before that section (lines 42, 44, 59); the reviewed image is scrolled.
  Reconsider hierarchy on design merit, not an incorrect initial-state claim.
- Autoplay off does not establish an ask-before-next-episode behavior. Use
  “Autoplay off” or equally accurate copy, not “asks before the next episode.”
- A warm field inside a cool panel is a visible palette disagreement, not by
  itself a proven runtime defect. Redesign the composition as one coherent family.
- `shield-compatibility.md` records stock Android 11/API 30 and separately an
  authorized LineageOS 22.2 image target. Neither was rechecked on hardware here.
  App `minSdk` remains 28. [RenderEffect blur](https://developer.android.com/reference/android/graphics/RenderEffect#createBlurEffect(float,float,android.graphics.Shader.TileMode))
  starts at API 31; [Compose's blur modifier](https://developer.android.com/develop/ui/compose/graphics/images/customize#blur)
  is ignored before Android 12. This limits those APIs, not every possible rendering
  technique. Specify a plausible cached/pre-softened-artwork glass tier and a
  polished opaque tier; do not promise or benchmark native real-time refraction.

## Deliverables

Own only `docs/design/explorations/liquid-glass-20261031/mocks/`.

Build **one finished recommended direction**, not three shallow style variants.
Include these full compositions, using credible local photography/original artwork
and believable invented catalog titles instead of fixture color blocks:

1. Populated Home Night and Day, with the same title, art and focus for comparison.
2. Apps-only Home, complete and welcoming with no fabricated library content.
3. Ask over Home, with empty/reviewed-draft states and compact working feedback.
4. Title details/Resume, exact item/source identity and safe initial Back focus.
5. A profile/preferences editor or management surface, showing a reachable Save.
6. A companion-phone surface at roughly 393 CSS pixels, with the same family of
   materials and a useful conversation/remote or explicit approval composition.

Use small companion specimens or prototype controls for the remaining states:
media-to-app focus with retained context; grouped Undo and its acknowledged state;
Restore defaults with Cancel focused; three agent states; a **read-only** TV
approval; busy/unavailable and unknown-effect copy; opaque/high-contrast Home and
Ask. This need not become a combinatorial board explosion. Prioritize polish.

Use the 960 × 540 logical TV canvas and 48/27 logical safe margins as the baseline.
Controls and prose must remain sofa-readable; solve fitting with hierarchy, not
tiny text. Keep correct media/app actions. Never draw Allow/Decline on TV; those
may appear on the paired phone. A title-context attachment, language picker or
relocated navigation is a marked design proposal, not an existing backend feature.

Provide:

- A self-contained, directly renderable HTML gallery/prototype (`index.html` or
  `inline.html`) with working view/theme/state controls. Inline CSS/JS; local
  assets and asset provenance. No server, framework install or runtime network
  dependency. For inline presentation, embed image data once and reuse it, or use
  static absolute image `src` attributes supported by T3's local-image inliner.
- An easy initial-view setting, so the coordinator can independently render each
  composition through `html_preview` without GUI automation or brittle HTML edits.
- Representative render exports if supported, plus a README identifying typography,
  sample content, visual choices, asset sources, proposed behavior and limits.

Use the available CSS skill for the authored styles. If new raster art would
improve the compositions, use the image-generation skill if its tools are
available; otherwise choose credible public assets with provenance. Do not settle
for placeholder rectangles or procedural color gradients as movie artwork.

Inspect rendered results with `html_preview` before delivery, including Night,
Day and at least the Ask/management/phone compositions. Record what was actually
seen; source code is not visual proof. The coordinator will inspect and show the
finished work in the parent thread through `html_preview` then `html_render`.
Outer HTML/body/gallery must stay transparent and use host theme variables; the
product frames may define their own Night/Day design colors. Avoid a large title
banner or padded outer card around the whole gallery.

Label these as concept mockups outside the product frame. Pure front-end state
changes demonstrate design only; no service, playback, model or saved-setting
success should be claimed. No production edits, builds, Git/PR changes, devices,
credentials, servers or infrastructure. The physical Shield remains paused.
