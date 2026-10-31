# External control contract

Current status: explicitly enabled loopback listener, dedicated credential lifecycle,
shared approvals, prepared ToolExecutor execution and Main-confined session admission
are mounted. [Application API](application-api.md), [core execution](core-execution-contract.md)
and [SSE correction](mcp-stream-correction.md) describe the assembled implementation.
The historical coordinator checklist below records requirements; newer prepared-target
and admission contracts supersede its original check/run examples. Device acceptance
limits are recorded in [integrated acceptance](acceptance-2026-10-31.md).

## Protocol and transport

The endpoint is a dual-era MCP server on the installed Ktor **3.6.1** runtime. It does not construct a
Provider or require `agentReady`, a model name or an internal model credential. An authorized desktop
model discovers Vela's existing `ToolSpec`s and invokes the corresponding guarded `Tool`s directly.

The current official revision is **2026-07-28**, which removed the initialize handshake and protocol
sessions. It is incorrect to label an initialize-only implementation as that revision. This implementation
also supports **2025-11-25** for desktop clients that use initialization:

| Behavior | 2026-07-28 | 2025-11-25 compatibility |
| --- | --- | --- |
| Establish capabilities | `server/discover`; also accepts requests without discovery | `initialize` returns version, capabilities, identity and a random session ID; then `notifications/initialized` |
| Request metadata | Required `params._meta` protocolVersion and clientCapabilities on every request | Negotiated state bound to the authenticated credential and session ID |
| HTTP headers | `MCP-Protocol-Version`, `Mcp-Method`, and `Mcp-Name` for tool calls must match the body | Session ID required after initialization; protocol header, when present, must match the negotiated version |
| Tool execution response | Request-scoped SSE, final JSON-RPC message, then EOF | Single JSON response |
| Cancellation | Closing the SSE response cancels its execution coroutine | `notifications/cancelled` scoped to that credential/session/request; notification and canceled POST return 202 with no JSON-RPC response |
| Session disposal | None | Authenticated DELETE; five-minute idle expiration |
| GET | Authenticated 405; no standalone stream | Authenticated 405; optional standalone stream is unsupported |

Both eras implement `ping`, `tools/list` and `tools/call`. Only `tools` is advertised; `listChanged` is
false. The bounded static catalog fits in one page. A supplied continuation cursor is an invalid parameter.
No resources, prompts, sampling, task extensions, subscriptions or remote approval-granting tools are advertised.
Modern `server/discover` includes both supported versions. Results include `resultType: complete` and server
identity metadata only for the modern era. Unsupported modern methods return HTTP 404 / JSON-RPC -32601.
Unsupported modern versions return HTTP 400 / -32022 with `data.supported` and `data.requested`.
Header mismatches use -32020; malformed envelopes -32600; invalid parameters/unknown tools -32602;
parse failures -32700; internal protocol failures -32603. Application busy/rate-limit errors use **-31000**,
outside the reserved MCP/JSON-RPC server-error ranges. Execution failures/denials/timeouts are tool results
with `isError: true`, not falsely successful results or protocol errors. Error messages never echo parser
exceptions, request bodies, raw tool exceptions or credentials.

Modern request example (all HTTP requests also carry Authorization, Content-Type and Accept):

```http
POST /mcp
Authorization: Bearer <locally provisioned token>
Content-Type: application/json
Accept: application/json, text/event-stream
MCP-Protocol-Version: 2026-07-28
Mcp-Method: tools/call
Mcp-Name: screen_read

{"jsonrpc":"2.0","id":"read-1","method":"tools/call","params":{"name":"screen_read","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}
```

The legacy `initialize` request supplies `protocolVersion`, `capabilities` and `clientInfo` (name/version).
If its offered version is unsupported, the result offers 2025-11-25, per legacy version negotiation. Subsequent
requests carry the returned `MCP-Session-Id` and should carry `MCP-Protocol-Version: 2025-11-25`.
Missing legacy protocol headers are inferred from the authenticated session, as allowed by that revision.
The session ID never substitutes for Authorization. Modern requests never infer identity/capabilities from
a legacy session or from untrusted clientInfo.

## Local authorization and limits

`McpCredential.generateToken()` generates 32 random bytes, URL-safe base64 encoded. `McpCredential`
retains only SHA-256(token), compares digests in constant time, and redacts its `toString`. Credentials are
injected via `McpBearerAuthorization { currentCredentialSnapshot }`, so an empty list denies access and
removing a credential rejects its next request. Each credential has a local controller name and optional
tool allowlist. Discovery filters that list, and calls independently enforce it. Give independent desktops
independent credentials; their self-reported names confer no privileges.

No token is created, persisted, displayed, exported or delivered automatically by mounting. The coordinator
must provision it through an approved local settings/pairing flow, persist it in private app storage excluded
from backup/transfer, and deliver it only to the approved desktop's secret store. Do not reuse the phone
pairing token, put tokens in URLs, expose them in state JSON, or log Authorization. This is an explicitly
configured local bearer scheme; it does not implement OAuth authorization-server/resource-metadata discovery.
Desktop clients must support custom Authorization headers. Provider access tokens are not MCP credentials.

Default HTTP policy requires a loopback peer and an exact loopback Host allowlist. A malformed Host,
userinfo/path/fragment in Host, duplicate security/protocol headers, or a present Origin outside the exact
allowlist is rejected. Origin `null` is rejected. CORS is not enabled. Every verb, including GET/DELETE/OPTIONS,
requires authorization. Responses are `no-store`; no public discovery/HTML/token route is added.
The implementation ignores forwarded headers for trust decisions. If direct network access is explicitly
approved later, `allowTlsNetwork = true` additionally requires real HTTPS at this Ktor socket and an explicit
Host allowlist. It is not a permission to expose the existing cleartext phone listener or a TLS-terminating
proxy configured with guessed forwarded-header trust.

| Bound | Value/default |
| --- | --- |
| Actual HTTP body including chunked bodies | 256 KiB; Content-Length also checked |
| Body read | 10 seconds, at most 16 readers |
| Body encoding | Strict UTF-8, JSON only; compressed input rejected |
| JSON nesting | 32 containers, checked before recursive parsing |
| Duplicate JSON keys | Rejected, including escaped-equivalent keys |
| JSON-RPC message shape | One request/notification object; no batches, responses or id-null requests |
| Request ID | 1–128 character string, or integer in JS-safe range; type preserved |
| Static catalog | 128 unique tools, 200,000 total metadata characters |
| Legacy sessions | 16, five-minute idle expiry; expiry checked on subsequent requests |
| In-flight tool requests | 8 across both eras; duplicate active IDs rejected per credential/session |
| Request rate | 240/minute/credential, bounded 64-entry limiter |
| End-to-end tool request | 150 seconds |
| Guard check / execution / owner approval | 30 / 30 / 60 seconds |
| Tool text returned | 24,000 characters |

Tool arguments use Vela's existing `ToolArguments` checks and are checked again inside the execution
adapter. Vela's emitted schemas use the existing object/properties/required/type/enum subset of JSON Schema;
this module does not claim to be an arbitrary JSON Schema validator. Additional integrations must provide
matching validation in their guarded backend. There is no schema/network `$ref` fetch. This adapter does not
advertise parameter-to-header annotations (`x-mcp-header`) and rejects catalogs containing them. Standard
`Mcp-Name` sentinel base64 encoding is decoded before comparison.

No automatic action retries, queue, replay cache or exactly-once promise is added. An ID may be reused
after completion, so clients must not replay actions after losing a response. Timeouts and cancellations
can have unknown effects; observe before acting again. A noncooperative Android/platform call cannot be
forcibly rolled back. The exclusive-control lease remains held until the coroutine actually exits, including
cancellation cleanup, rather than releasing early and admitting a competing controller.

## Guard and execution adapters

- `ControlArbiter.withControl(owner) { ... }` is the one application-wide exclusivity primitive. It rejects
  competing input immediately with `ControlBusyException`, avoiding queued screen actions against stale
  element IDs. `owner` is a read-only StateFlow for UI/phone busy state. A check of `running.value` alone
  is not synchronization and must not replace acquisition of the shared arbiter.
- `GuardedToolBackend(TvTools(app).all(), sharedControl, approveCallback)` exposes the installed real tools.
  It validates arguments, acquires control, calls `Tool.check`, awaits the injected approval if needed,
  rechecks the guarded target after approval, checks coroutine cancellation, then calls `Tool.run` once.
  A changed guard result or denial prevents execution. Raw tool exceptions are replaced with a safe error.
  The approval callback is mandatory and there is no permissive default.
- `ControlApprovalBroker.confirm(controller, GuardHit)` supplies model-independent pending-prompt state.
  `answer(allow, displayedPromptId)` consumes one matching answer, ignores stale IDs, declines on timeout,
  clears state on cancellation, and refuses a second outstanding prompt. Instantiate its clock with
  `android.os.SystemClock::uptimeMillis` so existing remote-key grace-period calculations use the same clock.
  It is intended for trusted Runner/overlay/phone presentation, never an MCP tool for granting approval.
- `ExternalToolBackend` is the backend seam for additional installed tool sets, not an unguarded transport
  hook. `executeGuarded` implementations must retain validation, approval, timeout/cancellation and shared
  arbitration. The supplied `GuardedToolBackend` already implements this contract for Vela `Tool`s.
- `McpService.cancelAll()` is for the trusted local Stop action or credential-change handler. It cancels
  external jobs and therefore their pending broker prompts. `close()` cancels the service lifecycle; the
  Ktor mounting hook also closes it at ApplicationStopped. Local credential revocation must cancel active
  jobs as well as publish a new credential snapshot; removal alone only rejects subsequent HTTP requests.

Legacy JSON response disconnection does not cancel work: the operation is owned by the service lifecycle,
not the HTTP call job. Legacy clients explicitly cancel via notification or DELETE. Modern tool calls use
SSE heartbeats so CIO detects a disconnected client while awaiting approval, then cancels that invocation.
Cancellation is best effort with no additional result emitted after disconnect. The test suite includes an
actual loopback CIO socket/stream-close test in addition to deterministic coroutine tests.

## Coordinator integration requirements (assembled)

Do not mount the endpoint until items 1–5 are integrated together. No further dependencies are required:
server CIO/core, kotlinx serialization JSON/coroutines, JUnit, coroutines-test and Ktor test-host are already
in `android/app/build.gradle.kts`. No MCP SDK, OAuth SDK, Gradle or manifest addition is necessary for the
loopback prototype. The inherited INTERNET permission is already used by the companion.

1. **VelaApp.kt:** create one `ControlArbiter` and one `ControlApprovalBroker` for the application; inject the
   same arbiter into Runner/AgentSession, phone operations and `GuardedToolBackend`. Create `McpService`
   using the application scope (Main.immediate, as the existing guard checks expect), not a request scope.
   Store a lifecycle owner for its server, credential snapshot and shutdown. Do not condition creation on
   `settings.agentReady` or call `settings.makeProvider()` anywhere on the MCP path.
2. **AgentSession.kt / Runner.kt:** inject the shared arbiter into the task session and wrap the entire internal
   agent run, including guard checks, approvals and task cleanup, in `withControl("agent:$taskId")`.
   Keep old-job cancellation/join before starting the replacement. A new task should fail as busy if an
   external controller owns the lease; do not merely set a running flag or cancel an unrelated controller.
   Ensure exception/state cleanup still runs if acquisition fails. Preserve generation checks and do not
   acquire the arbiter twice for nested Agent tools (it is deliberately non-reentrant).
3. **Runner.kt / approval presentation:** provide the external broker to the MCP approval callback:
   `{ call, hit -> externalApprovals.confirm(call.controller, hit) }`. Combine broker.pending with the existing
   session.pending for overlay/TV/phone presentation; combine sharedControl.owner with internal running for
   busy state and remote suppression. Wire question/id/since together. Runner.answer must route the displayed
   prompt ID to the correct broker/session; retain stale-ID protection and the TV remote grace period.
   If a physical TV answer omits an ID today, capture the current displayed ID only after the grace check.
   Runner.stop/clear must cancel internal work **and** `mcpService.cancelAll()` without clearing a newer prompt.
   Surface external activity/outcome through the trusted backend adapter; never claim execution succeeded
   before a tool result. The broker does not depend on an internal model or conversation.
4. **PhoneServer.kt / PhoneApi.kt:** wrap every competing action in the same arbiter, particularly remote
   input and direct layout/settings writes that could invalidate a running tool. The existing
   `!runner.running.value` check is insufficient. A phone ask schedules a session that itself holds the lease;
   do not hold a phone lease across starting that same session. Approval answers and Stop must remain usable
   while the lease is held; they resolve/cancel the owner and must not try to acquire control. Map busy to a
   clear conflict response. Reject connection/credential changes while a run owns control, or explicitly
   stop and join it before publishing the change. Existing pairing authorization still applies independently.
5. **Settings/pairing/lifecycle:** add an explicit local external-control enable/provision/revoke flow,
   generate a dedicated credential and persist it privately with backup/transfer exclusions. Default disabled.
   An empty credential snapshot fails closed. On revoke/disable, cancel external jobs, remove the credential,
   close the service/listener, and wait for control cleanup before admitting new input. Do not provision
   credentials, contact physical devices or expose LAN access without the main agent's retained approvals.
6. **Server mounting:** preferably create a **separate** `embeddedServer(CIO, host = "127.0.0.1", port = 8766)`
   whose Application calls `mcpApi(service, authorization)`. The route wrapper also enforces loopback, but
   binding loopback avoids unnecessarily opening a LAN socket. An approved desktop can use an approved
   local tunnel/Android port forward; tunnel provisioning is not performed here. Do not silently mount
   on PhoneServer's current `0.0.0.0:8765` cleartext listener. Stop the server and service with its lifecycle.
7. **Combined verification:** run Android unit tests (including `app.vela.tv.mcp.*` and
   `app.vela.tv.integrations.*`), then the coordinator's combined build/type checks. On an authorized emulator,
   verify external screen-read and a harmless guarded remote action with the internal provider unset;
   owner approval/deny/timeout/stale answers; local Stop; phone/internal-agent contention; and disable/revoke.
   UI integration needs actual light/dark visual review. No emulator or hardware acceptance is claimed here.

The core assembly is executable with the supplied classes; application ownership/lifecycle and trusted
approval presentation were originally coordinator-owned pieces and are now mounted:

```kotlin
val control = ControlArbiter() // Shared with all other controller entry points, not a second instance.
val approvals = ControlApprovalBroker(uptimeMs = android.os.SystemClock::uptimeMillis)
val backend = GuardedToolBackend(TvTools(app).all(), control,
    approve = { call, hit -> approvals.confirm(call.controller, hit) })
val service = McpService(backend, app.scope)
val authorization = McpBearerAuthorization { locallyApprovedCredentialSnapshot }
// Inside the separately lifecycle-owned loopback Ktor Application:
mcpApi(service, authorization)
```

## Provider and streaming extension points

The external desktop agent owns its supported model login and performs inference in its own process. Vela
receives only authenticated MCP requests and returns bounded observations. There is no browser-cookie,
Keychain, ChatGPT/Claude subscription-token scrape, token forwarding or promise that a consumer subscription
can authenticate Vela's internal provider. For an explicitly supported provider login/API, implement the
existing `Provider.complete` contract using that provider's documented credentials; keep credentials local
and injected through its approved connection flow. This MCP surface is independent of that adapter.

Streaming/media providers integrate through Vela's existing `Tool`/`ToolSpec` extension point: inject selected
validated tools into `GuardedToolBackend`, retaining their checks and explicit library/media targets. A new
HTTP streaming or account-linking API must implement its own bounded responses, supported authentication,
target validation, cancellation and entitlement/error handling. This module does not invent subscription
entitlements, scrape streaming accounts or claim DRM/playback integration. Catalogs are immutable per
service instance; recreate the service after an approved adapter/configuration change rather than silently
changing tools while advertising `listChanged: false`.

## Verification and official references

Isolated compilation uses the installed Kotlin **2.4.21**, JVM 17 bytecode on JDK 21, Ktor **3.6.1**,
coroutines **1.11.0** and serialization JSON **1.12.0**, without running a whole-project Gradle build.
Result: **36 tests passed**, including the real loopback CIO transport test. `git diff --check` and an
explicit whitespace scan of the new files also passed. The isolated run used only these new modules/tests
and the existing pure `Agent.kt`, `Model.kt`, and `Guard.kt` dependencies; it compiled them together with
the installed Kotlin compiler and cached runtime jars, then ran JUnitCore. No application code or build
output was rewritten by that check.
Tests cover handshake/capabilities, modern discovery and metadata, schemas/arguments, scoped discovery/calls,
malformed envelopes/IDs/duplicate keys/depth/bytes, content negotiation, Host/Origin/authentication,
session ownership/expiry/delete, duplicate active IDs, request/rate limits, denial/timeout/stale approval,
exclusive-controller contention, cancellation cleanup, sanitized errors and SSE disconnect cancellation.
Combined Android build, end-to-end Runner/phone integration and device acceptance remain coordinator work.
After combining the other workers' changes, the coordinator can run the scoped Android suite from the
repository root with `android/gradlew -p android :app:testDebugUnitTest --tests 'app.vela.tv.mcp.*' --tests
'app.vela.tv.integrations.*'` (with its configured JDK 21), followed by the combined project checks.

Current official references consulted, 2026-10-31:

- [MCP 2026-07-28 versioning and dual-era compatibility](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning)
- [MCP 2026-07-28 Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)
- [MCP 2026-07-28 discovery](https://modelcontextprotocol.io/specification/2026-07-28/server/discover)
- [MCP 2026-07-28 base protocol and errors](https://modelcontextprotocol.io/specification/2026-07-28/basic)
- [MCP 2026-07-28 schema](https://modelcontextprotocol.io/specification/2026-07-28/schema)
- [MCP 2025-11-25 lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)
- [MCP 2025-11-25 tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)
- [MCP 2025-11-25 cancellation](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/cancellation)
- [Ktor 3.6.1 responses and writer streaming](https://ktor.io/docs/server-responses.html)
