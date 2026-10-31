# One-step Home layout Undo

`SettingsStore.setLayout(HomeLayout)` keeps one session-only Undo receipt for the
most recent successful layout change. Human, phone, and agent callers already
share this method. The persistence component introduces no route or persistent schema.
The existing `layout: StateFlow<HomeLayout>` and `setLayout` signatures are unchanged.

## Public API and receipt identity

```kotlin
class LayoutUndo internal constructor(previous: HomeLayout) {
    val previous: HomeLayout
}
class StaleLayoutUndoException : IllegalStateException

// SettingsStore
val layoutUndo: StateFlow<LayoutUndo?>
@Synchronized fun undoLayout(expected: LayoutUndo): Unit
```

`LayoutUndo` is a final, immutable, non-data class. Its **object identity is the
unique change identity**. It deliberately has no value equality, `copy`, public
constructor, numeric generation, serialization, or redo API. The store checks
`activeReceipt === expected`, never layout equality or a hash. Every successful
non-identical save creates a fresh receipt, including A → B → A → B. A stale
receipt cannot become valid again when either document repeats. Receipts from
another store instance are also invalid, even with identical `previous` values.

Both `layout` and `layoutUndo` are read-only StateFlow wrappers. Layout inputs
(including the initial layout) are validated and copied through the real
`Layouts.encode`/`Layouts.decode` codec. All model collections are then wrapped
as unmodifiable copies: rows, app packages, pinned items, and Home Assistant
entity IDs. Row/item/action fields otherwise contain immutable values. The same
defensive copying applies to receipt construction. Mutating a caller's original
lists cannot change current state, persisted text, or a receipt; casting exposed
lists to mutable collections cannot modify them. If the public model gains new
collection-bearing fields, its snapshot handling must be extended as well.

## State and persistence rules

| Operation | Verified write | Published layout | Published Undo |
| --- | --- | --- | --- |
| Store initialization | None for layout | Existing valid document, otherwise existing default | `null` |
| Successful changed `setLayout(B)` from A | B | B | New receipt with previous A |
| Successful identical `setLayout(A)` from A | A | A | Exact existing receipt, including `null` |
| Successful `undoLayout(receipt)` | Receipt's previous document | Receipt's previous document | `null` |
| Invalid save, stale Undo, or failed commit | Invalid/stale requests never write; failed writes may have started | Unchanged | Unchanged |

Identical saves retain the existing verified-write behavior, while preserving
the active receipt. Undo is consumed only after the previous layout has been
persisted and verified. Undo never calls `setLayout`, so it cannot create redo.
The store retains at most one receipt, with no history chain. A subsequent save
after Undo may create a new receipt in the usual way.

Both store methods hold the same `SettingsStore` monitor (`@Synchronized`) used
by existing settings mutations. The Android-free `LayoutHistory` component also
serializes its save and Undo methods on one monitor. Receipt revalidation,
verified commit, and state updates occur within that synchronization; an Undo
waiting behind a changed save is rejected. The injected commit accepts the
canonical JSON string and must return only after verified persistence, or throw.
Production supplies the unchanged `write("layout.json", text)` implementation:
AtomicFile write, file-descriptor sync, finish, then exact read-back verification.

No layout or receipt is published before that callback succeeds. The internal
authoritative state is updated before flow notifications, so immediate collectors
also cannot use an invalidated receipt. The two public flows are individually
observable, **not an atomic pair**; callers must rely on `undoLayout` revalidation,
not an earlier UI observation or document comparison, for correctness.

As with the existing writer, a read-back verification failure can occur after
the file has been replaced. Such a failure leaves the in-process layout and
receipt unchanged and allows retry; it does not promise disk rollback after a
failed verification. No new disk recovery or cross-process coordination is added.
The application should continue to use its shared SettingsStore instance.

## UI and controller integration

1. Observe `layoutUndo`; offer Undo while non-null, alongside the existing Restore
   default affordance as appropriate.
2. Capture the exact displayed receipt when preparing/accepting the action.
3. Enter the existing owner-layout Runner lease and invoke
   `settings.undoLayout(capturedReceipt)` on IO while holding that lease.
4. Report completion only after the method returns. A stale receipt throws
   `StaleLayoutUndoException` with the message "Home changed; this Undo is no
   longer available." before writing. Surface that as an obsolete action, not permission to undo
   a different change. I/O failures propagate and leave the receipt retryable.

Do not substitute the latest receipt while executing a previously prepared
action, or call `setLayout(receipt.previous)` to perform Undo: either would bypass
the expected-change check. Admission, lease handling, prepared-action wiring,
UI presentation, and error presentation remain the integrating controller's work.

Undo history is deliberately session-only. A new store/process loads the existing
layout document and starts with `layoutUndo == null`; prior receipts are invalid.
Restore default remains available through `setLayout(Layouts.default)` after
restart. A changed Restore default is itself undoable during that session.
Settings values, watchlist operations, pairing tokens, device IDs, persistence
schemas, and the layout/default model are unchanged by this implementation.

## Coordinator wiring

Home observes the exact receipt and displays a compact Home updated / Undo / Keep
layout notice. Keep dismisses the notice without discarding history; Undo remains
available beside Arrange home. The controls are keyed by receipt identity, so a
gesture begun on an obsolete notice cannot activate a replacement notice.
`undoOwnerLayout` enters Runner's `owner-layout` controller before dispatching the
captured receipt to IO. Busy admission and the storage identity check both apply;
errors are shown without substituting the latest receipt or retrying silently.

The stale exception remains an `IllegalStateException` subtype for existing
callers. The agent adapter translates only that specific exception into
`ActionInvalidatedException`, including when the final storage check rejects a
receipt after IO dispatch. That stops the task and skips the rest of its tool
batch; it cannot capture newer history through a subsequent Undo. Unrelated
persistence exceptions keep the existing failure path and do not consume history.

The agent and authenticated MCP backend also expose `layout_undo` through the
shared tool set. `prepareLayoutUndo` snapshots the receipt and classifies its
previous document through the existing tile guards before approval. Prepared
execution rechecks identity; SettingsStore rechecks it again atomically with the
verified write after IO dispatch. The usual settings/profile binding and controller
lease remain in force. Raw tool execution still fails closed. Restoring the
document never activates any tile.

## Verification

`LayoutUndoTest` exercises the production state component and real layout codec:
one-step consumption, identical saves, ABA and foreign receipts, pre-write and
post-write verification failures, invalid documents, all mutable model collections,
restart behavior, competing save/Undo calls in both orders, double Undo, and
immediate flow collectors. Its injected commit makes failure timing observable
without replacing the codec or requiring Android storage in a local unit test.

This leaf runs standalone Kotlin/JUnit compilation in a private temporary
directory using the project's cached compiler and library versions. It does not
run Gradle or write shared build outputs. Combined Android builds, actual
AtomicFile/device behavior, and UI validation belong to the integrating root.

Leaf verification passed: **20 tests**, compiled against the real `HomeLayout.kt`
with Kotlin 2.4.21 and its serialization compiler plugin, serialization 1.12.0,
coroutines 1.11.0, and JUnit 4.13.2 on Java 21 (JVM target 17). No Android stub
substitutes were used for the state-machine or codec tests. `SettingsStore` itself
still requires the root's combined Android compilation and integration checks.

Coordinator verification subsequently passed the combined build/lint/test command
with **498 Android tests**. Native TV Undo restored the exact previous persisted
layout, and Keep layout retained its Undo action. A second native move/Undo
restored the default order. See [Home/Undo acceptance evidence](evidence/home-entertainment-20261031/README.md).
The new prepared-action suite adds eight cases for approval denial, missing or
stale history, ABA, cancellation, and the IO handoff identity check. This does not
claim live MCP transport execution or physical Shield acceptance.

The later independent review's storage-gap invalidation finding is corrected
as described above. Its strengthened regression failed before the change; the
batch-stop and ordinary-storage-error regressions now pass with the full
**500-test** combined suite. The prepared-action suite contains ten tests.
