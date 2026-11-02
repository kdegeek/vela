# Activation-gate correction review

2026-10-31. Read-only review of the coordinator's complete `ActivationGate.kt`,
`Focus.kt` wiring and six `ActivationGateTest` tests against installed Compose
Foundation/UI **1.12.1**, the design language, component contract and WP6 audit.
**Four actionable findings remain.** Ordinary single-input busy-transition protection
works; the correction is not yet complete for cancellation and overlapping inputs.

Only this new evidence note was written in the repository. No production/test edits,
Git mutations, shared Gradle tasks, peer/delegate interaction, GUI/browser/device,
SSH, infrastructure, firmware or credential actions occurred. The physical Shield
remained untouched and paused. This is not visual or device acceptance.

## Evidence and method

Private artifacts: `/tmp/vela-activation-review-kdlwjaat/`.
Reproduce the compiled probes with `python3 /tmp/vela-activation-review-kdlwjaat/run.py`.
`base-tests.log`, `key-probe.log`, `integration-probe.log`, probe sources and reviewed
source copies/hashes are in that directory. `run.py` recompiles the current repo gate
and tests; the extracted pointer observer in `src/IntegrationProbe.kt` records the
reviewed `Focus.kt:74–92` verbatim. Refresh that extraction before testing a revised
adapter. An `expected=0` line with `callbacks=1` is a reproduced defect, not a pass.

- Cached `foundation-android/1.12.1` AAR SHA-1 directory:
  `782d83fdb0f27e1fd723099da6ef33c45a69711c`; `ui-android/1.12.1`:
  `95600d9aea586baa1493db8a2c0ab48507cc4ecf`.
- `classes.jar` was extracted privately and inspected with JDK21 `javap -c -p`.
  Matching published [Foundation sources](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.12.1/foundation-android-1.12.1-sources.jar)
  and [UI sources](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.12.1/ui-android-1.12.1-sources.jar)
  were downloaded from Google's Maven repository into the private directory.
  Key predicates, key dispatch, pointer dispatch and callback paths agree with the
  cached bytecode. Source references below are within those versioned archives.
- The probes execute the **actual cached ClickableNode/AbstractClickableNode**, the
  unchanged compiled gate, installed coroutine gesture helpers and UI semantics
  configuration. The pointer observer is extracted from current `Focus.kt`.
- JVM fixture boundaries: a tiny Android `KeyEvent` getter carrier and non-rendering
  `Paint` stub, installed mockable Android jar, null interaction source, click sound
  disabled, manually supplied preview/pass ordering, and a minimal layout/coordinator
  with fixed density/view configuration. UI-tree gesture coordination is bypassed;
  the real ClickableNode Main/Final event handlers and cancellation methods execute.
  No Android input service, live focus traversal, recomposer or accessibility service
  runs. These are deterministic callback-path proofs corroborated by source, not
  claims that the owner generated these event sequences on the Shield.
- No dependency was added or installed. Compilation uses existing Kotlin 2.4.21,
  JDK21 and cached library jars, with all outputs private. Six existing gate tests
  independently pass. The reported 20-test/full-design compilation results were
  not rerun by this review.

## Findings

### 1. P1 — a repeat after focus loss re-arms the same unavailable hold

**Location:** `Focus.kt:60–68`; `ActivationGate.kt:24–27,39–41`.

Trigger: hold D-pad Center while busy → move focus away → become ready and restore
focus before physical release → receive a repeat of that same held key → release.
`cancelKeys()` deletes the record; the preview handler treats every KeyDown,
including `nativeKeyEvent.repeatCount > 0`, as `begin()`. With no existing record,
`putIfAbsent` creates an eligible press. Foundation also rebuilds its key record on
a repeat when its map was cleared by focus loss. The actual-node probe produced
**one callback; expected zero**. Without the repeat, the same sequence produces zero.
A key first routed to a newly focused surface as a repeat has the same issue.

Source: `Clickable.kt:1859–1877,1944–1983`; UI `FocusOwnerImpl.kt:584–607` validates
key-down/up membership but does not reject orphaned repeats. Android documents
[repeat counts](https://developer.android.com/reference/android/view/KeyEvent#getRepeatCount())
as distinguishing the initial down from later repeats.

**Bounded correction:** only an initial physical down may create a new key record;
repeats may preserve an existing hold but must never arm an absent/canceled one.
Retain focus-loss invalidation and the single Foundation focus target. Add an
adapter regression including actual repeat count and a subsequent fresh press.

### 2. P2 — a receipt from another input can authorize a rejected pointer gesture

**Location:** `ActivationGate.kt:14,24–31,44–47`; `Focus.kt:64–69,75–90,105`.

Trigger reproduced through the real pointer handlers: pointer 1 goes down while
busy → pointer 2 also goes down → readiness returns → pointer 1 lifts while pointer
2 remains down → Shift down/up → pointer 2 lifts. The observer completes the first
pointer early, whereas Foundation waits for all pointers to lift. Shift is tracked
by the preview handler even though it is not an activation key, and its release
replaces the single `completed` slot with an eligible receipt. The final pointer
callback consumes that unrelated receipt: **one callback; expected zero**. The
same two-pointer busy gesture without Shift produces zero; a continuously ready
two-pointer gesture produces one.

Cancellation compounds the problem: Android pointer cancellation is delivered to
the suspending observer as a **consumed synthetic up**, not a coroutine exception.
The current observer calls `release`, skips its `finally` cancellation and leaves
an eligible receipt. Consumed drag/out-of-bounds handling cancels Foundation but
is not mirrored by the observer. Probes establish no immediate click for those
cancellations, but canceled/dragged pointers leave usable receipts. A busy indirect
press → ready → direct pointer down/cancel → indirect up demonstrably consumes the
canceled pointer's receipt and invokes once. This is callback proof, not merely a
claim based on inspecting `takeClick()`.

Source: `Clickable.kt:873–895,945–952,965–1025`; UI
`SuspendingPointerInputFilter.kt:733–764`; Foundation `ForEachGesture.kt:96–118`.

**Bounded correction:** authorize the input/gesture completing the current dispatch,
not the latest unrelated release. Match Foundation's all-pointers-up lifetime;
ignore unrelated key codes; invalidate canceled/consumed/dragged sequences and
expire unused receipts after their event. Add the mixed-input and synthetic-cancel
integration cases. A global untyped completed slot is insufficient.

### 3. P2 — canceled key-up still invokes the action

**Location:** `Focus.kt:66–68`.

Trigger: initial Center down while ready, then ACTION_UP carrying `FLAG_CANCELED`
(or `FLAG_CANCELED | FLAG_CANCELED_LONG_PRESS`). The preview handler releases an
eligible receipt. Installed Foundation checks only event type and activation key;
it does not inspect Android cancellation flags. Both actual-node probes invoked
**one callback; expected zero**. The existing unit test calls `gate.cancel()`
explicitly and therefore does not cover this real adapter path.

Source: `Clickable.kt:658–678,1944–1983,1050–1054`; UI
`KeyEvent.android.kt:75–83`. Android explicitly says a
[canceled key-up must not perform the normal key action](https://developer.android.com/reference/android/view/KeyEvent#FLAG_CANCELED).
The long-press cancellation flag can accompany cancellation after another long-press
handler has already acted. This cancellation gap also exists in the baseline
Foundation-only path; the correction's claimed cancellation protection still needs
an adapter check.

**Bounded correction:** on canceled native key-up, cancel that input instead of
releasing it. Let Foundation finish its lifecycle while the gate rejects its
callback. Test both cancellation flag combinations and the next fresh down/up.

### 4. P2 — fresh indirect touch-navigation taps no longer activate

**Location:** `Focus.kt:64–93,104–105`.

Compose 1.12.1 supports focus-routed `IndirectPointerEvent` input from
`SOURCE_TOUCH_NAVIGATION`. It is separate from both ordinary `pointerInput` and
key events. Vela observes neither indirect down nor indirect up, so a valid,
continuously available indirect tap reaches Foundation's callback with no receipt
and is dropped. Actual-node probe: **zero callbacks; expected one**. With a stale
receipt, the same path instead admits an unrelated gesture (finding 2).

Source: `Clickable.kt:900–925,955–962`; UI
`AndroidComposeView.android.kt:2563–2589` and `IndirectPointerEvent.kt:23–40`.
This does not establish that the owner's particular remote emits that source.
It establishes a regression in an input route supported by the installed library.

**Bounded correction:** observe indirect input with the same gesture eligibility,
consumption and cancellation rules, or explicitly disable that route if its removal
is an intentional product decision. Do not let it opportunistically consume a
key/direct-pointer receipt. Preserve the existing focus target.

## Verified behavior and scope limits

- **Ordering is correct.** UI `FocusOwnerImpl.kt:352–374` dispatches all preview
  callbacks before regular key callbacks; Foundation invokes normal clickable on
  key-up synchronously. `HitPathTracker.kt:407–450` dispatches the observer's outer
  Initial pass before inner Main, and `awaitPointerEvent` resumes synchronously
  (`SuspendingPointerInputFilter.kt:86–96`). No asynchronous interaction-flow
  collection is relied upon for gate timing. This agrees with Android's
  [pointer pass documentation](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures).
- **Ordinary transitions pass.** Busy→ready and ready→busy→ready single-key/pointer
  holds reject. Repeats with uninterrupted focus cannot re-arm them. The next
  fresh key/pointer works. Pure release→availability-loss tests also pass.
- **Activation variants checked.** This modifier creates plain `ClickableNode`:
  `onClickKeyDownEvent` returns false; no long-click job is installed; a ready hold
  invokes once on up. The long/double/key-down behavior at `Clickable.kt:1518–1617`
  belongs to `CombinedClickableNode`, which the reviewed surface does not construct.
  Current activation keys are Center, Enter, numpad Enter and Space; all four were
  probed. Native Button A (96) is not accepted by this version's predicate, including
  before this correction; no gamepad mapping or device support is claimed.
- **Semantics precedence is correct.** LayoutNode applies same-node semantics
  tail-to-head (`LayoutNode.kt:468–484`). The outer Vela semantics therefore writes
  its action after clickable's action. `SemanticsConfiguration.kt:71–77` preserves
  the new non-null action/label. Actual configuration probes selected the Vela
  callback: enabled produced true/one accessibility callback/zero physical callbacks;
  disabled produced false/zero callbacks and retained `Disabled`. It is appropriately
  a fresh action independent of held input. Inspected design callers do not supply
  a conflicting outer onClick. TalkBack delivery/announcement remains unobserved.
- **Sole focus target is structurally preserved.** No new focusable was added;
  the caller modifier still precedes clickable; the interaction source is remembered.
  Busy maps to `enabled && !busy` in `Buttons.kt:32–33`, while Foundation enabled stays
  true. Its `updateCommon` only undelegates the focusable when Foundation enabled
  changes (`Clickable.kt:1736–1757`). No source-proven focus-target replacement on
  busy/disabled changes was found. Actual focus retention and visual state remain
  device acceptance items.

Uncertainty is limited to live platform routing/timing, parent gesture arbitration,
recomposition scheduling, hardware source/key mapping, accessibility delivery and
visual focus acceptance. The four findings above are reproduced/source-proven
paths under the stated event sequences; they do not assert a Runner authorization
bypass or any observed action on the physical Shield. Launcher, phone, fixture and
player peer work was outside this review.

## Coordinator correction awaiting handler verification

The coordinator replaced the coroutine observer with `ActivationInputNode`, which
observes key, direct-pointer and indirect-pointer routes around the same native
clickable/focus target. Only the four installed Foundation activation keys enter
the key gate; orphaned repeats cannot arm it and canceled key-up cancels it.
Pointer gestures remain bound until all pointers lift, while cancellation,
consumption and movement invalidate eligibility. A release is valid only during
its own synchronous event; Main/Final handling discards unused receipts.

All 26 pure design tests (including 12 gate regressions) and actual-source
design/overlay compilation pass. This is not yet proof against the four real
handler reproductions above; the follow-up harness must execute the new production
node and installed Foundation handlers together. Original review fingerprints
below continue to identify the reviewed first correction, not this later source.

## Reviewed source fingerprints (SHA-256)

- `ActivationGate.kt`: `3481a257234c288a250125d9d2279b12dd30e432c70898de3708de776b8cc46b`
- `Focus.kt`: `901a99573c3c036a52e11a43c4cd5c336f8724ac2bb09e0131c5b471f11aa63f`
- `ActivationGateTest.kt`: `00eb31d30181457f74129f3e88e6ff5bc43b5525f032832d8f1ce267248e3212`
