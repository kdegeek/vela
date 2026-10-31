# Plex server browsing contract

Implemented in the shared checkout, 2026-10-31. This is a bounded metadata client
and an optional Home source. It does not implement Plex cloud sign-in, direct
playback, transcoding, account/profile selection, or playback observation.

## Primary API evidence

Checked Plex's published PMS OpenAPI **1.2.3** on 2026-10-31:

- [Content types, headers, authentication and pagination](https://developer.plex.tv/pms/#section/API-Info):
  JSON requires `Accept: application/json`; authentication uses `X-Plex-Token`.
  Pagination uses `X-Plex-Container-Start` / `X-Plex-Container-Size`; clients must
  inspect returned offsets/totals rather than assume the requested page size.
- [Server configuration](https://developer.plex.tv/pms/#tag/General/operation/getSlash):
  authenticated `GET /` supplies `machineIdentifier`, `friendlyName`, and version.
- [Library sections](https://developer.plex.tv/pms/#tag/Library/operation/libraryGetSections):
  `GET /library/sections/all` supplies library IDs/titles/types.
- [Section items](https://developer.plex.tv/pms/#tag/Content/operation/librarySectionGetAll)
  and [media queries](https://developer.plex.tv/pms/#section/API-Info/Media-Queries):
  `GET /library/sections/{sectionId}/all` supports filtering/sorting;
  string `title=` means contains, and `sort=addedAt:desc` requests newest first.
- [Hubs](https://developer.plex.tv/pms/#tag/Hubs/operation/hubsGetSlash):
  `GET /hubs` discovers each hub's `key`, which addresses its full content.
- [Metadata](https://developer.plex.tv/pms/#tag/Content/operation/libraryMetadataGetSlash)
  and [artwork](https://developer.plex.tv/pms/#tag/Library/operation/libraryMetadataGetElement)
  document item and artwork reads.

The client requests PMS API **1.0.0** (documented for PMS 1.41.9+), using the
installed OkHttp 5.5.1, serialization 1.12.0, coroutines 1.11.0, Kotlin 2.4.21,
and JDK21/JVM17. No dependency changed. No real PMS endpoint was available to
establish version interoperability; MockWebServer tests validate the implemented
wire contract against those installed libraries.

No documented exact Android Plex item/playback link was established by this
investigation. Consequently no `plex://play`, guessed activity, playback endpoint,
or server/token URL is generated. The existing `app_search` tool uses Android's
[documented ACTION_SEARCH + SearchManager.QUERY convention](https://developer.android.com/develop/ui/views/search/search-dialog).
Whether a particular installed Plex version accepts it remains device acceptance.
No successful search dispatch is described as playback.

## Public API

Package `app.vela.tv.media.plex`:

```kotlin
PlexClient(address: String, token: String, clientId: String,
    http: OkHttpClient = privateDefault, beforeRead: () -> Unit = {})
suspend fun identity(): PlexServer
suspend fun checkConnection(): PlexConnection
suspend fun libraries(page: PlexPageRequest = PlexPageRequest()): PlexPage<PlexLibrary>
suspend fun browse(libraryId: String, page: PlexPageRequest = PlexPageRequest()): PlexPage<PlexItem>
suspend fun search(libraryId: String, query: String, page: PlexPageRequest = PlexPageRequest()): PlexPage<PlexItem>
suspend fun recent(libraryId: String, page: PlexPageRequest = PlexPageRequest()): PlexPage<PlexItem>
suspend fun resume(page: PlexPageRequest = PlexPageRequest()): PlexPage<PlexItem>
suspend fun onDeck(page: PlexPageRequest = PlexPageRequest()): PlexPage<PlexItem>
suspend fun item(id: String): PlexItem
suspend fun artwork(item: PlexItem, backdrop: Boolean = false): ByteArray?
```

`PlexPageRequest(offset = 0, limit = 20)` accepts offsets 0–1,000,000 and
limits 1–100. `PlexPage(items, offset, total, nextOffset, truncated)` exposes one
bounded page; callers deliberately request `nextOffset`. There is no automatic
whole-library traversal. A server that omits pagination metadata gets no invented
continuation; oversized unpaginated results set `truncated`. Conflicting header/body
offsets/totals, repeated item IDs and malformed sizes fail safely. Short paginated
pages advance by actual returned count. Totals can change with server library edits;
this is not a snapshot-isolated catalogue.

Search is a title-contains filter inside one library's default result type; it is
not fuzzy/global/cloud search. Resume/On Deck discover `home.continue` /
`home.ondeck` from `/hubs`, then request the returned key with pagination headers.
Only root-relative `/hubs/home/{name}` or `/hubs/sections/{numericId}/{name}`
keys, optionally with a numeric `type`, are accepted. Unsupported keys return a
safe error; absent hubs return an empty page. No `/library/onDeck` route is guessed.

`PlexItem` includes server/item/library identity, title/type, summary, year,
series/season/episode, raw content-rating label, duration and offset in milliseconds,
clamped progress and resume metadata. A completed offset resets the resume point.
Raw content ratings are not represented as trusted normalized age ratings.

Identity is cached per client after its first authenticated root read; metadata is
scoped to that machine ID. A response-supplied conflicting machine ID fails closed.
This is not persistent identity enrollment or a guarantee against a server changing
behind a proxy without reporting its identity.

`PlexException.code` is one of AUTH, REDIRECT, HTTP, NETWORK, INVALID_RESPONSE,
BODY_LIMIT, IDENTITY_CHANGED, UNSUPPORTED_HUB. Messages/cause chains omit raw
bodies, URLs and network/parser exceptions. Server text has exact saved-token
occurrences redacted before publishing. Client/settings string representations
omit credentials.

## Credential and transport boundary

- Address validation permits only explicit HTTP(S) without userinfo, query,
  fragment or control characters, at most 2048 characters. Reverse-proxy path
  prefixes are retained. IDs are numeric path segments, not server-returned URLs.
- Token and client identifier are validated before requests. Authentication is
  sent privately in `X-Plex-Token`; no URL, artwork URL, action or error receives it.
- All redirects, including same-origin and HTTP downgrade redirects, are rejected.
  Cookies, cache, authenticators and caller-supplied interceptors are disabled.
  TLS uses the installed client's supported trust configuration; no trust-all logic.
- No transparent transport retry. An installed-library regression reproduced
  OkHttp's GET replay on `503 Retry-After: 0`; a private network interceptor removes
  that replay opt-in before OkHttp processes the response. Errors are returned once.
- One request has 15-second total / 5-second connect / 10-second read deadlines.
  JSON is limited to 1 MiB (including chunked and decompressed bodies), depth 32,
  and 1000 returned entries before presentation slicing. Artwork is at most 512 KiB
  with JPEG/PNG/WebP content type and a strict `/library/metadata/{id}/thumb|art/{stamp}`
  path allowlist. Unknown/missing art remains absent.
- Reads, body consumption and parsing run off the caller's Main dispatcher.
  Coroutine cancellation cancels the actual OkHttp call during body reads.
  `beforeRead` runs before each HTTP request so owning UI can revalidate its
  settings/profile revision after intervening suspensions.

HTTP remains owner-configured LAN transport. This does not add HTTPS pairing,
credential encryption or server-certificate enrollment to the broader prototype.

## Settings, phone and Home integration

`VelaSettings.plexUrl`, `plexToken`, `plexConfigured` and
`plex(deviceId, beforeRead = {})` are additive and optional. Saved tokens are
masked in phone state, retained only on an unchanged trimmed server address, and
dropped when a masked edit changes that address. A blank edit clears the token.
A new explicit token can accompany a new server. Settings AtomicFile sync/read-back
and all existing profile/phone/external-control behavior are retained.

The paired phone Connections form has an optional labeled URL and password input.
It preserves masked and unsaved edits through polling, uses the existing paired
settings route, and explicitly describes search handoff. There is no cloud login
or scraping. `DeviceStatus.plexConfigured` reports configuration, not connectivity.

Supported validated layout source JSON:

```json
{"type":"plex_library","libraryId":"1"}
{"type":"plex_recent","libraryId":"1"}
{"type":"plex_search","libraryId":"1","query":"Arrival"}
{"type":"plex_resume"}
{"type":"plex_on_deck"}
```

`Layouts.SCHEMA_HELP` exposes these to the existing layout tools. Default household
rows are retained; an owner/agent adds Plex rows deliberately through the existing
layout editor. Home shows the first 20 entries. Empty results use existing row
empty states; failures stay in their row. Artwork has a shared three-second budget
for at most six cards per row; absent/failing artwork leaves readable titles.
Only authenticated image bytes reach Coil. No credential is appended to a URL;
there is no unauthenticated backdrop request.

Before every Plex row request, `ProfileExecution` pins the household revision and
`PlexBrowsePolicy` applies `ProfilePolicy.canPlay/canOpenApp/canUseAgent`. Missing
household storage fails closed. Because activation is opaque native-app search,
child/content-restricted/app-allowlisted profiles are conservatively denied even
when a Plex provider/library appears in an allowlist. Preferences grant no access.
Settings identity and household revision are rechecked before every HTTP read and
before publishing tiles. Restrictions therefore do not leak metadata by browsing
first and filtering afterward.

Activation is `TileAction.SearchApp("com.plexapp.android", title)` with visible
“Search in Plex” text. `TileCalls` maps it to the existing `app_search` tool and
normal Runner/ToolExecutor preparation, control ownership and profile checks.
No raw activity launch or second guard is added. Generic persisted `search_app`
actions validate package/query; saved tiles cannot bypass opaque-route denial.

## Coordinator integration and remaining acceptance

**Requested integration outside this worker's ownership:** mount
`settings.plex(deviceId)?.checkConnection()` in `PhoneServer.connections()` beside
the existing connection checks. Return only safe connectivity text (for example
machine name and checked library count); do not return the token or claim playback.
The coordinator mounted this check in the existing “Test saved connections” form. `PlexConnection` contains a server and one bounded library page.

Optional follow-through belongs to the coordinator: expose library IDs/selection
in a management UI, add Plex to the global Search destination, and give failed
`SearchApp` actions a dedicated retry button. Composable Home rows already work
through the published layout API. These changes require files outside ownership.

Worker evidence: isolated compilation of all owned Android adapters against SDK37
and the current assembled baseline; **39 JVM tests passed** (Plex HTTP/policy,
existing and extended settings/layout suites). The shipped phone script has
**35 passing tests**, retaining all original 32. The JVM suite covers header auth,
redaction, reverse-proxy paths, pagination/header conflicts, invalid IDs/deep JSON,
body bounds, off-caller body reads, cancellation, redirects, no-replay 503,
pre-read permission invalidation, restricted search denial, and secret masking.
The coordinator must run shared Gradle after all writers quiesce.

No device, browser visual check, real PMS/native Plex launch, playback, 4K or
hardware acceptance was performed. The prescribed device diagnostic retry remains
blocked as recorded in `acceptance-2026-10-31.md`; this worker used no device or ADB
workaround. No Git mutation, deployment, infrastructure change, credential request
or production dependency was introduced.

## Coordinator agent/phone integration

`PhoneServer.connections()` now checks Plex identity plus a bounded library read.
The model also has four bounded read-only tools: `plex_libraries`, `plex_browse`,
`plex_search`, and `plex_resume` (Continue Watching / On Deck). All accept explicit
`offset` and 1–20 `limit` paging. Library discovery returns IDs so an owner need not
find those IDs manually before composing Home sources or searching. The tools are
wrapped by TvTools' shared prepared settings/profile checks and PlexBrowsePolicy;
the client revalidates before every HTTP read. Search/browse metadata contains no
credential, artwork URL or playback-success claim. Native handoff remains the
existing guarded `app_search` route. HTTP regressions exercise discovery-to-search
and reject invalid page bounds without making a request.
