import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location("vela_fetch_vendor", ROOT / "fetch_vendor.py")
vendor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vendor)


class VendorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.cache = self.root / "cache"
        self.item = {"name": "stock-sif", "type": "nv-recovery-ota", "url": "https://example.invalid/sif.zip", "sha256": None}
        self.lock = self.root / "upstream-lock.json"
        self.write_lock()

    def write_lock(self):
        self.lock.write_text(json.dumps({"vendor_blobs": {"archives": [self.item]}}))

    def test_login_html_is_never_accepted_as_an_archive(self):
        file = self.root / "download"
        file.write_bytes(b"<!doctype html><html>Sign in</html>")
        for extension in vendor.EXTENSIONS.values():
            with self.subTest(extension=extension), self.assertRaises(vendor.BuildError):
                vendor.validate_archive(file, extension)

    def test_first_download_is_recorded_and_later_cache_tampering_is_rejected(self):
        class Opener:
            def open(self, url, timeout):
                return io.BytesIO(b"PK\x03\x04fixture-archive")
        with patch.object(vendor, "ROOT", self.root), patch.object(vendor.urllib.request, "build_opener", return_value=Opener()):
            receipt = vendor.fetch(self.cache)
            original = (self.cache / "vendor-inputs.json").read_bytes()
            self.assertEqual("observed-first-fetch-over-https", receipt["digest_authority"])
            self.assertIsNone(receipt["archives"]["stock-sif.zip"]["published_sha256"])
            (self.cache / "stock-sif.zip").write_bytes(b"PK\x03\x04modified-cache")
            with self.assertRaises(vendor.BuildError):
                vendor.fetch(self.cache)
        self.assertEqual(original, (self.cache / "vendor-inputs.json").read_bytes())

    def test_changed_upstream_url_requires_a_separate_cache(self):
        self.cache.mkdir()
        (self.cache / "vendor-inputs.json").write_text(json.dumps({"schema_version": 1, "device": "sif", "archives": {
            "stock-sif.zip": {"url": "https://example.invalid/old.zip", "sha256": "a" * 64}}}))
        with patch.object(vendor, "ROOT", self.root), self.assertRaises(vendor.BuildError):
            vendor.fetch(self.cache)
        self.assertFalse((self.cache / "stock-sif.zip").exists())

    def test_https_downloads_cannot_redirect_to_plain_http(self):
        with self.assertRaises(vendor.BuildError):
            vendor.HttpsRedirect().redirect_request(None, None, 302, "redirect", {}, "http://example.invalid/archive.zip")


if __name__ == "__main__":
    unittest.main()
