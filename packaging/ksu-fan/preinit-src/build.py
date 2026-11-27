#!/usr/bin/env python3
"""Standalone offline Clang build/tests; Python stdlib only, no make required."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = ROOT / "build/kernel-replace-audit/preinit-helper"
WARN = ["-std=c11", "-Wall", "-Wextra", "-Werror", "-Wpedantic", "-Wformat=2", "-Wshadow", "-Wconversion"]
BASE = WARN + ["-O2", "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2"]


def run(args, log, env=None):
    proc = subprocess.run(args, cwd=HERE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, env=env, timeout=180)
    text = "$ " + " ".join(map(str, args)) + "\n" + proc.stdout + f"\nexit={proc.returncode}\n"
    (OUT / log).write_text(text)
    print(text, end="", flush=True)
    if proc.returncode:
        raise RuntimeError(f"{log}: exit {proc.returncode}")
    return proc.stdout


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    run(["clang-19", "--version"], "compiler.log")
    run(["clang-19", *BASE, "-static", "-fno-pie", "-Wl,-no-pie",
         "-Wl,-z,noexecstack,-z,relro,-z,now", "-o", str(OUT / "fan-preinit"),
         "fan_preinit.c", "sha256.c"], "compile-static.log")
    for name, source in [("unit-tests", "tests.c"), ("elf-probe", "elf_probe.c")]:
        run(["clang-19", *BASE, "-o", str(OUT / name), source, "sha256.c"], f"compile-{name}.log")
    env = {**os.environ, "FAN_TEST_OUT": str(OUT), "PYTHONDONTWRITEBYTECODE": "1"}
    run([str(OUT / "unit-tests")], "unit-tests.log", env)
    run([sys.executable, "test_manifest.py", "--out", str(OUT)], "manifest-tests.log", env)
    # Clang's sanitizer archives are absent. Use the ALREADY INSTALLED GCC
    # UBSan runtime, offline only. No dependencies are installed. A separate
    # ASan attempt could not start its address-space allocator on this host;
    # see sanitizer-tests.log. Do not count that attempt as a passed test.
    run(["clang-19", *WARN, "-O1", "-g", "-fno-omit-frame-pointer", "-fsanitize=undefined",
         "-fno-sanitize-link-runtime", "-o", str(OUT / "unit-tests-ubsan"), "tests.c", "sha256.c",
         "-Wl,--no-as-needed", "/usr/lib/aarch64-linux-gnu/libubsan.so.1",
         "-Wl,--as-needed"], "compile-ubsan.log")
    run([str(OUT / "unit-tests-ubsan")], "ubsan-tests.log",
        {**env, "UBSAN_OPTIONS": "halt_on_error=1:print_stacktrace=1"})
    audit = run(["readelf", "-h", "-l", "-d", str(OUT / "fan-preinit")], "readelf.log")
    if re.search(r"\bINTERP\b|\(NEEDED\)", audit):
        raise RuntimeError("preinit ELF unexpectedly requires a loader/shared libraries")
    if "AArch64" not in audit or "EXEC (Executable file)" not in audit:
        raise RuntimeError("not a static AArch64 executable")
    artifacts = {}
    for name in ("fan-preinit", "unit-tests", "elf-probe", "unit-tests-ubsan"):
        data = (OUT / name).read_bytes()
        artifacts[name] = {"sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
    sources = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(HERE.iterdir())
               if p.is_file()}
    summary = {"status": "offline-tests-passed", "static_aarch64_no_interp_no_needed": True,
               "release_payload_present": False, "android_execution": False,
               "actual_mounts": 0, "artifacts": artifacts, "source_sha256": sources}
    (OUT / "build-summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
    print("PASS: standalone static helper + offline tests; payload absent, not a business release")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.SubprocessError) as exc:
        print(f"BUILD/TEST FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
