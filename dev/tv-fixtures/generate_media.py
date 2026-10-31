#!/usr/bin/env python3
"""Generate an original FIXTURE MP4 outside this checkout; requires existing ffmpeg."""

import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile


def output_path(value):
    path = Path(value).expanduser().resolve()
    checkout = Path(__file__).resolve().parents[2]
    if path == checkout or checkout in path.parents:
        raise argparse.ArgumentTypeError("FIXTURE media must be outside the checkout; use a temporary directory.")
    if path.suffix.lower() != ".mp4":
        raise argparse.ArgumentTypeError("Output must end in .mp4")
    if path.exists():
        raise argparse.ArgumentTypeError("Refusing to overwrite an existing file.")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=output_path, help="Default: a new temporary directory; prints its MP4 path.")
    parser.add_argument("--duration", type=int, default=60, help="Seconds, 5..120; match fixture.py --duration.")
    args = parser.parse_args()
    if not 5 <= args.duration <= 120:
        parser.error("duration must be 5..120")
    executable = shutil.which("ffmpeg")
    if executable is None:
        parser.error("ffmpeg must already be installed; this script installs nothing.")
    try:
        output = args.output or output_path(Path(tempfile.mkdtemp(prefix="vela-tv-fixture-")) / "fixture.mp4")
    except argparse.ArgumentTypeError as error:
        parser.error(str(error))
    output.parent.mkdir(parents=True, exist_ok=True)
    command = [executable, "-hide_banner", "-loglevel", "error", "-n",
               "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=24",
               "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
               "-t", str(args.duration), "-c:v", "libx264", "-preset", "ultrafast", "-crf", "28",
               "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-g", "48",
               "-c:a", "aac", "-b:a", "96k", "-ac", "2", "-metadata:s:a:0", "language=eng",
               "-metadata", "title=Vela FIXTURE original synthetic test pattern",
               "-movflags", "+faststart", str(output)]
    try:
        subprocess.run(command, check=True)
    except subprocess.CalledProcessError:
        parser.exit(1, "FIXTURE generation failed; ffmpeg output is above.\n")
    print(output)


if __name__ == "__main__":
    main()
