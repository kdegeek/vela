# Exact MediaSession targets

Implementation handoff, 2026-10-31. This contract supersedes the package-only
selection limitation in `now-playing-contract.md`. It does not supersede that
surface's lifecycle, privacy, profile, ownership, feedback or visual acceptance
requirements.

## Tool API

`media_status {}` returns at most 24 session lines. Every line includes a usable
`session=ms_…` reference, explicit `package`, observed state, bounded title,
position/duration and currently supported action names. Metadata is observation,
not proof that a previous request succeeded. Omitted or duplicate-identity sessions
are called out; nothing selects an omitted session by list index.

`media_control` keeps the existing action/seconds/package schema and adds the
optional string `session`:

```json
{"action":"pause","package":"com.example.player","session":"ms_<process-namespace>_<serial>"}
```

The example reference is schematic: copy a real reference from `media_status`.
The valid format contains a UUID namespace and base-36 serial, at most 53
characters. Raw Android tokens, token hashes and controller objects never appear
in the tool result or arguments.

Selection rules are explicit:

- With `session`, resolve that exact reference against the complete fresh active
  list. If `package` is also supplied, it must match. Invalid, absent, expired,
  evicted or ambiguous references fail without falling back to package/default.
- With `package` alone, exactly one session for that package must exist.
- With neither, exactly one playing session is selected; if none are playing,
  there must be exactly one active session. Its package must also have only one
  session. Multiple playing sessions and same-package duplicates fail closed.
- Duplicate token identities are rejected even across different packages or
  beyond the 24-session presentation cap. Full Android lists determine ambiguity.

Legacy package/default calls bind the current target **when preparing**; they
cannot identify an earlier observation that supplied no reference. Callers that
need observation-to-action identity must pass `session`. The native Now Playing
surface always passes both the observed exact `session` and `package`.

## References and lifetime

`ProcessMediaSessionTargets.references` is shared by all `TvTools` instances and
`NowPlayingObserver` instances in this process. `MediaSessionTargetStore` retains
at most 128 records containing token identity, package, creation time and opaque
reference; it retains no controller, media metadata, artwork or queue. Records
expire absolutely after five minutes of elapsed real time; reads do not extend
that lifetime. Expiry is checked on access, so idle token records can remain in
memory until another access, within the 128-record bound. Capacity eviction is
oldest-issued first. A single observation never evicts its own returned references.

A process-random UUID namespace and never-reused serial form the reference. Token
**equality**, never hash equality or string form, identifies the Android session.
A reference is a target selector, not permission: possession grants no controller
lease, profile access, settings validity or tool admission. References are neither
persisted nor transferable across process restarts. Expiry, eviction, observed
disappearance, duplicate identity, token/package mismatch, a session-destroyed
callback or a failed session-list read invalidates the corresponding references.
A renewed session/reference never revives an old reference.

Observer stop/disposal discards its controllers and metadata as before. Stopping
one observer does not revoke another caller's process references. The next actual
command still rereads Android membership and validates expiry independently.

## Preparation and one dispatch

`MediaSessionCommands.prepare(MediaSessionCommandRequest): PreparedToolAction`
runs selection on `Dispatchers.Main.immediate` and captures the exact
`AndroidMediaSessionTarget`, including its immutable `MediaController` and actual
`MediaSession.Token`, before the executor's approval boundary. No mutable current
controller is stored in a reusable tool definition.

Execution runs on Main, rereads the complete active list, resolves the captured
reference, checks exact token/package identity, and rereads the **captured**
controller's actual playback state, action mask, position and duration. A new
controller wrapper for an equal token can confirm membership, but it never
replaces the captured dispatch controller. Package-only preparation also fails
if package duplicates appear before execution. Reference lifetime/revocation and
coroutine cancellation are checked again directly before the single synchronous
transport call. There is no suspension between these final checks and dispatch.
Identity/capability invalidation throws `ActionInvalidatedException`, preserving
`ToolExecutor`'s stop-batch behavior. Cancellation propagates; no automatic retry,
settle delay, follow-up effect or replay is introduced.

Only advertised play/pause (including combined play/pause support), stop,
previous, next and seek actions are accepted in supported observed phases.
`seek` and relative `skip` require integer seconds, a known nonnegative position,
a known positive duration and actual seek support. Other actions reject seconds.
Seek is clamped to the fresh duration. Skip projects the fresh elapsed-time
position, applies an overflow-safe signed offset and clamps to `[0, duration]`.
Seconds-to-milliseconds conversion widens before multiplication. Missing or lost
capabilities/seek metadata dispatch nothing.

The Android adapter calls the captured controller's corresponding
`transportControls` method once. A non-error result says the request was sent and
that playback may not have changed; callers must observe `media_status` or the
native observer for actual state. No transport return value is interpreted as a
playback acknowledgement.

`TvTools.boundTool`, `ProfileExecution`, existing settings identity validation,
`ToolExecutor` and Runner/controller admission are preserved. Manual Now Playing
continues through `Runner.executeTool`, with no model/provider construction.
Multiple same-package sessions can now be selected safely through their exact
references. The UI still checks live busy/policy/state before submitting.

## Verification and limits

The isolated check compiles the actual `TvTools.kt`, `ToolExecutor.kt`,
`MediaSessionTarget*.kt`, `NowPlaying*.kt` and `NowPlayingSurface.kt` against the
existing compiled application APIs, installed Android SDK 37, Kotlin 2.4.21,
Compose compiler plugin, JDK 21/JVM 17 and installed dependency classes. No
application, Runner, household or Android session stubs substitute for those APIs;
shared Gradle outputs are untouched.

**44 JVM tests pass**: 24 injected-session/reference tests plus the existing 20
Now Playing model/policy tests. The new cases cover exact and package-compatible
replacement between prepare/execute (including actual ToolExecutor batch stop),
equal-token controller wrappers, hash
collisions, same-package duplicate selection, reference/package disagreement,
duplicate identities beyond the display cap, unique default selection, unsupported
and lost capabilities, every action's single dispatch, fresh seek/skip bounds,
missing seek metadata, cancellation before dispatch and during final state reads,
expiry/revocation during those reads, failed reads, bounded status output,
absolute expiry, eviction, disappearance, destruction, process replacement and
clock regression. Native argument tests now require exact references and allow
same-package controls only with that binding.

The reproducible isolated compiler/test script and class output are under
`/var/folders/tc/f5wf7p2n68z7htvy4dr283sw0000gn/T/vela-media-target-check-uidmdzhn/`.
The coordinator owns final combined Android tests, lint and APK assembly after
writers quiesce. No device, ADB, infrastructure, deployment, Git mutation or merge
was performed in this assignment. The recorded device close/relaunch failure
remains stopped. No visual, lifecycle-on-device or real playback acceptance is
claimed.

Android does not provide an atomic validate-and-dispatch transaction. A session
can disappear or change capabilities after the final system read; a request
already sent cannot be undone by cancellation and third-party apps may ignore it.
The captured token prevents redirection to a replacement session; it cannot pin a
media item inside a surviving session, guarantee delivery, or guarantee a playback
change. Session list/state/metadata are only as truthful and current as Android and
the source app report them.

Official API references checked: [MediaSession.Token](https://developer.android.com/reference/android/media/session/MediaSession.Token),
[PlaybackState actions](https://developer.android.com/reference/android/media/session/PlaybackState#getActions()),
and [MediaController.TransportControls](https://developer.android.com/reference/android/media/session/MediaController.TransportControls).
