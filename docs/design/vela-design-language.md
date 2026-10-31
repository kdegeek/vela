# Vela design language

Status: governing direction, 2026-10-31. Baseline source: `0809edfb31d2fac467dc55e2b67fd93d9cf777df`.
Companion contract: [vela-component-contract.md](vela-component-contract.md) defines the Kotlin/CSS APIs,
file ownership, work packages and acceptance. This document defines what those components must look and
behave like. Where this document and `vela-tv-system.md` differ on visual tokens, this document wins;
the product, agent, storage and security contracts in `vela-tv-system.md` are unchanged.

Native Compose is canonical. The phone companion follows the same semantic language using native HTML
controls. No new dependencies, fonts or raster assets are introduced by this direction.

## 1. Direction in one paragraph

Vela is a calm living-room appliance: **content and artwork lead, controls stay quiet until focused,
and the agent is a distinct, honest presence.** The night canvas is deep indigo, the day canvas warm
parchment; brass is the single brand accent and the colour of the "lit" focus state; a cool starlight
periwinkle is reserved for the agent. Headings are set in the system serif, everything else in the
system sans. Surfaces are flat and tonal: no glass, blur, drop shadows, decorative gradients or card
grids that frame every control. The only gradients are the functional legibility scrims over backdrop art.

What changes from the baseline screenshots (`evidence/tv-day.png`, `tv-night.png`):

| Baseline | New direction |
| --- | --- |
| Two header bands: wordmark + tagline + buttons, then ten outlined pills | One 52 dp top bar: sail mark + wordmark, seven text tabs, profile chip, agent "lantern" |
| Profiles, Routines, External control as top-level tabs | Reached from the Settings hub and the profile chip; Back returns to Settings |
| Every control is an outlined pill at rest | Quiet tonal controls at rest; focus is unmistakable (brass fill + outset ring) |
| Hero scrolls away with rows; 14 sp body text | Compact fixed hero (96 dp) over the backdrop; 16 sp minimum paragraph text |
| Empty/error panels as full-width cards | Status panels for page-level states; compact in-row status strips for row failures |
| "Now Playing needs attention" banner docked permanently when access is missing | Bar only for a live session; access problems live in Settings and Now Playing details |
| Agent state is a muted tagline | Agent lantern in the top bar, Activity task panel, read-only approval notice, matching overlay |
| Phone: auto-fit grid of bordered section cards | Single-column flow; container-query two-column layout on wide screens; flat sections |

## 2. Identity elements

These five elements make a screen recognisably Vela. Use all of them; add nothing ornamental.

1. **Night indigo / day parchment canvases** with tonal surfaces in the same hue family.
2. **Brass accent** (`accent`). It means "brand, selected, focused, progress". It is never used for
   warnings.
3. **Starlight agent colour** (`agent`). It appears only where Vela (the agent) is acting, asking or
   offered: the lantern, Activity task panel, approval notice, overlay border, Ask actions and
   `AskAgent` tiles. Never for ordinary focus or selection.
4. **Serif display type** (system serif) for the wordmark, hero title and page headings; sentence case.
5. **The sail glyph** (two triangles: mainsail and jib; Vela is the "Sails" constellation), drawn as
   a vector path beside the wordmark, on the Ask lantern, and as the phone favicon/header mark.

Voice: short, warm and exact. Buttons are verbs ("Play", "Resume", "Search in Plex", "Ask Vela").
A label ending in "…" opens a confirmation. Copy states the real effect: "Search in Plex" never implies
playback; an unknown outcome says "effect unknown, check before retrying". No provider plumbing
(ports, source IDs, schema names) in primary flows; it may appear in Settings detail pages only.

## 3. The canvas and measurement

- The design baseline is **960 × 540 dp**. The owner's Tube currently reports a 1920 × 1080 output override and density 320, matching that baseline. Pixel output and density vary independently; 4K does not guarantee a particular dp canvas. Use actual Compose constraints/configuration and tolerate larger or shorter surfaces without changing the owner's display settings.
- **Safe area: 48 dp horizontal, 27 dp vertical** (Android TV overscan guidance; replaces 40/24).
  Text and focusable controls stay inside it. Backdrop art and row scrolling may extend to the edge.
- Usable content width: 864 dp. Reading width for paragraphs: **max 560 dp** (~60 characters at 16 sp).

## 4. Colour

### 4.1 Semantic roles (TV, sRGB)

Every component consumes roles, never literals. Values were contrast-checked (WCAG 2.x relative
luminance); ratios below are the assertions the palette test must keep.

| Role | Night | Day | Night HC | Day HC | Purpose |
| --- | --- | --- | --- | --- | --- |
| `canvas` | `#0E1024` | `#F6F1E7` | `#000000` | `#FFFFFF` | Screen background |
| `surface` | `#1B2036` | `#FFFBF3` | `#000000` | `#FFFFFF` | Panels, dialogs, bars |
| `surfaceRaised` | `#262C47` | `#EAE4D8` | `#1C1C1C` | `#EDEDED` | Resting control fill, art placeholder |
| `surfaceFocused` | `#343C60` | `#DCD4C4` | `#2A2A2A` | `#DADADA` | Focused card/list-row fill |
| `ink` | `#F6F1E7` | `#171B2C` | `#FFFFFF` | `#000000` | Primary text |
| `inkMuted` | `#C3C5D3` | `#434758` | `#E8E8E8` | `#1F1F1F` | Supporting text, unfocused labels |
| `inkSubtle` | `#9A9EB4` | `#5C6073` | `#D0D0D0` | `#333333` | Tertiary metadata only |
| `line` | `#363C58` | `#D8D1C3` | `#BDBDBD` | `#4D4D4D` | Decorative dividers (non-essential) |
| `lineStrong` | `#8D91A5` | `#6B6F80` | `#FFFFFF` | `#000000` | Field borders, essential boundaries |
| `accent` | `#E5CA91` | `#74511C` | `#FFD966` | `#5A3A00` | Brand, focus fill, selected, progress |
| `onAccent` | `#231C0C` | `#FFFFFF` | `#000000` | `#FFFFFF` | Text/glyph on `accent` |
| `accentStrong` | `#F3DFB2` | `#5A3E12` | `#FFE699` | `#3F2800` | Focused primary button, pressed accent |
| `focusRing` | `#F6F1E7` | `#171B2C` | `#FFFFFF` | `#000000` | Outset focus ring |
| `agent` | `#AEB9FF` | `#3A4AB5` | `#BCC6FF` | `#1C2B94` | Agent presence, agent actions |
| `onAgent` | `#11163C` | `#FFFFFF` | `#000000` | `#FFFFFF` | Text on `agent` |
| `agentContainer` | `#262E63` | `#DEE2FF` | `#1C1C1C` | `#EDEDED` | Agent panels, resting agent buttons |
| `caution` | `#FFB870` | `#8A4B00` | `#FFC285` | `#6B3600` | Needs attention, unknown effect |
| `danger` | `#FFB4AB` | `#A82428` | `#FF9C94` | `#8C0009` | Errors, destructive actions |
| `onDanger` | `#690005` | `#FFFFFF` | `#000000` | `#FFFFFF` | Text on `danger` fill |
| `success` | `#9FD8A8` | `#2E6A3A` | `#93E6A2` | `#1A5126` | Confirmed/observed completion |

Measured contrast (Night / Day): ink on canvas 16.7 / 15.2; inkMuted on surfaceRaised 8.0 / 7.3;
inkSubtle on surface 6.1 / 6.0; lineStrong on canvas 6.0 / 4.4; accent on canvas 11.8 / 6.4;
onAccent on accent 10.6 / 7.2; agent on canvas 10.0 / 6.6; onAgent on agent 9.3 / 7.4;
caution, danger, success on surface ≥ 9.4 / ≥ 6.3. The implementation tests must verify high-contrast text pairs at ≥ 7:1; do not treat the computed design table as executed test evidence.

Rules:

- `accent` vs `surfaceRaised` must stay ≥ 3:1 so the focus fill change is a luminance change, not a hue change.
- `line` is decorative (≈1.7:1). Any boundary a user must perceive (field, focused ring, HC panel) uses `lineStrong` or `focusRing`.
- Status is never colour alone: every `caution`, `danger`, `success` or `agent` state also has a glyph and a word.
- Legibility scrims are `canvas` at alpha, not black. Night uses scrims; Day uses the same (art reads washed, by design).
- **High contrast** (`settings.highContrast`) selects the HC palette *and*: hides backdrop and ambient art
  (card artwork stays, it identifies content); every surface and control draws a 1 dp `lineStrong` border;
  focus ring is 4 dp; no translucent fills anywhere; scrims are not drawn.
- The accessibility **overlay** over third-party apps always uses the Night palette (Night HC when high contrast is on).
- QR codes are always `#0E1024` on `#F6F1E7` regardless of theme (scanner reliability).

### 4.2 Phone tokens (OKLCH)

The phone follows the phone's own light/dark preference (not the TV's appearance setting). Same role
names in kebab case. Values are the OKLCH conversions of the TV hex values above, so both surfaces
match. Light = Day column, dark = Night column.

| Token | Light | Dark | Hex fallback (light / dark) |
| --- | --- | --- | --- |
| `--canvas` | `oklch(95.9% 0.014 85)` | `oklch(18.3% 0.041 277)` | `#F6F1E7` / `#0E1024` |
| `--surface` | `oklch(98.9% 0.011 85)` | `oklch(25% 0.042 273)` | `#FFFBF3` / `#1B2036` |
| `--raised` | `oklch(92% 0.017 85)` | `oklch(30.1% 0.05 274)` | `#EAE4D8` / `#262C47` |
| `--ink` | `oklch(22.7% 0.034 273)` | `oklch(95.9% 0.014 85)` | `#171B2C` / `#F6F1E7` |
| `--ink-muted` | `oklch(40.1% 0.029 275)` | `oklch(82.6% 0.02 279)` | `#434758` / `#C3C5D3` |
| `--ink-subtle` | `oklch(49.3% 0.031 276)` | `oklch(70.3% 0.032 277)` | `#5C6073` / `#9A9EB4` |
| `--line` | `oklch(86.2% 0.021 85)` | `oklch(36.3% 0.049 274)` | `#D8D1C3` / `#363C58` |
| `--line-strong` | `oklch(54.4% 0.027 275)` | `oklch(66% 0.03 277)` | `#6B6F80` / `#8D91A5` |
| `--accent` | `oklch(46.3% 0.083 74)` | `oklch(84.8% 0.08 85)` | `#74511C` / `#E5CA91` |
| `--on-accent` | `oklch(100% 0 none)` | `oklch(23% 0.029 86)` | `#FFFFFF` / `#231C0C` |
| `--agent` | `oklch(46.2% 0.169 272)` | `oklch(80.3% 0.1 277)` | `#3A4AB5` / `#AEB9FF` |
| `--on-agent` | `oklch(100% 0 none)` | `oklch(22.2% 0.073 273)` | `#FFFFFF` / `#11163C` |
| `--agent-container` | `oklch(91.9% 0.04 279)` | `oklch(32.5% 0.093 274)` | `#DEE2FF` / `#262E63` |
| `--caution` | `oklch(47.9% 0.112 60)` | `oklch(83.4% 0.121 65)` | `#8A4B00` / `#FFB870` |
| `--danger` | `oklch(48% 0.168 25)` | `oklch(83.8% 0.089 27)` | `#A82428` / `#FFB4AB` |
| `--success` | `oklch(47.1% 0.099 148)` | `oklch(83% 0.089 149)` | `#2E6A3A` / `#9FD8A8` |

Derived states use `color-mix(in oklch, …)`: hover `color-mix(in oklch, var(--accent), var(--ink) 12%)`,
pressed fill `color-mix(in oklch, var(--raised), var(--ink) 10%)`, tints
`color-mix(in oklch, var(--agent) 14%, transparent)`. `@media (prefers-contrast: more)` swaps in the HC
values; `@media (forced-colors: active)` keeps borders on every control. Browser floor and fallback
policy are in the component contract §6.

## 5. Typography

System fonts only: `FontFamily.Serif` (the platform-resolved serif) and `FontFamily.SansSerif`
(Roboto). Phone: `"Iowan Old Style", "Noto Serif", Georgia, serif` and `system-ui, sans-serif`.

| Token | TV size / line (sp) | Face, weight | Use | M3 slot it maps to |
| --- | --- | --- | --- | --- |
| `display` | 34 / 40 | Serif, Regular | Hero title, 1 line (fits the 96 dp hero with kicker and metadata) | `displaySmall` |
| `headline` | 28 / 34 | Serif, Regular | Page and sheet titles | `headlineMedium` |
| `title` | 22 / 28 | Sans, SemiBold | Panel, dialog, Activity task titles | `titleLarge` |
| `section` | 18 / 24 | Sans, SemiBold | Row headers, list-row titles, Now Playing title | `titleMedium` |
| `body` | 16 / 24 | Sans, Regular | All paragraphs and instructions (minimum for prose) | `bodyLarge` |
| `meta` | 14 / 20 | Sans, Regular | Card labels, metadata, timestamps, supporting lines | `bodyMedium` |
| `label` | 15 / 20 | Sans, SemiBold | Buttons, tabs, chips | `labelLarge` |
| `caption` | 12 / 16 | Sans, Medium | Badges only; never sentences | `bodySmall`, `labelSmall` |

Rules: nothing below 12 sp; prose ≥ 16 sp; timestamps and durations use tabular figures
(`fontFeatureSettings = "tnum"`). Long titles ellipsize at fixed line counts (hero 1–2, card label 2,
list title 2, dialog title 2). Layouts must tolerate Android font scale 1.3 without clipping a control
label: rows grow, they do not truncate buttons. Phone sizes are in §6 of the contract.

## 6. Space, size and shape

Spacing scale (dp): **4, 8, 12, 16, 24, 32, 48**. Nothing off-scale except component sizes below.

| Size token | Value | Notes |
| --- | --- | --- |
| Safe area | 48 horizontal, 27 vertical | §3 |
| Top bar | 52 high | Starts at the 27 dp safe top |
| Minimum focus target | 44 high | Buttons, tabs, chips, list rows (list rows 56) |
| Wide card | 224 × 126 (16:9) | `TileStyle.WIDE` |
| Poster card | 112 × 168 (2:3) | `TileStyle.POSTER` |
| App card | 160 × 90 (Android TV banner) | `TileStyle.APP` |
| Card label | 2 lines of `meta`, 12 below art | Height reserved even for 1-line titles |
| Card gap | 16 | Between cards in a row/grid |
| Row header → art | 12 | Room for focus overhang |
| Row gap | 24 | Between rows |
| Hero | 96 high (compact 52 when Now Playing bar shows) | Not scrolled |
| Now Playing bar | 64 high, 48 artwork | Docked above the bottom safe edge |
| Dialog | max 640 wide, max 460 high | Sheets: max 720 × 480 |
| Glyph | 20 (inline), 24 (list leading), 32 (status panel) | |

Vertical budget on Home (540 dp): 27 safe + 52 bar + 8 + 96 hero + 16 = 199 dp; rows get 314 dp.
A wide row needs 24 header + 12 + 126 + 12 + 40 label + 12 focus padding = 226 dp, leaving a 24 dp gap
and a 64 dp peek of the next row. A poster row needs 268 dp. With the Now Playing bar (64 + 8) the hero
compacts to 52 dp, leaving 286 dp: still one full poster row. This is why the hero is fixed and compact, unlike the
150 dp hero rejected in `ui-review.md` which sat under two header bands.

Shapes: card 12 dp; panel/dialog 20 dp; controls pill (50%); fields 10 dp; thumbnails 8 dp.
A ring or child inside a rounded parent is concentric: outer radius = inner radius + inset.
No elevation or shadows (flat tonal hierarchy; also cheaper on the Shield's GPU).

## 7. Glyphs and artwork

- Glyphs are hand-authored vector paths (24-unit viewport, 2-unit round-cap strokes), defined in code.
  Bounded set: Sail, Search, Apps, Bookmark, House, Activity, Settings, Play, Pause, Stop, Previous,
  Next, Check, Caution, Error, Mic, Ask (spark), Link, Profile, Refresh. Add a glyph only by contract
  amendment. No icon fonts, material-icons artifacts or bitmaps.
- Glyphs accompany text; they never replace a label on TV. Decorative glyphs have no content description.
- **Artwork leads.** Card art fills its frame (`Crop` for media, `Fit` with `surfaceRaised` letterbox
  for app banners). Until art succeeds, the card shows its title in `meta` on `surfaceRaised`
  (plus a glyph for action tiles); failure leaves that fallback. Art never carries text overlays from Vela
  except the progress bar and a single badge.
- Progress: 4 dp bar on the art's bottom edge, `accent` on `canvas` at 60% alpha; value clamped 0–1;
  announced as "N percent watched".
- Backdrop: only the focused content item's backdrop, behind the hero, after focus has settled for
  300 ms; crossfade 400 ms. Two scrims only: horizontal (`canvas` 100% at 0 → 92% at 0.45 → 35% at 1.0)
  and vertical (transparent at 0.55 → `canvas` at 1.0). Hidden in high contrast.
- Action tiles (pinned `AskAgent`, `HomeService`, `SearchApp`, `OpenUri` without art) use the same card
  frame with a glyph and the affordance verb as kicker; `AskAgent` tiles use `agentContainer` as their
  fallback fill. No new tile styles or schema.

## 8. Focus, D-pad and scrolling

### 8.1 Focus geometry

Focus must be visible from across a room with two independent signals (fill/size **and** a ring).

| Element | Rest | Focused |
| --- | --- | --- |
| Media/app card | art, no border (HC: 1 dp `lineStrong`) | scale 1.05, 3 dp `focusRing` ring outset 3 dp, label → `ink` |
| Secondary button / chip | `surfaceRaised` fill, `ink` label | `accent` fill, `onAccent` label, 2 dp `focusRing` ring outset 2 dp |
| Primary button | `accent` fill | `accentStrong` fill + ring |
| Agent button | `agentContainer` fill, `agent` label | `agent` fill, `onAgent` label + ring |
| Danger button | `surfaceRaised` fill, `danger` label | `danger` fill, `onDanger` label + ring |
| Quiet button / tab | no fill, `inkMuted` label | `accent` fill, `onAccent` label + ring |
| List row | transparent | `surfaceFocused` fill + 3 dp ring outset 2 dp |
| Text field | 1 dp `lineStrong` border | `surfaceFocused` fill + one outset ring; no second inner outline |

The ring is drawn outside the element's bounds (outset) so it never covers artwork. HC: ring 4 dp.
Reduced motion: no scale; ring and fill still change.

Overhang (scale growth + ring offset + ring width): wide card 5.6 + 6 = 11.6 dp horizontally, 9.2 dp
vertically; poster 8.8 / 10.2; app 10.0 / 8.3. Therefore: card gap 16 ≥ 11.6; header→art and art→label
12 ≥ 10.2; rows are **full-bleed** with `contentPadding` of 48 dp horizontal and 12 dp vertical so end
cards' overhang lands in the safe margin and is never clipped. Never use the old `offset(-12)` trick.

### 8.2 Navigation model

- **Initial focus:** the Home tab on cold start (stable and synchronous). One Down press reaches content.
  Never auto-focus a destructive or effectful control (Stop, Remove, Confirm, Play) on page entry.
  Dialogs focus their safest action (Back/Cancel) first.
- **Top bar:** Left/Right move across tabs, profile and lantern; Down enters the page. Up from the first
  focusable content returns to the top bar, restored to the **selected tab**.
- **Rows:** Left/Right within a row stop at the ends (no wrap). Up/Down move between rows; each row
  restores its last focused card (`focusRestorer`). Grids behave the same per column.
- **Back:** dismiss sheet/dialog → leave a Settings sub-page to Settings with the originating row focused →
  leave any destination to Home with the Home tab focused → on Home with focus in content, scroll to top and
  focus the Home tab → on the Home tab, do nothing (Vela is the home app).
- **Disabled controls remain focusable** (announced "unavailable", click ignored) so focus never
  jumps when an action becomes temporarily unavailable (e.g. while a title is opening).
- **Restoration:** reloading data must not insert items above focused content or empty a row that is
  being refreshed; show stale tiles with a quiet "Refreshing" status in the hero/top bar instead.
  Item keys are stable (`Tile.key`, row id). Returning from another app restores the launching card.
- **Scrolling:** default bring-into-view keeps the focused row fully visible; never animate scroll on
  each horizontal move. Vertical lists in pages use `LazyColumn` with `focusRestorer`. Long text blocks
  inside dialogs scroll with the D-pad because the scroll container is focus-traversed via its controls;
  a dialog with no focusable below its text must keep text ≤ 4 lines or contain a "More" action.

## 9. States

| State | TV treatment | Semantics |
| --- | --- | --- |
| Pressed | Fill shifts toward `accentStrong`/darker for 100 ms; cards scale to 1.02 (none in reduced motion) | — |
| Selected | Tabs: 3 dp `accent` underline + `ink` label. Chips: `accent` 2 dp border + check glyph. Persists under focus. | `selected = true` |
| Disabled | Label `inkSubtle` (opaque in high contrast), no fill; still focusable; reason shown adjacent | `disabled()` |
| Busy (control) | Caller-supplied progressive label ("Opening…"), focusable, click ignored, no spinner in reduced motion | `stateDescription = "Working"` |
| Busy (control owned elsewhere) | Inline `caution` status: "Vela is working. Stop the current task to …" | polite live region |
| Loading (page) | Status line + indeterminate linear progress (static text only in reduced motion) | polite live region |
| Loading (row) | Five static skeleton cards of the row's format in `surfaceRaised`; no shimmer | row heading + "Loading" |
| Empty | Status panel: glyph, title, one-sentence reason, at most one action | heading |
| Error (row/source) | Compact strip inside the row's height: Error glyph + message + Retry; other rows unaffected | polite |
| Error (page/action) | Status panel or `VelaDialog` naming what failed and the safe next step | heading/alert |
| Unknown effect | `caution` glyph + "The request may or may not have taken effect. Check before retrying." No blind retry | polite |
| Success | Brief `success` inline status after observed completion only | polite |
| Agent working | Lantern "Working…" in `agent`; Activity task panel with latest step | polite |
| Approval pending | Lantern "Needs your OK" in `agent` with Caution glyph; Activity shows the read-only approval notice | assertive once |

**Approval rule (guard-critical):** TV approvals are answered only by the remote through the accessibility
service's prompt-key gate, or on the paired phone with the displayed prompt ID. Vela's own screens show the
question and instructions but **never render Allow/Decline buttons** on the TV. Instruction copy:
"Press OK to allow once, or Back to decline." when TV control is enabled; otherwise "Answer on your paired phone."

## 10. Motion

| Token | Duration | Easing | Reduced motion |
| --- | --- | --- | --- |
| Focus (scale, fill, ring) | 150 ms | `FastOutSlowInEasing` | Fill/ring instant; no scale |
| Press | 100 ms | linear out | Fill only |
| Backdrop settle + crossfade | 300 ms delay, 400 ms fade | linear | Delay kept, swap instant |
| Dialog/sheet enter | platform default | — | Platform default (no custom animation) |
| Agent working indicator | indeterminate linear progress | — | Static "Working…" text and a solid dot |

No looping animation other than the working indicator. Never animate layout size of rows or the hero.
Respect `LocalReducedMotion` (Vela's setting). The phone uses `prefers-reduced-motion`.

## 11. Surface hierarchy

### 11.1 Shell

`TopBar` (52 dp): sail + "Vela" wordmark (serif 26 sp) · tabs Home, Search, Apps, My list, House,
Activity, Settings (text-only, `label`, in a horizontally scrollable lane that takes remaining width) ·
profile chip (initial disc + name ≤ 14 characters, opens Profiles) · **agent lantern**.

Lantern states: Idle "Ask Vela" (Agent variant, Sail glyph; opens Ask) → Working "Working…"
(`agent` dot; opens Activity) → Approval "Needs your OK" (Caution glyph; opens Activity). The owner
can still ask a new task from Activity ("Ask something else"), or by holding Home.

`NowPlayingBar`: docked at the bottom on every destination **only while a media session exists**;
artwork, title, app · phase, one primary transport action, "Now Playing" details. Missing notification
access or unavailable sessions are a `caution` row in Settings › Connections & permissions and inside the
Now Playing details, not a permanent banner: the pre-redesign Shield capture
(`evidence/vela-before-shield-night.png`, 1920 × 1080 px = 960 × 540 dp) shows that banner, the pairing
panel and ten pills leaving room for a single row of app cards.

### 11.2 Home

Layers, back to front: backdrop (focused item) → scrims → fixed compact hero → rows (`LazyColumn`).
Hero: kicker (`meta`, `accent`), title (`display`, 1 line), metadata (`meta`, `inkMuted`). Idle hero
(nav or app focused): "Your evening, on your terms." / "Ask for a show. Set the mood. Make yourself at
home." Pairing panel (AI not configured) is the first scrolling item, not the hero.
Rows render exactly the layout document's order, title and style. A row whose source failed shows its
error strip; an empty row is omitted (as today). The column ends with two quiet actions:
"Arrange home" (D-pad reorder/remove using the coordinator-approved `owner-layout` controller) and
"Ask Vela to change home" (Agent variant; opens Ask, never auto-sends).

### 11.3 Content destinations

Search: one field + Search button, then full-bleed result rows (Apps, Library), then
"Ask Vela to find “…”" (Agent). Apps and My list: adaptive grids of the row format, same focus geometry.
Title sheet: header row (Back, Save/Remove) at a stable position; title (`headline`), subtitle, 4-line
overview, then the single primary action (Play/Resume, accent) or episode list with season chips.

### 11.4 Agent surfaces

Activity: `headline` "Working for you". If running: **task panel** (`agentContainer`, 20 dp radius):
"Now" kicker, task text (`title`), latest step (`body`, live), working indicator, approval notice when
pending, actions Stop task (Danger) and Ask something else (Agent). History: list rows with state glyph
and word (Running = agent, Complete = success Check, Failed = danger Error, Canceled = muted Stop),
time (tabular), detail, "Ask again" (Agent). Empty: status panel.

Overlay (over other apps): Night palette, `surface` at 95% opacity, 20 dp radius, top-end inside the
safe area, ≤ 520 dp wide, last three steps in `meta`/`inkMuted`, never focusable or touchable.
Approval: 2 dp `agent` border, "Vela needs your OK" in `section`, question in 20 sp `ink`, and the
existing hint text in `accent`. Background or step text alone never signals approval.

House: list rows (domain glyph, name, `domain · state`), trailing Toggle (Secondary) or Ask Vela (Agent).
Unavailable entities show `inkSubtle` state and Ask Vela only.

### 11.5 Settings and management

Settings is a hub of grouped list rows, not a button wall: **Phone** (pairing panel), **Household**
(Profiles & memory, Routines), **Agent control** (External agent control, Now Playing), **Connections &
permissions** (status rows with Check/Caution glyphs and an action), **Appearance & accessibility**
(choice chips: Theme Night/Day, Motion Standard/Reduced, Contrast Standard/High, Home button
Vela/System, plus a live non-interactive preview of the three card formats at rest and focused),
**Home screen** (Arrange home), **This device** (model, API, measured surface).
Sub-pages show the breadcrumb "Settings" above the `headline` title. Management lists use list rows
and field shells; long editors stay full pages with Save/Back at their end; confirmations use
`ConfirmDialog` with Cancel focused.

## 12. Phone companion

Same roles, voice, glyphs, state vocabulary and approval treatment; native HTML controls.

- Layout: single column by default; at container width > 60 rem a two-column grid with a primary column
  (Ask conversation, approval, remote) and a management column. No auto-fit grid of bordered cards.
- Header: sail mark + "Vela", connection status pill; section links in a horizontally scrolling lane.
- Sections are flat: serif `h2`, generous block spacing, a `--line` divider. Only the conversation log,
  the approval panel, the remote and editors use filled surfaces.
- Approval panel: 2 px `--agent` border on `--agent-container` tint, "Vela needs your OK", question,
  **Allow once** (primary, accent) and **Decline** (secondary), same prompt-ID behaviour as today.
- Conversation: owner messages right-aligned on `--raised`; agent replies with a 3 px `--agent`
  inline-start rule; steps in `--ink-muted` small; errors in `--danger` with a word.
- Remote: circular D-pad keys (56 px, OK 64 px with accent ring), Back/Home as pills; disabled while
  the agent works, with the existing explanation.
- Destructive or confirm-first buttons (labels ending "…") use the Danger style.
- Focus: `:focus-visible` 3 px `--accent` outline, 3 px offset; every pressable element has `:active`;
  hover only inside `(hover: hover) and (pointer: fine)`; transform transitions only inside
  `prefers-reduced-motion: no-preference`.

## Coordinator implementation clarifications

The owner has authorized this design increment. `owner-layout` is an internal controller label and needs no separate approval; saves use `Runner.withController` for atomic reservation. Nominal hero/card/chrome heights describe the 1× baseline, not clipping limits: larger text and short windows must grow or scroll accessibly. A paused valid media session keeps Now Playing visible. The pairing square in the before image is a deliberate credential redaction, not a QR rendering failure. The coordinator now has a working physical-Shield device route and full Gradle builds. Workers keep their file ownership, do not run shared Gradle builds or mutate Git; the coordinator owns those combined checks and visual QA.
