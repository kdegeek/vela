#!/usr/bin/env python3
"""Take a QMP screendump of the VM's display, save it as PNG and check it isn't blank.

Usage: screendump.py <qmp-socket> <out.png> [--width W --height H] [--glyph-box X0,Y0,X1,Y1]

Fails (exit 1) if the picture is not W x H (default 3840 x 2160), if 98 % or more of its pixels
are one colour (a black or blank screen), or, with --glyph-box, if the box is near-uniform: the
largest per-channel spread (max - min) inside it must reach --min-spread (default 80). Measured on
the Home tile's glyph: about 200 with the glyph drawn, 2 without. Needs python3-numpy and
python3-pil.
"""
import argparse
import json
import os
import socket
import sys
import tempfile

import numpy as np
from PIL import Image


def qmp(sock_path: str, command: str, arguments: dict) -> dict:
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(60)
    s.connect(sock_path)
    f = s.makefile("rw")

    def read_reply() -> dict:
        while True:
            line = f.readline()
            if not line:
                raise RuntimeError("QMP connection closed")
            msg = json.loads(line)
            if "return" in msg or "error" in msg:
                return msg  # skip greetings and events

    json.loads(f.readline())  # greeting
    f.write(json.dumps({"execute": "qmp_capabilities"}) + "\n")
    f.flush()
    read_reply()
    f.write(json.dumps({"execute": command, "arguments": arguments}) + "\n")
    f.flush()
    reply = read_reply()
    s.close()
    if "error" in reply:
        raise RuntimeError(f"QMP {command}: {reply['error']}")
    return reply


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("qmp_socket")
    ap.add_argument("out_png")
    ap.add_argument("--width", type=int, default=3840)
    ap.add_argument("--height", type=int, default=2160)
    ap.add_argument("--glyph-box", help="x0,y0,x1,y1 in pixels: a region that must not be uniform")
    ap.add_argument("--min-spread", type=int, default=80)
    a = ap.parse_args()

    with tempfile.TemporaryDirectory() as tmp:
        ppm = os.path.join(tmp, "screen.ppm")
        qmp(a.qmp_socket, "screendump", {"filename": ppm})  # PPM is the default format
        img = Image.open(ppm).convert("RGB")
        img.load()
    img.save(a.out_png, optimize=False)

    px = np.asarray(img).reshape(-1, 3)
    packed = (px[:, 0].astype(np.uint32) << 16) | (px[:, 1].astype(np.uint32) << 8) | px[:, 2]
    values, counts = np.unique(packed, return_counts=True)
    top = int(counts.argmax())
    share = counts[top] / packed.size
    print(f"{a.out_png}: {img.width}x{img.height}, {len(values)} colours, "
          f"most common #{int(values[top]):06x} covers {share:.1%} of pixels")

    ok = True
    if (img.width, img.height) != (a.width, a.height):
        print(f"FAIL: expected {a.width}x{a.height}", file=sys.stderr)
        ok = False
    if share >= 0.98:
        print("FAIL: the screen is (almost) a single colour", file=sys.stderr)
        ok = False
    if a.glyph_box:
        x0, y0, x1, y1 = (int(v) for v in a.glyph_box.split(","))
        box = np.asarray(img)[y0:y1, x0:x1].astype(np.int16)
        spread = int((box.max(axis=(0, 1)) - box.min(axis=(0, 1))).max()) if box.size else 0
        print(f"glyph box {a.glyph_box}: spread {spread} (need >= {a.min_spread})")
        if spread < a.min_spread:
            print("FAIL: the glyph box is near-uniform (glyph not drawn)", file=sys.stderr)
            ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
