#!/usr/bin/env python3
"""Freeze and stage the Pi 5 OS integration. Never operates on a device."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
REPO_ROOT = ROOT.parent.parent
MODULE = Path('vendor/vela/rpi5')
PRODUCT = Path('device/vela/rpi5')
PERMISSIONS = {'android.permission.WRITE_SECURE_SETTINGS', 'android.permission.MEDIA_CONTENT_CONTROL'}
MODIFICATIONS = {
    'BoardConfig.mk': ('TARGET_SCREEN_DENSITY := 240', 'TARGET_SCREEN_DENSITY := 480'),
    'vendor.prop': ('vendor.hwc.drm.force_mode=1920x1080', 'vendor.hwc.drm.force_mode=3840x2160'),
}


class BuildError(Exception):
    pass


def digest(data):
    return hashlib.sha256(data).hexdigest()


def sha256(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def encode_json(value):
    return (json.dumps(value, sort_keys=True, indent=2) + '\n').encode()


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.vela-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def command(args, cwd=None):
    return subprocess.check_output([str(a) for a in args], cwd=cwd, text=True).strip()


def locked():
    value = json.loads((ROOT / 'upstream-lock.json').read_text())
    if (value.get('schemaVersion') != 1 or value['target']['velaProduct'] != 'vela_rpi5_tv'
            or value['target']['architecture']['arch'] != 'arm64'):
        raise BuildError('Unsupported Pi 5 upstream lock')
    return value


def projects(xml):
    root = ET.fromstring(xml)
    nodes = root.findall('project')
    result = {node.get('path', node.get('name')): node.get('revision') for node in nodes}
    if (not nodes or len(result) != len(nodes) or root.findall('include') or root.findall('submanifest')
            or any(not re.fullmatch('[0-9a-f]{40}', rev or '') for rev in result.values())):
        raise BuildError('Resolved manifest must contain unique projects at exact commits')
    lock = locked()['raspberryVanillaManifest']
    raw = ET.fromstring(lock['rawXml'])
    remotes = {node.get('name'): node.get('fetch') for node in root.findall('remote')}
    nodes_by_path = {node.get('path', node.get('name')): node for node in nodes}
    original_nodes = {node.get('path'): node for node in raw.findall('project')}
    def files(node):
        return sorted((child.tag, tuple(sorted(child.attrib.items()))) for child in node
                      if child.tag in ('linkfile', 'copyfile'))
    for project in lock['projects']:
        node = nodes_by_path.get(project['path'])
        if (node is None or node.get('name') != project['name'] or
                remotes.get(node.get('remote'), '').rstrip('/') != 'https://github.com' or
                result.get(project['path']) != project['commit'] or
                files(node) != files(original_nodes[project['path']])):
            raise BuildError('Raspberry Vanilla project differs from upstream lock: ' + project['path'])
    return result


def source_root(source):
    source = source.resolve()
    if source == REPO_ROOT or not (source / '.repo').is_dir():
        raise BuildError('Use a separate Android repo checkout')
    base = command(['git', 'rev-parse', 'HEAD'], source / '.repo/manifests')
    if base != locked()['androidBase']['peeledCommit']:
        raise BuildError('AOSP manifest commit differs from upstream lock')
    return source


def safe_path(source, relative):
    path = source / relative
    for candidate in (path, *path.parents):
        if candidate == source:
            break
        if candidate.is_symlink():
            raise BuildError('Refusing a symlink in managed path: ' + str(candidate))
    if not path.resolve().is_relative_to(source):
        raise BuildError('Managed path escapes source tree')
    return path


def freeze(source, snapshot):
    source = source_root(source)
    if snapshot.exists():
        raise BuildError('Source snapshots are immutable; use a new directory')
    command(['repo', 'forall', '-c', 'git diff --quiet && git diff --cached --quiet'], source)
    manifest = (command(['repo', 'manifest', '-r'], source) + '\n').encode()
    resolved = projects(manifest)
    result = {'schema_version': 1, 'device': 'rpi5', 'manifest_sha256': digest(manifest),
              'upstream_lock_sha256': sha256(ROOT / 'upstream-lock.json'), 'project_count': len(resolved)}
    snapshot.mkdir(parents=True)
    atomic_write(snapshot / 'manifest.xml', manifest)
    atomic_write(snapshot / 'source-lock.json', encode_json(result))
    return result


def validate_apk(apk, aapt2, zipalign):
    expected = json.loads((ROOT / 'app-input.json').read_text())
    if apk.stat().st_size != expected['bytes'] or sha256(apk) != expected['sha256']:
        raise BuildError('APK differs from the frozen firmware input')
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or not {'AndroidManifest.xml', 'classes.dex'} <= set(names):
            raise BuildError('Invalid or ambiguous APK')
        native = [n for n in names if n.startswith('lib/arm64-v8a/') and n.endswith('.so')]
        if not native:
            raise BuildError('Pi 5 requires arm64 native libraries')
        for name in native:
            header = archive.read(name)[:20]
            if header[:6] != b'\x7fELF\x02\x01' or int.from_bytes(header[18:20], 'little') != 183:
                raise BuildError('Not an ELF64 AArch64 native library: ' + name)
    badging = command([aapt2, 'dump', 'badging', apk])
    if not re.search(r"^package: name='app\.vela\.tv' ", badging, re.M) or 'application-debuggable' in badging:
        raise BuildError('Expected non-debuggable app.vela.tv firmware APK')
    if any(f"uses-permission: name='{p}'" not in badging for p in PERMISSIONS):
        raise BuildError('Firmware APK is missing privileged permissions')
    command([zipalign, '-c', '-P', '16', '4', apk])
    return {**expected, 'native_library_count': len(native), 'zipalign_16k': 'passed'}


def planned_files(source, apk, snapshot, display, apk_info):
    state = json.loads((snapshot / 'source-lock.json').read_text())
    manifest = (snapshot / 'manifest.xml').read_bytes()
    if (state.get('device') != 'rpi5' or state.get('schema_version') != 1
            or state.get('manifest_sha256') != digest(manifest)
            or state.get('upstream_lock_sha256') != sha256(ROOT / 'upstream-lock.json')):
        raise BuildError('Invalid Pi source snapshot')
    current_manifest = (command(['repo', 'manifest', '-r'], source) + '\n').encode()
    projects(current_manifest)
    projects(manifest)
    if digest(current_manifest) != state['manifest_sha256']:
        raise BuildError('Complete source manifest changed since freezing')
    board_repo = source / 'device/brcm/rpi5'
    pin = next(p['commit'] for p in locked()['raspberryVanillaManifest']['projects']
               if p['path'] == 'device/brcm/rpi5')
    if command(['git', 'rev-parse', 'HEAD'], board_repo) != pin:
        raise BuildError('Pi device HEAD changed')
    modifications = {}
    plan = {}
    for name, (old, new) in MODIFICATIONS.items():
        original = subprocess.check_output(['git', 'show', f'{pin}:{name}'], cwd=board_repo)
        old, new = old.encode(), new.encode()
        if original.count(old) != 1 or new in original:
            raise BuildError('Pinned display configuration no longer matches expected baseline')
        changed = original.replace(old, new) if display == '4k' else original
        relative = Path('device/brcm/rpi5') / name
        path = safe_path(source, relative)
        # A managed prior stage is checked below; arbitrary edits are never replaced.
        if path.read_bytes() not in (original, changed):
            raise BuildError('Unrecognized Pi display edit: ' + str(path))
        plan[relative] = changed
        modifications[str(relative)] = {'original_sha256': digest(original), 'staged_sha256': digest(changed)}
    changes = command(['git', 'diff', '--name-only', 'HEAD'], board_repo).splitlines()
    if set(changes) - set(MODIFICATIONS):
        raise BuildError('Unexpected tracked Pi device changes')
    for relative in ['Android.bp', 'permissions/privapp-permissions-app.vela.tv.xml',
                     'preferred-apps/vela-home.xml']:
        plan[MODULE / relative] = (ROOT / 'product' / relative).read_bytes()
    for relative in ['AndroidProducts.mk', 'vela_rpi5_tv.mk']:
        plan[PRODUCT / relative] = (ROOT / 'product' / relative).read_bytes()
    plan[MODULE / 'VelaTv.apk'] = apk.read_bytes()
    marker = {'schema_version': 1, 'device': 'rpi5', 'product': 'vela_rpi5_tv',
              'display': {'mode': '3840x2160' if display == '4k' else '1920x1080',
                          'density': 480 if display == '4k' else 240,
                          'hardware_verified': False},
              'apk': apk_info, 'source_manifest_sha256': state['manifest_sha256'],
              'upstream_lock_sha256': state['upstream_lock_sha256'],
              'vela_commit': command(['git', 'rev-parse', 'HEAD'], REPO_ROOT),
              'integration_files': {str(p): digest(data) for p, data in plan.items()},
              'upstream_modifications': modifications}
    plan[MODULE / 'vela-staging.json'] = encode_json(marker)
    transaction_path = safe_path(source, Path('.vela/pi5-staging.pending.json'))
    hashes = {str(p): digest(data) for p, data in plan.items()}
    pending = json.loads(transaction_path.read_text()) if transaction_path.exists() else None
    recovering = pending == {'schema_version': 1, 'device': 'rpi5',
                             'product': 'vela_rpi5_tv', 'files': hashes}
    if pending is not None and not recovering:
        raise BuildError('Pending staging transaction belongs to different inputs; preserve it for inspection')
    # Establish ownership of all managed files before any write, including restart.
    previous_path = safe_path(source, MODULE / 'vela-staging.json')
    previous = json.loads(previous_path.read_text()) if previous_path.exists() else None
    if previous and (previous.get('device') != 'rpi5' or previous.get('schema_version') != 1
                     or previous.get('product') != 'vela_rpi5_tv'
                     or not isinstance(previous.get('integration_files'), dict)
                     or set(previous['integration_files']) != set(marker['integration_files'])
                     or any(not re.fullmatch('[0-9a-f]{64}', h or '')
                            for h in previous['integration_files'].values())):
        raise BuildError('Foreign staging record')
    for directory in (MODULE, PRODUCT):
        destination = safe_path(source, directory)
        for path in destination.rglob('*') if destination.exists() else []:
            relative = path.relative_to(source)
            safe_path(source, relative)
            if path.is_file():
                if recovering and str(relative) in hashes and sha256(path) == hashes[str(relative)]:
                    continue
                if relative not in plan or previous is None:
                    raise BuildError('Existing generated source is not owned by this stager: ' + str(path))
                expected_hash = previous['integration_files'].get(str(relative))
                if relative != MODULE / 'vela-staging.json' and sha256(path) != expected_hash:
                    raise BuildError('Managed file was modified: ' + str(path))
    if changes and previous is None and not recovering:
        raise BuildError('Display edits have no matching staging record')
    for path in plan:
        safe_path(source, path)
    return plan, marker


def stage(source, apk, snapshot, aapt2, zipalign, display):
    source = source_root(source)
    apk_info = validate_apk(apk, aapt2, zipalign)
    plan, marker = planned_files(source, apk, snapshot, display, apk_info)
    transaction_path = safe_path(source, Path('.vela/pi5-staging.pending.json'))
    atomic_write(transaction_path, encode_json({'schema_version': 1, 'device': 'rpi5',
                                              'product': 'vela_rpi5_tv',
                                              'files': {str(p): digest(data) for p, data in plan.items()}}))
    for relative, data in plan.items():
        atomic_write(source / relative, data)
    transaction_path.unlink()
    return marker


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest='action', required=True)
    freeze_parser = actions.add_parser('freeze')
    stage_parser = actions.add_parser('stage')
    for action in (freeze_parser, stage_parser):
        action.add_argument('--source', type=Path, required=True)
        action.add_argument('--snapshot', type=Path, required=True)
    stage_parser.add_argument('--apk', type=Path, required=True)
    stage_parser.add_argument('--aapt2', type=Path, required=True)
    stage_parser.add_argument('--zipalign', type=Path, required=True)
    stage_parser.add_argument('--display', choices=['4k', '1080p'], default='4k')
    args = parser.parse_args()
    if args.action == 'freeze':
        result = freeze(args.source, args.snapshot)
    else:
        result = stage(args.source, args.apk, args.snapshot, args.aapt2, args.zipalign, args.display)
    print(json.dumps(result, indent=2, sort_keys=True))
