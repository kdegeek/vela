# Deterministic adversarial properties — 2026-10-31

Scope: three new JVM test files. Production, existing tests, device tooling,
fixtures owned by other workers, infrastructure and Git state were not changed.
These checks complement the existing example regressions; they do not establish
visual, hardware, real-provider, firmware or assembled-app acceptance.

## Cases and replay

Each generator uses `kotlin.random.Random(baseSeed + case)` and bounded loops.
Assertion labels identify the seed and mutation. Replay by running the named
JUnit method; every seed is rerun in the same order with installed Kotlin 2.4.21.
There are no fuzzing dependencies, wall-clock sleeps or dependency upgrades.

| Test file / property | Seeds (inclusive) | Generated cases |
| --- | --- | --- |
| `agent/AdversarialPropertiesTest.kt`: batch envelope atomicity | `0xBA7C000`–`0xBA7C017` | 24 batches × 5 mutations = 120 rejected batches, each followed by a valid one-call batch |
| Same: conversation retention | `0xC017E00`–`0xC017E2F` | 48 histories; 1–10 owner turns, 1–4 exchanges per turn, 1–5 calls per exchange, mixed argument/result sizes; varied 12,000–100,000 character and 1–8 turn budgets |
| Same: typed integer limits | `0x1A70000`–`0x1A70027` | 40 inputs × 8 invalid encodings = 320 rejection probes; 80 valid direct/exponent controls |
| Same: replacement/cancellation | `0xD2A1000`–`0xD2A1017` | 24 chains with 2–16 replacements, varied dispatcher drains and shuffled stale cancellation IDs |
| `layout/LayoutPropertiesTest.kt`: composition round trips | `0x1A90100`–`0x1A9012F` | 48 layouts × original/permuted/prefix variants = 144 compositions |
| Same: corrupt row recovery | `0xBAD1A00`–`0xBAD1A1F` | 32 layouts × 6 mutations = 192 rejections, each followed by a valid decode |
| Same: nested/truncated actions | `0xDEE9100`–`0xDEE9117` | 24 inputs × 4 mutations = 96 rejections and recoveries; depth 32–128 |
| `mcp/McpPropertiesTest.kt`: parser recovery | `0x2FC0000`–`0x2FC001F` | 32 inputs × 5 mutations = 160 rejections and recoveries; depth 33–80, escaped duplicate keys, unsafe IDs and multibyte byte-limit probes |
| Same: HTTP authorization and recovery | `0xA077000`–`0xA07700B` | Intended 12 sequences × 4 requests: unauthorized hostile body, authorized rejection, valid same-ID recovery, revoked-credential denial |

Batch atomicity concerns malformed **envelopes**: blank IDs/names, duplicate IDs,
IDs reused from retained history and oversized call counts. It checks that no
tool preparation or effect happens anywhere in that invalid batch. It does not
assert transactional execution of a batch containing individually invalid tool
arguments; argument validation is a per-call contract.

Conversation checks require complete ordered call/result pairs, unchanged retained
arguments/results, retention of the latest owner request, idempotent trimming and
no mutation of the supplied history. They exercise budgets much smaller than the
production budget as well as oversized exchanges. The cancellation checks use
deferred cleanup/completion barriers and the coroutine test scheduler: old tasks
must drain once, the last task must keep its lease despite stale cancellations,
and completion must release ownership.

The numeric property checks actual fractional values, overflowing positive and
negative numbers, extreme exponents, strings, booleans and null through the real
executor, before preparation. An initial draft incorrectly treated all exponent
notation as invalid. A direct installed-library probe confirmed that
`-2147483648e0` is the exact integer `Int.MIN_VALUE`, while fractional and overflow
values fail conversion. The final property explicitly accepts exact `e0` values;
it does not impose an undocumented lexical-only rule.

Layouts compose Apps, Watchlist, HomeEntities and pinned actions independently of
media provider implementations. Unicode, escaped structural characters, row/tile
order and styles survive serialization. Corruption at a selected row or action,
unknown source types, oversized documents, missing required fields and truncated
JSON must fail without affecting the next decode. These tests do not claim a
layout nesting limit or rejection of intentionally ignored unknown metadata.

## Evidence

Isolated compilation uses the actual current production sources, installed
Kotlin 2.4.21, JDK 21 (JVM target 17), coroutines 1.11.0, serialization 1.12.0,
Ktor 3.6.1 and JUnit 4.13.2. Output is a fresh temporary directory; no shared
Gradle task or build directory was run or written by this worker.

Latest complete isolated run:

- **9 new test methods: 8 pass, 1 fails.** JUnit time 1.057 seconds; subprocess
  wall time 1.173 seconds. The HTTP property stops at its second recovery, so its
  remaining ten sequences have not passed. This is not a green suite claim.
- **78 existing targeted test methods pass.** JUnit time 0.645 seconds; subprocess
  wall time 0.757 seconds. These cover agent execution/history/ownership, argument
  validation, executor behavior, layout, MCP protocol/service/HTTP and backend.
- Logs and compiled classes:
  `/var/folders/tc/f5wf7p2n68z7htvy4dr283sw0000gn/T/vela-adversarial-ntr0kcgm/`
  (`new-junit.txt`, `existing-junit.txt`, `runtime-classpath`).
- Reproduction helper: `/tmp/vela-adversarial-check.py`. It compiles into temporary
  output using installed dependencies and the existing local Ktor classpath file;
  it is local evidence, not a portable repository build script. Root owns final
  combined Gradle verification after all writers quiesce.

## Production finding retained as a failing property

**Modern MCP can execute a tool while losing its SSE result.** At seed
`168259585` (`0xA077001`), the authorized valid recovery request returns HTTP 200
with `text/event-stream`, but no `data:` result event. Separate isolated runs
produced either an empty body or only `: keep-alive`. No test expectation was
weakened to accept that response.

The reproduction minimizes further to **two sequential valid authenticated
modern `tools/call` requests** against the same mounted application/service:

1. Configure one synthetic test credential and the existing `McpFixture` backend.
2. Send `remote` with `{"key":"select"}` and modern protocol metadata/mirrored
   headers, IDs `0` then `1`.
3. Read both complete response bodies.

Observed: call 0 returned a normal result event; call 1 returned HTTP 200 with an
empty body, while the effect counter was **2**. Neither malformed input nor
credential rotation is required for the minimal reproduction. A client retry
could therefore repeat an effect that already occurred. This is an in-process
Ktor HTTP reproduction, not a claim about a real external client or device.

Minimal evidence:
`/var/folders/tc/f5wf7p2n68z7htvy4dr283sw0000gn/T/vela-mcp-stream-minimal-w5j99wyz/`
(`McpStreamProbeTest.kt`, `result.txt`), generated by
`/tmp/vela-mcp-stream-probe.py`; JUnit time 0.335 seconds, one failing method.

Likely source: `McpHttp.kt`'s `streamInvocation` calls `invocation.cancel()` in a
`finally` outside the `respondTextWriter` body. The response writer can outlive
that outer call. Root was notified immediately with the failing seed and then
this smaller reproduction. Production remediation belongs to root; this worker
has not edited the transport or weakened the property.

## Limits

The generated domains are finite, not exhaustive. Deferred scheduling exercises
specific coroutine orderings; the HTTP test uses Ktor's real in-process test
engine, including its actual dispatcher behavior. No socket/device/browser,
focus-navigation, live provider, long-duration soak or visual acceptance was
performed. The existing device-control block remains in effect. The sub-ten-second
measurement above is observed local JVM runtime, not a guarantee for every host.
