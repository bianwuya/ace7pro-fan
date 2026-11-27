#!/usr/bin/env python3
"""Disposable synthetic ELF/APK/report fixtures test gates, NOT a working KO.

All fixtures and every test ZIP live only inside TemporaryDirectory and are
removed. APK host audit is mocked. No build/sign/load/install/network operation
is performed and no synthetic fixture is eligible for a real release decision.
"""
from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import stat
import struct
import tempfile
import unittest
from unittest import mock
from zipfile import ZipFile

import package_ksu_fan as pkg

MAGIC = '6.6.1-fixture SMP preempt mod_unload modversions aarch64 '
CERT = 'a' * 64
CONFIG = {'package_name': 'dev.eta.acefan', 'signer_certificate_sha256': CERT}
VERSION = {'app_version': '1.2.3', 'version_code': 123}
CRCS = {'module_layout': '0x12345678', 'fan_control': '0xabcdef01'}


def synthetic_elf(*, machine=183, kind=1, magic=MAGIC, name='eta_fan_policy',
                  extra_modinfo=(), omit=(), dependencies='', dynamic=False,
                  import_name='fan_control', extra_sections=()):
    """Tiny non-executable parser fixture; intentionally not a loadable module."""
    modinfo = ('name=' + name + '\0vermagic=' + magic + '\0license=GPL\0depends=' +
               dependencies + '\0' + '\0'.join(extra_modinfo)).encode()
    if not modinfo.endswith(b'\0'):
        modinfo += b'\0'
    versions = b''.join(struct.pack('<Q56s', int(crc, 16), symbol.encode()) for symbol, crc in CRCS.items())
    symstr = b'\0' + import_name.encode() + b'\0'
    symbols = b'\0' * 24 + struct.pack('<IBBHQQ', 1, 0x10, 0, 0, 0, 0)
    specs = [
        ('.shstrtab', 3, b'', 1, 0, None),
        ('.modinfo', 1, modinfo, 1, 0, None),
        ('__versions', 1, versions, 8, 64, None),
        ('.strtab', 3, symstr, 1, 0, None),
        ('.symtab', 2, symbols, 8, 24, '.strtab'),
    ]
    specs.extend(extra_sections)
    if dynamic:
        specs.append(('.dynamic', 6, b'\0' * 16, 8, 16, None))
    specs = [s for s in specs if s[0] not in omit]
    section_names = b'\0' + b''.join(s[0].encode() + b'\0' for s in specs)
    indices = {s[0]: i + 1 for i, s in enumerate(specs)}
    data = bytearray(64)
    rows = [b'\0' * 64]
    for section_name, stype, payload, alignment, entsize, link in specs:
        if section_name == '.shstrtab':
            payload = section_names
        data.extend(b'\0' * (-len(data) % alignment))
        offset = len(data)
        data.extend(payload)
        rows.append(struct.pack('<IIQQQQIIQQ', section_names.index(section_name.encode()),
                                stype, 0, 0, offset, len(payload), indices.get(link, 0),
                                0, alignment, entsize))
    data.extend(b'\0' * (-len(data) % 8))
    shoff = len(data)
    data.extend(b''.join(rows))
    struct.pack_into('<16sHHIQQQIHHHHHH', data, 0,
                     b'\x7fELF\x02\x01\x01' + b'\0' * 9,
                     kind, machine, 1, 0, 0, shoff, 0, 64, 0, 0, 64, len(rows), 1)
    return bytes(data)


def synthetic_helper(enabled, *, present=1, machine=183, kind=2, dynamic=None, stripped=False):
    """Temporary ELF EXEC layout/manifest fixture, never executable release code."""
    base = 0x400000
    names = ['.text', '.rodata', '.shstrtab', '.strtab'] + ([] if stripped else ['.symtab'])
    if dynamic == 'section':
        names.append('.dynamic')
    indices = {name: i + 1 for i, name in enumerate(names)}
    phnum = 2 if dynamic in {'interp', 'segment'} else 1
    data = bytearray(64 + phnum * 56)
    text_at = len(data)
    data.extend(b'\xc0\x03\x5f\xd6')
    data.extend(b'\0' * (-len(data) % 8))
    ro_at = len(data)
    data.extend(b'\0' * 72)
    addresses = {}
    for key in ('filename', 'sha256', 'kernel_release', 'kmi', 'vermagic', 'versions_sha256'):
        addresses[key] = base + len(data)
        data.extend(enabled[key].encode('ascii') + b'\0')
    ro_end = len(data)
    struct.pack_into('<I4x7QI4x', data, ro_at, present, addresses['filename'], addresses['sha256'],
                     enabled['size'], addresses['kernel_release'], addresses['kmi'],
                     addresses['vermagic'], addresses['versions_sha256'], enabled['versions_count'])
    shnames = b'\0' + b''.join(name.encode() + b'\0' for name in names)
    shnames_at = len(data)
    data.extend(shnames)
    str_at = len(data)
    symnames = b'\0fan_release_manifest\0'
    data.extend(symnames)
    data.extend(b'\0' * (-len(data) % 8))
    sym_at = len(data)
    if not stripped:
        data.extend(b'\0' * 24 + struct.pack('<IBBHQQ', 1, 1, 0, indices['.rodata'], base + ro_at, 72))
    dynamic_at = len(data)
    if dynamic == 'section':
        data.extend(struct.pack('<QQ', 1, 1))
    data.extend(b'\0' * (-len(data) % 8))
    shoff = len(data)
    rows = [(0,) * 10]
    specs = {
        '.text': (1, 6, text_at, 4, 4, 0, 0),
        '.rodata': (1, 2, ro_at, ro_end - ro_at, 8, 0, 0),
        '.shstrtab': (3, 0, shnames_at, len(shnames), 1, 0, 0),
        '.strtab': (3, 0, str_at, len(symnames), 1, 0, 0),
        '.symtab': (2, 0, sym_at, 48, 8, 24, indices['.strtab']),
        '.dynamic': (6, 0, dynamic_at, 16, 8, 16, 0),
    }
    for name in names:
        stype, flags, offset, size, alignment, entsize, link = specs[name]
        rows.append((shnames.index(name.encode()), stype, flags,
                     base + offset if flags & 2 else 0, offset, size, link, 0, alignment, entsize))
    for row in rows:
        data.extend(struct.pack('<IIQQQQIIQQ', *row))
    struct.pack_into('<16sHHIQQQIHHHHHH', data, 0,
                     b'\x7fELF\x02\x01\x01' + b'\0' * 9,
                     kind, machine, 1, base + text_at, 64, shoff, 0, 64, 56, phnum, 64,
                     len(rows), indices['.shstrtab'])
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, base, base, len(data), len(data), 0x1000)
    if phnum == 2:
        struct.pack_into('<IIQQQQQQ', data, 120, 3 if dynamic == 'interp' else 2,
                         4, 0, base, base, 16, 16, 1)
    return bytes(data)


class KsuFanPackagingTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='ksu-fan-synthetic-unit-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.apk = self.root / 'fixture.apk'
        with ZipFile(self.apk, 'w') as archive:
            archive.writestr('classes.dex', b'Only a disposable mocked APK fixture, not a real DEX')
        self.ko = self.root / 'fixture.ko'
        self.ko.write_bytes(synthetic_elf())
        self.templates = self.root / 'templates'
        contents = {
            'module.prop': 'id=horae_fan_spoof\nname=风扇控制\nversion=1.2.3\nversionCode=123\n'
                           'author=Local Maintainer\ndescription=风扇控制与状态显示。\n',
            # Harmless fixture commands, never executed; not installers.
            'customize.sh': '#!/system/bin/sh\nprintf "%s\\n" "Synthetic unit fixture, never installed"\n',
            'service.sh': '#!/system/bin/sh\nprintf "%s\\n" "Synthetic unit service, never executed"\n',
            'uninstall.sh': '#!/system/bin/sh\nprintf "%s\\n" "Synthetic unit removal, never executed"\n',
            'META-INF/com/google/android/update-binary':
                '#!/sbin/sh\nprintf "%s\\n" "Synthetic unit entry, never executed"\n',
            'META-INF/com/google/android/updater-script': '#MAGISK\n',
        }
        for name, data in contents.items():
            p = self.templates / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(data, encoding='utf-8')
        self.manifest_path = self.root / 'compatibility.json'
        self.compatibility = {
            'schema': 1, 'target_kernel_release': MAGIC.split()[0], 'expected_vermagic': MAGIC,
            'expected_symbol_crcs': dict(CRCS), 'certsha': CERT,
            'device': {'family': 'ACE7', 'hardware': {
                'ro.product.device': 'fixture-device', 'ro.product.model': 'fixture-model',
                'ro.soc.model': 'fixture-soc', 'ro.board.platform': 'fixture-platform',
            }},
            'artifacts': {'apk': {'sha256': pkg.sha256(self.apk.read_bytes()), 'package_name': 'dev.eta.acefan'},
                          'ko': {'sha256': pkg.sha256(self.ko.read_bytes()), 'name': 'eta_fan_policy', 'license': 'GPL'}},
            'templates': {},
        }
        self.reports = {}
        for kind in ('build', 'check', 'device_load'):
            p = self.root / (kind + '.txt')
            p.write_text('Synthetic unit-only assertion for ' + kind +
                         '; NOT actual build/check/device-loading evidence.\n', encoding='utf-8')
            self.reports[kind] = p
        self.record_path = self.root / 'record.json'
        self.refresh_manifest()
        self.refresh_record()
        self.output = self.root / 'unit-fixture-only.zip'
        state = mock.patch.object(pkg, 'project_state', return_value=(CONFIG.copy(), VERSION.copy()))
        state.start()
        self.addCleanup(state.stop)
        self.audit = mock.patch.object(pkg.project, 'audit_apk', side_effect=self.mock_audit).start()
        self.addCleanup(mock.patch.stopall)

    @staticmethod
    def mock_audit(apk, **kwargs):
        return {'sha256': pkg.sha256(Path(apk).read_bytes()), 'signer_certificate_sha256': CERT,
                'dex_class_counts': {'classes.dex': 1}}

    def inputs(self, **changes):
        values = {'apk': self.apk, 'ko': self.ko, 'manifest': self.manifest_path, 'templates': self.templates,
                  'release_record': self.record_path, 'build_report': self.reports['build'],
                  'check_report': self.reports['check'], 'load_report': self.reports['device_load']}
        values.update(changes)
        return pkg.Inputs(**values)

    def refresh_manifest(self):
        self.compatibility['templates'] = {
            p.relative_to(self.templates).as_posix(): pkg.sha256(p.read_bytes())
            for p in self.templates.rglob('*') if p.is_file()
        }
        self.manifest_path.write_bytes(pkg.canonical_json(self.compatibility))

    def refresh_record(self):
        self.record = {
            'schema': 1, 'kind': 'controlled-local-release',
            'compatibility_sha256': pkg.sha256(pkg.canonical_json(self.compatibility)),
            'template_review': {'reviewer': 'Unit Fixture Maintainer', 'result': 'pass'},
            'reports': {kind: {'sha256': pkg.sha256(p.read_bytes()), 'result': 'pass'}
                        for kind, p in self.reports.items()},
        }
        self.record_path.write_bytes(pkg.canonical_json(self.record))

    def save_record(self):
        self.record_path.write_bytes(pkg.canonical_json(self.record))

    def rejects(self, message, **changes):
        with self.assertRaisesRegex(pkg.PackageError, message):
            pkg.build(self.inputs(**changes), self.output)
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.root.glob('.ksu-fan-*')), [])

    def changed_ko(self, data):
        self.ko.write_bytes(data)
        self.compatibility['artifacts']['ko']['sha256'] = pkg.sha256(data)
        self.refresh_manifest()
        self.refresh_record()

    def cli_args(self):
        args = []
        for key, value in self.inputs().__dict__.items():
            if value is not None:
                args += ['--' + key.replace('_', '-'), str(value)]
        return args

    def test_elf_parser_inventory(self):
        observed = pkg.parse_elf64(self.ko.read_bytes())
        self.assertEqual(observed['name'], 'eta_fan_policy')
        self.assertEqual(observed['symbol_crcs'], CRCS)
        self.assertEqual(observed['undefined_symbols'], ['fan_control'])
        self.assertEqual(observed['type'], 'ET_REL')

    def test_missing_ko_refuses_before_apk_audit(self):
        self.rejects('missing/unreadable', ko=self.root / 'absent.ko')
        self.audit.assert_not_called()

    def test_wrong_machine_class_endian_or_type(self):
        variants = [synthetic_elf(machine=62), synthetic_elf(kind=3)]
        for position, value in ((4, 1), (5, 2)):
            damaged = bytearray(synthetic_elf())
            damaged[position] = value
            variants.append(bytes(damaged))
        for data in variants:
            with self.subTest(header=data[:20]):
                self.changed_ko(data)
                self.rejects('expected')

    def test_truncated_elf_every_cut_refused(self):
        data = synthetic_elf()
        # Every truncation must fail cleanly, not struct.error/IndexError.
        for end in range(len(data)):
            with self.subTest(end=end), self.assertRaises(pkg.PackageError):
                pkg.parse_elf64(data[:end])

    def test_missing_sections(self):
        for name in ('.modinfo', '__versions', '.symtab'):
            with self.subTest(section=name):
                self.changed_ko(synthetic_elf(omit=(name,)))
                self.rejects('mandatory')

    def test_wrong_vermagic(self):
        self.changed_ko(synthetic_elf(magic=MAGIC.replace('6.6.1', '6.6.2')))
        self.rejects('vermagic differs')

    def test_requires_modversions(self):
        self.changed_ko(synthetic_elf(magic=MAGIC.replace('modversions ', '')))
        self.rejects('modversions')

    def test_ko_hash_mismatch(self):
        self.compatibility['artifacts']['ko']['sha256'] = 'b' * 64
        self.refresh_manifest()
        self.rejects('KO: SHA-256 mismatch')

    def test_apk_hash_mismatch(self):
        self.compatibility['artifacts']['apk']['sha256'] = 'b' * 64
        self.refresh_manifest()
        self.rejects('APK: SHA-256 mismatch')

    def test_crc_format(self):
        for value in ('12345678', '0x123', '0xABCDEF01', '0x123456789', 123, True):
            with self.subTest(crc=value):
                self.compatibility['expected_symbol_crcs']['module_layout'] = value
                self.refresh_manifest()
                self.rejects('CRC format')

    def test_crc_mismatch(self):
        self.compatibility['expected_symbol_crcs']['fan_control'] = '0x00000001'
        self.refresh_manifest()
        self.rejects('symbol CRCs differ')

    def test_duplicate_modinfo_fields_refused(self):
        for field in ('name=eta_fan_policy', 'license=GPL', 'vermagic=' + MAGIC, 'depends='):
            with self.subTest(field=field):
                self.changed_ko(synthetic_elf(extra_modinfo=(field,)))
                self.rejects('duplicate .modinfo')

    def test_repeatable_parameter_entries_need_distinct_names(self):
        good = synthetic_elf(extra_modinfo=('parm=mode:Operation mode', 'parm=temp:Temperature'))
        self.assertEqual(pkg.parse_elf64(good)['license'], 'GPL')
        self.changed_ko(synthetic_elf(extra_modinfo=('parm=mode:One', 'parm=mode:Two')))
        self.rejects('duplicate .modinfo parm')

    def test_oem_name_override_forbidden_even_if_declared(self):
        self.changed_ko(synthetic_elf(name='oplus_fan'))
        self.compatibility['artifacts']['ko']['name'] = 'oplus_fan'
        self.refresh_manifest()
        self.rejects('OEM-overriding')

    def test_other_name_requires_exact_manifest_declaration(self):
        self.changed_ko(synthetic_elf(name='eta_fan_extra'))
        self.rejects('name differs')
        self.compatibility['artifacts']['ko']['name'] = 'eta_fan_extra'
        self.refresh_manifest()
        self.refresh_record()
        self.assertEqual(pkg.release_plan(self.inputs()).manifest['elf']['name'], 'eta_fan_extra')

    def test_shared_libraries_modules_and_unversioned_imports_forbidden(self):
        variants = [synthetic_elf(dynamic=True), synthetic_elf(dependencies='oplus_fan'),
                    synthetic_elf(import_name='dlopen'), synthetic_elf(extra_modinfo=('softdep=pre: other',))]
        for data in variants:
            with self.subTest(data=data[:30]):
                self.changed_ko(data)
                self.rejects('dynamic|dependencies|CRC inventory')

    def test_section_budget_and_invalid_offset(self):
        original = synthetic_elf()
        cases = []
        data = bytearray(original)
        struct.pack_into('<H', data, 60, 4096)
        cases.append(data)
        data = bytearray(original)
        struct.pack_into('<Q', data, 40, len(data) + 8)
        cases.append(data)
        for data in cases:
            with self.subTest(data=data[40:64]):
                self.changed_ko(data)
                self.rejects('section')

    def test_bad_templates_missing_noop_placeholder_extra_and_id(self):
        service = self.templates / 'service.sh'
        original = service.read_bytes()
        service.unlink()
        self.rejects('complete reviewed')
        for bad in (b'#!/system/bin/sh\n# comment only\n', b'#!/system/bin/sh\nexit 0\n',
                    b'#!/system/bin/sh\n# TODO implement\necho ok\n', b'not a shell script\n'):
            service.write_bytes(bad)
            self.rejects('templates:')
        service.write_bytes(original)
        extra = self.templates / 'private.key'
        extra.write_text('never a real key\n')
        self.rejects('unexpected file')
        extra.unlink()
        prop = self.templates / 'module.prop'
        prop.write_text(prop.read_text().replace('horae_fan_spoof', 'different_id'))
        self.refresh_manifest()
        self.refresh_record()
        self.rejects('upgrade id')

    def test_template_hash_review_binding(self):
        (self.templates / 'service.sh').write_text('#!/system/bin/sh\necho changed\n')
        self.rejects('templates: SHA-256')
        self.refresh_manifest()
        self.rejects('compatibility/artifact/template binding')

    def test_template_symlink_file_directory_and_root(self):
        service = self.templates / 'service.sh'
        data = service.read_bytes()
        service.unlink()
        target = self.root / 'external.sh'
        target.write_bytes(data)
        service.symlink_to(target)
        self.rejects('symlink')
        service.unlink()
        service.write_bytes(data)
        (self.templates / 'linked-dir').symlink_to(self.root, target_is_directory=True)
        self.rejects('symlink')
        (self.templates / 'linked-dir').unlink()
        alias = self.root / 'template-link'
        alias.symlink_to(self.templates, target_is_directory=True)
        self.rejects('symlink', templates=alias)

    def test_input_symlink_and_template_size_budget(self):
        link = self.root / 'link.ko'
        link.symlink_to(self.ko)
        self.rejects('symlink', ko=link)
        (self.templates / 'service.sh').write_bytes(b'x' * (pkg.MAX_TEMPLATE + 1))
        self.rejects('size budget')

    def test_package_and_certificate_mismatch(self):
        self.compatibility['artifacts']['apk']['package_name'] = 'other.app'
        self.refresh_manifest()
        self.rejects('package differs')
        self.compatibility['artifacts']['apk']['package_name'] = 'dev.eta.acefan'
        self.compatibility['certsha'] = 'b' * 64
        self.refresh_manifest()
        self.rejects('certificate differs')
        self.audit.assert_not_called()

    def test_apk_audit_error_and_audited_certificate_mismatch(self):
        self.audit.side_effect = pkg.project.ProjectError('APK signing identity differs')
        with self.assertRaises(pkg.project.ProjectError):
            pkg.build(self.inputs(), self.output)
        self.assertFalse(self.output.exists())
        self.audit.side_effect = None
        self.audit.return_value = {'sha256': pkg.sha256(self.apk.read_bytes()),
                                   'signer_certificate_sha256': 'b' * 64}
        self.rejects('audited certificate mismatch')

    def test_uses_existing_signed_audit_without_unsigned_bypass(self):
        pkg.release_plan(self.inputs())
        self.audit.assert_called_once_with(self.apk, root=pkg.ROOT, cfg=CONFIG, version=VERSION, unsigned=False)

    def test_audit_detects_apk_or_project_change(self):
        self.audit.side_effect = None
        self.audit.return_value = {'sha256': 'b' * 64, 'signer_certificate_sha256': CERT}
        self.rejects('changed during signature audit')
        self.audit.side_effect = self.mock_audit
        with mock.patch.object(pkg, 'project_state', side_effect=[(CONFIG, VERSION),
                                                               (CONFIG, {**VERSION, 'version_code': 124})]):
            self.rejects('contract/version changed')

    def test_missing_real_loading_record_or_reports_never_writes_output(self):
        for field in ('release_record', 'build_report', 'check_report', 'load_report'):
            with self.subTest(missing=field):
                self.rejects('missing controlled local', **{field: None})
        self.record_path.unlink()
        self.rejects('missing/unreadable')

    def test_verified_true_is_not_evidence(self):
        self.compatibility['verified'] = True
        self.refresh_manifest()
        self.rejects('required fields')
        self.compatibility.pop('verified')
        self.refresh_manifest()
        self.record_path.write_text('{"schema": 1, "verified": true}\n')
        self.rejects('required fields')

    def test_report_hash_result_and_record_binding(self):
        self.record['reports']['device_load']['sha256'] = 'b' * 64
        self.save_record()
        self.rejects('device_load report: SHA-256 mismatch')
        self.refresh_record()
        self.record['reports']['check']['result'] = 'fail'
        self.save_record()
        self.rejects('check report has not passed')
        self.refresh_record()
        self.record['template_review']['result'] = 'unreviewed'
        self.save_record()
        self.rejects('template review has not passed')
        self.refresh_record()
        self.record['compatibility_sha256'] = 'b' * 64
        self.save_record()
        self.rejects('binding mismatch')

    def test_placeholder_report_not_accepted(self):
        for content in ('', 'pass', 'TODO provide actual device loading report\n'):
            with self.subTest(content=content):
                self.reports['device_load'].write_text(content)
                self.refresh_record()
                self.rejects('empty|placeholder')

    def test_reused_report_not_accepted(self):
        self.reports['device_load'].write_bytes(self.reports['check'].read_bytes())
        self.refresh_record()
        self.rejects('separate build/check/device-load')

    def test_hardware_and_schema_are_explicit(self):
        self.compatibility['device']['hardware'].pop('ro.soc.model')
        self.refresh_manifest()
        self.rejects('device.hardware')

    def test_json_duplicate_nonfinite_and_placeholder_fields(self):
        for data in (b'{"schema": 1, "schema": 1}', b'{"schema": NaN}'):
            self.manifest_path.write_bytes(data)
            self.rejects('duplicate JSON|non-finite')
        self.refresh_manifest()
        self.compatibility['device']['hardware']['ro.soc.model'] = 'UNKNOWN'
        self.refresh_manifest()
        self.rejects('unspecified')

    def test_apk_zip_traversal_secret_oem_and_symlink_rejected(self):
        for name, mode in (('../escape', 0), ('assets/signing.pem', 0),
                           ('lib/arm64-v8a/oplus_fan.so', 0), ('assets/link', stat.S_IFLNK | 0o777)):
            with self.subTest(name=name):
                from zipfile import ZipInfo
                with ZipFile(self.apk, 'w') as archive:
                    info = ZipInfo(name)
                    info.external_attr = mode << 16
                    archive.writestr(info, b'payload')
                self.compatibility['artifacts']['apk']['sha256'] = pkg.sha256(self.apk.read_bytes())
                self.refresh_manifest()
                self.rejects('unsafe|forbidden|symlink')

    def test_safe_paths_reject_traversal_backslash_absolute_and_controls(self):
        for name in ('../out', '/out', 'x/../out', 'a//b', './x', 'x\\out', 'x:/out', 'a\nb', 'a/'):
            with self.subTest(path=name), self.assertRaises(pkg.PackageError):
                pkg.safe_member(name)

    def test_deterministic_zip_manifest_inventory_permissions_and_checksums(self):
        pkg.build(self.inputs(), self.output)
        other = self.root / 'second-unit-fixture-only.zip'
        # JSON formatting/key order and source mtimes do not affect normalized output.
        self.manifest_path.write_text(json.dumps(dict(reversed(list(self.compatibility.items()))), indent=1))
        for p in self.templates.rglob('*'):
            if p.is_file():
                import os
                os.utime(p, (1800000000, 1800000000))
        pkg.build(self.inputs(), other)
        self.assertEqual(self.output.read_bytes(), other.read_bytes())
        with ZipFile(self.output) as archive:
            self.assertEqual(archive.namelist(), sorted(archive.namelist()))
            self.assertEqual(set(archive.namelist()), pkg.REQUIRED_TEMPLATES |
                             {pkg.APK_MEMBER, pkg.KO_MEMBER, 'manifest.json', 'SHA256SUMS'})
            manifest = json.loads(archive.read('manifest.json'))
            self.assertEqual(manifest['module_id'], 'horae_fan_spoof')
            self.assertEqual(manifest['compatibility'], self.compatibility)
            self.assertIn('not authenticated device proof', manifest['local_release_record']['interpretation'])
            self.assertNotIn(str(self.root), archive.read('manifest.json').decode())
            for line in archive.read('SHA256SUMS').decode().splitlines():
                expected, name = line.split('  ', 1)
                self.assertEqual(pkg.sha256(archive.read(name)), expected)
            for info in archive.infolist():
                self.assertEqual(info.date_time, (1980, 1, 1, 0, 0, 0))
                self.assertEqual(stat.S_IMODE(info.external_attr >> 16),
                                 0o755 if pkg.executable_member(info.filename) else 0o644)
            self.assertFalse(any(name.endswith(('.txt', '.log', '.key', '.pem')) for name in archive.namelist()))

    def test_existing_output_not_overwritten(self):
        self.output.write_bytes(b'previous work')
        with self.assertRaisesRegex(pkg.PackageError, 'already exists'):
            pkg.build(self.inputs(), self.output)
        self.assertEqual(self.output.read_bytes(), b'previous work')

    def test_output_symlink_and_non_zip_refused(self):
        sentinel = self.root / 'sentinel'
        sentinel.write_bytes(b'unchanged')
        self.output.symlink_to(sentinel)
        with self.assertRaisesRegex(pkg.PackageError, 'already exists'):
            pkg.build(self.inputs(), self.output)
        self.assertEqual(sentinel.read_bytes(), b'unchanged')
        self.output.unlink()
        with self.assertRaisesRegex(pkg.PackageError, 'explicit .zip'):
            pkg.build(self.inputs(), self.root / 'not-a-zip')

    def test_total_size_budget_refuses_without_output(self):
        with mock.patch.object(pkg, 'MAX_PAYLOAD', 1):
            self.rejects('total payload budget')
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                status = pkg.main(self.cli_args() + ['--inspect'])
            self.assertEqual(status, 1)
            self.assertFalse(json.loads(output.getvalue())['release_ready'])
            self.assertFalse(self.output.exists())

    def test_failed_zip_write_cleans_temporary_and_output(self):
        with mock.patch.object(pkg.ZipFile, 'writestr', side_effect=OSError('simulated disk full')):
            self.rejects('ZIP write failed')

    def test_inspect_outputs_diagnostic_but_never_zip(self):
        args = self.cli_args()
        # Remove the local record and all reports: structural observations remain useful.
        args = args[:8]
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = pkg.main(args + ['--inspect'])
        self.assertEqual(code, 1)
        result = json.loads(out.getvalue())
        self.assertFalse(result['release_ready'])
        self.assertEqual(result['observed']['elf']['name'], 'eta_fan_policy')
        self.assertIn('missing controlled local', result['errors'][0])
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.root.glob('*.zip')), [])

    def test_cli_release_missing_record_never_writes(self):
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            code = pkg.main(self.cli_args()[:8] + ['--output', str(self.output)])
        self.assertEqual(code, 1)
        self.assertIn('REFUSED', err.getvalue())
        self.assertFalse(self.output.exists())

    def test_cli_release_and_inspect_valid_record_still_no_device_claim(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = pkg.main(self.cli_args() + ['--inspect'])
        self.assertEqual(code, 0)
        self.assertTrue(json.loads(out.getvalue())['release_ready'])
        self.assertFalse(self.output.exists())
        with contextlib.redirect_stdout(out):
            code = pkg.main(self.cli_args() + ['--output', str(self.output)])
        self.assertEqual(code, 0)
        self.assertTrue(self.output.exists())
        self.assertIn('does not establish device loadability', out.getvalue())

    def test_published_schema_contracts_match_implemented_inventory(self):
        directory = pkg.ROOT / 'packaging' / 'ksu-fan'
        schema = json.loads((directory / 'compatibility.schema.json').read_text())
        self.assertEqual(set(schema['required']), set(self.compatibility))
        self.assertFalse(schema['additionalProperties'])
        templates = schema['properties']['templates']
        self.assertEqual(set(templates['required']), pkg.REQUIRED_TEMPLATES)
        self.assertEqual(set(templates['properties']) - {pkg.PREINIT_CONF} - pkg.COMMISSIONING_TEMPLATES,
                         pkg.REQUIRED_TEMPLATES | pkg.OPTIONAL_TEMPLATES)
        self.assertNotIn(pkg.PREINIT_CONF, templates['required'])
        record = json.loads((directory / 'release-record.schema.json').read_text())
        self.assertEqual(set(record['required']), set(self.record))
        self.assertFalse(record['additionalProperties'])
        self.assertEqual(set(record['properties']['reports']['required']), set(self.reports))

    def test_explicit_additive_keeps_old_gates_and_rejects_preinit_inputs(self):
        self.compatibility['strategy'] = 'additive'
        self.refresh_manifest()
        self.refresh_record()
        self.assertIn(pkg.KO_MEMBER, pkg.release_plan(self.inputs()).entries)
        self.rejects('preinit helper inputs are forbidden', preinit_helper=self.root / 'helper')
        self.compatibility['strategy'] = 'something-else'
        self.refresh_manifest()
        self.rejects('unsupported strategy')
        self.compatibility['strategy'] = 'additive'
        conf = self.templates / pkg.PREINIT_CONF
        conf.parent.mkdir()
        conf.write_text(pkg.PREINIT_COMMAND + '\n')
        self.refresh_manifest()
        self.rejects('incomplete/unknown template inventory')

    def test_optional_watchdog_is_explicitly_hashed_and_preserved(self):
        watchdog = self.templates / 'watchdog.sh'
        watchdog.write_text('#!/system/bin/sh\nprintf "%s\\\\n" "Temporary parser unit fixture"\n')
        self.refresh_manifest()
        self.refresh_record()
        plan = pkg.release_plan(self.inputs())
        self.assertEqual(plan.entries['watchdog.sh'], watchdog.read_bytes())
        self.assertIn('watchdog.sh', plan.manifest['payloads'])
        self.assertIn('watchdog.sh', plan.compatibility['templates'])

    def test_bounded_elf_mutation_never_leaks_parser_exceptions(self):
        import random
        rng = random.Random(7)
        original = synthetic_elf()
        for _ in range(400):
            data = bytearray(original)
            for _ in range(rng.randrange(1, 5)):
                data[rng.randrange(len(data))] = rng.randrange(256)
            try:
                pkg.parse_elf64(data)
            except pkg.PackageError:
                pass

    def test_output_appearing_during_write_is_not_overwritten(self):
        real_open = pkg.os.open

        def open_with_race(path, flags, *args, **kwargs):
            if Path(path) == self.output and flags & pkg.os.O_EXCL:
                self.output.write_bytes(b'concurrent prior work')
            return real_open(path, flags, *args, **kwargs)

        with mock.patch.object(pkg.os, 'open', side_effect=open_with_race):
            with self.assertRaisesRegex(pkg.PackageError, 'ZIP write failed'):
                pkg.build(self.inputs(), self.output)
        self.assertEqual(self.output.read_bytes(), b'concurrent prior work')
        self.assertEqual(list(self.root.glob('.ksu-fan-*')), [])

    def test_failed_fsync_after_output_open_removes_partial_output(self):
        with mock.patch.object(pkg.os, 'fsync', side_effect=[None, OSError('simulated failed flush')]):
            self.rejects('ZIP write failed')

    def test_interrupted_copy_removes_partial_output(self):
        with mock.patch.object(pkg.os, 'fsync', side_effect=[None, KeyboardInterrupt()]):
            with self.assertRaises(KeyboardInterrupt):
                pkg.build(self.inputs(), self.output)
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.root.glob('.ksu-fan-*')), [])

    def test_actual_cli_missing_ko_has_no_mocked_audit_and_no_output(self):
        import os
        import subprocess
        import sys
        args = self.cli_args()
        args[args.index('--ko') + 1] = str(self.root / 'not-supplied.ko')
        result = subprocess.run([sys.executable, '-B', str(pkg.ROOT / 'tools' / 'package_ksu_fan.py'),
                                 *args, '--output', str(self.output)],
                                cwd=self.root, capture_output=True, text=True,
                                env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'}, timeout=20)
        self.assertEqual(result.returncode, 1)
        self.assertIn('REFUSED: missing/unreadable input:', result.stderr)
        self.assertIn('not-supplied.ko', result.stderr)
        self.assertEqual(result.stdout, '')
        self.assertFalse(self.output.exists())
        self.audit.assert_not_called()


class PreinitReplacementTests(unittest.TestCase):
    """No device calls; every ELF/report/ZIP remains a disposable fixture."""
    mock_audit = staticmethod(KsuFanPackagingTests.mock_audit)
    refresh_manifest = KsuFanPackagingTests.refresh_manifest
    save_record = KsuFanPackagingTests.save_record
    rejects = KsuFanPackagingTests.rejects
    cli_args = KsuFanPackagingTests.cli_args

    def setUp(self):
        KsuFanPackagingTests.setUp(self)
        self.magic = '6.6.1-android15-8-fixture SMP preempt mod_unload modversions aarch64 '
        self.ko.write_bytes(synthetic_elf(name='oplus_fan', magic=self.magic))
        self.compatibility.update(strategy='preinit-replace', target_kernel_release=self.magic.split()[0],
                                  expected_vermagic=self.magic)
        self.compatibility['artifacts']['ko'].update(name='oplus_fan', sha256=pkg.sha256(self.ko.read_bytes()))
        elf = pkg.parse_elf64(self.ko.read_bytes(), strategy='preinit-replace')
        self.enabled = {
            'schema': 1, 'present': 1, 'filename': 'oplus_fan.replacement',
            'sha256': pkg.sha256(self.ko.read_bytes()), 'size': len(self.ko.read_bytes()),
            'kernel_release': self.compatibility['target_kernel_release'], 'kmi': '6.6-android15-8',
            'vermagic': self.magic, 'versions_sha256': elf['versions_sha256'],
            'versions_count': elf['versions_count'],
        }
        self.helper = self.root / 'fan-preinit'
        self.helper.write_bytes(synthetic_helper(self.enabled))
        self.enabled_path = self.root / 'helper-enabled.json'
        self.enabled_path.write_bytes(pkg.canonical_json(self.enabled))
        self.compatibility['preinit'] = {
            'target': pkg.STOCK_TARGET, 'stock_hash': pkg.STOCK_HASH,
            'helper_sha256': pkg.sha256(self.helper.read_bytes()),
            'helper_enabled_manifest_digest': pkg.sha256(self.enabled_path.read_bytes()),
            'command': pkg.PREINIT_COMMAND,
        }
        conf = self.templates / pkg.PREINIT_CONF
        conf.parent.mkdir(parents=True)
        conf.write_text(pkg.PREINIT_COMMAND + '\n')
        self.refresh_manifest()
        self.refresh_record()

    def inputs(self, **changes):
        return KsuFanPackagingTests.inputs(self, preinit_helper=self.helper,
                                           helper_enabled_manifest=self.enabled_path, **changes)

    def refresh_record(self):
        KsuFanPackagingTests.refresh_record(self)
        if self.compatibility.get('strategy') == 'preinit-replace':
            spec = self.compatibility['preinit']
            self.record['preinit_helper'] = {key: spec[key] for key in
                                            ('helper_sha256', 'helper_enabled_manifest_digest')}
            self.record['preinit_helper']['result'] = 'pass'
            self.save_record()

    def changed_helper(self, data):
        self.helper.write_bytes(data)
        self.compatibility['preinit']['helper_sha256'] = pkg.sha256(data)
        self.refresh_manifest()
        self.refresh_record()

    def changed_enabled(self):
        self.enabled_path.write_bytes(pkg.canonical_json(self.enabled))
        self.compatibility['preinit']['helper_enabled_manifest_digest'] = pkg.sha256(self.enabled_path.read_bytes())
        self.refresh_manifest()
        self.refresh_record()

    def test_full_layout_deterministic_checksums_and_single_executable_action(self):
        pkg.build(self.inputs(), self.output)
        other = self.root / 'second-preinit-unit-fixture-only.zip'
        self.manifest_path.write_text(json.dumps(dict(reversed(list(self.compatibility.items())))))
        pkg.build(self.inputs(), other)
        self.assertEqual(self.output.read_bytes(), other.read_bytes())
        expected = pkg.REQUIRED_TEMPLATES | {
            pkg.APK_MEMBER, pkg.REPLACEMENT_MEMBER, pkg.HELPER_MEMBER, pkg.PREINIT_CONF,
            'manifest.json', 'SHA256SUMS',
        }
        with ZipFile(self.output) as archive:
            self.assertEqual(set(archive.namelist()), expected)
            self.assertEqual(archive.namelist(), sorted(archive.namelist()))
            self.assertNotIn(pkg.KO_MEMBER, archive.namelist())
            self.assertEqual(archive.read(pkg.PREINIT_CONF), b'exec bin/fan-preinit --apply\n')
            self.assertEqual(archive.read(pkg.REPLACEMENT_MEMBER), self.ko.read_bytes())
            self.assertEqual(archive.read(pkg.HELPER_MEMBER), self.helper.read_bytes())
            self.assertEqual(archive.read(pkg.APK_MEMBER), self.apk.read_bytes())
            self.assertEqual(Path(pkg.REPLACEMENT_MEMBER).parent, Path(pkg.HELPER_MEMBER).parent)
            for info in archive.infolist():
                self.assertEqual(info.date_time, (1980, 1, 1, 0, 0, 0))
                self.assertEqual(stat.S_IMODE(info.external_attr >> 16),
                                 0o755 if pkg.executable_member(info.filename) else 0o644)
            for line in archive.read('SHA256SUMS').decode().splitlines():
                digest, name = line.split('  ', 1)
                self.assertEqual(pkg.sha256(archive.read(name)), digest)
            manifest = json.loads(archive.read('manifest.json'))
            self.assertEqual(manifest['strategy'], 'preinit-replace')
            self.assertEqual(manifest['module_id'], 'horae_fan_spoof')
            self.assertEqual(manifest['elf']['name'], 'oplus_fan')
            self.assertEqual(manifest['preinit_helper']['embedded_manifest'], self.enabled)
            self.assertEqual(manifest['compatibility'], self.compatibility)
            self.assertEqual(manifest['preinit_runtime_requirements']['metadata_min_free_bytes'], 8 * 1024 * 1024)
            self.assertIn('NOT verified offline', manifest['preinit_runtime_requirements']['metadata_space_check'])
            self.assertIn('not authenticated device proof', manifest['local_release_record']['interpretation'])
            self.assertNotIn(str(self.root), archive.read('manifest.json').decode())
        self.audit.assert_called_with(self.apk, root=pkg.ROOT, cfg=CONFIG, version=VERSION, unsigned=False)

    def test_explicit_strategy_required_to_replace_oem_and_wrong_names_rejected(self):
        self.compatibility.pop('strategy')
        self.refresh_manifest()
        self.rejects('required fields')
        self.compatibility['strategy'] = 'preinit-replace'
        for name in ('eta_fan_policy', 'oplus_fan_extra'):
            with self.subTest(name=name):
                self.compatibility['artifacts']['ko']['name'] = name
                self.refresh_manifest()
                self.rejects('invalid/OEM-overriding')
        self.compatibility['artifacts']['ko']['name'] = 'oplus_fan'
        self.ko.write_bytes(synthetic_elf(name='eta_fan_policy', magic=self.magic))
        self.compatibility['artifacts']['ko']['sha256'] = pkg.sha256(self.ko.read_bytes())
        self.refresh_manifest()
        self.rejects('invalid/OEM-overriding')

    def test_stock_file_cannot_masquerade_as_policy(self):
        self.compatibility['artifacts']['ko']['sha256'] = pkg.STOCK_HASH
        self.refresh_manifest()
        self.rejects('stock original is not a policy replacement')
        self.compatibility['artifacts']['ko']['sha256'] = pkg.sha256(self.ko.read_bytes())
        self.refresh_manifest()
        # Patch only the stock constant to the temporary fixture identity so the
        # actual-byte rejection is exercised without reading/copying OEM inputs.
        self.compatibility['preinit']['stock_hash'] = pkg.sha256(self.ko.read_bytes())
        self.refresh_manifest()
        with mock.patch.object(pkg, 'STOCK_HASH', pkg.sha256(self.ko.read_bytes())):
            self.rejects('stock original is not a policy replacement')

    def test_fixed_target_stock_and_command_cannot_be_changed(self):
        cases = [('target', '/system/lib/modules/oplus_fan.ko'),
                 ('target', '/vendor_dlkm/lib/modules/../oplus_fan.ko'),
                 ('stock_hash', 'b' * 64), ('command', 'exec bin/other --apply'),
                 ('command', 'exec bin/fan-preinit --apply; insmod other.ko')]
        for key, value in cases:
            with self.subTest(field=key, value=value):
                original = self.compatibility['preinit'][key]
                self.compatibility['preinit'][key] = value
                self.refresh_manifest()
                self.rejects('preinit:')
                self.compatibility['preinit'][key] = original

    def test_missing_helper_manifest_and_record_each_refuse(self):
        base = self.inputs().__dict__
        for key in ('preinit_helper', 'helper_enabled_manifest', 'release_record', 'build_report',
                    'check_report', 'load_report'):
            with self.subTest(missing=key):
                values = {**base, key: None}
                with self.assertRaises(pkg.PackageError):
                    pkg.build(pkg.Inputs(**values), self.output)
                self.assertFalse(self.output.exists())
        self.helper.unlink()
        self.rejects('missing/unreadable')

    def test_helper_checksum_and_enabled_digest_must_match(self):
        self.compatibility['preinit']['helper_sha256'] = 'b' * 64
        self.refresh_manifest()
        self.rejects('helper SHA-256 mismatch')
        self.compatibility['preinit']['helper_sha256'] = pkg.sha256(self.helper.read_bytes())
        self.compatibility['preinit']['helper_enabled_manifest_digest'] = 'b' * 64
        self.refresh_manifest()
        self.rejects('helper_enabled_manifest_digest mismatch')

    def test_dynamic_wrong_architecture_nonexec_and_stripped_helpers_refused(self):
        for opts in ({'dynamic': 'interp'}, {'dynamic': 'segment'}, {'dynamic': 'section'},
                     {'machine': 62}, {'kind': 3}, {'stripped': True}):
            with self.subTest(options=opts):
                self.changed_helper(synthetic_helper(self.enabled, **opts))
                self.rejects('helper:')

    def test_disabled_compiled_helper_cannot_be_enabled_by_json_or_record(self):
        self.changed_helper(synthetic_helper(self.enabled, present=0))
        self.rejects('disabled development manifest')
        self.assertEqual(self.enabled['present'], 1)
        self.assertEqual(self.record['preinit_helper']['result'], 'pass')

    def test_actual_development_helper_read_only_rejection(self):
        actual = pkg.ROOT / 'build/kernel-replace-audit/preinit-helper/fan-preinit'
        if not actual.is_file():
            self.skipTest('optional existing development helper not available')
        # Read only: do not execute, package, modify or install this real DEV ELF.
        with self.assertRaisesRegex(pkg.PackageError, 'disabled development manifest'):
            pkg.inspect_preinit_helper(pkg.read_bounded(actual, pkg.MAX_PREINIT - 1, 'existing DEV helper'))

    def test_mismatched_compiled_manifest_is_not_certified_by_matching_hashes(self):
        wrong = {**self.enabled, 'sha256': 'b' * 64}
        self.changed_helper(synthetic_helper(wrong))
        self.rejects('compiled helper manifest differs')

    def test_enabled_manifest_must_match_replacement_kernel_versions_and_types(self):
        changes = [('filename', '../oplus_fan.replacement'), ('sha256', 'b' * 64),
                   ('size', self.enabled['size'] + 1), ('kernel_release', '6.6.2-android15-8-other'),
                   ('kmi', '6.6-android15-9'), ('vermagic', self.magic + 'x'),
                   ('versions_sha256', 'b' * 64), ('versions_count', 3),
                   ('present', True), ('schema', True), ('size', True)]
        for key, value in changes:
            with self.subTest(field=key, value=value):
                original = self.enabled[key]
                self.enabled[key] = value
                self.changed_enabled()
                self.rejects('preinit:')
                self.enabled[key] = original
        self.changed_enabled()
        self.enabled['verified'] = True
        self.changed_enabled()
        self.rejects('required fields')

    def test_bad_preinit_config_and_extra_exec_payload_never_allowed(self):
        conf = self.templates / pkg.PREINIT_CONF
        for content in ('exec bin/fan-preinit --apply\nexec bin/other\n',
                        'exec ../bin/fan-preinit --apply\n',
                        'exec /bin/fan-preinit --apply\n',
                        'exec bin/fan-preinit --check-only\n',
                        'exec bin/fan-preinit --apply || true\n'):
            with self.subTest(content=content):
                conf.write_text(content)
                self.rejects('templates:')
        conf.write_text(pkg.PREINIT_COMMAND + '\n')
        extra = self.templates / 'preinit/bin/other'
        extra.parent.mkdir(parents=True)
        extra.write_text('#!/system/bin/sh\necho unexpected\n')
        self.rejects('unexpected directory|unexpected file')
        extra.unlink()
        extra.parent.rmdir()
        (self.templates / 'service.sh').write_text('#!/system/bin/sh\ninsmod oplus_fan.ko\n')
        self.rejects('must not request a second driver load')

    def test_preinit_conf_hash_symlink_and_helper_symlink_are_rejected(self):
        conf = self.templates / pkg.PREINIT_CONF
        self.compatibility['templates'][pkg.PREINIT_CONF] = 'b' * 64
        self.manifest_path.write_bytes(pkg.canonical_json(self.compatibility))
        self.rejects('templates: SHA-256 inventory mismatch')
        self.refresh_manifest()
        conf.unlink()
        conf.symlink_to(self.enabled_path)
        self.rejects('symlink')
        conf.unlink()
        conf.write_text(pkg.PREINIT_COMMAND + '\n')
        target = self.root / 'actual-helper-fixture'
        self.helper.rename(target)
        self.helper.symlink_to(target)
        self.rejects('symlink')

    def test_helper_review_report_failure_and_binding_rejected(self):
        self.record.pop('preinit_helper')
        self.save_record()
        self.rejects('required fields')
        self.refresh_record()
        self.record['preinit_helper']['result'] = 'fail'
        self.save_record()
        self.rejects('helper review has not passed')
        self.refresh_record()
        self.record['preinit_helper']['helper_sha256'] = 'b' * 64
        self.save_record()
        self.rejects('helper review identity mismatch')
        self.refresh_record()
        self.record['reports']['device_load']['result'] = 'fail'
        self.save_record()
        self.rejects('device_load report has not passed')
        self.refresh_record()
        self.record['compatibility_sha256'] = 'b' * 64
        self.save_record()
        self.rejects('binding mismatch')

    def test_preinit_aggregate_budget_is_strictly_less_than_4mib(self):
        # ELF trailing bytes are legal; pad the helper so helper+KO+conf is
        # exactly 4 MiB, which must refuse even though each file is bounded.
        limit = pkg.MAX_PREINIT
        size = len(self.ko.read_bytes()) + len((pkg.PREINIT_COMMAND + '\n').encode())
        original = self.helper.read_bytes()
        padded = original + b'\0' * (limit - size - len(original))
        self.changed_helper(padded)
        self.rejects('must total less than 4 MiB')
        self.changed_helper(padded[:-1])
        self.assertEqual(pkg.release_plan(self.inputs()).manifest['strategy'], 'preinit-replace')

    def test_helper_parser_truncation_and_bounded_mutations(self):
        import random
        data = self.helper.read_bytes()
        for end in range(len(data)):
            with self.subTest(end=end), self.assertRaises(pkg.PackageError):
                pkg.inspect_preinit_helper(data[:end])
        rng = random.Random(17)
        for _ in range(400):
            damaged = bytearray(data)
            for _ in range(rng.randrange(1, 4)):
                damaged[rng.randrange(len(damaged))] = rng.randrange(256)
            try:
                pkg.inspect_preinit_helper(damaged)
            except pkg.PackageError:
                pass

    def test_replacement_schemas_bind_exact_fields_and_conditional_profile(self):
        directory = pkg.ROOT / 'packaging/ksu-fan'
        schema = json.loads((directory / 'compatibility.schema.json').read_text())
        self.assertEqual(set(schema['properties']['preinit']['required']), set(self.compatibility['preinit']))
        self.assertEqual(set(schema['$defs']['helperEnabledManifest']['required']), set(self.enabled))
        condition = schema['allOf'][0]
        self.assertEqual(condition['if']['properties']['strategy']['const'], 'preinit-replace')
        self.assertEqual(condition['then']['properties']['artifacts']['properties']['ko']['properties']['name']['const'],
                         'oplus_fan')
        self.assertIn(pkg.PREINIT_CONF, condition['then']['properties']['templates']['required'])
        record_schema = json.loads((directory / 'release-record.schema.json').read_text())
        self.assertEqual(set(record_schema['properties']['preinit_helper']['required']),
                         set(self.record['preinit_helper']))

    def test_inspect_missing_release_record_shows_preinit_without_writing_zip(self):
        args = self.cli_args()
        at = args.index('--release-record')
        del args[at:at + 2]
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            result = pkg.main(args + ['--inspect'])
        self.assertEqual(result, 1)
        diagnostic = json.loads(out.getvalue())
        self.assertFalse(diagnostic['release_ready'])
        self.assertEqual(diagnostic['observed']['preinit_helper']['embedded_manifest'], self.enabled)
        self.assertFalse(self.output.exists())


class FirstControlCommissioningTests(unittest.TestCase):
    """New channel only; fixtures remain synthetic and cannot prove real control."""
    mock_audit = staticmethod(KsuFanPackagingTests.mock_audit)
    refresh_manifest = KsuFanPackagingTests.refresh_manifest
    refresh_record = PreinitReplacementTests.refresh_record
    save_record = KsuFanPackagingTests.save_record
    rejects = KsuFanPackagingTests.rejects

    def setUp(self):
        PreinitReplacementTests.setUp(self)
        self.compatibility['commissioning_profile'] = pkg.FIRST_CONTROL_PROFILE
        (self.templates / 'fg-debug.sh').write_text(
            '#!/system/bin/sh\nprintf "%s\\n" "Synthetic observer entry, never executed"\n')
        (self.templates / 'allow-control').write_bytes(b'first-control\n')
        self.refresh_manifest()
        self.commissioning_path = self.root / 'commissioning.json'
        self.commissioning_record = {
            'schema': 1, 'kind': 'controlled-local-commissioning', 'profile': 'first-control',
            'compatibility_sha256': pkg.sha256(pkg.canonical_json(self.compatibility)),
            'production_runtime_verified': False, 'candidate_control_tested': False,
            'acknowledgement': pkg.FIRST_CONTROL_ACK,
            'template_review': {'reviewer': 'Unit Fixture Maintainer', 'result': 'pass'},
            'preinit_helper': dict(self.record['preinit_helper']),
            'reports': {name: {'sha256': pkg.sha256(self.reports[name].read_bytes()), 'result': 'pass'}
                        for name in ('build', 'check')},
        }
        self.save_commissioning()

    def save_commissioning(self):
        self.commissioning_path.write_bytes(pkg.canonical_json(self.commissioning_record))

    def inputs(self, **changes):
        values = dict(preinit_helper=self.helper, helper_enabled_manifest=self.enabled_path,
                      release_record=None, load_report=None, commissioning_record=self.commissioning_path,
                      confirm_first_control=pkg.FIRST_CONTROL_ACK)
        values.update(changes)
        return KsuFanPackagingTests.inputs(self, **values)

    def cli_args(self, **changes):
        args = []
        for key, value in self.inputs(**changes).__dict__.items():
            if value is not None:
                args += ['--' + key.replace('_', '-')]
                if key != 'confirm_first_control':
                    args.append(str(value))
        return args

    def with_observer(self):
        observer = self.root / 'observer.txt'
        observer.write_text('Synthetic observer-only report; candidate control remains UNVERIFIED.\n')
        self.commissioning_record['observer_evidence'] = {
            'sha256': pkg.sha256(observer.read_bytes()), 'scope': 'observer-only-not-control-load'}
        self.save_commissioning()
        return observer

    def test_first_control_zip_signed_audit_hashes_permissions_and_no_release_claim(self):
        pkg.build(self.inputs(), self.output)
        second = self.root / 'second-commissioning.zip'
        pkg.build(self.inputs(), second)
        self.assertEqual(self.output.read_bytes(), second.read_bytes())
        with ZipFile(self.output) as archive:
            self.assertIsNone(archive.testzip())
            manifest = json.loads(archive.read('manifest.json'))
            self.assertEqual(manifest['kind'], 'ksu-fan-commissioning')
            self.assertEqual(manifest['commissioning_profile'], 'first-control')
            for name in ('production_runtime_verified', 'candidate_control_tested',
                         'automatic_install', 'automatic_reboot'):
                self.assertIs(manifest[name], False)
            self.assertNotIn('local_release_record', manifest)
            self.assertEqual(set(manifest['local_commissioning_record']['report_sha256']), {'build', 'check'})
            self.assertEqual(archive.read(pkg.REPLACEMENT_MEMBER), self.ko.read_bytes())
            self.assertEqual(archive.read(pkg.HELPER_MEMBER), self.helper.read_bytes())
            self.assertEqual(archive.read('allow-control'), b'first-control\n')
            self.assertEqual(stat.S_IMODE(archive.getinfo('allow-control').external_attr >> 16), 0o644)
            self.assertEqual(stat.S_IMODE(archive.getinfo('fg-debug.sh').external_attr >> 16), 0o755)
            sums = archive.read('SHA256SUMS').decode().splitlines()
            self.assertEqual(len(sums), len(archive.namelist()) - 1)
            for line in sums:
                checksum, name = line.split('  ', 1)
                self.assertEqual(checksum, pkg.sha256(archive.read(name)))
            self.assertNotIn('commissioning.json', archive.namelist())
            self.assertNotIn('observer.txt', archive.namelist())
            self.assertNotIn(str(self.root), archive.read('manifest.json').decode())
        self.assertTrue(self.audit.called)
        self.assertFalse(self.audit.call_args.kwargs['unsigned'])

    def test_explicit_two_part_acknowledgement_and_channel_separation(self):
        self.rejects('both required', confirm_first_control=None)
        self.rejects('both required', commissioning_record=None)
        self.rejects('both required', confirm_first_control='first-control')
        self.rejects('release-record/load-report are forbidden', release_record=self.record_path)
        self.rejects('release-record/load-report are forbidden', load_report=self.reports['device_load'])
        with self.assertRaisesRegex(pkg.PackageError, 'cannot satisfy release gates'):
            pkg.release_plan(self.inputs())

    def test_explicit_profile_required_and_additive_not_eligible(self):
        self.compatibility.pop('commissioning_profile')
        self.refresh_manifest()
        self.rejects('explicit preinit-replace')
        self.compatibility['commissioning_profile'] = 'first-control'
        self.compatibility['strategy'] = 'additive'
        self.refresh_manifest()
        self.rejects('explicit preinit-replace')

    def test_flags_and_record_acknowledgement_cannot_promote_candidate(self):
        for key in ('production_runtime_verified', 'candidate_control_tested'):
            for value in (True, 0, 'false', None):
                with self.subTest(key=key, value=value):
                    self.commissioning_record[key] = value
                    self.save_commissioning()
                    self.rejects('must both be false')
            self.commissioning_record[key] = False
        self.commissioning_record['acknowledgement'] = 'first-control'
        self.save_commissioning()
        self.rejects('acknowledgement required')

    def test_release_record_and_device_load_report_fields_cannot_be_reused(self):
        self.commissioning_record['kind'] = 'controlled-local-release'
        self.save_commissioning()
        self.rejects('controlled-local-commissioning')
        self.commissioning_record['kind'] = 'controlled-local-commissioning'
        self.commissioning_record['reports']['device_load'] = {'sha256': 'c' * 64, 'result': 'pass'}
        self.save_commissioning()
        self.rejects('no device_load')

    def test_build_check_mandatory_distinct_hash_bound_and_passing(self):
        self.rejects('separate build-report and check-report', build_report=None)
        self.rejects('separate build-report and check-report', check_report=None)
        for name in ('build', 'check'):
            original = dict(self.commissioning_record['reports'][name])
            self.commissioning_record['reports'][name]['result'] = 'fail'
            self.save_commissioning()
            self.rejects(f'{name} report has not passed')
            self.commissioning_record['reports'][name] = {**original, 'sha256': 'b' * 64}
            self.save_commissioning()
            self.rejects('SHA-256 mismatch')
            self.commissioning_record['reports'][name] = original
        self.reports['check'].write_bytes(self.reports['build'].read_bytes())
        self.commissioning_record['reports']['check']['sha256'] = pkg.sha256(self.reports['check'].read_bytes())
        self.save_commissioning()
        self.rejects('must be separate report bytes')

    def test_honest_unverified_reports_allowed_but_placeholders_refused(self):
        for content, accepted in (
                ('Build completed; candidate control is UNVERIFIED and has never been loaded.\n', True),
                ('TODO: replace this report with actual build evidence before commissioning.\n', False)):
            self.reports['build'].write_text(content)
            self.commissioning_record['reports']['build']['sha256'] = pkg.sha256(self.reports['build'].read_bytes())
            self.save_commissioning()
            if accepted:
                pkg.commissioning_plan(self.inputs())
            else:
                self.rejects('placeholder/unreviewed report')

    def test_observer_is_optional_separate_scoped_and_never_a_control_load(self):
        observer = self.with_observer()
        plan = pkg.commissioning_plan(self.inputs(observer_report=observer))
        report = plan.manifest['local_commissioning_record']
        self.assertEqual(set(report['report_sha256']), {'build', 'check', 'observer'})
        self.assertIs(report['candidate_control_tested'], False)
        self.assertNotIn('device_load', report['report_sha256'])
        self.rejects('required fields are exactly')  # Observer declaration without its file.
        self.commissioning_record['observer_evidence']['scope'] = 'candidate-control-load'
        self.save_commissioning()
        self.rejects('observer-only-not-control-load', observer_report=observer)
        self.commissioning_record['observer_evidence']['scope'] = 'observer-only-not-control-load'
        self.commissioning_record['observer_evidence']['sha256'] = 'b' * 64
        self.save_commissioning()
        self.rejects('observer report: SHA-256 mismatch', observer_report=observer)
        self.commissioning_record.pop('observer_evidence')
        self.save_commissioning()
        self.rejects('required fields are exactly', observer_report=observer)

    def test_commissioning_templates_are_required_and_never_allowed_in_ordinary_profiles(self):
        for strategy in ('additive', 'preinit-replace'):
            self.assertTrue(pkg.COMMISSIONING_TEMPLATES.isdisjoint(pkg.template_names(strategy)[1]))
        with self.assertRaisesRegex(pkg.PackageError, 'requires explicit preinit-replace'):
            pkg.template_names('additive', commissioning=True)
        for name in sorted(pkg.COMMISSIONING_TEMPLATES):
            path = self.templates / name
            original = path.read_bytes()
            path.unlink()
            self.refresh_manifest()
            self.rejects('incomplete/unknown template inventory')
            path.write_bytes(original)
            self.refresh_manifest()
        self.compatibility.pop('commissioning_profile')
        self.refresh_manifest()
        self.rejects('incomplete/unknown template inventory', commissioning_record=None,
                     confirm_first_control=None, release_record=self.record_path,
                     load_report=self.reports['device_load'])

    def test_marker_and_debug_script_keep_exact_content_and_all_shell_gates(self):
        cases = [
            ('allow-control', b'1\n', 'exactly first-control'),
            ('allow-control', b'first-control\nextra\n', 'exactly first-control'),
            ('fg-debug.sh', b'#!/system/bin/sh\ntrue\n', 'no-op'),
            ('fg-debug.sh', b'#!/system/bin/sh\ninsmod driver.ko\n', 'second driver load'),
            ('fg-debug.sh', b'printf "%s" "not a script"\n', 'shebang'),
        ]
        for name, data, message in cases:
            with self.subTest(name=name, data=data):
                path = self.templates / name
                original = path.read_bytes()
                path.write_bytes(data)
                self.refresh_manifest()
                self.rejects(message)
                path.write_bytes(original)
                self.refresh_manifest()

    def test_missing_installer_not_generated_and_new_templates_not_silently_ignored(self):
        installer = self.templates / 'META-INF/com/google/android/update-binary'
        installer.unlink()
        self.refresh_manifest()
        self.rejects('incomplete/unknown template inventory')
        self.assertFalse(installer.exists())

    def test_commissioning_retains_artifact_crc_helper_and_signature_audit_gates(self):
        original = self.compatibility['expected_symbol_crcs']['module_layout']
        self.compatibility['expected_symbol_crcs']['module_layout'] = '0x87654321'
        self.refresh_manifest()
        self.rejects('symbol CRCs differ')
        self.compatibility['expected_symbol_crcs']['module_layout'] = original
        self.refresh_manifest()
        original_helper = self.helper.read_bytes()
        self.helper.write_bytes(original_helper + b'changed')
        self.rejects('helper SHA-256 mismatch')
        self.helper.write_bytes(original_helper)
        self.audit.side_effect = pkg.project.ProjectError('signature audit rejected')
        with self.assertRaisesRegex(pkg.project.ProjectError, 'signature audit rejected'):
            pkg.build(self.inputs(), self.output)
        self.assertFalse(self.output.exists())
        self.audit.side_effect = self.mock_audit
        self.compatibility['artifacts']['apk']['sha256'] = 'b' * 64
        self.refresh_manifest()
        self.rejects('APK: SHA-256 mismatch')

    def test_review_and_compatibility_binding_cannot_be_omitted(self):
        self.commissioning_record['template_review']['result'] = 'fail'
        self.save_commissioning()
        self.rejects('template review has not passed')
        self.commissioning_record['template_review']['result'] = 'pass'
        self.commissioning_record['preinit_helper']['helper_sha256'] = 'b' * 64
        self.save_commissioning()
        self.rejects('helper review identity mismatch')
        self.commissioning_record['preinit_helper']['helper_sha256'] = self.compatibility['preinit']['helper_sha256']
        self.commissioning_record['compatibility_sha256'] = 'c' * 64
        self.save_commissioning()
        self.rejects('compatibility binding mismatch')

    def test_cli_inspect_never_release_ready_and_output_is_clearly_commissioning(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = pkg.main(self.cli_args() + ['--inspect'])
        self.assertEqual(code, 0)
        diagnostics = json.loads(out.getvalue())
        self.assertTrue(diagnostics['commissioning_ready'])
        self.assertFalse(diagnostics['release_ready'])
        self.assertIs(diagnostics['production_runtime_verified'], False)
        self.assertIs(diagnostics['candidate_control_tested'], False)
        self.assertFalse(self.output.exists())
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = pkg.main(self.cli_args() + ['--output', str(self.output)])
        self.assertEqual(code, 0)
        self.assertIn('FIRST CONTROL commissioning packaging', out.getvalue())
        self.assertNotIn('PASS offline release packaging', out.getvalue())
        self.assertIn('candidate_control_tested=false', out.getvalue())
        self.assertTrue(self.output.exists())

    def test_known_candidate_layout_observations_require_exact_review_not_generic_bypass(self):
        data = bytearray(synthetic_elf(name='oplus_fan', magic=self.magic,
            extra_modinfo=('import_ns=PWM', 'import_ns=PWM', 'import_ns=PWM'),
            extra_sections=(('.plt', 1, b'\0' * 32, 16, 0, None),
                            ('.gnu.linkonce.this_module', 1, b'\0' * 128, 64, 0, None))))
        header = struct.unpack_from('<16sHHIQQQIHHHHHH', data)
        shoff, shnum, shstr = header[6], header[12], header[13]
        strings_row = struct.unpack_from('<IIQQQQIIQQ', data, shoff + shstr * 64)
        names = data[strings_row[4]:strings_row[4] + strings_row[5]]
        for index in range(1, shnum):
            at = shoff + index * 64
            row = list(struct.unpack_from('<IIQQQQIIQQ', data, at))
            name = names[row[0]:].split(b'\0', 1)[0].decode()
            if name in {'.plt', '.gnu.linkonce.this_module'}:
                row[2] = 2 if name == '.plt' else 3
                row[4] += 1
                row[5] = 1
                struct.pack_into('<IIQQQQIIQQ', data, at, *row)
        with self.assertRaisesRegex(pkg.PackageError, 'misaligned section'):
            pkg.parse_elf64(data, strategy='preinit-replace')
        elf = pkg.parse_elf64(data, strategy='preinit-replace', commissioning=True)
        self.assertEqual(elf['commissioning_duplicate_import_namespaces'],
                         [{'namespace': 'PWM', 'occurrences': 3}])
        self.assertEqual(len(elf['commissioning_file_alignment_observations']), 2)
        self.ko.write_bytes(data)
        self.compatibility['artifacts']['ko']['sha256'] = pkg.sha256(data)
        self.enabled.update(sha256=pkg.sha256(data), size=len(data))
        self.enabled_path.write_bytes(pkg.canonical_json(self.enabled))
        self.helper.write_bytes(synthetic_helper(self.enabled))
        self.compatibility['preinit'].update(helper_sha256=pkg.sha256(self.helper.read_bytes()),
            helper_enabled_manifest_digest=pkg.sha256(self.enabled_path.read_bytes()))
        self.refresh_manifest()
        self.commissioning_record['compatibility_sha256'] = pkg.sha256(pkg.canonical_json(self.compatibility))
        for key in ('helper_sha256', 'helper_enabled_manifest_digest'):
            self.commissioning_record['preinit_helper'][key] = self.compatibility['preinit'][key]
        self.save_commissioning()
        self.rejects('elf_candidate_layout_review')
        review = {'file_alignment': elf['commissioning_file_alignment_observations'],
                  'duplicate_import_namespaces': elf['commissioning_duplicate_import_namespaces'],
                  'reason': 'Synthetic candidate allocated-section file offsets and repeated namespace reviewed.',
                  'result': 'pass'}
        self.commissioning_record['elf_candidate_layout_review'] = review
        self.save_commissioning()
        pkg.commissioning_plan(self.inputs())
        review['duplicate_import_namespaces'][0]['occurrences'] = 2
        self.save_commissioning()
        self.rejects('exact ELF candidate layout observations review required')
        # Permission to review these two allocated sections is not a blanket ELF waiver.
        for index in range(1, shnum):
            at = shoff + index * 64
            row = list(struct.unpack_from('<IIQQQQIIQQ', data, at))
            if names[row[0]:].split(b'\0', 1)[0] == b'.plt':
                row[2] = 6
                struct.pack_into('<IIQQQQIIQQ', data, at, *row)
                break
        with self.assertRaisesRegex(pkg.PackageError, 'misaligned section'):
            pkg.parse_elf64(data, strategy='preinit-replace', commissioning=True)
        duplicate_name = synthetic_elf(name='oplus_fan', magic=self.magic,
                                       extra_modinfo=('name=oplus_fan',))
        with self.assertRaisesRegex(pkg.PackageError, 'duplicate .modinfo name'):
            pkg.parse_elf64(duplicate_name, strategy='preinit-replace', commissioning=True)

    def test_exact_oem_uname_vermagic_difference_requires_separate_review_only_in_commissioning(self):
        target = '6.6.1-android15-8-observed-device-build'
        self.compatibility['target_kernel_release'] = target
        self.enabled['kernel_release'] = target
        self.enabled_path.write_bytes(pkg.canonical_json(self.enabled))
        self.helper.write_bytes(synthetic_helper(self.enabled))
        self.compatibility['preinit']['helper_sha256'] = pkg.sha256(self.helper.read_bytes())
        self.compatibility['preinit']['helper_enabled_manifest_digest'] = pkg.sha256(self.enabled_path.read_bytes())
        self.refresh_manifest()
        self.commissioning_record['compatibility_sha256'] = pkg.sha256(pkg.canonical_json(self.compatibility))
        self.commissioning_record['preinit_helper'].update(self.compatibility['preinit'])
        self.commissioning_record['preinit_helper'] = {
            key: self.commissioning_record['preinit_helper'][key]
            for key in ('helper_sha256', 'helper_enabled_manifest_digest', 'result')}
        self.save_commissioning()
        self.rejects('vermagic_release_review')
        review = {'target_kernel_release': target, 'expected_vermagic': self.magic,
                  'reason': 'Synthetic OEM uname and module build labels differ; exact helper and CRC identities reviewed.',
                  'result': 'pass'}
        self.commissioning_record['vermagic_release_review'] = review
        self.save_commissioning()
        plan = pkg.commissioning_plan(self.inputs())
        self.assertEqual(plan.manifest['local_commissioning_record']['vermagic_release_review'], review)
        review['target_kernel_release'] = self.magic.split()[0]
        self.save_commissioning()
        self.rejects('exact target uname/vermagic mismatch review required')
        # Ordinary release still refuses the same mismatch before any record could waive it.
        ordinary = dict(self.compatibility)
        ordinary.pop('commissioning_profile')
        with self.assertRaisesRegex(pkg.PackageError, 'must bind target release'):
            pkg.validate_compatibility(ordinary)

    def test_commissioning_schema_matches_record_and_conditional_template_scope(self):
        directory = pkg.ROOT / 'packaging' / 'ksu-fan'
        schema = json.loads((directory / 'commissioning-record.schema.json').read_text())
        self.assertEqual(set(schema['required']), set(self.commissioning_record))
        self.assertFalse(schema['additionalProperties'])
        self.assertEqual(set(schema['properties']['reports']['required']), {'build', 'check'})
        self.assertIs(schema['properties']['production_runtime_verified']['const'], False)
        self.assertIs(schema['properties']['candidate_control_tested']['const'], False)
        compatibility = json.loads((directory / 'compatibility.schema.json').read_text())
        condition = compatibility['allOf'][1]
        self.assertEqual(condition['then']['properties']['strategy']['const'], 'preinit-replace')
        self.assertEqual(set(condition['then']['properties']['templates']['required']), pkg.COMMISSIONING_TEMPLATES)


class LegacyReleaseByteCompatibilityTests(unittest.TestCase):
    def test_unchanged_additive_and_preinit_release_zip_bytes(self):
        from zipfile import ZipInfo
        fixtures = (
            (KsuFanPackagingTests, 'c7fbf65a6d75294f25eae534ed0752a61b1754daf282902d70f3c65c2a9d008c'),
            (PreinitReplacementTests, '4809197a4ca27212f42d22ac89a2d7927777fbce239440951b163782cced6051'),
        )
        for cls, expected in fixtures:
            with self.subTest(profile=cls.__name__):
                fixture = cls('runTest')
                try:
                    fixture.setUp()
                    with ZipFile(fixture.apk, 'w') as archive:
                        archive.writestr(ZipInfo('classes.dex', date_time=(1980, 1, 1, 0, 0, 0)),
                                         b'Only a disposable mocked APK fixture, not a real DEX')
                    fixture.compatibility['artifacts']['apk']['sha256'] = pkg.sha256(fixture.apk.read_bytes())
                    fixture.refresh_manifest()
                    fixture.refresh_record()
                    pkg.build(fixture.inputs(), fixture.output)
                    self.assertEqual(pkg.sha256(fixture.output.read_bytes()), expected)
                finally:
                    fixture.doCleanups()


if __name__ == '__main__':
    unittest.main()
