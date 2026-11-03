#!/usr/bin/env python3
"""Management gates use synthetic fixtures; no phone, signing key or network access."""
import contextlib
import io
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from zipfile import ZipFile

import project


class ProjectTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='acefan-project-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        cfg = json.loads((project.ROOT / 'project.json').read_text())
        cfg['required_files'] = []
        self.cfg = cfg
        self.write('project.json', json.dumps(cfg))
        for name in ('AndroidManifest.xml', 'assets/xposed_init', 'res/values/arrays.xml',
                     'src/dev/eta/acefan/BuildInfo.java'):
            self.write(name, (project.ROOT / name).read_bytes())
        self.stage()

    def write(self, name, content):
        p = self.root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content.encode() if isinstance(content, str) else content)

    def stage(self):
        project.git('add', '--all', root=self.root)  # Synthetic disposable test repo only.

    def checked(self, staged=False):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return project.check(project.Snapshot(self.root, staged), quiet=True)

    def changed_metadata(self, name, old, new):
        original = (self.root / name).read_text()
        self.assertIn(old, original)
        self.write(name, original.replace(old, new))
        with self.assertRaises(project.ProjectError):
            project.metadata(lambda p: (self.root / p).read_bytes(), self.cfg)

    def test_valid_metadata(self):
        _, meta = self.checked()
        self.assertGreater(meta['version_code'], 0)

    def test_manifest_version_mismatch(self):
        old = json.loads((self.root / 'project.json').read_text())
        self.assertEqual(old['package_name'], 'dev.eta.acefan')
        text = (self.root / 'AndroidManifest.xml').read_text()
        import re
        text = re.sub(r'android:versionName="[^"]+"', 'android:versionName="0.0.0-wrong"', text)
        self.write('AndroidManifest.xml', text)
        with self.assertRaises(project.ProjectError):
            self.checked()

    def test_package_mismatch(self):
        self.changed_metadata('AndroidManifest.xml', 'package="dev.eta.acefan"', 'package="other.app"')

    def test_scope_mismatch(self):
        self.changed_metadata('res/values/arrays.xml', 'com.oplus.exsystemservice', 'android')

    def test_api_mismatch(self):
        self.changed_metadata('AndroidManifest.xml', 'android:value="82"', 'android:value="93"')

    def test_permission_change(self):
        self.changed_metadata('AndroidManifest.xml', '<application ', '<uses-permission android:name="android.permission.INTERNET"/><application ')

    def test_missing_required_document(self):
        self.cfg['required_files'] = ['AGENTS.md']
        self.write('project.json', json.dumps(self.cfg))
        with self.assertRaises(project.ProjectError):
            self.checked()

    def test_staged_metadata_cannot_hide_behind_worktree(self):
        name = 'res/values/arrays.xml'
        good = (self.root / name).read_bytes()
        self.write(name, good.replace(b'com.oplus.exsystemservice', b'android'))
        self.stage()
        self.write(name, good)
        self.checked()
        with self.assertRaises(project.ProjectError):
            self.checked(staged=True)

    def test_staged_secret_is_rejected_without_printing_value(self):
        token = b'sk-' + b'A' * 32
        self.write('candidate.txt', token)
        self.stage()
        self.write('candidate.txt', 'safe worktree, unsafe index\n')
        self.checked()
        output = io.StringIO()
        with contextlib.redirect_stderr(output), self.assertRaises(project.ProjectError):
            project.check(project.Snapshot(self.root, staged=True), quiet=True)
        self.assertNotIn(token.decode(), output.getvalue())
        self.assertIn('value omitted', output.getvalue())

    def test_generated_payload_rejected_even_if_tracked(self):
        self.write('build-tools/keys/anything.jks', 'synthetic, not a key')
        self.stage()
        with self.assertRaises(project.ProjectError):
            self.checked(staged=True)

    def test_secret_patterns(self):
        self.assertTrue(project.contains_secret(b'-----BEGIN ' + b'PRIVATE KEY-----'))
        self.assertTrue(project.contains_secret(b'ghp_' + b'B' * 40))
        self.assertFalse(project.contains_secret(b'certificate_sha256=' + b'f' * 64))

    def test_path_policy(self):
        for name in ['dist/out.zip', 'private/test.txt', '.env', 'sub/secret.pem', 'data.db-wal',
                     '../outside', '/outside', 'releases/new-before-test/src/a.java']:
            with self.subTest(name=name):
                self.assertTrue(project.forbidden_path(name))
        for name in ['src/Device.java', 'project.json', '.githooks/pre-commit',
                     'releases/0.1.0-before-wakefix/src/A.java']:
            self.assertFalse(project.forbidden_path(name))

    def test_broken_link(self):
        self.write('TASKS.md', '[missing](knowledge/no-such-file.md)\n')
        with self.assertRaises(project.ProjectError):
            self.checked()

    def test_local_link_resolves_relative_to_document(self):
        self.assertEqual(project.link_errors('knowledge/INDEX.md', '[read](../README.md#usage)', {'README.md'}), [])
        self.assertEqual(project.link_errors('README.md', '[web](https://example.com/)', set()), [])
        self.assertTrue(project.link_errors('README.md', '[escape](../outside)', set()))

    def test_syntax_is_checked_without_execution(self):
        self.write('tools/broken.py', 'def invalid(:\n')
        with self.assertRaises(project.ProjectError):
            self.checked()
        self.write('tools/broken.py', 'raise RuntimeError("must not execute during check")\n')
        self.checked()

    def test_symlink_rejected(self):
        (self.root / 'link.java').symlink_to(self.root / 'AndroidManifest.xml')
        with self.assertRaises(project.ProjectError):
            self.checked()

    def test_custom_hook_not_overwritten(self):
        hook = self.root / '.git/hooks/pre-commit'
        hook.write_text('# existing custom hook\n')
        with self.assertRaises(project.ProjectError):
            project.install_hooks(self.root)
        self.assertEqual(hook.read_text(), '# existing custom hook\n')

    def test_dirty_release_is_rejected_before_build(self):
        self.write('new-file.md', 'uncommitted\n')
        with self.assertRaises(project.ProjectError):
            project.release(self.root / 'output', self.root)
        self.assertFalse((self.root / 'output').exists())

    def test_lock_is_exclusive(self):
        with project.build_lock(self.root):
            with self.assertRaises(project.ProjectError):
                with project.build_lock(self.root):
                    pass

    @staticmethod
    def fake_dex(definition, reference='Lde/robv/android/xposed/XposedBridge;'):
        names = [definition, reference]
        strings = b''.join(bytes([len(n)]) + n.encode() + b'\0' for n in names)
        so = 112; to = so + 8; co = to + 8; start = co + 32
        data = bytearray(start + len(strings))
        data[:8] = b'dex\n035\0'
        struct.pack_into('<I', data, 32, len(data))
        struct.pack_into('<IIII', data, 56, 2, so, 2, to)
        struct.pack_into('<II', data, 96, 1, co)
        struct.pack_into('<II', data, so, start, start + len(names[0]) + 2)
        struct.pack_into('<II', data, to, 0, 1)
        struct.pack_into('<I', data, co, 0)
        data[start:] = strings
        return bytes(data)

    def apk(self, definition, extra=None):
        path = self.root / 'synthetic.apk'
        with ZipFile(path, 'w') as z:
            z.writestr('assets/xposed_init', 'dev.eta.acefan.FanHook')
            z.writestr('classes.dex', self.fake_dex(definition))
            if extra:
                z.writestr(extra, 'not a real credential')
        return path

    def test_api_reference_is_not_api_definition(self):
        project.audit_zip(self.apk('Ldev/eta/acefan/FanHook;'))

    def test_bundled_api_definition_rejected(self):
        with self.assertRaises(project.ProjectError):
            project.audit_zip(self.apk('Lde/robv/android/xposed/XposedBridge;'))

    def test_private_apk_payload_rejected(self):
        with self.assertRaises(project.ProjectError):
            project.audit_zip(self.apk('Ldev/eta/acefan/FanHook;', 'secrets/signing.jks'))

    def test_malformed_dex_rejected(self):
        with self.assertRaises(project.ProjectError):
            project.dex_defined_classes(b'bad')


if __name__ == '__main__':
    unittest.main()
