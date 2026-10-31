# Independent review corrections

First review baseline: `4b711104d82f5b5cd8952d8d20390dddd9597b0d`, GPT 6.1 Sol, 2026-10-31.
These are implementation and regression dispositions, not security certification.
See [integrated acceptance](acceptance-2026-10-31.md) for exact build/device boundaries.

| # | Finding | Implemented correction / evidence |
| --- | --- | --- |
| 1 | Generated/saved home actions bypass guard | Actual tile actions route through guarded Runner execution; tile execution regressions pass. Competing callbacks fail Busy. |
| 2 | Approval does not bind target/configuration | Prepared actions pin accessibility identity, resolved component, HA client, settings and profile revision; invalidation regressions pass. |
| 3 | Delayed OK after Stop; early controller release | Cancellable settling/revalidation and draining ownership; cancellation/ownership regressions pass. |
| 4 | Transparent POST replay and doubled model retry limit | SingleAttemptBody plus explicit model-only retries; 503 request-count regressions pass. |
| 5 | Default MediaSession IDs collide | Unique session IDs, reusable launches and onNewIntent handling compile in the full APK. Overlapping playback device acceptance remains pending. |
| 6 | Integration body reads block Main | Body consumption/close occurs off caller; delayed-body HTTP regressions pass. |
| 7 | Oversized arguments escape context bound | Complete oversized exchanges are evicted with valid history pairing; large-argument regressions pass. |
| 8 | Playback success before observed playing | Exact request-associated PLAYING outcome; failed/canceled/delayed outcome regressions pass. Native playback remains to be accepted. |
| 9 | Failed stop report suppresses resume update | Independent user-data write and bounded in-process retry; report-failure regressions pass. |
| 10 | Truncation before credential redaction | Redact before truncation; boundary regressions pass. |

The integrated state review found four further defects, corrected by the coordinator:

| # | Finding | Correction / evidence |
| --- | --- | --- |
| 11 | Cancellation loses reserved routine ID on return from Main | Retain ID inside the admission dispatcher with cleanup already installed; handoff cancellation regression passes. |
| 12 | Unverified routine claim can replay after silent file replacement failure | Explicit sync and read-back before publish/dispatch; silent claim/result loss and reopen regressions pass. |
| 13 | Subtitle On misses non-default language-tagged tracks | Media3 selectTextByDefault; On/Off/Automatic parameter regressions pass. |
| 14 | Valid sidecar writes exceed startup read cap | Shared bounded read/write limit; 100 large valid bindings reopen in regression. |

Coordinator runtime testing found an MCP startup hang: the implicit Ktor CoroutineScope
attached the listener to the startup withContext. It now has its own canceled lifetime.
A real socket regression reproduced the timeout before the correction and passes
start/stop/reopen afterward. Corrected assembled-app acceptance remains pending because
the device tool's close/relaunch path failed its diagnostic retry.

All 14 corrections and the MCP lifecycle regression were included in the 301-test
combined passing run. The separate integrated control review and its follow-through are recorded below. Read and address
actual PR review threads before any merge; the owner-review merge gate remains.

## Final integrated control/transport follow-through

The current combined suite passes **396 JVM tests**, lint and APK assembly. It
includes these subsequent corrections as well as all earlier ledger entries:

| # | Finding | Correction / regression |
| --- | --- | --- |
| 15 | Press binding drops a deeply selected leaf's purchase text | Retain and revalidate selected node, dispatched ancestor, both labels and ancestry; deep purchase/reparenting regressions. |
| 16 | MCP overtakes an accepted replacement during cleanup | Main-confined Runner.withController admission reserves the session slot and lease together; replacement handoff regression. |
| 17 | Idle notification arrives before control is acquirable | One synchronized lease/owner transition; immediate acquisition and profile-switch regressions. |
| 18 | wait bypasses restricted screen-read policy | All snapshot-returning opaque routes share the restriction; child-policy regression. |
| 19 | Ask cancels an externally admitted controller | Explicit task/controller reservation kind; Ask returns Busy during external work and drain, while internal replacement remains supported. |
| 20 | Modern SSE can lose a successful tool response | Writer-owned final-flush cleanup plus setup/pre-write failure cleanup; unchanged generated property and sequential response regressions now pass. |
| 21 | Package-targeted media action can reach a replacement session | Bounded opaque references, captured exact token/controller, fresh membership/capability checks immediately before one dispatch; replacement/ambiguity tests. |
| 22 | Loopback listener startup waits forever on its own server child | Separate explicitly drained listener lifetime; real listener start/stop/reopen regression. |

The fresh control review reproduced only item 19 after items 15–18 were corrected;
its retained regression now passes. All nine generated properties pass unchanged.
No review finding remains intentionally ignored. These finite source/JVM/socket
checks do not establish Android dispatch, live-provider quality or security certification.
