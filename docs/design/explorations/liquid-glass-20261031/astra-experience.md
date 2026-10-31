# Experience review: glass that works from the sofa

Independent adversarial design review · 2026-10-31 · Astra experience assignment

**Recommendation:** make liquid glass the material of navigation, temporary interaction and reassurance. Keep entertainment artwork crisp, give the focused object an unmistakable perimeter, and let the room's content remain perceptible behind a small number of substantial glass surfaces. The opportunity is a more inviting, spatially coherent TV experience—not simply translucent versions of the existing oversized controls.

The strongest changes to explore are a less vertically expensive Home header, an Ask surface that preserves browsing context, a clearly grouped Undo notice, and management screens organized around completing a task. Retain the existing strong media/app focus cue. Serif headings, brass and ubiquitous pills are optional for this exploration; safe action semantics are not.

## Evidence and limits

Read [the brief](brief.md) first, then both capture READMEs/manifests and relevant portions of the design language/component contract. Inspected these **17 actual PNGs using image tools**, not their HTML galleries or inferred renders:

| Evidence group | Images inspected | What it establishes |
| --- | --- | --- |
| [Current Home evidence](../../evidence/home-entertainment-20261031/README.md) | `home-media-night`, `home-media-day`, `home-media-focus-night`, `home-app-focus-night`, `home-apps-only-night`, `home-apps-only-day`, `home-updated-notice`, `restore-default-confirmation` | Visible Home composition, two strong focused-card examples, apps-only state, layout notice and ordinary confirmation |
| [Supporting-surface evidence](../../evidence/visual-corrections-20261031/README.md) | `ask-night`, `ask-day`, `settings-night`, `settings-day`, `profiles-night`, `profiles-day`, `profiles-feedback-night`, `profile-editor-night`, `profile-editor-day` | Composer, scrolled Settings/appearance state, profile list, refresh feedback, unsaved editor |

All filenames above have a `.png` extension. Both sets are documented as unedited 1920 × 1080 Android 14 Google TV emulator captures. The supporting set predates the Home revision; its older Home images were deliberately excluded. The brief identifies current source as `4006f179be9d9c068bf05cb0a943c2e0e5e7a3cd`; capture build ancestry is recorded separately in the manifests. These are related evidence, not a claim that every capture came from that exact commit.

The colored test patterns and fixture titles are synthetic library data. Their repetition, hue choices and artificial names are **not product art-direction defects**. They do expose layout, focus and layering. They cannot establish performance or legibility over a realistic range of artwork. The emulator's installed app inventory is also not an intended household lineup.

No details/resume, successful app launch, listening/result, pending approval, high-contrast, enlarged-text or real playback sequence is visually established by these images. Source tracing below distinguishes intended behavior from visible appearance; it does not prove D-pad timing or focus restoration. The README's test results are not design acceptance. No builds, GUI/device operations, servers or physical Shield contact were performed. No other review report was read. Only this report was written.

## Ranked findings

Ranks are design priorities for the mockup stage, not software defect severities.

### 1. Home's fixed context competes with the next entertainment choice

**Observed.** In [media focus at Night](../../evidence/home-entertainment-20261031/home-media-focus-night.png), the top navigation, large title block, repeated “Continue watching” label and source line leave one complete media row and only the upper edge of app banners. In [app focus](../../evidence/home-entertainment-20261031/home-app-focus-night.png), the previous media title still occupies the large header; cropped fragments of the previous row's labels appear immediately beneath it. The focused app itself is fully visible and strongly identified. This is a composition issue, not evidence that the app is unreachable or focus is broken.

**Consequence.** The fixed header gives context, but too much of the screen describes the previous selection while the user is choosing an app. Hard-cut text fragments make the scroll boundary feel accidental. Applying a taller glass navigation dock or an extra glass hero panel would worsen this.

**Mock instruction.** Reclaim vertical space through hierarchy and spacing before shrinking readable text. Consolidate the duplicate context labels. Show one complete focused content row plus a recognizable next-row preview, not merely colored corners. When browsing lower rows, explore a compact retained media context with a clean scroll boundary. Preserve the selected media identity through app focus; do not turn the hero into an oversized “Settings” app advertisement. A glass navigation surface and a gently occluded scroll edge can help distinguish stationary controls from moving content. Do not blur the focused artwork or its label.

**Unverified hypothesis.** A compact retained header will reduce the sense of separation between entertainment rows. Its expansion/collapse must not make a row move during horizontal browsing; the storyboard needs both positions and the same focused item.

### 2. Invoking Ask currently removes the entertainment context

**Observed.** [Ask Night](../../evidence/visual-corrections-20261031/ask-night.png) and [Ask Day](../../evidence/visual-corrections-20261031/ask-day.png) present a large centered panel on an otherwise empty full-screen canvas. The focused request field is clear. Most of the surface is generic heading, invitation and instruction rather than current context. The solid Back pill is visually stronger than the adjacent Speak action at rest.

**Source distinction.** This is a separate `AskActivity`. Speech fills a reviewable draft; the user then submits with Ask. Submission calls the runner and closes the activity. Explicit Back closes it, and leaving stops listening. This is not a continuously listening assistant, and the visible example is placeholder text, not a pending request. See [AskActivity.kt](../../../../android/app/src/main/java/app/vela/tv/launcher/AskActivity.kt), lines 29–85 and 98–129.

**Mock instruction.** Explore one substantial frosted composer over a still-recognizable Home, leaving the selected title spatially anchored behind it. Keep the panel compact enough to feel invoked, with a high-opacity text-entry area and direct Type/Speak/Back affordances. Show empty, listening, reviewed draft and unavailable-speech variants. Do not auto-send recognized speech, auto-start the microphone, or invent guaranteed knowledge of “the next episode.” If a title context is displayed, make its identity explicit and do not imply an existing context attachment API.

**Remote requirement.** Draw the initial safe focus and the route to Speak. Dismissing the composer should return to its invoking control and preserve the underlying row position; that is a proposed acceptance target, not a claim proved by these captures. Dismissing UI after submission must not mean canceling the task. Stop task remains an explicit separate action.

### 3. The reversible layout change needs a stronger visual receipt

**Observed.** In [Home updated](../../evidence/home-entertainment-20261031/home-updated-notice.png), “Home updated,” “Undo” and “Keep layout” are a loose row of similarly weighted text on the canvas. It is readable, but its grouping and significance are much weaker than the persistent profile/Ask pills. The [apps-only capture](../../evidence/home-entertainment-20261031/home-apps-only-night.png) shows the later “Undo last home change” action beside Arrange home.

**Source/provenance distinction.** Keep layout acknowledges an already-saved change and hides the notice; it does not perform the save. Undo remains available afterward. History is one step and session-only, with checked controller admission; it is not an unlimited history or a durable rollback promise. The evidence README records actual Undo round trips. [HomeActivity.kt](../../../../android/app/src/main/java/app/vela/tv/launcher/HomeActivity.kt), lines 60–105 and 124–134, shows the presentation and busy gating; [ArrangeHome.kt](../../../../android/app/src/main/java/app/vela/tv/launcher/ArrangeHome.kt), lines 18–30, shows the guarded write/Undo path.

**Mock instruction.** Use one compact glass receipt with a readable solid-enough backing: “Home updated” plus Undo and an acknowledgement. If changing “Keep layout” to “Done,” annotate that it only dismisses this receipt. A specific summary such as “Apps moved above Continue watching” is preferable only when derived from the actual change; retain a generic fallback. Give Undo a clear control boundary and focus state, without automatically moving focus to it. Keep the notice until acknowledged; do not invent a short expiry countdown. Show busy/unavailable Undo with its reason and a retained recovery action after acknowledgement.

The ordinary [restore confirmation](../../evidence/home-entertainment-20261031/restore-default-confirmation.png) has clear wording and readable content. Its mock must explicitly show Cancel focused rather than rely on the filled Restore defaults button to communicate focus. The still alone does not establish which action held remote focus; `ConfirmDialog` requests Cancel in source.

### 4. Management reads as a long administrative form rather than a finishable TV task

**Observed.** In [the Day editor](../../evidence/visual-corrections-20261031/profile-editor-day.png), navigation, breadcrumb, large heading and a separate Back row consume almost half the screen before useful form content. Display name is very wide; audio entry asks for “comma-separated tags, e.g. en, es”; the subtitle field is partially below the visible region. Save is not visible in this view. Both themes share this structure. This is not evidence that Save is missing or unreachable: [ProfileManagement.kt](../../../../android/app/src/main/java/app/vela/tv/launcher/ProfileManagement.kt), lines 88–110, places it after the remaining preferences.

The [profile list](../../evidence/visual-corrections-20261031/profiles-night.png) gives Refresh and Recover household storage prominent placement before the actual profile. In [refresh feedback](../../evidence/visual-corrections-20261031/profiles-feedback-night.png), inserting the completion message shifts Preferences/Explicit memory partially below the viewport. The message is truthful for the documented refresh, but “Request completed” is generic.

**Mock instruction.** Put the current person and the task first. Use a calm frosted grouping surface with a compact title, an appropriately bounded form column, and a stable, reachable Back/Save action region. Keep field interiors opaque and labels persistent. Explore human-readable language selection with a custom-entry route, preserving supported values and validation; that widget is a proposal, not an already implemented capability. Give refresh feedback local wording and placement that does not displace the active task. Keep recovery available in a secondary maintenance location with its existing confirmation; recovery is not deletion and should not be mislabeled as such.

The [Settings appearance captures](../../evidence/visual-corrections-20261031/settings-night.png) already demonstrate strong row focus and checked choices. Retain that clarity while reducing empty space and giving related settings a shared surface. These are scrolled captures, so the absence of an initial page heading is not a finding.

### 5. Persistent navigation is legible but visually expensive and weakly prioritized

**Observed.** The Home bar exposes seven text destinations plus profile and Ask. The two large right-hand pills occupy roughly the last quarter of the screen width. Entertainment destinations and House/Activity/Settings have very similar typographic emphasis. Labels fit at the captured baseline; this is not a report of clipped navigation.

**Consequence/hypothesis.** The linear arrangement asks the remote user to traverse many peers to reach the visible agent entry. The exact real key count is untested. A glass redesign could make this bar more attractive while retaining the same hierarchy problem—or create a worse one by rendering every tab as a reflective pill.

**Mock instruction.** Use a restrained shared glass navigation plane, distinguish entertainment from utility, and reduce redundant container bulk. Retain visible labels and a compact labeled agent presence; do not solve width with unexplained icons or an invisible shortcut. Keep all destinations available. If a mock relocates utilities, explicitly annotate the new D-pad route and treat that information-architecture change as a proposal. Show long profile names and “Needs your OK,” not just the shortest idle state. Status changes must not move neighboring targets under the user's focus.

### 6. “What OK does” needs to survive the beautiful material treatment

**Observed.** The media/app focus captures identify the selected object well, but do not visibly explain the difference between selecting media and selecting an app. Both use the same attractive card/focus structure.

**Source-derived behavior, not visual defect proof.** `TvShell.select` opens title details for Jellyfin media and OpenUri tiles, while a LaunchApp tile proceeds directly through the guarded action path. The title sheet starts with Back focused, has Save/Remove in the header, and renders Play/Resume in its body after title information. For series, individual episode actions include their identity and availability. See [TvShell.kt](../../../../android/app/src/main/java/app/vela/tv/launcher/TvShell.kt), lines 65–77 and 136, and [TitleDialog.kt](../../../../android/app/src/main/java/app/vela/tv/launcher/TitleDialog.kt), lines 92–135. No details screenshot was supplied, so this review does not call that screen visually broken.

**Mock instruction.** On focused media, make “Details” discoverable without covering every poster in buttons; on an app, communicate “Open.” In the detail mock, keep title/episode identity, source, available resume information and the primary action within one readable viewport. Back still receives safe initial focus; the obvious Resume action should be a short, explicitly drawn D-pad route away. Do not turn a source that only opens/searches an external app into a “Play” promise. Separate Opening from observed Playing, and show unavailable/unknown-effect states without a success flourish or blind retry.

## What already works, and what glass must preserve

- **Focus is an asset.** The media and app captures show an unbroken bright outer ring with a dark separation from artwork, modest enlargement and a brighter label. That survives both saturated media art and the gray Settings app banner. It is much stronger than a glow-only focus treatment. Keep a comparable perimeter and independent fill/size signal in the new language; no need to preserve the exact brass hue.
- **Selection differs from focus.** Home has a selected underline; Settings choices have checkmarks. A glass specular edge must not become a second, competing focus ring. One target has the dominant focus perimeter; persistent selection uses a check/marker, not the same bright outline.
- **Apps-only Home gets to the apps.** It does not fill the vacant library space with an assistant promotion. Preserve that directness. Add material warmth through the shared shell and intentional spacing, not fabricated media or a mandatory onboarding billboard.
- **Safe action hierarchy exists.** Initial Home focus, safe Back/Cancel focus, named actions and disabled/busy handling are useful constraints, not remnants of the flat style to discard. Do not auto-focus Play, Restore, Delete or Stop to make a mock look decisive.

## Joyful interaction principles

1. **The object under the remote feels held.** Respond immediately with a crisp focus boundary; any small lift or light response follows that signal. Depth never substitutes for knowing where OK will act.
2. **Vela joins the evening without taking it over.** Idle agent presence is compact. Invoking it reveals one contextual surface; finishing or dismissing returns the browsing context. Working, approval and completion are different, plainly named states.
3. **Changes leave a reassuring trace.** A moved row stays identifiable; focus remains with its move control. A saved layout gets a clear receipt and real Undo. No confetti, forced detours or disappearing recovery opportunity.
4. **Glass reveals place; it does not obscure decisions.** Artwork may color the shell and sheet edges. Text, inputs, focus and consequential action wording get a stable backing. The same screen should still feel considered with transparency removed.
5. **Delight rewards intention.** Use a restrained settle on a chosen tile or a subtle acknowledgement of a completed change. Avoid perpetual shimmering navigation, moving light beneath reading text, focus trails, background parallax and repeated listening theatrics.

## Material and resilience constraints

| Layer | Useful glass treatment | Legibility and fallback requirement |
| --- | --- | --- |
| Global navigation | One shallow frosted surface connecting stable destinations; restrained edge light | Labels remain stable over the brightest/darkest art. Opaque Night/Day equivalents retain geometry and selected/focused distinction. No glass pill inside every glass tab. |
| Focused content | Crisp artwork; controlled lift and a restrained edge response outside the focus separator | No blur, distortion or tint over identifying artwork. Strong perimeter stays visible over white app banners and bright poster edges. Reduced motion removes scale, not focus. |
| Media context | Art-derived atmosphere with a reliable text backing; cleaner boundary above scrolling rows | Do not depend on blur to rescue contrast. Keep title/metadata readable when images fail and when scrolling content passes underneath. |
| Ask/details/editor | A substantial frosted sheet can explain that the current interaction sits above Home | High-opacity or opaque reading/field regions; no nested translucent field on translucent panel on moving art. One active focus scope. Opaque fallback preserves the same control map. |
| Layout receipt | Small, grouped, frosted surface separating feedback from browsing | Stable, readable Undo and acknowledgement controls; never cover the focused tile or depend on animation to be noticed. |
| Approval notice/overlay | At most a quiet material edge; prioritize a solid reading surface | Read-only TV notice, unambiguous question/target and instruction. No apparent tappable Allow/Decline controls, focus ring or pointer affordance on the overlay. |

**Contrast:** carry the repository's text/essential-boundary/high-contrast targets into the composited result, rather than claiming the old opaque palette's ratios still apply. Test bright artwork, dark artwork, busy high-frequency artwork, image failure and Day/Night separately. No new contrast measurement or ten-foot legibility test was performed here. Keep essential action labels at the established readable control/body scale; solve fitting by layout rather than tiny typography.

**Reduced motion:** make focus position, selection, busy state and completion understandable in still frames. Remove scale, parallax, refraction animation and moving highlights. Keep text state changes. Do not make a fade or a morph the sole cue that a dialog or a new approval appeared.

**Reduced transparency and high contrast:** existing Settings expose reduced motion and high contrast; a separate reduced-transparency preference is a proposed design option, not an established product setting. Supply opaque variants even if the first implementation maps them to existing high contrast or capability fallback. High contrast removes decorative backdrops/translucency, retains identifying card artwork and uses strong essential borders. It must receive equal typographic and spacing care.

**Hardware feasibility:** the Android 14 emulator evidence says nothing conclusive about the paused Shield Tube's current Android/API level, supported blur path, frame pacing, memory or thermal behavior. None was verified here; no benchmark claim is made. Specify the visual goal independently of a blur implementation. A simpler rendering tier can use a stable art-derived/static backing, tint and restrained edge highlights; a fully opaque tier retains the interaction hierarchy. Live backdrop blur/refraction is an implementation choice to validate later against the actual supported platform, not a prerequisite for the mockup or a reason to reject glass. Do not add a new device exercise to this paused review.

**Approval and ownership boundaries:** [Agent.kt](../../../../android/app/src/main/java/app/vela/tv/design/Agent.kt), lines 24–34, renders question/instructions only. [Overlay.kt](../../../../android/app/src/main/java/app/vela/tv/control/Overlay.kt), lines 18–20 and 65–88, retains `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`. Preserve those semantics. TV approval comes through the existing remote prompt-key gate or the paired phone with the displayed prompt identity; without TV control, say “Answer on your paired phone.” Ordinary interactive Restore/Delete confirmation dialogs are a different surface. Never depict a normal D-pad focus path into overlay buttons. Profile switching, deletion, controller ownership, exact-target actions and existing confirmation rules are unchanged.

## State-by-state mockup checklist

These are design states and annotated remote transitions, not instructions to operate a device. The designer can group them into finished screens plus small state strips; it is unnecessary to render every combination as a separate full board. Put mockup labels and flow annotations outside product UI.

| State / daily flow | Show in the mock | Remote/focus specification | Opaque/high-contrast counterpart |
| --- | --- | --- | --- |
| 1. Home entry, populated | Credible artwork; Continue watching, Apps, Recently added, My list; recognizable next-row preview | Home tab initially focused; Down enters content; selected tab marker remains distinct | Same layout, opaque shell, no decorative backdrop, readable labels and visible focus |
| 2. Browsing a media row | Focused title, progress where available, concise metadata and details affordance | Left/Right changes card; row ends do not wrap; no navigation while merely animating | Perimeter uses a separator against artwork, not glow; reduced-motion still has immediate focus |
| 3. Browsing Apps beneath media | Entire focused app and label; clean upper scroll edge; compact retained media context | Down reaches Apps; Up restores the row's prior item; app focus is visually distinct from selected Home tab | White and dark app banners both retain visible focus without translucent washes |
| 4. Details → Resume | Exact movie/episode/source; available resume information; Back, save action and Resume | Back initially focused; annotate the short path to Resume; Back dismisses to originating tile | Opaque reading/action surface; long title and unavailable action remain readable |
| 5. Opening → result | Opening state; separately, observed playback or a clear failure/unknown outcome | Repeated OK cannot imply duplicate execution; unavailable control retains a discernible position | Text/glyph states remain distinct without animation/color alone; no blind-retry mock for unknown effect |
| 6. App launch and return | Focused app with “Open” semantics; return frame on that app | Activation opens the app directly through existing guarded behavior; return focus/scroll restoration is an acceptance target, not verified here | Same launching-card identity and label; no invented playback badge |
| 7. Apps-only Home | Real app tiles, Arrange home and library settings; no phantom empty media rows | Useful first content target; reachable utility actions | Intentional opaque canvas and spacing; no need for a media backdrop to look finished |
| 8. Invoke/dismiss Ask | One contextual composer; visible origin behind it; empty field, Speak and Back | Safe initial field/entry focus; annotate Speak route and Back return. No automatic microphone start | Solid field/reading zone; reduced motion can open instantly without losing context |
| 9. Listening → draft → submitted | Listening and Stop listening; then reviewed text and Ask; then compact Working presence | Recognition does not submit. Explicit Ask submits; closing presentation is not Stop task | State words plus glyphs; no animation required to distinguish listening from working |
| 10. Agent unavailable/working | Unavailable speech/AI message with truthful alternatives; working label that does not resize navigation | Typing/Back remain where appropriate; working indicator opens Activity; Stop is never initial focus | Stable labeled controls; no decorative pulse needed |
| 11. Approval pending | Exact question/target and plain remote or phone instructions; separate third-party-overlay example | Read-only TV notice. OK/Back uses existing prompt gate; overlay takes neither focus nor touch. No Allow/Decline button mock | Opaque prompt body with strong text hierarchy; bounded footprint over arbitrary video |
| 12. Arrange custom Home | Named custom row, immediate-save explanation, move/remove actions and current order | Move keeps focus on the same row/action; boundaries disable impossible moves; removal/restoration retains confirmation | Stable row grouping; text order change alone communicates the result |
| 13. Changed layout / Undo | Grouped receipt; Undo focused variant; acknowledgement; later retained Undo action | No focus theft on receipt arrival. Show unavailable/busy and completed Undo separately; one step, session-only | Opaque receipt, same placement/actions; no timed or animation-only recovery cue |
| 14. Restore defaults | Exact replacement row names and distinct Cancel/Restore actions | Cancel initially focused; Back cancels; confirmation does not bypass controller ownership | Fully readable modal; focus unmistakable even when Restore is a filled primary button |
| 15. Settings / profile list | Clear current person, preferences, maintenance hierarchy; local refresh feedback | Settings child returns to its originating Settings row; focus survives feedback; switching/deletion keep confirmations | Checked choices differ from focus; strong row boundary; no glass shine simulating selection |
| 16. Profile editor / save | Bounded readable fields, human-readable language proposal, stable Back/Save region; unsaved, saving, error and saved variants | Back remains safe initial focus; explicit Save uses existing validation/admission. Do not depict auto-save or silent profile switching | Opaque fields, readable persistent labels/errors; large text/IME can scroll without hiding the sole exit/action |

## Concrete handoff to the mock designer

Recommend one coherent direction: **artwork-led Home with a quiet frosted navigation plane, a crisp luminous focus perimeter, and substantial translucent sheets whose text regions remain stable**. Make its beauty visible in the normal Night and Day screens; do not confine glass to a decorative accent nobody interacts with. Keep the compact agent presence and direct entertainment hierarchy.

Deliver full compositions for populated Home in both themes, app focus lower in Home, contextual Ask, the layout receipt, and a supporting Settings/profile-editing surface. Include a detail/resume composition because the supplied evidence does not cover it. Use small companion strips for focus/selection, listening/draft/working, read-only approval, opening/unknown outcome, busy Undo and opaque/high-contrast variants. A paired-phone supporting surface may share these material/field rules, but must not imply a new live draft-transfer or permission capability.

For every full TV composition, identify the focused target and what OK, Back, Up and Down do. Show at least one Day focus example against bright artwork and one Night example against dark artwork. Check long content titles, long profile/agent status labels and enlarged text as layout variants. Preserve enough content to understand the destination behind a sheet, without allowing its contrast or movement to compete with the current decision.

The unresolved questions for a future implementation pass are actual return-focus/IME behavior, real-content composite contrast, row-scroll transitions and hardware rendering capability. They do not block this mockup stage and do not authorize contacting the paused Shield. The next stage should resolve the visual hierarchy and interaction states first, with all behavioral proposals visibly distinguished from already-supported behavior.
