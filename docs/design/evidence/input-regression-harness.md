# Production input-handler regression harness

2026-10-31. **72 cases, 264 explicit assertions, zero failures. No actionable
current-source production defect was proven within this harness's scope.** The
four original failure sequences in [the earlier review](activation-gate-review.md)
now produce the expected callbacks when the **actual current
`ActivationInputNode`** executes alongside installed Foundation **1.12.1** handlers.
This closes the bounded handler-replay gap; it is not live device acceptance.

Only new `dev/design-input-tests/**` and this evidence note were written in the
checkout. Production, existing tests, contracts and other workers' paths remained
read-only. No Git mutations, shared Gradle, peer/delegation interaction, browser,
GUI, device, SSH, infrastructure, firmware or credential work was performed.
**The physical Shield remains paused by the owner and was untouched.** Root
retains combined Gradle verification, visual acceptance and PR delivery.

## Run and dependency constraints

From `/Users/kdegeek/.t3/worktrees/project-vela/t3-5311468e`:

```sh
python3 dev/design-input-tests/run.py
```

Focused commands (substring filters, each recompiles current production):

```sh
python3 dev/design-input-tests/run.py --case busy-focus-loss-orphan-repeat
python3 dev/design-input-tests/run.py --case canceled-up
python3 dev/design-input-tests/run.py --case busy-two-pointer
python3 dev/design-input-tests/run.py --case cannot-authorize
python3 dev/design-input-tests/run.py --case indirect/ready
python3 dev/design-input-tests/run.py --case expiry/
python3 dev/design-input-tests/run.py --case semantics/
```

The standard-library Python runner uses existing JDK **21** (observed Homebrew
21.0.13.0.3), Kotlin compiler/stdlib **2.4.21**, Compose Foundation/UI/runtime
**1.12.1**, SDK **37.0** declarations, and the previously cached mockable Android
jar. `dependencies.json` locks **33 artifacts** by exact path and SHA-256, including
the matching Foundation/UI source archives from
`/tmp/vela-activation-review-kdlwjaat`. Other required cached versions are
coroutines 1.11.0, collection 1.5.0, annotations 23.0.0, compiler-reflect 1.6.10,
core/core-ktx 1.18.0, core-viewtree 1.0.0, lifecycle 2.9.4, savedstate 1.4.0,
customview/poolingcontainer 1.0.0, and activity 1.13.0.

The runner validates every artifact before compilation; unavailable or changed
dependencies fail nonzero without fetching alternatives. `--jdk`, `--sdk`,
`--gradle-cache` and `--review-artifacts` relocate the existing inputs. See the
[README](../../../dev/design-input-tests/README.md) for full commands. The two
review source archives must be retained or relocated; the harness does not rely
on the prior review's compiled probes, copied observer, or extracted dependency
jars. No installation, dependency addition, environment change or Gradle task is
needed. All build output goes to a new task-private temp directory. No server or
daemon is started.

Final full-run artifacts:
`/var/folders/tc/f5wf7p2n68z7htvy4dr283sw0000gn/T/vela-design-input-utmice36/`.
`results.log` ends with `RESULT cases=72 assertions=264 failures=0`;
`compile.log` is empty (successful compilation without warnings).
Commands, classpath, production snapshots/hashes, dependency lock, relevant
upstream sources and `javap -c -p` disassemblies are retained there. Production
hashes were unchanged before/after the run. Existing pure tests and full design
compilation were not rerun or counted as handler evidence.

## What actually executes

The runner compiles production `ActivationGate.kt` and **the complete unchanged
`ActivationInput.kt`** against the real SDK declarations. Kotlin calls the new
node's actual `onPreKeyEvent`/`onKeyEvent`, `onPointerEvent` Initial/Main/Final,
`onIndirectPointerEvent` Initial/Main/Final, cancellation, focus and lifecycle
interfaces. There is **no observer body copied into the probe**. Installed
`ClickableNode`/`AbstractClickableNode` are constructed reflectively and invoked
through their real input interfaces, including the actual click callback.

The callback fixture preserves `enabled && gate.takeClick()` and separately
counts Foundation's attempted clicks and authorized application callbacks. That
distinguishes a rejected click from a sequence that never reached Foundation.

Dispatch order was checked against the matching installed source and bytecode:

- `FocusOwnerImpl.kt:352–374`: outer preview before inner regular callbacks;
  consumed events stop bubbling. The harness **does not** call the outer regular
  key handler after Foundation consumes key-up. Unhandled key events reach it.
- `HitPathTracker.kt:407–469`: Initial tunnels, Main bubbles, Final tunnels.
  Full direct routing is observer Initial → Foundation Initial → Foundation Main
  → observer Main → observer Final → Foundation Final.
- `FocusOwnerImpl.kt:427–475`: indirect events use the same pass directions;
  indirect cancellation bubbles. Both actual indirect handlers execute.
- `Clickable.kt:870–1025,1944–1983`: direct/indirect click callbacks and ordinary
  key-up callbacks run synchronously. Pointer lifetimes end when all changes
  are up; Main/Final consumption can cancel held input.

The private fixture supplies a non-rendering LayoutNode/coordinator, fixed
density/view configuration (density 1, touch slop 8), Android KeyEvent getters
and no-op Paint. **Eleven key constants are checked against SDK37**. The real
Compose pointer-change objects retain consumption state between passes; indirect
events use interface proxies around real change objects. The fixture manually
sets attachment state and bypasses Foundation's gesture-coordination-node
attachment. Null interaction source and disabled click sound prevent rendering
and asynchronous interaction effects. These JVM-local choices do not alter the
Mac or Android platform environment.

## Regression results

- **Original P1: unavailable key hold → focus loss → readiness/refocus → orphaned
  repeat → up.** Zero authorized callbacks; Foundation attempts the up callback.
  The next fresh down/up invokes once. Replayed for Center (23), Enter (66),
  numpad Enter (160), and Space (62), including repeat count and long-press flag.
- **Original P2: canceled key-up.** Flags 32 (`FLAG_CANCELED`), 256
  (`FLAG_CANCELED_LONG_PRESS`), and 288 (both) each reject Foundation's attempted
  click for all four keys. Subsequent fresh down/up succeeds. Canceled down,
  repeat-only arrival and focus loss without a repeat also reject.
- **Original P2: busy two-pointer gesture → readiness → first up → Shift
  down/up → last up.** No receipt at first up; Foundation attempts completion
  only at the final up; zero authorized callbacks. Fresh direct tap succeeds.
  Continuously ready two-pointer input invokes once at final up. Busy indirect
  two-pointer input plus Shift also rejects.
- **Original P2: indirect route.** A continuously ready indirect tap invokes
  once; a held indirect gesture does not invoke before up. Duplicate up does
  not replay. Busy→ready and ready→busy→ready reject; next fresh tap succeeds.
- Direct explicit cancel, consumed synthetic up, consumed drag, out-of-bounds
  movement, Main consumption and Final consumption leave no usable receipt and
  cannot authorize an overlapping busy indirect press after readiness returns.
  Both routes recover for the next fresh gesture. Indirect cancel, focus loss,
  slop, consumed up and Main/Final consumption likewise reject and recover.
- Ordinary key/direct/indirect holds, repeated key downs and availability
  loss/recovery remain correct, including availability changes between observer
  Initial release and Foundation Main dispatch. Focus loss leaves an independent
  direct-pointer gesture eligible while canceling keys/indirect input.
- Unused receipts expire through normal unhandled-key and pointer passes.
  Direct and indirect Main and Final cleanup are checked separately. All six
  ordered cross-route combinations clear a pending completion. Positive controls
  confirm an eligible receipt exists before cleanup and is single-use. Unused
  direct release and intercepted key release cannot authorize busy indirect up.
- Density/configuration cancellation, reset, detach, and replacing the gate
  disarm the corresponding held inputs; next fresh gestures work. Shift and
  Button A remain unhandled by the installed activation predicate. **No new
  gamepad mapping or hardware support is claimed.**

The 264 count includes explicit outcome assertions only; fixture preconditions
and dispatch-consumption `check` calls are additional. Case failures are
aggregated and cause a nonzero process exit, not just diagnostic printing.

Failure-path self-checks also passed: a missing cache exits nonzero with the exact
missing artifact; selecting no cases exits nonzero; and a **temporary copy of the
harness only**, with a deliberately wrong down-consumption expectation, reports
`expected=false, actual=true`, `failures=1`, and exits nonzero. Production and the
checked-in harness expectations were unchanged. Logs are in
`/var/folders/tc/f5wf7p2n68z7htvy4dr283sw0000gn/T/vela-input-failure-checks-ot3r_m14/`
and
`/var/folders/tc/f5wf7p2n68z7htvy4dr283sw0000gn/T/vela-input-assertion-selfcheck-35sjejiw/`.

## Semantics and the sole focus target

`Focus.kt:35–37,53–68` remembers the gate/source, updates availability, places the
observer before outer Vela semantics, and keeps exactly one Foundation clickable
with `enabled = true`. `ActivationInputNode` adds input/focus observation only;
it does not create another focus target. Installed `Clickable.kt:1661–1666,
1736–1757,1792–1800` owns/delegates its one FocusableNode and changes delegation
when its own enabled value changes. No source-proven focus-target replacement
on Vela availability changes was found. Runner source guards preserve this
structure; **actual focus retention was not exercised**.

`LayoutNode.kt:468–484` applies same-node semantics tail-to-head, and
`SemanticsConfiguration.kt:71–77` gives a newly written non-null action precedence.
The test calls the installed clickable's real `applySemantics`, then a generated
function containing **the current `Focus.kt:57–64` semantics body verbatim**.
The resulting real configuration selects Vela's label/action. Disabled returns
false, carries Disabled and invokes neither callback. Rebuilding semantics with
current enabled=true returns true and invokes only the accessibility callback,
even while an ineligible physical key is held. That physical release still
rejects; the next physical gesture succeeds. Selected semantics is retained.

This extraction is limited to the semantics lambda: it does not render or execute
the complete `VelaFocusSurface` composable, and is distinct from the input node,
which is compiled whole without copying or rewriting its implementation.

## Limits and disposition

**Ranked actionable findings: none proven within scope.** No production correction
is requested from this harness. Earlier findings are resolved for their replayed
handler sequences; this does not assert universal input correctness.

Manual pass routing bypasses the live hit-test/focus dispatch tree, FocusOwner's
key validation, parent gesture arbitration and real gesture-coordination attachment.
Receipt-isolation tests intentionally omit later passes or intercept key callbacks
to inspect a synchronous completion window; they are interface-level proofs, not
claims that Android delivers those partial sequences. Focus notifications and
pointer/key events are simulated, not platform service input.

No Android input service, actual MotionEvent conversion, hardware key/source
mapping, render tree, live focus traversal, interaction-flow visuals, recomposer
or accessibility service runs. Availability is updated synchronously by the
fixture. Semantics is rebuilt explicitly for the current enabled value, so this
does not test stale accessibility closures, recomposition scheduling, abandoned
composition, or TalkBack announcement/delivery timing. Visual focus, retention
and timing remain coordinator acceptance items once the owner resumes testing.

## Current production fingerprints (SHA-256)

- `ActivationGate.kt`: `4646e68aa7c28e396131cda356157de0b5105ea5d34dc53f8297e9f3721cfcd9`
- `ActivationInput.kt`: `9932bbecbf67b75e8a3e66a70e0c0986997f8c913cf32a807b7ec98b99f5db41`
- `Focus.kt`: `ee581615c7c6c27ff44d2d7ee27b6f4d9698cce0416076376acda4a2648eb852`
