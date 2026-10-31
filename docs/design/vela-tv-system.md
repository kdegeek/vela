# Vela TV system

Status: implementation in progress, 2026-10-31. This document expands the
[Android TV direction](../superpowers/specs/2026-10-30-vela-agentic-tv-design.md).
The current product is a sideloaded Android TV shell. A complete firmware product has additional release gates below.
Passing unit tests is not visual, real-model, hardware, or firmware acceptance.

## Product behavior

Vela turns a request into an observed result: find a title, play it, control a room, or rearrange home.
Remote navigation and the phone companion remain usable when AI is disconnected. Users can inspect activity,
stop a task immediately, fix a connection, and retry. Screens never depend on a model response to render.

The default home has installed apps, Continue Watching, recent Jellyfin additions, and My list.
A layout is data, not executable UI code. The agent may compose validated sources and actions.
Existing purchase, security-device, account, install and reset confirmation categories remain in force.

## Information architecture

| Destination | Primary action | Recovery / empty behavior |
| --- | --- | --- |
| Home | Explore configurable content rows | Pairing when AI is absent; source errors stay in their row |
| Search | Search installed apps and Jellyfin; hand off to the agent | No query, no results, missing library, connection error |
| Apps | Launch installed TV apps | Explicit empty state |
| My list | Open or remove saved titles | Explain where to save a title |
| House | Read Home Assistant state; toggle ordinary devices or ask Vela | Missing connection, loading, failed connection, unavailable entity |
| Activity | Inspect tasks, stop, ask again, clear | Explicit empty state; session-local history |
| Settings | Pair a phone, grant permissions, customize appearance, open Android settings | Configured and permission status; actual app surface dimensions |
| Ask sheet | Type or speak a task | Permission denial, network error, no speech match, typing and Back |
| Title sheet | Details, save/remove, resume, choose an episode | Loading, retry, series without episodes |
| Playback | Native Media3 player and media session | Unsupported container/title or playback failure returns control |
| Companion | Chat, remote, connections, layout, activity | Pairing, connection feedback, bounded requests, stale-prompt conflict |

## Visual language and tokens

Implementation: `android/app/src/main/java/app/vela/tv/design/VelaTheme.kt`.
The companion uses matching semantic colors in CSS and follows the phone's light/dark preference independently.

| Token | Night | Day | Purpose |
| --- | --- | --- | --- |
| background | `#0E1024` | `#F6F1E7` | Screen canvas |
| surface | `#1B2036` | `#FFFBF3` | Controls and panels |
| surfaceVariant | `#2C324A` | `#E6E2DB` | Selected / secondary controls |
| onBackground / onSurface | `#F6F1E7` | `#171B2C` | Primary text |
| onSurfaceVariant | `#C3C5D3` | `#434758` | Supporting text |
| primary | `#E5CA91` | `#74511C` | Focus and emphasis |
| error | `#FFB4AB` | `#A82428` | Error text paired with an explanation |

Theme switching changes the semantic palette; components do not carry separate copies of color decisions.
Background art is decorative. High contrast hides that art. Reduced motion makes focus scaling and backdrop changes instantaneous.
The always-dark accessibility overlay remains legible over third-party apps regardless of their theme.

| Scale | Value | Usage |
| --- | --- | --- |
| Display | serif, 36/40 sp | Home welcome / focused title |
| Heading | serif, 26/32 sp | Destination and sheet headings |
| Large title | 20/26 sp, semibold | Panels |
| Title | 17/22 sp, semibold | Rows and device names |
| Body | 16/23 sp | TV instructions |
| Supporting body | 14/20 sp | Metadata and secondary text |
| Label | 14 sp, semibold | Controls |
| Spacing | 8, 16, 24, 40 dp | Gaps, panels, horizontal safe area |
| Minimum target | 44 dp | Focusable controls |
| Radius | 12 dp cards, 20 dp panels, pill buttons | Consistent component silhouettes |
| Focus transition | 160 ms, 0 with reduced motion | Focus feedback |
| Backdrop transition | 600 ms, 0 with reduced motion | Focused title art |

Native dp sizing is resolution-independent; it does not force a 4K rendering surface on stock firmware.
A panel's output resolution and the surface granted to the app must be measured separately.

## Components and interaction contracts

- **Navigation control:** visible label, separate selected and focused states, 44 dp target, semantic button role.
  Focus adds both a border and fill change. Color alone is never the only focus signal.
- **Content card:** wide 224×126 dp, poster 112×168 dp, app 160×90 dp. Title fallback when artwork is absent.
  Focus scales within row padding and adds a visible border. Progress is clamped to 0–100%.
- **Hero:** title, metadata and summary for content; a useful welcome for apps or no selection.
- **Empty/error panel:** readable heading, reason, recovery action where a retry can resolve the issue.
  Source failure does not remove other sources.
- **Loading:** linear progress while data is pending. Navigation and Back remain available.
- **Pairing panel:** high-resolution QR, explanation and LAN address. No pairing token is sent in a URL query.
- **Title sheet:** load metadata before offering playback. Series offer episodes instead of trying to play a container.
  Save/remove is persisted immediately and reflected in My list.
- **Ask sheet:** text, speech when available, explicit errors, Back. Never traps users behind microphone access.
- **Approval:** question names the effect. OK allows once, Back declines. Remote presses must start after the prompt's
  grace period. Phone responses must carry the prompt ID that was displayed. Timeout is a decline.
- **Activity:** running / complete / failed / canceled state, request, timestamp and outcome. Stop is immediately visible.
  A replaced run cannot clear a newer run's state.
- **Phone forms:** visible labels, password inputs, masked saved secrets, inline feedback, 44 px controls,
  native scrolling, keyboard focus outline, responsive intrinsic grid, safe-area spacing.

Back dismisses a sheet first, returns a destination to Home second, then focuses Home navigation.
Android keeps ownership of Wi-Fi, Bluetooth and other system settings in the sideloaded build.
Deep focus restoration, TalkBack ordering, overscan, and oversized-font behavior still require device QA.

## Agent backend contract

`AgentSession` owns task state and conversation lifetime. `Runner` binds it to Android UI and the overlay.
`Agent` owns provider/tool exchanges. `TvTools` owns capabilities, direct integrations and guard classification.
`PhoneApi` owns HTTP authorization/validation; `PhoneServer` supplies the Android operations behind that boundary.

A request replaces the active request, cancels its job and approval, and waits for its coroutine to finish before
executing the new task. Generation checks protect state from stale callbacks. Follow-ups retain bounded context.
Activity is deliberately memory-only; provider credentials, appearance, layout and My list survive process restarts.

Execution limits:

- 8,000 characters per owner task; 40 model steps; 12 tool calls per model turn.
- 120 seconds per model turn, 30 seconds per tool check/execution, 15 minutes per task, 60 seconds per approval.
- Exact required/type/enum checks before a tool runs; fractional, overflowing and string-encoded integers rejected.
- Invalid/duplicate call IDs rejected before executing their batch. Unknown tools return an error to the model.
- Four identical executed batches trigger a stall stop before a fifth repetition.
- Declining an action skips the remaining batch and ends the task; no alternate route is attempted.
- Tool results are bounded to 24,000 characters. Context targets 100,000 characters and eight owner turns,
  evicting complete assistant/tool exchanges. This is a character budget, not token metering.
- Model HTTP bodies are bounded to 4 MiB. Redirects never receive the owner's provider headers.
- At most three model HTTP attempts on explicit 429/502/503/504; Retry-After over ten seconds is returned to the user.
  Transport failures and device/home actions are not automatically replayed.
- A timeout has an unknown effect. The system prompt requires observation before another action and forbids treating
  on-screen text as instructions. Heuristic guards and a prompt are not a proof against prompt injection.

Tools cover screen read/press/focus/type/scroll, D-pad, waiting, app discovery/open/search, links, media sessions,
Home Assistant state/services, Jellyfin search/resume/latest/play, device capabilities, watchlist read, layout read/write.
Multiple select presses collapse to one guard-checked press. An explicit media target never falls back to another app.

## Storage and LAN boundary

Settings/layout/watchlist writes use Android AtomicFile. Settings/layout are validated before publishing their flows.
Credentials stay in private app files. Automatic backup and device-transfer rules exclude those files.
Changing a server address drops a masked saved credential rather than forwarding it to the new host.
Private storage is not a hardware-backed encrypted credential vault; that remains release work.

The companion listens on LAN port 8765. Every API route checks `X-Vela-Token` before executing.
Responses carrying state are `no-store`. Public HTML contains no device token, rejects framing and sets a CSP.
Bodies are bounded to 256 KiB including chunked bodies, with a ten-second read timeout.
Parser errors never echo submitted credentials. The HTML renders model/user text with `textContent`.
The phone remote is disabled during agent work to prevent competing controllers.

HTTP on a trusted LAN remains the prototype transport. HTTPS pairing, revocation and per-phone credentials are release work.

## Release inventory

There are **16 open release workstreams** below. None is marked complete by this implementation.
This is a concrete inventory, not a claim that every possible defect has been enumerated.

Eight can advance from repository context once implementation capacity is available:

1. Plex/native streaming adapters: authenticated libraries, artwork, deep-link mappings, provider availability and failure recovery.
2. Full Jellyfin media experience: pagination beyond the current 100-episode bound, seasons, up-next, tracks/subtitle preferences,
   transcoding negotiation and server/device codec compatibility.
3. A persistent TV Now Playing surface for third-party sessions, with seek/queue/capability-aware controls.
4. Household profiles, per-person provider/library preferences, child restrictions and migration between schemas.
5. Durable explicit user memory with inspect/edit/delete/export behavior and bounded retrieval into agent context.
6. Durable routines/schedules with cancel/edit/run history, restart recovery and guard checks at execution time.
7. An authenticated MCP surface and provider-supported subscription integrations; do not scrape or reuse subscription credentials.
8. Automated stress/property suites for layout, malformed providers, focus models, quotas, concurrency, credential redaction and migration.

Eight require external input, device access, operational choices or measured evidence:

9. Visual acceptance in both themes: all destinations, dialogs, long titles, fonts, missing art, loading/errors and D-pad traversal.
   **Needs:** working device tooling; emulator first, no owner's TV without separate authorization.
10. Real-model acceptance and cost/latency evaluation on the chosen Sol endpoint, Anthropic and a local model.
    **Needs:** secure credential delivery and the requested delegation route.
11. Stock Google TV / Shield / older Android compatibility, Home interception, opaque app surfaces, mic and remote behavior.
    **Needs:** explicit device authorization, exact model and firmware; previous TV restrictions remain.
12. AOSP firmware, native 4K surface, privileged control, signed OTA, rollback, recovery and factory reset.
    **Needs:** selected hardware/vendor BSP and a safe flashing/recovery plan before destructive device work.
13. Hardware OS integration: first boot, network/Bluetooth onboarding, CEC/power/volume, sleep/ambient, storage and thermal behavior.
    **Needs:** target hardware and measured system capabilities; stock Android Settings is the current fallback.
14. Hosted gateway operations: provider contracts, metering, quotas, billing, secret handling, deployment, monitoring and support.
    **Needs:** an approved service topology and commercial/provider decisions. No existing infrastructure has been modified.
15. Independent security validation: prompt injection in real apps, stale targets, LAN isolation, HTTPS pairing, revocation,
    hardware-backed secrets and signed update trust. **Needs:** a review and testing environment plus device evidence.
16. Release qualification: 4K frame-time/memory budgets, long playback/agent soak, accessibility audit, hardware/streaming
    distribution and certification requirements. **Needs:** final hardware, representative media and release channels.

## Evidence and reference

Android unit/HTTP tests exercise malformed calls, cancellations, stale approvals, bounded execution, retries, redirects,
settings/layout validation and authorization on every companion route. They do not establish real-model quality or TV focus behavior.
`pnpm typecheck` and existing workspace tests cover the earlier desktop implementation; they are not Android UI acceptance.

Relevant official guidance used for implementation:

- [Android Compose focus restoration](https://developer.android.com/develop/ui/compose/touch-input/focus/focus-restoration)
- [Media3 playback](https://developer.android.com/media/implement/playback-app)
- [Media3 track selection](https://developer.android.com/media/media3/exoplayer/track-selection)
- [Android backup and transfer configuration](https://developer.android.com/identity/data/autobackup)
- [Ktor request handling](https://ktor.io/docs/server-requests.html)
- [Ktor test applications](https://ktor.io/docs/server-testing.html)
- [Jellyfin official SDK show API](https://typescript-sdk.jellyfin.org/functions/generated-client.ShowApiFp.html)
