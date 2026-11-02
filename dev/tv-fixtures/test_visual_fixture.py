"""HTTP behavior for the optional photographic QA entry point; no device/decoder claim."""

import http.client
from pathlib import Path
import tempfile
import threading
import unittest

from fixture import TOKEN
from visual_fixture import make_server


class VisualFixtureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="vela-visual-fixture-test-")
        media = Path(self.temp.name) / "transport-only.mp4"
        media.write_bytes(b"transport fixture, not a decoder sample")
        self.server = make_server(media, port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": .01})
        self.thread.start()
        self.addCleanup(self.close)

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, path, method="GET", authenticated=False):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=2)
        try:
            headers = {"Authorization": f'MediaBrowser Client="FIXTURE", Token="{TOKEN}"'} if authenticated else {}
            connection.request(method, path, headers=headers)
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_photos_replace_both_image_types_without_changing_head_semantics(self):
        for item, photo in self.server.visual_artwork.items():
            for suffix in ("Primary", "Backdrop/0"):
                path = f"/Items/{item}/Images/{suffix}"
                status, headers, body = self.request(path)
                self.assertEqual(200, status)
                self.assertEqual("image/webp", headers["Content-Type"])
                self.assertEqual(photo, body)
                status, headers, body = self.request(path, method="HEAD")
                self.assertEqual(200, status)
                self.assertEqual(len(photo), int(headers["Content-Length"]))
                self.assertEqual(b"", body)

    def test_default_art_and_intentionally_absent_art_still_work(self):
        status, headers, body = self.request("/Items/fixture-season-1/Images/Primary")
        self.assertEqual(200, status)
        self.assertEqual("image/png", headers["Content-Type"])
        self.assertTrue(body.startswith(b"\x89PNG"))
        self.assertEqual(404, self.request("/Items/fixture-no-art/Images/Primary")[0])

    def test_metadata_authentication_and_synthetic_identity_are_preserved(self):
        self.assertEqual(401, self.request("/Items/fixture-movie")[0])
        status, _, body = self.request("/Items/fixture-movie", authenticated=True)
        self.assertEqual(200, status)
        self.assertIn(b"FIXTURE Test Pattern", body)


if __name__ == "__main__":
    unittest.main()
