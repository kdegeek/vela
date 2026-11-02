"""Focused stdlib tests for the offline Pi 5 image verifier."""

from __future__ import annotations

import hashlib
import importlib.util
import io
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile


ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("pi5_verify_image", ROOT / "verify_image.py")
verify = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(verify)


def entry(boot: int, part_type: int, start: int, count: int) -> bytes:
    raw = bytearray(16)
    raw[0] = boot
    raw[4] = part_type
    struct.pack_into("<I", raw, 8, start)
    struct.pack_into("<I", raw, 12, count)
    return bytes(raw)


def valid_fixture() -> tuple[bytearray, int]:
    """Small equivalent of the locked geometry for fast parser tests."""
    total_sectors = 20
    raw = bytearray(total_sectors * verify.SECTOR_SIZE)
    mbr = bytearray(verify.SECTOR_SIZE)
    table = [
        entry(0x80, 0x0C, 1, 1),
        entry(0, 0x05, 2, 8),
        entry(0, 0x83, 10, 10),
        bytes(16),
    ]
    for index, value in enumerate(table):
        mbr[446 + index * 16:462 + index * 16] = value
    mbr[510:512] = b"\x55\xaa"
    raw[:verify.SECTOR_SIZE] = mbr

    def ebr(lba: int, logical_type: int, count: int, next_lba: int | None) -> None:
        block = bytearray(verify.SECTOR_SIZE)
        block[446:462] = entry(0, logical_type, 1, count)
        if next_lba is not None:
            block[462:478] = entry(0, 0x05, next_lba - 2, 0)
        block[510:512] = b"\x55\xaa"
        raw[lba * verify.SECTOR_SIZE:(lba + 1) * verify.SECTOR_SIZE] = block

    ebr(2, 0x83, 2, 5)
    ebr(5, 0x83, 1, 7)
    ebr(7, 0x83, 1, None)
    return raw, len(raw)


class PartitionTests(unittest.TestCase):
    def setUp(self) -> None:
        self.constants = patch.multiple(
            verify,
            EXPECTED_IMAGE_BYTES=20 * verify.SECTOR_SIZE,
            MIB=verify.SECTOR_SIZE,
            EXPECTED_PARTITION_MIB={1: 1, 2: 8, 5: 2, 6: 1, 7: 1},
        )
        self.constants.start()
        self.addCleanup(self.constants.stop)

    def parse(self, raw: bytes) -> dict:
        return verify.parse_partition_table(io.BytesIO(raw), len(raw))

    def test_valid_mbr_and_three_ebr_logicals(self) -> None:
        raw, size = valid_fixture()
        result = self.parse(raw)
        self.assertTrue(result["ok"], result["errors"])
        self.assertEqual([p["number"] for p in result["logical_partitions"]], [5, 6, 7])
        self.assertEqual(result["logical_partitions"][0]["start_lba"], 3)
        self.assertEqual({p["number"] for p in result["partitions"] if not p["empty"]},
                         {1, 2, 3, 5, 6, 7})

    def test_bad_mbr_signature_fails_closed(self) -> None:
        raw, _ = valid_fixture()
        raw[510:512] = b"\x00\x00"
        result = self.parse(raw)
        self.assertFalse(result["ok"])
        self.assertTrue(any("MBR signature" in error for error in result["errors"]))

    def test_primary_overlap_and_bad_boot_geometry_fail(self) -> None:
        raw, _ = valid_fixture()
        raw[446] = 0
        struct.pack_into("<II", raw, 446 + 8 + 16 * 2, 9, 11)
        result = self.parse(raw)
        self.assertFalse(result["ok"])
        self.assertTrue(any("active flag" in error for error in result["errors"]))
        raw[446] = 0x80
        result = self.parse(raw)
        self.assertFalse(result["ok"])
        self.assertTrue(any("overlaps" in error for error in result["errors"]))

    def test_ebr_loop_is_detected(self) -> None:
        raw, _ = valid_fixture()
        # EBR at LBA 5 links to itself (relative to p2 start LBA 2).
        offset = 5 * verify.SECTOR_SIZE + 462 + 8
        struct.pack_into("<I", raw, offset, 3)
        result = self.parse(raw)
        self.assertFalse(result["ok"])
        self.assertTrue(any("loop" in error or "does not advance" in error
                            for error in result["errors"]))

    def test_ebr_link_out_of_bounds_is_detected(self) -> None:
        raw, _ = valid_fixture()
        offset = 2 * verify.SECTOR_SIZE + 462 + 8
        struct.pack_into("<I", raw, offset, 100)
        result = self.parse(raw)
        self.assertFalse(result["ok"])
        self.assertTrue(any("outside p2" in error for error in result["errors"]))


class FilesystemAndApkTests(unittest.TestCase):
    def test_ext4_bad_magic_rejected(self) -> None:
        raw = bytearray(8 * verify.SECTOR_SIZE)
        superblock = 1024
        struct.pack_into("<I", raw, superblock + 4, 8)
        struct.pack_into("<I", raw, superblock + 24, 0)
        struct.pack_into("<H", raw, superblock + 88, 512)
        result = verify.inspect_ext4(
            io.BytesIO(raw), {"number": 7, "start_lba": 0, "sector_count": 8},
            expected_label="metadata", expected_inode_size=512)
        self.assertEqual(result["status"], "fail")
        self.assertTrue(any("magic" in error for error in result["errors"]))

    def test_ext4_metadata_label_size_and_inode_are_checked(self) -> None:
        raw = bytearray(8 * verify.SECTOR_SIZE)
        superblock = 1024
        struct.pack_into("<I", raw, superblock + 4, 4)
        struct.pack_into("<I", raw, superblock + 24, 0)
        struct.pack_into("<H", raw, superblock + 56, 0xEF53)
        struct.pack_into("<H", raw, superblock + 88, 512)
        raw[superblock + 120:superblock + 128] = b"metadata"
        result = verify.inspect_ext4(
            io.BytesIO(raw), {"number": 7, "start_lba": 0, "sector_count": 8},
            expected_label="metadata", expected_inode_size=512)
        self.assertEqual(result["status"], "pass", result["errors"])

    def make_apk(self, path: Path, *, dex: bytes = b"dex") -> None:
        elf = bytearray(64)
        elf[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", elf, 18, 183)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            archive.writestr("classes.dex", dex)
            archive.writestr("lib/arm64-v8a/libvela.so", bytes(elf))
            archive.writestr("META-INF/CERT.RSA", b"signature")

    def test_apk_requires_aarch64_elf64_and_frozen_digest(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "input.apk"
            self.make_apk(apk)
            good = verify.inspect_apk(
                apk, frozen_metadata={"sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                                      "bytes": apk.stat().st_size})
            self.assertEqual(good["status"], "pass", good["errors"])
            bad = verify.inspect_apk(
                apk, frozen_metadata={"sha256": "0" * 64, "bytes": apk.stat().st_size})
            self.assertEqual(bad["status"], "fail")
            self.assertTrue(any("SHA-256" in error for error in bad["errors"]))

    def test_armv7_only_apk_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "input.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"manifest")
                archive.writestr("classes.dex", b"dex")
                archive.writestr("lib/armeabi-v7a/libvela.so", b"\x7fELF")
            result = verify.inspect_apk(apk)
            self.assertEqual(result["status"], "fail")
            self.assertTrue(any("ARMv7-only" in error for error in result["errors"]))


class ProvenanceTests(unittest.TestCase):
    def test_marker_schema_matches_pi_stager_contract(self) -> None:
        marker = {
            "schema_version": 1, "device": "rpi5", "product": "vela_rpi5_tv",
            "display": {"mode": "3840x2160", "density": 480},
            "source_manifest_sha256": "a" * 64, "upstream_lock_sha256": "b" * 64,
            "apk": {"sha256": "c" * 64},
            "integration_files": {"vendor/vela/rpi5/Android.bp": "d" * 64},
        }
        self.assertEqual(verify.marker_errors(marker), [])
        marker["device"] = "rpi4"
        self.assertTrue(verify.marker_errors(marker))

    def test_xml_semantic_fallback_accepts_formatting_only(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            expected = Path(temp) / "expected.xml"
            expected.write_bytes(
                b"<permissions><privapp-permissions package=\"app.vela.tv\">"
                b"<permission name=\"android.permission.MEDIA_CONTENT_CONTROL\"/>"
                b"<permission name=\"android.permission.WRITE_SECURE_SETTINGS\"/>"
                b"</privapp-permissions></permissions>")
            actual = (
                b"<?xml version=\"1.0\"?><permissions>\n"
                b" <privapp-permissions package=\"app.vela.tv\">\n"
                b"  <permission name=\"android.permission.WRITE_SECURE_SETTINGS\"/>\n"
                b"  <permission name=\"android.permission.MEDIA_CONTENT_CONTROL\"/>\n"
                b" </privapp-permissions>\n</permissions>")
            result = verify.compare_definition(actual, expected, "permissions")
            self.assertEqual(result["status"], "pass")
            self.assertEqual(result["mode"], "semantic")

    def test_sha256_file_streams_and_reports_size(self) -> None:
        with tempfile.NamedTemporaryFile() as file:
            file.write(b"hello")
            file.flush()
            size, digest = verify.sha256_file(Path(file.name))
        self.assertEqual(size, 5)
        self.assertEqual(digest, hashlib.sha256(b"hello").hexdigest())

    def test_signature_verification_requests_and_binds_certificate(self) -> None:
        output = f'Signer #1 certificate SHA-256 digest: {verify.TESTKEY_CERT_SHA256}\n'.encode()
        process = subprocess.CompletedProcess([], 0, output, b'')
        with patch.object(verify, '_tool', return_value='/tool/apksigner'), \
                patch.object(verify, '_run', return_value=process) as run:
            self.assertEqual(verify.inspect_signature('/tool/apksigner', Path('app.apk')), [])
            self.assertIn('--print-certs', run.call_args.args[0])
            self.assertTrue(verify.inspect_signature('/tool/apksigner', Path('app.apk'), '0' * 64))

    def test_xml_extra_permissions_and_filter_tag_changes_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            expected = Path(temp) / 'expected.xml'
            expected.write_bytes(b'<permissions><permission name="one"/></permissions>')
            self.assertEqual(verify.compare_definition(
                b'<permissions><permission name="one"/><permission name="two"/></permissions>',
                expected, 'permissions')['status'], 'fail')
            expected.write_bytes(b'<preferred-activities><item><filter><action name="home"/></filter></item></preferred-activities>')
            self.assertEqual(verify.compare_definition(
                b'<preferred-activities><item><filter><cat name="home"/></filter></item></preferred-activities>',
                expected, 'home')['status'], 'fail')


if __name__ == "__main__":
    unittest.main()
