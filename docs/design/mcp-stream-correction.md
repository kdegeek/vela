# MCP stream result delivery correction — 2026-10-31

## Reproduction and cause

The unchanged `McpPropertiesTest` reproduced the reported failure with the current
production sources: seed `168259585` (`0xA077001`) returned HTTP 200 and
`text/event-stream; charset=UTF-8`, but an empty body. Eight of the nine generated
properties passed; all 78 original targeted example tests passed. A new two-call
HTTP regression also failed on request ID 1 with zero result events. Nothing in
the parser, authentication or property expectations was relaxed.

An isolated instrumented copy of `McpHttp.kt` confirmed the ordering:

```text
respond returned: completed=false cancelled=false
writer entered: cancelled=true
```

`streamInvocation` canceled the service-owned invocation in a `finally` around
`respondTextWriter`. A normal return from that function does not universally mean
the writer has finished. Installed Ktor 3.6.1 bytecode confirms that test-host's
`TestApplicationResponse.respondWriteChannelContent` launches a separate writer
job, removes the calling context's Job and returns without joining that writer.
The outer cleanup therefore raced the operation and result delivery. Cancellation
can occur before the effect or after it has begun; the latter leaves the client
unable to infer whether an action occurred from the empty response.

This is a lifetime defect in production transport code, reproduced through the
real production route in Ktor test-host. **The original failure was not reproduced
over a CIO socket.** Ktor's current base response implementation awaits
`writeTo`; an isolated comparison running the five CIO tests with the original
`McpHttp.kt` also passed. The correction removes the engine scheduling assumption;
the socket checks establish compatibility and cancellation behavior, not evidence
of pre-fix CIO packet loss.

## Correction

Only the modern SSE path in `McpHttp.kt` changed:

- The channel writer owns invocation cleanup through its final result flush,
  including cancellation and write failures. A normal response handoff leaves
  the invocation alive.
- An outer `catch` cancels and rethrows response-setup failures, including
  cancellation before writer entry. Header setup is inside that protected region.
- `respondBytesWriter` writes explicit UTF-8 SSE with the same content type,
  status, framing and heartbeat interval. Installed `ChannelWriterContent.writeTo`
  invokes the supplied body directly, so entry with an already-canceled context
  reaches its `try/finally`. The previous `WriterContent` first dispatched through
  a blocking-writer bridge, which could skip the callback on prompt cancellation.
- Cancellation is checked at writer entry and before writing a completed result.

`McpService`, the guarded backend, shared controller admission and the root's
listener lifetime correction were not changed. Service Stop/revoke still cancel
the same invocation; its controller remains owned until actual coroutine cleanup
finishes. Legacy responses retain their existing service-owned disconnect
semantics. Authentication, input limits, rate/in-flight bounds, result limits and
duplicate-active-ID checks are unchanged. No automatic retry or replay was added.

## Verification

Command: `python3 /tmp/vela-mcp-stream-check.py`. This local helper extends the
existing `/tmp/vela-adversarial-check.py` dependency paths. It compiles actual
current source files into fresh temporary output, including the real
`AndroidExternalControl.kt`/`LoopbackMcpListener`, its private-file dependency and
the manager. Android APIs are compile-only via the installed Android 37 jar;
the listener tests exercise actual loopback CIO, not an Android stub listener.
Kotlin 2.4.21, JDK 21/JVM target 17, coroutines 1.11.0, serialization 1.12.0,
Ktor 3.6.1 and JUnit 4.13.2 were used without dependency changes or shared Gradle
build output.

**100 JUnit methods pass:**

| Group | Methods | Coverage |
| --- | ---: | --- |
| Generated properties | 9 | All agent/layout/MCP properties, including all 12 HTTP authorization/recovery sequences |
| Current targeted examples plus sequential HTTP regression | 80 | All 78 original tests, the coordinator's new external-admission/Ask drain regression, and the sequential HTTP regression: IDs 0 and 1 each deliver exactly one matching successful result |
| `McpCioTransportTest` | 5 | Original actual-listener start/stop/reopen; original real disconnect/approval cancellation; 16 sequential socket calls with matching successful results/effect counts; Stop and credential-revocation drain/recovery |
| `McpStreamCleanupTest` | 3 | Response setup failure; already-canceled deferred writer entry with no emitted bytes; closed-channel first-heartbeat failure |
| Existing `ExternalControlManagerTest` | 3 | Explicit enable/restore/revoke, authorization removal before cancellation drain, safe failures/recovery |

The Stop/revoke socket tests suspend an executing operation in cancellation
cleanup. They observe continued controller ownership, reject a competing action
(busy for Stop, unauthorized for revoked credentials), release cleanup, read the
canceled stream to EOF without a result event, and then deliver exactly one
successful fresh result. Effect counts prove no automatic replay. Socket tests
use ephemeral ports on `127.0.0.1` only. Deferred barriers, bounded timeouts,
channel reads and StateFlow predicates synchronize the tests; there are no wall
sleeps or busy polling. The original disconnect test's yield loop was replaced
by a StateFlow predicate.

Latest complete evidence directory:
`/var/folders/tc/f5wf7p2n68z7htvy4dr283sw0000gn/T/vela-mcp-stream-check-uyzy6ft4/`
(`new-junit.txt`, `existing-junit.txt`, `transport-junit.txt`, `runtime-classpath`).
Pre-fix property evidence is in `vela-adversarial-4o2znzr8`; pre-fix sequential
HTTP evidence is in `vela-adversarial-6b0xujmj`, under the same temporary parent.
The instrumented source and installed-library bytecode are separate local probes
under `/tmp/vela-mcp-lifecycle-probe/` and `/tmp/vela-mcp-lifecycle-bytecode/`.
Pre-fix CIO comparison: `vela-mcp-cio-before-m7b358m_` under that temporary parent.
No probe instrumentation was added to repository production code.

## Limits

This does not promise exactly-once delivery across a real disconnect: an action
may already have occurred before a failed write, and partial writes cannot be
rolled back. Clients still must observe state before retrying an action whose
result was lost. Credential-revocation socket coverage publishes an empty
authorization snapshot and cancels active work; the existing manager regression
separately verifies its complete lifecycle drain.

Shared Android builds remain coordinator-owned. No device, assembled-app,
hardware, external desktop client, UI, infrastructure or deployment acceptance
is claimed. The previously stopped device-control path was not resumed.

Official API reference checked against the installed implementation:
[Ktor 3.6.1 streaming responses](https://ktor.io/docs/server-responses.html#streaming-binary).
