#!/usr/bin/env python3
"""FIXTURE: local, synthetic Vela acceptance data; not a Jellyfin/HA implementation."""

import argparse
from collections import Counter, OrderedDict, deque
from copy import deepcopy
from dataclasses import dataclass, field
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import struct
import threading
from urllib.parse import parse_qs, unquote, urlsplit
import zlib


USER = "fixture-user"
TOKEN = "fixture-jellyfin-token"
HA_TOKEN = "fixture-home-token"
SERIES = "fixture-series"
TICKS = 10_000_000
MAX_BODY = 65_536
VTT = b"WEBVTT\n\n00:00.000 --> 00:10.000\nVela FIXTURE: original synthetic media\n\n00:10.000 --> 00:59.000\nFIXTURE subtitles: seek, pause and resume\n"


class BadRequest(Exception):
    def __init__(self, status=400, message="Invalid FIXTURE request."):
        self.status, self.message = status, message


def integer(value, minimum=0, maximum=2**63 - 1):
    if type(value) is not int or not minimum <= value <= maximum:
        raise BadRequest()
    return value


def catalog(duration):
    def item(identifier, name, kind, **extra):
        return dict(Id=identifier, Name="FIXTURE " + name, Type=kind,
                    Overview="Synthetic local acceptance fixture; no real service or model quality claim.",
                    Genres=["Fixture"], ProductionYear=2026, ImageTags={"Primary": "fixture-v1"},
                    BackdropImageTags=["fixture-v1"],
                    UserData={"PlaybackPositionTicks": 0, "Played": False}, **extra)

    entries = [item("fixture-movies", "Movies", "CollectionFolder"),
               item("fixture-tv", "TV", "CollectionFolder"),
               item(SERIES, "The Pagination Observatory", "Series", ParentId="fixture-tv")]
    for identifier, title in [("fixture-movie", "Test Pattern"), ("fixture-no-art", "Missing Artwork"),
                              ("fixture-long-title", "A Very Long Title About the Patient Observatory Keeper " * 3),
                              ("fixture-item-error", "Item Error (deliberate)")]:
        entries.append(item(identifier, title, "Movie", ParentId="fixture-movies", RunTimeTicks=duration * TICKS))
    for season in (1, 2):
        season_id = f"fixture-season-{season}"
        entries.append(item(season_id, f"Season {season}", "Season", ParentId=SERIES,
                            SeriesId=SERIES, IndexNumber=season))
        for episode in range(1, 106):
            entries.append(item(f"fixture-s{season:02}e{episode:03}", f"Episode {season}.{episode:03}", "Episode",
                                ParentId=season_id, SeriesId=SERIES, SeriesName="FIXTURE The Pagination Observatory",
                                SeasonId=season_id, ParentIndexNumber=season, IndexNumber=episode,
                                RunTimeTicks=duration * TICKS))
    entries.append(item("fixture-missing", "Unavailable Episode", "Episode", ParentId="fixture-season-2",
                        SeriesId=SERIES, SeasonId="fixture-season-2", ParentIndexNumber=2, IndexNumber=106,
                        IsMissing=True, RunTimeTicks=duration * TICKS))
    result = {entry["Id"]: entry for entry in entries}
    result["fixture-no-art"].update(ImageTags={}, BackdropImageTags=[])
    for identifier in ("fixture-movie", "fixture-s01e001"):
        result[identifier]["UserData"]["PlaybackPositionTicks"] = min(5, duration - 1) * TICKS
    return result


def artwork():
    """Original geometric RGB PNG, constructed without image libraries or binary assets."""
    def chunk(kind, data):
        return struct.pack("!I", len(data)) + kind + data + struct.pack("!I", zlib.crc32(kind + data))
    width, height = 160, 240
    rows = b"".join(b"\x00" + b"".join(bytes((25 + (x // 40) * 45, 45 + (y // 60) * 40, 140))
                                      for x in range(width)) for y in range(height))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack("!2I5B", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


@dataclass
class Failure:
    status: int
    remaining: int | None = None


@dataclass
class Config:
    media: Path
    duration: int = 60
    page_cap: int = 100
    home_assistant: bool = False
    item_error_status: int = 404
    range_mode: str = "normal"
    failures: dict = field(default_factory=dict)
    delays: dict = field(default_factory=dict)

    def __post_init__(self):
        self.media = Path(self.media).resolve(strict=True)
        if not self.media.is_file() or self.media.stat().st_size == 0:
            raise ValueError("--media must be a nonempty regular MP4 file")
        if not 5 <= self.duration <= 120 or not 1 <= self.page_cap <= 500:
            raise ValueError("duration must be 5..120; page cap must be 1..500")


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, config):
        self.config = config
        self.items = catalog(config.duration)
        self.lock = threading.RLock()
        self.closing = threading.Event()
        self.counts = Counter()
        self.events = deque(maxlen=200)
        self.sessions = OrderedDict()
        self.session_serial = 0
        self.action_attempts = 0
        self.applied_actions = 0
        self.entities = {
            "light.fixture_lamp": dict(entity_id="light.fixture_lamp", state="off",
                                       attributes={"friendly_name": "FIXTURE Lamp", "brightness": 128}),
            "switch.fixture_fan": dict(entity_id="switch.fixture_fan", state="off",
                                       attributes={"friendly_name": "FIXTURE Fan"}),
        }
        self.png = artwork()
        self.routes = {"/UserViews", "/Items", "/Items/Latest", "/UserItems/Resume", "/Shows/NextUp",
                       "/Users/AuthenticateByName", f"/Shows/{SERIES}/Seasons", f"/Shows/{SERIES}/Episodes",
                       "/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped"}
        for identifier in self.items:
            self.routes.update({f"/Items/{identifier}", f"/Items/{identifier}/PlaybackInfo",
                                f"/Items/{identifier}/Images/Primary", f"/Items/{identifier}/Images/Backdrop/0",
                                f"/UserItems/{identifier}/UserData", f"/Videos/{identifier}/stream",
                                f"/Videos/{identifier}/fixture-source-{identifier}/Subtitles/2/Stream.vtt"})
        if config.home_assistant:
            self.routes.update({"/api/states", "/api/services"})
            self.routes.update(f"/api/states/{entity}" for entity in self.entities)
            self.routes.update(f"/api/services/{domain}/{service}" for domain in ("light", "switch", "homeassistant")
                               for service in ("turn_on", "turn_off", "toggle"))
        super().__init__(address, Handler)

    def server_close(self):
        self.closing.set()
        super().server_close()

    def handle_error(self, request, client_address):
        # Never print request lines, Authorization values, query strings or bodies.
        pass

    def snapshot(self):
        with self.lock:
            return deepcopy(dict(FIXTURE=True, counts=dict(self.counts), recent=list(self.events),
                                 recentLimit=200, actionAttempts=self.action_attempts,
                                 appliedActions=self.applied_actions))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "VelaFIXTURE/1"
    sys_version = ""

    def log_message(self, *args):
        pass

    def setup(self):
        super().setup()
        self.connection.settimeout(5)

    def do_GET(self):
        self.handle_fixture()

    def do_HEAD(self):
        self.handle_fixture()

    def do_POST(self):
        self.handle_fixture()

    def reply(self, status, body=b"", kind="application/json", headers=None):
        if getattr(self, "event", None) is not None:
            with self.server.lock:
                self.event["status"] = status
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Vela-Fixture", "true")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.close_connection = True
        self.wfile.flush()
        if self.command != "HEAD":
            self.server.closing.wait(self.server.config.delays.get((self.command, self.path_only), 0))
            self.wfile.write(body)

    def json_reply(self, value, status=200, headers=None):
        with self.server.lock:
            payload = json.dumps(value, separators=(",", ":")).encode()
        self.reply(status, payload, headers=headers)

    def body(self):
        if self.headers.get("Transfer-Encoding") is not None:
            raise BadRequest()
        raw_length = self.headers.get("Content-Length", "")
        if not re.fullmatch(r"[0-9]{1,8}", raw_length):
            raise BadRequest(411, "FIXTURE requires Content-Length.")
        length = int(raw_length)
        if length > MAX_BODY:
            raise BadRequest(413, "FIXTURE body limit is 64 KiB.")
        if self.headers.get_content_type() != "application/json":
            raise BadRequest(415, "FIXTURE requires a JSON object.")
        try:
            value = json.loads(self.rfile.read(length))
        except (ValueError, UnicodeError):
            raise BadRequest() from None
        if not isinstance(value, dict):
            raise BadRequest()
        return value

    def query(self, name, default=None):
        values = self.query_args.get(name)
        if values is None:
            return default
        if len(values) != 1:
            raise BadRequest()
        return values[0]

    def page(self, entries):
        try:
            start = int(self.query("startIndex", "0"))
            limit = int(self.query("limit", "50"))
        except ValueError:
            raise BadRequest() from None
        integer(start, 0, 2**31 - 1)
        integer(limit, 1, 500)
        with self.server.lock:
            return dict(Items=deepcopy(entries[start:start + min(limit, self.server.config.page_cap)]),
                        TotalRecordCount=len(entries), StartIndex=start)

    def observed_body(self, body):
        with self.server.lock:
            self.event["body"] = deepcopy(body)

    def handle_fixture(self):
        self.path_only = "/"
        self.event = None
        try:
            if len(self.path) > 4096:
                raise BadRequest(414)
            parsed = urlsplit(self.path)
            path = unquote(parsed.path)
            if parsed.scheme or parsed.netloc or "\\" in path or any(p in (".", "..") for p in path.split("/")):
                raise BadRequest()
            self.path_only = path
            self.query_args = parse_qs(parsed.query, keep_blank_values=True, max_num_fields=30)
            if path == "/__fixture/status" and self.command in ("GET", "HEAD"):
                return self.json_reply(dict(FIXTURE=True, label="Synthetic local acceptance fixture",
                                            itemCount=len(self.server.items), episodesPerSeason=105,
                                            homeAssistant=self.server.config.home_assistant))
            if path == "/__fixture/observations" and self.command in ("GET", "HEAD"):
                return self.json_reply(self.server.snapshot())
            route = path if path in self.server.routes else "<unknown>"
            with self.server.lock:
                self.server.counts[f"{self.command} {route}"] += 1
                self.event = dict(method=self.command, path=route, status=None)
                self.server.events.append(self.event)
            if route == "<unknown>":
                raise BadRequest(404, "Unknown FIXTURE route or item.")
            authenticating = path == "/Users/AuthenticateByName"
            is_home = path.startswith("/api/")
            auth = self.headers.get("Authorization", "")
            if not authenticating:
                if is_home:
                    accepted = auth == f"Bearer {HA_TOKEN}"
                else:
                    accepted = bool(re.search(r'(?:^|,\s*)Token="' + TOKEN + r'"(?:,|$)', auth))
                    if path.endswith("/stream") and self.query("api_key") == TOKEN:
                        accepted = True  # Legacy JellyfinClient.streamUrl compatibility only.
                if not accepted:
                    raise BadRequest(401, "FIXTURE credentials required.")
                if not is_home and self.query("userId", USER) != USER:
                    raise BadRequest(403, "Unknown FIXTURE user.")
            body = self.body() if self.command == "POST" else {}
            if is_home and self.command == "POST" and path.startswith("/api/services/"):
                # Counts received calls, including an injected failure, separately from applied effects.
                with self.server.lock:
                    self.server.action_attempts += 1
            with self.server.lock:
                failure = self.server.config.failures.get((self.command, path))
                failure_status = None
                if failure and failure.remaining != 0:
                    if failure.remaining is not None:
                        failure.remaining -= 1
                    failure_status = failure.status
            if failure_status is not None:
                return self.json_reply(dict(FIXTURE=True, error="Injected FIXTURE failure."), failure_status,
                                       {"Retry-After": "0"} if failure_status == 503 else None)
            self.dispatch(path, body, is_home)
        except BadRequest as error:
            self.json_reply(dict(FIXTURE=True, error=error.message), error.status)
        except (ValueError, UnicodeError):
            self.json_reply(dict(FIXTURE=True, error="Invalid FIXTURE request."), 400)
        except OSError:
            pass  # Expected when acceptance tests cancel a delayed response.

    def dispatch(self, path, body, is_home):
        parts = path.strip("/").split("/")
        method = self.command
        if is_home:
            return self.home(parts, body)
        if path == "/Users/AuthenticateByName" and method == "POST":
            if body.get("Username") != "fixture" or body.get("Pw") != "fixture":
                raise BadRequest(401, "FIXTURE sign-in rejected.")
            return self.json_reply(dict(AccessToken=TOKEN, User={"Id": USER, "Name": "FIXTURE Viewer"}))
        if method in ("GET", "HEAD"):
            if path == "/UserViews":
                return self.json_reply(self.page([self.server.items[k] for k in ("fixture-movies", "fixture-tv")]))
            if path in ("/Items", "/Items/Latest", "/UserItems/Resume", "/Shows/NextUp"):
                entries = list(self.server.items.values())
                parent = self.query("parentId")
                if parent and parent not in self.server.items:
                    raise BadRequest(404)
                if path == "/Items":
                    term = self.query("searchTerm")
                    if term is not None:
                        entries = [i for i in entries if i["Type"] in ("Movie", "Series", "Episode") and term.casefold() in i["Name"].casefold()]
                    else:
                        entries = [i for i in entries if i.get("ParentId") == parent]
                        entries.sort(key=lambda i: (i["Name"], i["Id"]))
                elif path == "/Items/Latest":
                    entries = [i for i in entries if i["Type"] in ("Movie", "Series") and (not parent or i.get("ParentId") == parent)]
                elif path == "/UserItems/Resume":
                    entries = [i for i in entries if i["UserData"]["PlaybackPositionTicks"] > 0 and not i.get("IsMissing")]
                else:
                    if self.query("seriesId", SERIES) != SERIES:
                        raise BadRequest(404)
                    entries = [self.server.items["fixture-s01e002"]]
                return self.json_reply(self.page(entries))
            if parts[:2] == ["Shows", SERIES]:
                if parts[2] == "Seasons":
                    entries = [i for i in self.server.items.values() if i["Type"] == "Season"]
                else:
                    entries = [i for i in self.server.items.values() if i["Type"] == "Episode"]
                    season = self.query("seasonId")
                    if season and season not in ("fixture-season-1", "fixture-season-2"):
                        raise BadRequest(404)
                    entries = [i for i in entries if (not season or i["SeasonId"] == season)
                               and (self.query("isMissing") != "false" or not i.get("IsMissing"))]
                    start = self.query("startItemId")
                    if start:
                        index = next((n for n, i in enumerate(entries) if i["Id"] == start), None)
                        if index is None:
                            raise BadRequest(404)
                        entries = entries[index:]
                return self.json_reply(self.page(entries))
            if parts[0] == "Items":
                item = self.server.items[parts[1]]
                if len(parts) == 2:
                    if item["Id"] == "fixture-item-error":
                        raise BadRequest(self.server.config.item_error_status, "Deliberate FIXTURE item error.")
                    return self.json_reply(item)
                if parts[2] == "Images":
                    if not item["ImageTags"]:
                        raise BadRequest(404, "FIXTURE artwork intentionally absent.")
                    return self.reply(200, self.server.png, "image/png")
            if parts[0] == "Videos":
                if parts[-1] == "stream":
                    return self.media(parts[1])
                return self.reply(200, VTT, "text/vtt; charset=utf-8")
        if method == "POST":
            if len(parts) == 3 and parts[0] == "Items" and parts[2] == "PlaybackInfo":
                return self.playback(parts[1], body)
            if parts[0] == "Sessions":
                return self.report(body)
            if parts[0] == "UserItems" and parts[-1] == "UserData":
                position = integer(body.get("PlaybackPositionTicks"))
                update = dict(PlaybackPositionTicks=position)
                if "Played" in body:
                    if type(body["Played"]) is not bool:
                        raise BadRequest()
                    update["Played"] = body["Played"]
                if "LastPlayedDate" in body:
                    date = body["LastPlayedDate"]
                    if not isinstance(date, str) or len(date) > 40:
                        raise BadRequest()
                    update["LastPlayedDate"] = datetime.fromisoformat(date.replace("Z", "+00:00")).isoformat()
                with self.server.lock:
                    self.server.items[parts[1]]["UserData"].update(update)
                    saved = deepcopy(self.server.items[parts[1]]["UserData"])
                self.observed_body(update)
                return self.json_reply(saved)
        raise BadRequest(405, "Unsupported FIXTURE method.")

    def playable(self, identifier):
        item = self.server.items[identifier]
        if item["Type"] not in ("Movie", "Episode") or item.get("IsMissing"):
            raise BadRequest(400, "FIXTURE item is not playable.")
        if identifier == "fixture-item-error":
            raise BadRequest(self.server.config.item_error_status, "Deliberate FIXTURE item error.")
        return item

    def playback(self, identifier, body):
        item = self.playable(identifier)
        if body.get("UserId") != USER:
            raise BadRequest(403)
        integer(body.get("StartTimeTicks", 0))
        if body.get("EnableDirectPlay") is not True or body.get("EnableTranscoding") is not False:
            raise BadRequest(400, "FIXTURE expects direct play with transcoding disabled.")
        source = f"fixture-source-{identifier}"
        with self.server.lock:
            self.server.session_serial += 1
            session = f"fixture-session-{self.server.session_serial:06}"
            self.server.sessions[session] = identifier
            if len(self.server.sessions) > 128:
                self.server.sessions.popitem(last=False)
        self.observed_body(dict(ItemId=identifier, StartTimeTicks=body.get("StartTimeTicks", 0)))
        self.json_reply(dict(PlaySessionId=session, MediaSources=[dict(
            Id=source, Container="mp4", RunTimeTicks=item["RunTimeTicks"], SupportsDirectPlay=True,
            SupportsDirectStream=False, SupportsTranscoding=False, RequiresOpening=False, IsInfiniteStream=False,
            DefaultAudioStreamIndex=1, DefaultSubtitleStreamIndex=2,
            MediaStreams=[dict(Index=0, Type="Video", Codec="h264"),
                          dict(Index=1, Type="Audio", Codec="aac", Language="eng", IsDefault=True),
                          dict(Index=2, Type="Subtitle", Codec="vtt", Language="eng", IsExternal=True,
                               DeliveryMethod="External", DisplayTitle="FIXTURE English", IsDefault=True,
                               DeliveryUrl=f"/Videos/{identifier}/{source}/Subtitles/2/Stream.vtt")])]))

    def association(self, identifier, source, session):
        if source is not None and source != f"fixture-source-{identifier}":
            raise BadRequest(400, "FIXTURE media source mismatch.")
        with self.server.lock:
            if session is not None and (not isinstance(session, str) or self.server.sessions.get(session) != identifier):
                raise BadRequest(400, "FIXTURE play session mismatch or expired session.")

    def report(self, body):
        identifier = body.get("ItemId")
        if not isinstance(identifier, str) or identifier not in self.server.items:
            raise BadRequest(404)
        self.playable(identifier)
        self.association(identifier, body.get("MediaSourceId"), body.get("PlaySessionId"))
        safe = dict(ItemId=identifier, PositionTicks=integer(body.get("PositionTicks")))
        for key in ("IsPaused", "CanSeek", "Failed"):
            if key in body:
                if type(body[key]) is not bool:
                    raise BadRequest()
                safe[key] = body[key]
        for key in ("AudioStreamIndex", "SubtitleStreamIndex"):
            if key in body:
                safe[key] = integer(body[key], -1, 100)
        for key in ("MediaSourceId", "PlaySessionId"):
            if key in body:
                safe[key] = body[key]
        self.observed_body(safe)
        self.reply(204)

    def media(self, identifier):
        self.playable(identifier)
        self.association(identifier, self.query("mediaSourceId"), self.query("playSessionId"))
        size = self.server.config.media.stat().st_size
        start, end, status = 0, size - 1, 200
        headers = {"Accept-Ranges": "bytes"}
        requested = self.headers.get("Range") if self.command == "GET" else None
        mode = self.server.config.range_mode
        if requested and mode != "ignore":
            match = re.fullmatch(r"bytes=([0-9]{0,20})-([0-9]{0,20})", requested)
            if not match or not any(match.groups()):
                raise BadRequest(400, "FIXTURE accepts one byte range only.")
            first, last = match.groups()
            if first:
                start, end = int(first), min(int(last), size - 1) if last else size - 1
            else:
                start = max(0, size - int(last))
            if start >= size or end < start:
                return self.reply(416, headers={"Content-Range": f"bytes */{size}", **headers})
            status = 206
            headers["Content-Range"] = f"bytes {start + (mode == 'wrong-start')}-{end}/{size}"
        length = end - start + 1
        with self.server.lock:
            self.event["status"] = status
        self.send_response(status)
        for key, value in {"Content-Type": "video/mp4", "Content-Length": str(length), "X-Vela-Fixture": "true",
                           "Connection": "close", **headers}.items():
            self.send_header(key, value)
        self.end_headers()
        self.close_connection = True
        self.wfile.flush()
        if self.command == "HEAD":
            return
        self.server.closing.wait(self.server.config.delays.get((self.command, self.path_only), 0))
        remaining = length - (mode == "truncate")
        with self.server.config.media.open("rb") as media:
            media.seek(start)
            while remaining > 0:
                chunk = media.read(min(65_536, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    def home(self, parts, body):
        if self.command in ("GET", "HEAD"):
            if parts[1] == "states":
                if len(parts) == 2:
                    return self.json_reply(list(self.server.entities.values()))
                return self.json_reply(self.server.entities[parts[2]])
            return self.json_reply([dict(domain=d, services={s: {"description": "FIXTURE action"}
                                   for s in ("turn_on", "turn_off", "toggle")}) for d in ("light", "switch", "homeassistant")])
        if self.command != "POST" or len(parts) != 4 or parts[1] != "services":
            raise BadRequest(405)
        domain, service = parts[2:]
        target = body.get("entity_id")
        targets = [target] if isinstance(target, str) else target
        if not isinstance(targets, list) or not 1 <= len(targets) <= 2 or any(
                not isinstance(t, str) or t not in self.server.entities or
                (domain != "homeassistant" and not t.startswith(domain + ".")) for t in targets):
            raise BadRequest(400, "FIXTURE requires known, explicit entity_id targets.")
        if len(set(targets)) != len(targets):
            raise BadRequest()
        changed = []
        with self.server.lock:
            for target in targets:
                entity = self.server.entities[target]
                entity["state"] = ("off" if entity["state"] == "on" else "on") if service == "toggle" else service[5:]
                changed.append(deepcopy(entity))
            self.server.applied_actions += 1
        self.observed_body(dict(entity_id=targets))
        self.json_reply(changed)


def route_option(text, delay=False):
    try:
        fields = text.split(":")
        if len(fields) not in ((3,) if delay else (3, 4)):
            raise ValueError()
        method, path, number = fields[:3]
        if method not in ("GET", "HEAD", "POST") or not re.fullmatch(r"/[A-Za-z0-9_./-]+", path) or ".." in path:
            raise ValueError()
        if delay:
            value = float(number)
            if not 0 <= value <= 30:
                raise ValueError()
        else:
            status = int(number)
            if status not in (400, 401, 403, 404, 500, 503):
                raise ValueError()
            remaining = int(fields[3]) if len(fields) == 4 else None
            if remaining is not None and remaining <= 0:
                raise ValueError()
            value = Failure(status, remaining)
        return (method, path), value
    except ValueError:
        raise argparse.ArgumentTypeError("Expected METHOD:/exact/path:seconds (0..30)" if delay else
                                         "Expected METHOD:/exact/path:status[:positive-count]") from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1", help="Default: loopback. IPv4 only; use a local test tunnel for emulator access.")
    parser.add_argument("--port", type=int, default=8096)
    parser.add_argument("--media", type=Path, required=True, help="One explicitly selected generated MP4; no directory serving.")
    parser.add_argument("--duration", type=int, default=60, help="Match generated media duration in seconds (5..120).")
    parser.add_argument("--page-cap", type=int, default=100)
    parser.add_argument("--home-assistant", action="store_true")
    parser.add_argument("--item-error-status", type=int, choices=(401, 403, 404, 500, 503), default=404)
    parser.add_argument("--range-mode", choices=("normal", "ignore", "wrong-start", "truncate"), default="normal")
    parser.add_argument("--fail", action="append", type=route_option, default=[], metavar="METHOD:/path:STATUS[:COUNT]")
    parser.add_argument("--delay", action="append", type=lambda value: route_option(value, delay=True), default=[], metavar="METHOD:/path:SECONDS")
    args = parser.parse_args()
    if not 0 <= args.port <= 65535:
        parser.error("port must be 0..65535 (0 chooses a free port)")
    try:
        config = Config(args.media, args.duration, args.page_cap, args.home_assistant,
                        args.item_error_status, args.range_mode, dict(args.fail), dict(args.delay))
        server = FixtureServer((args.host, args.port), config)
    except (ValueError, OSError):
        parser.error("Cannot open media or bind fixture; check --media, --host, --port and numeric bounds.")
    print(f"FIXTURE listening on http://{server.server_address[0]}:{server.server_address[1]} (synthetic local data only)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
