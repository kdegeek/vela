# Durable routines contract

Implementation: `android/app/src/main/java/app/vela/tv/routines/`. The shared engine,
trusted profile bindings, Runner callback, native JobService, manifest and TV/phone
management are now assembled. See [Application API](application-api.md) and
[integrated acceptance](acceptance-2026-10-31.md). The integration checklist below
records ownership requirements, not a claim that these modules remain unmounted.
No new library dependency is required.

## Management API

`RoutineEngine.open(storage, executor, now, newId)` opens and recovers the store.
Open **once per application process**, on IO, and retain that engine independently
of activities. Do not open another engine against the same file while the first
is live, and do not put the JobService in another Android process. The mutex
serializes competing UI, phone, startup, and background triggers within this
instance. AtomicFile supplies atomic replacement, not cross-process arbitration.

Observe `engine.state: StateFlow<RoutineSnapshot>` for definitions and history.
All management operations are suspending and safe to call concurrently:

| Operation | Contract |
| --- | --- |
| `create(spec)` | Validate and persist a new definition; at most 100 definitions. |
| `update(id, expectedRevision, spec)` | Reject stale revisions. Cancel queued/active snapshots. Re-arm only if the schedule changed. |
| `setEnabled(id, expectedRevision, enabled)` | Disable cancels queued/active work. Enable retains its cursor and applies missed-run policy on the next wake. |
| `delete(id, expectedRevision)` | Cancel queued/active work, remove definition, retain execution history. |
| `runNow(id, requestKey)` | Persist an immediate normal task, including for disabled/exhausted routines. Reject a second pending run. Does not consume a future scheduled slot. |
| `cancelRun(runId)` | Cancel queued work immediately; request cancellation of active work. Repeated cancel is harmless. Does not disable future occurrences. |
| `restartRun(runId, requestKey)` | Cancel the routine's pending work, then enqueue a fresh task from its **current** definition. Wait for prior cleanup before dispatch. Does not move its scheduled cursor. |
| `onWake()` | Recover failed result writes, consume due slots and expire queues, then attempt serial execution. An overlapping wake does not start another drain. |

Management calls commit state; they do not start an invisible timer. After a
successful mutation, launch `onWake()` in an application-owned IO coroutine.
The caller should receive the persisted definition/run ID without waiting for a
15-minute agent task. Also launch a wake at process startup and when owner work
finishes. Never bind the dispatcher to a phone request or an Activity's lifetime.
Canceling the dispatcher interrupts its current routine task, not an owner task.

Require a fresh client-generated request key for a deliberate manual run/restart;
reuse it for transport retries. Keys use 1–128 ASCII letters/digits/`_`/`-`, are
scoped to a routine, and cannot be reused for a different operation. Idempotency
is guaranteed only while that run's history entry is retained. History keeps up
to the 500 most recently finished runs plus all nonterminal runs. Display history
with its actual finish/start/queue timestamps; list position is enqueue order.
Each run retains the submitted name, task and routine revision, even after edits
or deletion. Task text is 1–8000 characters; names are 1–120 characters.

## Schedules and missed work

All persisted instants are epoch milliseconds. Supported schedule objects:

```json
{"type":"once","at":1793448000000}
{"type":"interval","firstAt":1793448000000,"everyMillis":900000}
{"type":"weekly","days":[1,2,3,4,5],"time":"18:30","zone":"America/Chicago"}
{"type":"monthly","day":31,"time":"09:00","zone":"America/Chicago"}
```

Weekly days are ISO Monday=1 through Sunday=7; all seven days is a daily
schedule. Calendar times use strict `HH:mm` and a valid named Java/Android time
zone. Device zone changes do not silently move an explicitly zoned routine.
Spring DST gaps shift forward by the gap duration; fall overlaps select the
earlier offset **once**. Monthly days beyond month length clamp to the last day
without moving later months' requested day. Interval schedules use elapsed time,
remain anchored to `firstAt`, and accept 15 minutes through 366 days.

Creating/editing a recurring schedule starts with the next slot at or after the
current instant; it does not backfill time before creation. A one-shot in the past
is evaluated under missed-run policy. Exhausted one-shots retain a null cursor;
disable/enable and task/name edits never replay them. Run now is available for
an explicit retry, or change the scheduled time to re-arm.

The default `RUN_LATEST` coalesces downtime to the most recent due occurrence if
it is within `maxLatenessMillis` (default 24 hours; range one minute–30 days).
`SKIP` permits only a 60-second on-time grace. This is intentionally strict and
can skip occurrences with the inexact Android background adapter. The current
due cursor and one queued/missed history entry are committed together, and the
cursor advances beyond now. Older coalesced slots are not individually logged.
If another run of that routine is queued/running/canceling, the latest slot is
recorded `MISSED` and consumed; there is no overlapping execution or backlog storm.
Queued tasks expire at their original slot's allowed lateness deadline (manual
tasks use enqueue time plus the configured window). A busy agent does not extend
that deadline. Moving the clock backward does not move consumed cursors backward.

## Execution and cancellation safety

Inject `RoutineTaskExecutor.tryExecute(RoutineTaskRequest): RoutineTaskResult`.
The request contains only run ID, routine ID, name, and a normal owner task.
There are no saved raw tool calls, stored approvals, or bypass tokens.

The coordinator's adapter must atomically reserve the normal AgentSession task
slot **with all owner submission entry points**, and:

1. Return `Busy` without effects if any owner/agent task already holds the slot.
   Checking `running` on IO and then calling `Runner.ask()` is unsafe: current
   `ask()` replaces the active request. Add a main-confined non-replacing
   submission-and-await operation to AgentSession/Runner instead.
2. Execute the task through the same Agent/provider/TvTools path, fresh current
   settings, validation, guard classifications, approvals, and time limits as
   owner work. Do not reuse an earlier routine's conversation or approvals.
3. Await its actual terminal result, returning `Completed` or `Failed` with a
   safe, credential-free detail. An accepted task ID is not a completed result.
4. Preserve owner priority: an owner can stop/replace routine work. Cancellation
   must target the routine's task ID/generation and await its cleanup, including
   pending approval cancellation. Never call an unqualified `Runner.stop()` in
   delayed cleanup that might stop a newer owner task.
5. Wake the engine after owner completion so queued routines need not wait for
   another periodic job. Do not await that wake inside the task-slot lock.

The engine durably claims `RUNNING` before calling the adapter, including its
idle arbitration. `Busy` returns that claim to `QUEUED`. One drain executes at a
time, even across restarts requested during cancellation. Edit, disable, delete,
cancel and restart first persist cancellation intent; only then do they cancel
the task coroutine. Active work is `CANCELING` until cleanup finishes, then
`CANCELED`. The next task waits for cleanup, even if a callback is slow to honor
cancellation. The adapter must cooperate; Kotlin cannot forcibly stop arbitrary
uncooperative code.

The engine applies a 15-minute execution timeout. A timeout or interruption may
leave external effects; it is never automatically replayed. On process restart,
persisted `RUNNING`/`CANCELING` claims become `INTERRUPTED`, including the narrow
window between saving a claim and entering the callback. Persisted `QUEUED`
work is eligible for dispatch until it expires. Failed result writes also leave
an interrupted claim on the next successful wake. Inspect current device/home
state before explicitly restarting unknown work. This prevents duplicate
dispatch of consumed slots; it does **not** promise exactly-once external effects.

## Coordinator integration edits

No Gradle/catalog/settings edits or new WorkManager dependency are needed.
Existing Kotlin serialization/coroutines and native API 21+ JobScheduler/
AtomicFile plus API 26+ java.time fit the current minSdk 28 project.

1. **`agent/AgentSession.kt` and `agent/Runner.kt`:** add the atomic non-replacing,
   task-ID-scoped submission/await/cancel operation described above. All owner
   asks and scheduled starts must share its main-thread arbitration. Adapt this
   operation to `RoutineTaskExecutor`; do not implement it with a racy separate
   `running` check followed by `ask()`.
2. **`VelaApp.kt`:** implement `RoutineRuntimeOwner`. Initialize one engine using
   `AtomicRoutineStorage(this)` on IO, after Runner exists, and return that same
   instance from the suspending `routineEngine()` method. A single lazily-created
   application-scope `Deferred<RoutineEngine>` works. First call
   `AndroidRoutineScheduler(this).ensureScheduled()` and surface failures before
   accepting writes. Launch an application-scope IO wake at startup. This lazy
   initialization must also work when Android starts only the JobService after
   process death/reboot, without any Activity.
3. **`AndroidManifest.xml`:** add the following permission and application child:

   ```xml
   <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

   <service
       android:name=".routines.RoutineJobService"
       android:exported="true"
       android:permission="android.permission.BIND_JOB_SERVICE" />
   ```

   Keep it in the default app process. The system signature permission protects
   this exported system-bound service. A custom boot receiver is unnecessary
   for persisted JobScheduler jobs. Reserve job ID `0x56454C41` (1447382081).
4. **Management UI/phone boundary:** expose list/create/edit/delete/enable/
   disable/run-now/history/cancel/restart through the API above, using current
   revisions and client request keys, authenticated with the existing owner
   boundary. Show pending/busy, cancellation, failure, expired/missed, and unknown
   effect states accurately. Handle validation/I/O errors without discarding the
   last state. Dispatch in application scope after successful writes.
5. **Coordinator checks:** run the combined Android build/unit suite. Exercise
   process kill/reboot, force-stop/relaunch, persisted job permission/registration,
   OS job cancellation, owner/routine arbitration, and approval handling on an
   authorized emulator. This worker did not drive physical devices or claim
   Android runtime/visual acceptance.

## Background guarantees and limits

`AndroidRoutineScheduler` registers a persisted 15-minute periodic job once.
It polls the engine rather than resubmitting an executing job under the same ID.
`RoutineJobService` offloads engine work from the main thread and cancels its
coroutine when Android stops it. Scheduling failure is an error, not silent
fallback to an in-memory timer. Routine state lives in
`Context.noBackupFilesDir/routines-v1.json` via AtomicFile, independent of the job.
Malformed/unsupported/oversized documents and unreadable existing files fail
closed; they are never silently replaced by empty state. The document cap is
16 MiB; a full/unwritable store rejects changes before publishing or dispatching.
No credential store or backup migration is introduced.

Once integrated, ordinary process death/reboot does not depend on an app-alive
timer. Android scheduling is **best effort and inexact**: a 15-minute interval
is not a maximum lateness guarantee. Doze, quotas, unavailable networking,
force-stop, user background restrictions, and OEM policies can defer execution
arbitrarily; startup applies the same missed-run policy. It is unsuitable for
alarms requiring an exact minute. Android can stop a job before the engine's
15-minute task limit; that task becomes interrupted and is not automatically
retried. The callback cannot bypass Android's background Activity-launch or
accessibility restrictions; missing capabilities must produce a normal guarded
failure or owner approval, not privileged fallback.

Official API references verified for this implementation:

- [JobInfo.Builder: persisted jobs and periodic timing](https://developer.android.com/reference/android/app/job/JobInfo.Builder)
- [JobService: threading, lifecycle and stop handling](https://developer.android.com/reference/android/app/job/JobService)
- [AtomicFile: atomic replacement and caller-owned locking](https://developer.android.com/reference/android/util/AtomicFile)
- [ZonedDateTime: gap and overlap resolution](https://developer.android.com/reference/java/time/ZonedDateTime)
- [Auto Backup: excluded no-backup storage](https://developer.android.com/identity/data/autobackup)

## Regression checks

`ScheduleTimesTest` and `RoutineEngineTest` use an injected clock, serialized
storage and injected task callback. They cover DST gaps/overlaps, fixed zones,
weekdays/month-end/interval arithmetic, creation/edit/disable/delete/manual keys,
busy-agent queueing, competing wakes, cancellation cleanup, process death with
queued/claimed work, explicit restart, missed-run/overlap/expiry behavior,
backward-clock changes, history retention, invalid inputs, timeouts, callback
failure, and write failures before dispatch/after effects. Serialized restart
tests exercise the stored document; they do not emulate AtomicFile filesystem
crash behavior or Android JobScheduler lifecycle.

Worker verification: all four production routine files and both test files
compiled in isolation with Kotlin 2.4.21, coroutines 1.11.0, serialization 1.12.0,
JDK 21/JVM target 17 and the installed Android 37 SDK; JUnit 4.13.2 passed all
**33 tests**. No project-wide Gradle build was run while peers were editing.
The coordinator can run the integrated suite with
`./gradlew :app:testDebugUnitTest --tests 'app.vela.tv.routines.*'` from `android/`.
