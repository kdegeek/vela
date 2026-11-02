# Home / Undo review

Read-only review on 2026-10-31 of
`20502607a7667bfc012168f5819d84a1b4941ea4..d64290a41711cb1563a8128818a1b5414b8cf1ee`.
HEAD matched the supplied endpoint and the checkout was initially clean.
This report is the only checkout addition. No production/test edits, Git or PR
mutations, builds, device operations, network services, credentials, or physical
Shield access were performed. No delegation was used.

## Ranked finding

### P2 — Translate a storage-level stale receipt into task invalidation

**Owning change:** `android/app/src/main/java/app/vela/tv/agent/LayoutUndoAction.kt:17-19`
(the unadapted `restore(receipt)` call); production callback:
`android/app/src/main/java/app/vela/tv/agent/TvTools.kt:211-215`.

An Undo that becomes stale after the prepared action's identity check is correctly
rejected by `LayoutHistory.undoLayout` (`settings/LayoutUndo.kt:44-46`). However,
that rejection is an `IllegalStateException`, which escapes the Undo adapter and
lands in the generic failure handler in `agent/ToolExecutor.kt:60-62`. Unlike an
early stale rejection (`ToolExecutor.kt:21,54-56`), it does **not** call `stopped()`.
The owner/model receives a connection/permissions failure rather than an obsolete
action. `Agent.kt:87-100` consequently permits the remaining calls in that batch
and subsequent model turns to continue.

This contradicts the integration contract in `docs/design/layout-undo-contract.md:82-85`:
surface the stale receipt as an obsolete action, not permission to undo a different
change. A subsequent `layout_undo` has no caller-supplied receipt argument; it
captures the newer receipt and can undo that newer change in the same task.

**Deterministic evidence:** a private Kotlin probe used the real `LayoutHistory`,
`prepareLayoutUndo`, `ToolExecutor`, and `Agent` implementations, with an in-memory
commit callback and a scripted local Provider (no network). It used the same
IO-gap injection already present in `LayoutUndoActionTest.kt:98-110`:

1. Start at A; save B, creating receipt A→B.
2. Prepare Undo. In its restore callback, save C before calling
   `history.undoLayout(capturedReceipt)`.
3. Assert C remains persisted and the first Undo errors. Observe `stopped == false`.
4. In a two-Undo agent batch, inject the change only on the first callback. The
   second call executes, captures B→C, and restores B rather than being skipped.

Observed output:

```text
STORAGE_STALE: error=true, stopped=false, layout=C, writes=2,
text=layout_undo failed. Check the current state, connection and permissions.
STORAGE_STALE_BATCH: subsequentRestores=1, finalLayout=B
first result: isError=true
second result: Previous Home layout restored., isError=false
```

**Reachability limit:** this proves an error-contract failure at the production
storage/executor seam, not an observed UI/phone race. All current layout writers
examined acquire the shared controller lease, which normally prevents a competing
save during an agent Undo. The injected race is the explicitly supported defensive
case tested by the increment; the existing test verifies rejection but omits the
task-stop assertion. There is no evidence that a stale receipt itself writes or
that the controller lease is bypassed.

**Suggested correction:** distinguish the store's stale-receipt rejection from
ordinary persistence errors and translate that rejection to
`ActionInvalidatedException` in the agent adapter. Keep I/O failures retryable.
Extend the existing IO-gap case to require `stopped == true`, and verify that a
following tool call in the same agent batch is skipped. Avoid translating every
`IllegalStateException` indiscriminately or substituting the latest receipt.

## Checks and boundaries

- Receipt identity is object identity, including ABA and foreign-store cases.
  Inputs and every collection-bearing layout field are defensively copied/frozen.
  The store rechecks identity under its synchronization before committing.
- The verified write precedes authoritative state and flow publication. The
  publication code rereads authoritative state after immediate collector reentry.
  The two flows are intentionally not an atomic pair. Post-write verification
  failure can leave disk ahead of memory; this is explicitly documented existing
  writer behavior, not a newly reported rollback guarantee.
- Native Undo captures the displayed receipt, reserves `owner-layout`, dispatches
  to IO, checks cancellation, and relies on the storage identity check. UI buttons
  are keyed by receipt and disabled during controller activity; Keep retains the
  receipt. Native errors are surfaced without automatically choosing a new receipt.
- Agent/MCP admission, settings/profile binding, and risk classification were traced
  through `TvTools.boundTool`, `ProfileExecution`, `Runner`, `AgentSession`,
  `ControlArbiter`, `GuardedToolBackend`, and `OwnerManagement`. Profile edits are
  exclusive; profile switches stop and drain before committing. Undo restores only
  layout data and never dispatches a tile action. Native owner layout operations
  retain the preexisting owner-management policy rather than requiring agent access.
- Cancellation during approval is covered. Cancellation while synchronous disk
  work is already executing is not a rollback guarantee: the lease remains held
  until the call exits. This review does not establish Android disk timing behavior.
- Default order is Continue watching, Apps, Recently added, My list. Explicit saved
  rows are not migrated. Media selection changes decoration and leaves row actions
  intact. A private probe also confirmed that Home's preexisting key-only focused
  tile reconciliation (`HomeActivity.kt:83`) can select a replacement at a reused
  pinned position before the new helper checks action identity. Because that
  replacement is the current tile at that focus position and no stale action was
  dispatched, this is not ranked as a consequential regression. Pure helper tests
  alone should not be read as end-to-end focus/refresh acceptance.

## Verification performed

The four focused existing suites were replayed from private copies of the current
debug build's runtime/test classes, using cached dependencies and Java 21, without
Gradle or writes to shared build outputs:

| Suite | Result |
| --- | --- |
| LayoutUndoTest | 20 passed |
| LayoutUndoActionTest | 8 passed |
| HomeMediaPresentationTest | 12 passed |
| EntertainmentHomeLayoutTest | 2 passed |

Total: **42 passed**, zero failures. These are existing compiled tests, not a new
source build. The private probe was separately compiled and executed against the
copied runtime JAR; all probe assertions passed. The finding follows the actual
source contracts as well as the runtime result.

Private reproducibility artifacts:
`/var/folders/tc/f5wf7p2n68z7htvy4dr283sw0000gn/T/vela-home-undo-review-_i6aplah/`
contains `Probe.kt`, `compile.log`, `probe.log`, `focused-tests.log`, `classpath`,
the copied runtime JAR, and the copied focused test classes. The probe entry point
is `app.vela.tv.agent.ProbeKt`; JUnit entry point is `org.junit.runner.JUnitCore`.

The root's existing XML reports were read: **498 tests / 72 suites**, zero
failures/errors/skips. No redundant full-suite run was made. The fixed range also
passes `git diff --check`. The capture provenance/limits were read from
`home-entertainment-20261031/README.md`; no new visual inspection or acceptance is
claimed, and screenshot-scope omission is not treated as an app rendering bug.

There is **one actionable contract-level finding** above, with its production
reachability limit stated. No other consequential finding was established within
this scope. Live MCP transport, GUI focus timing, Android persistence faults,
physical Shield behavior, visual acceptance, and complete OS acceptance remain
outside this review. PR #6 remains intentionally unmerged; remote checks and
review state were not queried or changed.

## Coordinator correction

The coordinator independently reproduced the finding before changing production:
the strengthened storage-gap test and new two-Undo agent-batch regression both
failed in the targeted Gradle run (**10 tests, two failures**). The batch test
executes the real Agent, ToolExecutor, Undo adapter and layout state component
against a scripted local provider. It checks that no second restore and no next
model turn occurs, with the newer document and its receipt left intact.

`LayoutHistory` now throws the specific `StaleLayoutUndoException` subtype of
`IllegalStateException`; the Undo adapter translates only that rejection into
`ActionInvalidatedException`. The existing executor then stops the task. A further
regression injects an ordinary `IllegalStateException` persistence failure and
verifies that its receipt stays available for a successful explicit retry. It is
not misclassified as invalidation. No controller or UI admission behavior changed.

Production UI/phone race reachability remains unproven; the shared lease normally
excludes the competing write. The correction closes the supported defensive
storage/executor contract without claiming a demonstrated controller bypass.

Final coordinator validation:
`testDebugUnitTest lintDebug lintFirmware assembleDebug assembleFirmware`
passed in 48 seconds. **500 tests / 72 suites**, zero failures/errors/skips;
both lint variants retain zero errors and 13 existing warnings. Both APKs build.
The focused Undo suite now has **10 passing tests**. No device operation or new
visual acceptance was needed for this backend-only correction.
