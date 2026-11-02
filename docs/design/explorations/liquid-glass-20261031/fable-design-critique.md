# Fable design critique: identity, warmth, copy and coherence

Exploration: liquid-glass-20261031 · Reviewer: Fable (independent creative-director critique) · 2026-10-31

Scope: identity, emotional quality, warmth, copy and visual coherence across the TV shell and the
phone companion, judged against the owner's positive brief: *tasteful, beautiful, joyful liquid glass*.
This is a critique and a creative direction, not a mockup. No production, Git, build, device or
infrastructure action was taken. The physical Shield was not contacted.

## 1. Verdict

**Would I want to live with this every evening? Not yet.** It is clean, legible, honest and inert.
Nothing in the frame is lit. The screens read as a well-set settings app that happens to show a poster,
and the warmth that exists lives almost entirely in a handful of sentences ("What would you like?",
"Keep layout") rather than in anything you can see.

Three visible reasons, all from the current captures:

1. **The shell and the artwork never touch.** The top bar is an opaque band; the backdrop starts under
   it with a hard seam and collapses into a rectangle on the right half of the screen. There is no
   "behind", so there is nothing for glass, or for the eye, to look through.
2. **Every surface is flat paint, and the paint fights itself.** Brass fill, periwinkle fill and a thick
   cream ring compete on every screen. In Day the brass becomes brown. Focus is a heavy outline, not a
   lift.
3. **The hospitality moments are typographic debris.** The first-run apps-only Home is four banners,
   three unframed bold words and 400 px of nothing. "Home updated · Undo · Keep layout" is a text row.
   Ask is a centred slab with its primary action drawn weaker than Back.

What already works and must survive the redesign: the entertainment-first order; the honesty of the
restore-defaults dialog copy; the deep indigo Night canvas; the sail glyph as a real, ownable mark;
the refusal to frame every control in a card; readable type sizes; focus you can see from a sofa.

## 2. Evidence inspected and limits

Inspected directly as images (all 1920 × 1080 emulator captures unless stated):

- `docs/design/evidence/home-entertainment-20261031/`: `home-media-night.png`, `home-media-day.png`,
  `home-media-focus-night.png`, `home-app-focus-night.png`, `home-apps-only-night.png`,
  `home-apps-only-day.png`, `home-updated-notice.png`, `restore-default-confirmation.png`.
- `docs/design/evidence/visual-corrections-20261031/`: `ask-night.png`, `ask-day.png`,
  `settings-night.png`, `settings-day.png`, `profiles-night.png`, `profiles-day.png`,
  `profiles-feedback-night.png`, `profile-editor-night.png`, `profile-editor-day.png`,
  `home-night.png` (pre-entertainment Home with the saved "Tonight / Something light" row).
- `docs/design/evidence/vela-phone-night-*.png` (three captures, desktop Safari at 1280 CSS px, not
  phone width), `vela-before-shield-night.png` and `tv-night.png` (pre-redesign baselines).
- Crops and pixel probes of the seams, the clipped captions, the progress nub and the row peek.
- Read as context only: `brief.md`, both evidence READMEs and manifests, `vela-design-language.md`,
  `VelaPalette.kt`, `VelaTokens.kt`, the launcher/design string literals and `phone.html` copy.

Limits: static captures only, so motion, focus timing, sound and D-pad feel are not observed. Artwork
is synthetic test-pattern fixture material; I critique the system's treatment of art, not the art.
Search, Apps grid, My list, House, Activity, title sheet, Now Playing, high contrast, large text and
the TV approval notice were not captured in the current build and are not judged here. Phone captures
are not phone-width, so only family resemblance is judged, not mobile layout.

## 3. Ranked observations

Each item: what is visible (file and region), why it matters for identity or warmth, and the move.

### 1. The shell and the artwork never meet

**Observed.** `home-media-night.png`: the top bar region is canvas colour; the backdrop begins at
y ≈ 170 with a hard horizontal seam (probe at x = 1600: y = 160 is `(14,16,36)` canvas, y = 175 is
`(84,49,86)` backdrop). The horizontal scrim fades the art to canvas by x ≈ 1000–1450, so the art
is a block pinned to the right half with a soft left wall. `home-media-day.png`: the same block
reads as a dusty mauve rectangle behind a black serif title; the design doc's "art reads washed, by
design" is visible and it does not read as design. The hard vertical edge at x ≈ 1440 and the
horizontal edge at y ≈ 600 are the fixture's own block boundaries, not a system fault; but the system
applies no softening, so real art with hard shapes (title cards, logos, letterboxing) will present the
same way.

**Why it matters.** Glass is a relationship between chrome and what lies behind it. Right now there is
no behind. The hero, the top bar and the rows are three unrelated layers on a dark sheet.

**Move.** Art runs under the top bar from the very top edge. The top bar becomes the first glass
element: a thin surface that catches the art's colour and a single warm edge-light. The hero text
sits on a soft tonal ledge, not a wall. The backdrop is always downsampled, darkened and vignetted
before display (see Constraints), so block edges, logos and bright frames dissolve. In Day the art
stays art; the glass gets lighter, the art does not get washed.

### 2. First-run Home is the loneliest screen in the product

**Observed.** `home-apps-only-night.png` / `home-apps-only-day.png`: four app banners at y ≈ 272–452,
then three unframed bold words at y ≈ 672 ("Arrange home", "Undo last home change",
"Library settings"), then roughly 400 px of empty canvas. "Undo last home change" sits in the footer
as a permanent resting control. `home-updated-notice.png`: "Home updated   Undo   Keep layout" is a
bare text row under the top bar with no container, no glyph and no sense that anything happened.

**Why it matters.** Apps-only Home is the hello. A new owner who has not connected a library sees
this first and judges the whole product by it. The Undo/Keep moment is the product's single most
reversible, trustworthy interaction and it looks like debug output.

**Move.** Treat the empty library as a guest, not a gap. One quiet glass invitation card after the
Apps row: the sail, "Connect your library to see what's on tonight", one action that opens Settings.
Footer actions collapse to a single quiet "Arrange home". The "Home updated" notice becomes a
floating glass receipt under the top bar: check glyph, "Home updated", **Undo** and **Keep layout** as
two equal pills; after Keep layout it recedes. Keep one-step Undo reachable afterwards, but from
Arrange home and the receipt, not as a bare bold word on Home. (This touches the product decision
that Undo stays beside Arrange home after Keep layout; I recommend the coordinator relax the
placement, not the capability.)

### 3. The pinned hero clips scrolled content with a hard edge, and the row peek is a hairline

**Observed.** `home-app-focus-night.png`: with Apps focused, the retained hero ("FIXTURE The
Pagination Observatory / FIXTURE Episode 1.001") stays pinned and the Continue watching captions
scroll under it; "FIXTURE Test Pattern" and "FIXTURE The Pagination Observatory" are sliced
horizontally at y ≈ 360–410 directly beneath the hero's metadata line, with no fade or mask. At the
bottom of the same capture the "Recently added" row shows only ~2 px coloured hairlines of tile tops
at y ≈ 1025, which reads as a rendering glitch rather than a peek. The hero also describes an
episode while focus is on the Settings app banner; retention is intended, but visually the screen
tells two stories at once.

**Why it matters.** These are system defects, not fixture. A premium material language cannot
survive raw clipping; glass without an edge treatment is just a rectangle with content leaking
through it.

**Move.** The hero sits on a glass ledge whose lower edge dissolves scrolled content with a gradient
mask. The next-row peek is either at least a third of a tile or none. When focus leaves media, the
retained hero recedes (lower opacity, slightly smaller) so it reads as memory rather than as a claim
about the focused app.

### 4. The top bar is a website header, not a TV shell

**Observed.** Every shell capture: serif "Vela" wordmark with the sail at ~44 px, seven text tabs
("Home", "Search", "Apps", "My list", "House", "Activity", "Settings"), a "Household" pill with an
"H" disc, and an "Ask Vela" pill of equal weight. The band occupies y ≈ 60–150. The lantern is the
second-loudest object on every screen, including when idle. "House" and "Activity" are vague as
destinations; "Household" as the default profile name makes the pill read like an organisation
account.

**Why it matters.** Identity is being carried by a navigation bar, and navigation bars do not carry
identity. The horizontal weight, not the 52 dp height, is the problem.

**Move.** The sail becomes the mark; the word "Vela" appears only on Home at rest. Tabs shrink to a
quiet lane. The profile chip is the disc alone unless focused. The lantern is small and luminous at
rest and earns size only in Working and Needs your OK. Rename "House" to "Home control" or "Rooms"
and "Activity" to "What Vela did" (or "Vela's work"); the default profile is the owner's first name
or "You", never "Household".

### 5. Ask is a slab, not a conversation

**Observed.** `ask-night.png` / `ask-day.png`: full-screen takeover; the shell disappears; a
1280 × 560 flat card with a 1 px border; "Ask" and "Speak" have no container while "Back" is a
pill, so the primary action is drawn weakest; the Night card and the Night Home canvas share no
light. In Day the focused field fill is `(220,212,196)` (`surfaceFocused`, warm khaki) inside a card
of `(222,226,255)` (`agentContainer`, cool lavender). That is a token conflict, not a taste note: a
warm neutral inside the cool agent tint.

**Why it matters.** Ask is where the product's warmest copy lives ("What would you like?", "Find
something to watch, set the mood, or make Vela yours.") and the surface undercuts it.

**Move.** Ask rises from the lantern as a glass sheet over the dimmed, still-visible Home. The field
is the only bright object. Ask and Speak are equal glass pills with Ask primary; Back becomes a hint
("Back closes") not a third button. Add an `agentSurfaceFocused` role so fields inside agent
containers stay in the cool family in both themes.

### 6. Three accents are fighting, and none of them is light

**Observed.** Brass is used as eyebrow text, tab underline, progress nub, primary button fill and
selected-chip border; periwinkle as the Ask pill, the Ask tile and agent panels; a thick cream ring
as focus on cards, list rows and fields. `restore-default-confirmation.png`: the brass "Restore
defaults" button is the warmest object on a cold scrim. `settings-night.png`: the focused
"Android settings" row is a 1740 px wide navy slab with a cream ring. `profiles-day.png`: the brass
primary button renders as the Day `accent` token, a brown (`#74511C` per `VelaPalette.kt`, not a pixel probe). `home-night.png` (older): the "Tonight" Ask tile uses
a sparkle glyph while the lantern uses the sail, so the agent has two symbols, one of which is the
generic AI-marketing star.

**Why it matters.** A glass language needs one light source and one cold counter-light. Brass as
paint cannot survive Day, and three focus signals from three families (fill, ring, scale) make focus
feel engineered rather than felt.

**Move.** Brass stops being a fill and becomes light: a warm edge highlight, the progress glow, the
selected tab's underline. Primary actions are lantern-lit glass, not brass slabs. Periwinkle remains
the only cold light and appears only where Vela acts. Focus is expressed as rising to the surface:
scale 1.04, warm rim brightening, a soft halo on the neighbours' edges. Retire the sparkle glyph.

### 7. Management copy is cold where the product is warm

**Observed** (current strings and captures): "Household profiles & memory", "Explicit memory",
"Recover household storage…", "New adult profile", "Audio languages · comma-separated tags, e.g.
en, es", "Request completed. Review the current state below." (`profiles-feedback-night.png`),
"Media access enabled / Apps that publish media sessions can appear in Now Playing." as the first
line of Settings, "Current profile · adult · subtitles automatic · autoplay off", the row subtitle
"Jellyfin" under "Continue watching" on Home. Phone: "Vela · Your TV, in reach", "Load profiles /
resolve conflict", "comma-separated BCP-47 tags".

**Why it matters.** Copy is the strongest warmth lever with direct evidence here. The product
promises "set the mood" and then speaks in schema.

**Move.** The table in section 7 keeps every statement exact and drops the plumbing vocabulary.
Settings opens with people and the phone, not with a permission status. Source labels on Home rows
appear only when more than one library is connected.

### 8. The phone belongs to a cousin, not the same family

**Observed** (`vela-phone-night-*.png`, 1280-wide desktop Safari, not phone width): serif wordmark
and sticky dark header; outlined pills with salmon text for both "Switch to Synthetic Alex" and
"Delete profile…" (a danger tint on a non-destructive switch); dense forms; the approval panel with
Allow once / Decline is correct for the phone. It shares the palette but not the material or the
voice. No mobile-width judgment is made from these captures.

**Move.** Define the phone variant of the material explicitly: "frosted paper", flat by default,
glass only for the conversation, the approval panel and the remote. Danger tint only on destructive
labels ending in "…".

## 4. Observed, hypotheses and constraints, kept apart

### Observed (static evidence above)

- Hard seam under the top bar (Night and Day), art confined to the right half, Day art washed.
- Captions clipped under the pinned hero; hairline row peek; hero retained across app focus.
- Apps-only Home: three unframed footer words, ~400 px empty canvas; Undo as a resting footer word.
- Notice row without container; Ask primary action weaker than Back; Day Ask field token clash.
- Brass renders brown in Day; full-width focused list rows; two agent glyphs (sail and sparkle).
- Settings leads with a permission status; profile and memory copy uses internal vocabulary.
- Phone captures not at phone width; danger tint on a switch action.

### Hypotheses (no evidence in the captures; Sol should not treat as fact)

- A 150 ms, scale-1.05 focus with a thick ring probably feels mechanical rather than pleasurable.
- A 300 ms settle plus 400 ms crossfade on the backdrop may feel laggy when browsing quickly.
- The 4 dp brass progress nub at low progress will look like a defect in the card corner.
- The hairline peek will vary with row content and font scale; it is likely worse at large text.
- The "H" disc will be the only avatar most households ever see unless profiles get images.

### Constraints the mock designer must design within

- **No live blur on the current target.** The Shield is Android 11 / API 30
  (`docs/design/shield-compatibility.md`); the app's minSdk is 28. Compose `Modifier.blur` and
  `RenderEffect` need API 31. Glass on stock Shield must be built from pre-downsampled, pre-blurred
  artwork bitmaps (produced once at image load), translucent tonal fills, gradient rims and
  highlight strokes. Refraction, lensing and live backdrop blur are out of scope for this target.
  A future 4K AOSP surface multiplies fill cost; translucency budgets must be set per screen.
- **High contrast turns translucency off**; every surface gets a solid border. The glass identity
  needs a defined flat fallback that still looks like Vela.
- **Reduced motion** keeps every state visible without scale or glow animation.
- **The overlay over third-party apps** stays Night palette, near-opaque, never focusable.
- **Approvals on TV are read-only.** Never Allow/Decline on the TV. The instruction stays "Press OK
  to allow once, or Back to decline." or "Answer on your paired phone."
- **Honest states.** No shimmer, pulse or motion that implies playback or completion the app has not
  observed. Working and Needs your OK are the only animated agent states.
- **Entertainment-first order, custom rows, Arrange home, one-step Undo and Restore default layout**
  remain. The look of the notice can change; its existence and semantics cannot.
- **System fonts only** is a rule from the superseded direction. A chosen serif is permitted as a
  proposal, but it is a dependency decision for the owner; the mock must show which face and
  state the fallback.

## 5. An original Vela identity: night water and a lantern

Vela is the sails. The material metaphor is not a frosted bubble on a bright room; it is **the
surface of dark water at night, lit by one warm lantern, with the content's light rising from
beneath.** Glass in Vela is that surface: artwork glows up through it, the lantern grazes across it.

- **Two lights, no paint.** The warm lantern light (what brass becomes) comes from one consistent
  direction, upper left, and shows as edge highlights, progress glow, the selected underline and the
  rim of whatever is focused. The cool starlight (periwinkle) is the agent's light and appears only
  where Vela is acting, asking or offered. Nothing else is coloured; colour comes from the art.
- **Hull glass.** Panels, the top bar, the Ask sheet and dialogs are translucent indigo (roughly
  55–70 % over pre-blurred art), with a 1 px warm top-edge highlight and a cool lower-edge shadow.
  In Day the same recipe is lighter and cooler, "morning water", never parchment paper over art.
- **Rising to the surface.** Focus means the object lifts toward the viewer: a small scale, a warm
  rim that brightens, a soft halo on neighbours. One family of signals instead of three.
- **A constellation mark.** Vela is a constellation; the sail glyph keeps its two triangles but is
  drawn as light with small star points at its vertices. It is the brand at rest, the lantern's glyph
  and the phone favicon. The word "Vela" appears on Home at rest and nowhere else in the shell.
- **Type.** If the owner accepts a chosen face, keep a serif for the hero title and page titles with a
  warmer transitional design and true italics; the platform default reads as a book, not an evening.
  If not, set titles in the sans at larger optical size and let the mark and the light carry the
  brand. Do not keep the default serif and call it identity.
- **Voice.** Short, warm, exact. Vela speaks like a good host: it tells you what it did, never what
  schema it used.

If a paragraph of the mock's rationale could be pasted under a tvOS screenshot unchanged, it is not
this identity. The difference is the single warm light and the water-dark depth; tvOS glass is bright,
sourceless and bubble-shaped.

## 6. Moments of delight (few, restrained, all honest)

1. **The lantern breathes.** While Vela works, the compact lantern pill carries a slow warm glow
   that rises and falls inside its glass; Needs your OK holds steady with the caution glyph. Reduced
   motion: a solid warm dot. No takeover, no text beyond the existing labels.
2. **Arrival.** On cold start the sail mark lights from the lantern side and the first row's art
   fades up beneath the glass, once, in well under a second. Never loops, never repeats on resume.
3. **The receipt.** "Home updated" floats in as a small glass receipt under the top bar with a check
   glyph and two equal pills, Undo and Keep layout. After Keep layout it recedes. Reversibility made
   visible and pretty.
4. **Progress as light.** On a Continue watching tile, progress is a warm light filling the bottom rim
   of the card's glass rather than a brass nub in the corner. Same 4 dp data, same announcement.
5. **Ask rises from the lantern.** The sheet grows from the lantern's position over the dimmed Home;
   the field is the one lit object; "What would you like?" stays exactly as written.

## 7. Copy: current, problem, proposed

| Where | Current | Problem | Proposed |
| --- | --- | --- | --- |
| Shell tab | House | Vague; sounds like a building | Home control (or Rooms) |
| Shell tab | Activity | Could mean anything | Vela's work |
| Profile chip | Household | Reads as an account, not a person | Owner's first name; "You" before a name is set |
| Settings title | Household profiles & memory | Database nouns | People & what Vela remembers |
| Profiles button | Explicit memory | Internal term | What Vela remembers |
| Profiles button | Recover household storage… | Alarming plumbing in a primary list | Move to a "Trouble?" group: Repair profile storage… |
| Profiles button | New adult profile | Clinical | Add a person (child/adult chosen inside) |
| Profiles intro | Each profile has its own preferences and saved memories. Profiles have no PIN protection. Home rows and My list are shared. | Correct but flat | Each person gets their own preferences and memories. Anyone in the room can switch; there is no PIN. Home rows and My list are shared. |
| Profile summary | Current profile · adult · subtitles automatic · autoplay off | Flag dump | Watching now · subtitles when needed · asks before the next episode |
| Feedback | Request completed. Review the current state below. | Sounds like a server | Done. Here's the current list. |
| Editor field | Audio languages · comma-separated tags, e.g. en, es | Asks a person to type codes | Preferred audio languages (choose; show names, store tags) |
| Settings first row | Media access enabled / Apps that publish media sessions can appear in Now Playing. | Permission status opens Settings | Move under Connections & permissions; "Now Playing can see other apps" |
| Home row subtitle | Jellyfin | Provider plumbing in a primary flow | Show only when more than one library is connected; otherwise omit |
| Home footer | Undo last home change | Permanent control for a transient act | Undo lives in the receipt and Arrange home; label "Undo" |
| Ask | What would you like? / Find something to watch, set the mood, or make Vela yours. | Good; keep | Keep verbatim |
| Ask footer | You can type, speak, or ask from your paired phone. | Good; slightly long | Type, speak, or ask from your phone. |
| Restore dialog | Replace your current rows with Continue watching, Apps, Recently added and My list? | Good; keep | Keep verbatim |
| Phone header | Vela · Your TV, in reach | Slogan in a title | Vela |
| Phone | Load profiles / resolve conflict | Engineering verbs | Refresh (conflict handling explained only when it happens) |
| Phone | comma-separated BCP-47 tags | Standards jargon | Language names with a picker |

Keep every "…" convention for confirm-first actions, every "Couldn't …" error, and the approval
instruction strings exactly.

## 8. Decisive creative direction

Go with **night water and a lantern**: dark, thin, warm-lit glass over the content's own light.
Not bright frosted bubbles, not tonal flat paint. Brass becomes light, periwinkle stays the agent's
cold light, focus is a lift to the surface, the sail is drawn as a small constellation. Day is the same
water in the morning, not parchment. The serif stays only if a chosen face is approved; otherwise the
mark and the light carry the brand.

Sol may show one alternate (a brighter frosted variant) only if it is finished enough to prove the
recommendation; a menu of half-finished directions is worse than none.

## 9. Do and don't for the Sol mock designer

**Do**

- Mock Home Night with credible artwork (real-looking posters and backdrops, not test patterns),
  with the art running under the top bar and the pre-blurred backdrop treatment made explicit.
- Show the same Home in Day with art intact and the glass lightened, not the art washed.
- Mock the apps-only first evening as a welcome, with the invitation card and a single quiet footer.
- Mock the "Home updated" receipt with Undo and Keep layout, and its state after Keep layout.
- Mock Ask as a sheet over the dimmed Home, Ask primary, Speak equal, Back as a hint.
- Show focus on a media card, an app banner, a pill and a list row using one signal family.
- Show the lantern in all three states: Ask Vela, Working, Needs your OK; and the read-only
  approval notice on TV with the OK/Back instruction.
- Show the flat high-contrast fallback of at least Home and Ask so the identity survives without
  translucency.
- Show one phone screen at 393 px wide (Home or Ask plus the approval panel) in the frosted-paper
  variant.
- Label every frame as a mockup; state the chosen serif, if any, as a dependency decision.

**Don't**

- Don't draw live blur, refraction or lensing that API 30 cannot render; build glass from
  pre-blurred art and tonal translucency and say so.
- Don't put Allow/Decline buttons on any TV frame.
- Don't add sparkles, orbs, waveforms, gradients-for-their-own-sake or any "AI" iconography; the
  sail is the only agent symbol.
- Don't turn Home into a dashboard of status tiles; entertainment leads, the agent is compact.
- Don't frame every control; glass is for the shell, the sheet, the receipt, dialogs and focus.
- Don't invent playback states, autoplay previews or shimmer that implies the app is doing
  something it has not observed.
- Don't copy tvOS wholesale; if the material has no light source, it is not Vela's.
- Don't use the platform default serif as the identity; choose or drop.

## 10. Handoff summary

Highest-impact findings, in order: the shell and the art never meet (seam, confined art, washed Day);
the apps-only first run and the text-row notice; the pinned hero clipping content; the website-header
top bar; the Ask slab with a Day token clash; three accents and brass turning brown; schema copy in
management; a phone that shares palette but not material. Constraints that shape the answer: no live
blur on API 30, high contrast and reduced motion fallbacks, read-only TV approvals, honest states,
and the preserved entertainment-first/Undo product boundaries.
