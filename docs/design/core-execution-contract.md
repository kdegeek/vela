# Core execution integration

Status: implementation on disk; 61 targeted JVM tests passed, narrow Android adapter compilation passed. The coordinator owns combined build and app assembly.

## One controller, one prompt

Construct `Runner(app, scope, control = app.control, approvals = app.externalApprovals)` after creating the single
`ControlArbiter` and `ControlApprovalBroker`. AgentSession accepts those same named constructor arguments. Its
`pending` is the broker's actual `StateFlow<ControlApprovalPrompt?>`, including external prompts, ID and uptime.
`Runner.answer(allow, promptId)` answers that broker. Keep the existing remote grace and key-down/key-up binding.
Stop cancels the session job and `app.mcpService?.cancelAll()`. Ownership remains held through cancellation cleanup.
`running` covers session-owned work and its drain chain; external adapters and phone input MUST additionally
consult/acquire `app.control`. External controller acquisition must run on Main.immediate and also reject
`session.running` so it cannot jump into a reserved replacement/drain chain before that job acquires its lease. Do not infer idle from the task record's canceled state.

`Runner.executeTool(call: ToolCall, controller: String = "owner-ui"): ToolOutcome` acquires a non-replacing session
reservation and the single shared control lease, then invokes ToolExecutor. Busy fails immediately. This is the
entry point for standalone debug/UI actions. HomeData.perform routes every actual tile action here. AskAgent tiles
submit a normal task only while idle. Synthetic tile clicks during an active task cannot replace that task or
queue a prompt behind it: the UI receives a busy error. Agents should use home_call/open_link/jellyfin_play.
screen_press/remote.select targeting Vela return an explicit error directing the agent to direct action tools.
No UI hook is required for this fail-closed behavior; do not add a second tile guard or unguarded launch path.

## Nested execution and MCP

If the caller ALREADY holds `app.control.withControl(controller)`, call this directly, without Runner.executeTool:

```kotlin
ToolExecutor(TvTools(app).all()).execute(
    ToolCall(requestId, toolName, arguments),
    confirm = { _, hit -> app.externalApprovals.confirm(controller, hit) },
)
```

The executor never acquires a controller or constructs a model. MCP's GuardedToolBackend must replace its old
check/approve/check/run sequence with the executor INSIDE its existing lease. A second GuardHit equality check
is not target identity. Tools from TvTools deliberately reject direct `run`; use `prepare` through the executor.
The executor validates arguments, prepares once with a 30-second deadline, asks once, executes once with a
30-second deadline and bounds results to 24,000 characters. Approval duration belongs to the shared broker.
Pass `stopped` when executing a batch; denial/invalidation must stop the rest of that batch and the caller's task.
Never automatically retry a timeout/unknown effect.

The default Tool.prepare rechecks classification for legacy immutable-argument tools. Tools with mutable external
targets MUST override it and bind actual identity; risk equality alone does not satisfy this contract.

`Tool.prepare(args): PreparedToolAction` captures the real target/configuration BEFORE asking.
`PreparedToolAction(hit, valid = { stillSameIdentityAndRevision }) { performCapturedAction() }` is single-use.
Validation compares settings object identity (not just a risk label), pinned household revision and accessibility
node identity/window/package/content/focus. If an action suspends again, it must revalidate immediately before
dispatch; HandsService does this after its cancellable focus-settle. No private snapshot is serialized to a model.

## Routines

`Runner.submit(text): String?` runs on Main and atomically reserves a NON-replacing task slot. Null means Busy.
It checks both the session reservation and external controller; it must replace no owner task. Accepted routines
run the normal Agent/provider/TvTools path with fresh settings, profile, guards and empty conversation history.
`Runner.await(taskId): TaskRecord` awaits that exact task's terminal state (complete/failed/canceled).
`Runner.cancelAndJoin(taskId): Boolean` cancels ONLY that ID and awaits its cancellation cleanup. It cannot stop
an owner task that subsequently replaced the routine. Call it from NonCancellable cleanup if the adapter itself
was canceled. RoutineTaskExecutor maps null to Busy, complete to Completed, other terminal states to Failed.
Wake the engine after `running` becomes false, outside execution/ownership locks.

## Profile and memory binding

Runner uses the app's `householdOrNull()` and `householdReady` seams. Null/unavailable storage fails closed.
Every agent run pins the active profile ID and household revision; every tool revalidates that revision. All
household commits clear conversation/activity/overlay context and cancel old work; a stale run cannot execute
or send a later model turn. Coordinator profile/memory mutations should stop and await current work BEFORE
committing, rather than relying solely on asynchronous StateFlow invalidation. `Runner.stopAndJoin()` cancels
session/MCP work and awaits actual drain/idle; run the following mutation under the shared controller on Main
and reject competing new submissions until that mutation finishes. `Runner.cancelAndJoin(taskId)` already
uses NonCancellable cleanup internally and cannot cancel a different task ID.

`TvTools(app, profile = ProfileExecution.capture(store))` pins an agent run. Unpinned TvTools capture on each
preparation for long-lived external backends. `memory_retrieve` has only an optional query, no profile argument;
it returns fresh opted-in JSON bounded to 8 entries/4000 characters. Memory is tool-result data, never system
instructions. `ProfileToolPolicy` enforces agent/home restrictions and denies opaque screen/remote/app/link/media
routes for content-restricted or app-allowlisted profiles. Jellyfin tools use canPlay with missing trusted
library/rating metadata and therefore fail closed where those are required. This is conservative: restricted
profiles cannot use those media routes until the coordinator supplies trusted metadata. No owner profile-edit
or memory-write tools are exposed to the model. Preferences do not grant access.

## Playback observation

jellyfin_play creates a PlaybackRequest, starts PlayerActivity.intent(app, request), then awaits that exact
request's awaitStarted(). Only PLAYING reports observed playback. Failure/timeout/stopped/canceled are errors.
Cancellation cancels the request, preventing a late launch from becoming apparent success. Player/device runtime
acceptance remains the media owner's/coordinator's responsibility.

## Verification boundary

Isolated checks use Kotlin 2.4.21, coroutines 1.11.0, serialization 1.12.0 and JDK21/JVM17. The Android adapter
compile uses the locally installed Android36 SDK, existing dependency classes, and narrow app/Player intent
stubs for the coordinator's not-yet-landed assembly seams. It is a type/syntax check, not full app assembly or
a device UI check. No device was driven. Security heuristics remain a backstop, not a proof against arbitrary
third-party app semantics or prompt injection. All cover actions now prompt conservatively because entity IDs
alone cannot prove a cover is not a garage door.

## Integrated control review correction (coordinator)

External application admission now calls `Runner.withController(controller) { ... }`,
which enters `AgentSession.withController` on Main.immediate. This reserves the task
slot and screen lease together; checking only ControlArbiter.owner cannot detect an
accepted replacement waiting for predecessor cleanup. `GuardedToolBackend` accepts
an `admission` callback; VelaApp supplies this Runner path. Already admitted tool
execution still uses ToolExecutor without acquiring another lease.

ControlArbiter publishes idle only after acquisition becomes possible, under one
short synchronized transition. An immediate collector may acquire the next lease
without a stale Busy error, and a previous owner cannot overwrite its successor.
Prepared accessibility presses retain and revalidate the selected leaf, dispatched
ancestor, both labels and their relationship, along with the existing window,
focus, settings and profile checks. `wait` shares opaque screen-read restrictions.

The coordinator's isolated current-source suite passes 22 control/profile/management
regressions, including deep purchase labels, immediate reacquisition, replacement
reservation exclusion and profile switching after external cancellation. Combined
verification follows the active media integration workers; Android device dispatch
is still limited by the documented device-tool lifecycle interruption.

External reservations now also carry an explicit controller kind. Owner Ask may
replace an internal task, but returns Busy for an external/direct controller,
including its canceled cleanup drain. Stop remains the explicit cancellation path.
The follow-up ownership regression and final combined 396-test suite pass.
