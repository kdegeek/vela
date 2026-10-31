import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

MODULE = Path(__file__).resolve().parents[1] / "vela_shield.py"
spec = importlib.util.spec_from_file_location("vela_shield", MODULE)
shield = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shield)
MANIFEST = '<manifest><project name="example/device" path="device/nvidia/sif" revision="' + "a" * 40 + '" /></manifest>'
PRODUCT = "PRODUCT_NAME := lineage_sif\nPRODUCT_DEVICE := sif\n"
BADGING = "package: name='app.vela.tv' versionCode='1'\n" + "\n".join(
    f"uses-permission: name='{p}'" for p in shield.PERMISSIONS)


def apk_bytes(dex=b"fixture-dex", arm64=True, manifest=b"fixture-binary-manifest", native=b"fixture-native", resources=b"fixture-resources", signed=False):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        archive.writestr("AndroidManifest.xml", manifest)
        archive.writestr("classes.dex", dex)
        archive.writestr("resources.arsc", resources)
        if arm64:
            archive.writestr("lib/arm64-v8a/fixture.so", native)
        if signed:
            archive.writestr("META-INF/MANIFEST.MF", b"fixture-signature-manifest")
            archive.writestr("META-INF/CERT.SF", b"fixture-signature")
            archive.writestr("META-INF/CERT.RSA", b"fixture-certificate")
    return stream.getvalue()


class ShieldTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.tree = self.root / "lineage"
        (self.tree / ".repo").mkdir(parents=True)
        self.product = self.tree / shield.PRODUCT
        self.product.parent.mkdir(parents=True)
        self.product.write_text(PRODUCT)
        (self.tree / shield.BOARD).write_text('include device/nvidia/foster/BoardConfig.mk\nTARGET_OTA_ASSERT_DEVICE := foster,sif\n')
        self.apk = self.root / "app-firmware-unsigned.apk"
        self.apk.write_bytes(apk_bytes())

    @staticmethod
    def command(args, cwd=None):
        if args[:3] == ["repo", "manifest", "-r"]:
            return MANIFEST
        if args[0] == "repo":
            return ""
        if args[0] == "git":
            return "b" * 40
        return BADGING

    def stage(self):
        snapshot = self.root / "snapshot"
        if not snapshot.exists():
            self.snapshot()
        with patch.object(shield, "command", side_effect=self.command):
            return shield.stage(self.tree, self.apk, Path("aapt2"), snapshot)

    def snapshot(self):
        snapshot = self.root / "snapshot"
        with patch.object(shield, "command", side_effect=self.command):
            shield.freeze(self.tree, snapshot)
        return snapshot

    def target_files(self, dex=b"fixture-dex", include_permissions=True, extension="SYSTEM_EXT", app=None, build_manifest=MANIFEST, old_marker=False):
        target = self.root / "target-files.zip"
        with zipfile.ZipFile(target, "w") as archive:
            archive.writestr(f"{extension}/priv-app/VelaTv/VelaTv.apk", app if app is not None else apk_bytes(dex))
            archive.writestr("IMAGES/recovery.img", b"fixture-recovery")
            archive.writestr("SYSTEM/etc/build-manifest.xml", build_manifest)
            marker = json.loads((self.tree / "vendor/vela/vela-staging.json").read_bytes())
            if old_marker:
                marker["source_manifest_sha256"] = "0" * 64
            archive.writestr(f"{extension}/etc/vela/image-inputs.json", json.dumps(marker))
            for file in (self.tree / "vendor/vela").rglob("*.xml"):
                if include_permissions or "permissions" not in file.parts:
                    archive.writestr(extension + "/etc/" + str(file.relative_to(self.tree / "vendor/vela")), file.read_bytes())
        tool = self.tree / "out/host/linux-x86/bin/ota_from_target_files"
        tool.parent.mkdir(parents=True, exist_ok=True)
        tool.write_text("fixture-tool")
        key = self.tree / "build/target/product/security/testkey"
        key.parent.mkdir(parents=True, exist_ok=True)
        key.with_suffix(".pk8").write_bytes(b"fixture-key")
        key.with_suffix(".x509.pem").write_bytes(b"fixture-certificate")
        return target

    @staticmethod
    def ota_run(args, **kwargs):
        with zipfile.ZipFile(args[-1], "w") as archive:
            archive.writestr("META-INF/com/android/metadata", "pre-device=sif\n")
            archive.writestr("META-INF/com/google/android/updater-script", 'assert(getprop("ro.product.device") == "sif" || abort("wrong device"));\n')
        return subprocess.CompletedProcess(args, 0)

    def test_stage_is_idempotent_and_preserves_upstream(self):
        first = self.stage()
        second = self.stage()
        self.assertEqual(first, second)
        self.assertEqual(PRODUCT + shield.HOOK, self.product.read_text())
        self.assertEqual(self.apk.read_bytes(), (self.tree / "vendor/vela/VelaTv.apk").read_bytes())
        self.assertIn("vela.mk", first["integration_files"])

    def test_other_shield_target_is_rejected_before_writes(self):
        self.product.write_text(PRODUCT.replace("sif", "foster"))
        with self.assertRaises(shield.BuildError):
            self.stage()
        self.assertFalse((self.tree / "vendor/vela").exists())

    def test_foreign_vendor_directory_is_preserved(self):
        foreign = self.tree / "vendor/vela"
        foreign.mkdir(parents=True)
        (foreign / "owner-file").write_text("preserve")
        with self.assertRaises(shield.BuildError):
            self.stage()
        self.assertEqual("preserve", (foreign / "owner-file").read_text())
        self.assertEqual(PRODUCT, self.product.read_text())

    def test_changed_upstream_cannot_be_silently_re_staged(self):
        self.stage()
        changed = "# owner edit\n" + self.product.read_text()
        self.product.write_text(changed)
        with self.assertRaises(shield.BuildError):
            self.stage()
        self.assertEqual(changed, self.product.read_text())

    def test_debug_and_stock_apks_are_rejected(self):
        for badging in (BADGING + "\napplication-debuggable", "package: name='app.vela.tv' versionCode='1'"):
            with self.subTest(badging=badging), patch.object(shield, "command", return_value=badging):
                with self.assertRaises(shield.BuildError):
                    shield.validate_apk(self.apk, Path("aapt2"))

    def test_arm32_stock_runtime_does_not_change_firmware_architecture(self):
        self.apk.write_bytes(apk_bytes(arm64=False))
        with self.assertRaises(shield.BuildError):
            self.stage()

    def test_freeze_rejects_branch_revisions_and_preserves_existing_lock(self):
        with patch.object(shield, "command", return_value=MANIFEST.replace("a" * 40, "lineage-22.2")):
            with self.assertRaises(shield.BuildError):
                shield.freeze(self.tree, self.root / "bad-lock")
        self.assertFalse((self.root / "bad-lock").exists())
        snapshot = self.snapshot()
        before = (snapshot / "manifest.xml").read_bytes()
        with self.assertRaises(shield.BuildError):
            shield.freeze(self.tree, snapshot)
        self.assertEqual(before, (snapshot / "manifest.xml").read_bytes())

    def test_freeze_refuses_dirty_source(self):
        with patch.object(shield, "command", side_effect=subprocess.CalledProcessError(1, ["repo", "forall"])):
            with self.assertRaises(subprocess.CalledProcessError):
                shield.freeze(self.tree, self.root / "snapshot")
        self.assertFalse((self.root / "snapshot").exists())

    def test_bundle_generates_ota_from_inspected_target_files_and_attests_recovery(self):
        snapshot = self.snapshot()
        self.stage()
        target = self.target_files()
        output = self.root / "bundle"
        with patch.object(shield, "command", side_effect=self.command), patch.object(shield.subprocess, "run", side_effect=self.ota_run) as run:
            provenance = shield.bundle(self.tree, target, snapshot, output)
        self.assertEqual(str(target.resolve()), run.call_args.args[0][-2])
        self.assertEqual(b"fixture-recovery", (output / "recovery.img").read_bytes())
        self.assertEqual("unverified-development-testkeys", provenance["qualification"])
        for line in (output / "SHA256SUMS").read_text().splitlines():
            expected, name = line.split("  ")
            self.assertEqual(expected, shield.sha256(output / name))

    def test_wrong_application_or_missing_allowlist_cannot_produce_bundle(self):
        snapshot = self.snapshot()
        self.stage()
        for dex, permissions in ((b"stale-application", True), (b"fixture-dex", False)):
            target = self.target_files(dex=dex, include_permissions=permissions)
            with patch.object(shield, "command", side_effect=self.command), patch.object(shield.subprocess, "run") as run:
                with self.assertRaises(shield.BuildError):
                    shield.bundle(self.tree, target, snapshot, self.root / "bundle")
            run.assert_not_called()
        self.assertFalse((self.root / "bundle").exists())

    def test_changed_resolved_sources_cannot_be_packaged(self):
        snapshot = self.snapshot()
        self.stage()
        target = self.target_files()
        def changed(args, cwd=None):
            return self.command(args, cwd).replace("a" * 40, "c" * 40)
        with patch.object(shield, "command", side_effect=changed), patch.object(shield.subprocess, "run") as run:
            with self.assertRaises(shield.BuildError):
                shield.bundle(self.tree, target, snapshot, self.root / "bundle")
        run.assert_not_called()

    def test_wrong_device_ota_is_rejected_and_output_is_not_published(self):
        snapshot = self.snapshot()
        self.stage()
        target = self.target_files()
        def wrong_device(args, **kwargs):
            with zipfile.ZipFile(args[-1], "w") as archive:
                archive.writestr("META-INF/com/android/metadata", "pre-device=foster\n")
                archive.writestr("META-INF/com/google/android/updater-script", 'assert(getprop("ro.product.device") == "foster");\n')
        with patch.object(shield, "command", side_effect=self.command), patch.object(shield.subprocess, "run", side_effect=wrong_device):
            with self.assertRaises(shield.BuildError):
                shield.bundle(self.tree, target, snapshot, self.root / "bundle")
        self.assertFalse((self.root / "bundle").exists())

    def test_source_lock_tampering_is_detected(self):
        snapshot = self.snapshot()
        self.stage()
        target = self.target_files()
        (snapshot / "manifest.xml").write_text(MANIFEST + "<!-- changed -->")
        with patch.object(shield.subprocess, "run") as run:
            with self.assertRaises(shield.BuildError):
                shield.bundle(self.tree, target, snapshot, self.root / "bundle")
        run.assert_not_called()

    def test_mac_is_rejected_as_os_compiler_host(self):
        with patch.object(shield.platform, "system", return_value="Darwin"), patch.object(shield.platform, "machine", return_value="arm64"):
            result = shield.doctor(self.tree)
        self.assertFalse(result["ready"])
        self.assertTrue(any("Linux x86_64" in problem for problem in result["problems"]))

    def test_pinned_manifest_replays_public_remotes_and_complete_device_closure(self):
        out = self.root / "replay.xml"
        info = shield.materialize_manifest(out)
        manifest = shield.ET.parse(out).getroot()
        self.assertEqual(1171, info["project_count"])
        self.assertTrue(all(p.get("revision") and len(p.get("revision")) == 40 for p in manifest.findall("project")))
        self.assertTrue(all(r.get("fetch").startswith("https://") for r in manifest.findall("remote")))
        self.assertFalse(any(r.get("name") == "private" for r in manifest.findall("remote")))

    def test_legacy_system_extension_layout_is_accepted_with_colocated_permissions(self):
        snapshot = self.snapshot()
        self.stage()
        target = self.target_files(extension="SYSTEM/system_ext")
        with patch.object(shield, "command", side_effect=self.command), patch.object(shield.subprocess, "run", side_effect=self.ota_run):
            info = shield.bundle(self.tree, target, snapshot, self.root / "bundle")
        self.assertEqual("SYSTEM/system_ext", info["system_extension_archive_root"])

    def test_broad_updater_assertion_cannot_be_hidden_by_sif_only_metadata(self):
        ota = self.root / "ota.zip"
        with zipfile.ZipFile(ota, "w") as archive:
            archive.writestr("META-INF/com/android/metadata", "pre-device=sif\n")
            archive.writestr("META-INF/com/google/android/updater-script", 'assert(getprop("ro.product.device") == "sif" || getprop("ro.product.device") == "foster");')
        with self.assertRaises(shield.BuildError):
            shield.check_ota(ota)

    def test_stale_os_archive_cannot_receive_current_checkout_provenance(self):
        snapshot = self.snapshot()
        self.stage()
        target = self.target_files(build_manifest=MANIFEST.replace("a" * 40, "c" * 40))
        with patch.object(shield, "command", side_effect=self.command), patch.object(shield.subprocess, "run") as run:
            with self.assertRaisesRegex(shield.BuildError, "Built OS source manifest"):
                shield.bundle(self.tree, target, snapshot, self.root / "bundle")
        run.assert_not_called()

    def test_same_dex_does_not_hide_changed_manifest_resources_or_native_code(self):
        snapshot = self.snapshot()
        self.stage()
        for app in (apk_bytes(manifest=b"missing-boot-receiver"), apk_bytes(resources=b"stale-resources"), apk_bytes(native=b"stale-native-library")):
            target = self.target_files(app=app)
            with patch.object(shield, "command", side_effect=self.command), patch.object(shield.subprocess, "run") as run:
                with self.assertRaisesRegex(shield.BuildError, "different Vela application"):
                    shield.bundle(self.tree, target, snapshot, self.root / "bundle")
            run.assert_not_called()

    def test_os_resigning_can_change_only_signature_zip_entries(self):
        snapshot = self.snapshot()
        self.stage()
        target = self.target_files(app=apk_bytes(signed=True))
        with patch.object(shield, "command", side_effect=self.command), patch.object(shield.subprocess, "run", side_effect=self.ota_run):
            shield.bundle(self.tree, target, snapshot, self.root / "bundle")
        self.assertTrue((self.root / "bundle/provenance.json").is_file())

    def test_old_integration_marker_cannot_be_reattested(self):
        snapshot = self.snapshot()
        self.stage()
        target = self.target_files(old_marker=True)
        with patch.object(shield, "command", side_effect=self.command), patch.object(shield.subprocess, "run") as run:
            with self.assertRaisesRegex(shield.BuildError, "integration metadata"):
                shield.bundle(self.tree, target, snapshot, self.root / "bundle")
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
