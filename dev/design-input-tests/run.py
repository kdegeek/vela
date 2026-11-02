#!/usr/bin/env python3
"""Offline actual-handler regressions; no Gradle, downloads, devices, or project writes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run(args, destination, quiet=False):
    destination.with_suffix('.command.json').write_text(json.dumps([str(x) for x in args], indent=2) + '\n')
    result = subprocess.run([str(x) for x in args], text=True, capture_output=True, timeout=120)
    destination.write_text(result.stdout + result.stderr)
    if not quiet or result.returncode:
        print(result.stdout + result.stderr, end="", flush=True)
    if result.returncode:
        raise RuntimeError(f"exit {result.returncode}: {args[0]} (details: {destination})")
    return result.stdout + result.stderr


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle-cache', type=Path, default=Path.home() / '.gradle/caches')
    parser.add_argument('--review-artifacts', type=Path, default=Path('/tmp/vela-activation-review-kdlwjaat'))
    parser.add_argument('--sdk', type=Path, default=Path('/opt/homebrew/share/android-commandlinetools'))
    parser.add_argument('--jdk', type=Path, default=Path('/opt/homebrew/opt/openjdk@21'))
    parser.add_argument('--case', help='run only cases whose names contain this string')
    args = parser.parse_args()
    roots = dict(gradle=args.gradle_cache, review=args.review_artifacts, sdk=args.sdk)
    dependencies = json.loads((HERE / 'dependencies.json').read_text())
    resolved = {}
    for item in dependencies:
        path = roots[item['root']] / item['path']
        if not path.is_file():
            raise RuntimeError(f"required dependency unavailable: {item['name']} at {path}; no downloads attempted")
        if sha(path) != item['sha256']:
            raise RuntimeError(f"dependency SHA-256 mismatch: {item['name']} at {path}")
        resolved[item['name']] = path
    for tool in ('java', 'javac', 'javap'):
        if not (args.jdk / 'bin' / tool).is_file():
            raise RuntimeError(f"required JDK 21 tool unavailable: {args.jdk / 'bin' / tool}")

    work = Path(tempfile.mkdtemp(prefix='vela-design-input-'))
    print(f'ARTIFACTS {work}', flush=True)
    classes = work / 'classes'
    classes.mkdir()
    java = args.jdk / 'bin/java'
    version = run([java, '-version'], work / 'java-version.log')
    if not re.search(r'version "21[.\"]', version):
        raise RuntimeError('JDK 21 required')
    (work / 'dependencies.json').write_text(json.dumps(dependencies, indent=2) + '\n')
    jars = {}
    for name, path in resolved.items():
        if path.suffix == '.aar':
            destination = work / (name.split(':')[1] + '.jar')
            with zipfile.ZipFile(path) as archive:
                destination.write_bytes(archive.read('classes.jar'))
            jars[name] = destination
        elif name not in ('android-sdk-37', 'foundation-sources.jar', 'ui-sources.jar'):
            jars[name] = path
    compile_only = ('kotlin-compiler-embeddable', 'kotlin-reflect')
    cp = os.pathsep.join(str(p) for n, p in jars.items() if not any(x in n for x in compile_only))
    compiler_cp = os.pathsep.join(str(p) for p in jars.values())
    # Compile production against real SDK declarations, then substitute getter carriers only at runtime.
    compile_cp = os.pathsep.join([str(resolved['android-sdk-37'])] + [str(p) for n, p in jars.items()
                                  if n != 'mockable-android' and not any(x in n for x in compile_only)])
    (work / 'classpath.txt').write_text(cp)
    sdk_constants = run([args.jdk / 'bin/javap', '-constants', '-classpath', resolved['android-sdk-37'],
                         'android.view.KeyEvent'], work / 'sdk-keyevent.txt', quiet=True)
    stub = (HERE / 'android/view/KeyEvent.java').read_text()
    constants = re.findall(r'(ACTION_\w+|FLAG_\w+|KEYCODE_\w+)\s*=\s*(\d+)', stub)
    for name, value in constants:
        if not re.search(rf'\b{name} = {value};', sdk_constants):
            raise RuntimeError(f'key carrier constant differs from SDK: {name}={value}')
    print(f'PASS Android KeyEvent carrier: {len(constants)} SDK constants verified')

    # Record the pinned library evidence for dispatch ordering and semantics; no web needed.
    evidence = {}
    for archive_name, names in {
        'foundation-sources.jar': ['commonMain/androidx/compose/foundation/Clickable.kt',
                                   'commonMain/androidx/compose/foundation/Focusable.kt'],
        'ui-sources.jar': ['commonMain/androidx/compose/ui/focus/FocusOwnerImpl.kt',
                          'commonMain/androidx/compose/ui/input/pointer/HitPathTracker.kt',
                          'commonMain/androidx/compose/ui/node/LayoutNode.kt',
                          'commonMain/androidx/compose/ui/semantics/SemanticsConfiguration.kt'],
    }.items():
        with zipfile.ZipFile(resolved[archive_name]) as archive:
            for name in names:
                text = archive.read(name).decode()
                (work / Path(name).name).write_text(text)
                evidence[name] = hashlib.sha256(text.encode()).hexdigest()
    for name in ('androidx.compose.foundation.ClickableNode', 'androidx.compose.foundation.AbstractClickableNode',
                 'androidx.compose.ui.focus.FocusOwnerImpl', 'androidx.compose.ui.input.pointer.Node',
                 'androidx.compose.ui.semantics.SemanticsConfiguration'):
        result = subprocess.run([str(args.jdk / 'bin/javap'), '-c', '-p', '-classpath', cp, name],
                                text=True, capture_output=True, timeout=60)
        if result.returncode:
            raise RuntimeError(f'cannot inspect pinned bytecode {name}: {result.stderr}')
        (work / (name.rsplit('.', 1)[1] + '.bytecode.txt')).write_text(result.stdout)

    production = REPO / 'android/app/src/main/java/app/vela/tv/design'
    hashes = {name: sha(production / name) for name in ('ActivationGate.kt', 'ActivationInput.kt', 'Focus.kt')}
    for name in hashes:
        (work / name).write_bytes((production / name).read_bytes())
    # Execute the exact current semantics body in isolation; the whole composable is NOT rendered.
    focus = (production / 'Focus.kt').read_text()
    start = focus.index('.semantics {') + len('.semantics {')
    depth, end = 1, start
    while depth:
        if focus[end] == '{': depth += 1
        if focus[end] == '}': depth -= 1
        end += 1
    body = focus[start:end - 1]
    generated = work / 'ProductionSemantics.kt'
    generated.write_text('package app.vela.tv.design\nimport androidx.compose.ui.semantics.*\n'
                         'internal fun SemanticsPropertyReceiver.productionSemantics(enabled: Boolean, '
                         'selected: Boolean?, onClickLabel: String?, onClick: () -> Unit) {\n' + body + '\n}\n')
    # These checks are review guards, not a claim of live focus/recomposition validation.
    require(focus.count('.clickable(') == 1, 'expected one clickable')
    require('.focusable(' not in focus and '.focusTarget(' not in focus, 'additional focus target')
    require(focus.index('.guardActivation(activation)') < start < focus.index('.clickable('), 'observer/semantics order changed')
    require('indication = null, enabled = true' in focus, 'Foundation focus target must stay enabled')
    require('if (enabled && activation.takeClick()) onClick()' in focus, 'physical gate wiring changed')
    require('val activation = remember { ActivationGate() }' in focus, 'gate must be remembered')
    require('activation.availability(enabled)' in focus, 'availability wiring changed')
    print('PASS source wiring: sole stable clickable; observer/semantics order; physical gate')
    (work / 'source-sha256.json').write_text(json.dumps(dict(production=hashes, upstream=evidence), indent=2) + '\n')
    run([args.jdk / 'bin/javac', '-d', classes, HERE / 'android/view/KeyEvent.java',
         HERE / 'android/graphics/Paint.java'], work / 'compile-carriers.log')
    compile_args = [java, '-cp', compiler_cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-jvm-target', '11', '-classpath', compile_cp,
                    '-d', classes, production / 'ActivationGate.kt', production / 'ActivationInput.kt',
                    HERE / 'InputRegression.kt', generated]
    run(compile_args, work / 'compile.log')
    # Carrier's compact constructor is invoked reflectively; production uses real Android getters.
    command = [java, '-ea', '-cp', str(classes) + os.pathsep + cp, 'app.vela.tv.design.InputRegressionKt']
    if args.case: command.append(args.case)
    run(command, work / 'results.log')
    if any(sha(production / name) != digest for name, digest in hashes.items()):
        raise RuntimeError('production source changed during the run; rerun against a stable snapshot')
    print(f'PASS source snapshot unchanged; artifacts: {work}')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, AssertionError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        print(f'FAIL {error}', file=sys.stderr)
        sys.exit(1)
