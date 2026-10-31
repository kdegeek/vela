# Jellyfin media and playback contract

Implemented in `media/` and `player/`, 2026-10-31. This is a negotiated direct-play foundation, not a transcoding,
DRM, hardware compatibility, or visual acceptance claim. No Jellyfin server, physical TV, Shield UI, or deployment
was used for this implementation. Combined Gradle/lint/build checks belong to the coordinator.

## Public API and coordinator integration

Existing `JellyfinClient` entry points and `PlayerActivity.intent(context, itemId)` remain source-compatible.
New `JellyfinItem` constructor fields are appended with defaults: `seriesId`, `seasonId`, `seasonNumber`,
`episodeNumber`, `played`, `missing`. `playable` accepts available Movie/Episode/Video items only.
`resumePositionMs` preserves valid positive positions, including rewatches and unknown runtime. Negative positions
and positions at/past a known runtime restart at zero. Tick conversions saturate instead of overflowing.

The existing `episodes(seriesId)` now fetches all available episodes in cancellable 100-item pages rather than
silently truncating at 100. An explicitly supplied `limit` remains an overall cap. Prefer pages for UI:

```kotlin
val seasons = jellyfin.seasons(seriesId)
val page = jellyfin.episodesPage(seriesId, startIndex = 0, limit = 50, seasonId = selectedSeasonId)
// Append a subsequent page only to the same series/season. De-duplicate IDs across live library mutations.
page.nextStartIndex?.let { next -> jellyfin.episodesPage(seriesId, next, 50, selectedSeasonId) }
```

`JellyfinPage` contains `items`, `startIndex`, nullable `totalRecordCount`, and nullable `nextStartIndex`.
A known total takes precedence over page length: servers may cap responses below the requested page size.
Empty pages terminate even if a stale total claims more records. Whole-series enumeration rejects a repeated
page rather than looping. Page limits are 1–500; offsets must be nonnegative. Listings are not server snapshots.

Other new APIs:

- `libraries()`: user views.
- `browse(parentId = null, startIndex = 0, limit = 50)`: immediate children ordered by SortName, ascending.
- `searchPage(query, startIndex = 0, limit = 50)`: recursive Movie/Series/Episode search.
- `nextUp(seriesId = null, startIndex = 0, limit = 20)`: Jellyfin's resumable Next Up choices.
- `nextEpisode(episode)`: the immediate next available episode in server order; returns null for movies, missing
  series metadata, or the last episode. The player offers Play next after natural completion; it does not autoplay.

### Agent playback readiness

Replace any unconditional “Playing” result in coordinator-owned `TvTools.jellyfin_play` with the request API:

```kotlin
val request = PlaybackRequests.create(itemId)
try {
    app.startActivity(PlayerActivity.intent(app, request).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val outcome = request.awaitStarted(timeoutMillis = 20_000)
    // PLAYING alone is a successful playing claim. FAILED carries a safe user-facing message.
    // CANCELLED, STOPPED and TIMED_OUT are not success. Report their actual status.
} catch (e: CancellationException) {
    request.cancel()
    throw e
} catch (e: Exception) {
    request.cancel() // startActivity itself may fail before the activity can publish a result.
    // Return a tool error; do not echo raw launch/URI/credential exceptions.
}
```

Import `app.vela.tv.player.PlaybackRequests`, `PlaybackPhase`, and `PlayerActivity`.
`PlaybackRequest` exposes `id`, `itemId`, `state: StateFlow<PlaybackOutcome>`, `cancel()`, and `awaitStarted()`.
`PlaybackOutcome` contains `requestId`, `itemId`, `phase`, nullable `message`.
Phases: PREPARING, READY, PLAYING, FAILED, CANCELLED, STOPPED, TIMED_OUT.
READY means decoded media is prepared; PLAYING requires Media3's observed `isPlaying`.
An await timeout cancels the pending request and the activity observes that cancellation, preventing a late launch.
Cancelling the awaiting coroutine also cancels the request and rethrows coroutine cancellation.
The first start/failure result is retained independently of later state changes. Requests for the same item have
separate IDs. The process-local registry holds at most 64 handles and never evicts a still-pending handle.
This is process-local observation, not a durable command queue.

No coordinator-owned files were edited. TitleDialog's season/page integration is compatible with these APIs.
No manifest or catalog changes are required. Production code now imports the coordinator's existing internal
`app.vela.tv.network.SingleAttemptBody`; retain that file when integrating this work.

## Negotiation, streams and credentials

`playback(item, capabilities, positionMs = item.resumePositionMs)` posts to `/Items/{id}/PlaybackInfo` with the
configured user, resume time, direct-play device profile and `AutoOpenLiveStream=false`. Device capabilities come
from installed Android decoder MIME types. Only progressive Media3 containers and those decoder codec names are
advertised. No transcoding profiles are supplied; direct streaming/remuxing and transcoding are explicitly disabled.
A finite source with `SupportsDirectPlay=true`, a source ID and no opening requirement is required.

The selected source ID and returned PlaySessionId bind the stream and reports. The player uses authenticated
`/Videos/{id}/stream?static=true&mediaSourceId=...&playSessionId=...`; it never loads the server's filesystem Path.
The old `streamUrl()` remains for source compatibility and contains an API key; the player does not use it.
Do not log that legacy URL or use it for a new UI. Negotiated URLs contain no Vela API key.

API and media clients refuse redirects and disable connection-failure replay. POST request bodies are one-shot,
which also prevents OkHttp's transparent `503 Retry-After: 0` follow-up. Session actions are never automatically
replayed. API bodies are bounded to 8 MiB, read and closed on OkHttp's worker dispatcher. Coroutine cancellation
cancels the call through response-body consumption, including delayed bodies.

Media requests and subtitle requests use the same-origin, same-base-path HTTP stack and Authorization header.
API-root-relative Jellyfin subtitle URLs retain a configured server path such as `/jellyfin`. Cross-origin URLs,
URLs escaping that prefix, user-info URLs and redirects are rejected; old `api_key` parameters are removed.
This intentionally does not implement authenticated CDN redirects. Configure the final server address.

Progressive HTTP seeking validates 206 byte offsets, safely skips when a server ignores Range, accepts 416 only
at the exact stream end, and fails truncated explicit ranges instead of treating them as successful EOF.
Read timeouts are 20 seconds for media; API calls have a ten-second total deadline.

## Resume, reporting and lifecycle

Playback starts only for Movie/Episode/Video items. Series, Seasons and missing episodes fail with an explanation;
the title sheet must offer available episodes. Failed preparation never sends a stop or clears a prior resume point.

The first READY event queues a start report. Progress observes pause/audio-focus suppression separately from
buffering and uses actual seekability. Reports are serialized across activity recreation. Pending periodic
checkpoints coalesce to the latest observation, preserving start/stop ordering and preventing offline backlog growth.
Stop is emitted once per prepared player. Natural end is the only client event that explicitly clears position and
sets Played=true. Leaving near 90%, playing briefly below 5%, and playback failure do not trigger guessed watched
thresholds. Seeking backwards is preserved.

API-key sessions have no authenticated user. In addition to session reports, progress and stop write the configured
user's exact confirmed `PlaybackPositionTicks` through `/UserItems/{id}/UserData?userId=...`. A failed session
report does not suppress this independent write, except when authentication was rejected. Partial user-data updates
also set the observed LastPlayedDate so API-key playback updates Continue Watching order, without replacing
favorite, rating or play-count fields. Authenticated user sessions can still apply the server's own policy;
the configured user and token identity must be validated on the actual server.

Failed UserData writes retain the latest checkpoint per server/user/item in process memory. Only those idempotent
writes retry, after 1, 5 and 15 seconds. A rejected credential is removed from retry state. After that bounded window,
a later reporting event can schedule another attempt. A newer checkpoint supersedes older ones, even across recreated
activities/clients; retries and foreground writes serialize so an older request cannot finish after a newer write.
An outage followed by process death can lose pending checkpoints: there is no durable offline sync or conflict
resolution with another client watching the same item. Successful progress checkpoints normally limit process-death
loss to approximately one reporting interval (ten seconds, plus network/reporting latency).

Player allocation follows onStart/onStop. Stop cancels preparation and periodic reporting, saves position/pause intent,
releases media session/decoder/audio focus and removes keep-screen-on. Returning restores playback without finishing
the activity. Saved state retains item, position, pause intent and a completed item's Up next surface. Media3 handles
focus and noisy-audio events. Errors show native Retry/Back controls; Retry renegotiates from the confirmed position.

Launch Intents use CLEAR_TOP|SINGLE_TOP and onNewIntent releases the previous item before preparing another.
Each activity also gives MediaSession a unique ID, avoiding collisions if two activities overlap before onStop.
Listener callbacks are bound to their exact player instance. A stale player's callback cannot claim readiness for
its successor. No foreground-service/background-playback capability is claimed or required.

## Native tracks and preferences

PlayerView provides audio settings, CC selection, seek/play/pause and a buffering indicator. External or extracted
Jellyfin subtitles are attached as MediaItem subtitle configurations when DeliveryUrl is available and the codec is
SRT/SubRip, VTT/WebVTT, ASS/SSA. Unsupported image subtitles and external-host subtitle URLs are not attached.
Embedded tracks remain Media3's native tracks; renderer/codec support still decides whether they can play.

The server's default audio language and external subtitle index are used initially. Explicit audio/subtitle language
choices and the native CC None intent persist in private `vela-playback` preferences. None preserves forced-subtitle
behavior, matching installed Media3 1.11.1. Source-specific group IDs are not persisted across titles. The native
Audio Auto action clears a prior saved language choice for future titles. Coordinator integration now scopes native overrides by stable household profile ID, applies profile audio/subtitle intent, and invalidates overrides when those preferences change. Playback is stopped when its bound profile changes. The management UI is being wired separately; combined Android and visual verification are still pending.

Only external subtitle IDs have verified Jellyfin stream indexes. Reports omit unverified audio/embedded-subtitle
indexes instead of inventing a correspondence between Media3 group IDs and Jellyfin indexes.

## Dependencies, validation and remaining gates

No new production or test artifacts are needed. Existing Media3 1.11.1 (exoplayer/ui/session), OkHttp 5.5.1,
kotlinx-coroutines 1.11.0, serialization 1.12.0, JUnit 4.13.2 and mockwebserver3 5.5.1 suffice. In particular, this
implementation does not require `media3-exoplayer-hls`, FFmpeg or a native codec extension.

Isolated verification compiled owned media/player sources against cached Android/Media3 libraries and baseline app
classes with Kotlin 2.4.21/JDK 21, outside Gradle's shared output directories. The HTTP/state/request suites pass
56 tests: pagination beyond 100, capped/empty/repeated pages, negotiation and source/session association, subtitle
origin/base paths, Range correctness, early EOF, readiness/cancellation/item association, exactly-once stop,
coalescing/order, retained resume retries, delayed-body responsiveness/cancellation, and one-shot 503 POST behavior.
That check also compiled the coordinator-owned SingleAttemptBody as an external integration dependency.

Remaining acceptance gates:

1. Coordinator: combined Gradle tests/lint/assemble and application integration, including the TvTools result mapping above.
2. Emulator visual/interaction QA in light and dark: PlayerView D-pad controls, track preference persistence, native dialogs,
   rapid consecutive launches, audio interruptions, Back/background/return and process recreation. No visual acceptance is claimed.
3. Real Jellyfin: API-key/user-token permissions, configured user matching, user-data persistence, alternate versions,
   base-path reverse proxy, large/mutating libraries, extracted subtitles and Next Up. No live server was assumed or contacted.
4. Authorized target hardware/media: actual codec profiles/levels/bit depth/HDR, passthrough and audio channel support,
   subtitle rendering, seek accuracy and long-playback/network recovery. Shield ABI/API observations do not prove playback;
   no physical-device UI fallback was attempted. See `shield-compatibility.md`.
5. Separate future media capabilities: HLS/remux/transcoding negotiation and cleanup, DRM, live TV/opening/closing live
   sources, version chooser, trusted rating/library normalization for restricted-profile playback and durable offline progress conflict resolution. Their support
   is not implied by DirectPlay negotiation or passing JVM tests.

## Official contracts checked

- [Jellyfin show API: episodes, seasons, Next Up](https://typescript-sdk.jellyfin.org/functions/generated-client.ShowApiFp.html)
- [Jellyfin PlaybackInfo DTO](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Models/MediaInfoDtos/PlaybackInfoDto.cs)
- [Jellyfin playback negotiation and subtitle delivery](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Helpers/MediaInfoHelper.cs)
- [Jellyfin session reporting](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/PlaystateController.cs)
- [Jellyfin partial user-data update](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/ItemsController.cs)
- [Jellyfin user-data merge behavior](https://github.com/jellyfin/jellyfin/blob/master/Emby.Server.Implementations/Library/UserDataManager.cs)
- [Media3 track selection](https://developer.android.com/media/media3/exoplayer/track-selection)
- [Media3 progressive formats and device decoder dependency](https://developer.android.com/media/media3/exoplayer/supported-formats)
- [Installed-version native CC/Audio behavior, Media3 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/ui/src/main/java/androidx/media3/ui/PlayerControlView.java)


Coordinator integration evidence: 4 pure profile-playback regressions pass for per-profile storage names,
explicit language/subtitle/autoplay intent and fail-closed unknown restricted metadata. Native preference and
Activity wiring require the combined build and emulator pass. The legacy global playback preference file
is preserved; no implicit cross-person migration is performed.
