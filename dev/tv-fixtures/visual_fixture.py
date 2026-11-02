#!/usr/bin/env python3
"""Loopback-only visual acceptance fixture; real API shapes with labelled synthetic content."""

import argparse
from pathlib import Path

from fixture import Config, FixtureServer, Handler


class VisualHandler(Handler):
    def reply(self, status, body=b"", kind="application/json", headers=None):
        if status == 200 and kind == "image/png" and self.path_only.startswith("/Items/"):
            image = self.server.visual_artwork.get(self.path_only.split("/")[2])
            if image is not None:
                body, kind = image, "image/webp"
        return super().reply(status, body, kind, headers)


def make_server(media, port=18096, duration=60):
    assets = Path(__file__).resolve().parents[2] / "docs/design/explorations/liquid-glass-20261031/mocks/assets"
    # These four public reference assets are the only extra bytes exposed. The
    # base handler still owns known routes, absent-art behavior and authentication.
    photos = {name: (assets / f"{name}.webp").read_bytes() for name in ("north", "train", "kyoto", "table")}
    server = FixtureServer(("127.0.0.1", port), Config(media, duration))
    server.visual_artwork = {
        "fixture-movie": photos["north"],
        "fixture-series": photos["train"],
        "fixture-s01e001": photos["train"],
        "fixture-long-title": photos["kyoto"],
        "fixture-item-error": photos["table"],
    }
    server.RequestHandlerClass = VisualHandler
    return server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--media", type=Path, required=True)
    parser.add_argument("--duration", type=int, default=60)
    parser.add_argument("--port", type=int, default=18096)
    args = parser.parse_args()
    if not 0 <= args.port <= 65535:
        parser.error("port must be 0..65535")
    try:
        server = make_server(args.media, args.port, args.duration)
    except (OSError, ValueError):
        parser.error("Cannot open the fixture inputs or bind the loopback port.")
    print(f"VISUAL FIXTURE listening on http://127.0.0.1:{server.server_address[1]} (synthetic data, reference photos)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
