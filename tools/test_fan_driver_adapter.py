#!/usr/bin/env python3
"""Offline target BTF + actual incremental relink + real adapter host tests.

No driver is loaded or executed. Host harness replaces EVERY kernel/OEM API.
All generated files stay in the driver-relink analysis directory.
"""
from __future__ import annotations
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock

import relink_fan_driver as r
import gen_fan_target_abi as abi
import build_fan_driver_candidate as b


class AdapterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='adapter-tests-',dir=r.ANALYSIS)
        cls.root=Path(cls.temp.name)
        cls.result=b.build(cls.root/'candidate')
        cls.original=r.Elf.read(r.OEM)
        cls.candidate=r.Elf.read(cls.result['candidate'])
        cls.proof=json.loads((cls.root/'candidate/contribution-comparison.json').read_text())
        cls.crcs=b.donor_versions()
        cls.btf=abi.Btf((abi.INPUT/'vmlinux.btf').read_bytes())

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def comparison(self,payload):
        return b.contribution_check(self.original,r.Elf(payload),self.proof['approved_relocation_edits'],self.crcs)

    def mutate(self,offset,blob):
        data=bytearray(self.candidate.data);data[offset:offset+len(blob)]=blob;return data

    def test_real_adapter_and_policy_are_present(self):
        x=self.candidate
        for name in ['eta_adapter_probe','eta_adapter_capture_group','eta_policy_work','eta_adapter_parse_config','eta_fan_policy_step','eta_fan_normal_step']:
            symbol=x.unique_symbol(name,2)
            self.assertTrue(x.sections[symbol['shndx']]['name'].startswith('.eta_'))
            self.assertGreater(symbol['size'],8)
        self.assertTrue(self.result['adapter_and_policy_linked'])
        self.assertFalse(self.result['release_eligible'])
        self.assertFalse(self.result['runtime_verified'])

    def test_original_contribution_mapping_and_exact_edits(self):
        self.assertTrue(self.proof['passed'])
        self.assertEqual(len(self.proof['original_functions']),85)
        self.assertEqual(self.proof['preserved_original_relocations'],1406)
        self.assertEqual(len(self.proof['approved_relocation_edits']),8)
        self.assertTrue(all(x['input_offset']==0 and x['output_offset']==0 for x in self.proof['original_section_contributions']))
        self.assertEqual(r.sha(r.OEM.read_bytes()),r.OEM_SHA256)
        for name in ['.data','.rodata','.gnu.linkonce.this_module','.init.text','.exit.text']:
            self.assertEqual(self.original.payload(self.original.by_name[name]),self.candidate.payload(self.candidate.by_name[name]))
        self.assertEqual(self.candidate.by_name['.gnu.linkonce.this_module']['size'],1600)

    def test_all_old_versions_and_four_proven_additions(self):
        ov=r.versions(self.original);cv=r.versions(self.candidate)
        self.assertEqual(cv['legacy'][:85],ov['legacy'])
        self.assertEqual(cv['legacy'],cv['extended'])
        self.assertEqual(len(cv['legacy']),89)
        self.assertEqual({x['name']:x['crc'] for x in cv['legacy'][85:]},self.crcs)
        self.assertEqual(self.crcs,{'power_supply_get_by_name':'0x24d7ec04','power_supply_get_property':'0x094fa0fa','power_supply_put':'0x0b380f7c','sysfs_remove_group':'0x04d7c425'})
        self.assertFalse(r.signature_state(self.candidate.data)['appended_marker_present'])

    def test_wrong_approved_callback_is_detected(self):
        rel=next(x for x in self.candidate.relocations if x['section']=='.data' and x['offset']==8)
        wrong=self.candidate.unique_symbol('eta_adapter_remove',2)
        result=self.comparison(self.mutate(rel['table_offset']+8,struct.pack('<Q',(wrong['index']<<32)|257)))
        self.assertFalse(result['passed'])
        self.assertTrue(any('function' in x['category'] for x in result['differences']))

    def test_unapproved_original_relocation_is_detected(self):
        approved={(x['section'],x['offset']) for x in self.proof['approved_relocation_edits']}
        rel=next(x for x in self.candidate.relocations if x['section']=='.text' and (x['section'],x['offset']) not in approved)
        result=self.comparison(self.mutate(rel['table_offset']+16,struct.pack('<q',rel['addend']+4)))
        self.assertFalse(result['passed'])
        self.assertTrue(any('preserved RELA' in x['category'] for x in result['differences']))

    def test_original_function_and_cfi_bytes_detected(self):
        f=self.candidate.unique_symbol('eta_original_level_store',2)
        base=self.candidate.sections[f['shndx']]['offset']+f['value']
        for offset in (base,base-4):
            with self.subTest(offset=offset):
                result=self.comparison(self.mutate(offset,b'\0'*4))
                self.assertFalse(result['passed'])
                self.assertTrue(any('machine code' in x['category'] or 'prefix4' in x['category'] for x in result['differences']))

    def test_original_this_module_and_modinfo_detected(self):
        for name in ['.gnu.linkonce.this_module','.modinfo']:
            offset=self.candidate.by_name[name]['offset']
            byte=bytes([self.candidate.data[offset]^1])
            self.assertFalse(self.comparison(self.mutate(offset,byte))['passed'])

    def test_old_and_new_crc_corruption_rejected(self):
        for record in (0,85):
            offset=self.candidate.by_name['__versions']['offset']+record*64
            with self.subTest(record=record),self.assertRaises(r.AuditError):
                self.comparison(self.mutate(offset,struct.pack('<Q',0xdeadbeef)))

    def test_cfi_twelve_indirect_callbacks_match(self):
        checks=json.loads((self.root/'candidate/cfi-validation.json').read_text())
        self.assertEqual(len(checks),12)
        self.assertTrue(all(x['match'] for x in checks))

    def test_btf_offsets_and_prototypes_reject_guesses(self):
        bt=self.btf
        self.assertEqual(bt.named('delayed_work',4)['size'],104)
        for fields in [[('timer',40,'struct timer_list')],[('cpu',80,'u32')]]:
            with self.subTest(fields=fields),self.assertRaises(ValueError):
                bt.verify_struct('delayed_work',104,fields)
        with self.assertRaises(ValueError):
            bt.verify_struct('mutex',32,[])
        # Actual 6.12 API's fourth arg is a name, NOT the bool used in some
        # guessed add_action_or_reset declarations. Generator caught this.
        with self.assertRaises(ValueError):
            bt.func('__devm_add_action','(*struct device,*(*void)->void,*void,u8)->s32')

    def test_bad_target_identity_refuses_header_generation(self):
        out=self.root/'wrong-target.h';report=self.root/'wrong-target.json'
        with mock.patch.object(abi,'BTF_SHA','0'*64),self.assertRaises(ValueError):
            abi.generate(out,report)
        self.assertFalse(out.exists())
        with mock.patch.object(abi,'CONFIG_SHA','0'*64),self.assertRaises(ValueError):
            abi.generate(out,report)
        self.assertFalse(out.exists())

    def test_host_real_adapter_execution(self):
        sources=[b.SOURCE/'test_adapter_host.c',b.SOURCE/'config_protocol.c',r.ROOT/'kernel/eta_fan_policy/policy_core.c']
        for tag,flags in [('optimized',['-O2']),('ubsan-trap',['-O1','-fsanitize=undefined','-fsanitize-trap=all'])]:
            executable=self.root/('host-'+tag)
            r.command(['clang',*flags,'-g','-Wall','-Wextra','-Werror',*[str(p) for p in sources],'-o',str(executable)])
            result=r.command([str(executable)])
            self.assertIn('PASS',result['stdout']);self.assertIn('NOT device verification',result['stdout'])

    def test_control_test_profile_is_compiled_not_runtime_evidence(self):
        result=b.build(self.root/'control-test-profile','CONTROL_TEST_PENDING')
        path=Path(result['candidate']);data=path.read_bytes()
        self.assertTrue(path.name.endswith('CONTROL_TEST_PENDING.candidate.ko'))
        self.assertIn(b'CONTROL_TEST_PENDING;CANDIDATE;BTF=',data)
        self.assertIn(b'candidateLabel=CONTROL_TEST_PENDING',data)
        self.assertEqual(result['candidate_label'],'CONTROL_TEST_PENDING')
        self.assertFalse(result['runtime_verified'])
        self.assertFalse(result['production_runtime_verified'])
        self.assertFalse(result['load_report_generated'])
        self.assertFalse(result['release_eligible'])
        self.assertEqual(self.result['candidate_label'],'NONRELEASE_UNLOADED')
        self.assertTrue(Path(self.result['candidate']).name.endswith('NONRELEASE_UNLOADED.ko'))
        output=self.root/'bad-label'
        with self.assertRaises(r.AuditError):
            b.build(output,'PRODUCTION_VERIFIED')
        self.assertFalse(output.exists())

    def test_candidate_builder_cannot_overwrite_or_escape(self):
        with self.assertRaises(r.AuditError):
            b.build(self.root/'candidate')
        with self.assertRaises(r.AuditError):
            b.build(Path('/tmp/disallowed-fan-candidate'))


if __name__=='__main__':
    unittest.main(verbosity=2)
