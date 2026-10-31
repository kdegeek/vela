"""HTTP contract self-tests for the FIXTURE, not app/decoder or real-service tests."""

from contextlib import redirect_stderr
import argparse
import http.client
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest

from fixture import Config, Failure, FixtureServer, HA_TOKEN, MAX_BODY, SERIES, TOKEN, USER, route_option
from generate_media import output_path


class FixtureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="vela-fixture-tests-")
        self.media_bytes = bytes(range(256)) * 4
        media = Path(self.temp.name) / "synthetic-range-test.mp4"
        media.write_bytes(self.media_bytes)  # Byte transport only; not a decoder sample.
        self.config = Config(media, home_assistant=True)
        self.server = FixtureServer(("127.0.0.1", 0), self.config)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01})
        self.thread.start()
        self.addCleanup(self.close)

    def close(self):
        self.server.closing.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def connection(self):
        return http.client.HTTPConnection(*self.server.server_address, timeout=2)

    def request(self, path, method="GET", body=None, headers=None, auth=True, raw=False):
        actual = {}
        if auth:
            actual["Authorization"] = f'Bearer {HA_TOKEN}' if path.startswith("/api/") else f'MediaBrowser Client="FIXTURE", Token="{TOKEN}"'
        if body is not None:
            if not raw:
                body = json.dumps(body).encode()
            actual["Content-Type"] = "application/json"
        actual.update(headers or {})
        connection = self.connection()
        try:
            connection.request(method, path, body=body, headers=actual)
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def get_json(self, path, **kwargs):
        status, _, payload = self.request(path, **kwargs)
        self.assertEqual(200, status, payload)
        return json.loads(payload)

    def negotiate(self, identifier="fixture-movie"):
        return self.get_json(f"/Items/{identifier}/PlaybackInfo", method="POST", body=dict(
            UserId=USER, StartTimeTicks=0, AutoOpenLiveStream=False, EnableDirectPlay=True,
            EnableDirectStream=False, EnableTranscoding=False,
            DeviceProfile={"DirectPlayProfiles": [{"Type": "Video", "VideoCodec": "h264", "AudioCodec": "aac"}]}))

    def report_body(self, playback):
        return dict(ItemId="fixture-movie", PositionTicks=42, IsPaused=False, CanSeek=True,
                    MediaSourceId=playback["MediaSources"][0]["Id"], PlaySessionId=playback["PlaySessionId"])

    def test_libraries_latest_resume_and_search_shapes(self):
        views = self.get_json(f"/UserViews?userId={USER}")
        self.assertEqual(["fixture-movies", "fixture-tv"], [i["Id"] for i in views["Items"]])
        latest = self.get_json("/Items/Latest?parentId=fixture-movies&limit=12")
        self.assertEqual(4, latest["TotalRecordCount"])
        self.assertTrue(all(i["Type"] == "Movie" for i in latest["Items"]))
        resume = self.get_json("/UserItems/Resume?limit=12")
        self.assertEqual({"fixture-movie", "fixture-s01e001"}, {i["Id"] for i in resume["Items"]})
        result = self.get_json("/Items?searchTerm=PATTERN&recursive=true&includeItemTypes=Movie,Series,Episode")
        self.assertEqual(["fixture-movie"], [i["Id"] for i in result["Items"]])
        self.assertEqual([], self.get_json("/Items?searchTerm=no-such-fixture")["Items"])
        movie = self.get_json("/Items/fixture-movie")
        self.assertEqual(60 * 10_000_000, movie["RunTimeTicks"])
        self.assertTrue(movie["Name"].startswith("FIXTURE"))

    def test_two_seasons_over_100_episodes_without_duplicates(self):
        seasons = self.get_json(f"/Shows/{SERIES}/Seasons")["Items"]
        self.assertEqual(2, len(seasons))
        for season in seasons:
            with self.subTest(season=season["Id"]):
                identifiers = []
                for offset, expected in ((0, 100), (100, 5), (105, 0)):
                    page = self.get_json(f"/Shows/{SERIES}/Episodes?seasonId={season['Id']}&isMissing=false&startIndex={offset}&limit=100")
                    self.assertEqual(105, page["TotalRecordCount"])
                    self.assertEqual(expected, len(page["Items"]))
                    identifiers.extend(i["Id"] for i in page["Items"])
                self.assertEqual(105, len(set(identifiers)))
        full = self.get_json(f"/Shows/{SERIES}/Episodes?isMissing=false&limit=500")
        self.assertEqual(210, full["TotalRecordCount"])
        self.assertEqual(100, len(full["Items"]))  # Intentional server page cap.

    def test_capped_pagination_search_and_browse(self):
        self.config.page_cap = 37
        identifiers = []
        start = 0
        while True:
            page = self.get_json(f"/Items?searchTerm=Episode&recursive=true&startIndex={start}&limit=100")
            self.assertEqual(211, page["TotalRecordCount"])
            self.assertEqual(start, page["StartIndex"])
            identifiers.extend(i["Id"] for i in page["Items"])
            start += len(page["Items"])
            if start >= page["TotalRecordCount"]:
                break
        self.assertEqual(211, len(set(identifiers)))
        self.assertEqual(2, self.get_json("/Items")["TotalRecordCount"])
        self.assertEqual(4, self.get_json("/Items?parentId=fixture-movies")["TotalRecordCount"])
        self.assertEqual(404, self.request("/Items?parentId=not-a-fixture")[0])

    def test_next_episode_crosses_season_boundary_and_next_up(self):
        following = self.get_json(f"/Shows/{SERIES}/Episodes?startItemId=fixture-s01e105&limit=2&isMissing=false")["Items"]
        self.assertEqual(["fixture-s01e105", "fixture-s02e001"], [i["Id"] for i in following])
        last = self.get_json(f"/Shows/{SERIES}/Episodes?startItemId=fixture-s02e105&limit=2&isMissing=false")["Items"]
        self.assertEqual(1, len(last))
        self.assertEqual("fixture-s01e002", self.get_json(f"/Shows/NextUp?seriesId={SERIES}")["Items"][0]["Id"])

    def test_missing_art_long_title_and_item_errors(self):
        missing = self.get_json("/Items/fixture-no-art")
        self.assertEqual({}, missing["ImageTags"])
        self.assertEqual([], missing["BackdropImageTags"])
        self.assertEqual(404, self.request("/Items/fixture-no-art/Images/Primary")[0])
        self.assertGreater(len(self.get_json("/Items/fixture-long-title")["Name"]), 150)
        status, headers, png = self.request("/Items/fixture-movie/Images/Backdrop/0?maxWidth=100&tag=fixture-v1")
        self.assertEqual(200, status)
        self.assertEqual("image/png", headers["Content-Type"])
        self.assertTrue(png.startswith(b"\x89PNG\r\n\x1a\n"))
        self.assertEqual(404, self.request("/Items/fixture-item-error")[0])
        self.config.item_error_status = 503
        self.assertEqual(503, self.request("/Items/fixture-item-error")[0])
        self.assertEqual(404, self.request("/Items/no-such-fixture")[0])

    def test_authentication_and_no_credential_or_arbitrary_request_logging(self):
        sentinel = "never-record-this-private-input"
        output = io.StringIO()
        with redirect_stderr(output):
            auth = self.get_json("/Users/AuthenticateByName", method="POST", auth=False,
                                 body={"Username": "fixture", "Pw": "fixture"})
            self.assertEqual((TOKEN, USER), (auth["AccessToken"], auth["User"]["Id"]))
            self.assertEqual(401, self.request("/Items", auth=False)[0])
            self.assertEqual(401, self.request("/Users/AuthenticateByName", method="POST", auth=False,
                                             body={"Username": sentinel, "Pw": sentinel})[0])
            self.assertEqual(403, self.request("/Items?userId=wrong-user")[0])
            self.request(f"/Items?searchTerm={sentinel}&api_key={sentinel}", headers={"Authorization": sentinel})
            self.request("/" + sentinel)
        observations = self.get_json("/__fixture/observations", auth=False)
        text = json.dumps(observations) + output.getvalue()
        for credential in (TOKEN, HA_TOKEN, sentinel, "Authorization", "Username", '"Pw"'):
            self.assertNotIn(credential, text)
        self.assertEqual("", output.getvalue())
        self.assertEqual(2, observations["counts"]["POST /Users/AuthenticateByName"])

    def test_playback_source_session_subtitle_and_report_binding(self):
        playback = self.negotiate()
        source = playback["MediaSources"][0]
        self.assertTrue(source["SupportsDirectPlay"])
        self.assertFalse(source["RequiresOpening"])
        self.assertEqual("mp4", source["Container"])
        subtitle = next(t for t in source["MediaStreams"] if t["Type"] == "Subtitle")
        self.assertTrue(subtitle["DeliveryUrl"].startswith("/Videos/fixture-movie/"))
        self.assertNotIn("://", subtitle["DeliveryUrl"])
        status, headers, vtt = self.request(subtitle["DeliveryUrl"])
        self.assertEqual(200, status)
        self.assertTrue(headers["Content-Type"].startswith("text/vtt"))
        self.assertTrue(vtt.startswith(b"WEBVTT\n"))
        for path in ("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped"):
            self.assertEqual(204, self.request(path, method="POST", body=self.report_body(playback))[0])
        other = self.negotiate("fixture-s01e001")
        wrong = self.report_body(playback) | {"PlaySessionId": other["PlaySessionId"]}
        self.assertEqual(400, self.request("/Sessions/Playing", method="POST", body=wrong)[0])
        wrong = self.report_body(playback) | {"MediaSourceId": "wrong-source"}
        self.assertEqual(400, self.request("/Sessions/Playing", method="POST", body=wrong)[0])

    def test_container_and_missing_playback_rejected(self):
        for identifier in (SERIES, "fixture-season-1", "fixture-missing"):
            with self.subTest(identifier=identifier):
                status, _, _ = self.request(f"/Items/{identifier}/PlaybackInfo", method="POST", body={"UserId": USER})
                self.assertEqual(400, status)
                self.assertEqual(400, self.request(f"/Videos/{identifier}/stream")[0])

    def test_userdata_updates_resume_preserving_other_fields(self):
        path = f"/UserItems/fixture-movie/UserData?userId={USER}"
        data = {"PlaybackPositionTicks": 123, "LastPlayedDate": "2026-10-31T12:00:00Z"}
        self.get_json(path, method="POST", body=data)
        movie = self.get_json("/Items/fixture-movie")
        self.assertEqual(123, movie["UserData"]["PlaybackPositionTicks"])
        self.assertFalse(movie["UserData"]["Played"])
        self.get_json(path, method="POST", body={"PlaybackPositionTicks": 0, "Played": True})
        self.assertTrue(self.get_json("/Items/fixture-movie")["UserData"]["Played"])
        self.assertNotIn("fixture-movie", [i["Id"] for i in self.get_json("/UserItems/Resume")["Items"]])

    def test_mp4_full_head_and_exact_range_boundaries(self):
        path = "/Videos/fixture-movie/stream?static=true"
        status, headers, body = self.request(path)
        self.assertEqual((200, self.media_bytes), (status, body))
        self.assertEqual("bytes", headers["Accept-Ranges"])
        self.assertEqual("video/mp4", headers["Content-Type"])
        status, headers, body = self.request(path, method="HEAD", headers={"Range": "bytes=100-110"})
        self.assertEqual((200, str(len(self.media_bytes)), b""), (status, headers["Content-Length"], body))
        cases = [("0-0", 0, 0), ("100-110", 100, 110), ("1023-", 1023, 1023),
                 ("1020-9999", 1020, 1023), ("-5", 1019, 1023), ("-9999", 0, 1023)]
        for requested, start, end in cases:
            with self.subTest(requested=requested):
                status, headers, body = self.request(path, headers={"Range": "bytes=" + requested})
                self.assertEqual(206, status)
                self.assertEqual(f"bytes {start}-{end}/1024", headers["Content-Range"])
                self.assertEqual(str(end - start + 1), headers["Content-Length"])
                self.assertEqual(self.media_bytes[start:end + 1], body)

    def test_range_errors_and_end_of_stream_are_distinct(self):
        path = "/Videos/fixture-movie/stream"
        for requested in ("bytes=1024-", "bytes=1025-", "bytes=4-3", "bytes=-0"):
            with self.subTest(requested=requested):
                status, headers, body = self.request(path, headers={"Range": requested})
                self.assertEqual((416, "bytes */1024", b""), (status, headers["Content-Range"], body))
        for requested in ("bytes=", "bytes=-", "bytes=0-1,3-4", "bytes=abc-", "items=0-10"):
            with self.subTest(requested=requested):
                self.assertEqual(400, self.request(path, headers={"Range": requested})[0])

    def test_stream_association_and_legacy_api_key(self):
        playback = self.negotiate()
        source = playback["MediaSources"][0]["Id"]
        path = f"/Videos/fixture-movie/stream?mediaSourceId={source}&playSessionId={playback['PlaySessionId']}"
        self.assertEqual(200, self.request(path)[0])
        self.assertEqual(400, self.request(path.replace(source, "wrong-source"))[0])
        self.assertEqual(200, self.request(f"/Videos/fixture-movie/stream?api_key={TOKEN}", auth=False)[0])
        observations = json.dumps(self.get_json("/__fixture/observations"))
        self.assertNotIn("api_key", observations)
        self.assertNotIn(TOKEN, observations)

    def test_bad_range_server_modes_are_observable(self):
        path = "/Videos/fixture-movie/stream"
        self.config.range_mode = "ignore"
        status, headers, body = self.request(path, headers={"Range": "bytes=8-15"})
        self.assertEqual((200, self.media_bytes), (status, body))
        self.assertNotIn("Content-Range", headers)
        self.config.range_mode = "wrong-start"
        status, headers, _ = self.request(path, headers={"Range": "bytes=8-15"})
        self.assertEqual((206, "bytes 9-15/1024"), (status, headers["Content-Range"]))
        self.config.range_mode = "truncate"
        with self.assertRaises(http.client.IncompleteRead):
            self.request(path, headers={"Range": "bytes=8-15"})

    def test_503_is_one_observed_post_with_retry_after_zero(self):
        path = "/Sessions/Playing"
        self.config.failures[("POST", path)] = Failure(503, 1)
        playback = self.negotiate()
        status, headers, _ = self.request(path, method="POST", body=self.report_body(playback))
        self.assertEqual((503, "0"), (status, headers["Retry-After"]))
        observations = self.get_json("/__fixture/observations")
        self.assertEqual(1, observations["counts"]["POST " + path])
        events = [e for e in observations["recent"] if e["path"] == path]
        self.assertEqual([503], [e["status"] for e in events])
        # Explicit caller retry, not automatic fixture replay; the one-shot failure is consumed.
        self.assertEqual(204, self.request(path, method="POST", body=self.report_body(playback))[0])
        self.assertEqual(2, self.get_json("/__fixture/observations")["counts"]["POST " + path])

    def test_delayed_body_sends_headers_and_leaves_observations_responsive(self):
        self.config.delays[("GET", "/Items/fixture-movie")] = 10
        connection = self.connection()
        self.addCleanup(connection.close)
        connection.request("GET", "/Items/fixture-movie", headers={"Authorization": f'MediaBrowser Client="FIXTURE", Token="{TOKEN}"'})
        response = connection.getresponse()  # Headers are available before the ten-second body delay.
        self.assertEqual(200, response.status)
        observations = self.get_json("/__fixture/observations")  # Two-second timeout detects held locks.
        self.assertEqual(1, observations["counts"]["GET /Items/fixture-movie"])
        self.assertEqual(200, observations["recent"][0]["status"])

    def test_home_states_actions_and_failure_counts(self):
        states = self.get_json("/api/states")
        self.assertEqual(2, len(states))
        self.assertTrue(all(i["attributes"]["friendly_name"].startswith("FIXTURE") for i in states))
        path = "/api/services/light/toggle"
        body = {"entity_id": "light.fixture_lamp"}
        changed = self.get_json(path, method="POST", body=body)
        self.assertEqual("on", changed[0]["state"])
        self.config.failures[("POST", path)] = Failure(503)
        self.assertEqual(503, self.request(path, method="POST", body=body)[0])
        self.assertEqual("on", self.get_json("/api/states/light.fixture_lamp")["state"])
        observations = self.get_json("/__fixture/observations")
        self.assertEqual((2, 1), (observations["actionAttempts"], observations["appliedActions"]))
        self.assertEqual(2, observations["counts"]["POST " + path])
        self.assertEqual(404, self.request("/api/states/light.unknown")[0])

    def test_invalid_bodies_pages_methods_and_paths(self):
        for query in ("limit=0", "limit=501", "startIndex=-1", "startIndex=2147483648", "limit=no", "limit=2&limit=3"):
            with self.subTest(query=query):
                self.assertEqual(400, self.request("/Items?" + query)[0])
        for value in (b"not-json", b"[]", b"null", b'{"bad":"\xff"}'):
            self.assertEqual(400, self.request("/Sessions/Playing", method="POST", body=value, raw=True)[0])
        self.assertEqual(413, self.request("/Sessions/Playing", method="POST", body=b"x" * (MAX_BODY + 1), raw=True)[0])
        self.assertEqual(400, self.request(f"/UserItems/fixture-movie/UserData", method="POST",
                                         body={"PlaybackPositionTicks": True})[0])
        self.assertEqual(400, self.request(f"/UserItems/fixture-movie/UserData", method="POST",
                                         body={"PlaybackPositionTicks": -1})[0])
        self.assertEqual(400, self.request("/api/services/light/turn_on", method="POST", body={})[0])
        self.assertEqual(405, self.request("/Items", method="POST", body={})[0])
        for path in ("/../README.md", "/%2e%2e/README.md", "/Items/%2e%2e/README.md"):
            self.assertEqual(400, self.request(path)[0])
        self.assertEqual(404, self.request("/etc/passwd")[0])
        self.assertEqual(404, self.request("/v1/chat/completions", method="POST", body={})[0])

    def test_bounded_observations_and_nonintrusive_inspection(self):
        for _ in range(205):
            self.request("/not-a-fixture")
        for _ in range(2):
            observations = self.get_json("/__fixture/observations", auth=False)
        self.assertEqual({"GET <unknown>": 205}, observations["counts"])
        self.assertEqual(200, len(observations["recent"]))
        self.assertTrue(self.get_json("/__fixture/status", auth=False)["FIXTURE"])

    def test_cli_fault_and_generator_validation(self):
        key, failure = route_option("POST:/Sessions/Playing:503:1")
        self.assertEqual(("POST", "/Sessions/Playing"), key)
        self.assertEqual((503, 1), (failure.status, failure.remaining))
        self.assertEqual((("GET", "/Items"), 1.5), route_option("GET:/Items:1.5", delay=True))
        for text in ("POST:/Items:200", "GET:/Items:503:0", "PUT:/Items:503", "GET:/../Items:503"):
            with self.assertRaises(argparse.ArgumentTypeError):
                route_option(text)
        with self.assertRaises(argparse.ArgumentTypeError):
            route_option("GET:/Items:31", delay=True)
        with self.assertRaises(argparse.ArgumentTypeError):
            output_path(Path(__file__).parent / "must-not-write.mp4")
        with self.assertRaises(argparse.ArgumentTypeError):
            output_path(self.config.media)
        with self.assertRaises(ValueError):
            Config(self.config.media, duration=0)
        with self.assertRaises(FileNotFoundError):
            Config(Path(self.temp.name) / "does-not-exist.mp4")


if __name__ == "__main__":
    unittest.main()
