# Integrated action-integrity review

2026-10-31. Independent Astra Extra High review leaf; no subagents.

Reviewed baseline `0809edfb31d2fac467dc55e2b67fd93d9cf777df` through
`9af13c8b29f913f464585eaf72209c4b86132db3` on
`t3/explore-agentic-tv-pivot`. HEAD matched the reviewed endpoint and the
worktree was clean before this note. The governing inputs were
`docs/design/vela-design-language.md`, `docs/design/vela-component-contract.md`,
and `docs/design/evidence/ui-integration-2026-10-31.md`.

## Result

**No proven finding within the assigned scope. Zero production corrections
requested.** This is a comparative source/action review, not TV runtime,
browser-rendering, firmware, or release acceptance.

Scope was limited to effect admission, confirmation, action identity and privacy
integrity introduced by this increment. Navigation geometry/focus traversal and
ActivationInputNode internals belong to other assignments and were not reviewed.
Unchanged backend behavior was inspected only where needed to establish a changed
UI's call path; it was not treated as a new regression.

## Evidence

- **Common tile path and original action identity.** `HomeActivity.kt:67–95`
  loads the layout's row specifications and renders them through `TileRow` in
  document order. `TileCards.kt:15–31` forwards the original `Tile` through
  `MediaCard` selection; affordance/source labels do not reconstruct its action.
  `TvShell.kt:63–74` retains selection into a title sheet or `perform(action)`.
  `HomeData.kt:145–148`, `Runner.kt:84–103` and `TileCalls.kt` retain the guarded
  Runner/tool path, including actual package, URI, entity, service and item
  arguments and propagation of tool errors. Default and agent-composed rows
  have no separate execution path. Focus callbacks update presentation only.

- **Title and profile/configuration state.** The entire title state/loading/
  pagination block before `val content` is byte-identical to baseline
  (`TitleDialog.kt:24–91`). Its settings-keyed loads, pagination generation,
  cancellation and connection/filter comparisons remain. Save/open/episode
  callbacks retain the original action and `!opening` eligibility; shell
  `perform` retains its synchronous `opening` guard and originating-tile check.
  `TvShell.kt:46–55` additionally keys the retained Home presentation by household
  revision and retains title/retry clearing on revision changes. Existing direct
  watchlist and appearance-preference writes remain baseline callbacks; they
  were not newly routed around Runner by this increment.

- **Arrange Home admission.** `ArrangeHome.kt:48–56` checks the live Runner and
  control-owner values plus its local busy flag, then applies the transformation
  to `app.settings.layout.value`, not the composed row list. `LayoutEdits.kt`
  preserves row contents/identity, rejects missing IDs and last-row removal, and
  bounds movement. `ArrangeHome.kt:17–21` validates an encode/decode round trip,
  obtains exactly one `owner-layout` Runner reservation and performs `setLayout`
  on IO. Runner's Main-confined admission remains non-replacing
  (`AgentSession.kt:175–184`). Remove/default confirmations call the same guarded
  edit function (`ArrangeHome.kt:94–99`); a busy confirmation cannot bypass it.
  The sole new mutation path is the contract's owner-layout write.

- **Management confirmations, revisions and drafts.** Independently extracted
  and compared every `management.*(...)` invocation and argument expression:
  all 4 in `ManagementPage.kt`, 11 in `ProfileManagement.kt` and 8 in
  `RoutineManagement.kt` match baseline in order. Profile/memory editor revision
  capture, original drafts, provenance, routine definition revisions and retry
  request-key reuse remain. `ManagementActions.run` still sets busy before
  launching and rejects work while busy; confirmation executes through this
  function (`ManagementPage.kt:28–64`). Moving the confirmation out of the lazy
  feedback row changes its composition lifetime, not its execution authority.
  `VelaTextField` gates writes on enabled/read-only state and preserves length
  limits (`design/Fields.kt:16–38`). The one-time external credential remains
  ordinary `remember` state, masked until explicitly revealed and discarded by
  Back/hide/revoke; its value is not added to status text or saved state
  (`ManagementPage.kt:145–201`).

- **Media session binding and outcomes.** The complete observer, policy,
  selected-session and `act` block (`NowPlayingSurface.kt:45–106`) is
  byte-identical to baseline. It retains the live household-policy check,
  refreshed exact session lookup, live busy arguments, single guarded
  `executeTool` call and error/unknown-effect feedback. Unchanged
  `NowPlayingObserver.kt:110–114` resolves the exact session reference;
  `NowPlayingModel.kt:72–102` sends both session and package, checks capabilities
  and seek bounds, and distinguishes dispatch from observed playback success.
  Bar visibility is session-based, so a paused valid session remains eligible.

- **No TV approval backdoor or automatic mutation.** Launcher/design searches
  and callback inspection found no new `runner.answer`, direct tool execution,
  or mutation from focus/composition. Activity approval notices remain read-only;
  the lantern navigates to Activity. Shared design components do not receive
  VelaApp/Runner/store objects. Layout requester-cache and Now Playing status
  effects update UI state only. Metadata loads and observer lifecycle work are
  retained reads, not automatic owner actions. Overlay keeps its window type,
  non-focusable/non-touchable flags and exact approval hint.

- **Phone approval and privacy behavior.** Removing only the new task
  `dataset.state` and management-button `className` assignments reproduces the
  baseline script exactly. An independent HTML-parser comparison found all 105
  baseline IDs retained and the same 86 form/control elements with unchanged
  functional attributes and membership in `main` (presentation classes, labels,
  placeholders and row hints excluded from that comparison). Exact displayed-ID
  capture remains at `phone.html:552–559`; connection/request/approval locks,
  management loading, drafts, credential handling and `textContent` rendering
  are unchanged.

- **Native player callback preservation.** Reversing only the new chrome field,
  construction/application and dialog/show substitutions reproduces the entire
  baseline `PlayerActivity.kt` byte for byte. Retry, cancellation, Back, next-item
  playback, reporting, profile checks and player lifecycle are therefore
  preserved at their callers. `PlayerChrome.kt` was inspected: it styles the
  existing Media3 views and native dialogs without replacing click, key, player
  or cancellation listeners.

## Independently executed verification

- `node --test android/app/src/test/ui/phone.test.cjs`: **36 passed**, zero failed,
  canceled, skipped or todo. This exercised displayed approval-ID binding during
  polls, single-flight requests, draft/revision retention, text-safe rendering,
  explicit private export and external-credential masking/discard behavior in
  the repository's DOM harness.
- In-memory Python source comparisons and HTML parsing produced the equality
  results above. These were structural audits, not simulated Android execution.

The coordinator's reported Gradle, fixture-HTTP and Compose-handler results were
read as context, not rerun or claimed as this reviewer's executions. No shared
Gradle process, listener, browser, device, SSH session, credentials, infrastructure,
firmware action or Git mutation was used. This evidence note is the only file
written by this assignment.

## Limits and remaining acceptance

No source finding is awaiting a production fix or an owner decision from this
review. Live TV input dispatch, disabled/busy interaction, accessibility prompt
timing, actual media playback and native rendering still require the separate
acceptance already recorded in the integration evidence. The DOM tests establish
script behavior in their harness, not native browser behavior. Nothing here
requalifies unchanged backends or grants merge/release approval. The physical
Shield remains paused by its owner; this review neither contacted it nor requests
an exception to that pause.
