# Actual activation-handler regression harness

From the repository root:

```sh
python3 dev/design-input-tests/run.py
python3 dev/design-input-tests/run.py --case busy-focus-loss-orphan-repeat
python3 dev/design-input-tests/run.py --case canceled-up
python3 dev/design-input-tests/run.py --case busy-two-pointer
python3 dev/design-input-tests/run.py --case cannot-authorize
python3 dev/design-input-tests/run.py --case indirect/ready
python3 dev/design-input-tests/run.py --case expiry/
python3 dev/design-input-tests/run.py --case semantics/
```

The full suite currently runs **72 cases / 264 explicit assertions**. Each case
also enforces uncounted fixture/dispatch preconditions with Kotlin `check`.
Every failure prints the case and exception, and exits nonzero. A filter matching
no cases also fails. `--case` is a literal, case-sensitive substring filter.

This uses Python's standard library, existing JDK 21, cached Kotlin 2.4.21,
Compose Foundation/UI/runtime 1.12.1, SDK 37.0 declarations, and the existing
mockable Android jar. All 33 dependency artifacts (including the two source
archives) have exact paths and SHA-256 checks in `dependencies.json`. Additional
runtime carriers need cached collection 1.5.0, coroutines 1.11.0, core 1.18.0,
core-viewtree 1.0.0, lifecycle 2.9.4, savedstate 1.4.0, customview/poolingcontainer
1.0.0 and activity 1.13.0; annotations is 23.0.0 and compiler-reflect is 1.6.10.
No project dependency or environment setting changes. No Gradle or network access.

Defaults match the task machine:

```sh
python3 dev/design-input-tests/run.py \
  --jdk /opt/homebrew/opt/openjdk@21 \
  --sdk /opt/homebrew/share/android-commandlinetools \
  --gradle-cache "$HOME/.gradle/caches" \
  --review-artifacts /tmp/vela-activation-review-kdlwjaat
```

`--review-artifacts` supplies the **matching upstream source archives only**. The
old copied observer, old compiled probe classes, and old classpath are not loaded.
Binary classes are extracted from the fingerprinted cached AARs. Missing/mismatched
dependencies fail clearly; the runner never downloads, upgrades, installs or falls
back to another version. This is deliberately pinned, not a general Compose-version
compatibility test. Retain the two source archives if cleaning the earlier temp
directory. Relocating it is supported through `--review-artifacts`.

On another checkout, obtain the matching official
[Foundation sources](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.12.1/foundation-android-1.12.1-sources.jar)
and [UI sources](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.12.1/ui-android-1.12.1-sources.jar),
save them as `foundation-sources.jar` and `ui-sources.jar` in a chosen directory,
and pass that directory as `--review-artifacts`. The runner verifies their pinned
hashes; the original private review directory is not required. Populate the
matching SDK/Gradle dependencies through the normal Android build first.

Each run creates a private `vela-design-input-*` temp directory and prints its
path. It contains compiled classes, extracted jars, commands, logs, bytecode
disassembly, selected upstream sources, source snapshots/hashes and the generated
semantics-only function. Nothing writes into the Android project or shared caches;
no daemon/server is started. Artifacts are retained for review, not auto-deleted.

The runner compiles **the current production `ActivationGate.kt` and
`ActivationInput.kt` unchanged**. `InputRegression.kt` instantiates that node and
calls its real key, pointer, indirect-pointer, focus and lifecycle interfaces.
It constructs installed `ClickableNode` reflectively because the class is internal,
then invokes its actual interface methods in the order established from pinned
upstream source/bytecode. There is no copied observer body or replacement gesture
state machine. Source hashes are checked again after execution to reject a run
whose production inputs changed while peers were editing.

`Focus.kt`'s current semantics lambda is extracted verbatim into a small generated
function and applied **after the real Foundation semantics implementation** to a
real `SemanticsConfiguration`. The composable itself is not rendered. Structural
source guards verify the sole always-enabled clickable, gate wiring, modifier order
and remembered gate. These checks intentionally fail for wiring changes needing
review, even if a textual refactor is behaviorally equivalent.

The JVM fixture supplies Android KeyEvent getters (11 constants checked against
the real SDK), a no-op Paint, the cached mockable Android classes, and a private
LayoutNode/coordinator with density 1 and touch slop 8. It manually sets attachment
state and bypasses only Foundation's gesture-coordination-node attachment. Null
interaction source and disabled click sound avoid rendering/coroutine effects.
Pointer samples, indirect-event proxies and focus notifications are synthetic.
Full routing uses outer Initial → inner Initial → inner Main → outer Main → outer
Final → inner Final. A consumed key stops bubbling; the harness does **not** force
the observer's regular-key callback after Foundation consumes it.

Receipt-isolation cases deliberately deliver partial passes or intercepted key
events to leave an unused completion before checking expiry. Positive controls
confirm the receipt exists in that synchronous window and is single-use. These
are direct interface tests, not assertions that a platform dispatches partial
passes in exactly those sequences.

See [the evidence report](../../docs/design/evidence/input-regression-harness.md)
for results and limits. This does not run Android input dispatch, a recomposer,
TalkBack, live focus traversal, parent gesture arbitration or visual acceptance.
The physical Shield remains paused and untouched.
