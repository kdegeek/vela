# Vela TV FIXTURE

**FIXTURE ONLY.** Dependency-free Python 3.10+ HTTP data for local app acceptance.
This is a small synthetic Jellyfin-shaped catalog and optional Home Assistant-shaped
state/action fixture. It is not either real service, a model API, a model-quality
evaluation, or evidence of real-server/hardware compatibility. There is no model
endpoint; agent acceptance still needs a separately configured authorized provider.

## Launch from the repository root

Generate original test-pattern video and a synthesized sine tone using the already
installed `ffmpeg`; no downloads, third-party media, tool installation or binary
assets in this checkout:

```sh
TV_FIXTURE_MEDIA="$(python3 dev/tv-fixtures/generate_media.py)"
python3 dev/tv-fixtures/fixture.py \
  --media "$TV_FIXTURE_MEDIA" --duration 60 \
  --host 127.0.0.1 --port 18096 --home-assistant
```

The generator prints the MP4's absolute path in a new temporary directory. Save
that path to reuse it; delete that directory after acceptance. `--output` accepts
an explicit `.mp4` path **outside this checkout** and refuses overwrites. Both
scripts accept `--duration 5..120`; keep the values equal (default 60 seconds).
The server does not inspect codec/duration metadata in arbitrary supplied files.
The generated sample uses H.264 baseline, 640×360 at 24 fps, stereo AAC, and MP4
fast-start metadata. Every playable fixture item uses this same original sample.

Server defaults are `127.0.0.1:8096`; the command above selects port `18096` to
avoid an existing local Jellyfin port. `--port 0` prints the selected free port.
Stop with Ctrl-C. No daemon or background process is installed. Use loopback and
the root-owned emulator's authorized local forwarding/tunnel for app access;
the worker does not configure devices or networking. `--host` can explicitly
select a different local IPv4 bind address when the coordinator needs it.

Apply these integration fields through the app's existing settings interface,
using the loopback URL **as seen by the app** after the coordinator's forwarding:

```json
{
  "jellyfinUrl": "http://127.0.0.1:18096",
  "jellyfinToken": "fixture-jellyfin-token",
  "jellyfinUserId": "fixture-user",
  "homeAssistantUrl": "http://127.0.0.1:18096",
  "homeAssistantToken": "fixture-home-token"
}
```

These are public, fixture-only credentials; do not supply real credentials.
Optional `POST /Users/AuthenticateByName` accepts `{"Username":"fixture","Pw":"fixture"}`
and returns the same fixed Jellyfin token/user. Jellyfin requests use the client's
`Authorization: MediaBrowser ... Token="fixture-jellyfin-token"` header. HA uses
`Authorization: Bearer fixture-home-token`. Only the legacy video stream route
also accepts the fixed `api_key` query parameter. Other API routes require headers.
Base URLs use the API root with no `/jellyfin` or `/api` suffix.

## Acceptance data and routes

Routes and field casing follow the current Android `media/Jellyfin.kt`,
`media/Playback.kt`, `media/JellyfinStream.kt` and `home/HomeAssistant.kt`.
Catalog titles, media metadata, subtitles and health/error responses identify FIXTURE;
all successful responses include `X-Vela-Fixture: true`.

| Scenario | Entry / route |
| --- | --- |
| Libraries | `GET /UserViews`: `fixture-movies` / `FIXTURE Movies`, `fixture-tv` / `FIXTURE TV` |
| Library browse | `GET /Items?parentId=fixture-movies&startIndex=0&limit=50` (omit parent for libraries) |
| Latest / resume | `GET /Items/Latest?parentId=fixture-movies`, `GET /UserItems/Resume` |
| Search / paging | `GET /Items?searchTerm=Episode&recursive=true&startIndex=100&limit=100` |
| Series | `fixture-series` / `FIXTURE The Pagination Observatory` |
| Seasons | `GET /Shows/fixture-series/Seasons`: `fixture-season-1`, `fixture-season-2` |
| Episodes | `GET /Shows/fixture-series/Episodes?seasonId=fixture-season-1&isMissing=false&startIndex=100&limit=100` |
| Next Up / next episode | `GET /Shows/NextUp?seriesId=fixture-series`; episodes also accept `startItemId` and `limit=2` |
| Normal playback | `fixture-movie`, episode IDs `fixture-s01e001` … `fixture-s02e105` |
| Missing art / long title | `fixture-no-art` (no art tags; image 404); `fixture-long-title` |
| Item errors | `fixture-item-error` appears in browse but item/playback returns 404; configurable `--item-error-status` |
| Missing episode | `fixture-missing` in season 2, filtered by `isMissing=false`; playback rejected |
| Artwork | `/Items/{id}/Images/Primary`, `/Items/{id}/Images/Backdrop/0`: original generated PNG |

There are **105 available episodes in each season (210 total)** plus one explicitly
missing episode. Pages return `Items`, `TotalRecordCount`, `StartIndex`, with a
default server cap of 100 even if the requested limit is larger. `--page-cap 37`
exercises short pages with a known total. Limits outside 1..500 and negative
offsets fail with 400; an offset at/past the total returns an empty page.
Latest/resume return the same Items envelope accepted by this client. When a layout
specifies a library by name, use `FIXTURE Movies` / `FIXTURE TV` exactly (case-insensitive).
Resume starts five seconds into the normal movie and first episode. Successful
user-data writes update item/resume responses in memory; restart resets all data.
Next Up is the fixed second episode and does not simulate a real recommendation policy.

`POST /Items/{id}/PlaybackInfo` returns a finite direct-play MP4 source, unique
`PlaySessionId`, audio stream index 1 and external English WebVTT index 2. The
subtitle `DeliveryUrl` is root-relative on this same server, with no API key.
The fixture rejects container/missing-item playback, incorrect source/session
associations, and requests without direct play enabled/transcoding disabled.
It keeps only the latest 128 negotiated sessions; it does not evaluate codec profiles.

`GET /Videos/{id}/stream?static=true&mediaSourceId=...&playSessionId=...` serves
only the single explicitly selected MP4. Full responses are 200. Single byte ranges,
open-ended ranges and suffix ranges return 206 with exact lengths/`Content-Range`.
Starts at/past EOF return 416 with `Content-Range: bytes */SIZE` (the app must
distinguish exact EOF from beyond EOF). Ends past EOF clamp to EOF; malformed or
multiple ranges return 400. HEAD returns full 200 metadata and no body, ignoring
Range because HTTP ranges apply to GET. No arbitrary filesystem or directory serving.

## Observe requests and inject failures

```sh
curl -fsS http://127.0.0.1:18096/__fixture/status
curl -fsS http://127.0.0.1:18096/__fixture/observations
```

These read-only fixture endpoints require no credentials and do not count their
own reads. Observations contain per-method/path counts and the most recent 200
events, plus `actionAttempts` / `appliedActions`. They omit authorization, raw URL
queries, raw request bodies, sign-in credentials and arbitrary unknown paths.
Validated reports include only typed playback fields and known item/source/session
IDs; user-data observations include positions, played status and normalized dates.
Nothing is written to an access log or persisted. Unknown requests share one
`<unknown>` bucket. Restart for a clean observation window.

Session posts are `/Sessions/Playing`, `/Sessions/Playing/Progress`, and
`/Sessions/Playing/Stopped`. They record requests but **do not** change resume data;
the separate `/UserItems/{id}/UserData?userId=fixture-user` POST does that. This lets
acceptance distinguish the session and checkpoint writes. Check counts and the
ordered event tail after a concrete UI action, allowing for periodic progress
and intentionally bounded idempotent UserData retries. A count is a received
HTTP request, not proof of a user gesture or of app success.

Repeatable options match an **exact method and root-relative path, excluding query**:

```sh
# Append these flags to the launch command, in separate acceptance runs as needed:
--fail POST:/Sessions/Playing:503
--fail POST:/Sessions/Playing/Stopped:503:1
--fail POST:/UserItems/fixture-movie/UserData:503:1
--fail POST:/api/services/light/toggle:503
--delay GET:/Items/fixture-movie:15
--delay POST:/Items/fixture-movie/PlaybackInfo:15
--range-mode ignore
--range-mode wrong-start
--range-mode truncate
```

`--fail METHOD:/path:STATUS[:COUNT]` fails indefinitely unless a positive count is
given; statuses are 400, 401, 403, 404, 500, 503. A 503 includes `Retry-After: 0`
so app acceptance can verify no automatic POST replay by inspecting request counts.
The self-test proves the fixture receives/records one request per explicit caller
attempt; observing the app is still required to prove the app's behavior.
Failures occur after authentication/body parsing and before effects, so rejected
HA actions are counted as attempts but never applied. `--delay ...:SECONDS` (0..30)
sends headers first, then delays the body for cancellation/deadline checks; other
requests and observation reads remain responsive. Fail/delay flags can be combined.
Range modes deliberately simulate ignored ranges, a wrong start offset, or a body
shorter than its declared length. Default `normal` always produces valid byte ranges.

With `--home-assistant`, `/api/states`, `/api/states/{entity_id}` and `/api/services`
provide two synthetic entities: `light.fixture_lamp` and `switch.fixture_fan`.
`POST /api/services/{light|switch|homeassistant}/{turn_on|turn_off|toggle}` accepts
`{"entity_id":"light.fixture_lamp"}` or a list of explicit known targets, returns
changed state objects, and increments `appliedActions` once per successful request.
No real device, wildcard targets or external service is contacted.

## Self-tests

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s dev/tv-fixtures -v
```

Tests start short-lived ephemeral loopback servers and stop them automatically.
They use deterministic temporary bytes to validate HTTP ranges, not to claim MP4
decoding. Coverage includes >100-episode/short-page traversal, known totals, next
episode ordering, missing art, source/session binding, same-origin VTT, resume
updates, HEAD/range boundaries, injected errors/truncation/delayed bodies, counted
actions, bounded observations, input limits, and credential/log suppression.
No app builds, device commands, model calls, real integrations or long-lived
server are part of this check.
