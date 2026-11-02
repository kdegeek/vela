"""Regression checks for wrong-target, input drift and managed-source ownership."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent

def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

pi = load('vela_pi5')
prepare = load('prepare_sources')
LOCK = pi.locked()
PIN = next(p['commit'] for p in LOCK['raspberryVanillaManifest']['projects'] if p['path'] == 'device/brcm/rpi5')
MANIFEST = prepare.materialize(LOCK).strip() + b'\n'
ORIGINALS = {'BoardConfig.mk': b'TARGET_ARCH := arm64\nTARGET_SCREEN_DENSITY := 240\n',
             'vendor.prop': b'vendor.hwc.drm.force_mode=1920x1080\n'}

class PiIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.source = Path(self.tmp.name).resolve() / 'source'
        self.source.mkdir()
        self.snapshot = Path(self.tmp.name) / 'snapshot'
        self.snapshot.mkdir()
        (self.snapshot / 'manifest.xml').write_bytes(MANIFEST)
        (self.snapshot / 'source-lock.json').write_bytes(pi.encode_json({
            'device': 'rpi5', 'schema_version': 1, 'manifest_sha256': pi.digest(MANIFEST),
            'upstream_lock_sha256': pi.sha256(ROOT / 'upstream-lock.json')}))
        self.board = self.source / 'device/brcm/rpi5'
        self.board.mkdir(parents=True)
        for name, raw in ORIGINALS.items():
            (self.board / name).write_bytes(raw)
        self.apk = Path(self.tmp.name) / 'input.apk'
        self.apk.write_bytes(b'fixture payload')
        self.apk_info = {'sha256': pi.sha256(self.apk)}
        def command(args, cwd=None):
            if args[:2] == ['repo', 'manifest']:
                return MANIFEST.decode().strip()
            if args == ['git', 'rev-parse', 'HEAD']:
                return PIN if cwd == self.board else 'a' * 40
            if args[:2] == ['git', 'diff']:
                return '\n'.join(n for n, data in ORIGINALS.items() if (self.board / n).read_bytes() != data)
            raise AssertionError(args)
        self.commands = patch.object(pi, 'command', side_effect=command)
        self.commands.start()
        self.addCleanup(self.commands.stop)
        self.gitshow = patch.object(pi.subprocess, 'check_output',
                                   side_effect=lambda args, cwd=None: ORIGINALS[args[2].split(':')[1]])
        self.gitshow.start()
        self.addCleanup(self.gitshow.stop)

    def plan(self):
        return pi.planned_files(self.source, self.apk, self.snapshot, '4k', self.apk_info)

    def materialize(self):
        plan, marker = self.plan()
        for relative, raw in plan.items():
            pi.atomic_write(self.source / relative, raw)
        return plan, marker

    def test_pin_manifest_preserves_linkfiles_and_exact_refs(self):
        root = ET.fromstring(MANIFEST)
        self.assertEqual(len(root.findall('project')), 20)
        self.assertEqual(len(root.findall("project[@path='device/brcm/rpi5']/linkfile")), 2)
        self.assertEqual(pi.projects(MANIFEST)['device/brcm/rpi5'], PIN)

    def test_manifest_rejects_corrupt_embedded_xml(self):
        lock = copy.deepcopy(LOCK)
        lock['raspberryVanillaManifest']['rawXml'] += ' '
        with self.assertRaises(ValueError):
            prepare.materialize(lock)

    def test_manifest_rejects_missing_and_duplicate_pins(self):
        lock = copy.deepcopy(LOCK)
        lock['raspberryVanillaManifest']['projects'].pop()
        with self.assertRaises(ValueError):
            prepare.materialize(lock)
        root = ET.fromstring(MANIFEST)
        root.append(copy.deepcopy(root.find('project')))
        with self.assertRaises(pi.BuildError):
            pi.projects(ET.tostring(root))

    def test_wrong_pi_revision_rejected(self):
        root = ET.fromstring(MANIFEST)
        root.find("project[@path='device/brcm/rpi5']").set('revision', 'f' * 40)
        with self.assertRaises(pi.BuildError):
            pi.projects(ET.tostring(root))

    def test_wrong_repository_remote_and_linkfile_rejected(self):
        for mutation in ('name', 'remote', 'linkfile'):
            root = ET.fromstring(MANIFEST)
            node = root.find("project[@path='device/brcm/rpi5']")
            if mutation == 'name':
                node.set('name', 'other/repository')
            elif mutation == 'remote':
                root.find("remote[@name='github']").set('fetch', 'https://other.invalid/')
            else:
                node.find('linkfile').set('dest', 'another-script.sh')
            with self.assertRaises(pi.BuildError):
                pi.projects(ET.tostring(root))

    def test_4k_plan_preserves_geometry_and_uses_pi_product(self):
        plan, marker = self.plan()
        self.assertEqual(plan[Path('device/brcm/rpi5/BoardConfig.mk')],
                         ORIGINALS['BoardConfig.mk'].replace(b'240', b'480'))
        product = plan[pi.PRODUCT / 'vela_rpi5_tv.mk']
        self.assertIn(b'device/brcm/rpi5/aosp_rpi5_tv.mk', product)
        self.assertNotIn(b'PRODUCT_BUILD_USERDATA_IMAGE', product)
        self.assertEqual(marker['device'], 'rpi5')
        self.assertEqual(marker['display']['mode'], '3840x2160')

    def test_idempotent_stage_retains_exact_inputs(self):
        plan, marker = self.materialize()
        second, other = self.plan()
        self.assertEqual(plan, second)
        self.assertEqual(marker, other)

    def test_matching_pending_stage_can_resume_after_partial_writes(self):
        plan, _ = self.plan()
        transaction = {'schema_version': 1, 'device': 'rpi5', 'product': 'vela_rpi5_tv',
                       'files': {str(p): pi.digest(data) for p, data in plan.items()}}
        pi.atomic_write(self.source / '.vela/pi5-staging.pending.json', pi.encode_json(transaction))
        for relative, raw in list(plan.items())[:3]:
            pi.atomic_write(self.source / relative, raw)
        resumed, _ = self.plan()
        self.assertEqual(plan, resumed)

    def test_foreign_pending_stage_and_product_marker_rejected(self):
        self.materialize()
        marker_path = self.source / pi.MODULE / 'vela-staging.json'
        marker = json.loads(marker_path.read_text())
        marker['product'] = 'other_product'
        marker_path.write_bytes(pi.encode_json(marker))
        with self.assertRaisesRegex(pi.BuildError, 'Foreign staging record'):
            self.plan()

    def test_complete_manifest_metadata_drift_rejected(self):
        other = ET.fromstring(MANIFEST)
        other.append(ET.Element('notice'))
        with patch.object(pi, 'command', return_value=ET.tostring(other).decode()):
            with self.assertRaisesRegex(pi.BuildError, 'Complete source manifest'):
                self.plan()

    def test_snapshot_tamper_rejected_before_writes(self):
        (self.snapshot / 'manifest.xml').write_bytes(MANIFEST + b' ')
        with self.assertRaises(pi.BuildError):
            self.plan()
        self.assertFalse((self.source / pi.MODULE).exists())

    def test_foreign_vendor_file_preserved(self):
        foreign = self.source / pi.MODULE / 'Android.bp'
        foreign.parent.mkdir(parents=True)
        foreign.write_bytes(b'owner content')
        with self.assertRaises(pi.BuildError):
            self.plan()
        self.assertEqual(foreign.read_bytes(), b'owner content')

    def test_managed_apk_tamper_rejected(self):
        self.materialize()
        (self.source / pi.MODULE / 'VelaTv.apk').write_bytes(b'changed payload')
        with self.assertRaises(pi.BuildError):
            self.plan()

    def test_symlink_parent_rejected_before_writes(self):
        external = Path(self.tmp.name) / 'external'
        external.mkdir()
        (self.source / 'vendor').symlink_to(external, target_is_directory=True)
        with self.assertRaises(pi.BuildError):
            self.plan()
        self.assertEqual(list(external.iterdir()), [])

    def test_foreign_display_edit_preserved(self):
        path = self.board / 'vendor.prop'
        path.write_bytes(b'owner setting\n')
        with self.assertRaises(pi.BuildError):
            self.plan()
        self.assertEqual(path.read_bytes(), b'owner setting\n')

    def test_display_edit_requires_matching_staging_record(self):
        (self.board / 'vendor.prop').write_bytes(ORIGINALS['vendor.prop'].replace(b'1920x1080', b'3840x2160'))
        with self.assertRaises(pi.BuildError):
            self.plan()

    def test_unfrozen_apk_rejected(self):
        with self.assertRaisesRegex(pi.BuildError, 'frozen firmware input'):
            pi.validate_apk(self.apk, Path('/not-used'), Path('/not-used'))

if __name__ == '__main__':
    unittest.main()
