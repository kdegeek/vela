#!/usr/bin/env python3
"""Cache upstream NVIDIA archives and freeze observed digests before extraction.

Most NVIDIA registry URLs have no published digest. The first receipt records
bytes obtained over verified HTTPS; later runs enforce that receipt. It is not
an independently published NVIDIA checksum or a redistributable vendor bundle.
"""
import argparse
import json
from pathlib import Path
import re
import sys
import urllib.request

from vela_shield import ROOT, BuildError, atomic_write, sha256, write_json

EXTENSIONS = {"git": "sh", "blob": "zip", "nv-recovery-ota": "zip", "l4t": "tbz2"}


class HttpsRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        if not newurl.startswith("https://"):
            raise BuildError("Vendor download attempted a non-HTTPS redirect.")
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def validate_archive(file, extension):
    with file.open("rb") as stream:
        prefix = stream.read(512)
    if (extension == "zip" and not prefix.startswith(b"PK\x03\x04")) or (
            extension == "tbz2" and not prefix.startswith(b"BZh")) or (
            extension == "sh" and not prefix.startswith(b"#!")):
        raise BuildError(f"{file.name} is not a {extension} archive; a login/error page may have been returned.")


def fetch(cache):
    cache.mkdir(parents=True, exist_ok=True)
    receipt_file = cache / "vendor-inputs.json"
    receipt = json.loads(receipt_file.read_text()) if receipt_file.exists() else {
        "schema_version": 1, "device": "sif", "digest_authority": "observed-first-fetch-over-https", "archives": {}}
    if receipt.get("schema_version") != 1 or receipt.get("device") != "sif":
        raise BuildError("Unrecognized vendor cache receipt.")
    lock_file = ROOT / "upstream-lock.json"
    lock = json.loads(lock_file.read_text())
    receipt["upstream_lock_sha256"] = sha256(lock_file)
    opener = urllib.request.build_opener(HttpsRedirect())
    for item in lock["vendor_blobs"]["archives"]:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", item["name"]) or not item["url"].startswith("https://"):
            raise BuildError("Unrecognized vendor registry entry.")
        extension = EXTENSIONS[item["type"]]
        name = item["name"] + "." + extension
        file = cache / name
        previous = receipt["archives"].get(name)
        if previous and previous["url"] != item["url"]:
            raise BuildError(f"Source URL changed for {name}; use a separate reviewed cache.")
        if not file.exists():
            partial = cache / (name + ".partial")
            print("Downloading " + name, flush=True)
            with opener.open(item["url"], timeout=90) as response, partial.open("wb") as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
            validate_archive(partial, extension)
            expected = item.get("sha256") or (previous or {}).get("sha256")
            if expected and sha256(partial) != expected:
                raise BuildError(f"Checksum mismatch for {name}; the cache was not replaced.")
            partial.replace(file)
        validate_archive(file, extension)
        digest = sha256(file)
        expected = item.get("sha256") or (previous or {}).get("sha256")
        if expected and digest != expected:
            raise BuildError(f"Vendor cache checksum mismatch: {name}")
        receipt["archives"][name] = {"url": item["url"], "sha256": digest,
                                      "bytes": file.stat().st_size, "published_sha256": item.get("sha256")}
        write_json(receipt_file, receipt)
        print(name + " SHA256 " + digest, flush=True)
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    args = parser.parse_args()
    try:
        fetch(args.cache)
    except (BuildError, OSError, ValueError, KeyError) as error:
        print(f"vela-vendor: {error}", file=sys.stderr)
        sys.exit(2)
