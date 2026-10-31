# Native TV Now Playing

Implementation handoff, 2026-10-31. Source implementation and isolated validation;
device and visual acceptance are pending. This adds a native shell surface for
Android MediaSessions without requiring a configured model.

## Entry points and appearance

`TvShell` mounts `NowPlayingSurface` outside destination state, below the current
page. An observed session produces a compact banner with bounded art/title,
source app, actual playback state, a supported play/pause control, and a Now
Playing details button. Paused, stopped, buffering, connecting and failed sessions
remain visible while Android reports them active. Multiple playback apps can be
selected in details. No permanent navigation destination is added.

Settings has a Now Playing button for discovery when nothing is playing and for
permission/error recovery. Details use a native Compose dialog with Back, bounded
text, scrollable content and wrapping controls. Existing shell destination state,
navigation focus restoration, management entry points and direct tile guards
remain in place. The shell Back handler yields while this dialog is open.

The surface uses the existing Vela semantic theme, shapes and focusable buttons.
Artwork is decorative and hidden in high contrast. Missing or rejected artwork
uses a music placeholder. Missing titles say “Title unavailable”; app labels fall
back to the explicit package. Nothing depends on an AI response to render.

## Observation, lifetime and bounds

`NowPlayingObserver` is read-only. It registers
`MediaSessionManager.OnActiveSessionsChangedListener` using Vela's existing
`MediaListener` notification-listener component and a Main handler. Every retained
controller has a callback for playback state, metadata, queue changes and session
destruction. Registration, state publication and disposal are Main-confined.

Observation runs only while the shell lifecycle is STARTED. Stop, composition
disposal and profile replacement unregister both listeners and callbacks, remove
the ticker and discard metadata/artwork. Already-queued callbacks cannot restore
discarded data. A household revision creates a fresh observer, preventing cached
metadata from a previous profile being displayed. State is rediscovered from
Android after activity/process recreation; media metadata is never persisted.

A one-second display tick projects only playing/fast-forwarding/rewinding
positions, using elapsed real time, the app's position timestamp and finite
playback speed. Paused/buffering/connecting/skipping/error states do not advance.
Unknown positions remain unknown. Missing/future timestamps, negative positions,
zero/NaN/infinite speed, reverse playback, duration limits and arithmetic overflow
are handled conservatively. Known duration bounds progress; unknown duration is
shown as unknown and disables seeking.

The system list is reread every five seconds to recover missed events and detect
notification-access revocation, on shell resume, on Retry, and immediately before
a command. Security failure clears all metadata and shows notification-access
recovery. Other session-read failures clear stale data and offer Retry. The
permission button opens Android notification settings; it grants nothing itself.

Bounds are 24 displayed sessions, 40 queue entries per session, 240 characters per
title/subtitle, 160 per queue title and 360 per app error. All system sessions
contribute to package-ambiguity detection, including those beyond the display cap.
Only embedded bitmaps at most 2048×2048 and 16 MiB are accepted, scaled to at most
512 pixels on either edge before display. Rejected source metadata is held only
weakly. Artwork decode/scale exceptions fall back to the placeholder. No artwork
URI, network request, custom session intent or custom action is executed.

## Profile boundary

`nowPlayingPolicyError` calls the actual `ProfileToolPolicy` for **both**
`media_status` and `media_control`. Unavailable, startup and malformed household
state fail closed. Child profiles, provider/library/rating restrictions, denying
unrated content, app allowlists (including an allowlist containing this app), and
disabled agent actions deny observation and controls. Unrelated home-control
restrictions do not deny media. This matches the current shared opaque-route
policy; personalization preferences never grant media access.

Denied profiles show no session banner or metadata. An explicit attempt to open
details explains the restriction. The live policy is also rechecked by the
observer and click handler; `Runner`/`TvTools` independently capture and validate
the profile and settings for actual execution. Manual control does not construct
a provider or bypass the shared policy.

## Commands and outcome semantics

Every playback effect goes through exactly this application seam:

```kotlin
app.runner.executeTool(
    ToolCall(freshId, "media_control", arguments),
    controller = "owner-now-playing",
)
```

The UI and observer never access `transportControls`. Runner owns task reservation,
the shared controller lease, prepared settings/profile validation and tool policy.
The UI also disables controls while Runner is running, **any** controller owns the
lease, or its own dispatch is in flight. Click-time checks close the gap before
reactive UI state arrives. There is no replacement task, model requirement or
automatic retry.

Controls derive from the app's actual PlaybackState action mask and observed
phase: play/pause (including combined play/pause support), stop, previous, next,
and seek. Seek offers Back 10s, Forward 10s and Start, only when a supported state,
position and positive duration are known. Relative buttons calculate an absolute
seek from elapsed-time-adjusted display position; arguments use the tool's integer
seconds schema, clamp to duration and Int range, and include an explicit package
for every command. Invalid, stale, unsupported, busy or ambiguous targets produce
no arguments and dispatch no command. The target/capabilities are reread before
dispatch.

Queue entries and the app's current-item marker are displayed, not treated as
launch instructions. Queue selection/reorder is not implemented: the installed
`media_control` schema has previous/next but no queue-item targeting. Unsupported
controls are disabled, with an explanation for missing seek metadata or ambiguity.

A non-error ToolOutcome is **not** interpreted as successful playback. Feedback
names the target app and says the request was sent and may not have taken effect.
Displayed playback state comes only from Android observation. Tool errors are
bounded and displayed. Exceptions report unknown effect and ask the user to check
playback before another deliberate action. No timed-out or failed command is
automatically replayed. Banner actions show the same feedback as details.

## Exact integration limits

The existing tool selects a controller by package rather than session token.
This surface therefore disables effects when multiple sessions belong to one
package and rejects vanished observed session IDs on its pre-dispatch refresh.
There remains a platform race if that package replaces its session between the
UI check and the tool's internal lookup; the tool cannot bind the observed token.
That backend contract requires a separately owned change before exact-token
guarantees can be claimed. The surface never falls back to another package.

Playback state, queue, actions, artwork and duration are only as complete and
truthful as the third-party app's MediaSession. Apps publishing no session cannot
appear. The five-second permission poll bounds normal detection latency but is
not an OS-wide privacy or parental-control boundary. There is no cross-app overlay,
firmware integration, queue editing or simulated playback success.

## Verification and remaining acceptance

The actual `control/NowPlaying*.kt`, `launcher/NowPlayingSurface.kt` and modified
`launcher/TvShell.kt` compiled in isolated temporary output with Kotlin 2.4.21,
the installed Compose compiler plugin, JDK 21/JVM 17, installed Android SDK 37,
installed dependency classes and the existing compiled application API jar.
No VelaApp, Runner, household or UI stubs were substituted. Shared Gradle output
was not written by this worker.

**20 JVM tests passed**, covering elapsed-time/speed/duration math, invalid and
extreme state, Android state/action mapping, combined play/pause support,
capability/busy/duplicate-package denial, exact safe package-targeted arguments,
seek clamps and overflow, unknown seek metadata, bounded text/time fallbacks,
dispatch feedback that does not claim playback, actual profile-policy denial,
storage unavailability and malformed-profile recovery.

Four acceptance items remain:

1. Coordinator-owned combined Android tests, lint and APK assembly after peer
   writers quiesce. The isolated compile does not replace those checks.
2. A working device-control route, then both themes, long titles/missing art,
   large fonts, D-pad traversal, dialog/banner focus restoration and empty/error
   recovery. No visual acceptance is claimed; the prescribed device retry was
   already exhausted before this assignment and no device command was run here.
3. Real MediaSession lifecycle/callback disposal, notification-access
   revoke/regrant, concurrent apps, same-package duplicate sessions, queue and
   unsupported capability behavior on a device.
4. Real playback effects and controller contention/profile-switch races,
   including third-party apps that ignore commands or report delayed state.
   JVM math/policy/argument tests do not establish those effects.

Official Android API references checked for this implementation:
[MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager),
[MediaController.Callback](https://developer.android.com/reference/android/media/session/MediaController.Callback),
and [PlaybackState](https://developer.android.com/reference/android/media/session/PlaybackState).
