#!/usr/bin/env python3
"""Offline real-OEM + disposable synthetic ELF tests. Never loads any object.

Fixtures and outputs are confined to build/kernel-replace-audit/driver-relink/.
No network, ZIP, device command, sysfs access, real-driver redirection or install.
"""
from __future__ import annotations

import copy
import json
from pathlib import Path
import shutil
import struct
import tempfile
import unittest

import relink_fan_driver as r

# Only tiny synthetic assembly fixtures are redirected. These RET stubs are not
# a driver, do not carry module_layout/struct module and are never made a KO.
SYNTHETIC = r'''
.text
.p2align 2
.word 0xc7f8c87c
.type oplus_fan_probe,%function
oplus_fan_probe:
    adrp x1, oplus_fan_attrs_group
    add x1, x1, :lo12:oplus_fan_attrs_group
    bl sysfs_create_group
    ret
.size oplus_fan_probe,.-oplus_fan_probe
.word 0x95c41a97
.type state_store,%function
state_store: ret
.size state_store,.-state_store
.word 0x95c41a97
.type level_store,%function
level_store: ret
.size level_store,.-level_store
.word 0xc7f8c87c
.global replacement_probe
.type replacement_probe,%function
replacement_probe: ret
.size replacement_probe,.-replacement_probe
.word 0x95c41a97
.global replacement_store
.type replacement_store,%function
replacement_store: ret
.size replacement_store,.-replacement_store
.word 0x12345678
.global incompatible_callback
.type incompatible_callback,%function
incompatible_callback: ret
.size incompatible_callback,.-incompatible_callback

.data
.p2align 3
.type oplus_fan_driver,%object
oplus_fan_driver: .xword 0, oplus_fan_probe
.size oplus_fan_driver,.-oplus_fan_driver
.type oplus_fan_attrs_group,%object
oplus_fan_attrs_group: .xword 0, 0, 0, oplus_fan_attrs, 0
.size oplus_fan_attrs_group,.-oplus_fan_attrs_group
.type oplus_fan_attrs,%object
oplus_fan_attrs: .xword dev_attr_state, dev_attr_level
.zero 112
.size oplus_fan_attrs,.-oplus_fan_attrs
.type dev_attr_state,%object
 dev_attr_state: .xword 0, 0, 0, state_store
.size dev_attr_state,.-dev_attr_state
.type dev_attr_level,%object
 dev_attr_level: .xword 0, 0, 0, level_store
.size dev_attr_level,.-dev_attr_level

.section .modinfo,"a",%progbits
.asciz "import_ns=PWM", "name=fixture_only_not_a_module"
.section __versions,"a",%progbits
.p2align 3
.quad 0x12345678
.asciz "sysfs_create_group"
.zero 37
.quad 0x87654321
.asciz "module_layout"
.zero 42
.section __version_ext_names,"a",%progbits
.asciz "sysfs_create_group", "module_layout"
.byte 0
.section __version_ext_crcs,"a",%progbits
.long 0x12345678, 0x87654321
.section .note.GNU-stack,"",%progbits
'''


class RelinkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        r.ANALYSIS.mkdir(parents=True, exist_ok=True)
        cls.temp = tempfile.TemporaryDirectory(prefix='synthetic-tests-', dir=r.ANALYSIS)
        cls.root = Path(cls.temp.name)
        cls.clang = shutil.which('clang')
        cls.linker = shutil.which('ld.lld')
        cls.objcopy = shutil.which('aarch64-linux-gnu-objcopy')
        if not all((cls.clang, cls.linker, cls.objcopy)):
            cls.temp.cleanup()
            raise RuntimeError('tests require existing clang, ld.lld, AArch64 GNU objcopy')
        cls.source = cls.root / 'fixture.S'
        cls.source.write_text(SYNTHETIC, encoding='utf-8')
        cls.assembled = cls.root / 'fixture-assembled.analysis.o'
        cls.fixture_path = cls.root / 'fixture.analysis.o'
        r.command([cls.clang, '--target=aarch64-linux-gnu', '-c', str(cls.source), '-o', str(cls.assembled)])
        # Mirror the OEM's separate .strtab/.shstrtab. The strict comparator
        # deliberately refuses objcopy introducing a previously absent section.
        r.command([cls.linker, '-r', str(cls.assembled), '-o', str(cls.fixture_path)])
        cls.fixture = r.Elf.read(cls.fixture_path)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def mutate(self, elf, offset, data):
        out = bytearray(elf.data)
        out[offset:offset + len(data)] = data
        return r.Elf(out, 'corrupted synthetic fixture')

    def section_word(self, elf, name, field_offset, value):
        return self.mutate(elf, elf.shoff + elf.by_name[name]['index'] * 64 + field_offset,
                           struct.pack('<Q', value))

    def test_fixture_inventory_and_extended_implicit_nul(self):
        inv = r.inventory(self.fixture)
        self.assertEqual(inv['function_count'], 6)
        self.assertEqual(inv['imports'], ['sysfs_create_group'])
        self.assertEqual(inv['version_count'], 2)
        self.assertEqual(inv['versions']['legacy'], inv['versions']['extended'])
        self.assertFalse(inv['signature']['appended_marker_present'])

    def test_unique_definition_required(self):
        with self.assertRaises(r.AuditError):
            self.fixture.unique_symbol('missing_function')
        with self.assertRaises(r.AuditError):
            self.fixture.unique_symbol('oplus_fan_probe', 1)

    def test_malformed_headers_fail_closed(self):
        for raw in [b'\x7fELF', self.fixture.data[:64], b'not-an-elf' * 20]:
            with self.subTest(length=len(raw)), self.assertRaises(r.AuditError):
                r.Elf(raw)
        for offset, data in [(4, b'\x01'), (5, b'\x02'), (16, b'\x02\0'), (18, b'\x3e\0')]:
            with self.subTest(offset=offset), self.assertRaises(r.AuditError):
                self.mutate(self.fixture, offset, data)

    def test_invalid_relocation_symbol_is_rejected(self):
        relocation = self.fixture.relocations[0]
        with self.assertRaises(r.AuditError):
            self.mutate(self.fixture, relocation['table_offset'] + 8,
                        struct.pack('<Q', (0xffffff << 32) | relocation['type']))

    def test_duplicate_selected_definition_fails_before_output(self):
        original = self.fixture.unique_symbol('oplus_fan_probe', 2)
        replacement = self.fixture.unique_symbol('replacement_probe', 2)
        symbol_base = self.fixture.symtab['offset']
        old_name_offset = struct.unpack_from('<I', self.fixture.data,
                                             symbol_base + original['index'] * 24)[0]
        modified = self.mutate(self.fixture, symbol_base + replacement['index'] * 24,
                               struct.pack('<I', old_name_offset))
        with self.assertRaises(r.AuditError):
            modified.unique_symbol('oplus_fan_probe', 2)
        source = self.root / 'duplicate.analysis.o'
        source.write_bytes(modified.data)
        output = self.root / 'duplicate-output.analysis.o'
        with self.assertRaises(r.AuditError):
            r.prepare_symbols(source, output, r.RENAMES, self.objcopy)
        self.assertFalse(output.exists())

    def test_symbol_local_boundary_and_imports_are_protected(self):
        at = self.fixture.shoff + self.fixture.symtab['index'] * 64 + 44
        with self.assertRaises(r.AuditError):
            self.mutate(self.fixture, at, struct.pack('<I', 1))
        fun = self.fixture.unique_symbol('replacement_probe', 2)
        at = self.fixture.symtab['offset'] + fun['index'] * 24
        row = list(r.SYM.unpack_from(self.fixture.data, at))
        row[3], row[4], row[5] = 0, 0, 0
        modified = self.mutate(self.fixture, at, r.SYM.pack(*row))
        result = r.compare(self.fixture, modified)
        self.assertFalse(result['passed'])
        self.assertIn('undefined imports', [d['category'] for d in result['differences']])

    def test_strict_gate_rejects_objcopy_adding_unexpected_sections(self):
        # A raw clang object has a unified string table; objcopy adds .shstrtab.
        # Do not silently broaden the real-OEM preservation gate to mask this.
        output = self.root / 'unified-strtab.analysis.o'
        with self.assertRaises(r.AuditError):
            r.prepare_symbols(self.assembled, output, r.RENAMES, self.objcopy)
        self.assertTrue(output.exists())  # Retained analysis evidence, never promoted.

    def test_machine_code_corruption_is_detected(self):
        f = self.fixture.unique_symbol('level_store', 2)
        at = self.fixture.sections[f['shndx']]['offset'] + f['value']
        modified = self.mutate(self.fixture, at, b'\0\0\0\0')
        result = r.compare(self.fixture, modified)
        self.assertFalse(result['passed'])
        self.assertTrue(any(d['category'] == 'FUNC machine code level_store' for d in result['differences']))

    def test_prefix_corruption_is_detected(self):
        f = self.fixture.unique_symbol('state_store', 2)
        at = self.fixture.sections[f['shndx']]['offset'] + f['value'] - 4
        modified = self.mutate(self.fixture, at, b'\0' * 4)
        categories = [d['category'] for d in r.compare(self.fixture, modified)['differences']]
        self.assertIn('FUNC prefix4 state_store', categories)

    def test_section_flags_and_alignment_are_protected(self):
        for field, offset, value in [('flags', 8, 2), ('align', 48, 8)]:
            modified = self.section_word(self.fixture, '.text', offset, value)
            result = r.compare(self.fixture, modified)
            self.assertFalse(result['passed'])
            self.assertIn('section .text ' + field, [d['category'] for d in result['differences']])
            with self.assertRaises(r.AuditError):
                r.restore_lld_plt_flag(self.fixture, modified)

    def test_namespace_is_protected(self):
        at = self.fixture.data.index(b'import_ns=PWM') + len(b'import_ns=')
        result = r.compare(self.fixture, self.mutate(self.fixture, at, b'BAD'))
        self.assertFalse(result['passed'])
        self.assertTrue(any(d['category'] == 'section .modinfo payload SHA256' for d in result['differences']))

    def test_version_corruption_fails_closed(self):
        at = self.fixture.by_name['__versions']['offset']
        modified = self.mutate(self.fixture, at, struct.pack('<Q', 0x11111111))
        with self.assertRaises(r.AuditError):
            r.compare(self.fixture, modified)
        with self.assertRaises(r.AuditError):
            r.inventory(modified)

    def test_extended_names_reject_nonzero_trailing_data(self):
        sec = self.fixture.by_name['__version_ext_names']
        modified = self.mutate(self.fixture, sec['offset'] + sec['size'] - 2, b'X')
        with self.assertRaises(r.AuditError):
            r.versions(modified)

    def test_relocation_addend_is_protected(self):
        at = self.fixture.relocations[0]['table_offset'] + 16
        modified = self.mutate(self.fixture, at, struct.pack('<q', 4))
        result = r.compare(self.fixture, modified)
        self.assertFalse(result['passed'])
        self.assertIn('relocations', [d['category'] for d in result['differences']])

    def test_real_relocation_graph_not_guessed_structs(self):
        plan = r.callback_plan(self.fixture)
        self.assertEqual([x['role'] for x in plan['callbacks']], ['probe', 'state_store', 'level_store'])
        self.assertEqual(len(plan['attribute_group']['probe_group_address']), 2)
        self.assertEqual(plan['attribute_group']['attrs_size_observed'], 128)
        self.assertTrue(all(x['relocation']['type'] == 257 for x in plan['callbacks']))

    def test_globalize_and_rename_real_tool(self):
        output = self.root / 'globalized.analysis.o'
        result = r.prepare_symbols(self.fixture_path, output, r.RENAMES, self.objcopy)
        self.assertTrue(result['comparison']['passed'])
        after = r.Elf.read(output)
        for old, new in r.RENAMES.items():
            self.assertEqual(after.unique_symbol(new)['bind'], 1)
            self.assertFalse(any(s['name'] == old for s in after.symbols))
        with self.assertRaises(r.AuditError):
            r.prepare_symbols(self.fixture_path, output, r.RENAMES, self.objcopy)

    def test_rename_collisions_and_missing_names_leave_no_output(self):
        for name, mapping in [('collision', {'level_store': 'replacement_store'}),
                              ('missing', {'missing': 'eta_original_missing'}),
                              ('noninjective', {'level_store': 'same', 'state_store': 'same'})]:
            output = self.root / (name + '.analysis.o')
            with self.subTest(name=name), self.assertRaises(r.AuditError):
                r.prepare_symbols(self.fixture_path, output, mapping, self.objcopy)
            self.assertFalse(output.exists())

    def specs(self):
        plan = r.callback_plan(self.fixture)
        return [{'input_sha256': r.sha(self.fixture.data),
                 'section': c['relocation']['section'], 'offset': c['relocation']['offset'],
                 'type': c['relocation']['type'], 'original_function': c['function'],
                 'replacement_function': 'replacement_probe' if c['role'] == 'probe' else 'replacement_store'}
                for c in plan['callbacks']]

    def test_exact_rela_redirection_synthetic_only(self):
        specs = self.specs()
        payload, log = r.redirect_function_relocations(self.fixture, specs)
        modified = r.Elf(payload)
        self.assertEqual(len(log), 3)
        for a, b in zip(self.fixture.sections, modified.sections):
            if a['type'] not in (4, 8):
                self.assertEqual(self.fixture.payload(a), modified.payload(b))
        for spec in specs:
            row = next(x for x in modified.relocations if x['section'] == spec['section']
                       and x['offset'] == spec['offset'])
            self.assertEqual(modified.symbols[row['symbol_index']]['name'], spec['replacement_function'])
            self.assertEqual(row['addend'], 0)
        # Unselected RELA and every machine-code byte must remain identical.
        selected = {(s['section'], s['offset']) for s in specs}
        before = [x for x in self.fixture.relocations if (x['section'], x['offset']) not in selected]
        after = [x for x in modified.relocations if (x['section'], x['offset']) not in selected]
        self.assertEqual(before, after)
        result = r.compare(self.fixture, modified)
        self.assertEqual([x['category'] for x in result['differences']], ['relocations'])

    def test_redirection_rejects_stale_plan_and_cfi_mismatch(self):
        variants = []
        a = self.specs(); a[0]['input_sha256'] = '0' * 64; variants.append(a)
        a = self.specs(); a[0]['replacement_function'] = 'incompatible_callback'; variants.append(a)
        a = self.specs(); a[0]['offset'] += 1; variants.append(a)
        a = self.specs(); a[0]['original_function'] = 'state_store'; variants.append(a)
        a = self.specs(); a[0]['type'] = 283; variants.append(a)
        a = self.specs(); a.append(copy.deepcopy(a[0])); variants.append(a)
        for specs in variants:
            with self.subTest(specs=specs), self.assertRaises(r.AuditError):
                r.redirect_function_relocations(self.fixture, specs)

    def test_signature_status_does_not_claim_validity(self):
        fake = b'FAKE-NOT-A-REAL-SIGNATURE'
        trailer = struct.pack('>BBBBB3sI', 0, 0, 2, 0, 0, b'\0' * 3, len(fake))
        status = r.signature_state(self.fixture.data + fake + trailer + r.SIG_MAGIC)
        self.assertTrue(status['appended_marker_present'])
        self.assertEqual(status['cryptographic_verification'], 'NOT_PERFORMED')
        self.assertEqual(status['validity_for_modified_artifact'], 'MUST_NOT_BE_INHERITED')
        with self.assertRaises(r.AuditError):
            r.signature_state(r.SIG_MAGIC)

    def test_oem_inventory_and_exact_callback_sites(self):
        elf = r.Elf.read(r.OEM)
        self.assertEqual(r.sha(elf.data), r.OEM_SHA256)
        inv = r.inventory(elf)
        self.assertEqual((inv['function_count'], inv['relocation_count'], inv['import_count'], inv['version_count']),
                         (85, 1414, 84, 85))
        self.assertEqual(inv['module_namespaces'], ['PWM', 'PWM', 'PWM'])
        plan = r.callback_plan(elf)
        self.assertEqual([c['relocation']['offset'] for c in plan['callbacks']], [8, 0x1d8, 0x218])
        self.assertEqual([c['function_prefix4_hex'] for c in plan['callbacks']],
                         ['7cc8f8c7', '971ac495', '971ac495'])
        self.assertEqual([c['offset'] for c in plan['attribute_group']['probe_group_address']], [0x1030, 0x1034])
        self.assertFalse(inv['btf_present'])

    def test_oem_actual_immutable_relink_pipeline(self):
        before = r.OEM.read_bytes()
        result = r.audit(r.OEM, self.root / 'oem-run', self.linker, self.objcopy)
        self.assertTrue(result['input_unchanged'])
        self.assertFalse(result['raw_ld_r_preserves_all_protected_material'])
        self.assertTrue(result['protected_relink_passed'])
        self.assertTrue(result['callable_relink_passed'])
        self.assertFalse(result['adapter_attached'])
        self.assertFalse(result['runtime_verified'])
        self.assertEqual(r.OEM.read_bytes(), before)
        output = self.root / 'oem-run'
        self.assertFalse(list(output.glob('*.ko')))
        self.assertFalse(list(output.glob('*.zip')))
        with self.assertRaises(r.AuditError):
            r.audit(r.OEM, output, self.linker, self.objcopy)

    def test_audit_refuses_bad_identity_and_outside_directory(self):
        output = self.root / 'identity-run'
        with self.assertRaises(r.AuditError):
            r.audit(r.OEM, output, self.linker, self.objcopy, expected_sha='0' * 64)
        self.assertFalse(output.exists())
        with self.assertRaises(r.AuditError):
            r.audit(r.OEM, Path('/tmp/not-an-authorized-output'), self.linker, self.objcopy)

    def test_compile_callback_kcfi_normalization_evidence(self):
        source = r.ROOT / 'kernel/eta_fan_policy/driver-adapter/callback_abi_probe.c'
        output = self.root / 'callback-types.analysis.o'
        args = [self.clang, '--target=aarch64-linux-gnu', '-ffreestanding', '-fno-builtin',
                '-O2', '-fsanitize=kcfi', '-Xclang',
                '-fsanitize-cfi-icall-experimental-normalize-integers',
                '-fno-stack-protector', '-mbranch-protection=pac-ret',
                '-DETA_FAN_OFFLINE_ABI_PROBE=1', '-c', str(source), '-o', str(output)]
        r.command(args)
        original, probe = r.Elf.read(r.OEM), r.Elf.read(output)
        for a, b in [('oplus_fan_probe', 'eta_abi_probe'), ('level_store', 'eta_abi_level_store'),
                     ('state_store', 'eta_abi_state_store')]:
            self.assertEqual(original.prefix4(original.unique_symbol(a, 2)),
                             probe.prefix4(probe.unique_symbol(b, 2)))


if __name__ == '__main__':
    unittest.main(verbosity=2)
