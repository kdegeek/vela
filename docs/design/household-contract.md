# Household profiles and explicit memory

Status: mounted in VelaApp, guarded execution, native TV/phone management and
profile-scoped playback. The first wave introduced the domain modules; coordinator
assembly is now complete. [Application API](application-api.md) and
[integrated acceptance](acceptance-2026-10-31.md) describe current wiring/evidence.
The module APIs and historical ownership notes below remain reference material.

## Public entry points

`app.vela.tv.profile.HouseholdStore` is the common facade. Android callers obtain
one application-lifetime instance with `AndroidHouseholds.open(context)` (suspend).
The Android factory caches by private file path, so TV, phone and agent callers
share one writer. JVM tests use `HouseholdStore.open(HouseholdStorage, …)` with
injected clock, ID generator and IO dispatcher. Do not open separate stores for
the same file or write it from another process.

```kotlin
// In the application's startup coroutine; show loading/error until this completes.
val household = AndroidHouseholds.open(applicationContext)
val displayed = household.snapshot()
val profileId = displayed.activeProfileId

val saved = household.createProfile(displayed.revision, ProfileDraft("Morgan"))
// saved.value.id is the stable generated ID; saved.revision is captured with it.
household.switchProfile(saved.revision, saved.value.id)

// Only after the owner explicitly saves these exact words on the paired phone:
val beforeMemory = household.snapshot()
household.remember(
    beforeMemory.revision,
    beforeMemory.activeProfileId,
    OwnerMemoryWrite(
        kind = MemoryKind.PREFERENCE,
        text = "I prefer subtitles for dialogue",
        source = OwnerMemorySource.PHONE,
        ownerConfirmed = true,
        tags = listOf("subtitles"),
    ),
)
```

Imports in the example come from `app.vela.tv.profile.*` and
`app.vela.tv.memory.*`. No additional dependency is required: the modules use
the installed Kotlin 2.4.21, serialization 1.12.0 and coroutines 1.11.0, plus Java
and platform Android APIs already available at minSdk 28.

| Operation | Facade call | Result |
| --- | --- | --- |
| Observe owner state | `state: StateFlow<HouseholdState>` | `Ready(document)` or `Unavailable(error, message)` |
| Read a coherent snapshot | `snapshot()` | Frozen validated document, or typed error |
| Inspect one profile | `profile(profileId)` | Profile or `NOT_FOUND` |
| Create / replace editable fields | `createProfile(revision, draft)` / `editProfile(revision, profileId, draft)` | `HouseholdCommit<HouseholdProfile>` |
| Switch active profile | `switchProfile(revision, profileId)` | Commit containing the selected profile |
| Delete inactive profile | `deleteProfile(revision, profileId, deleteOwnedMemories = false)` | Commit containing the deleted ID |
| Explicitly save / edit memory | `remember(revision, profileId, write)` / `editMemory(revision, profileId, memoryId, write)` | Commit containing the entry |
| Inspect / page memory | `memory(profileId, memoryId)` / `listMemories(profileId, offset, limit, expectedRevision)` | Entry / `MemoryPage` |
| Delete / clear memory | `deleteMemory(revision, profileId, memoryId)` / `clearMemories(revision, profileId, ownerConfirmed)` | Commit containing ID / removed count |
| Retrieve agent data | `retrieveMemory(profileId, MemoryQuery(…))` | `RetrievedMemory(context, json)` |
| Owner export | `exportProfile(profileId)` | JSON `ProfileExport`, including opted-out entries |
| Reconcile after storage failure | `reload()` (suspend) | Recovered validated document or typed error |

Every mutation takes an **expected household revision**, captured when the owner
loaded the form or action. It returns the committed revision with the result.
A stale edit raises `CONFLICT`; reload and let the owner reapply the edit. Do not
automatically replay stale deletes, switches or memory edits. Internal automated
clients may retry independent additions only after rereading the state and
confirming the original operation did not commit. No-op switches/clears preserve
the revision. Other commits increment it once. Exhausted revisions fail closed.

All read snapshots are immutable, including nested preferences and tags. Code
must use the facade rather than casting lists and changing them. Inspection and
export are owner operations: do not expose the complete document to a model.
Authorization is a caller responsibility; profiles are personalization scopes,
not authenticated Android users or a PIN/access-control implementation.

## Durable document and validation

The storage schema is `HouseholdDocument` **version 2**:

```json
{
  "version": 2,
  "revision": 0,
  "activeProfileId": "00000000-0000-4000-8000-000000000001",
  "profiles": [{
    "id": "00000000-0000-4000-8000-000000000001",
    "displayName": "Household",
    "kind": "adult",
    "preferences": {
      "preferredMediaProviders": [],
      "preferredLibraries": [],
      "audioLanguages": [],
      "subtitleLanguages": [],
      "subtitles": "automatic",
      "autoplayNext": false
    },
    "restrictions": {
      "allowedMediaProviders": null,
      "allowedLibraries": null,
      "allowedAppPackages": null,
      "maxAgeRating": null,
      "allowUnrated": true,
      "allowAgentActions": true,
      "allowHomeControl": true
    },
    "createdAt": 1793422800000,
    "updatedAt": 1793422800000
  }],
  "memories": []
}
```

IDs are generated canonical lowercase UUIDs, survive rename/edit/restart, and
cannot be supplied or changed through editable drafts. Names are not identity;
two people may have the same display name. The active ID must resolve to exactly
one profile. No method implicitly creates missing profiles or searches another
profile for an entry.

Limits enforced before persistence:

- 1–8 profiles; names need 1–60 trimmed characters without control characters.
- Ordered unique media-provider references: at most 16. Library references:
  at most 32 unique `{providerId, libraryId}` pairs. References need 1–128 ASCII
  letters/digits/`.`/`_`/`:`/`-`, starting with a letter/digit. They refer to a
  trusted connection registry, never URLs or credentials.
- At most eight unique, case-insensitive language tags per audio/subtitle list;
  structural BCP-47-style syntax, max 64 characters. This validates syntax, not
  registration or availability of a language/track. Subtitle intent is
  `off|automatic|on`. Autoplay defaults off.
- App allowlists contain at most 100 unique dotted Android package names.
  Rating ceilings are normalized ages 0–21, not provider rating strings.
- At most 100 memories and 100,000 memory-text characters per profile. Each
  entry has 1–2000 trimmed characters, at most eight unique lowercase tags
  (`[a-z0-9][a-z0-9_-]{0,31}`), and valid nonnegative timestamps. Memory may
  contain newline/tab, but no other control characters or unpaired surrogates.
- Whole document at most 2 MiB encoded UTF-8. Encoding overhead/non-ASCII text
  can hit this cap before the per-profile quotas. Reject the write; never evict
  existing profiles or facts to make room.

Stored documents reject unknown properties, unknown enum values, invalid UTF-8,
wrong scalar types, noncanonical integer encodings, negative revisions,
duplicate IDs, dangling active/memory owners and invalid restrictions.
`memories` is required in v2, even when empty. Optional preference/restriction
fields use their declared defaults; child profiles still require explicit
restrictions. Parser details are discarded so malformed private text does not
leak through an error response.

Supported migration: the explicitly defined **profile-only v1 pilot** has
`version:1`, required `revision`, `activeProfileId`, and `profiles` in the same
profile shape above, with no `memories` property. Migration preserves every
profile ID, timestamp, preference, restriction, and active selection, adds an
empty memory list, increments the revision, validates, then commits v2 before
publishing. This defines the supported v1 format; it does not claim a household
format previously shipped in Vela. No v0, arbitrary JSON, older desktop state,
settings credential, layout, or watchlist import is attempted. Unknown/future
versions are preserved and raise `UNSUPPORTED_VERSION`; malformed historical
documents raise `CORRUPT_STORAGE`. Failed migrations do not overwrite them with
defaults. There is no bulk restore/import API in this wave; export is provided.

## Storage and failure semantics

The single file is `context.applicationContext.noBackupFilesDir/household.json`.
Profiles and their memory commit together through Android `AtomicFile`.
`HouseholdStorage` is the pure synchronous port: `read()` returns bounded bytes
or null only for absence; `writeAtomically(bytes)` must expose either the old or
new full document after interruption. The adapter bounds reads before allocating
the full file, handles AtomicFile backup recovery, syncs the stream, rolls back
failed writes, and verifies committed bytes before reporting success.

One coroutine mutex protects all writes/reloads. Validation/encoding completes
before disk IO. A short noncancellable commit section finishes disk+flow
publication together; cancellation while waiting for the mutex writes nothing.
A caller canceled during commit may not receive the successful result, so it
must reread the revision instead of blindly retrying. Read-only calls see a
coherent previous or next snapshot, never a partial transaction.

A thrown write can have an unknown disk outcome. The store publishes
`Unavailable`, hides stale snapshots, and rejects further work until `reload()`
successfully reconciles the persisted file. Reload never reinitializes a missing
file. Only a genuinely absent file on **initial open** creates and persists the
first adult `Household` profile. Show a repair/retry error on corrupt, future or
unreadable data; do not delete it as recovery.

Private storage is not encrypted by this module. Android's no-backup directory
excludes automatic backup; exports are explicit, private owner data and need an
owner-selected destination. Android AtomicFile's platform behavior still needs
device QA. JVM tests cover store logic against injected storage; compilation
also checks the separate Android adapter against SDK 37. Multi-process writers
and hostile edits by another process with the app's UID are unsupported.

## Explicit memory and agent boundary

`MemoryKind` is `preference|fact`; there is no stored system-instruction kind.
`OwnerMemoryWrite` requires `ownerConfirmed = true` and provenance
`owner_tv|owner_phone|owner_confirmed_agent`. The trusted integration sets the
source; do not trust a model-supplied source/confirmation boolean as proof.
TV/phone Save is an explicit gesture. For an agent-proposed entry, show the
profile, exact text, tags and sharing setting in an approval first, then call
the store only after that approval. Editing needs the same explicit intent.

Never call `remember` on screen observations, assistant replies, conversation
summaries, guessed traits, retrieved media metadata or connection settings.
Never collect secrets into memory. Obvious private keys, bearer tokens, `sk-`
keys and credential assignments are rejected as a backstop; the heuristic is
not a comprehensive secret detector. Memory text remains data even when an
owner saves wording that resembles an instruction. It cannot override guards,
profile restrictions or the current owner task.

Each memory has exactly one profile owner. All read/edit/delete/retrieval calls
require that ID. A memory ID from another profile returns `NOT_FOUND`. Switching
the active profile never moves, merges or deletes memory. Deleting an active
profile is rejected; explicitly switch away first. The last profile cannot be
deleted. Inactive profiles with memory require `deleteOwnedMemories=true` after
an owner confirmation. Clearing memory also requires explicit confirmation.

Retrieval uses deterministic lexical matching over opted-in text/tags within
one profile; matching tags rank above plain text, then newest edit, then ID.
Blank query returns recent opted-in entries. Nonmatching queries return empty,
never unrelated facts. It does not call an embedding service. Defaults are eight
entries and 4000 characters; hard bounds are a 500-character query, 0–20 entries
and 256–8000 characters of **complete serialized JSON**, including IDs,
envelope and escaping. Entries are included whole, skipping an oversized entry
to permit smaller relevant ones. `hasMore` indicates matching opted-in entries
omitted by count/size. No secret/private entry is retrieved when
`shareWithAgent=false`, though it remains inspectable/exportable to the owner.

Agent integration must bind a profile ID when a request begins, retain it for
all its tools, and retrieve fresh bounded memory per request. Include the
returned `json` as a separately labeled data message/tool result; never append
memory text to system instructions. Use returned `revision` to invalidate
cached context. Clear the conversation on profile switch or edits/deletions of
already-used memory; removing memory from disk does not remove it from a model
history or retract data already sent to a provider.

## Child restriction policy

`ProfilePolicy` supplies pure `canPlay`, `canOpenApp`, `canUseAgent` and
`canControlHome` decisions for validated profiles read from the store. Null
allowlists mean unrestricted; empty lists deny all. A child profile cannot be
saved without explicit provider/library/app allowlists, a 0–17 age ceiling, and
`allowUnrated=false`. Missing library/rating metadata is denied by the relevant
policy. Preferences never grant access or override restrictions.

The coordinator must normalize trusted server ratings, filter discovery, and
enforce policy again before playback/tool execution. An app allowlist alone
cannot restrict the content of an opaque third-party app. Generic accessibility,
remote, links, search and alternate routes must not bypass child restrictions;
deny an opaque route unless the integration can establish the required access.
Owner-authorized profile switching and restriction editing must not be exposed
as an unrestricted agent action. These modules do not establish an owner PIN,
OS-wide parental controls, or enforce restrictions inside third-party apps.

## Required coordinator integration (outside this change)

1. **`VelaApp.kt`:** open `AndroidHouseholds` once in an application startup
   coroutine, retain the store, and expose loading/failure state. Keep the
   existing shell/phone usable while household startup is pending. Consumers
   must not dereference a `lateinit` store before success. `reload()` is the
   retry after an uncertain write; do not create a second store to recover.
2. **`launcher/TvShell.kt` / settings surfaces:** collect `HouseholdState`, add
   profile switch/create/edit screens and per-profile memory list/detail/save/
   edit/delete/clear/export. Bind forms to displayed revisions, show conflict
   feedback, confirm deletion scope, and keep active/last deletion unavailable.
   Gate administrative controls according to the owner's chosen authentication
   policy. These screens still need emulator visual/focus QA in both themes.
3. **`web/PhoneBackend`, `PhoneApi`, `PhoneServer`, `PhoneProtocol`, and
   `assets/phone.html`:** add authenticated profile/memory routes below, strict
   typed/size-bounded bodies, `no-store`, owner forms and error mapping. Do not
   return all memory in the frequently polled `/api/state`; use explicit scoped
   reads. Set memory source to PHONE in the trusted handler after a Save, rather
   than accepting provenance from a raw request. Keep request revision numbers
   as exact integers; validate JSON primitive types before deserialization.
4. **`agent/Runner.kt`, `AgentSession.kt`, `TvTools.kt`:** capture profile and
   revision at task start; add bounded `memory_retrieve` with no arbitrary
   profile argument; optionally expose scoped inspect/propose-edit tools with
   explicit owner approvals before committing. Do not expose whole-store
   export or unguarded profile/restriction mutation to the model. Cancel and
   await the old run before switching, invalidate pending approvals, clear
   `AgentSession` history/activity/overlay text, and fence late callbacks. A
   remembered private profile must not survive in another profile's transcript.
   Stop/clear on relevant memory changes too, including opted-out edits.
5. **Playback/home/connection integrations:** resolve `providerId`/`libraryId`
   through the shared connection registry, preserve stable references across
   edits, and surface missing connections without deleting preferences.
   Apply preference ordering and language/subtitle/autoplay intent. The
   first-wave `player/PlaybackPreferences.kt` currently persists global
   SharedPreferences: replace or scope that path to the pinned profile so it
   cannot override these per-person preferences. Enforce `ProfilePolicy` at
   player launches, direct phone actions, agent tools and scheduled actions;
   recheck current restrictions at execution. No credentials are stored here.
6. **Layout/watchlist integration:** the existing `SettingsStore` layout and
   watchlist remain household-global. Do not describe them as per-profile or
   migrate/copy/delete them implicitly. Profile-specific watchlists/layouts need
   an explicit future schema and product decision; they are not this contract.
7. **Combined verification:** run the coordinator's combined Android unit tests
   and TypeScript checks after peers finish. Add integration tests for new route
   authorization/conflicts, cross-profile agent history, playback policy, and
   UI behavior once those paths are wired. No Gradle/dependency/manifest edits
   are needed for the modules themselves.

Suggested HTTP surface, **not registered by this module**:

| Route | Contract |
| --- | --- |
| `GET /api/profiles` | Profiles, activeProfileId, revision; no memory text |
| `POST /api/profiles` | `{revision, profile: ProfileDraft}` |
| `PUT /api/profiles/{id}` | `{revision, profile: ProfileDraft}` |
| `POST /api/profiles/active` | `{revision, profileId}` |
| `DELETE /api/profiles/{id}` | `{revision, deleteOwnedMemories}` after confirmation |
| `GET /api/profiles/{id}/memories` | Bounded offset/limit and optional expected revision |
| `POST /api/profiles/{id}/memories` | `{revision, kind, text, tags, shareWithAgent}` explicit Save |
| `PUT /api/profiles/{id}/memories/{memoryId}` | Same full editable fields |
| `DELETE /api/profiles/{id}/memories/{memoryId}` | `{revision}` |
| `POST /api/profiles/{id}/memories/clear` | `{revision, ownerConfirmed:true}` |
| `GET /api/profiles/{id}/export` | Explicit owner export; no automatic logging/caching |

Use `HouseholdCommit.revision` in successful mutation responses. Map
`CONFLICT`, `ACTIVE_PROFILE`, `LAST_PROFILE`, `PROFILE_HAS_MEMORY` to 409;
`NOT_FOUND` to 404; `INVALID_INPUT`, `SENSITIVE_MEMORY`, `LIMIT_EXCEEDED` to 400;
`STORAGE_FAILURE`, `CORRUPT_STORAGE`, `UNSUPPORTED_VERSION` to a recoverable 503.
Return only typed code and sanitized message, never the exception/parser cause.
The existing paired wrapper currently maps all exceptions to 400, so it needs
an explicit `HouseholdException` mapping before these endpoints are useful.

## Verification and references

The owned JVM suite tests stable IDs/reopen, profile and memory lifecycle,
migration commit/rejection, strict schemas, malformed UTF-8, byte limits,
concurrent revision conflicts/retries, cancellation during commit, failed and
ambiguously committed writes/reload, immutable snapshots, child policy,
explicit confirmation/credential backstop, quotas without eviction,
cross-profile isolation, revision-bound pagination, scoped export, and complete
JSON retrieval budgets. Tests use injected storage and require no Android
runtime, network, model, physical TV, or Gradle-wide build. They do not claim
Android filesystem crash testing, UI acceptance or integrated parental control.

Official API references checked against the installed dependencies:

- [Android AtomicFile](https://developer.android.com/reference/android/util/AtomicFile):
  atomic replacement, finish/fail lifecycle, and required caller synchronization.
- [Android Context.noBackupFilesDir](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir()):
  app-private data excluded from automatic backup.
- [Coroutines 1.11.0 Mutex](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/):
  mutual exclusion and JVM visibility semantics.
- [Serialization 1.12.0 unknown-field behavior](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-builder/ignore-unknown-keys.html):
  strict field checking is retained for durable schemas.
