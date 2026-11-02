#!/usr/bin/env python3
"""Offline integrity/provenance verifier for the locked Pi 5 raw SD image."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from typing import BinaryIO, Mapping, Optional, Sequence
SECTOR_SIZE = 512
MIB = 1024 * 1024
EXPECTED_IMAGE_BYTES = 15_360_000_000
EXPECTED_PARTITION_MIB = {1: 128, 2: 3476, 5: 3072, 6: 384, 7: 16}
EXTENDED_TYPES = frozenset((0x05, 0x0F, 0x85))
FAT32_TYPES = frozenset((0x0B, 0x0C))
ROOT = Path(__file__).resolve().parent
LOCK_PATH = ROOT / "upstream-lock.json"
PRODUCT_ROOT = ROOT / "product"
PERMISSIONS = PRODUCT_ROOT / "permissions" / "privapp-permissions-app.vela.tv.xml"
HOME = PRODUCT_ROOT / "preferred-apps" / "vela-home.xml"
INPUT_MANIFEST = ROOT / "app-input.json"
SYSTEM_EXT_ROOTS = ("/system/system_ext", "/system_ext")
TESTKEY_CERT_SHA256 = "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"
APK_SIGNATURE = re.compile(
    r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))$", re.I
)
DTB_NAME = re.compile(r"\b(bcm2712[^ \t\r\n/]*\.dtb)\b", re.I)
ARM64_SO = re.compile(r"^lib/arm64-v8a/[^/]+\.so$", re.I)
class VerificationError(ValueError):
    pass
def _read(source: BinaryIO, offset: int, size: int) -> bytes:
    if offset < 0 or size < 0:
        raise VerificationError("negative read range")
    source.seek(offset)
    value = source.read(size)
    if len(value) != size:
        raise VerificationError(f"short read at {offset}: wanted {size}, got {len(value)}")
    return value
def _entry(raw: bytes, slot: int, table: str = "MBR") -> dict:
    if len(raw) != 16:
        raise VerificationError(f"{table} entry {slot} is not 16 bytes")
    start, count = struct.unpack_from("<II", raw, 8)
    return {"slot": slot, "table": table, "boot": raw[0], "type": raw[4],
            "start_lba": start, "sector_count": count, "end_lba_exclusive": start + count,
            "empty": raw[0] == 0 and raw[4] == 0 and start == 0 and count == 0}
def _interval(part: Mapping[str, object]) -> tuple[int, int]:
    return int(part["start_lba"]), int(part["end_lba_exclusive"])
def _overlap(a: tuple[int, int], b: tuple[int, int]) -> bool:
    return a[0] < b[1] and b[0] < a[1]
def _inside(a: int, b: int, low: int, high: int) -> bool:
    return low <= a < b <= high
def _parse_table(source: BinaryIO, image_bytes: int) -> dict:
    if image_bytes != EXPECTED_IMAGE_BYTES:
        raise VerificationError(f"image size {image_bytes} != {EXPECTED_IMAGE_BYTES} bytes")
    if image_bytes % SECTOR_SIZE:
        raise VerificationError("image is not a whole number of 512-byte sectors")
    sectors = image_bytes // SECTOR_SIZE
    mbr = _read(source, 0, SECTOR_SIZE)
    if mbr[510:512] != b"\x55\xaa":
        raise VerificationError("MBR signature is not 0x55aa")
    primaries = [_entry(mbr[446 + 16 * i:462 + 16 * i], i + 1) for i in range(4)]
    for part in primaries:
        part['number'] = part['slot']
        if part["empty"]:
            continue
        if part["boot"] not in (0, 0x80):
            raise VerificationError(f"p{part['slot']} has invalid boot flag 0x{part['boot']:02x}")
        if not part["sector_count"] or part["start_lba"] == 0:
            raise VerificationError(f"p{part['slot']} has an empty extent")
        if part["end_lba_exclusive"] > sectors:
            raise VerificationError(f"p{part['slot']} ends past image")
    by_number = {p["slot"]: p for p in primaries if not p["empty"]}
    p1, p2, p3 = by_number.get(1), by_number.get(2), by_number.get(3)
    if not p1 or not p2 or not p3:
        raise VerificationError("MBR must contain non-empty primary p1, p2, and p3")
    if not primaries[3]["empty"]:
        raise VerificationError("MBR p4 must be empty")
    if p1["boot"] != 0x80 or p2["boot"] != 0 or p3["boot"] != 0:
        raise VerificationError("invalid active flag combination: only p1 may be active")
    if p1["type"] not in FAT32_TYPES:
        raise VerificationError("p1 is not FAT32 W95 LBA (0x0b/0x0c)")
    if p2["type"] not in EXTENDED_TYPES:
        raise VerificationError("p2 is not an MBR extended type")
    if p3["type"] != 0x83:
        raise VerificationError("p3 is not Linux/ext4 type 0x83")
    for number, part in ((1, p1), (2, p2)):
        expected = EXPECTED_PARTITION_MIB[number] * MIB // SECTOR_SIZE
        if part["sector_count"] != expected:
            raise VerificationError(f"p{number} is not exactly {EXPECTED_PARTITION_MIB[number]} MiB")
    if p3["end_lba_exclusive"] != sectors:
        raise VerificationError("p3 userdata does not end at the image boundary")
    if p3["start_lba"] < p2["end_lba_exclusive"]:
        raise VerificationError("p3 userdata starts inside and overlaps the p2 container")
    for i, first in enumerate((p1, p2, p3)):
        for second in (p1, p2, p3)[i + 1:]:
            if _overlap(_interval(first), _interval(second)):
                raise VerificationError(f"primary p{first['slot']} overlaps p{second['slot']}")
    ext_start, ext_end = _interval(p2)
    logical: list[dict] = []
    visited: set[int] = set()
    current = ext_start
    for index in range(128):
        if current in visited:
            raise VerificationError(f"extended EBR loop at LBA {current}")
        visited.add(current)
        if not _inside(current, current + 1, ext_start, ext_end):
            raise VerificationError(f"EBR LBA {current} is outside p2")
        block = _read(source, current * SECTOR_SIZE, SECTOR_SIZE)
        if block[510:512] != b"\x55\xaa":
            raise VerificationError(f"EBR at LBA {current} has no 0x55aa signature")
        entries = [_entry(block[446 + 16 * i:462 + 16 * i], i + 1, f"EBR@{current}")
                   for i in range(4)]
        if not entries[0]["type"] or not entries[0]["sector_count"]:
            raise VerificationError(f"EBR at LBA {current} has no logical data entry")
        if entries[0]["boot"] not in (0, 0x80) or entries[1]["boot"] not in (0, 0x80):
            raise VerificationError(f"EBR at LBA {current} has an invalid boot flag")
        if not entries[2]["empty"] or not entries[3]["empty"]:
            raise VerificationError(f"EBR at LBA {current} uses reserved entries")
        number = 5 + index
        data = dict(entries[0])
        data.update({"number": number, "ebr_lba": current,
                     "relative_start_lba": entries[0]["start_lba"],
                     "start_lba": current + entries[0]["start_lba"]})
        data["end_lba_exclusive"] = data["start_lba"] + data["sector_count"]
        if data["type"] != 0x83:
            raise VerificationError(f"logical p{number} is not ext4 type 0x83")
        if (data["start_lba"] <= current or not _inside(
                data["start_lba"], data["end_lba_exclusive"], ext_start, ext_end)):
            raise VerificationError(f"logical p{number} is outside p2 or overlaps its EBR")
        if any(_overlap(_interval(data), _interval(old)) for old in logical):
            raise VerificationError(f"logical p{number} overlaps another logical partition")
        if any(_overlap((current, current + 1), _interval(old)) for old in logical):
            raise VerificationError(f"EBR at LBA {current} overlaps logical data")
        expected = EXPECTED_PARTITION_MIB.get(number)
        if expected is not None and data["sector_count"] != expected * MIB // SECTOR_SIZE:
            raise VerificationError(f"p{number} is not exactly {expected} MiB")
        logical.append(data)
        link = entries[1]
        if link["empty"]:
            break
        if link["type"] not in EXTENDED_TYPES:
            raise VerificationError(f"EBR link at LBA {current} is not extended")
        next_lba = ext_start + link["start_lba"]
        if next_lba <= current or not (ext_start <= next_lba < ext_end):
            raise VerificationError(f"EBR link from LBA {current} points outside p2 or loops")
        if link["sector_count"] and not _inside(
                next_lba, next_lba + link["sector_count"], ext_start, ext_end):
            raise VerificationError(f"EBR link from LBA {current} exceeds p2")
        current = next_lba
    else:
        raise VerificationError("extended EBR chain exceeds 128 entries")
    if [p["number"] for p in logical] != [5, 6, 7]:
        raise VerificationError("logical chain must contain p5, p6, p7 in order")
    return {
        "ok": True, "image_sectors": sectors,
        "primaries": primaries, "logical_partitions": logical,
        "partitions": [*primaries, *logical], "errors": [],
    }
def parse_partition_table(source: BinaryIO, image_bytes: Optional[int] = None) -> dict:
    if image_bytes is None:
        position = source.tell()
        source.seek(0, os.SEEK_END)
        image_bytes = source.tell()
        source.seek(position)
    try:
        return _parse_table(source, int(image_bytes))
    except (OSError, VerificationError, ValueError) as exc:
        return {"ok": False, "errors": [str(exc)], "partitions": [],
                "logical_partitions": []}
def inspect_ext4(source: BinaryIO, partition: Mapping[str, object],
                 expected_label: Optional[str] = None,
                 expected_inode_size: Optional[int] = None) -> dict:
    start = int(partition["start_lba"])
    capacity = int(partition["sector_count"]) * SECTOR_SIZE
    try:
        sb = _read(source, start * SECTOR_SIZE + 1024, 1024)
        magic = struct.unpack_from("<H", sb, 56)[0]
        inode = struct.unpack_from("<H", sb, 88)[0]
        log_size = struct.unpack_from("<I", sb, 24)[0]
        block_size = 1024 << log_size if log_size < 31 else 0
        incompat = struct.unpack_from("<I", sb, 96)[0]
        blocks = struct.unpack_from("<I", sb, 4)[0]
        if incompat & 0x80:
            blocks |= struct.unpack_from("<I", sb, 336)[0] << 32
        fs_size = blocks * block_size
        label = sb[120:136].split(b"\0", 1)[0].decode("utf-8", "replace").strip()
        errors = []
        if magic != 0xEF53:
            errors.append(f"magic 0x{magic:04x} != 0xef53")
        if block_size < 1024 or block_size > 65536 or block_size & (block_size - 1):
            errors.append("invalid ext4 block size")
        if fs_size != capacity:
            errors.append(f"filesystem size {fs_size} != partition size {capacity}")
        if inode < 128 or inode > 4096 or inode & (inode - 1):
            errors.append(f"invalid inode size {inode}")
        if expected_label is not None and label != expected_label:
            errors.append(f"label {label!r} != {expected_label!r}")
        if expected_inode_size is not None and inode != expected_inode_size:
            errors.append(f"inode size {inode} != {expected_inode_size}")
        return {"status": "pass" if not errors else "fail", "partition": partition.get("number"),
                "label": label, "magic": f"0x{magic:04x}", "inode_size": inode,
                "block_size": block_size, "blocks": blocks, "filesystem_bytes": fs_size,
                "errors": errors}
    except (OSError, VerificationError, struct.error) as exc:
        return {"status": "fail", "partition": partition.get("number"), "errors": [str(exc)]}
def sha256_file(path: Path, chunk_size: int = 8 * 1024 * 1024) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with Path(path).open("rb") as stream:
        while chunk := stream.read(chunk_size):
            digest.update(chunk)
            size += len(chunk)
    return size, digest.hexdigest()
def _zip_hashes(path: Path) -> tuple[dict[str, str], list[str]]:
    hashes: dict[str, str] = {}
    errors: list[str] = []
    try:
        with zipfile.ZipFile(path) as archive:
            names = set()
            for info in archive.infolist():
                name = info.filename
                if name in names:
                    errors.append(f"duplicate member {name}")
                names.add(name)
                if name.startswith("/") or ".." in Path(name).parts:
                    errors.append(f"unsafe member {name}")
                if not info.is_dir() and not APK_SIGNATURE.fullmatch(name):
                    hashes[name] = hashlib.sha256(archive.read(info)).hexdigest()
            if "AndroidManifest.xml" not in names:
                errors.append("AndroidManifest.xml is missing")
    except (OSError, zipfile.BadZipFile, RuntimeError, KeyError) as exc:
        errors.append(f"invalid APK: {exc}")
    return hashes, errors
def inspect_apk(path: Path, frozen_metadata: Optional[Mapping[str, object]] = None) -> dict:
    errors: list[str] = []
    path = Path(path)
    if not path.is_file():
        return {"status": "fail", "errors": ["input APK does not exist"]}
    try:
        size, digest = sha256_file(path)
    except OSError as exc:
        return {"status": "fail", "errors": [f"cannot hash input APK: {exc}"]}
    if frozen_metadata:
        if frozen_metadata.get("sha256") and digest != frozen_metadata["sha256"]:
            errors.append("APK SHA-256 differs from frozen app-input.json")
        if frozen_metadata.get("bytes") is not None and size != frozen_metadata["bytes"]:
            errors.append("APK byte count differs from frozen app-input.json")
    hashes, zip_errors = _zip_hashes(path)
    errors.extend(zip_errors)
    native: list[str] = []
    try:
        with zipfile.ZipFile(path) as archive:
            for info in archive.infolist():
                if info.is_dir() or not ARM64_SO.fullmatch(info.filename):
                    continue
                body = archive.read(info)
                native.append(info.filename)
                if len(body) < 20 or body[:4] != b"\x7fELF":
                    errors.append(f"{info.filename} is not ELF")
                elif body[4:6] != b"\x02\x01" or struct.unpack_from("<H", body, 18)[0] != 183:
                    errors.append(f"{info.filename} is not little-endian AArch64 ELF64")
                if not body:
                    errors.append(f"{info.filename} is empty")
    except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
        errors.append(str(exc))
    if not native:
        errors.append("APK has no arm64-v8a native library (ARMv7-only rejected)")
    return {"status": "pass" if not errors else "fail", "bytes": size, "sha256": digest,
            "non_signature_members": len(hashes), "arm64_native_members": native, "errors": errors}
def _tool(name: str, supplied: Optional[str] = None) -> Optional[str]:
    if supplied:
        candidate = Path(supplied)
        return str(candidate) if candidate.is_file() and os.access(candidate, os.X_OK) else None
    return shutil.which(name)
def _run(args: Sequence[str], timeout: int = 120) -> subprocess.CompletedProcess:
    return subprocess.run(args, check=False, capture_output=True, text=False, timeout=timeout)
def _output(process: subprocess.CompletedProcess) -> str:
    return ((process.stdout or b"").decode("utf-8", "replace") + "\n" +
            (process.stderr or b"").decode("utf-8", "replace"))
def inspect_boot(image: Path, partition: Mapping[str, object],
                 mtools: Optional[str] = None) -> dict:
    mdir, mcopy = _tool("mdir", mtools), _tool("mcopy")
    if not mdir or not mcopy:
        return {"status": "unknown", "errors": ["mtools mdir/mcopy unavailable"]}
    spec = f"{Path(image).resolve()}@@{int(partition['start_lba']) * SECTOR_SIZE}"
    errors: list[str] = []
    listing = _run([mdir, "-i", spec, "-/", "::"])
    text = _output(listing)
    if listing.returncode:
        errors.append("mtools cannot read boot FAT: " + text[-400:].strip())
    with tempfile.TemporaryDirectory(prefix="vela-boot-") as temp:
        root = Path(temp)
        files = {}
        for name in ("config.txt", "cmdline.txt", "ramdisk.img", "Image"):
            destination = root / name
            copied = _run([mcopy, "-i", spec, "::" + name, str(destination)])
            files[name] = copied.returncode == 0 and destination.is_file() and destination.stat().st_size > 0
            if not files[name]:
                errors.append(f"missing boot file {name}")
        config = root / "config.txt"
        if config.is_file():
            value = config.read_text("utf-8", errors="replace")
            for token in ("kernel=Image", "initramfs ramdisk.img", "dtoverlay=vc4-kms-v3d"):
                if token not in value:
                    errors.append(f"config.txt lacks {token}")
        cmdline = root / "cmdline.txt"
        if cmdline.is_file() and "androidboot.hardware=rpi5" not in cmdline.read_text(errors="replace"):
            errors.append("cmdline.txt lacks androidboot.hardware=rpi5")
        kernel = root / "Image"
        if kernel.is_file() and (kernel.stat().st_size < 64 or
                                 kernel.read_bytes()[56:60] != b"ARM\x64"):
            errors.append("Image lacks ARM64 Linux Image magic")
        dtb_text = _output(_run([mdir, "-i", spec, "::bcm2712*-rpi-*.dtb"]))
        names = sorted(set(DTB_NAME.findall(dtb_text)))
        if not names:
            errors.append("no bcm2712*-rpi-*.dtb in boot FAT")
        for name in names:
            destination = root / Path(name).name
            copied = _run([mcopy, "-i", spec, "::" + Path(name).name, str(destination)])
            if copied.returncode or not destination.is_file():
                errors.append(f"cannot copy {name}")
                continue
            body = destination.read_bytes()
            if len(body) < 40 or struct.unpack_from(">I", body, 0)[0] != 0xD00DFEED:
                errors.append(f"{name} is not an FDT")
            elif struct.unpack_from(">I", body, 4)[0] > len(body):
                errors.append(f"{name} FDT totalsize exceeds file")
        return {"status": "pass" if not errors else "fail", "files": files, "dtbs": names,
                "overlays": sorted(set(re.findall(r"(?:^|[ /])([A-Za-z0-9_.+-]+\.dtbo)\b", text, re.I))),
                "errors": errors}
def _copy_partition(image: Path, partition: Mapping[str, object], destination: Path) -> None:
    remaining = int(partition["sector_count"]) * SECTOR_SIZE
    with image.open("rb") as source, destination.open("wb") as target:
        source.seek(int(partition["start_lba"]) * SECTOR_SIZE)
        while remaining:
            chunk = source.read(min(8 * 1024 * 1024, remaining))
            if not chunk:
                raise VerificationError("short system partition copy")
            target.write(chunk)
            remaining -= len(chunk)
def _dump(debugfs: str, image: Path, member: str, destination: Path) -> bool:
    destination.unlink(missing_ok=True)
    process = _run([debugfs, "-R", f"dump {member} {destination}", str(image)], 180)
    return process.returncode == 0 and destination.is_file() and destination.stat().st_size > 0
def _marker(path: Path) -> tuple[Optional[dict], list[str]]:
    try:
        value = json.loads(path.read_bytes())
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        return None, [f"invalid staging marker: {exc}"]
    return (value, []) if isinstance(value, dict) else (None, ["marker is not an object"])
def marker_errors(value: Mapping[str, object], lock_sha256: Optional[str] = None, frozen_input: Optional[Mapping[str, object]] = None) -> list[str]:
    errors = []
    if value.get("schema_version") != 1:
        errors.append("marker schema_version is not 1")
    if value.get("device") != "rpi5":
        errors.append("marker device is not rpi5")
    if value.get("product") != "vela_rpi5_tv":
        errors.append("marker product is not vela_rpi5_tv")
    display = value.get('display', {})
    if not isinstance(display, dict) or (display.get('mode'), display.get('density')) not in (
            ('3840x2160', 480), ('1920x1080', 240)):
        errors.append('marker display mode/density is not a supported Pi candidate')
    for field in ("source_manifest_sha256", "upstream_lock_sha256"):
        if not re.fullmatch(r"[0-9a-f]{64}", str(value.get(field, ""))):
            errors.append(f"marker {field} is not a SHA-256")
    if lock_sha256 and value.get("upstream_lock_sha256") != lock_sha256:
        errors.append("marker upstream lock hash differs from current lock")
    files = value.get("integration_files")
    if not isinstance(files, dict) or not files:
        errors.append("marker integration_files is empty or missing")
    elif any(not isinstance(k, str) or k.startswith("/") or ".." in Path(k).parts
             or not re.fullmatch(r"[0-9a-f]{64}", str(v))
             for k, v in files.items()):
        errors.append("marker integration_files contains an invalid digest")
    apk = value.get("apk")
    if not isinstance(apk, dict) or not re.fullmatch(r"[0-9a-f]{64}", str(apk.get("sha256", ""))):
        errors.append("marker apk.sha256 is not a SHA-256")
    if frozen_input and isinstance(apk, dict) and apk.get("sha256") != frozen_input.get("sha256"):
        errors.append("marker apk hash differs from frozen app-input.json")
    return errors
def compare_definition(actual: bytes, expected: Path, kind: str) -> dict:
    if not expected.is_file():
        return {"status": "fail", "errors": [f"missing frozen {kind} XML"]}
    if actual == expected.read_bytes():
        return {"status": "pass", "mode": "bytes", "errors": []}
    try:
        left = ET.fromstring(actual)
        right = ET.parse(expected).getroot()
        def shape(node):
            return (node.tag, tuple(sorted(node.attrib.items())), (node.text or '').strip(),
                    tuple(sorted(shape(child) for child in node)), (node.tail or '').strip())
        if shape(left) == shape(right):
            return {"status": "pass", "mode": "semantic", "errors": []}
    except (ET.ParseError, OSError):
        pass
    return {"status": "fail", "mode": "bytes-and-semantics",
            "errors": [f"{kind} XML differs from frozen definition"]}
def inspect_signature(apksigner, apk, expected_cert_sha256=None):
    signer = _tool('apksigner', apksigner)
    if not signer:
        return ['apksigner is required and must be executable']
    process = _run([signer, 'verify', '--verbose', '--print-certs', str(apk)])
    expected = (expected_cert_sha256 or TESTKEY_CERT_SHA256).replace(':', '').lower()
    certs = [x.replace(':', '').lower() for x in re.findall(
        r'certificate SHA-256 digest:\s*([0-9a-f:]{32,95})', _output(process), re.I)]
    errors = []
    if process.returncode:
        errors.append('apksigner rejected installed APK')
    if not re.fullmatch('[0-9a-f]{64}', expected) or certs != [expected]:
        errors.append('installed APK signer certificate digest differs from expected')
    return errors
def read_properties(debugfs, image, root, candidates):
    values = {}
    for index, member in enumerate(candidates):
        target = root / f'properties-{index}'
        if _dump(debugfs, image, member, target):
            for line in target.read_text(errors='replace').splitlines():
                if '=' in line and not line.lstrip().startswith('#'):
                    key, value = line.strip().split('=', 1)
                    values.setdefault(key, set()).add(value)
    return values
def inspect_system(image: Path, partition: Mapping[str, object], input_apk: Optional[Path],
                   expected_marker: Optional[Path], debugfs: Optional[str] = None,
                   apksigner: Optional[str] = None, expected_cert_sha256: Optional[str] = None,
                   frozen_input: Optional[Mapping[str, object]] = None) -> dict:
    tool = _tool("debugfs", debugfs)
    if not tool:
        return {"status": "unknown", "errors": ["debugfs unavailable"]}
    errors: list[str] = []
    if input_apk is None or not input_apk.is_file():
        errors.append("input APK is required for system_ext comparison")
    if expected_marker is None or not expected_marker.is_file():
        errors.append("expected marker is required for system_ext comparison")
    lock_sha = sha256_file(LOCK_PATH)[1] if LOCK_PATH.is_file() else None
    with tempfile.TemporaryDirectory(prefix="vela-system-") as temp:
        root = Path(temp)
        system = root / "system.img"
        try:
            _copy_partition(image, partition, system)
        except (OSError, VerificationError) as exc:
            return {"status": "fail", "errors": [str(exc)]}
        members = {
            "apk": "/priv-app/VelaTv/VelaTv.apk",
            "marker": "/etc/vela/image-inputs.json",
            "permissions": "/etc/permissions/privapp-permissions-app.vela.tv.xml",
            "home": "/etc/preferred-apps/vela-home.xml",
        }
        extracted = {}
        for name, relative in members.items():
            target = root / name
            for base in SYSTEM_EXT_ROOTS:
                candidate = base + relative
                if _dump(tool, system, candidate, target):
                    extracted[name] = target
                    break
            if name not in extracted:
                errors.append(f"missing system_ext member {relative}")
        if "apk" in extracted and input_apk and input_apk.is_file():
            expected_hashes, expected_errors = _zip_hashes(input_apk)
            actual_hashes, actual_errors = _zip_hashes(extracted["apk"])
            errors.extend(expected_errors + actual_errors)
            if expected_hashes != actual_hashes:
                errors.append("installed APK non-signature members differ from input APK")
            installed_arch = inspect_apk(extracted["apk"])
            if installed_arch["status"] != "pass":
                errors.append("installed APK does not contain AArch64 ELF64 native code")
        if "marker" in extracted and expected_marker and expected_marker.is_file():
            actual = extracted["marker"].read_bytes()
            expected = expected_marker.read_bytes()
            if actual != expected:
                errors.append("installed staging marker bytes differ from expected marker")
            value, marker_error = _marker(extracted["marker"])
            errors.extend(marker_error)
            if value is not None:
                errors.extend(marker_errors(value, lock_sha, frozen_input))
        if "permissions" in extracted:
            check = compare_definition(extracted["permissions"].read_bytes(), PERMISSIONS, "permissions")
            errors.extend(check["errors"])
        if "home" in extracted:
            check = compare_definition(extracted["home"].read_bytes(), HOME, "home")
            errors.extend(check["errors"])
        if "apk" in extracted:
            errors.extend(inspect_signature(apksigner, extracted['apk'], expected_cert_sha256))
        return {"status": "pass" if not errors else "fail",
                "temporary_copy": "p5 regular-file copy; no mount or loop device", "errors": errors}
def inspect_vendor(image, partition, marker, debugfs=None):
    tool = _tool('debugfs', debugfs)
    if not tool:
        return {'status': 'fail', 'errors': ['debugfs is required for vendor inspection']}
    expected, errors = _marker(marker) if marker else (None, ['expected marker is required'])
    mode = (expected or {}).get('display', {}).get('mode')
    density = str((expected or {}).get('display', {}).get('density', ''))
    with tempfile.TemporaryDirectory(prefix='vela-vendor-') as temp:
        root = Path(temp)
        vendor = root / 'vendor.img'
        _copy_partition(image, partition, vendor)
        props = read_properties(tool, vendor, root, ('/build.prop', '/vendor/build.prop'))
        modes = props.get('vendor.hwc.drm.force_mode', set())
        if not mode or modes != {mode}:
            errors.append('built vendor.hwc.drm.force_mode differs from staged display mode')
        if props.get('ro.sf.lcd_density') != {density}:
            errors.append('built ro.sf.lcd_density differs from staged display density')
    return {'status': 'pass' if not errors else 'fail', 'display_modes': sorted(modes), 'errors': errors}
def _load_json(path: Path) -> Optional[dict]:
    try:
        value = json.loads(path.read_bytes())
        return value if isinstance(value, dict) else None
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return None
def _check(status: str, detail: str, errors: Optional[list[str]] = None,
           required: bool = True) -> dict:
    return {"status": status, "required": required, "detail": detail, "errors": errors or []}
def verify_image(image: Path, input_apk: Optional[Path] = None,
                 expected_marker: Optional[Path] = None, *,
                 apksigner: Optional[str] = None,
                 expected_cert_sha256: Optional[str] = None,
                 mtools: Optional[str] = None, debugfs: Optional[str] = None) -> dict:
    image, input_apk = Path(image), Path(input_apk) if input_apk else None
    expected_marker = Path(expected_marker) if expected_marker else None
    checks = {}
    report = {
        "schema_version": 1, "verifier": "firmware/pi5/verify_image.py",
        "image": {"path": str(image), "bytes": None, "sha256": None},
        "checks": checks, "limitations": [
            "No boot/runtime/display/audio/media/hardware validation; not flash authorization or production approval."],
    }
    lock = _load_json(LOCK_PATH)
    lock_errors = []
    if not lock or lock.get("build", {}).get("wholeImageBytes") != EXPECTED_IMAGE_BYTES:
        lock_errors.append("upstream lock does not pin whole-image size")
    if not lock or lock.get("target", {}).get("velaProduct") != "vela_rpi5_tv":
        lock_errors.append("upstream lock does not pin vela_rpi5_tv")
    lock_sha = sha256_file(LOCK_PATH)[1] if LOCK_PATH.is_file() else None
    checks["upstream_lock"] = _check(
        "pass" if not lock_errors else "fail", "locked Pi 5 product contract", lock_errors)
    frozen = _load_json(INPUT_MANIFEST)
    checks['frozen_input_definition'] = _check(
        'pass' if frozen and re.fullmatch('[0-9a-f]{64}', str(frozen.get('sha256', '')))
        and isinstance(frozen.get('bytes'), int) else 'fail', 'frozen APK identity is required')
    if not image.is_file():
        checks["image_file"] = _check("fail", "image file does not exist")
        report["ok"] = False
        return report
    try:
        size, digest = sha256_file(image)
        report["image"].update({"bytes": size, "sha256": digest})
        checks["image_file"] = _check("pass", "readable; whole-image SHA-256 computed")
        checks["image_size"] = _check(
            "pass" if size == EXPECTED_IMAGE_BYTES else "fail",
            f"expected {EXPECTED_IMAGE_BYTES} bytes")
        with image.open("rb") as source:
            table = parse_partition_table(source, size)
        report["partition_table"] = table
        checks["partition_table"] = _check(
            "pass" if table["ok"] else "fail", "MBR and EBR geometry", table.get("errors"))
    except (OSError, VerificationError, ValueError) as exc:
        checks["partition_table"] = _check("fail", "partition parsing failed", [str(exc)])
        table = {"ok": False, "partitions": []}
    parts = {int(p["number"]): p for p in table.get("partitions", [])
             if "number" in p and not p.get("empty")}
    report["partitions"] = {str(k): v for k, v in sorted(parts.items())}
    if table.get("ok"):
        with image.open("rb") as source:
            for number in (3, 5, 6, 7):
                expected_label = {3: "userdata", 7: "metadata"}.get(number)
                value = inspect_ext4(
                    source, parts[number], expected_label,
                    512 if number in (3, 7) else None)
                report.setdefault("ext4", {})[str(number)] = value
                checks[f"ext4_p{number}"] = _check(
                    value["status"], f"p{number} ext4 superblock", value.get("errors"))
        boot = inspect_boot(image, parts[1], mtools)
        report["boot"] = boot
        checks["boot_fat"] = _check(boot["status"], "Pi 5 FAT inventory/payload", boot["errors"])
        system = inspect_system(
            image, parts[5], input_apk, expected_marker, debugfs, apksigner,
            expected_cert_sha256, frozen)
        report["system"] = system
        checks["system_ext"] = _check(
            system["status"], "system_ext APK/marker/XML/signature provenance",
            system["errors"])
        vendor = inspect_vendor(image, parts[6], expected_marker, debugfs)
        report['vendor'] = vendor
        checks['vendor_display'] = _check(vendor['status'], 'built Pi display mode', vendor['errors'])
    else:
        for name in ("ext4_p3", "ext4_p5", "ext4_p6", "ext4_p7", "boot_fat", "system_ext", "vendor_display"):
            checks[name] = _check("unknown", "not attempted after geometry failure")
    if input_apk is None:
        checks["input_apk"] = _check("fail", "--input-apk is required")
    else:
        value = inspect_apk(input_apk, frozen)
        report["input_apk"] = value
        checks["input_apk"] = _check(value["status"], "frozen unsigned APK", value["errors"])
    if expected_marker is None or not expected_marker.is_file():
        checks["expected_marker"] = _check("fail", "--expected-marker is required")
    else:
        value, errors = _marker(expected_marker)
        errors.extend(marker_errors(value, lock_sha, frozen) if value else [])
        checks["expected_marker"] = _check(
            "pass" if not errors else "fail", "expected rpi5 staging marker", errors)
    required = [c for c in checks.values() if c.get("required", True)]
    report["ok"] = bool(required) and all(c["status"] == "pass" for c in required)
    report["qualification"] = "complete-offline-integrity" if report["ok"] else "rejected-or-incomplete"
    return report
def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--image", type=Path, required=True)
    result.add_argument("--input-apk", type=Path, required=True)
    result.add_argument("--expected-marker", type=Path, required=True)
    result.add_argument("--output", type=Path, required=True)
    result.add_argument("--apksigner")
    result.add_argument("--expected-cert-sha256")
    result.add_argument("--mtools")
    result.add_argument("--debugfs")
    return result
def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parser().parse_args(argv)
    report = verify_image(
        args.image, args.input_apk, args.expected_marker,
        apksigner=args.apksigner, expected_cert_sha256=args.expected_cert_sha256,
        mtools=args.mtools, debugfs=args.debugfs)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps({"ok": report["ok"], "output": str(args.output),
                      "sha256": report["image"]["sha256"]}, sort_keys=True))
    return 0 if report["ok"] else 1
if __name__ == "__main__":
    sys.exit(main())
