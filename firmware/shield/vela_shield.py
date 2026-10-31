#!/usr/bin/env python3
"""Stage and attest the sif image integration. Never operates on a device."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
REPO_ROOT = ROOT.parent.parent
PRODUCT = Path("device/nvidia/sif/lineage_sif.mk")
BOARD = Path("device/nvidia/sif/BoardConfig.mk")
HOOK = "\n# Vela sif integration (managed by firmware/shield/vela_shield.py)\n$(call inherit-product, vendor/vela/vela.mk)\n"
BOARD_HOOK = "\n# Vela image is installable only on the owner's Tube.\nTARGET_OTA_ASSERT_DEVICE := sif\n"
PERMISSIONS = {"android.permission.WRITE_SECURE_SETTINGS", "android.permission.MEDIA_CONTENT_CONTROL"}


class BuildError(Exception):
    pass


def sha256(file: Path) -> str:
    digest = hashlib.sha256()
    with file.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def atomic_write(file: Path, data: bytes) -> None:
    file.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=".vela-", dir=file.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, file)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def write_json(file: Path, value: object) -> None:
    atomic_write(file, (json.dumps(value, indent=2, sort_keys=True) + "\n").encode())


def command(args: list[str], cwd: Path | None = None) -> str:
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def doctor(workspace: Path, phase: str = "source") -> dict:
    workspace = workspace.resolve()
    ancestor = workspace
    while not ancestor.exists():
        ancestor = ancestor.parent
    memory = None
    if platform.system() == "Linux" and Path("/proc/meminfo").exists():
        match = re.search(r"^MemTotal:\s+(\d+) kB", Path("/proc/meminfo").read_text(), re.M)
        if match:
            memory = int(match[1]) * 1024
    free = shutil.disk_usage(ancestor).free
    problems = []
    if platform.system() != "Linux" or platform.machine() not in {"x86_64", "AMD64"}:
        problems.append("The OS compiler requires Linux x86_64; macOS/ARM is supported for APK and tooling checks only.")
    minimum_disk_gib = 400 if phase == "source" else 150
    if free < minimum_disk_gib * 1024**3:
        problems.append(f"Reserve at least {minimum_disk_gib} GiB free for the {phase} phase.")
    # A nominal 32 GiB VM reports slightly less usable RAM after reservations.
    if memory is None or memory < 30 * 1024**3:
        problems.append("A nominal 32 GiB RAM allocation is required for this build plan; 64 GiB is recommended.")
    missing = [name for name in ("git", "git-lfs", "repo", "python3", "bash", "make", "unzip") if not shutil.which(name)]
    if missing:
        problems.append("Missing build tools: " + ", ".join(missing))
    return {"system": platform.system(), "architecture": platform.machine(), "free_bytes": free,
            "ram_bytes": memory, "phase": phase, "workspace": str(workspace), "ready": not problems, "problems": problems}


def validate_apk(apk: Path, aapt2: Path) -> dict:
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        if not {"AndroidManifest.xml", "classes.dex"} <= names:
            raise BuildError("Not a compiled Android application APK.")
        if not any(name.startswith("lib/arm64-v8a/") for name in names):
            raise BuildError("Firmware must include arm64-v8a native libraries; sif's custom OS is arm64.")
    badging = command([str(aapt2), "dump", "badging", str(apk)])
    if not re.search(r"^package: name='app\.vela\.tv' ", badging, re.M):
        raise BuildError("Expected package app.vela.tv.")
    if "application-debuggable" in badging:
        raise BuildError("Use assembleFirmware, not a debug APK.")
    if not all(f"uses-permission: name='{permission}'" in badging for permission in PERMISSIONS):
        raise BuildError("APK is missing firmware-only permissions; build assembleFirmware.")
    return {"sha256": sha256(apk), "package": "app.vela.tv", "variant": "firmware"}


def validate_product() -> None:
    xml = ET.parse(ROOT / "product/permissions/privapp-permissions-app.vela.tv.xml")
    grants = xml.getroot().findall("privapp-permissions")
    if len(grants) != 1 or grants[0].get("package") != "app.vela.tv":
        raise BuildError("Invalid Vela privilege allowlist.")
    if {node.get("name") for node in grants[0]} != PERMISSIONS:
        raise BuildError("Unexpected privilege in Vela allowlist.")
    home = ET.parse(ROOT / "product/preferred-apps/vela-home.xml").getroot()
    if home.find("item").get("name") != "app.vela.tv/app.vela.tv.launcher.HomeActivity":
        raise BuildError("Default Home component differs from Vela's manifest.")


def materialize_manifest(output: Path) -> dict:
    lock = json.loads((ROOT / "upstream-lock.json").read_text())
    if (lock.get("schema_version"), lock.get("device"), lock.get("lineage_branch")) != (1, "sif", "lineage-22.2"):
        raise BuildError("Unsupported Shield upstream lock.")
    resolved = lock["manifest"]["resolved"]
    raw = resolved["embedded_xml"].encode()
    if hashlib.sha256(raw).hexdigest() != resolved["sha256"]:
        raise BuildError("Embedded upstream manifest checksum mismatch.")
    tree = ET.fromstring(raw)
    projects = tree.findall("project")
    if len(projects) != resolved["project_count"] or tree.findall("include") or tree.findall("submanifest"):
        raise BuildError("Incomplete upstream manifest.")
    for project in projects:
        if not re.fullmatch(r"[0-9a-f]{40}", project.get("revision", "")):
            raise BuildError("Upstream source revision is not immutable.")
        parts = Path(project.get("path", project.get("name", ""))).parts
        if not parts or ".." in parts or project.get("path", "").startswith("/"):
            raise BuildError("Invalid source checkout path.")
    pins = {project.get("path", project.get("name")): project.get("revision") for project in projects}
    if any(pins.get(project["target_path"]) != project["revision"] for project in lock["device_projects"]):
        raise BuildError("Device dependency closure differs from the official source manifest.")
    used_remotes = {p.get("remote", tree.find("default").get("remote")) for p in projects}
    for remote in tree.findall("remote"):
        if remote.get("name") not in used_remotes:
            tree.remove(remote)
            continue
        if remote.get("name") == "github" and remote.get("fetch") == "..":
            remote.set("fetch", "https://github.com")
        if not remote.get("fetch", "").startswith("https://"):
            raise BuildError("Manifest remotes must use absolute HTTPS URLs.")
    if output.exists():
        raise BuildError("Manifest output exists; source locks are immutable.")
    data = ET.tostring(tree, encoding="utf-8", xml_declaration=True) + b"\n"
    atomic_write(output, data)
    return {"upstream_sha256": resolved["sha256"], "replay_sha256": sha256(output), "project_count": len(projects)}


def init(workspace: Path) -> dict:
    workspace = workspace.resolve()
    host = doctor(workspace)
    if not host["ready"]:
        raise BuildError("; ".join(host["problems"]))
    if workspace.exists() and any(workspace.iterdir()):
        raise BuildError("Source initialization requires a new empty workspace.")
    manifest_repo = workspace / ".vela/manifest-repo"
    info = materialize_manifest(manifest_repo / "default.xml")
    command(["git", "init", "--initial-branch=vela-pin"], manifest_repo)
    command(["git", "add", "default.xml"], manifest_repo)
    command(["git", "-c", "user.name=Vela Builder", "-c", "user.email=vela-builder@localhost",
             "commit", "-m", "Pin official sif source manifest"], manifest_repo)
    subprocess.run(["repo", "init", "-u", str(manifest_repo), "-b", "vela-pin", "--git-lfs", "--no-clone-bundle"],
                   cwd=workspace, check=True)
    write_json(workspace / ".vela/bootstrap.json", info)
    return info


def stage(workspace: Path, apk: Path, aapt2: Path, snapshot: Path) -> dict:
    workspace = workspace.resolve()
    if workspace == REPO_ROOT or not (workspace / ".repo").is_dir():
        raise BuildError("--workspace must be a separate, synced LineageOS repo checkout.")
    product = workspace / PRODUCT
    original = product.read_text()
    if "PRODUCT_DEVICE := sif" not in original or "PRODUCT_NAME := lineage_sif" not in original:
        raise BuildError("Only the lineage_sif product is supported. Do not stage on another Shield variant.")
    if original.count(HOOK) > 1 or ("vendor/vela/vela.mk" in original and HOOK not in original):
        raise BuildError("Unrecognized Vela product modification; inspect it before staging.")
    destination = workspace / "vendor/vela"
    if destination.is_symlink() or not destination.resolve().is_relative_to(workspace):
        raise BuildError("Refusing a generated vendor directory that redirects outside the source tree.")
    state_file = destination / "vela-staging.json"
    base = original.removesuffix(HOOK)
    if HOOK in base:
        raise BuildError("Vela hook must be the final product stanza.")
    base_hash = hashlib.sha256(base.encode()).hexdigest()
    board = workspace / BOARD
    original_board = board.read_text()
    board_base = original_board.removesuffix(BOARD_HOOK)
    if BOARD_HOOK in board_base:
        raise BuildError("Vela board restriction must be the final BoardConfig stanza.")
    board_hash = hashlib.sha256(board_base.encode()).hexdigest()
    if destination.exists():
        if not state_file.is_file():
            raise BuildError("vendor/vela already exists without Vela's staging record; it was left untouched.")
        previous = json.loads(state_file.read_text())
        if previous.get("schema_version") != 1 or previous.get("device") != "sif" or previous.get("base_product_sha256") != base_hash or previous.get("base_board_sha256") != board_hash:
            raise BuildError("Product source changed since staging; use a fresh/reviewed source tree.")
    elif HOOK in original or BOARD_HOOK in original_board:
        raise BuildError("Product hook has no matching staging record.")
    info = validate_apk(apk, aapt2)
    validate_product()
    source_lock = json.loads((snapshot / "source-lock.json").read_text())
    if source_lock.get("device") != "sif" or sha256(snapshot / "manifest.xml") != source_lock.get("manifest_sha256"):
        raise BuildError("Invalid source snapshot for staging.")
    # Validate everything before changing any source. Only this private generated
    # directory and one explicit stanza in the sif product are written.
    for file in sorted((ROOT / "product").rglob("*")):
        if file.is_file():
            atomic_write(destination / file.relative_to(ROOT / "product"), file.read_bytes())
    atomic_write(destination / "VelaTv.apk", apk.read_bytes())
    state = {"schema_version": 1, "device": "sif", "apk": info,
             "base_product_sha256": base_hash,
             "base_board_sha256": board_hash,
             "source_manifest_sha256": source_lock["manifest_sha256"],
             "vela_commit": command(["git", "rev-parse", "HEAD"], REPO_ROOT),
             "integration_files": {str(f.relative_to(ROOT / "product")): sha256(f)
                                   for f in sorted((ROOT / "product").rglob("*")) if f.is_file()}}
    write_json(state_file, state)
    atomic_write(product, (base + HOOK).encode())
    atomic_write(board, (board_base + BOARD_HOOK).encode())
    return state


def freeze(workspace: Path, output: Path) -> dict:
    workspace, output = workspace.resolve(), output.resolve()
    if output.exists():
        raise BuildError("Use a new snapshot directory; source locks are immutable.")
    command(["repo", "forall", "-c", "git diff --quiet && git diff --cached --quiet"], workspace)
    manifest = command(["repo", "manifest", "-r"], workspace)
    parsed = ET.fromstring(manifest)
    projects = parsed.findall("project")
    if not projects or any(not re.fullmatch(r"[0-9a-f]{40}", p.get("revision", "")) for p in projects):
        raise BuildError("repo manifest -r did not resolve every project to an immutable commit.")
    # A bootstrap manifest alone does not pin branch-based dependencies. This is
    # the complete resolved source lock used for replay and artifact provenance.
    output.mkdir(parents=True)
    atomic_write(output / "manifest.xml", (manifest + "\n").encode())
    lock = {"schema_version": 1, "device": "sif", "manifest_sha256": sha256(output / "manifest.xml"),
            "project_count": len(projects)}
    write_json(output / "source-lock.json", lock)
    return lock


def check_ota(ota: Path) -> None:
    with zipfile.ZipFile(ota) as archive:
        metadata = archive.read("META-INF/com/android/metadata").decode()
        script = archive.read("META-INF/com/google/android/updater-script").decode()
    values = dict(line.split("=", 1) for line in metadata.splitlines() if "=" in line)
    if values.get("pre-device") != "sif":
        raise BuildError("OTA metadata must target only sif.")
    devices = re.findall(r'getprop\("ro\.(?:product\.device|build\.product)"\)\s*==\s*"([^"]+)"', script)
    if not devices or set(devices) != {"sif"}:
        raise BuildError("Recovery updater assertion is missing or permits another device.")
    assertion = script.find('getprop("ro.product.device")')
    writes = [m.start() for m in re.finditer(r'(?:package_extract_file|block_image_update|write_raw_image|run_program)\(', script)]
    if assertion < 0 or (writes and min(writes) < assertion):
        raise BuildError("Device assertion must precede updater effects.")


def apk_content_hashes(apk_bytes: bytes) -> dict[str, str]:
    import io
    with zipfile.ZipFile(io.BytesIO(apk_bytes)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise BuildError("APK has ambiguous duplicate entries.")
        return {name: hashlib.sha256(archive.read(name)).hexdigest() for name in names
                if not name.endswith("/") and not re.fullmatch(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", name, re.I)}


def source_projects(xml: str | bytes) -> dict:
    tree = ET.fromstring(xml)
    default = tree.find("default")
    remotes = {r.get("name"): r.get("fetch") for r in tree.findall("remote")}
    return {p.get("path", p.get("name")): (p.get("name"), p.get("revision"),
            remotes.get(p.get("remote", default.get("remote") if default is not None else None)))
            for p in tree.findall("project")}


def bundle(workspace: Path, target_files: Path, snapshot: Path, output: Path) -> dict:
    workspace, target_files, snapshot, output = (p.resolve() for p in (workspace, target_files, snapshot, output))
    if output.exists():
        raise BuildError("Bundle output already exists; choose a new directory.")
    stage_dir = workspace / "vendor/vela"
    state = json.loads((stage_dir / "vela-staging.json").read_text())
    if state.get("device") != "sif" or sha256(stage_dir / "VelaTv.apk") != state["apk"]["sha256"]:
        raise BuildError("Staged APK no longer matches the build record.")
    source_lock = json.loads((snapshot / "source-lock.json").read_text())
    if source_lock.get("device") != "sif" or sha256(snapshot / "manifest.xml") != source_lock.get("manifest_sha256"):
        raise BuildError("Resolved source manifest was changed after freezing.")
    if state.get("source_manifest_sha256") != source_lock["manifest_sha256"]:
        raise BuildError("Staged integration belongs to another source snapshot.")
    current_manifest = command(["repo", "manifest", "-r"], workspace) + "\n"
    if current_manifest != (snapshot / "manifest.xml").read_text():
        raise BuildError("Source revisions changed since freezing; take a new source snapshot and rebuild.")
    # The only permitted tracked source edit is the explicit sif integration.
    command(["repo", "forall", "-c",
             'if [ "$REPO_PATH" = device/nvidia/sif ]; then '
             'git diff --quiet -- . ":(exclude)lineage_sif.mk" ":(exclude)BoardConfig.mk"; '
             'else git diff --quiet; fi && git diff --cached --quiet'], workspace)
    product = (workspace / PRODUCT).read_text()
    if not product.endswith(HOOK) or hashlib.sha256(product.removesuffix(HOOK).encode()).hexdigest() != state["base_product_sha256"]:
        raise BuildError("The managed sif product hook was changed after staging.")
    board = (workspace / BOARD).read_text()
    if not board.endswith(BOARD_HOOK) or hashlib.sha256(board.removesuffix(BOARD_HOOK).encode()).hexdigest() != state["base_board_sha256"]:
        raise BuildError("The managed sif-only board restriction was changed after staging.")
    for name, expected in state["integration_files"].items():
        if sha256(stage_dir / name) != expected:
            raise BuildError("Staged product configuration changed after staging.")
    with zipfile.ZipFile(target_files) as archive:
        # Legacy Shield partition layouts can fold system_ext into system.
        # Keep the app and configuration together in either supported layout.
        roots = [prefix for prefix in ("SYSTEM_EXT", "SYSTEM/system_ext")
                 if f"{prefix}/priv-app/VelaTv/VelaTv.apk" in archive.namelist()]
        if len(roots) != 1:
            raise BuildError("Target files must contain exactly one Vela system-extension app.")
        extension_root = roots[0]
        required = {f"{extension_root}/priv-app/VelaTv/VelaTv.apk",
                    f"{extension_root}/etc/permissions/privapp-permissions-app.vela.tv.xml",
                    f"{extension_root}/etc/preferred-apps/vela-home.xml",
                    f"{extension_root}/etc/vela/image-inputs.json"}
        if not required <= set(archive.namelist()):
            raise BuildError("Target files do not contain the Vela app and same-partition configuration.")
        installed = archive.read(f"{extension_root}/priv-app/VelaTv/VelaTv.apk")
        if apk_content_hashes(installed) != apk_content_hashes((stage_dir / "VelaTv.apk").read_bytes()):
            raise BuildError("Built image contains a different Vela application.")
        if json.loads(archive.read(f"{extension_root}/etc/vela/image-inputs.json")) != state:
            raise BuildError("Built integration metadata differs from staged inputs.")
        build_manifests = [name for name in ("SYSTEM/etc/build-manifest.xml", "SYSTEM/system/etc/build-manifest.xml")
                           if name in archive.namelist()]
        if len(build_manifests) != 1 or source_projects(archive.read(build_manifests[0])) != source_projects((snapshot / "manifest.xml").read_bytes()):
            raise BuildError("Built OS source manifest differs from the frozen source revisions.")
        for prefix, source in (("permissions", "privapp-permissions-app.vela.tv.xml"), ("preferred-apps", "vela-home.xml")):
            if archive.read(f"{extension_root}/etc/{prefix}/{source}") != (stage_dir / prefix / source).read_bytes():
                raise BuildError("Built system configuration differs from staged integration.")
        recovery = archive.read("IMAGES/recovery.img")
    if not recovery:
        raise BuildError("Target files contain no recovery image.")
    ota_tool = workspace / "out/host/linux-x86/bin/ota_from_target_files"
    key = workspace / "build/target/product/security/testkey"
    if not ota_tool.is_file() or not key.with_suffix(".pk8").is_file() or not key.with_suffix(".x509.pem").is_file():
        raise BuildError("Build otatools and retain the development OTA key before packaging.")
    # Generate the OTA from the exact inspected target-files archive. Accepting
    # a separately supplied OTA would allow a stale/stock image into the bundle.
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="vela-sif-package-", dir=output.parent) as temp:
        prepared = Path(temp)
        ota = prepared / "vela-sif-development.zip"
        subprocess.run([str(ota_tool), "-k", str(key), str(target_files.resolve()), str(ota)], cwd=workspace, check=True)
        check_ota(ota)
        atomic_write(prepared / "recovery.img", recovery)
        shutil.copyfile(snapshot / "manifest.xml", prepared / "source-manifest.xml")
        output.mkdir()
        for file in prepared.iterdir():
            shutil.copyfile(file, output / file.name)
    artifacts = ("vela-sif-development.zip", "recovery.img", "source-manifest.xml")
    provenance = {"schema_version": 1, "device": "sif", "qualification": "unverified-development-testkeys",
                  "staging": state, "sources": source_lock, "target_files_sha256": sha256(target_files),
                  "installed_apk_sha256": hashlib.sha256(installed).hexdigest(),
                  "system_extension_archive_root": extension_root,
                  "artifacts": {name: sha256(output / name) for name in artifacts}}
    write_json(output / "provenance.json", provenance)
    sums = "".join(f"{sha256(file)}  {file.name}\n" for file in sorted(output.iterdir()))
    atomic_write(output / "SHA256SUMS", sums.encode())
    return provenance


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    for name in ("doctor", "init", "stage", "freeze", "bundle", "manifest"):
        cmd = sub.add_parser(name)
        if name == "manifest":
            cmd.add_argument("--output", type=Path, required=True)
            continue
        cmd.add_argument("--workspace", type=Path, required=True)
        if name == "doctor":
            cmd.add_argument("--phase", choices=("source", "build"), default="source")
        elif name == "stage":
            cmd.add_argument("--apk", type=Path, required=True)
            cmd.add_argument("--aapt2", type=Path, required=True)
            cmd.add_argument("--snapshot", type=Path, required=True)
        elif name == "freeze":
            cmd.add_argument("--output", type=Path, required=True)
        elif name == "bundle":
            for argument in ("target-files", "snapshot", "output"):
                cmd.add_argument("--" + argument, type=Path, required=True)
    args = vars(parser.parse_args())
    action = args.pop("action")
    try:
        result = (materialize_manifest if action == "manifest" else globals()[action])(**args)
        print(json.dumps(result, indent=2, sort_keys=True))
        return 2 if action == "doctor" and not result["ready"] else 0
    except (BuildError, OSError, ValueError, KeyError, zipfile.BadZipFile, ET.ParseError, subprocess.CalledProcessError) as error:
        print(f"vela-shield: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
