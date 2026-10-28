#!/usr/bin/env python3
"""Lists the t2linux COPR's kernel RPMs for one Fedora release, read from its repodata.

Usage: t2-copr-kernels.py <fedora-release>          -> "<version-release>" of every kernel-core build, newest last
       t2-copr-kernels.py <fedora-release> <vr>     -> "<url> <sha256>" for the five kernel RPMs of <vr>
"""
import gzip, re, subprocess, sys
import xml.etree.ElementTree as ET

BASE = "https://download.copr.fedorainfracloud.org/results/sharpenedblade/t2linux/fedora-{}-x86_64/"
NAMES = ("kernel", "kernel-core", "kernel-modules", "kernel-modules-core", "kernel-modules-extra")
NS = {"repo": "http://linux.duke.edu/metadata/repo", "c": "http://linux.duke.edu/metadata/common"}

def fetch(url):
    return subprocess.run(["curl", "-fsSL", "--retry", "3", url], check=True, capture_output=True).stdout

def vkey(vr):
    return [(0, int(p), "") if p.isdigit() else (1, 0, p) for p in re.split(r"[.\-_]", vr)]

def main():
    base = BASE.format(sys.argv[1])
    md = ET.fromstring(fetch(base + "repodata/repomd.xml"))
    href = md.find("repo:data[@type='primary']/repo:location", NS).get("href")
    primary = ET.fromstring(gzip.decompress(fetch(base + href)))
    pkgs = []
    for p in primary.findall("c:package", NS):
        if p.findtext("c:arch", namespaces=NS) != "x86_64":
            continue
        v = p.find("c:version", NS)
        pkgs.append((p.findtext("c:name", namespaces=NS), f"{v.get('ver')}-{v.get('rel')}",
                     base + p.find("c:location", NS).get("href"), p.findtext("c:checksum", namespaces=NS)))
    if len(sys.argv) == 2:
        for vr in sorted({vr for n, vr, _, _ in pkgs if n == "kernel-core"}, key=vkey):
            print(vr)
        return
    want = [(n, u, s) for n, vr, u, s in pkgs if vr == sys.argv[2] and n in NAMES]
    if len(want) != len(NAMES):
        sys.exit(f"COPR has {len(want)} of the {len(NAMES)} kernel RPMs for {sys.argv[2]}")
    for _, u, s in sorted(want):
        print(u, s)

main()
