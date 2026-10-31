# WP6 — focus and action audit

2026-10-31. Read-only review against `vela-design-language.md` and
`vela-component-contract.md`, including coordinator clarifications. Assignment baseline:
`0809edfb31d2fac467dc55e2b67fd93d9cf777df`. Writers were active; line references below
identify the source observed during this audit, not a final integrated revision.

Only this note was added. No production/test edits, Git mutations, Gradle builds,
tests, device/browser operations, screenshots, firmware or infrastructure work ran.
Installed Compose Foundation/UI 1.12.1 was inspected through cached AAR bytecode
with `javap`, with extraction/output confined to a private temporary directory.
The catalog specifies Compose BOM 2026.09.00 and Media3 1.11.1; no upgrade is proposed.

## Prioritized finding

**P1 — reject an entire press that begins unavailable or crosses an unavailable state.**
`android/app/src/main/java/app/vela/tv/design/Focus.kt:39,59–62`, called by
`design/Buttons.kt:32–34`.

The shared surface permanently sets Foundation `clickable(enabled = true)` and
checks the Vela `enabled` value only when `onClick` runs. This correctly ignores a
click while still disabled, but retains a press begun while disabled/busy. If the
control becomes available before release, the updated callback invokes the action.
An enabled → disabled → enabled transition within one held press has the same issue.
This can retrigger an owner action when the previous operation finishes under a
held remote key. It is an unintended activation, not a demonstrated Runner bypass.

Evidence beyond inference: cached Foundation 1.12.1
`AbstractClickableNode.onKeyEvent` records a `PressInteraction` on activation-key
down, retains it through repeats, then invokes `ClickableNode.onClickKeyUpEvent`
on release; that calls `performClick`. `updateCommon` disposes interactions when
*Foundation's* enabled property changes to false. Here it never does. Pointer down/up
also remains enabled. Masking `rawPressed` changes the visual state, not this lifecycle.

Bounded correction: retain one focus node and the accessibility callback guard,
but latch eligibility for the whole physical gesture. Reject down while unavailable,
invalidate an armed gesture if availability is lost, and consume its repeats/release
without activating even if availability returns. Cover D-pad center, Enter/numpad
Enter and supported gamepad activation keys, plus pointer gestures. A new fresh
press after availability returns must work. Verify this transition specifically;
ordinary `enabled=false` click checks do not cover it.

## Source evidence and limits

Coordinator correction (2026-10-31): `ActivationGate` now binds eligibility to the
entire key/pointer press. Availability loss invalidates held presses; key repeats
cannot re-arm them. Foundation still completes its own input lifecycle and owns
the sole focus target. Accessibility activation has a separate current-availability
guard. Six regressions cover unavailable-to-ready, availability loss/recovery,
repeats, cancellation/replay, focus loss and release-to-dispatch changes. All 20
pure design tests and isolated compilation of the design/overlay sources pass.
Actual input timing and accessibility delivery remain unverified on a device.

- **One focus node and requester order:** `Focus.kt:52–62` applies the caller's
  modifier before the clickable target and adds no second focusable. `Cards.kt:63–71`
  and `Fields.kt:27–38` place caller requesters on an ancestor of their single
  interactive child. No requester-order defect was found. Requesting focus in a
  callback/effect follows [Android's FocusRequester guidance](https://developer.android.com/develop/ui/compose/touch-input/focus/request-focus).
- **Safe confirmation focus and text overflow:** the later observed
  `Dialogs.kt:25–45,53–71,98–104` explicitly focuses Cancel after a frame and adds
  `ScrollableDetails`, a focusable D-pad scroll target only when overflowing.
  Short-window handling also exists at `:58–64,79–86`. An earlier version lacked
  the text-scroll route; that concern is superseded, not an outstanding defect.
  Actual focus traversal into/out of this new container still needs Shield QA.
- **Ring clearance:** `Rows.kt:34–37,70–73` gives full-width rows/grids 48 dp side
  padding and 12 dp vertical padding. `Focus.kt:52–53,67–85` draws outside the target
  without clipping its graphics layer; artwork clips internally. At scale 1.05,
  the HC poster's vertical overhang is approximately 11.55 dp, within the 12 dp
  padding. This supports the component geometry; it does not prove clipping or
  bring-into-view behavior in a completed shell. The nav lane was still pending.
- **Semantics:** cards expose title/subtitle, action label and clamped watched
  percentage (`Cards.kt:64–69`); duplicate fallback/label text is cleared.
  Tabs/radio chips/groups and switches have roles/selection state
  (`Buttons.kt:97–115`, `Lists.kt:39–48`). Disabled/busy metadata is present
  (`Focus.kt:55–57`, `Buttons.kt:32–33`). TalkBack announcements and the native
  text field's disabled/read-only behavior require observation.
- **Effects remain behind callbacks in inspected sources:** baseline
  `HomeActivity.kt:94,147–152` uses focus only to update the hero; activation calls
  `TvShell.select` (`TvShell.kt:65–81`) → `HomeData.perform` (`HomeData.kt:145–147`)
  → `Runner.performTile` (`agent/Runner.kt:93–100`). Non-agent tiles reach guarded
  `executeTool`; AskAgent checks running/controller ownership. Now Playing
  rechecks live policy/target/availability before `Runner.executeTool`
  (`NowPlayingSurface.kt:84–102`). New design effects inspected request focus or
  settle artwork, and import no Runner/owner store. These traces establish the
  baseline and component separation, not the unpublished adapter implementation.
- **Approval identity is preserved in inspected sources:** `Agent.kt:24–35`
  renders read-only text; `Overlay.kt:52–54,70–74` retains the exact hint and both
  non-interactive window flags. `HandsService.kt:108–121` consumes approval keys
  and passes the current prompt ID; `guard/PromptKeyGate.kt:7–13` requires a fresh
  press after grace on that same ID. Baseline `assets/phone.html:221–229` captures
  `displayed = pendingId` before posting and conditionally clears only that ID.

## Pending integration coverage — four areas

At the one launcher/player publication checkpoint, launcher files and phone assets
were still the baseline; `ShellChrome.kt`, `SettingsHub.kt`, `ArrangeHome.kt` and
`player/PlayerChrome.kt` were not published. Their absence is **not a defect**.
Root should verify the finished changes in these four areas:

1. Shell/management adoption: selected-tab Up routing, Settings origin restoration,
   Home scroll-to-top, safe dialog focus, Activity entry avoiding Stop, stable stale
   rows and keys across refresh/resume. Baseline `TvShell.kt:83–85` always returns
   Home, so it cannot serve as proof of the new subpage contract.
2. Arranger: callbacks derive from current layout; validated saves enter
   `Runner.withController("owner-layout")`; moved-row/button focus survives each
   save; boundary/remove-last controls reject activation; cancellation has no write.
3. Phone: inspect the final script delta for unchanged IDs, prompt-ID capture,
   connection gating and locks; this audit did not review or execute the new phone UI.
4. Native player adapter, if published: preserve Media3's controller/key and track
   behavior; check Back-first error/completion dialogs and return focus. No audit
   conclusion about an absent `PlayerChrome.kt` is implied.

## Root's Shield acceptance matrix

**Paused by the owner:** the Shield is in normal TV use, and app launches steal
the active input. Root closed its Device panel. This matrix is a future checklist;
do not connect to or operate the physical Shield until the owner explicitly resumes
testing. Source/build/fixture/browser work can continue independently.

Use T3 `device_open` with host `local`, device `10.0.0.209:5555`, then the returned
device-bound `agent-device` command for launches, input, settings and captures;
use its `help` for supported operations and `device_screenshot` to inspect results.
Do not substitute raw simctl/adb/SSH input. If held-key timing or accessibility
actions cannot be generated through this route, explicitly mark those cases
unverified rather than treating repeated taps as a hold test.

The known baseline is **1920×1080 / density 320**, approximately 960×540 dp, not a
4K proof. Record actual app constraints. Use app-window captures, redact pairing
credentials, preserve display size/density, and restore any changed accessibility
preference after QA. The before-image QR was intentionally redacted.

| Priority | Exercise | Acceptance evidence |
| --- | --- | --- |
| P1 | Focus a disabled/busy control; center/Enter taps and holds; begin hold while busy, let it finish, release; also lose and regain availability mid-hold | Focus stays; **zero callbacks/effects** from the unavailable gesture; a fresh press works once. Verify behavior/log or state delta, not only button appearance. Include pointer/accessibility click if supported. |
| P1 | D-pad across content, tabs, settings choices and arranger controls without activating; force ordinary recomposition/refresh | Hero/art may update; no owner write, playback command, agent request or confirmation starts merely from focus. |
| P1 | Open destructive/owner confirmations, title sheet and action-error dialog; cancel with Back/Cancel | First focused action is Back/Cancel, never Confirm/Play/Remove. Cancel produces no mutation. No same-key release activates the newly opened surface. |
| P1 | Controlled pending approval: hold OK before it appears; complete a fresh OK/Back gesture after grace; exercise prompt replacement if available | Held pre-prompt input does not answer; fresh gesture answers only that prompt once; key does not also activate underlying UI. TV has no approval buttons. Do not use an unintended destructive action as the fixture. |
| P2 | Cold Home → Down; each top-level tab → content → Up; Settings → Profiles/Routines/External/Arrange → Back; nested editor/dialog Back | Home initially focused; Up restores selected tab; subpages restore Settings and originating row; successive Back dismisses locally, then destination, then Home. Home-content Back scrolls to top; Home-tab Back stays home. |
| P2 | First/last card of every format, vertical grid edges, scrollable nav ends; leave/re-enter rows; refresh and return from an app | No wrap; no clipped outset ring or scale; selected card/row identity and scroll position restore; stale data remains during refresh. Capture Night, Day and HC extremes. |
| P2 | Arranger move up/down, cancel Remove/Restore, boundary and last-row controls; guarded busy/error case | Exactly one valid save per deliberate action; same moved row/action retains focus; disabled boundaries do nothing; failure leaves truthful state and usable Back. |
| P2 | Search, Ask and management single/multiline fields; IME submit and Back; font scale 1.3 | Draft/selection retained; IME action obeys the same busy/validity guard as the button; Back first exits IME appropriately; no trapped focus or clipped action labels. At 1.3 also inspect wrapped top-bar/lantern labels and full dialogs. |
| P2 | Long confirmation/error text, title overview/episode list and read-only details, at 1× and 1.3× | Every required sentence/action is reachable by D-pad; new scroll target enters/exits at boundaries; Cancel remains reachable; no confirmation is needed merely to read details. |
| P2 | Semantic inspection with supported accessibility tooling; Night/Day/HC and reduced motion; paused media session | One actionable node per ordinary control; correct role/name/selected/unavailable/Working/watch progress; no duplicate artwork text; focus remains visible without scale; paused valid session retains Now Playing. |
| P2 | Player controls, seek, subtitle/audio menus, error/completion dialogs and Back, if adapter lands | Native Media3 functions remain available; safe default dialog focus; playback/reporting intent preserved; return restores the launching tile. |

All device outcomes in this matrix remain **unexecuted by WP6**. Root owns final
combined checks and observed visual/device acceptance after writers finish.
