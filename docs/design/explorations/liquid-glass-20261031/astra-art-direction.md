# Vela: independent art-direction review

2026-10-31 · Astra · Design review only · Recommended direction: **warm cinematic glass**

The current screens establish a clear, orderly appliance. They do not yet give the owner's love of liquid glass a visual expression. Their strongest assets are readable controls, identifiable artwork frames, generous margins and unmistakable focused cards. Their main weakness is hierarchy: the brand, utility controls, headings and form furniture often receive more visual ceremony than the entertainment or person being served. Adding blur to the current rectangles would leave that problem intact.

Keep the entertainment-first product. Recompose its hierarchy, replace the compulsory serif/brass/pill identity, and make a few control surfaces feel like substantial, luminous glass. Artwork should remain crisp and emotionally dominant; the material should make browsing feel tactile and welcoming.

## Evidence and limits

Read [the exploration brief](brief.md) first, then both evidence READMEs/manifests and the relevant visual/product sections of [the design language](../../vela-design-language.md) and [component contract](../../vela-component-contract.md). No other reviewer's report informed this assessment.

The following **15 actual PNGs** were opened and visually inspected with the image tool:

| Evidence set | Inspected files | Use in this review |
| --- | --- | --- |
| [Current Home](../../evidence/home-entertainment-20261031/README.md) | `home-media-night.png`, `home-media-day.png` | Populated composition, title hierarchy, theme treatment |
| Current Home | `home-apps-only-night.png`, `home-apps-only-day.png` | Sparse composition, application artwork, utility actions |
| Current Home | `home-media-focus-night.png`, `home-app-focus-night.png` | Focus appearance and content/header relationship at the captured positions |
| Current Home | `home-updated-notice.png`, `restore-default-confirmation.png` | Visual expression of reversible layout changes and confirmation |
| [Other surfaces](../../evidence/visual-corrections-20261031/README.md) | `ask-night.png`, `ask-day.png` | Composer, panel, color and action hierarchy |
| Other surfaces | `profiles-night.png`, `profiles-day.png`, `profile-editor-day.png` | Management hierarchy, typography and fields |
| Other surfaces | `settings-night.png`, `settings-day.png` | Grouping, choices, negative space and focus |

These are 1920 × 1080 Android 14 Google TV emulator captures. Home provenance identifies an integration on base `20502607`; the earlier correction set identifies base `2afc4ab`. The brief names `4006f179` as current source. This review evaluates the supplied rendered states, not a newly rendered build of that commit. The older Home compositions were deliberately not used.

The populated Home titles and color-block artwork are **synthetic fixture data**. The observations below do not condemn those blocks as the product's artwork direction, mistake two fixture titles for a normal library size, or attribute third-party app-banner aesthetics to Vela. Night and Day populated captures show different selected titles; Settings also has different captured focus states. They are theme examples, not controlled pixel-for-pixel comparisons.

The READMEs document tests and exercised operations; those results are not visual acceptance. Static captures cannot establish animation quality, readability at an actual sofa distance, D-pad timing, playback truth, high-contrast rendering, enlarged text, phone fidelity or glass performance. No runtime defect is asserted here. No production files, builds, Git state, devices, GUI sessions, services or infrastructure were changed or contacted. The physical Shield remains paused.

## Ranked observations

Each **Observed** statement describes the images. **Design reading** is an interpretation, not a measured user response. **Proposal** is an exploration instruction, not a claim about present behavior.

### 1. Populated Home spends too much of its first view announcing content

**Observed:** Both populated themes place “Continue watching” above a large serif title, metadata beneath it, then a second “Continue watching” heading and a separate “Jellyfin” line before the first artwork. At this captured scroll position, artwork begins roughly 46% down the screen; Apps artwork is only a shallow strip at the bottom. This vertical sequence exists independently of the fixture's image quality or small catalog.

**Design reading:** The screen has an entertainment-first order but a heading-first composition. A selected title is treated like a page heading while the actual things to browse start late. The long fixture title exaggerates its width, not the repeated structural announcements.

**Proposal:** Remove the duplicated hero kicker, compact the selected-title header, and put the source beside the row heading. Recover vertical space for a complete first media rail and a useful Apps rail. Do not introduce a taller promotional hero in the name of “cinematic.”

### 2. Material and depth are almost absent from the interface itself

**Observed:** Apps-only Home and Profiles use broad, uniform canvases. Pills are solid tonal fills. Ask is a large solid blue/lavender panel with a perimeter stroke. Populated Home has an atmospheric artwork backdrop, but the shell sits in a distinct opaque top band. The visible interface does not convey transmitted background light, a reflective edge or separation through shadow.

**Design reading:** This is consistent with the superseded flat contract. It now makes the interface feel more like a well-arranged control panel than a desirable object. Rounded corners alone do not provide the material quality the owner asked for.

**Proposal:** Introduce one clear depth stack: atmosphere/artwork, crisp content, floating glass controls, then a denser glass sheet when a task needs it. Spend visible material detail on navigation, the compact agent entry, a layout receipt and task sheets. Do not glaze every media tile.

### 3. The serif, brass and parchment combination feels formal rather than playful

**Observed:** The wordmark, selected media title, Profiles title and Ask question all use a traditional serif. Day combines cream canvas, beige controls, brown selected/primary accents and lavender agent surfaces. Night combines near-black indigo, cream type and pale gold. Most supporting type is a comparatively neutral sans.

**Design reading:** The combination suggests an editorial or institutional interface to me, especially on Profiles. It offers warmth, but mostly through heritage cues. It does not supply much of the freshness or tactile delight sought in this brief. This is a judgment about this combination and scale, not a claim that serif type is inherently dated.

**Proposal:** Use a warm, open-aperture sans throughout the interface, with medium display weights and deliberate size contrast. Retain the sail as a small identifying mark. Make amber a restrained light accent rather than the default solid button paint; move Day from beige/brown toward luminous pearl and deep ink. Do not preserve the serif merely to preserve continuity.

### 4. Utility chrome has more weight than its everyday role warrants

**Observed:** Across Home and management, the large Vela wordmark precedes seven substantial text tabs. Household and Ask Vela are the header's two prominent filled capsules. Profiles repeats the capsule shape for refresh, creation, recovery and profile actions. Some unrelated operations have similar visual mass. Focused media cards, by contrast, are clearly distinguished by an outset light ring and enlargement.

**Design reading:** There are too many equally assertive pieces of furniture. The agent is compact relative to a chat dashboard, but its idle capsule still competes with the content. Repeated pills make a selection, a destination and a consequential action look like members of the same toolbar.

**Proposal:** Quiet the wordmark and idle utilities; keep their labels. Let a shared glass navigation surface provide cohesion, with flat controls inside it. Use rounded rectangles for task buttons and fields, reserving capsules for genuinely compact identity/status elements. Preserve the decisive focus signal; do not replace it with a decorative glint.

### 5. Apps-only Home is honest but visually unfinished

**Observed:** In both themes there is one four-item app row, a generous gap, three widely spaced utility labels, and a large remaining uninterrupted canvas. The artwork belongs to Live TV, Play Store, Settings and YouTube. There is no fabricated media or oversized agent pitch.

**Design reading:** Removing unavailable content is correct. The resulting composition feels like a launcher awaiting the rest of its page because the open space has no material treatment and the utilities do not form a clear group. The app banners' older visual styles are not a Vela design defect.

**Proposal:** Treat this as a finished sparse state: maintain recognizable app banners and generous breathing room, use a very quiet ambient background, and group layout/library utilities in one compact place associated with the rail. Do not fill the space with invented recommendations, setup promotion or an agent billboard.

### 6. Ask feels like a form submission rather than a welcome interruption

**Observed:** A centered panel occupies about two thirds of the width. The large serif question, long introductory sentence, floating field label, focused input, action row and bottom help sentence are all stacked inside it. The focused field has a strong single ring. Ask is visibly subdued in this empty-input capture; Back has a conspicuous filled capsule. Day introduces a beige field inside a lavender panel on cream.

**Design reading:** The multiple fills and form conventions make the experience feel procedural. The warm copy is doing more work than the composition. The subdued Ask is not evidence of a broken button; it is consistent with the empty state shown.

**Proposal:** Design a denser, softly transmitted glass sheet with one short heading, one prominent input and a clear action hierarchy. Keep the field label and keyboard/voice/paired-phone routes, but subordinate explanatory copy. Use the agent hue as a small identity signal rather than a whole purple slab. Give the sheet a relationship to the surrounding entertainment context where that context is available.

### 7. Management is ordered around operations before people

**Observed:** Profiles places Refresh profiles, New adult profile and Recover household storage above its explanatory paragraph and the Household identity. Preferences and Explicit memory sit below that identity. The captured editor gives simple fields nearly the full safe width. Settings shows large vertical separations between labels and choices, with extensive open space on the right. The Settings capture may be scrolled; its missing upper title is not treated as a defect.

**Design reading:** The interface foregrounds administration rather than ownership. Large text and space are beneficial for TV, but the current full-width forms and equal-weight operations do little to make personal settings feel considered.

**Proposal:** Give the profile identity and its ordinary preferences the first visual position. Put recovery in a clearly labeled, readily discoverable maintenance group with its existing confirmation. Constrain form reading width, align label/control rows, and make one softly luminous management surface carry related controls. Keep the no-PIN/shared-home explanation visible and exact.

### 8. Reversible customization deserves a stronger visual object

**Observed:** The update capture shows “Home updated,” “Undo” and “Keep layout” as uncontained words under the header. Apps-only Home exposes “Undo last home change” among separated bottom utilities. The restore dialog names the replacement rows and shows distinct Cancel and Restore defaults controls.

**Design reading:** The explicit dialog is a useful clarity baseline. The update receipt is much less visually cohesive; the relationship between status and recovery actions depends mostly on proximity. This is a presentation opportunity, not evidence that Undo fails or disappears incorrectly.

**Proposal:** Make the update message and actions one modest glass receipt, with Undo the unmistakable recovery action. Keep it inside the safe area and out of artwork/label paths. Preserve the persistent available Undo entry and the honest one-step/session-only behavior; do not depict a durable history stack or a fleeting toast as its replacement.

## Recommended visual direction

**Warm cinematic glass:** a quiet room, vivid pictures, and a small set of polished objects catching the light. The emotional reference is evening light through substantial clear or smoked glass, not a neon gaming dashboard and not a replica of an Apple home screen.

Retain the sail, readable remote focus, restrained navigation and the semantic distinction of the agent. Challenge the required serif, the universal pill, brass-as-primary-button, flat-only surfaces and the Day treatment that washes the backdrop into parchment. These are visual choices; none is necessary to preserve the product's effects or approvals.

### Palette and typography

| Role | Night starting point | Day starting point | Character |
| --- | --- | --- | --- |
| Canvas | `#14151D` | `#F4F1EA` | Charcoal with a trace of plum / neutral warm pearl |
| Dense reading surface | `#252630` | `#FBF9F4` | Stable local contrast beneath words |
| Primary ink | `#F8F5EF` | `#20222B` | Soft white / deep ink, never light gray body copy |
| Supporting ink | `#C7C7CF` | `#52545E` | Clearly subordinate without fading away |
| Warm accent | `#E6C7A0` | `#765238` | Champagne light / restrained warm umber, small areas |
| Agent identity | `#BBC2FF` | `#454FA0` | Periwinkle retained as a localized signal |

These are mockup starting colors, not contrast-certified production tokens. Choose solid ink first, then tune the composited glass below it. Artwork supplies most saturation. Decorative warmth must not resemble caution status; retain glyphs and explicit words for caution, failure, completion and agent states.

Typography should have open counters, a generous lowercase, moderate width and softly human proportions. Use regular body text, medium display text and semibold labels; avoid thin geometric display type, all-caps navigation and excessive tracking. A consistent sans hierarchy will feel more intentional than the current automatic serif/sans split. Mock with an available face and identify it in the handoff; a new font is not implicitly approved for implementation. The sail carries continuity even if the wordmark becomes a modest sans.

### Material construction

The numbers below describe a visual recipe at **960 × 540 logical units**; the supplied captures are twice that in pixels. They are not renderer/API requirements or performance claims.

| Material | Where it belongs | Starting recipe and visual test |
| --- | --- | --- |
| Clear smoked / pearl glass | Navigation surface, compact utility/agent presence, layout receipt | Approx. 55–65% charcoal tint at Night or 65–75% pearl tint in Day over locally softened background. Blur equivalent around 12–18 logical units. Background color should visibly pass through, while fine content detail disappears beneath labels. |
| Dense frosted glass | Ask, management grouping, confirmation sheet | Approx. 80–90% local tint with 18–26 units of background softening. Enough transmitted color to belong to the scene; a stable reading bed under text. Use opaque fields and ordinary flat controls inside this glass. |
| Optical edge | The outer boundary of a floating surface | A 0.5–1 unit light rim, strongest on the upper/leading edge, with a restrained darker return edge. Suggest 2–3 units of thickness through edge shading; avoid a uniformly bright outline around every object. Refraction, if depicted, stays in a narrow perimeter band and never bends text. |
| Separation | Floating controls and task sheets | A short contact shadow plus a broad faint shadow, roughly 2–4 units offset and 8–16 units softness. Day needs a slightly darker lower edge to remain legible on pearl. No giant black halo. |
| Focus | Exactly one active target per mock | A dedicated opaque 3-unit ring with 2–3 units of separation, plus a clear fill or restrained scale change. Contrast it against both art and glass. Material rims remain much quieter, so glass does not make everything look focused. |

Use a coherent lighting direction. One soft edge reflection is sufficient; no animated shine is required to prove liquid glass. Major glass panels can use 18–24 unit corners, navigation 14–18, fields and task controls 10–12, artwork 10–12. Make nested radii concentric. Do not stack a glass button on a glass pane on another glass pane.

Backgrounds must give the glass something to transmit. On populated Home, use the selected content's photographic backdrop with local scrims, letting the subject live primarily in the right half. Keep row artwork untouched. On sparse Home and management, use a low-contrast neutral light field with a small warm/cool shift, not a fabricated content image or saturated aurora. Day should retain the backdrop's recognizable subject and color on the right while protecting the reading area with pearl; it need not bleach the whole image. Glass should remain visibly glass on both sparse and populated screens.

## Concrete handoff: 12 mockup instructions

These are proposals for the separate mock designer. They do not authorize implementation or new product behavior.

1. **Build a real TV composition first.** Use a 1920 × 1080 artboard corresponding to 960 × 540 logical units, retaining 48-unit side and 27-unit top/bottom safe margins. Set type and controls for viewing distance, not desktop screenshots. Present the same content and focus state in the primary Night/Day pair so material differences can be judged fairly.

2. **Make the shell one quiet, luminous instrument.** Target a 48–52-unit-high glass navigation surface. Keep all seven destinations, profile identity and a labeled Ask Vela entry; do not add a second navigation band. Reduce the wordmark to about 20–22 units of type, and make idle utilities flat within the pane. Show a selected-tab marker distinct from the actual D-pad focus ring. Test the longer “Needs your OK” agent state without shrinking labels into illegibility.

3. **Recover the first viewport for entertainment.** Remove the repeated “Continue watching” hero kicker. Start with a 52–60-unit selected-title/metadata header, title about 28/32 and metadata 14/20. Place the source on the row-heading baseline. With a standard one-line title, aim for one complete Continue watching rail and an Apps rail with usable labels before the bottom safe edge. A long title or enlarged text may require more scroll; do not solve it by microscopic type.

4. **Use a consistent, economical rail rhythm.** Start with 200 × 112.5-unit media art, 16-unit horizontal gaps, 12 units from heading to art, 8 from art to labels, and up to two 20-unit label lines. Try app banners around 144 × 81 units with recognizable original proportions. Use 16–24 units between completed rails. Keep Recently added and My list in the default sequence below, and show that custom order/style still works; do not make the layout dependent on only one favored rail arrangement.

5. **Supply credible, varied artwork before judging the material.** Use coherent cinematic images with one warm human scene, one cool/dark scene and one bright or detailed image. Give titles believable lengths, including a two-line case. Make wide art, poster art and backdrop crops belong to the same depicted title. Do not recreate the fixture patterns or grade Vela by app vendors' banner designs. Do not invent playback capability: the Home tile may still open details, and a play/resume label requires the corresponding supported effect.

6. **Make glass visibly substantial, but leave the pictures alone.** Apply the material recipe to navigation and sheets; preserve opaque, crisp media/app art. Ensure a pane crosses enough background variation to show transmission without placing text directly over a face or bright detail. Show the focused card's independent ring. Include a small material comparison on dark, bright and busy backgrounds outside product UI; beautiful blur on one empty gradient is insufficient evidence.

7. **Finish apps-only Home as its own composition.** Omit the media-derived header when media is absent. Start Apps promptly under navigation, keep the sparse atmosphere calm, and place Arrange home, available Undo and Library settings in one compact associated utility group. Leave real negative space. No fake library, recommendations, marketing hero or automatic enlargement of Ask to occupy it.

8. **Redesign Ask as a single intentional sheet.** Try a width of 560–620 units, about 24 units inner padding, and content-led height. Use a short sans heading at 26–28, a legible labeled input, and one line of supporting guidance at 16. Retain Ask, Speak and Back with true empty/ready states; keep Back visually secondary but clearly available. Show the idle form and a separate truthful agent-state specimen. Do not imply that suggesting a task means it ran.

9. **Give management a person-centered hierarchy.** Mock Profiles or Appearance in the same material family. Use a reading/form width around 560–640 units rather than stretching each field across 864. For Profiles, lead with Household identity and ordinary preferences, then creation and maintenance; retain the explanation that profiles lack PIN protection and Home/My list are shared. A related control group can share one dense glass surface; its child controls stay flat. Recovery remains discoverable and keeps its confirmation.

10. **Make Undo a legible receipt, not decoration.** Show a stable “Home updated” group with Undo and Keep layout, approximately 44–48 units tall when it fits one line, growing if necessary. Place it in a reserved content lane so it does not cover a title or focus ring. Include the available “Undo last home change” state and Restore defaults confirmation as small companion specimens. Preserve one-step/session-only semantics and exact replacement copy; do not suggest multistep or cross-session history.

11. **Prove that delight survives truthful and accessible states.** Include one read-only TV approval specimen with the exact hint “OK to allow · Back to cancel,” not a new clickable Allow button. Identify the target and effect. Include an unknown-effect state using caution plus words, separate from success. Show an opaque high-contrast fallback with the same geometry and a stronger ring. Static material should remain beautiful without continuous shimmer, parallax or animated backdrop motion; any proposed movement must have a reduced-motion counterpart.

12. **Translate the family to one phone surface, then stop.** A roughly 390-unit-wide phone companion can share type character, pearl/smoked material, sail and status semantics while using a single-column touch layout and at least 44-unit controls. Do not squeeze the TV header onto it. If approval is shown, use the existing paired-phone route and boundaries. Deliver the populated Night/Day pair, sparse Home, Ask, one management surface and this phone view as clearly labeled concepts outside their product UI, with the key state specimens alongside. No claim of native implementation or device validation.

## Pitfalls to reject in the mock stage

- **Blur as the whole material.** A uniform translucent fill over a gradient looks like frosted plastic. The edge, transmitted color and modest separation must work together; arbitrary opacity on the old panel is not enough.
- **Glass everywhere.** Glazed posters, nested translucent buttons and many independent reflective capsules erase the distinction between content and controls. Give related controls one outer material surface.
- **An Apple imitation.** An SF-like type choice, enormous floating pill dock, colorful orb, rainbow edge and wallpaper-led hero would borrow recognizable cues without solving Vela's seven-destination, D-pad and content needs. Vela's sail, content-first rails, warm light and explicit state language should supply its identity.
- **A luxury-hotel costume.** Keeping cream, gold buttons, oversized serif headings and then adding glass would make the existing formality more ornate. Warmth must also come from friendly proportions, clear hierarchy and inviting artwork.
- **Focus confused with sparkle.** A glass rim is not selection, a glow is not approval, and amber light is not completion. Focus and status need independent visual signals and explicit labels where appropriate.
- **One flattering wallpaper hiding weak contrast.** Beautiful art can mask unusable glass. The bright/busy/absent-art cases and Day theme are part of the same design, not later cleanup.
- **A concept that silently changes the product.** Do not add autoplay, speculative recommendations, new approval controls, durable Undo history, fake playback success or private-profile promises to make the screen seem richer.

## Hypotheses and constraints for later validation

**Hypotheses, not established findings:** the new type/color combination may feel warmer and more contemporary; the compact header may improve browsing; glass may make sparse Home feel finished; a grouped receipt may make Undo easier to understand. These require owner judgment and later observed use. The captures do not prove that current users miss any action or cannot read any text.

**Unverified implementation constraints:** real-time backdrop sampling, blur/refraction cost, rendering availability, motion quality and device-level contrast are unknown here. The mock designer should specify the desired result and an opaque fallback, not select a graphics library or promise Shield performance. A newly chosen font/art asset needs a later availability and licensing decision. No implementation commitment follows from a concept.

**Non-negotiable continuity:** entertainment-first default rows; saved custom layouts and their real actions; compact agent presence; exact targets and honest effect states; current profile/controller boundaries; read-only TV approval surfaces; existing remote/paired-phone confirmation routes; visible, truthful Undo and Restore defaults. Beauty is not a reason to soften those distinctions.

The mock stage should pursue this one coherent direction first. Its success should be judged from finished images: does entertainment lead, does the glass read as material in both themes, and do Ask and management feel like equally considered parts of the same home?
