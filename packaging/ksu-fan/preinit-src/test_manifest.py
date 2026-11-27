#!/usr/bin/env python3
"""Offline tests: every generated or damaged input is a temporary *.fixture.
No fixture is a usable driver; no fixture/header is retained as release data.
"""
import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest import mock

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("manifest_generator", HERE / "generate_manifest.py")
gen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gen)
OUT = None
STOCK = HERE.parents[2] / "build/direct-node-audit/oplus_fan.ko"
RELEASE = "6.12.69-android16-6-o-gdea09c7a246a-4k"
KMI = "6.12-android16-6"
VM = RELEASE + " SMP preempt mod_unload modversions aarch64"
OBSERVED_RUNTIME = "6.12.69-android16-6-g242e1a07f878-ab15865446-4k"


class OfflineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="python-tests-", dir=OUT)
        self.directory = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def args(self, **updates):
        args = dict(stock=STOCK, replacement=self.directory / "missing-real-replacement",
                    kernel_release=RELEASE, kmi=KMI, probe=OUT / "elf-probe",
                    output=self.directory / "test-only.generated.h")
        args.update(updates)
        return argparse.Namespace(**args)

    def probe(self, data, stock=False):
        path = self.directory / "non-loadable.fixture"
        path.write_bytes(data)
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            return subprocess.run([str(OUT / "elf-probe"), "--stock-fd" if stock else "--fd", str(fd)],
                                  pass_fds=(fd,), capture_output=True, text=True, timeout=15)
        finally:
            os.close(fd)

    def test_known_stock_sample_offline(self):
        self.assertTrue(STOCK.is_file(), f"required offline stock evidence absent: {STOCK}")
        got = gen.inspect_file(STOCK, OUT / "elf-probe")
        self.assertEqual(got["sha256"], gen.STOCK_SHA)
        self.assertEqual(got["size"], 104888)
        self.assertEqual(got["module_name"], "oplus_fan")
        self.assertEqual(got["vermagic"], VM)
        self.assertEqual(got["versions_count"], 85)
        (OUT / "offline-stock-identity.json").write_text(
            json.dumps({k: v for k, v in got.items() if k != "file_identity"}, indent=2) + "\n")

    def test_independent_python_c_hash(self):
        data = STOCK.read_bytes()
        got = self.probe(data)
        self.assertEqual(got.returncode, 0, got.stderr)
        self.assertEqual(json.loads(got.stdout)["sha256"], hashlib.sha256(data).hexdigest())

    def test_same_fd_stock_verifier_used_by_check_only(self):
        data = bytearray(STOCK.read_bytes())
        self.assertEqual(self.probe(data, stock=True).returncode, 0)
        data[100] ^= 1
        got = self.probe(data, stock=True)
        self.assertEqual(got.returncode, 5)
        self.assertIn("offline-stock", got.stderr)

    def test_sha_padding_boundaries(self):
        stock = STOCK.read_bytes()
        for residue in (0, 1, 55, 56, 63):
            padding = (residue - len(stock)) % 64
            data = stock + b"x" * padding
            got = self.probe(data)
            self.assertEqual(got.returncode, 0, got.stderr)
            self.assertEqual(json.loads(got.stdout)["sha256"], hashlib.sha256(data).hexdigest())

    def test_missing_payload_generates_nothing(self):
        args = self.args()
        with self.assertRaises(FileNotFoundError):
            gen.generate(args)
        self.assertFalse(args.output.exists())

    def test_stock_cannot_be_replacement(self):
        args = self.args(replacement=STOCK)
        with self.assertRaisesRegex(ValueError, "replacement is stock"):
            gen.generate(args)
        self.assertFalse(args.output.exists())

    def test_wrong_stock_hash(self):
        damaged = self.directory / "not-stock.fixture"
        data = bytearray(STOCK.read_bytes())
        data[100] ^= 1
        damaged.write_bytes(data)
        args = self.args(stock=damaged)
        with self.assertRaisesRegex(ValueError, "not the pinned stock"):
            gen.generate(args)
        self.assertFalse(args.output.exists())

    def test_symlink_and_dotdot_inputs(self):
        link = self.directory / "link.fixture"
        link.symlink_to(STOCK)
        with self.assertRaises(OSError):
            gen.inspect_file(link, OUT / "elf-probe")
        with self.assertRaisesRegex(ValueError, "canonical"):
            gen.inspect_file(str(self.directory) + "/../escape", OUT / "elf-probe")

    def test_hardlink_and_oversize(self):
        source = self.directory / "one.fixture"
        source.write_bytes(b"x" * 64)
        os.link(source, self.directory / "two.fixture")
        with self.assertRaisesRegex(ValueError, "one link"):
            gen.inspect_file(source, OUT / "elf-probe")
        (self.directory / "two.fixture").unlink()
        with source.open("wb") as f:
            f.truncate(gen.MAX_SIZE + 1)
        with self.assertRaisesRegex(ValueError, "bounded"):
            gen.inspect_file(source, OUT / "elf-probe")

    def test_kmi_mismatch_no_output(self):
        args = self.args(kmi="6.12-android16-7")
        with self.assertRaisesRegex(ValueError, "KMI disagree"):
            gen.generate(args)
        self.assertFalse(args.output.exists())

    def test_corrupt_elf_and_truncated(self):
        data = bytearray(STOCK.read_bytes())
        data[0] = 0
        self.assertEqual(self.probe(data).returncode, 4)
        self.assertEqual(self.probe(b"x" * 64).returncode, 4)

    def test_extended_crc_mismatch(self):
        data = bytearray(STOCK.read_bytes())
        shoff = struct.unpack_from("<Q", data, 40)[0]
        shnum = struct.unpack_from("<H", data, 60)[0]
        shstr = struct.unpack_from("<H", data, 62)[0]
        off, size = struct.unpack_from("<QQ", data, shoff + shstr * 64 + 24)
        names = data[off:off + size]
        found = False
        for i in range(shnum):
            base = shoff + i * 64
            ni = struct.unpack_from("<I", data, base)[0]
            if names[ni:].split(b"\0", 1)[0] == b"__version_ext_crcs":
                pos = struct.unpack_from("<Q", data, base + 24)[0]
                data[pos] ^= 1
                found = True
                break
        self.assertTrue(found)
        got = self.probe(data)
        self.assertEqual(got.returncode, 4)
        self.assertIn("extended-versions-mismatch", got.stderr)

    def test_generator_positive_metadata_mock_only(self):
        # No fake new driver is emitted. Mocked metadata exercises serializer
        # only; temp header is deleted and never used for helper compilation.
        stock = dict(sha256=gen.STOCK_SHA, size=104888, file_identity=(1, 1),
                     vermagic=VM, versions_sha256="a" * 64, versions_count=85)
        new = {**stock, "sha256": "b" * 64, "file_identity": (1, 2)}
        args = self.args()
        with mock.patch.object(gen, "inspect_file", side_effect=[stock, new]), mock.patch("sys.stdout", new=io.StringIO()):
            gen.generate(args)
        text = args.output.read_text()
        self.assertIn('.filename = "oplus_fan.replacement"', text)
        self.assertIn('.kernel_release = "' + RELEASE + '"', text)
        self.assertIn(".versions_count = 85", text)
        with mock.patch.object(gen, "inspect_file", side_effect=[stock, new]):
            with self.assertRaises(FileExistsError):
                gen.generate(args)

    def test_observed_runtime_differs_from_unchanged_stock_vermagic(self):
        stock = dict(sha256=gen.STOCK_SHA, size=104888, file_identity=(1, 1),
                     vermagic=VM, versions_sha256="a" * 64, versions_count=85)
        new = {**stock, "sha256": "b" * 64, "file_identity": (1, 2),
               "size": 151040, "versions_sha256": "c" * 64, "versions_count": 89}
        mirror = self.directory / "mirror.json"
        args = self.args(kernel_release=OBSERVED_RUNTIME, json_output=mirror)
        with mock.patch.object(gen, "inspect_file", side_effect=[stock, new]), mock.patch("sys.stdout", new=io.StringIO()):
            gen.generate(args)
        got = json.loads(mirror.read_text())
        self.assertEqual(got["kernel_release"], OBSERVED_RUNTIME)
        self.assertEqual(got["vermagic"], VM)
        self.assertEqual(got["versions_count"], 89)
        self.assertEqual(got["present"], 1)
        self.assertIn("__attribute__((used))", args.output.read_text())
        self.assertNotEqual(got["kernel_release"], got["vermagic"].split()[0])

    def test_new_vermagic_change_or_kmi_or_flags_mismatch_refused(self):
        stock = dict(sha256=gen.STOCK_SHA, size=104888, file_identity=(1, 1),
                     vermagic=VM, versions_sha256="a" * 64, versions_count=85)
        new = {**stock, "sha256": "b" * 64, "file_identity": (1, 2)}
        args = self.args(kernel_release=OBSERVED_RUNTIME)
        changed = {**new, "vermagic": OBSERVED_RUNTIME + gen.VERMAGIC_FLAGS}
        with mock.patch.object(gen, "inspect_file", side_effect=[stock, changed]):
            with self.assertRaisesRegex(ValueError, "stock vermagic disagree"):
                gen.generate(args)
        for bad in (VM.replace("android16-6-", "android16-7-"), VM + " extra"):
            with mock.patch.object(gen, "inspect_file", side_effect=[{**stock, "vermagic": bad}, {**new, "vermagic": bad}]):
                with self.assertRaises(ValueError):
                    gen.generate(args)
        self.assertFalse(args.output.exists())

    def test_cli_disabled_apply_and_bad_args(self):
        cases = [(["--apply"], 21, "replacement-not-configured"),
                 (["--target", "/tmp/elsewhere"], 20, "usage-check-only-or-apply"),
                 (["--apply", "--apply"], 20, "usage-check-only-or-apply")]
        lines = []
        for options, code, reason in cases:
            proc = subprocess.run([str(OUT / "fan-preinit"), *options],
                                  capture_output=True, text=True, timeout=15)
            self.assertEqual(proc.returncode, code)
            self.assertEqual(proc.stdout, f"fan-preinit result=not-applied exit={code} reason={reason} errno=0\n")
            self.assertEqual(proc.stderr, "")
            lines.append(json.dumps({"argv": options, "stdout": proc.stdout,
                                     "stderr": proc.stderr, "exit": proc.returncode}, sort_keys=True))
        (OUT / "cli-stdout-exitcodes.jsonl").write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    OUT = args.out.resolve()
    unittest.main(argv=[__file__], verbosity=2)
