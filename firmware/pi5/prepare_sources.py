#!/usr/bin/env python3
"""Initialize a separate pinned Pi source tree. Never syncs or writes media."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent


def materialize(lock):
    upstream = lock['raspberryVanillaManifest']
    raw = upstream['rawXml'].encode()
    if hashlib.sha256(raw).hexdigest() != upstream['rawXmlSha256']:
        raise ValueError('Embedded upstream manifest checksum differs')
    tree = ET.fromstring(raw)
    expected = {p['path']: p for p in upstream['projects']}
    projects = tree.findall('project')
    if (len(projects) != 20 or len(expected) != 20 or
            set(expected) != {p.get('path') for p in projects}):
        raise ValueError('Incomplete Raspberry Vanilla project lock')
    for node in projects:
        pin = expected[node.get('path')]
        if node.get('name') != pin['name'] or not re.fullmatch('[0-9a-f]{40}', pin['commit']):
            raise ValueError('Project name or commit is invalid')
        node.set('upstream', pin['branch'])
        node.set('revision', pin['commit'])
    return ET.tostring(tree, encoding='utf-8', xml_declaration=True) + b'\n'


def initialize(source):
    source = source.resolve()
    if source.exists() and any(source.iterdir()):
        raise ValueError('Initialize only a new empty source directory; existing work is preserved')
    lock = json.loads((ROOT / 'upstream-lock.json').read_text())
    xml = materialize(lock)
    removed = (ROOT / 'remove_projects.xml').read_bytes()
    if hashlib.sha256(removed).hexdigest() != 'ead76d5894c43f9fa09c09d9712cb597c18953cffdbe3cdabca7e99dddb4064f':
        raise ValueError('remove_projects.xml checksum differs')
    source.mkdir(parents=True, exist_ok=True)
    base = lock['androidBase']
    subprocess.run(['repo', 'init', '-u', base['manifestUrl'], '-b', base['tag'],
                    '--depth=1', '--no-clone-bundle'], cwd=source, check=True)
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'],
                                      cwd=source / '.repo/manifests', text=True).strip()
    if revision != base['peeledCommit']:
        raise ValueError('AOSP manifest tag moved; refusing source synchronization')
    destination = source / '.repo/local_manifests'
    destination.mkdir(exist_ok=True)
    (destination / 'manifest_brcm_rpi.xml').write_bytes(xml)
    (destination / 'remove_projects.xml').write_bytes(removed)
    print(json.dumps({'source': str(source), 'baseManifest': revision,
                      'localManifestSha256': hashlib.sha256(xml).hexdigest()}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    initialize(parser.parse_args().source)
