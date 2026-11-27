#!/usr/bin/env python3
"""Offline manifest-header generator; not a ZIP builder or device installer.

The caller must supply actual stock and new full-driver files plus independently
established running-kernel release/KMI. Nothing is downloaded, loaded or mounted.
The helper uses the resulting immutable header; no on-device config is parsed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys

STOCK_SHA = "a1abed95ae8ab56402f13efa39ddd3253da606fafe119cf347a9ed6155039ddd"
STOCK_SIZE = 104888
MAX_SIZE = 8 * 1024 * 1024
FILENAME = "oplus_fan.replacement"
VERMAGIC_FLAGS = " SMP preempt mod_unload modversions aarch64"


def identity(st):
    return (st.st_dev, st.st_ino, st.st_size, st.st_mode, st.st_uid,
            st.st_gid, st.st_nlink, st.st_mtime_ns, st.st_ctime_ns)


def inspect_file(path, probe):
    # No symlink at any component; keep directories and payload pinned by FDs.
    raw = os.fspath(path)
    if not os.path.isabs(raw) or any(x in ("", ".", "..") for x in raw.split("/")[1:]):
        raise ValueError("input must be a canonical absolute path without dot/empty components")
    directory = os.open("/", os.O_PATH | os.O_DIRECTORY | os.O_CLOEXEC)
    fd = None
    try:
        parts = raw.split("/")[1:]
        for part in parts[:-1]:
            child = os.open(part, os.O_PATH | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC,
                            dir_fd=directory)
            os.close(directory)
            directory = child
        fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC,
                     dir_fd=directory)
        before = os.fstat(fd)
        if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_uid != 0
                or before.st_mode & 0o022 or not 64 <= before.st_size <= MAX_SIZE):
            raise ValueError("input must be bounded, root-owned, non-group/world-writable regular file with one link")
        data = bytearray()
        while len(data) < before.st_size:
            chunk = os.read(fd, min(65536, before.st_size - len(data)))
            if not chunk:
                raise ValueError("input shortened during read")
            data.extend(chunk)
        if os.read(fd, 1):
            raise ValueError("input grew during read")
        got = subprocess.run([os.fspath(probe), "--fd", str(fd)], pass_fds=(fd,),
                             check=True, capture_output=True, text=True, timeout=15)
        info = json.loads(got.stdout)
        if info["sha256"] != hashlib.sha256(data).hexdigest() or info["size"] != len(data):
            raise ValueError("independent Python/C hash or size disagreement")
        if identity(os.fstat(fd)) != identity(before):
            raise ValueError("input changed during validation")
        info["file_identity"] = (before.st_dev, before.st_ino)
        return info
    finally:
        if fd is not None:
            os.close(fd)
        os.close(directory)


def generate(args):
    release = args.kernel_release
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)-android(\d+)-(\d+)-[A-Za-z0-9_.+\-]+", release)
    if not match or len(release) >= 128:
        raise ValueError("unsupported exact kernel release")
    major, minor, _patch, android, generation = map(int, match.groups())
    if major > 100 or minor > 100 or _patch > 0xffffffff or not 1 <= android <= 100 or generation > 1000:
        raise ValueError("kernel release outside helper's explicit parser range")
    kmi = f"{major}.{minor}-android{android}-{generation}"
    if kmi != args.kmi:
        raise ValueError("release and independently supplied KMI disagree")
    stock = inspect_file(args.stock, args.probe)
    if stock["sha256"] != STOCK_SHA or stock["size"] != STOCK_SIZE:
        raise ValueError("not the pinned stock sample")
    new = inspect_file(args.replacement, args.probe)
    if new["sha256"] == STOCK_SHA or new["file_identity"] == stock["file_identity"]:
        raise ValueError("replacement is stock, not a new full driver")
    if new["vermagic"] != stock["vermagic"]:
        raise ValueError("replacement/stock vermagic disagree")
    # This target's known stock has a different first vermagic release token
    # from runtime uname. MODVERSIONS compatibility must not be modeled as
    # equality of those strings. Keep exact runtime binding and compare KMI,
    # the full unchanged stock/new vermagic and the remaining pinned flags.
    module_release, sep, flags = new["vermagic"].partition(" ")
    vm_match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)-android(\d+)-(\d+)-[A-Za-z0-9_.+\-]+", module_release)
    if not sep or " " + flags != VERMAGIC_FLAGS or not vm_match:
        raise ValueError("replacement lacks exact stock modversions/aarch64 flags")
    vm_major, vm_minor, vm_patch, vm_android, vm_generation = map(int, vm_match.groups())
    if (vm_major > 100 or vm_minor > 100 or vm_patch > 0xffffffff
            or not 1 <= vm_android <= 100 or vm_generation > 1000
            or f"{vm_major}.{vm_minor}-android{vm_android}-{vm_generation}" != kmi):
        raise ValueError("runtime and stock/replacement vermagic KMI disagree")
    values = {
        "filename": FILENAME, "sha256": new["sha256"], "size": new["size"],
        "kernel_release": release, "kmi": kmi, "vermagic": new["vermagic"],
        "versions_sha256": new["versions_sha256"], "versions_count": new["versions_count"],
    }
    lines = ["/* Generated from explicitly supplied offline inputs; no device compatibility claim. */",
             "static const struct fan_manifest fan_release_manifest __attribute__((used)) = {", "    .present = 1,"]
    for key, value in values.items():
        encoded = json.dumps(value, ensure_ascii=True) if isinstance(value, str) else str(value)
        lines.append(f"    .{key} = {encoded},")
    lines.extend(["};", ""])
    # Mirror only schema-defined immutable struct fields, no runtime claims.
    mirror = {"schema": 1, "present": 1, **values}
    json_output = getattr(args, "json_output", None)
    if json_output is not None and Path(json_output).exists():
        raise FileExistsError(os.fspath(json_output))
    # Exclusive creation: never overwrite a previous generated identity/header.
    with open(args.output, "x", encoding="ascii") as out:
        out.write("\n".join(lines))
    if json_output is not None:
        with open(json_output, "x", encoding="ascii") as out:
            out.write(json.dumps(mirror, sort_keys=True, indent=2) + "\n")
    print(json.dumps({"status": "manifest-generated-not-deployed", "header": os.fspath(args.output),
                      "json": os.fspath(json_output) if json_output is not None else None,
                      **values}, sort_keys=True))
    return mirror


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stock", required=True, type=Path)
    parser.add_argument("--replacement", required=True, type=Path)
    parser.add_argument("--kernel-release", required=True)
    parser.add_argument("--kmi", required=True)
    parser.add_argument("--probe", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--json-output", type=Path, help="exclusive schema-compatible immutable manifest mirror")
    args = parser.parse_args()
    try:
        generate(args)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as exc:
        print(f"manifest-refused: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
